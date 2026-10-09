package me.alex4386.typhon.engine.dike;

import me.alex4386.typhon.engine.config.ConfigCopy;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.Objects;
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.deformation.DikeGeometry;
import me.alex4386.typhon.engine.dike.DikeEvents.StallReason;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.magma.MagmaCommands;
import me.alex4386.typhon.engine.magma.MeltDensity;
import me.alex4386.typhon.engine.magma.conduit.ConduitModel;
import me.alex4386.typhon.engine.random.SimRandom;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.UnitSource;
import me.alex4386.typhon.engine.world.WorldModel;
import java.util.TreeSet;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;

/**
 * Dike nucleation and propagation from a magma chamber.
 *
 * <p><b>Nucleation.</b> A dike starts when the chamber walls fail in tension: at an overpressure of twice
 * the rock's tensile strength, where the hoop stress on a pressurised sphere reaches it (Tait, Jaupart &amp;
 * Vergniolle 1989; {@link me.alex4386.typhon.engine.magma.MagmaChamber#ruptureOverpressureMPa()}). The
 * magma the walls can no longer hold leaves through it. A molten summit conduit fails first (at a lower
 * pressure) and vents at the summit instead. Where on the roof it starts is the only random choice.
 *
 * <p><b>Geometry.</b> A dike leaves the chamber from its roof (hoop tension on a pressurised sphere in a
 * gravitational stress field is greatest at its top) and is a vertical crack of height {@code H} (roof to
 * tip) and breadth {@code b = min(H, D)}: a crack driven from a source grows about as broad as it is high
 * (penny-shaped), and is fed over at most the chamber's diameter {@code D} (Rubin 1995; Rivalta et al.
 * 2015). Its driving pressure is chamber overpressure plus buoyancy, {@code ΔP = P + ∫(ρ_rock − ρ_magma) g dz}
 * over the magma column from the roof to the tip. Bubble-free basalt is denser than the crust and must be
 * pushed; but the melt's CO₂ and H₂O beyond their solubility at the local (lithostatic plus water) pressure
 * are gas, so the magma lightens as it rises and is buoyant in the shallow crust (closed-system equilibrium
 * exsolution: bubbles cannot leave a dike rising at ~0.1 m/s). Its opening is the
 * elastic estimate for a pressurised crack whose shorter dimension is {@code b},
 * {@code w = 2(1−ν) ΔP̄ b / μ} under the net pressure averaged over its height, {@code ΔP̄} (Pollard 1987), and the tip advances at the laminar slot velocity
 * {@code v = w² (ΔP / H) / (12 η)} (Lister &amp; Kerr 1991) — basaltic dikes rise at ~0.1–5 m/s, viscous
 * silicic dikes far slower. The intruded volume {@code w · b · H} is drawn from the chamber, lowering its
 * overpressure.
 *
 * <p><b>Arrest.</b> The dike stalls when the stress intensity at its tip, {@code K = ΔP √(π b / 2)}, falls
 * below the rock's fracture toughness (it can no longer break rock), or when it freezes: a sheet of
 * half-width {@code w/2} solidifies against wall rock at temperature {@code T₀} in
 * {@code t_s = (w/2)² / (4 κ λ²)}, {@code λ} from the Stefan condition
 * {@code L √π / (c (T_m − T₀)) = e^{−λ²} / (λ (1 + erf λ))} (Turcotte &amp; Schubert 2002, §4.18); magma
 * that takes longer than that to rise through the dike ({@code H / v > t_s}) freezes in it. A stalled dike
 * stays molten for that time {@code t_s} and connected to the chamber: if the chamber's pressure (or a
 * recharge) drives it on before then, it resumes; otherwise it solidifies into an intrusion.
 *
 * <p><b>Path.</b> Dikes rise vertically at depth. Within the edifice, gravitational stresses rotate
 * them down the topographic slope ({@code −∇h}, weighted by {@code exp(−depth / scale)}), so dikes
 * starting beneath a summit tend to surface on the flanks (Muller et al. 2001). A deterministic,
 * mean-reverting (Ornstein–Uhlenbeck) perturbation stands for unresolved host-rock heterogeneity.
 *
 * <p><b>Fissure.</b> On reaching the surface the dike's top edge opens a fissure as long as the dike is
 * broad and as wide as its opening. It strikes perpendicular to the least compressive stress: radial around
 * the inflated chamber (circumferential hoop tension), else down the edifice's slope (the load's stress is
 * radial on a cone; Acocella &amp; Neri 2009), else perpendicular to the regional σ₃ when one is given; only
 * with none of these is the strike undetermined, and drawn at random.
 *
 * <p>Positions and depths are in metres.
 *
 * <p>References: Rubin (1995), Annu. Rev. Earth Planet. Sci. 23:287-336; Pollard (1987), Geol. Assoc. Canada Spec.
 * Pap. 34; Lister &amp; Kerr (1991), J. Geophys. Res. 96; Rivalta et al. (2015), Tectonophysics 638; Turcotte &amp;
 * Schubert (2002), Geodynamics; Muller et al. (2001), J. Geophys. Res. 106; Acocella &amp; Neri (2009),
 * Tectonophysics 471. See {@code docs/references.md}.
 */
