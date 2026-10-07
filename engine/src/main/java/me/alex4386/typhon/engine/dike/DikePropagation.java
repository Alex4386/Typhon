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
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;
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
 * <p><b>Nucleation.</b> Once chamber overpressure {@code P} exceeds {@code f₀·T} (roof tensile strength
 * {@code T}), dikes nucleate as a Poisson process with rate
 * {@code λ = λ_max · sealing · ((P/T − f₀)/(1 − f₀))²}. An open summit conduit ({@code sealing → 0})
 * vents pressure at the summit instead; no dikes form while the chamber erupts.
 *
 * <p><b>Mechanics.</b> The dike is a vertical crack of height {@code H} (chamber to tip) and strike
 * length {@code L = min(H, L_max)}. Its driving pressure is chamber overpressure plus buoyancy,
 * {@code ΔP = P + (ρ_rock − ρ_magma) g H} (basalt is denser than the crust and must be pushed;
 * silicic magma is buoyant). Opening follows the elastic crack estimate
 * {@code w = 2(1−ν) ΔP L / μ}, clamped to observed thicknesses, and the tip advances at the
 * Poiseuille slot velocity {@code v = w² (ΔP / H) / (12 η)} — basaltic dikes rise at ~0.1–5 m/s,
 * viscous silicic dikes are far slower. The intruded volume {@code w · L · H} is drawn from the
 * chamber, lowering its overpressure. The dike stalls when {@code ΔP} falls below
 * {@link DikeConfig#stallPressureMPa} or when {@code v} drops below {@link DikeConfig#freezeSpeed}
 * (magma freezes against the wall rock faster than it advances).
 *
 * <p><b>Path.</b> Dikes rise vertically at depth. Within the edifice, gravitational stresses rotate
 * them down the topographic slope ({@code −∇h}, weighted by {@code exp(−depth / scale)}), so dikes
 * starting beneath a summit tend to surface on the flanks. A deterministic, mean-reverting
 * (Ornstein–Uhlenbeck) perturbation makes the heading wander. On reaching the surface a fissure
 * opens along the radial direction from the volcano axis (radial dikes, the least compressive
 * stress being circumferential around a cone).
 *
 * <p>Depth is tracked in real metres; the model world compresses it between the chamber and the
 * surface, so the tip's world y is interpolated.
 *
 * <p>References: Rubin (1995), Annu. Rev. Earth Planet. Sci. 23:287-336 (propagation of magma-filled cracks). See {@code docs/references.md}.
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
    /** Spontaneous nucleation blocked by the user. */
    private boolean nucleationBlocked;

    /**
     * @param terrain surface model for slopes and fissure elevation; may be {@code null} (flat world
     *     at the chamber's assumed surface)
     */
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
        if (activeCount() > 0 || forcedPending > 0) return RISING_STEP_SECONDS;
        // Keep a spontaneous dike unlikely within one step (≤ 10 %), so it starts, rises and erupts in
        // steps of its own rather than all inside a day-long quiet step.
        double rate = blocked() ? 0 : nucleationRate();
        return rate > 0 ? Math.max(RISING_STEP_SECONDS, 0.1 / rate) : Double.POSITIVE_INFINITY;
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
    }

    /** Stops the propagating dike {@code dikeId} at the next step; false if there is no such dike. */
    public boolean arrest(int dikeId) {
        Dike dike = find(dikeId);
        if (dike == null || !dike.propagating()) return false;
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
        if (dike.propagating()) pendingArrests.add(dikeId);
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

    /** Blocks or allows spontaneous dike nucleation (forced dikes still start). */
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
            if (dike == null || !dike.propagating()) continue;
            dike.status = DikeStatus.STALLED;
            emplaceIntrusion(dike, context.time());
            context.outbox().emit(new DikeEvents.DikeStalled(context.time(), volcanoId, dike.id, dike.tip(config.metersPerBlock), dike.depth,
                    dike.volume, StallReason.ARRESTED));
        }
        pendingArrests.clear();

        if (!blocked() && magma.ruptureExcessM3() > 0) {
            // Ruptured walls: the magma they could not hold leaves through a dike (a rising one, or a new one).
            Dike carrier = null;
            for (Dike dike : dikes) {
                if (dike.propagating()) {
                    carrier = dike;
                    break;
                }
            }
            if (carrier == null && activeCount() < config.maxConcurrentDikes) carrier = start(context);
            if (carrier != null) carrier.volume += magma.takeRuptureExcess();
        } else if (!blocked() && activeCount() < config.maxConcurrentDikes && random.chance(nucleationProbability(context))) {
            start(context);
        }

        for (Dike dike : dikes) {
            if (dike.propagating()) advance(dike, stepDt, context);
        }
        prune();
    }

    /** Probability that a dike nucleates during a step of this length. */
    double nucleationProbability(StepContext context) {
        return 1 - StrictMath.exp(-nucleationRate() * context.dtSeconds());
    }

    /** Spontaneous nucleation rate (per second) at the current overpressure. */
    double nucleationRate() {
        double ratio = magma.overpressureMPa() / magma.tensileStrengthMPa();
        double f0 = config.initiationPressureRatio;
        if (ratio < f0) return 0;
        double x = Math.min(1, (ratio - f0) / (1 - f0));
        // an open, venting summit conduit lets the pressure out there instead of through the walls
        double sealing = 1 - Math.max(0, Math.min(1, magma.conduitOpenness()));
        return config.maxInitiationRate * sealing * x * x;
    }

    private Dike start(StepContext context) {
        SimRandom random = context.random();
        BlockPos center = magma.chamberCenter();
        double angle = random.nextDouble() * 2 * Math.PI;
        double radius = Math.sqrt(random.nextDouble()) * config.startOffsetBlocks;
        double x = center.x() + 0.5 + radius * StrictMath.cos(angle);
        double z = center.z() + 0.5 + radius * StrictMath.sin(angle);
        int surface = surfaceY(x, z, center.y() + 64);
        Dike dike = new Dike(nextId++, context.time(), x, z, Math.max(surface, center.y() + 1), center.y(),
                magma.chamberDepthM());
        dikes.add(dike);
        context.outbox().emit(new DikeEvents.DikeStarted(context.time(), volcanoId, dike.id, dike.origin(config.metersPerBlock),
                magma.overpressureMPa()));
        return dike;
    }

    private void advance(Dike dike, double stepDt, StepContext context) {
        SimRandom random = context.random();
        double remaining = stepDt;
        double travelled = 0;
        StallReason stall = null;
        List<Point3> hypocenters = new ArrayList<>();

        while (remaining > 0 && dike.depth > 0) {
            double height = Math.max(0, dike.chamberDepth - dike.depth);
            double characteristic = Math.max(height, config.minCharacteristicHeight);
            double buoyancy = (config.rockDensity - magmaDensity(magma.silicaWt())) * GRAVITY * height / 1e6;
            double drive = magma.overpressureMPa() + buoyancy;
            if (drive < config.stallPressureMPa) {
                stall = StallReason.INSUFFICIENT_PRESSURE;
                break;
            }

            double strike = Math.min(characteristic, config.maxStrikeLength);
            double opening = clamp(2 * (1 - config.poissonRatio) * drive * 1e6 * strike / config.shearModulusPa,
                    config.minOpening, config.maxOpening);
            double viscosity = StrictMath.pow(10, magma.viscosityLog10());
            double speed = Math.min(config.maxSpeed, opening * opening * (drive * 1e6 / characteristic) / (12 * viscosity));
            dike.speed = speed;
            dike.opening = opening;
            if (speed < config.freezeSpeed) {
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
            double[] slope = slope(dike.x, dike.z, dike.surfaceStartY);
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
            dike.x += moveX / config.metersPerBlock;
            dike.z += moveZ / config.metersPerBlock;
            double horizontal = Math.hypot(moveX, moveZ);
            if (horizontal > 1e-9) {
                dike.travelX = moveX / horizontal;
                dike.travelZ = moveZ / horizontal;
            }
            double advanced = step * fraction;
            travelled += advanced;

            double newHeight = dike.chamberDepth - dike.depth;
            dike.strikeLength = Math.min(newHeight, config.maxStrikeLength);
            // The newly opened sheet (rise × strike, opening thick) cools into its host rock.
            ground.addIntrusionHeat(dike.x, dike.z, 0.5 * (depthBefore + dike.depth), rise * dike.strikeLength,
                    opening, magma.temperatureC());
            double target = opening * dike.strikeLength * newHeight;
            if (target > dike.volume) {
                magma.withdraw(target - dike.volume);
                dike.volume = target;
            }

            int count = random.nextPoisson(config.hypocentersPerKm * advanced / 1000);
            for (int i = 0; i < count; i++) {
                double hxPos = dike.x + random.nextGaussian() * config.hypocenterJitterBlocks;
                double hzPos = dike.z + random.nextGaussian() * config.hypocenterJitterBlocks;
                double hDepth = dike.depth + random.nextDouble() * (depthBefore - dike.depth);
                double l = config.metersPerBlock;
                hypocenters.add(new Point3(hxPos * l, (worldY(dike, hxPos, hzPos, hDepth) + 0.5) * l, hzPos * l));
            }
        }

        dike.tipY = worldY(dike, dike.x, dike.z, dike.depth);
        if (travelled > 0) {
            if (hypocenterListener != null && !hypocenters.isEmpty()) hypocenterListener.accept(hypocenters);
            context.outbox().emit(new DikeEvents.DikeAdvanced(context.time(), volcanoId, dike.id, dike.tip(config.metersPerBlock), dike.depth,
                    dike.speed, dike.opening, dike.volume, hypocenters));
        }

        if (dike.depth <= 0) {
            openFissure(dike, context);
            emplaceIntrusion(dike, context.time());
        } else if (stall != null) {
            dike.status = DikeStatus.STALLED;
            emplaceIntrusion(dike, context.time());
            context.outbox().emit(new DikeEvents.DikeStalled(context.time(), volcanoId, dike.id, dike.tip(config.metersPerBlock), dike.depth,
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
        int steps = Math.max(1, (int) Math.ceil(2 * Math.hypot(dx, dz)));
        TreeSet<Long> done = new TreeSet<>();
        for (int s = 0; s <= steps; s++) {
            double f = (double) s / steps;
            int x = (int) Math.floor(dike.startX + f * dx);
            int z = (int) Math.floor(dike.startZ + f * dz);
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
        int x = (int) Math.floor(dike.x);
        int z = (int) Math.floor(dike.z);
        int y = surfaceY(dike.x, dike.z, dike.surfaceStartY);
        double angle = strikeOf(dike, context.random());
        int length = (int) clamp(Math.round(dike.strikeLength / config.metersPerBlock),
                config.minFissureLength, config.maxFissureLength);
        dike.fissure = VentSite.fissure(volcanoId + "-dike-" + dike.id, new BlockPos(x, y, z), angle, length);
        dike.status = DikeStatus.ERUPTED;
        dike.tipY = y;
        context.outbox().emit(new DikeEvents.FissureOpened(context.time(), volcanoId, dike.id, dike.fissure, dike.volume));
    }

    /**
     * Strike of the dike plane: radial from the volcano axis (the chamber's vertical), falling back to
     * the travel direction near the axis.
     */
    private double strikeOf(Dike dike, SimRandom random) {
        if (dike.fissure != null) return dike.fissure.fissureAngleRad();
        BlockPos center = magma.chamberCenter();
        double dx = dike.x - (center.x() + 0.5);
        double dz = dike.z - (center.z() + 0.5);
        if (Math.hypot(dx, dz) >= 2) return StrictMath.atan2(dz, dx);
        if (dike.travelX != 0 || dike.travelZ != 0) return StrictMath.atan2(dike.travelZ, dike.travelX);
        return random == null ? 0 : random.nextDouble() * Math.PI;
    }

    // ── Geometry helpers ──

    /** Bulk magma density from silica (kg/m³): ~2750 for basalt to ~2510 for rhyolite. */
    static double magmaDensity(double silicaWt) {
        return clamp(2750 - 10 * (silicaWt - 48), 2300, 2800);
    }

    private int surfaceY(double x, double z, int fallback) {
        if (terrain == null) return fallback;
        return terrain.groundY((int) Math.floor(x), (int) Math.floor(z), fallback);
    }

    /** Dimensionless topographic slope (dh/dx, dh/dz) around a point. */
    private double[] slope(double x, double z, int fallback) {
        if (terrain == null) return new double[] {0, 0};
        int ix = (int) Math.floor(x);
        int iz = (int) Math.floor(z);
        int r = config.slopeSampleRadius;
        int center = terrain.groundY(ix, iz, fallback);
        double gx = (terrain.groundY(ix + r, iz, center) - terrain.groundY(ix - r, iz, center)) / (2.0 * r);
        double gz = (terrain.groundY(ix, iz + r, center) - terrain.groundY(ix, iz - r, center)) / (2.0 * r);
        return new double[] {gx, gz};
    }

    /** World y for a real depth below the surface at (x, z). */
    private int worldY(Dike dike, double x, double z, double depth) {
        int surface = surfaceY(x, z, dike.surfaceStartY);
        double span = dike.surfaceStartY - dike.chamberY;
        int y = (int) Math.floor(surface - depth / dike.chamberDepth * span);
        return Math.max(dike.chamberY, Math.min(surface, y));
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
                if (!d.propagating() && (pass == 1 || !vent)) {
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
            if (d.propagating()) sink.active((int) Math.floor(d.x), (int) Math.floor(d.z));
        }
    }

    public int activeCount() {
        int n = 0;
        for (Dike d : dikes) if (d.propagating()) n++;
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
        nucleationBlocked = in.has("nucleationBlocked") && in.get("nucleationBlocked").getAsBoolean();
        pendingArrests.clear();
        if (in.has("pendingArrests")) {
            for (JsonElement e : in.getAsJsonArray("pendingArrests")) pendingArrests.add(e.getAsInt());
        }
        dikes.clear();
        for (JsonElement e : in.getAsJsonArray("dikes")) dikes.add(Dike.load(e.getAsJsonObject()));
    }
}
