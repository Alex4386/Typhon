package me.alex4386.typhon.engine.assembly;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import me.alex4386.typhon.engine.assembly.SurfaceEvents.BurstKind;
import me.alex4386.typhon.engine.dike.DikePropagation;
import me.alex4386.typhon.engine.geothermal.Geothermal;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.lava.LavaSource;
import me.alex4386.typhon.engine.magma.ConduitBurst;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.massflow.ColumnCollapse;
import me.alex4386.typhon.engine.massflow.PyroclasticFlows;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.random.SimRandom;
import me.alex4386.typhon.engine.seismic.SeismicityModel;
import me.alex4386.typhon.engine.sim.SimTime;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.tephra.Ballistics;
import me.alex4386.typhon.engine.tephra.ExplosivePhase;
import me.alex4386.typhon.engine.tephra.GrainSizeDistribution;
import me.alex4386.typhon.engine.tephra.TephraSubsystem;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.EruptiveRegime;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;
import me.alex4386.typhon.engine.world.BlockId;

/**
 * Turns the magma chamber's eruption into surface activity.
 *
 * <ul>
 *   <li>Chooses the erupting vents: the summit vents, or the flank fissures a dike opened (a fissure
 *       opening while the chamber is quiet starts a flank eruption at the current pressure).
 *   <li>Follows the chamber's conduit regime: fountains, open vents and domes effuse lava; a
 *       fragmenting conduit feeds a sustained explosive column.
 *   <li>Turns the chamber's discrete explosions (Strombolian slugs, Vulcanian plug failures) into
 *       bomb salvos, short ash puffs and explosion quakes.
 *   <li>Makes the eruption phreatomagmatic (Surtseyan) while open sea or lake water reaches the vent
 *       at shallow depth: magma–water explosions with fine ash, cock's-tail jets and steam, no lava.
 *       Wet jet fallout builds a tuff ring around the vent; once its rim stands above the water the
 *       vent is sealed off from the sea and effusion takes over (as at Surtsey in April 1964).
 *   <li>Checks explosive columns for collapse (Woods 1988) and feeds the collapsing share into
 *       pyroclastic density currents; feeds heat from effusing vents into the geothermal field.
 * </ul>
 *
 * <p>Physics stays in real units: lava receives the volume scaled by
 * {@link VolcanoScaling#volumeScale()}, while tephra and mass flows take real rates and apply the
 * scaling themselves. Register after the chamber, dikes and seismicity and before the lava, tephra
 * and mass-flow subsystems so changes apply in the same tick.
 */
public final class VolcanoCoupler implements Subsystem {
    /** Relative change in explosive rate that restarts the explosive phase with new parameters. */
    static final double PHASE_UPDATE_THRESHOLD = 0.25;
    /** Share of a collapsing column's mass that falls back as pyroclastic flows. */
    static final double COLLAPSE_SHARE = 0.5;

    /**
     * Water depth (real m) below which magma–water interaction is explosive. Deeper, hydrostatic
     * pressure suppresses steam expansion and lava erupts as pillows (Surtseyan activity is
     * typically confined to the upper ~100–200 m; Kokelaar 1986).
     */
    static final double PHREATOMAGMATIC_MAX_DEPTH_M = 120;
    /** Share of the vent area that must be submerged to start / keep phreatomagmatic activity. */
    static final double PHREATOMAGMATIC_START_FRACTION = 0.25;
    static final double PHREATOMAGMATIC_STOP_FRACTION = 0.1;
    /** Mass of sea water flashed to steam per unit mass of magma (Surtseyan ~0.1–0.3). */
    static final double WATER_MAGMA_RATIO = 0.2;
    /** Effective expanding-steam mass fraction and pressure driving the jets (most steam condenses). */
    static final double STEAM_DRIVE_FRACTION = 0.05;
    static final double STEAM_DRIVE_PRESSURE_MPA = 0.3;
    static final double STEAM_DRIVE_TEMPERATURE_C = 300;
    /** Mean interval between cock's-tail jets (real s; Surtsey: every few seconds to minutes). */
    static final double JET_INTERVAL_SECONDS = 20;
    /** Fine-ash-rich grain size of phreatomagmatic fragmentation. */
    static final GrainSizeDistribution PHREATOMAGMATIC_GRAIN = GrainSizeDistribution.of(0.10, 0.20, 0.35, 0.35);
    /** Ticks between steam telemetry events. */
    static final int STEAM_EVENT_TICKS = 400;
    /**
     * Partition of phreatomagmatic ejecta: wet jet/surge fallout building the tuff ring near the vent,
     * ballistic blocks in the cock's-tail jets, and the remainder lofted as fine ash in the column.
     */
    static final double NEAR_VENT_FALLOUT_SHARE = 0.4;
    static final double JET_BALLISTIC_SHARE = 0.1;
    /** Bulk density of fresh wet tuff (kg/m³). */
    static final double TUFF_BULK_DENSITY = 1500;
    /** Width (blocks) of the tuff ring beyond the crater rim. */
    static final int TUFF_RING_WIDTH = 6;
    static final int MAX_TUFF_BLOCKS_PER_STEP = 64;
    static final BlockId TUFF = BlockId.minecraft("tuff");
    static final BlockId WATER = BlockId.minecraft("water");