public final class DikePropagation implements Subsystem {
    static final double GRAVITY = 9.81;

    private final DikeConfig config;
    private final DikeMagmaSource magma;
    private final TerrainModel terrain;
    private final String volcanoId;

    private final List<Dike> dikes = new ArrayList<>();
    private int nextId = 1;
    private int forcedPending;
    /** Dikes the user asked to stop, arrested at the next step. */
    private final TreeSet<Integer> pendingArrests = new TreeSet<>();
    /** Dike nucleation blocked by the user. */
    private boolean nucleationBlocked;

    private Consumer<List<Point3>> hypocenterListener;
    private me.alex4386.typhon.engine.volcano.GroundCoupling ground = me.alex4386.typhon.engine.volcano.GroundCoupling.NONE;

    /**
     * The ground model intruded sheets heat (transient: re-attach when the engine is built).
     * Default {@link me.alex4386.typhon.engine.volcano.GroundCoupling#NONE}.
     */
    public void setGround(me.alex4386.typhon.engine.volcano.GroundCoupling ground) {
        this.ground = ground == null ? me.alex4386.typhon.engine.volcano.GroundCoupling.NONE : ground;
    }
    private UnitSource units = UnitSource.UNATTRIBUTED;

    /**
     * @param terrain ground for slopes and fissure elevation; may be {@code null} (a flat surface
     *     {@link DikeMagmaSource#chamberDepthM()} above the chamber)
     */
    public DikePropagation(DikeConfig config, DikeMagmaSource magma, TerrainModel terrain) {
        config.validate();
        this.config = config;
        this.magma = Objects.requireNonNull(magma, "magma");
        this.terrain = terrain;
        this.volcanoId = magma.volcanoId();
        if (terrain != null) this.units = UnitSource.typed(terrain.world());
    }

    @Override
    public boolean reconfigure(Object c) {
        if (!(c instanceof DikeConfig n)) return false;
        DikeConfig next = n.copy();
        next.validate();
        ConfigCopy.into(next, config);
        return true;
    }

    @Override
    public String id() {
        return "dike:" + volcanoId;
    }

    @Override
    public double periodSeconds() {
        return config.stepPeriodSeconds;
    }

    /** Longest step while a dike rises (s); propagation sub-steps by distance inside it. */
    public static final double RISING_STEP_SECONDS = 20;

    @Override
    public double maxStepSeconds() {
        if (activeCount() > 0 || forcedPending > 0 || moltenCount() > 0) return RISING_STEP_SECONDS;
        // a dike nucleates only when the chamber walls fail; the chamber bounds its own step towards that
        return Double.POSITIVE_INFINITY;
    }

    @Override
    public void registerCommands(CommandBus bus) {
        bus.register(DikeCommands.ForceDike.class, c -> {
            if (c.volcanoId().equals(volcanoId)) forcedPending++;
        });
        bus.register(DikeCommands.ArrestDike.class, c -> {
            if (c.volcanoId().equals(volcanoId)) arrest(c.dikeId());
        });
        bus.register(DikeCommands.RemoveDike.class, c -> {
            if (c.volcanoId().equals(volcanoId)) remove(c.dikeId());
        });
        bus.register(DikeCommands.BlockDikes.class, c -> {
            if (c.volcanoId().equals(volcanoId)) nucleationBlocked = c.blocked();
        });
        // a forced eruption without a molten conduit to a crater needs a way up: a dike
        bus.register(MagmaCommands.StartEruption.class, c -> {
            if (c.volcanoId().equals(volcanoId) && !magma.erupting() && !magma.conduitToSurface()) forcedPending++;
        });
    }

    /** Stops the propagating dike {@code dikeId} at the next step; false if there is no such dike. */
    public boolean arrest(int dikeId) {
        Dike dike = find(dikeId);
        if (dike == null || !(dike.propagating() || dike.molten())) return false;
        pendingArrests.add(dikeId);
        return true;
    }

