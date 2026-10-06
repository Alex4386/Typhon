package me.alex4386.typhon.engine.assembly;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import me.alex4386.typhon.engine.assembly.SurfaceEvents.BurstKind;
import me.alex4386.typhon.engine.dike.DikePropagation;
import me.alex4386.typhon.engine.geothermal.Geothermal;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.lava.LavaSource;
import me.alex4386.typhon.engine.magma.ConduitBurst;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.magma.conduit.ConduitSolution;
import me.alex4386.typhon.engine.massflow.ColumnCollapse;
import me.alex4386.typhon.engine.massflow.PyroclasticFlows;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.seismic.SeismicityModel;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.subsurface.HydrothermalField;
import me.alex4386.typhon.engine.tephra.Ballistics;
import me.alex4386.typhon.engine.tephra.ExplosivePhase;
import me.alex4386.typhon.engine.tephra.GrainSizeDistribution;
import me.alex4386.typhon.engine.tephra.TephraSubsystem;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.UnitSource;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;

/**
 * Turns the magma chamber's conduit flow into surface activity.
 *
 * <ul>
 *   <li>Chooses the erupting vents: the summit vents, or the flank fissures a dike opened (a fissure
 *       opening while the chamber is quiet starts a flank eruption at the current pressure).
 *   <li>Partitions the steady conduit flow continuously ({@link VentPartition}) into lava (coherent
 *       effusion and clastogenic lava from hot fountain fall-back), ballistic fall-back, an eruption
 *       column and the share of it that collapses into pyroclastic density currents. Nothing selects a
 *       style: a fountain, a dome, a Plinian column or a mixture of them follows from the flow.
 *   <li>Brings external water to the vent: sea or lake water over an open crater and groundwater from
 *       the aquifer the conduit crosses set a water/magma ratio, and the magma–water fragmentation it
 *       drives adds fine ash, steam, cock's-tail jets and a tuff ring of wet fallout. Tuff that walls
 *       the crater off from the sea cuts the water supply (Surtsey, 1964). The vent's ambient pressure
 *       and water table are reported back to the chamber's conduit.
 *   <li>Turns the chamber's discrete explosions (slug bursts, plug failures) into bomb salvos, short
 *       ash puffs and explosion quakes; their ballistic share follows the same clast physics.
 * </ul>
 *
 * <p>Physics stays in real units: lava, tephra and mass flows all take real rates (m³/s, kg/s) and
 * map them onto the block world themselves (the lava grid is {@link VolcanoScaling#metersPerBlock()}
 * wide per column, set by {@link VolcanoSystem}). Register after the chamber, dikes and seismicity
 * and before the lava, tephra and mass-flow subsystems so changes apply in the same step.
 */
public final class VolcanoCoupler implements Subsystem {
    /** Relative change in column parameters that restarts the explosive phase with new parameters. */
    static final double PHASE_UPDATE_THRESHOLD = 0.25;
    /** Column mass flux (kg/s, physical) below which no column is sustained (a few puffs at most). */
    static final double MIN_COLUMN_MASS_FLUX = 1;
    /** Lava flux (m³/s DRE, physical) below which no lava source is kept. */
    static final double MIN_LAVA_RATE = 1e-6;
    /** Share of the magma fragmented by water that starts / keeps the phreatomagmatic descriptor. */
    static final double PHREATOMAGMATIC_START_SHARE = 0.2;
    static final double PHREATOMAGMATIC_STOP_SHARE = 0.1;
    /** Fine-ash-rich grain size of magma–water fragmentation (bursts of wet ash). */
    static final GrainSizeDistribution PHREATOMAGMATIC_GRAIN = GrainSizeDistribution.of(0.10, 0.20, 0.35, 0.35);
    /** Simulated seconds between steam telemetry events. */
    static final double STEAM_EVENT_SECONDS = 20;
    /** Bulk density of fresh wet tuff (kg/m³). */
    static final double TUFF_BULK_DENSITY = 1500;
    /** Width (blocks) of the tuff ring beyond the crater rim. */
    static final int TUFF_RING_WIDTH = 6;
    static final int MAX_TUFF_BLOCKS_PER_STEP = 64;
    static final int MAX_BOMBS_PER_SALVO = 10;
    static final BlockId TUFF = BlockId.minecraft("tuff");
    static final BlockId WATER = BlockId.minecraft("water");
    /** Median clast (m) of a slug burst tearing fluid magma, and of a plug shattering. */
    static final double SLUG_CLAST_M = 0.03;
    /**
     * Ash/lapilli boundary (m): 2 mm (White and Houghton 2006). A discrete explosion's clasts above it
     * are thrown out ballistically and land around the vent; only the ash rises with its cloud.
     */
    static final double LAPILLI_MIN_M = 2e-3;
    static final double PLUG_CLAST_M = 2e-3;

