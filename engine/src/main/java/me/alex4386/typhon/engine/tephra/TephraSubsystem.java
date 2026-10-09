package me.alex4386.typhon.engine.tephra;

import me.alex4386.typhon.engine.config.ConfigCopy;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.command.EngineCommand;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.random.SimRandom;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.tephra.TephraCommands.LaunchBomb;
import me.alex4386.typhon.engine.tephra.TephraCommands.LaunchSalvo;
import me.alex4386.typhon.engine.tephra.TephraCommands.ProximalFallout;
import me.alex4386.typhon.engine.tephra.TephraCommands.SetWind;
import me.alex4386.typhon.engine.tephra.TephraCommands.StartExplosivePhase;
import me.alex4386.typhon.engine.tephra.TephraCommands.StopExplosivePhase;
import me.alex4386.typhon.engine.tephra.TephraEvents.AshFall;
import me.alex4386.typhon.engine.tephra.TephraEvents.BombLanded;
import me.alex4386.typhon.engine.tephra.TephraEvents.BombLaunched;
import me.alex4386.typhon.engine.tephra.TephraEvents.ExplosivePhaseChanged;
import me.alex4386.typhon.engine.tephra.TephraEvents.PlumeColumn;
import me.alex4386.typhon.engine.tephra.TephraEvents.VolcanicLightning;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.LayerFlags;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.engine.world.UnitSource;
import me.alex4386.typhon.engine.volcano.VentKind;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;

/**
 * Explosive tephra: ballistic bombs, the eruption column, and wind-driven ash fall.
 *
 * <p><b>Bombs</b> are launched from the vent of the active {@link ExplosivePhase} at a Poisson rate
 * set by the ballistic share of the mass eruption rate, with log-normal diameters, near-vertical
 * launch angles and the gas-thrust exit speed ({@link Ballistics#gasThrustExitSpeed}). They fly under
 * gravity and quadratic drag relative to the wind ({@link Ballistics#rk4}, sub-steps of at most
 * {@link TephraConfig#maxIntegrationStepSeconds} and of half a ground column of travel) until they cross the ground surface of the world model;
 * where the ground is unknown it is taken to be at launch height. On impact a crater of radius
 * {@code k·E^(1/3)} is excavated and the bomb's own volume is laid on the ground as loose rock chosen by
 * silica content.
 *
 * <p><b>Ash</b>: every {@link TephraConfig#ashStepSeconds} seconds the non-ballistic mass is injected
 * around the vent at the plume height from {@link PlumeModel}, transported and settled on an
 * {@link AshGrid}, and the accumulated deposit is laid on the ground as loose ash layers. The subsystem
 * emits {@link PlumeColumn}, {@link AshFall} and {@link VolcanicLightning} events.
 *
 * <p>All quantities are physical: positions in metres, masses in kg, speeds in m/s.
 *
 * <p>Must be registered after the {@link TerrainModel} it reads.
 */
public final class TephraSubsystem implements Subsystem {
    /** Mass accounting for airborne tephra (kg). emitted = airborne + deposited + exported + discarded. */
    public record MassBudget(double emitted, double airborne, double deposited, double exported, double discarded) {}

    private record Landing(Vec3d position, Vec3d velocity) {}

    private final String id;
    private final TerrainModel terrain;
    private UnitSource units;
    private final TephraConfig config;
    private final WindField wind;
    private final List<EngineCommand> pending = new ArrayList<>();
    /** Parcels a proximal-fallout command is split into (deterministic sampling of the landing field). */
    static final int PROXIMAL_PARCELS = 96;
    private final List<Bomb> bombs = new ArrayList<>();
    /** Last announced ash fall per region: {fallRate, airborneLoad, time}. */
    private final TreeMap<Integer, double[]> ashReports = new TreeMap<>();

    /** Active explosive phases by vent id: each vent that feeds a column has its own. */
    private final java.util.TreeMap<String, ExplosivePhase> phases = new java.util.TreeMap<>();
    private long nextBombId = 1;
    private AshGrid grid;

    public TephraSubsystem(String id, TerrainModel terrain, TephraConfig config) {
        this.id = Objects.requireNonNull(id, "id");
        this.terrain = Objects.requireNonNull(terrain, "terrain");
        this.units = UnitSource.typed(terrain.world());
        this.config = config.copy();
        this.config.validate();
        this.wind = new WindField(this.config.initialWindSpeed, this.config.initialWindDirectionRad,
                this.config.initialWindVariability);
    }

    public TephraSubsystem(String id, TerrainModel terrain) {
        this(id, terrain, new TephraConfig());
    }