    private final String volcanoId;
    private final MagmaChamber chamber;
    private final SeismicityModel seismicity;
    private final List<VentSite> baseVents;
    private final DikePropagation dikes;
    private final TerrainModel terrain;
    private final LavaFlow lava;
    private final TephraSubsystem tephra;
    private final PyroclasticFlows pdc;
    private final Geothermal geothermal;
    private final VolcanoScaling scaling;
    private final double ballisticFraction;

    private final TreeSet<String> activeLavaSources = new TreeSet<>();
    private final Set<String> eruptionVents = new LinkedHashSet<>();
    private int knownFissures;
    private boolean flankPending;
    private double explosiveRate;
    private boolean explosivePhreatomagmatic;
    private String collapseSource;
    private long burstPhaseUntil = -1;
    private boolean phreatomagmatic;
    private double waterDepthM;
    private long nextSteamEventTick;
    /** Fractional tuff thickness (blocks) waiting to become whole blocks, keyed by packed x/z. */
    private final TreeMap<Long, Double> tuffDebt = new TreeMap<>();

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
        this.ballisticFraction = ballisticFraction;
    }

    @Override
    public String id() {
        return "coupler:" + volcanoId;
    }

    @Override
    public int interval() {
        return 20;
    }

    @Override
    public void step(StepContext context) {
        watchFissures();
        List<ConduitBurst> bursts = chamber.drainBursts();

        double rate = chamber.eruptionRate();
        if (rate <= 0) {
            stopLava();
            stopExplosive();
            endBurstPhaseIfDue(context.tick(), true);
            setPhreatomagmatic(context, false, null);
            if (!flankPending) eruptionVents.clear();
            return;
        }
        flankPending = false;
        if (eruptionVents.isEmpty()) {
            for (VentSite vent : baseVents) eruptionVents.add(vent.id());
        }
        List<VentSite> vents = activeVents();
        VentSite main = vents.get(0);

        updatePhreatomagmatic(context, main);
        boolean sustained = phreatomagmatic || chamber.eruptiveRegime() == EruptiveRegime.EXPLOSIVE;

        if (sustained) {
            stopLava();
            updateExplosive(main, rate);
        } else {
            updateLava(vents, rate);
            stopExplosive();
        }

        for (ConduitBurst burst : bursts) {
            fireBurst(context, main, burst, sustained);
        }
        if (phreatomagmatic) {
            fireJets(context, main, rate);
            buildTuffRing(context, main, rate);
            if (context.tick() >= nextSteamEventTick) {
                double steam = rate * ExplosivePhase.DRE_DENSITY * WATER_MAGMA_RATIO;
                context.outbox().emit(new SurfaceEvents.PhreatomagmaticSteam(
                        context.tick(), volcanoId, main.position(), steam, waterDepthM));
                nextSteamEventTick = context.tick() + STEAM_EVENT_TICKS;
            }
        }
        endBurstPhaseIfDue(context.tick(), sustained);
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

    // ── Lava ──

    private void updateLava(List<VentSite> vents, double realRate) {
        double perVent = realRate * scaling.volumeScale() / vents.size();
        Set<String> wanted = new TreeSet<>();
        for (VentSite vent : vents) {
            String sourceId = sourceId(vent);
            wanted.add(sourceId);
            if (activeLavaSources.add(sourceId)) {
                lava.addSource(LavaSource.atVent(vent, perVent, chamber.temperatureC(), chamber.silicaWt(), chamber.ventWaterWt())
                        .withId(sourceId));
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

    // ── Sustained explosive columns ──

    private void updateExplosive(VentSite vent, double realRate) {
        boolean restart = explosiveRate <= 0
                || explosivePhreatomagmatic != phreatomagmatic
                || Math.abs(realRate - explosiveRate) > PHASE_UPDATE_THRESHOLD * explosiveRate;
        if (!restart) return;
        explosiveRate = realRate;
        explosivePhreatomagmatic = phreatomagmatic;
        burstPhaseUntil = -1; // the sustained column takes over any ash puff

        double mass = realRate * ExplosivePhase.DRE_DENSITY;
        ExplosivePhase phase;
        if (phreatomagmatic) {
            // Steam-driven: fine ash, low column; ballistics come from the cock's-tail jets.
            double lofted = mass * (1 - NEAR_VENT_FALLOUT_SHARE - JET_BALLISTIC_SHARE);
            phase = new ExplosivePhase(vent, lofted, STEAM_DRIVE_FRACTION, STEAM_DRIVE_PRESSURE_MPA,
                    STEAM_DRIVE_TEMPERATURE_C, chamber.silicaWt(), 0, PHREATOMAGMATIC_GRAIN);
        } else {
            phase = ExplosivePhase.fromMagma(vent, chamber, ballisticFraction);
            phase = new ExplosivePhase(phase.vent(), mass, phase.gasFraction(), phase.overpressureMPa(),
                    phase.temperatureC(), phase.silicaWt(), phase.ballisticFraction(), phase.grainSize());
        }
        double columnMass = phase.massEruptionRate();
        double collapsing = collapseShare(phase, columnMass);
        tephra.startPhase(new ExplosivePhase(phase.vent(), columnMass * (1 - collapsing), phase.gasFraction(),
                phase.overpressureMPa(), phase.temperatureC(), phase.silicaWt(), phase.ballisticFraction(), phase.grainSize()));
        updateCollapse(vent, columnMass * collapsing, phase.temperatureC());
    }

    /** Share of the column falling back as PDCs: {@link #COLLAPSE_SHARE} if the column is unstable. */
    private double collapseShare(ExplosivePhase phase, double massRate) {
        if (pdc == null || massRate <= 0) return 0;
        double gas = phase.gasFraction();
        if (!(gas > 0 && gas < 1)) return COLLAPSE_SHARE; // no gas thrust: the jet cannot become buoyant
        double exitSpeed = Ballistics.gasThrustExitSpeed(gas, phase.temperatureC(), phase.overpressureMPa());
        if (!(exitSpeed > 0)) return COLLAPSE_SHARE;
        return ColumnCollapse.analyze(massRate, exitSpeed, gas, phase.temperatureC()).collapses() ? COLLAPSE_SHARE : 0;
    }

    private void updateCollapse(VentSite vent, double massRate, double temperatureC) {
        if (pdc == null) return;
        if (collapseSource != null) {
            pdc.removeSource(collapseSource);
            collapseSource = null;
        }
        if (massRate > 0) {
            collapseSource = pdc.columnCollapse(volcanoId, vent.position(), Math.max(1, vent.craterRadius()), massRate,
                    1.0, temperatureC);
        }
    }

    private void stopExplosive() {
        if (explosiveRate > 0) tephra.stopPhase();
        explosiveRate = 0;
        explosivePhreatomagmatic = false;
        if (collapseSource != null && pdc != null) pdc.removeSource(collapseSource);
        collapseSource = null;
    }

    // ── Discrete explosions ──

    /**
     * A Strombolian or Vulcanian explosion: a bomb salvo, a short ash puff (unless a sustained column
     * is already running) and an explosion quake.
     */
    private void fireBurst(StepContext context, VentSite vent, ConduitBurst burst, boolean sustained) {
        boolean strombolian = burst.kind() == ConduitBurst.Kind.STROMBOLIAN;
        double ballisticShare = strombolian ? 0.8 : 0.2;
        double speed = Ballistics.gasThrustExitSpeed(burst.gasMassFraction(), burst.temperatureC(), burst.overpressureMPa());
        tephra.launchSalvo(vent, burst.ejectaMassKg() * ballisticShare, speed, 0, strombolian ? 20 : 30,
                burst.silicaWt(), strombolian ? 40 : 120);
        if (!sustained) {
            GrainSizeDistribution grain = strombolian ? GrainSizeDistribution.STROMBOLIAN : GrainSizeDistribution.VULCANIAN;
            startAshPuff(context.tick(), vent, burst.ejectaMassKg() * (1 - ballisticShare), burst.durationSeconds(),
                    burst.gasMassFraction(), burst.overpressureMPa(), burst.temperatureC(), burst.silicaWt(), grain);
        }
        double energy = 0.5 * burst.ejectaMassKg() * speed * speed;
        if (seismicity != null) seismicity.queueExplosion(vent.position().offset(0, -2, 0), energy);
        context.outbox().emit(new SurfaceEvents.ExplosiveBurst(context.tick(), volcanoId,
                strombolian ? BurstKind.STROMBOLIAN : BurstKind.VULCANIAN, vent.position(), burst.ejectaMassKg(),
                burst.gasMassKg(), speed, energy));
    }

    /** Cock's-tail jets of a submerged vent: Poisson bursts of inclined bombs. */
    private void fireJets(StepContext context, VentSite vent, double rate) {
        SimRandom random = context.random();
        // Jets are timed in physical time; the chamber's rate is per (compressed) simulated second.
        double compression = scaling.eruptiveTimeCompression();
        double physicalSeconds = SimTime.ticksToSeconds(interval()) * compression;
        int jets = Math.min(5, random.nextPoisson(physicalSeconds / JET_INTERVAL_SECONDS));
        double mass = rate / compression * ExplosivePhase.DRE_DENSITY * JET_INTERVAL_SECONDS * JET_BALLISTIC_SHARE;
        for (int i = 0; i < jets; i++) {
            double speed = Ballistics.gasThrustExitSpeed(STEAM_DRIVE_FRACTION, STEAM_DRIVE_TEMPERATURE_C, STEAM_DRIVE_PRESSURE_MPA);
            tephra.launchSalvo(vent, mass, speed, 40, 15, chamber.silicaWt(), 30);
            double energy = 0.5 * mass * speed * speed;
            if (seismicity != null) seismicity.queueExplosion(vent.position(), energy);
            context.outbox().emit(new SurfaceEvents.ExplosiveBurst(context.tick(), volcanoId, BurstKind.SURTSEYAN_JET,
                    vent.position(), mass, mass * WATER_MAGMA_RATIO, speed, energy));
        }
    }

    private void startAshPuff(long tick, VentSite vent, double ashMassKg, double durationSeconds, double gasFraction,
            double overpressureMPa, double temperatureC, double silicaWt, GrainSizeDistribution grain) {
        double gameSeconds = durationSeconds / scaling.eruptiveTimeCompression();
        tephra.startPhase(new ExplosivePhase(vent, ashMassKg / durationSeconds, Math.min(1, gasFraction), overpressureMPa,
                temperatureC, silicaWt, 0, grain));
        burstPhaseUntil = Math.max(burstPhaseUntil, tick + Math.max(1, SimTime.secondsToTicks(gameSeconds)));
    }

    private void endBurstPhaseIfDue(long tick, boolean sustained) {
        if (burstPhaseUntil < 0 || tick < burstPhaseUntil) return;
        if (!sustained) tephra.stopPhase();
        burstPhaseUntil = -1;
    }

    // ── Magma–water interaction ──

    /**
     * Water reaches the vent when the vent floor is submerged at shallow depth and the crater rim is
     * open to the surrounding water (a ring of columns just outside the crater is submerged).
     */
    private void updatePhreatomagmatic(StepContext context, VentSite vent) {
        if (terrain == null) return;
        BlockPos c = vent.position();
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
        if (ventColumns == 0 || rimColumns == 0) return;
        double depth = ventSubmerged == 0 ? 0 : depthSum / ventSubmerged * scaling.metersPerBlock();
        double open = rimSubmerged / (double) rimColumns;
        boolean wet = ventSubmerged * 2 >= ventColumns;
        boolean active = phreatomagmatic
                ? wet && open >= PHREATOMAGMATIC_STOP_FRACTION && depth <= 1.1 * PHREATOMAGMATIC_MAX_DEPTH_M
                : wet && open >= PHREATOMAGMATIC_START_FRACTION && depth <= PHREATOMAGMATIC_MAX_DEPTH_M;
        waterDepthM = depth;
        setPhreatomagmatic(context, active, vent);
    }

    /**
     * Wet jet and base-surge fallout around a phreatomagmatic vent: {@link #NEAR_VENT_FALLOUT_SHARE}
     * of the erupted mass settles as tuff in a ring peaking just outside the crater rim, raising the
     * ground block by block.
     */
    private void buildTuffRing(StepContext context, VentSite vent, double rate) {
        if (terrain == null) return;
        // rate is per simulated second, so the volume erupted this step is rate × step length.
        double bulkBlocks = rate * SimTime.ticksToSeconds(interval()) * ExplosivePhase.DRE_DENSITY
                * NEAR_VENT_FALLOUT_SHARE / TUFF_BULK_DENSITY * scaling.volumeScale();
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
                terrain.setGround(x, z, y, TUFF);
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
        context.outbox().emit(new SurfaceEvents.PhreatomagmaticChanged(context.tick(), volcanoId, active, at, waterDepthM));
    }

    private String sourceId(VentSite vent) {
        return volcanoId + "/" + vent.id();
    }

    public boolean effusing() {
        return !activeLavaSources.isEmpty();
    }

    /** True while a sustained explosive column (magmatic or phreatomagmatic) is erupting. */
    public boolean explosive() {
        return explosiveRate > 0;
    }

    /** True while water reaching the vent makes the eruption phreatomagmatic. */
    public boolean phreatomagmatic() {
        return phreatomagmatic;
    }

    public boolean columnCollapsing() {
        return collapseSource != null;
    }

    @Override
    public void saveState(JsonObject out) {
        JsonArray sources = new JsonArray();
        activeLavaSources.forEach(sources::add);
        out.add("lavaSources", sources);
        JsonArray vents = new JsonArray();
        eruptionVents.forEach(vents::add);
        out.add("eruptionVents", vents);
        out.addProperty("knownFissures", knownFissures);
        out.addProperty("flankPending", flankPending);
        out.addProperty("explosiveRate", explosiveRate);
        out.addProperty("explosivePhreatomagmatic", explosivePhreatomagmatic);
        if (collapseSource != null) out.addProperty("collapseSource", collapseSource);
        out.addProperty("burstPhaseUntil", burstPhaseUntil);
        out.addProperty("phreatomagmatic", phreatomagmatic);
        out.addProperty("waterDepthM", waterDepthM);
        out.addProperty("nextSteamEventTick", nextSteamEventTick);
        JsonObject debt = new JsonObject();
        for (Map.Entry<Long, Double> e : tuffDebt.entrySet()) debt.addProperty(Long.toString(e.getKey()), e.getValue());
        out.add("tuffDebt", debt);
    }

    @Override
    public void loadState(JsonObject in) {
        activeLavaSources.clear();
        for (JsonElement e : in.getAsJsonArray("lavaSources")) activeLavaSources.add(e.getAsString());
        eruptionVents.clear();
        if (in.has("eruptionVents")) {
            for (JsonElement e : in.getAsJsonArray("eruptionVents")) eruptionVents.add(e.getAsString());
        }
        knownFissures = in.has("knownFissures") ? in.get("knownFissures").getAsInt() : 0;
        flankPending = in.has("flankPending") && in.get("flankPending").getAsBoolean();
        explosiveRate = in.get("explosiveRate").getAsDouble();
        explosivePhreatomagmatic = in.has("explosivePhreatomagmatic") && in.get("explosivePhreatomagmatic").getAsBoolean();
        collapseSource = in.has("collapseSource") ? in.get("collapseSource").getAsString() : null;
        burstPhaseUntil = in.has("burstPhaseUntil") ? in.get("burstPhaseUntil").getAsLong() : -1;
        phreatomagmatic = in.has("phreatomagmatic") && in.get("phreatomagmatic").getAsBoolean();
        waterDepthM = in.has("waterDepthM") ? in.get("waterDepthM").getAsDouble() : 0;
        nextSteamEventTick = in.has("nextSteamEventTick") ? in.get("nextSteamEventTick").getAsLong() : 0;
        tuffDebt.clear();
        if (in.has("tuffDebt")) {
            for (Map.Entry<String, JsonElement> e : in.getAsJsonObject("tuffDebt").entrySet()) {
                tuffDebt.put(Long.parseLong(e.getKey()), e.getValue().getAsDouble());
            }
        }
    }
}