    private final String volcanoId;
    private final MagmaChamber chamber;
    private final SeismicityModel seismicity;
    private final List<VentSite> baseVents;
    private final DikePropagation dikes;
    private final TerrainModel terrain;
    private UnitSource units = UnitSource.UNATTRIBUTED;
    private HydrothermalField ground;
    private final LavaFlow lava;
    private final TephraSubsystem tephra;
    private final PyroclasticFlows pdc;
    private final Geothermal geothermal;
    private final VolcanoScaling scaling;

    private final TreeSet<String> activeLavaSources = new TreeSet<>();
    private final Set<String> eruptionVents = new LinkedHashSet<>();
    private int knownFissures;
    private boolean flankPending;
    /** Column in progress: simulated mass rate (kg per simulated s), collapse share, gas fraction. */
    private double explosiveRate;
    private double explosiveCollapse;
    private double explosiveGas;
    private String collapseSource;
    /** Simulation time (s) at which the current ash puff ends; negative = none. */
    private double burstPhaseUntil = -1;
    private boolean phreatomagmatic;
    private double waterDepthM;
    private double openWaterFraction;
    private double nextSteamEventTime;
    private VentPartition.Result lastPartition;
    /** Discrete explosions fired so far, by mechanism (for observers such as the style estimate). */
    private long slugBursts;
    private long plugBursts;
    /** Fractional tuff thickness (blocks) waiting to become whole blocks, keyed by packed x/z. */
    private final TreeMap<Long, Double> tuffDebt = new TreeMap<>();
    /** Collapse thresholds by rounded column parameters: a pure function, not saved. */
    private final Map<Long, Double> collapseThresholds = new HashMap<>();

    /**
     * @param ballisticFraction ignored: the ballistic share follows from clast physics (kept so
     *     existing definitions still load)
     */
    public VolcanoCoupler(String volcanoId, MagmaChamber chamber, SeismicityModel seismicity, List<VentSite> vents,
            DikePropagation dikes, TerrainModel terrain, LavaFlow lava, TephraSubsystem tephra, PyroclasticFlows pdc,
            Geothermal geothermal, VolcanoScaling scaling, double ballisticFraction) {
        if (vents.isEmpty()) throw new IllegalArgumentException("A volcano needs at least one vent");
        this.volcanoId = volcanoId;
        this.chamber = chamber;
        this.seismicity = seismicity;
        this.baseVents = List.copyOf(vents);
        this.dikes = dikes;
        this.terrain = terrain;
        this.lava = lava;
        this.tephra = tephra;
        this.pdc = pdc;
        this.geothermal = geothermal;
        this.scaling = scaling;
    }

    @Override
    public String id() {
        return "coupler:" + volcanoId;
    }

    @Override
    public double periodSeconds() {
        return 1.0;
    }

    /** Groundwater model the conduit draws aquifer water from (optional). */
    public void setGround(HydrothermalField ground) {
        this.ground = ground;
    }