    /**
     * Deletes the dike {@code dikeId} (arresting it first if it is still rising); false if there is no
     * such dike. Its fissure, if any, stops being a vent.
     */
    public boolean remove(int dikeId) {
        Dike dike = find(dikeId);
        if (dike == null) return false;
        if (dike.propagating() || dike.molten()) pendingArrests.add(dikeId);
        dike.removed = true;
        return true;
    }

    /** Deletes the dike that opened the fissure {@code ventId}; false if no recorded dike opened it. */
    public boolean removeFissure(String ventId) {
        for (Dike d : dikes) {
            if (d.fissure != null && d.fissure.id().equals(ventId)) return remove(d.id);
        }
        return false;
    }

    /** Blocks or allows dike nucleation at wall rupture (forced dikes still start). */
    public void setNucleationBlocked(boolean blocked) {
        this.nucleationBlocked = blocked;
    }

    public boolean nucleationBlocked() {
        return blocked();
    }

    /** No new dikes: the definition's {@code dikes.blocked}, or the legacy runtime block. */
    private boolean blocked() {
        return config.blocked || nucleationBlocked;
    }

    private Dike find(int dikeId) {
        for (Dike d : dikes) if (d.id == dikeId) return d;
        return null;
    }

    /** Nucleates a dike at the next step (same as {@link DikeCommands.ForceDike}). */
    /**
     * Receives the hypocentres of tip fracturing each time a dike advances (e.g.
     * {@code seismicity::queueInducedVt}). Not persisted: re-attach when building the engine.
     */
    public void setHypocenterListener(Consumer<List<Point3>> listener) {
        this.hypocenterListener = listener;
    }

    public void forceDike() {
        forcedPending++;
    }

    @Override
    public void step(StepContext context) {
        SimRandom random = context.random();
        double stepDt = context.dtSeconds();

        while (forcedPending > 0) {
            forcedPending--;
            start(context);
        }
        for (int id : pendingArrests) {
            Dike dike = find(id);
            if (dike == null || !(dike.propagating() || dike.molten())) continue;
            if (dike.propagating()) dike.stallTime = context.time();
            dike.status = DikeStatus.STALLED;
            dike.frozen = true;
            emplaceIntrusion(dike, context.time());
            context.outbox().emit(new DikeEvents.DikeStalled(context.time(), volcanoId, dike.id, dike.tip(), dike.depth,
                    dike.volume, StallReason.ARRESTED));
        }
        pendingArrests.clear();

        if (!blocked() && magma.ruptureExcessM3() > 0) {
            // Ruptured walls: the magma they could not hold leaves through a dike (a rising one, or a new one).
            // a rising dike takes it, else a stalled one still molten (an open crack to the chamber)
            Dike carrier = null;
            for (Dike dike : dikes) {
                if (dike.propagating()) {
                    carrier = dike;
                    break;
                }
                if (dike.molten()) carrier = dike;
            }
            if (carrier == null && activeCount() < config.maxConcurrentDikes) carrier = start(context);
            if (carrier != null) carrier.volume += magma.takeRuptureExcess();
        }

        for (Dike dike : dikes) {
            if (dike.propagating()) {
                advance(dike, stepDt, context, false);
            } else if (dike.molten()) {
                if (context.time() - dike.stallTime >= solidificationSeconds(dike.opening, wallRockC(dike))) {
                    solidify(dike, context);
                } else {
                    dike.status = DikeStatus.PROPAGATING; // try to drive it on
                    advance(dike, stepDt, context, true);
                }
            }
        }
        prune();
    }

    /** Depth (m) of the chamber roof the dikes leave from: the centre depth less the chamber's radius. */
    double roofDepthM() {
        double depth = magma.chamberDepthM();
        double radius = magma.chamberRadiusM();
        if (!(radius > 0)) return depth;
        // numerical: a chamber reaching (nearly) to the surface still leaves a roof to rise through
        return Math.max(Math.min(depth, MIN_ROOF_DEPTH_M), depth - radius);
    }

    /** Diameter (m) of the chamber feeding the dikes, or +∞ for a point source. */
    double sourceDiameterM() {
        double radius = magma.chamberRadiusM();
        return radius > 0 ? 2 * radius : Double.POSITIVE_INFINITY;
    }

    private Dike start(StepContext context) {
        Point3 center = magma.chamberCenter();
        // from the roof's apex, straight above the chamber centre
        double x = center.x();
        double z = center.z();
        double depth = roofDepthM();
        double surface = surfaceZ(x, z, center.y() + magma.chamberDepthM());
        Dike dike = new Dike(nextId++, context.time(), x, z, surface, depth);
        dikes.add(dike);
        context.outbox().emit(new DikeEvents.DikeStarted(context.time(), volcanoId, dike.id, dike.origin(),
                magma.overpressureMPa()));
        return dike;
    }