    /** Live retune; the ash grid's layout (cell size, cell count) is refused. A changed wind blows from now on. */
    @Override
    public boolean reconfigure(Object c) {
        if (!(c instanceof TephraConfig n) || !ConfigCopy.same(n, config, "cellSizeM", "gridCells")) return false;
        TephraConfig next = n.copy();
        next.validate();
        boolean wind = !ConfigCopy.same(next, config, "initialWindSpeed", "initialWindDirectionRad", "initialWindVariability");
        ConfigCopy.into(next, config);
        if (wind) setWind(config.initialWindSpeed, config.initialWindDirectionRad, config.initialWindVariability);
        return true;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public void registerCommands(CommandBus bus) {
        bus.register(StartExplosivePhase.class, c -> { if (c.target().equals(id)) pending.add(c); });
        bus.register(StopExplosivePhase.class, c -> { if (c.target().equals(id)) pending.add(c); });
        bus.register(SetWind.class, c -> { if (c.target().equals(id)) pending.add(c); });
        bus.register(LaunchBomb.class, c -> { if (c.target().equals(id)) pending.add(c); });
        bus.register(LaunchSalvo.class, c -> { if (c.target().equals(id)) pending.add(c); });
        bus.register(ProximalFallout.class, c -> { if (c.target().equals(id)) pending.add(c); });
    }

    // ── Direct API (same effect as the commands; applied at this subsystem's next step) ──

    public void startPhase(ExplosivePhase phase) {
        pending.add(new StartExplosivePhase(id, phase));
    }

    /** Stops every vent's phase. */
    public void stopPhase() {
        pending.add(new StopExplosivePhase(id));
    }

    /** Stops the phase of the vent {@code ventId} (the others go on). */
    public void stopPhase(String ventId) {
        pending.add(new StopExplosivePhase(id, ventId));
    }

    /** Attributes ash fall and bombs to the producing volcano's current eruption ({@link DepositType#FALL}). */
    public void setUnits(UnitSource units) {
        this.units = units;
    }

    public void setWind(double speed, double directionRad, double variability) {
        pending.add(new SetWind(id, speed, directionRad, variability));
    }

    /** See {@link LaunchSalvo}; applied at this subsystem's next step. */
    public void launchSalvo(VentSite vent, double ballisticMassKg, double exitSpeed, double zenithMeanDeg,
            double zenithSigmaDeg, double silicaWt, int maxBombs, boolean carriesMass) {
        pending.add(new LaunchSalvo(id, vent, ballisticMassKg, exitSpeed, zenithMeanDeg, zenithSigmaDeg, silicaWt, maxBombs,
                carriesMass));
    }

    /** See {@link ProximalFallout}; applied at this subsystem's next step. */
    public void proximalFallout(VentSite vent, double massKg, double exitSpeed, double zenithMeanDeg,
            double zenithSigmaDeg, double medianSizeM, double minSizeM, double maxSizeM) {
        if (!(massKg > 0) || !(maxSizeM > minSizeM)) return;
        pending.add(new ProximalFallout(id, vent, massKg, exitSpeed, zenithMeanDeg, zenithSigmaDeg, medianSizeM,
                minSizeM, maxSizeM));
    }

    public void launchBomb(Vec3d start, Vec3d velocity, double diameter, double silicaWt) {
        pending.add(new LaunchBomb(id, start, velocity, diameter, silicaWt));
    }

    // ── Queries ──

    /** Explosive activity for dashboards. */
    public record Snapshot(boolean phaseActive, double massEruptionRate, double plumeHeight, int bombsInFlight) {}

    @Override
    public Snapshot snapshot() {
        return new Snapshot(!phases.isEmpty(), totalMassEruptionRate(), plumeHeight(), inFlightBombs());
    }

    /** The strongest active phase (highest mass eruption rate), or {@code null}. */
    public ExplosivePhase activePhase() {
        ExplosivePhase best = null;
        for (ExplosivePhase p : phases.values()) if (best == null || p.massEruptionRate() > best.massEruptionRate()) best = p;
        return best;
    }

    /** Every active phase, by vent id. */
    public java.util.Collection<ExplosivePhase> activePhases() {
        return java.util.Collections.unmodifiableCollection(phases.values());
    }

    private double totalMassEruptionRate() {
        double sum = 0;
        for (ExplosivePhase p : phases.values()) sum += p.massEruptionRate();
        return sum;
    }

    public int inFlightBombs() {
        return bombs.size();
    }

    public WindField wind() {
        return wind;
    }

    /** Height (m) of the tallest column above its vent (0 when no phase is active). */
    public double plumeHeight() {
        double h = 0;
        for (ExplosivePhase p : phases.values()) h = Math.max(h, plumeHeight(p));
        return h;
    }

    /** Radius (m) over which {@code phase}'s column spreads its ash (as in its {@link PlumeColumn} events). */
    public double plumeRadiusM(ExplosivePhase phase) {
        if (grid == null) return 0;
        return 2 * Math.max(grid.cellMeters() * 0.5, 0.25 * PlumeModel.heightForMassRate(phase.massEruptionRate()));
    }

    /** Height (m) of {@code phase}'s column above its vent (0 before its first ash step). */
    public double plumeHeight(ExplosivePhase phase) {
        return grid == null ? 0 : PlumeModel.heightForMassRate(phase.massEruptionRate());
    }

    /**
     * World expansion materialised columns {@code [x0, x0+size) × [z0, z0+size)}: they get the fall deposit
     * that landed there while they were not simulated (see {@code WorldExpansion}).
     */
    public void backfill(double time, int x0, int z0, int size) {
        if (grid == null) return;
        grid.backfill(terrain.world(), units.unit(DepositType.FALL, time, Double.NaN), x0, z0, size);
    }

    /** Reports ground with a fall deposit at least {@code minThicknessM} thick (world expansion activity). */
    public void reportDeposits(double minThicknessM, me.alex4386.typhon.engine.expansion.ExpansionActivity.Sink sink) {
        if (grid == null) return;
        grid.reportDeposits(minThicknessM, config.depositBulkDensity, 16, sink);
    }

    /** Ash deposit thickness (m) at a column; 0 outside the ash grid. */
    public double depositThickness(int x, int z) {
        if (grid == null) return 0;
        return grid.thicknessAt(x, z, config.depositBulkDensity);
    }

    /** Suspended ash load (kg/m²) above a column; 0 outside the ash grid. */
    public double airborneLoad(int x, int z) {
        if (grid == null) return 0;
        int cell = grid.cellAt(x, z);
        return cell < 0 ? 0 : grid.airborneAt(cell) / grid.cellArea();
    }

    public MassBudget massBudget() {
        if (grid == null) return new MassBudget(0, 0, 0, 0, 0);
        return new MassBudget(grid.emitted, grid.airborneTotal(), grid.deposited, grid.exported, grid.discarded);
    }

    AshGrid grid() {
        return grid;
    }

    @Override
    public TephraConfig config() {
        return config;
    }

    // ── Simulation ──

    /** Longest step while bombs fly (s). */
    static final double BOMB_STEP_SECONDS = 1;

    /**
     * While a phase erupts or ash is airborne, one ash step per engine step ({@link
     * TephraConfig#ashStepSeconds}); while bombs fly, {@link #BOMB_STEP_SECONDS}.
     */
    @Override
    public double maxStepSeconds() {
        double limit = Double.POSITIVE_INFINITY;
        // a phase started or bombs launched through the direct API take effect at the next step: keep it short
        if (!phases.isEmpty() || !pending.isEmpty() || (grid != null && grid.airborneTotal() > 0)) limit = config.ashStepSeconds;
        if (inFlightBombs() > 0) limit = Math.min(limit, BOMB_STEP_SECONDS);
        return limit;
    }

    @Override
    public void step(StepContext context) {
        processPending(context);
        launchFromPhase(context);
        advanceBombs(context);
        if (context.crossed(config.ashStepSeconds)) ashStep(context);
        if (grid != null && context.crossed(config.ashEventSeconds)) emitAshFall(context);
    }

    private void processPending(StepContext context) {
        for (EngineCommand command : pending) {
            switch (command) {
                case StartExplosivePhase start -> {
                    ExplosivePhase phase = start.phase();
                    phases.put(phase.vent().id(), phase);
                    ensureGrid(phase.vent().position());
                    context.outbox().emit(new ExplosivePhaseChanged(context.time(), id, true, totalMassEruptionRate()));
                }
                case StopExplosivePhase stop -> {
                    boolean had = !phases.isEmpty();
                    if (stop.ventId() == null) phases.clear();
                    else phases.remove(stop.ventId());
                    if (had) {
                        context.outbox().emit(new ExplosivePhaseChanged(context.time(), id, !phases.isEmpty(),
                                totalMassEruptionRate()));
                    }
                }
                case SetWind set -> wind.set(set.speed(), set.directionRad(), set.variability(), context.random());
                case LaunchBomb launch -> launch(context, launch.start(), launch.velocity(), launch.diameter(), launch.silicaWt(), 1);
                case LaunchSalvo salvo -> launchSalvo(context, salvo);
                // a salvo's parcels land, then the cone relaxes once
                case ProximalFallout fallout ->
                        terrain.world().withRelaxationDeferred(() -> depositProximal(context, fallout));
                default -> throw new IllegalStateException("Unexpected command " + command);
            }
        }
        pending.clear();
    }

    private void ensureGrid(Point3 center) {
        if (grid != null) return;
        double l = metersPerColumn();
        int cellColumns = Math.max(1, (int) Math.round(config.cellSizeM / l));
        grid = AshGrid.centeredOn(center, cellColumns, l, config.gridCells);
    }

    // ── Bombs ──

    private void launchFromPhase(StepContext context) {
        for (ExplosivePhase phase : phases.values()) launchFromPhase(context, phase);
    }

    private void launchFromPhase(StepContext context, ExplosivePhase phase) {
        if (phase.massEruptionRate() <= 0 || phase.ballisticFraction() <= 0) return;
        SimRandom random = context.random();

        double sigma = config.bombDiameterSigma;
        double meanMass = Ballistics.sphereMass(config.bombMedianDiameter, config.bombDensity)
                * StrictMath.exp(4.5 * sigma * sigma);
        double trueRate = phase.massEruptionRate() * phase.ballisticFraction() / meanMass;
        double rate = Math.min(config.maxBombsPerSecond, trueRate);
        // beyond the tracking cap each tracked bomb stands for several real ones, so all the mass lands
        double weight = rate > 0 ? trueRate / rate : 0;
        int count = random.nextPoisson(rate * context.dtSeconds());
        if (count == 0) return;

        double physical = Ballistics.gasThrustExitSpeed(phase.gasFraction(), phase.temperatureC(), phase.overpressureMPa());
        double exitSpeed = Math.max(config.minExitSpeed, Math.min(config.maxExitSpeed, physical));

        for (int n = 0; n < count; n++) {
            double diameter = clamp(
                    config.bombMedianDiameter * StrictMath.exp(sigma * random.nextGaussian()),
                    config.minBombDiameter,
                    config.maxBombDiameter);
            // Large clasts decouple from the gas jet and leave more slowly.
            double speed = exitSpeed * StrictMath.exp(0.15 * random.nextGaussian()) / Math.sqrt(1 + diameter);
            double zenith = Math.min(
                    Math.abs(random.nextGaussian()) * Math.toRadians(config.launchAngleSigmaDeg),
                    Math.toRadians(config.maxLaunchAngleDeg));
            double azimuth = random.nextDouble(0, 2 * Math.PI);
            double horizontal = speed * StrictMath.sin(zenith);
            Vec3d velocity = new Vec3d(
                    horizontal * StrictMath.cos(azimuth), speed * StrictMath.cos(zenith), horizontal * StrictMath.sin(azimuth));
            launch(context, sampleVentPoint(phase.vent(), random), velocity, diameter, phase.silicaWt(), weight);
        }
    }

    private double meanBombMass() {
        double sigma = config.bombDiameterSigma;
        return Ballistics.sphereMass(config.bombMedianDiameter, config.bombDensity) * StrictMath.exp(4.5 * sigma * sigma);
    }

    private void launchSalvo(StepContext context, LaunchSalvo salvo) {
        SimRandom random = context.random();
        double expected = salvo.ballisticMassKg() / meanBombMass();
        int count = (int) Math.floor(expected);
        if (random.chance(expected - count)) count++;
        count = Math.min(count, salvo.maxBombs());
        // a salvo that carries its mass lays all of it, even when it is less than one mean bomb
        if (salvo.carriesMass() && count == 0 && salvo.ballisticMassKg() > 0) count = 1;
        if (count <= 0) return;

        double exitSpeed = Math.max(config.minExitSpeed, Math.min(config.maxExitSpeed, salvo.exitSpeed()));
        double sigma = config.bombDiameterSigma;
        double[] diameters = new double[count];
        double sampled = 0;
        for (int n = 0; n < count; n++) {
            diameters[n] = clamp(
                    config.bombMedianDiameter * StrictMath.exp(sigma * random.nextGaussian()),
                    config.minBombDiameter,
                    config.maxBombDiameter);
            sampled += Ballistics.sphereMass(diameters[n], config.bombDensity);
        }
        // tracked bombs stand for the whole ballistic mass (capped salvos weight each up), or for none of it
        double weight = salvo.carriesMass() && sampled > 0 ? salvo.ballisticMassKg() / sampled : 0;
        for (int n = 0; n < count; n++) {
            double diameter = diameters[n];
            double speed = exitSpeed * StrictMath.exp(0.15 * random.nextGaussian()) / Math.sqrt(1 + diameter);
            double zenithDeg = launchZenithDeg(salvo.zenithMeanDeg(), salvo.zenithSigmaDeg(), random);
            double zenith = Math.toRadians(zenithDeg);
            double azimuth = random.nextDouble(0, 2 * Math.PI);
            double horizontal = speed * StrictMath.sin(zenith);
            Vec3d velocity = new Vec3d(
                    horizontal * StrictMath.cos(azimuth), speed * StrictMath.cos(zenith), horizontal * StrictMath.sin(azimuth));
            launch(context, sampleVentPoint(salvo.vent(), random), velocity, diameter, salvo.silicaWt(), weight);
        }
    }

    /**
     * Launch angle from vertical (degrees): normal around {@code meanDeg}, reflected at the vertical.
     * Clamping the negative half to 0° instead sends half of a vertical salvo straight up, all landing
     * back in the vent; reflection gives the half-normal spread of real explosions.
     */
    private double launchZenithDeg(double meanDeg, double sigmaDeg, SimRandom random) {
        double z = Math.abs(meanDeg + random.nextGaussian() * sigmaDeg);
        return Math.min(z, config.maxLaunchAngleDeg);
    }

    /** Lapilli of a discrete explosion: see {@link ProximalFallout}. */
    private void depositProximal(StepContext context, ProximalFallout f) {
        ensureGrid(f.vent().position());
        SimRandom random = context.random();
        double exitSpeed = Math.max(config.minExitSpeed, Math.min(config.maxExitSpeed, f.exitSpeed()));
        Vec3d w = wind.at(context.time());
        double parcelMass = f.massKg() / PROXIMAL_PARCELS;
        double sigma = config.bombDiameterSigma;
        for (int n = 0; n < PROXIMAL_PARCELS; n++) {
            double d = clamp(f.medianSizeM() * StrictMath.exp(sigma * random.nextGaussian()), f.minSizeM(), f.maxSizeM());
            double speed = exitSpeed * StrictMath.exp(0.15 * random.nextGaussian()) / Math.sqrt(1 + d);
            double zenith = Math.toRadians(launchZenithDeg(f.zenithMeanDeg(), f.zenithSigmaDeg(), random));
            double azimuth = random.nextDouble(0, 2 * Math.PI);
            // Quadratic drag (v_t = sqrt(g/k)): a vertical launch at speed v peaks at (v_t²/2g)·ln(1 + v²/v_t²),
            // so use the drag-free speed that reaches the same height.
            double vt = Math.sqrt(config.gravity / Ballistics.dragFactor(d, config.bombDensity, config.dragCoefficient,
                    config.airDensity * Ballistics.airDensityRatio(f.vent().position().y(), config.gravity)));
            double u = vt * Math.sqrt(Math.log1p((speed / vt) * (speed / vt)));
            double vh = u * StrictMath.sin(zenith);
            double vz = u * StrictMath.cos(zenith);
            double flight = 2 * vz / config.gravity + 1e-3;
            // The wind couples over the drag response time tau = v_t/g.
            double tau = vt / config.gravity;
            double drift = flight - tau * (1 - StrictMath.exp(-flight / tau));
            double r = vh * flight;
            Vec3d at = sampleVentPoint(f.vent(), random);
            double x = at.x() + r * StrictMath.cos(azimuth) + w.x() * drift;
            double z = at.z() + r * StrictMath.sin(azimuth) + w.z() * drift;
            depositParcel(context, x, z, parcelMass);
        }
    }

    /**
     * Bulk density of proximal lapilli/ash fall (kg/m³): fresh Surtseyan tephra ~1.4–1.6 t/m³ dry bulk
     * (Jakobsson &amp; Moore 1986), proximal scoria-lapilli fall 1.0–1.6 t/m³.
     */
    static final double PROXIMAL_BULK_DENSITY = 1400;

    /**
     * Lays one proximal parcel (kg) on the ground where it lands (m), at column resolution (a 3×3
     * footprint, the centre weighted twice), not spread over an ash-grid cell: near the vent the cone is built
     * column by column and relaxes to its angle of repose. The ash grid keeps the record (for maps and
     * world growth) without applying it again. Off the simulated ground the grid holds it for the backfill.
     */
    private void depositParcel(StepContext context, double x, double z, double parcelMass) {
        WorldModel world = terrain.world();
        double l = world.spec().metersPerColumn();
        int cx = (int) Math.floor(x / l);
        int cz = (int) Math.floor(z / l);
        int cell = grid.cellAt(cx, cz);
        if (cell < 0) {
            grid.discarded += parcelMass;
            return;
        }
        if (!world.isKnown(cx, cz)) {
            grid.addDeposit(cell, parcelMass); // applied (or backfilled) through the grid later
            return;
        }
        double realMass = parcelMass;
        int unit = units.unit(DepositType.FALL, context.time(), Double.NaN);
        double weightSum = 10; // centre 2, eight neighbours 1
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                double w = (dx == 0 && dz == 0) ? 2 : 1;
                int px = cx + dx;
                int pz = cz + dz;
                if (!world.isKnown(px, pz)) {
                    px = cx;
                    pz = cz;
                }
                double thickness = realMass * w / weightSum / (PROXIMAL_BULK_DENSITY * l * l);
                world.deposit(px, pz, thickness, MaterialTable.ASH, unit, LayerFlags.LOOSE, 1 - PROXIMAL_BULK_DENSITY / ExplosivePhase.DRE_DENSITY, 0);
            }
        }
        grid.addAppliedDeposit(cell, parcelMass, config.depositBulkDensity);
    }

    /** A launch point on the vent floor (m). */
    private static Vec3d sampleVentPoint(VentSite vent, SimRandom random) {
        Point3 p = vent.position();
        double cx = p.x(), cz = p.z(), y = p.y();
        if (vent.kind() == VentKind.FISSURE) {
            double along = (random.nextDouble() - 0.5) * vent.fissureLengthM();
            double across = (random.nextDouble() - 0.5) * 2 * vent.craterRadiusM();
            double dx = StrictMath.cos(vent.fissureAngleRad()), dz = StrictMath.sin(vent.fissureAngleRad());
            return new Vec3d(cx + along * dx - across * dz, y, cz + along * dz + across * dx);
        }
        double r = vent.craterRadiusM() * Math.sqrt(random.nextDouble());
        double a = random.nextDouble(0, 2 * Math.PI);
        return new Vec3d(cx + r * StrictMath.cos(a), y, cz + r * StrictMath.sin(a));
    }

    private void launch(StepContext context, Vec3d start, Vec3d velocity, double diameter, double silicaWt, double weight) {
        if (!(diameter > 0)) throw new IllegalArgumentException("diameter must be positive");
        double k = Ballistics.dragFactor(diameter, config.bombDensity, config.dragCoefficient, config.airDensity);
        double[] s = {start.x(), start.y(), start.z(), velocity.x(), velocity.y(), velocity.z()};
        Bomb bomb = new Bomb(
                nextBombId++, s, diameter, k, silicaWt, start.y(), context.time(), weight);
        // A launch point sampled inside the crater can sit below a wall column: start on its surface,
        // otherwise the bomb "lands" on its first step without flying.
        s[1] = Math.max(s[1], surfaceTop(s[0], s[2], bomb));

        // Predict the flight on a copy so hosts can render it; identical unless terrain changes mid-flight.
        Bomb probe = bomb.copy();
        double dt = context.dtSeconds();
        double flight = 0;
        Landing landing = null;
        while (flight < config.maxFlightSeconds && landing == null) {
            landing = advance(probe, context.time() + flight, dt);
            flight += dt;
        }
        context.outbox().emit(new BombLaunched(
                context.time(),
                id,
                bomb.id,
                start,
                velocity,
                diameter,
                k,
                flight,
                landing == null ? probe.position() : landing.position()));
        bombs.add(bomb);
    }

    /** Ground elevation (m) under a point, the bomb's fallback where the ground is not known. */
    private double surfaceTop(double x, double z, Bomb bomb) {
        WorldModel world = terrain.world();
        double l = world.spec().metersPerColumn();
        int cx = (int) Math.floor(x / l);
        int cz = (int) Math.floor(z / l);
        double s = world.surfaceZ(cx, cz);
        return Double.isFinite(s) ? s : bomb.fallbackGroundZ;
    }

    /** Integrates one engine step of {@code stepSeconds}; returns the landing point if the bomb reached the ground. */
    private Landing advance(Bomb bomb, double time, double stepSeconds) {
        Vec3d w = wind.at(time);
        double[] s = bomb.s;
        double half = 0.5 * metersPerColumn();
        double elapsed = 0;
        while (elapsed < stepSeconds) {
            // resolve the ground column by column: at most half a column of travel per sub-step
            double speed = Math.sqrt(s[3] * s[3] + s[4] * s[4] + s[5] * s[5]);
            double limit = speed > 0 ? Math.min(config.maxIntegrationStepSeconds, half / speed) : config.maxIntegrationStepSeconds;
            double remaining = stepSeconds - elapsed;
            double dt = remaining <= limit * (1 + 1e-9) ? remaining : remaining / Math.ceil(remaining / limit - 1e-9);
            elapsed = dt == remaining ? stepSeconds : elapsed + dt;
            double px = s[0], py = s[1], pz = s[2], pvx = s[3], pvy = s[4], pvz = s[5];
            double f0 = py - surfaceTop(px, pz, bomb);
            Ballistics.rk4(s, dt, bomb.dragFactor, config.gravity, w.x(), w.z());
            double f1 = s[1] - surfaceTop(s[0], s[2], bomb);
            if (f1 < 0) {
                double t = f0 > 0 ? f0 / (f0 - f1) : 0;
                return new Landing(
                        new Vec3d(px + t * (s[0] - px), py + t * (s[1] - py), pz + t * (s[2] - pz)),
                        new Vec3d(pvx + t * (s[3] - pvx), pvy + t * (s[4] - pvy), pvz + t * (s[5] - pvz)));
            }
        }
        return null;
    }

    private void advanceBombs(StepContext context) {
        double now = context.time();
        double dt = context.dtSeconds();
        Iterator<Bomb> it = bombs.iterator();
        while (it.hasNext()) {
            Bomb bomb = it.next();
            Landing landing = advance(bomb, now, dt);
            if (landing != null) {
                it.remove();
                land(context, bomb, landing);
            } else if (now - bomb.launchTime + dt >= config.maxFlightSeconds - 1e-9) {
                it.remove();
            }
        }
    }

    private void land(StepContext context, Bomb bomb, Landing landing) {
        WorldModel world = terrain.world();
        double l = metersPerColumn();
        int x = (int) Math.floor(landing.position().x() / l);
        int z = (int) Math.floor(landing.position().z() / l);
        double speed = landing.velocity().length();
        double energy = 0.5 * Ballistics.sphereMass(bomb.diameter, config.bombDensity) * speed * speed;
        double radius = Ballistics.craterRadius(energy, config.craterCoefficient);
        double dug = 0;
        if (world.isKnown(x, z)) {
            if (radius > 0) dug = dig(context.time(), landing.position().x(), landing.position().z(), radius) > 0 ? radius : 0;
            placeBomb(context, x, z, bomb);
        }
        context.outbox().emit(new BombLanded(
                context.time(), id, bomb.id, landing.position().toPoint(), speed, energy, bomb.diameter, dug));
    }

    /** Porosity of loose impact ejecta (a poorly sorted breccia of the excavated ground). */
    static final double EJECTA_POROSITY = 0.35;
    /** The ejecta blanket reaches this many crater radii (its thickness ∝ (r/R)⁻³ beyond the rim). */
    static final double EJECTA_REACH = 3;

    /**
     * Excavates an impact crater of radius {@code radius} m centred at ({@code cx}, {@code cz}) (m): a
     * paraboloid of depth {@code 0.5·R·(1 − (d/R)²)} and volume {@code πR³/4}, in loose ground only (a bomb
     * landing on solid rock or a cooled flow splatters without cratering it). What it digs out is thrown onto
     * an ejecta blanket beyond the rim, thinning as {@code (r/R)⁻³} (McGetchin, Settle &amp; Head 1973), so
     * cratering moves material and never destroys it. A crater smaller than half a column keeps crater and
     * blanket inside its column: the column's mean is unchanged and nothing is moved. Returns the volume
     * excavated (m³).
     */
    private double dig(double time, double cx, double cz, double radius) {
        WorldModel world = terrain.world();
        double l = metersPerColumn();
        if (radius < 0.5 * l) return 0;
        int ix = (int) Math.floor(cx / l);
        int iz = (int) Math.floor(cz / l);
        double area = l * l;
        int reach = (int) Math.ceil(EJECTA_REACH * radius / l);
        double removed = 0;
        java.util.Map<Short, Double> solids = new java.util.TreeMap<>();
        for (int dz = -reach; dz <= reach; dz++) {
            for (int dx = -reach; dx <= reach; dx++) {
                int x = ix + dx;
                int z = iz + dz;
                double d = Math.hypot((x + 0.5) * l - cx, (z + 0.5) * l - cz);
                if (d > radius) continue;
                double depth = 0.5 * radius * (1 - (d / radius) * (d / radius));
                if (depth < 0.01 || !world.isKnown(x, z)) continue;
                var cut = world.erode(x, z, depth, true);
                removed += cut.removedM() * area;
                cut.byMaterial().forEach((m, t) -> solids.merge(m, t * area, Double::sum));
            }
        }
        if (solids.isEmpty()) return removed;
        // the blanket: weights (r/R)⁻³ over the columns beyond the rim
        java.util.List<int[]> cells = new java.util.ArrayList<>();
        java.util.List<Double> weights = new java.util.ArrayList<>();
        double total = 0;
        for (int dz = -reach; dz <= reach; dz++) {
            for (int dx = -reach; dx <= reach; dx++) {
                int x = ix + dx;
                int z = iz + dz;
                double d = Math.hypot((x + 0.5) * l - cx, (z + 0.5) * l - cz);
                if (d <= radius || d > EJECTA_REACH * radius || !world.isKnown(x, z)) continue;
                double w = Math.pow(d / radius, -3);
                cells.add(new int[] {x, z});
                weights.add(w);
                total += w;
            }
        }
        if (total <= 0) {
            // no ground beyond the rim (a crater filling the known area): the ejecta falls back in
            cells.add(new int[] {ix, iz});
            weights.add(1.0);
            total = 1;
        }
        int unit = units.unit(DepositType.EJECTA, time, Double.NaN);
        for (var e : solids.entrySet()) {
            var material = MaterialTable.get(e.getKey());
            for (int i = 0; i < cells.size(); i++) {
                double solid = e.getValue() * weights.get(i) / total;
                double thickness = solid / ((1 - EJECTA_POROSITY) * area);
                if (thickness > 0) world.deposit(cells.get(i)[0], cells.get(i)[1], thickness, material, unit,
                        LayerFlags.LOOSE, EJECTA_POROSITY, 0);
            }
        }
        return removed;
    }

    private double metersPerColumn() {
        return terrain.world().spec().metersPerColumn();
    }

    /** Rock a bomb of {@code silicaWt} cools into, for the world model. */
    private static me.alex4386.typhon.engine.world.Material bombRock(double silicaWt) {
        if (silicaWt < 53) return MaterialTable.BASALT;
        if (silicaWt < 63) return MaterialTable.ANDESITE;
        if (silicaWt < 69) return MaterialTable.DACITE;
        return MaterialTable.RHYOLITE;
    }

    /** Porosity of a heap of landed bombs and blocks (loose, poorly sorted coarse clasts). */
    static final double BOMB_HEAP_POROSITY = 0.3;

    /**
     * Lays the volume of the real bombs this one stands for ({@link Bomb#weight}) on the column it landed in,
     * as a loose heap (its solid volume over {@code 1 − porosity}).
     */
    private void placeBomb(StepContext context, int x, int z, Bomb bomb) {
        if (!(bomb.weight > 0)) return;
        double l = metersPerColumn();
        double solid = bomb.weight * Math.PI / 6 * bomb.diameter * bomb.diameter * bomb.diameter;
        double thickness = solid / ((1 - BOMB_HEAP_POROSITY) * l * l);
        terrain.world().deposit(x, z, thickness, bombRock(bomb.silicaWt),
                units.unit(DepositType.FALL, context.time(), Double.NaN), LayerFlags.LOOSE, BOMB_HEAP_POROSITY, 0);
    }

    // ── Ash ──

    private void ashStep(StepContext context) {
        double dt = config.ashStepSeconds;
        // Each column injects its ash over its own vent. The ash grid is column-integrated, with one release
        // height for all airborne ash: the columns' mass-weighted mean height.
        double heightSum = 0;
        double massSum = 0;
        for (ExplosivePhase phase : phases.values()) {
            Point3 base = phase.vent().position();
            ensureGrid(base);
            grid.parallel = context.parallel();
            double height = PlumeModel.heightForMassRate(phase.massEruptionRate());
            double sigma = Math.max(grid.cellMeters() * 0.5, 0.25 * height);
            heightSum += height * phase.massEruptionRate();
            massSum += phase.massEruptionRate();
            grid.inject(
                    phase.massEruptionRate() * (1 - phase.ballisticFraction()) * dt,
                    phase.grainSize().fractions(),
                    base.x(),
                    base.z(),
                    sigma);
            context.outbox().emit(new PlumeColumn(
                    context.time(), id, base, base.y() + height, 2 * sigma, phase.massEruptionRate()));
            lightning(context, phase, base, height, sigma, dt);
        }
        if (massSum > 0) grid.plumeHeight = heightSum / massSum;
        if (grid == null) return;
        grid.parallel = context.parallel();

        if (grid.airborneTotal() > 0) {
            grid.transport(dt, wind.at(context.time()), config.diffusivity);
            grid.settle(dt, config.settlingVelocities, grid.plumeHeight);
            if (phases.isEmpty() && grid.airborneTotal() < config.minAirborneMass) grid.discardAirborne();
        }
        grid.applyDeposits(terrain.world(), config.depositBulkDensity, config.depositUpdateThickness,
                units.unit(DepositType.FALL, context.time(), Double.NaN), molten);
    }

    private java.util.function.BiPredicate<Integer, Integer> molten = (x, z) -> false;

    /**
     * Tells the ash fall which columns are covered by molten lava (e.g. {@code (x, z) ->
     * lava.thickness(x, z) > 0}): ash landing there joins the flow instead of forming a layer under it.
     */
    public void setMoltenSurface(java.util.function.BiPredicate<Integer, Integer> molten) {
        this.molten = molten;
    }

    private void lightning(StepContext context, ExplosivePhase phase, Point3 base, double height, double sigma, double dt) {
        // Flash rate scales with the column's mass eruption rate.
        double rate = phase.massEruptionRate();
        if (rate < config.lightningMinMassEruptionRate || height <= 0) return;
        double flashesPerSecond = Math.min(config.maxLightningPerSecond,
                config.lightningPerMassRate * rate);
        SimRandom random = context.random();
        int flashes = random.nextPoisson(flashesPerSecond * dt);
        for (int i = 0; i < flashes; i++) {
            double x = base.x() + random.nextGaussian() * sigma * 0.5;
            double z = base.z() + random.nextGaussian() * sigma * 0.5;
            double y = base.y() + height * (0.4 + 0.6 * random.nextDouble());
            context.outbox().emit(new VolcanicLightning(context.time(), id, new Point3(x, y, z)));
        }
    }

    /**
     * Ash fall aggregated over square regions. A region is announced when ash starts falling on it,
     * when its fall rate or airborne load changes by {@code ashEventChangeFraction}, at least every
     * {@code ashEventRefreshSeconds}, and once with zero rates when it clears.
     */
    private void emitAshFall(StepContext context) {
        int r = Math.max(1, config.ashEventRegionCells);
        int regions = (grid.cells + r - 1) / r;
        for (int rj = 0; rj < regions; rj++) {
            for (int ri = 0; ri < regions; ri++) {
                int region = rj * regions + ri;
                double[] last = ashReports.get(region);
                double rate = 0, load = 0;
                int count = 0;
                for (int j = rj * r; j < Math.min(grid.cells, (rj + 1) * r); j++) {
                    for (int i = ri * r; i < Math.min(grid.cells, (ri + 1) * r); i++) {
                        int cell = grid.index(i, j);
                        rate += grid.depositionRate[cell];
                        load += grid.airborneAt(cell);
                        count++;
                    }
                }
                double area = count * grid.cellArea();
                double fallRate = rate / area;
                double airborneLoad = load / area;
                double half = r * grid.cellMeters() / 2;
                Point3 center = new Point3((grid.originX + (double) ri * r * grid.cellColumns) * grid.metersPerColumn + half, 0,
                        (grid.originZ + (double) rj * r * grid.cellColumns) * grid.metersPerColumn + half);
                if (fallRate < config.ashFallRateThreshold && airborneLoad < config.ashLoadThreshold) {
                    if (last != null) {
                        ashReports.remove(region);
                        context.outbox().emit(new AshFall(context.time(), id, center, half, 0, 0));
                    }
                    continue;
                }
                boolean report = last == null
                        || changed(fallRate, last[0], config.ashFallRateThreshold)
                        || changed(airborneLoad, last[1], config.ashLoadThreshold)
                        || context.time() - last[2] >= config.ashEventRefreshSeconds;
                if (!report) continue;
                ashReports.put(region, new double[] {fallRate, airborneLoad, context.time()});
                context.outbox().emit(new AshFall(context.time(), id, center, half, fallRate, airborneLoad));
            }
        }
    }

    /** Relative change beyond {@code ashEventChangeFraction}, ignoring changes below the threshold. */
    private boolean changed(double now, double before, double threshold) {
        return Math.abs(now - before) >= config.ashEventChangeFraction * Math.max(before, threshold);
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    // ── Persistence ──

    @Override
    public void saveState(StateWriter writer) {
        JsonObject out = writer.json();
        JsonArray phaseStates = new JsonArray();
        for (ExplosivePhase p : phases.values()) phaseStates.add(savePhase(p));
        out.add("phases", phaseStates);
        JsonObject windState = new JsonObject();
        wind.save(windState);
        out.add("wind", windState);
        out.addProperty("nextBombId", nextBombId);
        JsonArray bombStates = new JsonArray();
        for (Bomb bomb : bombs) bombStates.add(bomb.save());
        out.add("bombs", bombStates);
        if (grid != null) {
            JsonObject gridState = new JsonObject();
            grid.save(gridState);
            out.add("ash", gridState);
            writer.field("ash", 1).put(0, 0, grid.arrays());
        }
        JsonArray reports = new JsonArray();
        for (Map.Entry<Integer, double[]> e : ashReports.entrySet()) {
            JsonArray entry = new JsonArray();
            entry.add(e.getKey());
            entry.add(Double.doubleToRawLongBits(e.getValue()[0]));
            entry.add(Double.doubleToRawLongBits(e.getValue()[1]));
            entry.add(Double.doubleToRawLongBits(e.getValue()[2]));
            reports.add(entry);
        }
        out.add("ashReports", reports);
    }

    @Override
    public void loadState(StateReader reader) {
        JsonObject in = reader.json();
        phases.clear();
        if (in.has("phases")) {
            for (JsonElement e : in.getAsJsonArray("phases")) {
                ExplosivePhase p = loadPhase(e.getAsJsonObject());
                phases.put(p.vent().id(), p);
            }
        } else if (in.has("phase")) { // saves from before one phase per vent
            ExplosivePhase p = loadPhase(in.getAsJsonObject("phase"));
            phases.put(p.vent().id(), p);
        }
        wind.load(in.getAsJsonObject("wind"));
        nextBombId = in.get("nextBombId").getAsLong();
        bombs.clear();
        for (JsonElement e : in.getAsJsonArray("bombs")) bombs.add(Bomb.load(e.getAsJsonObject()));
        grid = null;
        if (in.has("ash")) {
            grid = AshGrid.load(in.getAsJsonObject("ash"), reader.field("ash").get(0, 0));
            if (grid.cells != config.gridCells) {
                throw new IllegalStateException("Saved ash grid of " + grid.cells + " cells does not match config "
                        + config.gridCells);
            }
        }
        ashReports.clear();
        for (JsonElement e : in.getAsJsonArray("ashReports")) {
            JsonArray entry = e.getAsJsonArray();
            ashReports.put(entry.get(0).getAsInt(), new double[] {
                Double.longBitsToDouble(entry.get(1).getAsLong()),
                Double.longBitsToDouble(entry.get(2).getAsLong()),
                Double.longBitsToDouble(entry.get(3).getAsLong())});
        }
    }

    private static JsonObject savePhase(ExplosivePhase p) {
        JsonObject out = new JsonObject();
        VentSite v = p.vent();
        JsonObject vent = new JsonObject();
        vent.addProperty("id", v.id());
        vent.add("position", v.position().toJson());
        vent.addProperty("kind", v.kind().name());
        vent.addProperty("craterRadius", v.craterRadiusM());
        vent.addProperty("fissureAngle", v.fissureAngleRad());
        vent.addProperty("fissureLength", v.fissureLengthM());
        out.add("vent", vent);
        out.addProperty("massEruptionRate", p.massEruptionRate());
        out.addProperty("gasFraction", p.gasFraction());
        out.addProperty("overpressure", p.overpressureMPa());
        out.addProperty("temperature", p.temperatureC());
        out.addProperty("silica", p.silicaWt());
        out.addProperty("ballisticFraction", p.ballisticFraction());
        JsonArray fractions = new JsonArray();
        for (double w : p.grainSize().weights()) fractions.add(w);
        out.add("grainSize", fractions);
        return out;
    }

    private static ExplosivePhase loadPhase(JsonObject in) {
        JsonObject v = in.getAsJsonObject("vent");
        VentSite vent = new VentSite(
                v.get("id").getAsString(),
                Point3.fromJson(v.get("position")),
                VentKind.valueOf(v.get("kind").getAsString()),
                v.get("craterRadius").getAsDouble(),
                v.get("fissureAngle").getAsDouble(),
                v.get("fissureLength").getAsDouble());
        JsonArray f = in.getAsJsonArray("grainSize");
        double[] fractions = new double[f.size()];
        for (int i = 0; i < fractions.length; i++) fractions[i] = f.get(i).getAsDouble();
        return new ExplosivePhase(
                vent,
                in.get("massEruptionRate").getAsDouble(),
                in.get("gasFraction").getAsDouble(),
                in.get("overpressure").getAsDouble(),
                in.get("temperature").getAsDouble(),
                in.get("silica").getAsDouble(),
                in.get("ballisticFraction").getAsDouble(),
                new GrainSizeDistribution(fractions));
    }
}