    @Override
    public void step(StepContext context) {
        watchFissures();
        List<ConduitBurst> bursts = chamber.drainBursts();

        List<VentSite> vents = eruptionVents.isEmpty() ? baseVents : activeVents();
        VentSite main = vents.isEmpty() ? baseVents.get(0) : vents.get(0);
        VentPartition.Water water = surveyWater(main);
        chamber.setVentEnvironment(VentPartition.ambientPressurePa(water.surfaceDepthM()),
                water.waterTableDepthM());

        double rate = chamber.eruptionRate();
        ConduitSolution flow = chamber.conduitFlow();
        if (rate <= 0 || flow == null) {
            stopLava();
            stopExplosive();
            endBurstPhaseIfDue(context.time(), false);
            setPhreatomagmatic(context, false, null);
            lastPartition = null;
            if (!flankPending) eruptionVents.clear();
            for (ConduitBurst burst : bursts) fireBurst(context, main, burst, false);
            return;
        }
        flankPending = false;
        if (eruptionVents.isEmpty()) {
            for (VentSite vent : baseVents) eruptionVents.add(vent.id());
        }
        vents = activeVents();
        main = vents.get(0);

        VentPartition.Result p = VentPartition.partition(flow, chamber.ventAmbientPressurePa(),
                chamber.config().conduitRadius(), chamber.silicaWt(), water, this::criticalGasFraction);
        lastPartition = p;
        // The chamber's actual outflow (linearised between conduit solutions) sets the totals; the
        // partition sets the shares.
        double physicalMass = chamber.physicalEruptionRate() * ExplosivePhase.DRE_DENSITY;
        double scale = p.magmaMassFlux() > 0 ? physicalMass / p.magmaMassFlux() : 0;
        double compression = scaling.eruptiveTimeCompression();
        double physicalSeconds = context.dtSeconds() * compression;

        double lavaRate = p.lavaMassFlux() * scale / ExplosivePhase.DRE_DENSITY;
        if (lavaRate > MIN_LAVA_RATE) updateLava(vents, lavaRate);
        else stopLava();

        double column = p.columnMassFlux() * scale;
        boolean sustained = column >= MIN_COLUMN_MASS_FLUX;
        if (sustained) updateExplosive(main, p, column);
        else stopExplosive();

        double ballistic = p.ballisticMassFlux() * scale * physicalSeconds;
        if (ballistic > 0) {
            tephra.launchSalvo(main, ballistic, p.ballisticSpeed(), 0, 15, chamber.silicaWt(), MAX_BOMBS_PER_SALVO);
        }

        double wetShare = p.magmaMassFlux() > 0 ? p.waterFragmentedMassFlux() / p.magmaMassFlux() : 0;
        setPhreatomagmatic(context, phreatomagmatic ? wetShare >= PHREATOMAGMATIC_STOP_SHARE
                : wetShare >= PHREATOMAGMATIC_START_SHARE, main);
        if (p.jetMassFlux() > 0) fireJets(context, main, p.jetMassFlux() * scale * physicalSeconds, p.jetSpeed());
        if (p.wetFalloutMassFlux() > 0) buildTuffRing(context, main, p.wetFalloutMassFlux() * scale * physicalSeconds);
        if (p.steamMassFlux() > 0 && context.time() >= nextSteamEventTime) {
            context.outbox().emit(new SurfaceEvents.PhreatomagmaticSteam(
                    context.time(), volcanoId, main.position(), p.steamMassFlux() * scale, waterDepthM));
            nextSteamEventTime = context.time() + STEAM_EVENT_SECONDS;
        }

        for (ConduitBurst burst : bursts) fireBurst(context, main, burst, sustained);
        endBurstPhaseIfDue(context.time(), sustained);
    }

    /** Picks up fissures opened by dikes since the last step. */
    private void watchFissures() {
        if (dikes == null) return;
        List<VentSite> opened = dikes.openedVents();
        if (opened.size() <= knownFissures) return;
        for (VentSite vent : opened.subList(knownFissures, opened.size())) {
            if (!chamber.erupting()) {
                // A fresh flank eruption is fed through the new fissures, not the summit.
                if (!flankPending) eruptionVents.clear();
                flankPending = true;
                chamber.requestFlankEruption();
            }
            eruptionVents.add(vent.id());
        }
        knownFissures = opened.size();
    }