    /** The stalled sheet has solidified: it becomes an intrusion. */
    private void solidify(Dike dike, StepContext context) {
        dike.frozen = true;
        emplaceIntrusion(dike, context.time());
        context.outbox().emit(new DikeEvents.DikeSolidified(context.time(), volcanoId, dike.id, dike.tip(), dike.depth,
                dike.volume));
    }

    /**
     * Advances a rising dike through {@code stepDt}. {@code resuming}: the dike had stalled and is still
     * molten; if it still cannot move it stays stalled, as it was.
     */
    private void advance(Dike dike, double stepDt, StepContext context, boolean resuming) {
        SimRandom random = context.random();
        double remaining = stepDt;
        double travelled = 0;
        StallReason stall = null;
        List<Point3> hypocenters = new ArrayList<>();

        while (remaining > 0 && dike.depth > 0) {
            double height = Math.max(0, dike.chamberDepth - dike.depth);
            double characteristic = Math.max(height, config.minCharacteristicHeight);
            double[] buoyancy = buoyancy(dike.x, dike.z, dike.depth, dike.chamberDepth);
            double drive = magma.overpressureMPa() + buoyancy[0];
            // the walls open under the net pressure averaged over the crack, not its value at the tip
            double wallDrive = magma.overpressureMPa() + buoyancy[1];
            double breadth = Math.min(characteristic, sourceDiameterM());
            // the tip breaks rock only while its stress intensity exceeds the rock's toughness
            if (!(drive > 0) || !(wallDrive > 0)
                    || drive * Math.sqrt(Math.PI * breadth / 2) < config.fractureToughnessMPaSqrtM) {
                stall = StallReason.INSUFFICIENT_PRESSURE;
                break;
            }

            double opening = 2 * (1 - config.poissonRatio) * wallDrive * 1e6 * breadth / config.shearModulusPa;
            double viscosity = StrictMath.pow(10, magma.viscosityLog10());
            double speed = Math.min(config.maxSpeed, opening * opening * (drive * 1e6 / characteristic) / (12 * viscosity));
            dike.speed = speed;
            dike.opening = opening;
            if (speed < freezeSpeed(opening, characteristic, wallRockC(dike))) {
                stall = StallReason.FROZE;
                break;
            }

            double step = Math.min(speed * remaining, config.maxSubstepMeters);
            if (step <= 1e-9) break;
            remaining -= step / speed;

            double decay = StrictMath.exp(-step / config.headingCorrelationLength);
            double kick = config.headingNoise * Math.sqrt(1 - decay * decay);
            dike.noiseX = dike.noiseX * decay + random.nextGaussian() * kick;
            dike.noiseZ = dike.noiseZ * decay + random.nextGaussian() * kick;
            double[] slope = slope(dike.x, dike.z, dike.surfaceStartZ);
            double shallow = StrictMath.exp(-dike.depth / config.edificeDepthScale);
            double hx = -config.deflectionStrength * shallow * slope[0] + dike.noiseX;
            double hz = -config.deflectionStrength * shallow * slope[1] + dike.noiseZ;
            double norm = Math.sqrt(1 + hx * hx + hz * hz);

            double rise = step / norm;
            double fraction = 1;
            if (rise > dike.depth) {
                fraction = dike.depth / rise;
                rise = dike.depth;
            }
            double moveX = step * fraction * hx / norm;
            double moveZ = step * fraction * hz / norm;
            double depthBefore = dike.depth;
            dike.depth = Math.max(0, dike.depth - rise);
            dike.x += moveX;
            dike.z += moveZ;
            double horizontal = Math.hypot(moveX, moveZ);
            if (horizontal > 1e-9) {
                dike.travelX = moveX / horizontal;
                dike.travelZ = moveZ / horizontal;
            }
            double advanced = step * fraction;
            travelled += advanced;

            double newHeight = dike.chamberDepth - dike.depth;
            dike.strikeLength = Math.min(newHeight, sourceDiameterM());
            // The newly opened sheet (rise × strike, opening thick) cools into its host rock.
            ground.addIntrusionHeat(dike.x, dike.z, 0.5 * (depthBefore + dike.depth), rise * dike.strikeLength,
                    opening, magma.temperatureC());
            double target = opening * dike.strikeLength * newHeight;
            if (target > dike.volume) {
                magma.withdraw(target - dike.volume);
                dike.volume = target;
            }

            // tip fracturing along the dike's leading edge: anywhere along its breadth, at the depths just broken
            int count = random.nextPoisson(config.hypocentersPerKm * advanced / 1000);
            double strikeAngle = count > 0 ? strikeOf(dike, null) : 0;
            double sx = StrictMath.cos(strikeAngle);
            double sz = StrictMath.sin(strikeAngle);
            for (int i = 0; i < count; i++) {
                double along = (random.nextDouble() - 0.5) * dike.strikeLength;
                double hxPos = dike.x + along * sx;
                double hzPos = dike.z + along * sz;
                double hDepth = dike.depth + random.nextDouble() * (depthBefore - dike.depth);
                hypocenters.add(new Point3(hxPos, surfaceZ(hxPos, hzPos, dike.surfaceStartZ) - hDepth, hzPos));
            }
        }

        dike.tipElevation = surfaceZ(dike.x, dike.z, dike.surfaceStartZ) - dike.depth;
        if (resuming && travelled > 0) {
            context.outbox().emit(new DikeEvents.DikeResumed(context.time(), volcanoId, dike.id, dike.tip(), dike.depth));
        }
        if (travelled > 0) {
            if (hypocenterListener != null && !hypocenters.isEmpty()) hypocenterListener.accept(hypocenters);
            context.outbox().emit(new DikeEvents.DikeAdvanced(context.time(), volcanoId, dike.id, dike.tip(), dike.depth,
                    dike.speed, dike.opening, dike.volume, hypocenters));
        }

        if (dike.depth <= 0) {
            openFissure(dike, context);
            emplaceIntrusion(dike, context.time());
        } else if (stall != null) {
            dike.status = DikeStatus.STALLED;
            if (resuming && travelled == 0) return; // still stalled since dike.stallTime
            // still molten: it solidifies after t_s unless driven on first
            dike.stallTime = context.time();
            context.outbox().emit(new DikeEvents.DikeStalled(context.time(), volcanoId, dike.id, dike.tip(), dike.depth,
                    dike.volume, stall));
        }
    }