    /** All vents of this volcano: the configured ones plus fissures opened by dikes. */
    public List<VentSite> allVents() {
        List<VentSite> all = new ArrayList<>(baseVents);
        if (dikes != null) all.addAll(dikes.openedVents());
        return all;
    }

    /** Vents currently erupting (or about to). */
    public List<VentSite> activeVents() {
        List<VentSite> active = new ArrayList<>();
        for (VentSite vent : allVents()) {
            if (eruptionVents.contains(vent.id())) active.add(vent);
        }
        return active;
    }

    /** The partition of the current eruption's flow at the vent; {@code null} when not erupting. */
    public VentPartition.Result partition() {
        return lastPartition;
    }

    // ── Lava ──

    private void updateLava(List<VentSite> vents, double physicalRate) {
        // Lava sources take the physical rate; the lava field (on this volcano's clock) re-applies the
        // eruptive compression.
        double perVent = physicalRate / vents.size();
        Set<String> wanted = new TreeSet<>();
        for (VentSite vent : vents) {
            String sourceId = sourceId(vent);
            wanted.add(sourceId);
            if (activeLavaSources.add(sourceId)) {
                int unit = units.unit(DepositType.LAVA, chamber.eruptionStartTime(), chamber.temperatureC());
                lava.addSource(LavaSource.atVent(vent, perVent, chamber.temperatureC(), chamber.silicaWt(), chamber.ventWaterWt())
                        .withId(sourceId).withUnit(unit));
            } else {
                lava.setRate(sourceId, perVent);
            }
            if (geothermal != null) {
                int x = vent.position().x();
                int z = vent.position().z();
                geothermal.addLavaHeat(x, z, chamber.temperatureC(), Math.max(1.0, lava.thickness(x, z)));
            }
        }
        for (String sourceId : new ArrayList<>(activeLavaSources)) {
            if (!wanted.contains(sourceId)) {
                lava.removeSource(sourceId);
                activeLavaSources.remove(sourceId);
            }
        }
    }

    private void stopLava() {
        for (String sourceId : activeLavaSources) lava.removeSource(sourceId);
        activeLavaSources.clear();
    }

    // ── Sustained columns ──

    private void updateExplosive(VentSite vent, VentPartition.Result p, double physicalColumn) {
        double compression = scaling.eruptiveTimeCompression();
        double simulated = physicalColumn * compression;
        boolean restart = explosiveRate <= 0
                || Math.abs(simulated - explosiveRate) > PHASE_UPDATE_THRESHOLD * explosiveRate
                || Math.abs(p.collapseFraction() - explosiveCollapse) > 0.1
                || Math.abs(p.columnGasFraction() - explosiveGas) > PHASE_UPDATE_THRESHOLD * explosiveGas;
        if (!restart) return;
        explosiveRate = simulated;
        explosiveCollapse = p.collapseFraction();
        explosiveGas = p.columnGasFraction();
        burstPhaseUntil = -1; // the sustained column takes over any ash puff

        // The phase's gas thrust is set by the jet: an equivalent overpressure that expands the
        // column's gas to its exit velocity.
        double overpressure = equivalentOverpressureMPa(p.columnVelocity(), p.columnGasFraction(), p.columnTemperatureC());
        ExplosivePhase phase = new ExplosivePhase(vent, simulated, Math.min(1, p.columnGasFraction()), overpressure,
                p.columnTemperatureC(), chamber.silicaWt(), 0, p.grainSize(), compression);
        tephra.startPhase(phase.withMassEruptionRate(simulated * (1 - p.collapseFraction())));
        updateCollapse(vent, simulated * p.collapseFraction(), p.columnTemperatureC());
    }

    /** Overpressure (MPa) whose isothermal expansion drives gas fraction {@code n} to speed {@code u}. */
    static double equivalentOverpressureMPa(double u, double n, double temperatureC) {
        if (!(n > 0) || !(u > 0)) return 0;
        double x = u * u / (2 * n * 461.5 * (temperatureC + 273.15));
        return Math.min(100, Math.expm1(Math.min(x, 6)) * 0.101325);
    }