    /**
     * Records the frozen dike as {@link DepositType#INTRUSION} rock in the world model: in every
     * column its path crossed (nucleation point to tip), from the chamber depth up to the tip depth
     * (for a dike that reached the surface, up to one column-width below the surface, leaving the
     * vent itself open). Dikes are thinner than a column; the layer marks where the intrusion is so
     * cross-sections, heat and groundwater can see it.
     */
    private void emplaceIntrusion(Dike dike, double time) {
        if (terrain == null) return;
        WorldModel world = terrain.world();
        double l = world.spec().metersPerColumn();
        Material rock = intrusiveRock(magma.silicaWt());
        int unit = units.unit(DepositType.INTRUSION, time, Double.NaN);
        double dx = dike.x - dike.startX;
        double dz = dike.z - dike.startZ;
        int steps = Math.max(1, (int) Math.ceil(2 * Math.hypot(dx, dz) / l));
        TreeSet<Long> done = new TreeSet<>();
        for (int s = 0; s <= steps; s++) {
            double f = (double) s / steps;
            int x = (int) Math.floor((dike.startX + f * dx) / l);
            int z = (int) Math.floor((dike.startZ + f * dz) / l);
            if (!done.add(((long) x << 32) | (z & 0xffffffffL))) continue;
            double surface = world.surfaceZ(x, z);
            if (Double.isNaN(surface)) continue;
            double top = surface - Math.max(dike.depth, l);
            double bottom = surface - dike.chamberDepth;
            if (top > bottom) world.fill(x, z, bottom, top, rock, unit);
        }
    }

    /** Coarse-grained rock a dike of {@code silicaWt} crystallises into. */
    static Material intrusiveRock(double silicaWt) {
        if (silicaWt < 53) return MaterialTable.GABBRO;
        if (silicaWt < 63) return MaterialTable.ANDESITE;
        return MaterialTable.GRANITE;
    }

    /** Attributes the frozen dike's intrusion layers to the volcano's current eruption episode. */
    public void setUnits(UnitSource units) {
        this.units = units;
    }

    private void openFissure(Dike dike, StepContext context) {
        double surface = surfaceZ(dike.x, dike.z, dike.surfaceStartZ);
        double angle = strikeOf(dike, context.random());
        // the dike's top edge: as long as the dike is broad, as wide as it is open
        dike.fissure = VentSite.fissure(volcanoId + "-dike-" + dike.id, new Point3(dike.x, surface, dike.z), angle,
                dike.strikeLength, dike.opening / 2);
        dike.status = DikeStatus.ERUPTED;
        dike.tipElevation = surface;
        context.outbox().emit(new DikeEvents.FissureOpened(context.time(), volcanoId, dike.id, dike.fissure, dike.volume));
    }