    /**
     * Gas mass fraction below which the column collapses (Woods 1988), by bisection on {@link
     * ColumnCollapse}; cached on rounded parameters.
     */
    private double criticalGasFraction(double massFlux, double velocity, double temperatureC) {
        long key = (Math.round(Math.log10(Math.max(1, massFlux)) * 10) << 32)
                ^ (Math.round(Math.log(Math.max(1, velocity)) * 20) << 16) ^ Math.round(temperatureC / 20);
        Double cached = collapseThresholds.get(key);
        if (cached != null) return cached;
        double m = Math.pow(10, Math.round(Math.log10(Math.max(1, massFlux)) * 10) / 10.0);
        double u = Math.exp(Math.round(Math.log(Math.max(1, velocity)) * 20) / 20.0);
        double t = Math.round(temperatureC / 20) * 20.0;
        double lo = 1e-3;
        double hi = 0.6;
        double critical;
        if (!ColumnCollapse.analyze(m, u, hi, t).collapses()) {
            if (ColumnCollapse.analyze(m, u, lo, t).collapses()) {
                for (int i = 0; i < 16; i++) {
                    double mid = Math.sqrt(lo * hi);
                    if (ColumnCollapse.analyze(m, u, mid, t).collapses()) lo = mid;
                    else hi = mid;
                }
                critical = Math.sqrt(lo * hi);
            } else {
                critical = lo / 2; // buoyant at any realistic gas content
            }
        } else {
            critical = 1; // collapses whatever its gas
        }
        if (collapseThresholds.size() > 4096) collapseThresholds.clear();
        collapseThresholds.put(key, critical);
        return critical;
    }

    private void updateCollapse(VentSite vent, double massRate, double temperatureC) {
        if (pdc == null) return;
        if (collapseSource != null) {
            pdc.removeSource(collapseSource);
            collapseSource = null;
        }
        if (massRate > 0 && explosiveCollapse > 0.01) {
            collapseSource = pdc.columnCollapse(volcanoId, vent.position(), Math.max(1, vent.craterRadius()), massRate,
                    1.0, temperatureC);
        }
    }

    private void stopExplosive() {
        if (explosiveRate > 0) tephra.stopPhase();
        explosiveRate = 0;
        explosiveCollapse = 0;
        explosiveGas = 0;
        if (collapseSource != null && pdc != null) pdc.removeSource(collapseSource);
        collapseSource = null;
    }

    // ── Discrete explosions ──

    /**
     * A slug burst or plug failure: a bomb salvo of the clasts the burst jet cannot carry, a short ash
     * puff of the rest (unless a sustained column is already running) and an explosion quake.
     */
    private void fireBurst(StepContext context, VentSite vent, ConduitBurst burst, boolean sustained) {
        boolean slug = burst.kind() == ConduitBurst.Kind.SLUG;
        if (slug) slugBursts++;
        else plugBursts++;
        double speed = Ballistics.gasThrustExitSpeed(burst.gasMassFraction(), burst.temperatureC(), burst.overpressureMPa());
        double median = slug ? SLUG_CLAST_M : PLUG_CLAST_M;
        double gasDensity = VentPartition.ambientPressurePa(0) / (461.5 * (burst.temperatureC() + 273.15));
        double supported = 3 * VentPartition.DRAG_COEFFICIENT * gasDensity * speed * speed
                / (4 * VentPartition.CLAST_DENSITY * VentPartition.GRAVITY);
        // A burst is momentary: the gas jet decelerates within tens of metres and its cloud rises as a
        // thermal, so it cannot carry lapilli the way a sustained column does. Clasts the jet could not
        // support fly as tracked bombs; lapilli fall around the vent; only ash goes up with the cloud.
        double bombCut = Math.max(LAPILLI_MIN_M, Math.min(supported, VentPartition.BALLISTIC_SIZE));
        double ballisticShare = 1 - VentPartition.lognormalCdf(bombCut, median);
        double ashShare = VentPartition.lognormalCdf(LAPILLI_MIN_M, median);
        double lapilliShare = Math.max(0, 1 - ballisticShare - ashShare);
        double zenithSigma = slug ? 20 : 30;
        tephra.launchSalvo(vent, burst.ejectaMassKg() * ballisticShare, speed, 0, zenithSigma,
                burst.silicaWt(), slug ? 40 : 120);
        tephra.proximalFallout(vent, burst.ejectaMassKg() * lapilliShare, speed, 0, zenithSigma, median,
                LAPILLI_MIN_M, bombCut);
        if (!sustained && ashShare > 0) {
            double[] f = VentPartition.grainFractions(median, LAPILLI_MIN_M);
            startAshPuff(context.time(), vent, burst.ejectaMassKg() * ashShare, burst.durationSeconds(),
                    burst.gasMassFraction(), burst.overpressureMPa(), burst.temperatureC(), burst.silicaWt(),
                    GrainSizeDistribution.of(f[0] + 1e-6, f[1] + 1e-6, f[2] + 1e-6, f[3] + 1e-6));
        }
        double energy = 0.5 * burst.ejectaMassKg() * speed * speed;
        if (seismicity != null) seismicity.queueExplosion(vent.position().offset(0, -2, 0), energy);
        context.outbox().emit(new SurfaceEvents.ExplosiveBurst(context.time(), volcanoId,
                slug ? BurstKind.STROMBOLIAN : BurstKind.VULCANIAN, vent.position(), burst.ejectaMassKg(),
                burst.gasMassKg(), speed, energy));
    }