    /**
     * Strike of the dike plane, perpendicular to the least compressive stress: radial from the inflated
     * chamber's axis (its hoop tension), else down the slope of the edifice (radial on a cone), else
     * perpendicular to the regional σ₃, else along the dike's travel; with none of these, undetermined
     * ({@code random} draws it; {@code null} gives 0).
     */
    private double strikeOf(Dike dike, SimRandom random) {
        if (dike.fissure != null) return dike.fissure.fissureAngleRad();
        Point3 center = magma.chamberCenter();
        double dx = dike.x - center.x();
        double dz = dike.z - center.z();
        if (Math.hypot(dx, dz) >= DIRECTION_EPS_M) return StrictMath.atan2(dz, dx);
        double[] slope = slope(dike.x, dike.z, dike.surfaceStartZ);
        if (Math.hypot(slope[0], slope[1]) >= SLOPE_EPS) return StrictMath.atan2(-slope[1], -slope[0]);
        if (Double.isFinite(config.regionalSigma3AzimuthDeg)) {
            return Math.toRadians(config.regionalSigma3AzimuthDeg) + Math.PI / 2;
        }
        if (dike.travelX != 0 || dike.travelZ != 0) return StrictMath.atan2(dike.travelZ, dike.travelX);
        return random == null ? 0 : random.nextDouble() * Math.PI;
    }

    /** Depths along a dike at which its wall rock is sampled. */
    static final int WALL_SAMPLES = 8;

    /**
     * Mean temperature (°C) of the rock a dike has risen through, from its tip down to the chamber roof: the
     * ground model's where it reaches (warmed by earlier dikes and flows: later dikes freeze less, as at
     * Krafla), the configured geotherm below it.
     */
    double wallRockC(Dike dike) {
        double sum = 0;
        for (int i = 0; i < WALL_SAMPLES; i++) {
            double depth = dike.depth + (i + 0.5) / WALL_SAMPLES * (dike.chamberDepth - dike.depth);
            double t = ground.rockTemperatureC(dike.x, dike.z, depth);
            sum += Double.isFinite(t) ? t : config.surfaceTemperatureC + config.geothermalGradientCPerKm * depth / 1000;
        }
        return sum / WALL_SAMPLES;
    }

    /**
     * Slowest rise (m/s) at which magma in a dike of opening {@code w} and height {@code h} reaches its top
     * before a sheet of that thickness freezes against wall rock at {@code wallC}: {@code h / t_s} with
     * {@code t_s = (w/2)² / (4 κ λ²)} (Turcotte &amp; Schubert 2002, §4.18).
     */
    double freezeSpeed(double opening, double height, double wallC) {
        if (!(opening > 0)) return 0;
        return height / solidificationSeconds(opening, wallC);
    }

    /**
     * Time (s) for a stagnant sheet of opening {@code w} to solidify against wall rock at {@code wallC}:
     * {@code t_s = (w/2)² / (4 κ λ²)} (Turcotte &amp; Schubert 2002, §4.18); never for a wall as hot as the magma.
     */
    double solidificationSeconds(double opening, double wallC) {
        if (!(opening > 0)) return 0;
        double contrast = magma.temperatureC() - wallC;
        if (!(contrast > 0)) return Double.POSITIVE_INFINITY;
        double lambda = stefanLambda(config.magmaLatentHeat * Math.sqrt(Math.PI) / (config.specificHeat * contrast));
        double half = opening / 2;
        return half * half / (4 * config.wallRockDiffusivity * lambda * lambda);
    }

    /**
     * The root λ of {@code e^{−λ²} / (λ (1 + erf λ)) = rhs} (Stefan condition for a solidifying sheet; the
     * left side falls monotonically from +∞ to 0).
     */
    static double stefanLambda(double rhs) {
        double lo = 1e-6;
        double hi = 10;
        for (int i = 0; i < 100; i++) {
            double mid = 0.5 * (lo + hi);
            double f = StrictMath.exp(-mid * mid) / (mid * (1 + erf(mid)));
            if (f > rhs) lo = mid;
            else hi = mid;
        }
        return 0.5 * (lo + hi);
    }