    /** Cock's-tail jets of water-fragmented magma: one salvo of the coarse wet ejecta of this step. */
    private void fireJets(StepContext context, VentSite vent, double mass, double speed) {
        tephra.launchSalvo(vent, mass, speed, 40, 15, chamber.silicaWt(), MAX_BOMBS_PER_SALVO);
        double energy = 0.5 * mass * speed * speed;
        if (seismicity != null) seismicity.queueExplosion(vent.position(), energy);
        context.outbox().emit(new SurfaceEvents.ExplosiveBurst(context.time(), volcanoId, BurstKind.SURTSEYAN_JET,
                vent.position(), mass, 0, speed, energy));
    }

    private void startAshPuff(double now, VentSite vent, double ashMassKg, double durationSeconds, double gasFraction,
            double overpressureMPa, double temperatureC, double silicaWt, GrainSizeDistribution grain) {
        double compression = scaling.eruptiveTimeCompression();
        double gameSeconds = durationSeconds / compression;
        // The puff lasts durationSeconds of physical time, i.e. gameSeconds of simulated time: inject
        // the whole ash mass over the simulated span, while the column sees the physical rate.
        tephra.startPhase(new ExplosivePhase(vent, ashMassKg / gameSeconds, Math.min(1, gasFraction), overpressureMPa,
                temperatureC, silicaWt, 0, grain, compression));
        burstPhaseUntil = Math.max(burstPhaseUntil, now + gameSeconds);
    }

    private void endBurstPhaseIfDue(double now, boolean sustained) {
        if (burstPhaseUntil < 0 || now < burstPhaseUntil) return;
        if (!sustained) tephra.stopPhase();
        burstPhaseUntil = -1;
    }

    // ── Magma–water interaction ──

    /**
     * Water around the vent: depth of water standing over the crater floor, the share of the ring just
     * outside the crater that is submerged (open to the sea or lake), and the water table below the
     * vent from the groundwater model.
     */
    private VentPartition.Water surveyWater(VentSite vent) {
        BlockPos c = vent.position();
        double table = ground != null && ground.known(c.x(), c.z())
                ? ground.waterTableDepthM(c.x(), c.z()) : Double.POSITIVE_INFINITY;
        if (terrain == null) {
            waterDepthM = 0;
            openWaterFraction = 0;
            return new VentPartition.Water(0, 0, table);
        }
        int crater = Math.max(1, vent.craterRadius());
        int ventColumns = 0;
        int ventSubmerged = 0;
        double depthSum = 0;
        int rimColumns = 0;
        int rimSubmerged = 0;
        int outer = crater + 3;
        for (int dz = -outer; dz <= outer; dz++) {
            for (int dx = -outer; dx <= outer; dx++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > outer * outer) continue;
                TerrainColumn column = terrain.column(c.x() + dx, c.z() + dz);
                if (column == null) continue;
                if (d2 <= crater * crater) {
                    ventColumns++;
                    if (column.submerged()) {
                        ventSubmerged++;
                        depthSum += column.waterDepth();
                    }
                } else {
                    rimColumns++;
                    if (column.submerged()) rimSubmerged++;
                }
            }
        }
        boolean wet = ventColumns > 0 && ventSubmerged * 2 >= ventColumns;
        waterDepthM = wet ? depthSum / ventSubmerged * scaling.metersPerBlock() : 0;
        openWaterFraction = wet && rimColumns > 0 ? rimSubmerged / (double) rimColumns : 0;
        return new VentPartition.Water(waterDepthM, openWaterFraction, table);
    }

    /**
     * Wet jet and base-surge fallout around a water-fragmenting vent: the wet tephra settles as tuff in a
     * ring peaking just outside the crater rim, raising the ground block by block.
     */
    private void buildTuffRing(StepContext context, VentSite vent, double massKg) {
        if (terrain == null) return;
        double bulkBlocks = massKg / TUFF_BULK_DENSITY * scaling.volumeScale();
        int crater = Math.max(1, vent.craterRadius());
        int outer = crater + TUFF_RING_WIDTH;
        double peak = crater + 2;
        BlockPos c = vent.position();
        List<long[]> cells = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0;
        for (int dz = -outer; dz <= outer; dz++) {
            for (int dx = -outer; dx <= outer; dx++) {
                double r = Math.sqrt(dx * dx + dz * dz);
                if (r <= crater || r > outer) continue;
                double w = Math.exp(-(r - peak) * (r - peak) / 8.0);
                cells.add(new long[] {c.x() + dx, c.z() + dz});
                weights.add(w);
                total += w;
            }
        }
        if (total <= 0) return;
        int placed = 0;
        for (int i = 0; i < cells.size(); i++) {
            int x = (int) cells.get(i)[0];
            int z = (int) cells.get(i)[1];
            long key = BlockPos.pack(x, 0, z);
            double debt = tuffDebt.getOrDefault(key, 0.0) + bulkBlocks * weights.get(i) / total;
            TerrainColumn column = terrain.column(x, z);
            while (debt >= 1 && column != null && placed < MAX_TUFF_BLOCKS_PER_STEP) {
                int y = column.groundY() + 1;
                BlockId expected = column.waterY() != TerrainColumn.NO_WATER && column.waterY() >= y ? WATER : BlockId.AIR;
                context.outbox().setBlock(BlockChange.replace(new BlockPos(x, y, z), expected, TUFF));
                terrain.setGround(x, z, y, TUFF, units.unit(DepositType.FALL, context.time(), Double.NaN));
                column = terrain.column(x, z);
                debt -= 1;
                placed++;
            }
            if (debt > 1e-9) tuffDebt.put(key, debt); else tuffDebt.remove(key);
        }
    }

    private void setPhreatomagmatic(StepContext context, boolean active, VentSite vent) {
        if (active == phreatomagmatic) return;
        phreatomagmatic = active;
        BlockPos at = vent != null ? vent.position() : baseVents.get(0).position();
        context.outbox().emit(new SurfaceEvents.PhreatomagmaticChanged(context.time(), volcanoId, active, at, waterDepthM));
    }

    /** Attributes lava sources and tuff-ring deposits to this volcano's eruptions. */
    public void setUnits(UnitSource units) {
        this.units = units;
    }

    private String sourceId(VentSite vent) {
        return volcanoId + "/" + vent.id();
    }

    public boolean effusing() {
        return !activeLavaSources.isEmpty();
    }

    /** True while a sustained column (magmatic and/or phreatomagmatic) is erupting. */
    public boolean explosive() {
        return explosiveRate > 0;
    }

    /** True while water fragments a large share of the erupting magma. */
    public boolean phreatomagmatic() {
        return phreatomagmatic;
    }

    public boolean columnCollapsing() {
        return collapseSource != null;
    }

    /** Slug bursts fired so far. */
    public long slugBursts() {
        return slugBursts;
    }

    /** Plug failures fired so far. */
    public long plugBursts() {
        return plugBursts;
    }

    /** Water depth over the vent (m) at the last survey. */
    public double waterDepthM() {
        return waterDepthM;
    }

    @Override
    public void saveState(StateWriter writer) {
        JsonObject out = writer.json();
        JsonArray sources = new JsonArray();
        activeLavaSources.forEach(sources::add);
        out.add("lavaSources", sources);
        JsonArray vents = new JsonArray();
        eruptionVents.forEach(vents::add);
        out.add("eruptionVents", vents);
        out.addProperty("knownFissures", knownFissures);
        out.addProperty("flankPending", flankPending);
        out.addProperty("explosiveRate", explosiveRate);
        out.addProperty("explosiveCollapse", explosiveCollapse);
        out.addProperty("explosiveGas", explosiveGas);
        if (collapseSource != null) out.addProperty("collapseSource", collapseSource);
        out.addProperty("burstPhaseUntil", burstPhaseUntil);
        out.addProperty("phreatomagmatic", phreatomagmatic);
        out.addProperty("waterDepthM", waterDepthM);
        out.addProperty("openWaterFraction", openWaterFraction);
        out.addProperty("nextSteamEventTime", nextSteamEventTime);
        out.addProperty("slugBursts", slugBursts);
        out.addProperty("plugBursts", plugBursts);
        JsonObject debt = new JsonObject();
        for (Map.Entry<Long, Double> e : tuffDebt.entrySet()) debt.addProperty(Long.toString(e.getKey()), e.getValue());
        out.add("tuffDebt", debt);
    }

    @Override
    public void loadState(StateReader reader) {
        JsonObject in = reader.json();
        activeLavaSources.clear();
        for (JsonElement e : in.getAsJsonArray("lavaSources")) activeLavaSources.add(e.getAsString());
        eruptionVents.clear();
        if (in.has("eruptionVents")) {
            for (JsonElement e : in.getAsJsonArray("eruptionVents")) eruptionVents.add(e.getAsString());
        }
        knownFissures = in.has("knownFissures") ? in.get("knownFissures").getAsInt() : 0;
        flankPending = in.has("flankPending") && in.get("flankPending").getAsBoolean();
        explosiveRate = in.get("explosiveRate").getAsDouble();
        explosiveCollapse = in.has("explosiveCollapse") ? in.get("explosiveCollapse").getAsDouble() : 0;
        explosiveGas = in.has("explosiveGas") ? in.get("explosiveGas").getAsDouble() : 0;
        collapseSource = in.has("collapseSource") ? in.get("collapseSource").getAsString() : null;
        burstPhaseUntil = in.get("burstPhaseUntil").getAsDouble();
        phreatomagmatic = in.has("phreatomagmatic") && in.get("phreatomagmatic").getAsBoolean();
        waterDepthM = in.has("waterDepthM") ? in.get("waterDepthM").getAsDouble() : 0;
        openWaterFraction = in.has("openWaterFraction") ? in.get("openWaterFraction").getAsDouble() : 0;
        nextSteamEventTime = in.get("nextSteamEventTime").getAsDouble();
        slugBursts = in.has("slugBursts") ? in.get("slugBursts").getAsLong() : 0;
        plugBursts = in.has("plugBursts") ? in.get("plugBursts").getAsLong() : 0;
        lastPartition = null;
        tuffDebt.clear();
        if (in.has("tuffDebt")) {
            for (Map.Entry<String, JsonElement> e : in.getAsJsonObject("tuffDebt").entrySet()) {
                tuffDebt.put(Long.parseLong(e.getKey()), e.getValue().getAsDouble());
            }
        }
    }
}