    /** Error function (Abramowitz &amp; Stegun 7.1.26, |error| < 1.5e-7). */
    static double erf(double x) {
        double t = 1 / (1 + 0.3275911 * Math.abs(x));
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592)
                * t * StrictMath.exp(-x * x);
        return x >= 0 ? y : -y;
    }

    // ── Geometry helpers ──

    /** Gauss–Legendre nodes and weights on [−1, 1] for {@link #buoyancyMPa}. */
    private static final double[] GL_NODES = {
        -0.9602898564975363, -0.7966664774136267, -0.5255324099163290, -0.1834346424956498,
        0.1834346424956498, 0.5255324099163290, 0.7966664774136267, 0.9602898564975363};
    private static final double[] GL_WEIGHTS = {
        0.1012285362903763, 0.2223810344533745, 0.3137066877114911, 0.3626837833783620,
        0.3626837833783620, 0.3137066877114911, 0.2223810344533745, 0.1012285362903763};
    /** Atmospheric pressure (MPa). */
    static final double ATMOSPHERE_MPA = 0.101325;
    /** Density of the water over submerged ground (kg/m³). */
    static final double WATER_DENSITY = 1000;

    /**
     * Buoyancy (MPa) of the magma column from {@code bottomDepth} up to {@code topDepth} (m below the ground
     * at {@code (x, z)}): {@code ∫(ρ_rock − ρ_magma(P)) g dz}. The pressure is the rock load plus the air or
     * water above the ground, so {@code g dz = dP / ρ_rock} and the integral is
     * {@code ∫(1 − ρ_magma(P)/ρ_rock) dP}; it is taken over {@code ln P}, where the gas fraction varies
     * smoothly even near the surface.
     */
    double buoyancyMPa(double x, double z, double topDepth, double bottomDepth) {
        return buoyancy(x, z, topDepth, bottomDepth)[0];
    }

    /**
     * {@code {at the top, mean over the column}} (MPa): the buoyancy at the top as {@link #buoyancyMPa}, and its
     * mean over the column's height, {@code (1/H) ∫₀^H B(s) ds = ∫ (1 − s/H) b(s) ds} — the part of the net
     * pressure on the crack's walls that opens it.
     */
    double[] buoyancy(double x, double z, double topDepth, double bottomDepth) {
        if (!(bottomDepth > topDepth)) return new double[] {0, 0};
        double ambient = ambientMPa(x, z);
        double load = config.rockDensity * GRAVITY / 1e6;
        double lnTop = StrictMath.log(ambient + load * Math.max(0, topDepth));
        double lnBottom = StrictMath.log(ambient + load * bottomDepth);
        double half = 0.5 * (lnBottom - lnTop);
        double mid = 0.5 * (lnBottom + lnTop);
        double pTop = StrictMath.exp(lnTop);
        double range = StrictMath.exp(lnBottom) - pTop;
        double top = 0;
        double mean = 0;
        for (int i = 0; i < GL_NODES.length; i++) {
            double p = StrictMath.exp(mid + half * GL_NODES[i]);
            double f = GL_WEIGHTS[i] * (1 - magmaDensityAt(p) / config.rockDensity) * p;
            top += f;
            // height above the bottom s ↔ pressure: 1 − s/H = (P − P_top) / (P_bottom − P_top)
            mean += f * (p - pTop) / range;
        }
        return new double[] {top * half, mean * half};
    }

    /**
     * Density (kg/m³) of the magma at {@code pressureMPa}: H₂O up to {@code 0.411 √P} wt% and CO₂ up to
     * {@code 5·10⁻⁴ P} wt% (the chamber's and conduit's solubility laws) stay dissolved in the melt
     * ({@link MeltDensity}); the rest is an ideal gas at the magma's temperature.
     */
    double magmaDensityAt(double pressureMPa) {
        double total = magma.meltWaterWt();
        double dissolved = Math.min(total, ConduitModel.SOLUBILITY * Math.sqrt(pressureMPa));
        double co2 = Math.max(0, magma.meltCo2Wt() - ConduitModel.CO2_SOLUBILITY * pressureMPa);
        double melt = MeltDensity.meltKgPerM3(magma.silicaWt(), dissolved);
        return MeltDensity.bubbly(melt,
                MeltDensity.gasVolumePerKg(total - dissolved, co2, pressureMPa * 1e6, magma.temperatureC()));
    }

    /** Pressure (MPa) on the ground at {@code (x, z)} (m): the atmosphere, plus the water over it. */
    private double ambientMPa(double x, double z) {
        if (terrain == null) return ATMOSPHERE_MPA;
        WorldModel world = terrain.world();
        double l = world.spec().metersPerColumn();
        int cx = (int) Math.floor(x / l);
        int cz = (int) Math.floor(z / l);
        double depth = world.waterZ(cx, cz) - world.surfaceZ(cx, cz);
        return ATMOSPHERE_MPA + (depth > 0 ? WATER_DENSITY * GRAVITY * depth / 1e6 : 0);
    }


    /** Numerical: below this horizontal offset (m) from the chamber axis the radial direction is undefined. */
    static final double DIRECTION_EPS_M = 1;
    /** Numerical: below this slope the ground sets no direction. */
    static final double SLOPE_EPS = 1e-3;
    /** Numerical: shallowest roof (m) a chamber that nearly reaches the surface is given. */
    static final double MIN_ROOF_DEPTH_M = 100;

    /** Ground elevation (m) at a point (m), {@code fallback} where the ground is not known. */
    private double surfaceZ(double x, double z, double fallback) {
        if (terrain == null) return fallback;
        WorldModel world = terrain.world();
        double l = world.spec().metersPerColumn();
        double s = world.surfaceZ((int) Math.floor(x / l), (int) Math.floor(z / l));
        return Double.isFinite(s) ? s : fallback;
    }

    /** Dimensionless topographic slope (dh/dx, dh/dz) around a point (m). */
    private double[] slope(double x, double z, double fallback) {
        if (terrain == null) return new double[] {0, 0};
        double r = config.slopeSampleRadiusM;
        double center = surfaceZ(x, z, fallback);
        double gx = (surfaceZ(x + r, z, center) - surfaceZ(x - r, z, center)) / (2 * r);
        double gz = (surfaceZ(x, z + r, center) - surfaceZ(x, z - r, center)) / (2 * r);
        return new double[] {gx, gz};
    }

    /**
     * Forgets the oldest finished dikes beyond {@link DikeConfig#maxRecordedDikes}: stalled and removed
     * ones first, so fissures that may still be erupting are the last to go.
     */
    private void prune() {
        int excess = dikes.size() - config.maxRecordedDikes;
        for (int pass = 0; pass < 2 && excess > 0; pass++) {
            for (int i = 0; i < dikes.size() && excess > 0; ) {
                Dike d = dikes.get(i);
                boolean vent = d.fissure != null && !d.removed;
                if (!d.propagating() && !d.molten() && (pass == 1 || !vent)) {
                    dikes.remove(i);
                    excess--;
                } else {
                    i++;
                }
            }
        }
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ── Queries ──

    @Override
    public DikeConfig config() {
        return config;
    }

    /** All recorded dikes (propagating, stalled and erupted), oldest first. */
    public List<Dike> dikes() {
        return Collections.unmodifiableList(dikes);
    }

    /** World expansion activity: the tips of rising dikes. */
    public void reportActivity(me.alex4386.typhon.engine.expansion.ExpansionActivity.Sink sink) {
        for (Dike d : dikes) {
            if (d.propagating() && terrain != null) {
                double l = terrain.world().spec().metersPerColumn();
                sink.active((int) Math.floor(d.x / l), (int) Math.floor(d.z / l));
            }
        }
    }

    public int activeCount() {
        int n = 0;
        for (Dike d : dikes) if (d.propagating()) n++;
        return n;
    }

    /** Stalled dikes still molten (they may yet resume). */
    public int moltenCount() {
        int n = 0;
        for (Dike d : dikes) if (d.molten()) n++;
        return n;
    }

    /** Fissures opened by recorded dikes the user has not removed, oldest first. */
    public List<VentSite> openedVents() {
        List<VentSite> vents = new ArrayList<>();
        for (Dike d : dikes) if (d.fissure != null && !d.removed) vents.add(d.fissure);
        return vents;
    }

    /** Dislocation sources for {@code deformation.DeformationModel}. */
    public List<DikeGeometry> geometries() {
        List<DikeGeometry> out = new ArrayList<>(dikes.size());
        for (Dike d : dikes) {
            if (d.volume > 0) out.add(d.geometry(strikeOf(d, null)));
        }
        return out;
    }

    // ── Persistence ──

    @Override
    public void saveState(StateWriter writer) {
        JsonObject out = writer.json();
        out.addProperty("nextId", nextId);
        out.addProperty("forcedPending", forcedPending);
        out.addProperty("nucleationBlocked", nucleationBlocked);
        JsonArray arrests = new JsonArray();
        pendingArrests.forEach(arrests::add);
        out.add("pendingArrests", arrests);
        JsonArray array = new JsonArray();
        for (Dike d : dikes) array.add(d.save());
        out.add("dikes", array);
    }

    @Override
    public void loadState(StateReader reader) {
        JsonObject in = reader.json();
        nextId = in.get("nextId").getAsInt();
        forcedPending = in.get("forcedPending").getAsInt();
        nucleationBlocked = in.get("nucleationBlocked").getAsBoolean();
        pendingArrests.clear();
        for (JsonElement e : in.getAsJsonArray("pendingArrests")) pendingArrests.add(e.getAsInt());
        dikes.clear();
        for (JsonElement e : in.getAsJsonArray("dikes")) dikes.add(Dike.load(e.getAsJsonObject()));
    }
}
