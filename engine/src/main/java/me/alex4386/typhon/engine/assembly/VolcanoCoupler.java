package me.alex4386.typhon.engine.assembly;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import me.alex4386.typhon.engine.assembly.SurfaceEvents.BurstKind;
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.dike.Dike;
import me.alex4386.typhon.engine.dike.DikePropagation;
import me.alex4386.typhon.engine.geothermal.Geothermal;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.lava.LavaSource;
import me.alex4386.typhon.engine.magma.ConduitBurst;
import me.alex4386.typhon.engine.magma.CrustColumn;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.magma.conduit.ConduitSolution;
import me.alex4386.typhon.engine.massflow.ColumnCollapse;
import me.alex4386.typhon.engine.massflow.PyroclasticFlows;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.seismic.SeismicityModel;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.subsurface.HydrothermalField;
import me.alex4386.typhon.engine.tephra.Ballistics;
import me.alex4386.typhon.engine.tephra.ExplosivePhase;
import me.alex4386.typhon.engine.tephra.GrainSizeDistribution;
import me.alex4386.typhon.engine.tephra.TephraSubsystem;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.LayerFlags;
import me.alex4386.typhon.engine.world.LayerView;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.engine.world.UnitSource;
import me.alex4386.typhon.engine.volcano.VentCommands;
import me.alex4386.typhon.engine.volcano.VentEvents;
import me.alex4386.typhon.engine.volcano.VentKind;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.volcano.VentStatus;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;

/**
 * Turns the magma chamber's conduit flow into surface activity.
 *
 * <ul>
 *   <li>Chooses the erupting vents: the summit vents, or the flank fissures a dike opened (a fissure
 *       opening while the chamber is quiet starts a flank eruption at the current pressure).
 *   <li>Follows each dike-fed fissure's feeder thermally ({@link FissureFeeder}): the flow shares out
 *       by feeder conductance, narrow segments freeze, the eruption localises to a few vents and, once
 *       the flux falls, the feeder freezes and the fissure goes extinct. A frozen fissure is never an
 *       outlet again; later eruptions start from the vents still open. Narrowing feeders throttle the
 *       chamber's outflow ({@link MagmaChamber#setOutletCapacity}).
 *   <li>Applies the user's vent controls ({@link VentCommands}): sealed vents carry no magma, a sealed
 *       summit keeps the roof from failing, removed fissures leave the vent set.
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
 * <p>Physics is in SI units: lava, tephra and mass flows all take real rates (m³/s, kg/s) on the world
 * model's columns. Register after the chamber, dikes and seismicity and before the lava, tephra and
 * mass-flow subsystems so changes apply in the same step.
 */
public final class VolcanoCoupler implements Subsystem {
    /**
     * Relative change in column parameters that is reported as a new explosive phase (an event); smaller
     * changes retune the running phase silently, so the column always carries the eruption's current rate.
     */
    static final double PHASE_UPDATE_THRESHOLD = 0.25;
    /** Column mass flux (kg/s, physical) below which no column is sustained (a few puffs at most). */
    static final double MIN_COLUMN_MASS_FLUX = 1;
    /** Lava flux (m³/s DRE, physical) below which no lava source is kept. */
    static final double MIN_LAVA_RATE = 1e-6;
    /** Share of the magma fragmented by water that starts / keeps the phreatomagmatic descriptor. */
    static final double PHREATOMAGMATIC_START_SHARE = 0.2;
    static final double PHREATOMAGMATIC_STOP_SHARE = 0.1;
    /** Seconds between steam telemetry events. */
    static final double STEAM_EVENT_SECONDS = 20;
    /** Porosity of fresh, wet Surtseyan tephra (ash and lapilli; ~0.4–0.5, Jakobsson &amp; Moore 1986). */
    static final double WET_TEPHRA_POROSITY = 0.45;
    /** Density of basaltic glass (sideromelane, kg/m³; ~2.6–2.8 t/m³), the solid of hyaloclastite and wet tuff. */
    static final double GLASS_DENSITY = 2700;
    /** Bulk density of fresh wet tuff (kg/m³): its glass with {@link #WET_TEPHRA_POROSITY} pore space. */
    static final double TUFF_BULK_DENSITY = GLASS_DENSITY * (1 - WET_TEPHRA_POROSITY);
    /** The apron of in-place quenched tephra reaches this many e-folding lengths beyond the rim. */
    static final double APRON_REACH = 4;
    /** Furthest a crater's ring is followed outwards when measuring its width at sea level (columns). */
    static final int MAX_RING_COLUMNS = 64;
    private static final int[] RING_DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] RING_DZ = {0, 1, 1, 1, 0, -1, -1, -1};
    static final int MAX_BOMBS_PER_SALVO = 10;
    /** Median clast (m) of a slug burst tearing fluid magma, and of a plug shattering. */
    static final double SLUG_CLAST_M = 0.03;
    /**
     * Ash/lapilli boundary (m): 2 mm (White and Houghton 2006). A discrete explosion's clasts above it
     * are thrown out ballistically and land around the vent; only the ash rises with its cloud.
     */
    static final double LAPILLI_MIN_M = 2e-3;
    static final double PLUG_CLAST_M = 2e-3;
    /**
     * Segments a fissure feeder is resolved into along strike (numerical resolution, independent of the
     * world's columns): the finest scale at which the flow can localise into a vent.
     */
    static final int FEEDER_SEGMENTS = 16;

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

    private final TreeSet<String> activeLavaSources = new TreeSet<>();
    private final Set<String> eruptionVents = new LinkedHashSet<>();
    /** Fissure vent ids already picked up from the dikes. */
    private final TreeSet<String> knownFissures = new TreeSet<>();
    /** Thermal state of each dike-fed fissure's feeder, by vent id. */
    private final TreeMap<String, FissureFeeder> feeders = new TreeMap<>();
    /** Vents the flow of a fissure localised into, by vent id (they outlive their fissure). */
    private final TreeMap<String, LocalVent> localVents = new TreeMap<>();
    /** Localised vents removed by the user since the last step, with their last state. */
    private final TreeMap<String, VentStatus> removedLocalVents = new TreeMap<>();
    /** Vents sealed by the user. */
    private final TreeSet<String> sealed = new TreeSet<>();
    /** Last reported state of each vent. */
    private final TreeMap<String, VentStatus> ventStates = new TreeMap<>();
    /** Physical magma flux (m³/s DRE) leaving through each outlet at the last step. */
    private final TreeMap<String, Double> ventFlux = new TreeMap<>();
    private boolean flankPending;
    /** A vent's sustained column: simulated mass rate (kg/s), collapse share, gas fraction, its collapse flow. */
    private static final class Column {
        double rate;
        double collapse;
        double gas;
        String collapseSource;
    }

    /** Columns in progress, by vent id: every vent with enough fragmented magma feeds its own. */
    private final TreeMap<String, Column> columns = new TreeMap<>();
    /** Simulation time (s) at which the current ash puff ends; negative = none. */
    private double burstPhaseUntil = -1;
    /** Vent of the current ash puff. */
    private String burstVentId;
    /** Collapse flow of a save from before one column per vent, whose vent is unknown: ended at the next step. */
    private String staleCollapseSource;
    private boolean phreatomagmatic;
    private double waterDepthM;
    private double openWaterFraction;
    private double nextSteamEventTime;
    private VentPartition.Result lastPartition;
    /** Water at the main vent at the last step (reporting). */
    private VentPartition.Water lastWater = VentPartition.Water.DRY;
    /** Discrete explosions fired so far, by mechanism (for observers such as the style estimate). */
    private long slugBursts;
    private long plugBursts;
    /** Collapse thresholds by rounded column parameters: a pure function, not saved. */
    private final Map<Long, Double> collapseThresholds = new HashMap<>();

    public VolcanoCoupler(String volcanoId, MagmaChamber chamber, SeismicityModel seismicity, List<VentSite> vents,
            DikePropagation dikes, TerrainModel terrain, LavaFlow lava, TephraSubsystem tephra, PyroclasticFlows pdc,
            Geothermal geothermal) {
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
        chamber.setCrust(crustAbove(chamber.chamberCenter()));
    }

    /** Column width L (m) of the world. */
    public double metersPerColumn() {
        return terrain.world().spec().metersPerColumn();
    }

    /** A vent's crater radius in whole columns (at least one). */
    private int craterColumns(VentSite vent) {
        return Math.max(1, (int) Math.round(vent.craterRadiusM() / metersPerColumn()));
    }

    @Override
    public String id() {
        return "coupler:" + volcanoId;
    }

    @Override
    public double periodSeconds() {
        return ERUPTION_RESOLUTION_SECONDS;
    }

    /**
     * Longest engine step while the volcano erupts (s): fountains, ballistics and flow fronts move on
     * second timescales, so the surface (lava, tephra) is resolved at this; the slower subsystems keep
     * their own, longer periods.
     */
    public static final double ERUPTION_RESOLUTION_SECONDS = 1;

    @Override
    public double maxStepSeconds() {
        return chamber.erupting() ? ERUPTION_RESOLUTION_SECONDS : Double.POSITIVE_INFINITY;
    }

    /** Groundwater model the conduit draws aquifer water from (optional). */

    public void setGround(HydrothermalField ground) {
        this.ground = ground;
        chamber.setCrust(crustAbove(chamber.chamberCenter())); // its water table saturates the crust
        if (ground != null) {
            double surface = ground.geothermC(0);
            chamber.setGeotherm(surface, (ground.geothermC(1000) - surface));
        }
    }

    @Override
    public void registerCommands(CommandBus bus) {
        bus.register(VentCommands.SealVent.class, c -> {
            if (c.volcanoId().equals(volcanoId)) seal(c.ventId());
        });
        bus.register(VentCommands.UnsealVent.class, c -> {
            if (c.volcanoId().equals(volcanoId)) unseal(c.ventId());
        });
        bus.register(VentCommands.RemoveVent.class, c -> {
            if (c.volcanoId().equals(volcanoId)) removeVent(c.ventId());
        });
    }

    /** Plugs vent {@code ventId}; false if this volcano has no such vent. */
    public boolean seal(String ventId) {
        if (find(ventId) == null) return false;
        sealed.add(ventId);
        pushOutlets();
        return true;
    }

    /** Reopens a sealed vent (a frozen fissure stays frozen); false if it was not sealed. */
    public boolean unseal(String ventId) {
        boolean was = sealed.remove(ventId);
        pushOutlets();
        return was;
    }

    /**
     * Deletes the dike-fed fissure {@code ventId} (its dike stays as an intrusion); false for a summit
     * vent or an unknown id.
     */
    public boolean removeVent(String ventId) {
        if (localVents.remove(ventId) != null) {
            sealed.remove(ventId);
            ventFlux.remove(ventId);
            VentStatus previous = ventStates.remove(ventId);
            if (previous != null) removedLocalVents.put(ventId, previous); // reported at the next step
            pushOutlets();
            return true;
        }
        if (dikes == null) return false;
        for (VentSite vent : baseVents) if (vent.id().equals(ventId)) return false;
        return dikes.removeFissure(ventId);
    }

    @Override
    public void step(StepContext context) {
        watchFissures(context);
        List<ConduitBurst> bursts = chamber.drainBursts();

        List<VentSite> vents = outlets();
        VentSite main = vents.isEmpty() ? referenceSite() : vents.get(0);
        VentPartition.Water water = surveyWater(main);
        lastWater = water;
        chamber.setVentEnvironment(VentPartition.ambientPressurePa(water.surfaceDepthM()),
                water.waterTableDepthM());
        chamber.setCrust(crustAbove(chamber.chamberCenter()));

        double rate = chamber.eruptionRate();
        ConduitSolution flow = chamber.conduitFlow();
        if (rate <= 0 || flow == null || vents.isEmpty()) {
            stopLava();
            stopExplosive();
            endBurstPhaseIfDue(context.time(), false);
            setPhreatomagmatic(context, false, null);
            lastPartition = null;
            if (!flankPending && !chamber.erupting()) {
                // An eruption that ended without a vent of its own leaves no conduit behind: its fissures
                // freeze as dikes, and the next eruption needs a new dike.
                if (!eruptionVents.isEmpty() && !anyCrater(eruptionVents)) chamber.closeConduit();
                eruptionVents.clear();
            }
            ventFlux.clear();
            updateFeeders(context);
            for (ConduitBurst burst : bursts) fireBurst(context, main, burst, false);
            return;
        }
        flankPending = false;
        if (eruptionVents.isEmpty()) {
            for (VentSite vent : vents) eruptionVents.add(vent.id());
        }
        vents = outlets();
        main = vents.get(0);
        double[] weights = shares(vents);
        ventFlux.clear();
        for (int i = 0; i < vents.size(); i++) {
            ventFlux.put(vents.get(i).id(), chamber.eruptionRate() * weights[i]);
        }
        updateFeeders(context);

        // Each vent erupts into its own setting: a crater under a shallow sea is Surtseyan while a fissure on
        // the deep sea floor (water pressure suppresses the steam explosions) pours pillow lava. The chamber's
        // outflow is split by the vents' shares; each share is partitioned with that vent's water.
        double magmaMassRate = chamber.eruptionRate() * ExplosivePhase.DRE_DENSITY;
        double stepSeconds = context.dtSeconds();
        if (water.openFraction() <= 0 && water.surfaceDepthM() > 0) {
            boilCraterLake(main, magmaMassRate * stepSeconds, flow.exitTemperatureC());
        }
        double mainDepth = waterDepthM;
        double mainOpen = openWaterFraction;
        double[] lavaRates = new double[vents.size()];
        double[] columnRates = new double[vents.size()];
        VentPartition.Result[] partitions = new VentPartition.Result[vents.size()];
        double wetMass = 0;
        double magmaMass = 0;
        boolean steam = false;
        for (int i = 0; i < vents.size(); i++) {
            VentSite vent = vents.get(i);
            VentPartition.Water w = i == 0 ? water : surveyWater(vent);
            double ambient = i == 0 ? chamber.ventAmbientPressurePa() : VentPartition.ambientPressurePa(w.surfaceDepthM());
            // the magma decompresses to this vent's own pressure: a deep fissure may not fragment at all
            ConduitSolution ventFlow = i == 0 ? flow : chamber.conduitFlowAt(ambient);
            if (ventFlow == null) ventFlow = flow;
            VentPartition.Result p = VentPartition.partition(ventFlow, ambient, chamber.config().conduitRadius(),
                    chamber.silicaWt(), w, this::criticalGasFraction);
            if (i == 0) lastPartition = p;
            // the chamber's actual outflow (linearised between conduit solutions) sets the totals; the
            // partition sets the shares
            double scale = p.magmaMassFlux() > 0 ? magmaMassRate * weights[i] / p.magmaMassFlux() : 0;
            lavaRates[i] = p.lavaMassFlux() * scale / ExplosivePhase.DRE_DENSITY;
            double col = p.columnMassFlux() * scale;
            columnRates[i] = col;
            partitions[i] = p;
            double ballistic = p.ballisticMassFlux() * scale * stepSeconds;
            if (ballistic > 0) {
                // Cooled fall-back of the fountain: its whole mass lands as loose scoria lapilli around the vent
                // (and back into it); a few tracked bombs show the larger clasts.
                double median = Double.isFinite(p.medianClastM()) ? Math.max(p.medianClastM(), LAPILLI_MIN_M) : LAPILLI_MIN_M;
                tephra.proximalFallout(vent, ballistic, p.ballisticSpeed(), 0, 15, median, LAPILLI_MIN_M,
                        Math.max(2 * LAPILLI_MIN_M, VentPartition.BALLISTIC_SIZE));
                tephra.launchSalvo(vent, ballistic, p.ballisticSpeed(), 0, 15, chamber.silicaWt(), MAX_BOMBS_PER_SALVO, false);
            }
            wetMass += p.waterFragmentedMassFlux() * scale;
            magmaMass += p.magmaMassFlux() * scale;
            if (p.jetMassFlux() > 0) fireJets(context, vent, p.jetMassFlux() * scale * stepSeconds, p.jetSpeed());
            if (p.wetFalloutMassFlux() > 0) buildTuffRing(context, vent, p.wetFalloutMassFlux() * scale * stepSeconds, w.surfaceDepthM());
            if (p.steamMassFlux() > 0 && context.time() >= nextSteamEventTime) {
                context.outbox().emit(new SurfaceEvents.PhreatomagmaticSteam(
                        context.time(), volcanoId, vent.position(), p.steamMassFlux() * scale, w.surfaceDepthM()));
                steam = true;
            }
        }
        if (steam) nextSteamEventTime = context.time() + STEAM_EVENT_SECONDS;
        waterDepthM = mainDepth; // the volcano reports its main vent's water
        openWaterFraction = mainOpen;

        if (Arrays.stream(lavaRates).anyMatch(r -> r > MIN_LAVA_RATE)) updateLava(vents, lavaRates);
        else stopLava();

        if (staleCollapseSource != null && pdc != null) pdc.removeSource(staleCollapseSource);
        staleCollapseSource = null;
        // each vent's column rises on its own
        TreeSet<String> rising = new TreeSet<>();
        for (int i = 0; i < vents.size(); i++) {
            if (columnRates[i] < MIN_COLUMN_MASS_FLUX) continue;
            rising.add(vents.get(i).id());
            updateExplosive(vents.get(i), partitions[i], columnRates[i]);
        }
        for (String id : new ArrayList<>(columns.keySet())) if (!rising.contains(id)) stopExplosive(id);
        boolean sustained = rising.contains(main.id());

        double wetShare = magmaMass > 0 ? wetMass / magmaMass : 0;
        setPhreatomagmatic(context, phreatomagmatic ? wetShare >= PHREATOMAGMATIC_STOP_SHARE
                : wetShare >= PHREATOMAGMATIC_START_SHARE, main);

        for (ConduitBurst burst : bursts) fireBurst(context, main, burst, sustained);
        endBurstPhaseIfDue(context.time(), sustained);
    }

    /** Picks up fissures opened by dikes since the last step, and drops removed ones. */
    private void watchFissures(StepContext context) {
        if (dikes == null) return;
        TreeSet<String> present = new TreeSet<>();
        for (Dike dike : dikes.dikes()) {
            VentSite vent = dike.fissure();
            if (vent == null || dike.removed()) continue;
            present.add(vent.id());
            if (!knownFissures.add(vent.id())) {
                if (!feeders.containsKey(vent.id())) feeders.put(vent.id(), openFeeder(dike, vent, context));
                continue;
            }
            feeders.put(vent.id(), openFeeder(dike, vent, context));
            if (!chamber.erupting()) {
                // A fresh flank eruption is fed through the new fissures, not the summit.
                if (!flankPending) eruptionVents.clear();
                flankPending = true;
                chamber.requestFlankEruption();
            }
            eruptionVents.add(vent.id());
        }
        for (String id : new ArrayList<>(knownFissures)) {
            if (present.contains(id)) continue;
            // Removed by the user (or forgotten as an old record): no longer a vent.
            knownFissures.remove(id);
            FissureFeeder feeder = feeders.remove(id);
            // Kept in eruptionVents: an eruption fed only through it loses its outlet and ends.
            sealed.remove(id);
            ventFlux.remove(id);
            VentStatus previous = ventStates.remove(id);
            if (previous != null) {
                context.outbox().emit(new VentEvents.VentStateChanged(context.time(), volcanoId, id, previous,
                        VentStatus.REMOVED, feeder == null ? Double.NaN : feeder.widestWidth()));
            }
        }
        pushOutlets();
    }

    private FissureFeeder openFeeder(Dike dike, VentSite vent, StepContext context) {
        double length = vent.fissureLengthM();
        return FissureFeeder.open(dike.openingM(), length, dike.heightM(), wallRockC(vent, dike.heightM()),
                FEEDER_SEGMENTS, context.random());
    }

    /** Average continental geotherm where the subsurface model does not reach: 15 °C + 30 °C/km (Turcotte &amp; Schubert). */
    static final double FALLBACK_SURFACE_C = 15;
    static final double FALLBACK_GRADIENT_C_PER_M = 0.030;

    /**
     * Mean host-rock temperature (°C) along a feeder dike {@code heightM} tall under {@code vent}: the
     * subsurface model's ground temperature averaged over the height (8 depths), the average continental
     * geotherm where the model does not know the column.
     */
    private double wallRockC(VentSite vent, double heightM) {
        double l = metersPerColumn();
        int x = vent.position().columnX(l);
        int z = vent.position().columnZ(l);
        double h = Math.max(1, heightM);
        if (ground == null || !ground.known(x, z)) return FALLBACK_SURFACE_C + FALLBACK_GRADIENT_C_PER_M * h / 2;
        double sum = 0;
        int n = 8;
        for (int i = 0; i < n; i++) sum += ground.temperatureC(x, z, (i + 0.5) / n * h);
        return sum / n;
    }

    /**
     * Advances every fissure feeder by this step (carrying its share of the flow, nothing once the
     * eruption stopped) and reports vent state changes.
     */
    private void updateFeeders(StepContext context) {
        double stepDt = context.dtSeconds();
        for (Map.Entry<String, FissureFeeder> e : feeders.entrySet()) {
            FissureFeeder feeder = e.getValue();
            if (feeder.frozen()) continue;
            feeder.advance(stepDt, ventFlux.getOrDefault(e.getKey(), 0.0), chamber.temperatureC(), chamber.silicaWt());
        }
        for (Map.Entry<String, LocalVent> e : localVents.entrySet()) {
            VentThroat throat = e.getValue().throat();
            if (throat.frozen()) continue;
            throat.advance(stepDt, ventFlux.getOrDefault(e.getKey(), 0.0), chamber.temperatureC(), chamber.silicaWt());
        }
        if (chamber.erupting()) {
            for (String id : new ArrayList<>(feeders.keySet())) {
                if (eruptionVents.contains(id) && open(id)) localise(context, id, feeders.get(id));
            }
        }
        pushOutlets();
        reportStates(context);
    }

    /**
     * A vent the flow of a fissure localised into: one feeder segment still carrying magma after the
     * segments beside it froze (Bruce &amp; Huppert 1989; Wylie et al. 1999). Its throat starts with that
     * segment's hydraulic conductance and then widens or freezes by its own heat balance ({@link VentThroat}),
     * measured against its fissure's conductance when it opened, as the chamber's conduit model describes the
     * freshly opened dike ({@link #outletCapacity()}).
     */
    private record LocalVent(VentSite site, String fissureId, VentThroat throat, double referenceConductance) {
        double conductance() {
            return throat.conductance();
        }
    }

    /**
     * Turns every open segment of an erupting fissure whose neighbours froze into a vent: the flow has
     * localised there and the fissure no longer carries it, so the segment leaves the feeder (which may
     * then be frozen whole) and goes on as a crater on the fissure line.
     */
    private void localise(StepContext context, String fissureId, FissureFeeder feeder) {
        int n = feeder.width.length;
        if (n < 2) return; // nothing along strike to freeze around it
        VentSite fissure = find(fissureId);
        if (fissure == null) return;
        double sum = 0;
        for (double w : feeder.width) if (w > FissureFeeder.FREEZE_WIDTH_M) sum += w * w * w;
        double fissureFlux = ventFlux.getOrDefault(fissureId, 0.0);
        for (int i = 0; i < n; i++) {
            double w = feeder.width[i];
            if (w <= FissureFeeder.FREEZE_WIDTH_M) continue;
            boolean leftFrozen = i == 0 || feeder.width[i - 1] <= FissureFeeder.FREEZE_WIDTH_M;
            boolean rightFrozen = i == n - 1 || feeder.width[i + 1] <= FissureFeeder.FREEZE_WIDTH_M;
            if (!leftFrozen || !rightFrozen) continue;
            double conductance = w * w * w * feeder.segmentLengthM / 12;
            // a pipe of the same conductance (π a⁴ / 8 = w³ ℓ / 12) is the vent's throat
            double radius = Math.pow(8 * conductance / Math.PI, 0.25);
            double along = (i + 0.5) * feeder.segmentLengthM - fissure.fissureLengthM() / 2;
            double x = fissure.position().x() + along * Math.cos(fissure.fissureAngleRad());
            double z = fissure.position().z() + along * Math.sin(fissure.fissureAngleRad());
            double y = surfaceAt(x, z, fissure.position().y());
            VentSite site = VentSite.crater(fissureId + "-vent-" + i, new Point3(x, y, z), radius);
            feeder.width[i] = 0; // the flow goes on through the vent, no longer through the fissure
            localVents.put(site.id(), new LocalVent(site, fissureId, VentThroat.ofConductance(conductance, feeder),
                    feeder.initialConductance));
            eruptionVents.add(site.id());
            ventFlux.put(site.id(), sum > 0 ? fissureFlux * w * w * w / sum : 0); // its share until the next step
            context.outbox().emit(new VentEvents.VentFormed(context.time(), volcanoId, site, fissureId));
        }
    }

    /**
     * The world's rock column above {@code point}, from the ground down: each layer's bulk density, less its
     * voids, with its pores full of water below the water table. {@code null} where the world does not know the
     * column (the chamber keeps its crust).
     */
    private CrustColumn crustAbove(Point3 point) {
        if (terrain == null) return null;
        WorldModel world = terrain.world();
        double l = world.spec().metersPerColumn();
        int cx = point.columnX(l);
        int cz = point.columnZ(l);
        if (!world.isKnown(cx, cz)) return null;
        int layers = world.layerCount(cx, cz);
        if (layers == 0) return null;
        double table = ground != null && ground.known(cx, cz) ? ground.waterTableDepthM(cx, cz) : Double.POSITIVE_INFINITY;
        double[] bottoms = new double[layers];
        double[] density = new double[layers];
        double depth = 0;
        int n = 0;
        for (int k = layers - 1; k >= 0; k--) {
            LayerView layer = world.layer(cx, cz, k);
            double thick = layer.thickness();
            if (!(thick > 0)) continue;
            var material = layer.materialInfo();
            if (!material.solid()) continue; // voids and standing water: no rock weight here
            double bulk = material.densityKgM3() * (1 - Math.max(0, Math.min(1, layer.voidFraction())));
            // pores below the water table are full of water
            double mid = depth + thick / 2;
            if (mid > table) bulk += Math.max(0, Math.min(1, layer.porosity())) * VentPartition.WATER_DENSITY;
            depth += thick;
            bottoms[n] = depth;
            density[n] = bulk;
            n++;
        }
        if (n == 0) return null;
        bottoms[n - 1] = Double.POSITIVE_INFINITY; // the deepest layer goes on down
        return new CrustColumn(java.util.Arrays.copyOf(bottoms, n), java.util.Arrays.copyOf(density, n));
    }

    /** Ground elevation (m) at a point (m), {@code fallback} where the ground is not known. */
    private double surfaceAt(double x, double z, double fallback) {
        if (terrain == null) return fallback;
        WorldModel world = terrain.world();
        double l = world.spec().metersPerColumn();
        int cx = (int) Math.floor(x / l);
        int cz = (int) Math.floor(z / l);
        if (!world.isKnown(cx, cz)) return fallback;
        double s = world.surfaceZ(cx, cz);
        return Double.isFinite(s) ? s : fallback;
    }

    /** True if a crater (configured or localised) is among {@code ventIds}. */
    private boolean anyCrater(Set<String> ventIds) {
        for (String id : ventIds) if (crater(id)) return true;
        return false;
    }

    /** A crater: a configured vent or one a fissure localised into (not a fissure). */
    private boolean crater(String ventId) {
        if (localVents.containsKey(ventId)) return true;
        for (VentSite vent : baseVents) if (vent.id().equals(ventId)) return vent.kind() != VentKind.FISSURE;
        return false;
    }

    /**
     * Where the volcano meets its surroundings while it has no outlet: its main vent, or, before any vent
     * has formed, the ground above the chamber.
     */
    private VentSite referenceSite() {
        List<VentSite> all = allVents();
        if (!all.isEmpty()) return all.get(0);
        Point3 c = chamber.chamberCenter();
        return VentSite.crater(volcanoId + "-surface", new Point3(c.x(), surfaceAt(c.x(), c.z(), 0), c.z()), 0);
    }

    /** Tells the chamber how much of its conduit's conductance the open outlets can carry. */
    private void pushOutlets() {
        redirectIfStranded();
        // the chamber's own conduit leads to a crater; without an open one it can only erupt through a dike
        boolean summitOpen = false;
        for (VentSite vent : allVents()) summitOpen |= crater(vent.id()) && !sealed.contains(vent.id());
        chamber.setSummitBlocked(!summitOpen);
        chamber.setOutletCapacity(outletCapacity());
    }

    /**
     * When every vent of an ongoing eruption froze, was sealed or was removed, the pressurised magma
     * takes the path left open: the craters, if a molten conduit still leads to them, and not sealed
     * (otherwise the eruption ends with its pressure kept, {@link MagmaChamber#setOutletCapacity}).
     */
    private void redirectIfStranded() {
        if (!chamber.erupting() || eruptionVents.isEmpty()) return;
        for (VentSite vent : allVents()) if (eruptionVents.contains(vent.id()) && open(vent.id())) return;
        if (!(chamber.conduitOpenness() > 0)) return;
        for (VentSite vent : allVents()) {
            if (crater(vent.id()) && !sealed.contains(vent.id())) eruptionVents.add(vent.id());
        }
    }

    /**
     * Share of the chamber's conduit conductance the outlets can carry: 1 while an open summit vent
     * takes part, otherwise the fissure feeders' conductance relative to when they opened (the conduit
     * model describes the freshly opened dike), 0 when no outlet is left.
     */
    double outletCapacity() {
        double now = 0;
        double initial = 0;
        Set<String> counted = new TreeSet<>(); // each fissure's opening conductance once, with its vents
        for (VentSite vent : outlets()) {
            LocalVent local = localVents.get(vent.id());
            if (local != null) {
                now += local.conductance();
                if (counted.add(local.fissureId())) initial += local.referenceConductance();
                continue;
            }
            FissureFeeder feeder = feeders.get(vent.id());
            if (feeder == null) return 1; // a configured crater
            now += feeder.conductance();
            if (counted.add(vent.id())) initial += feeder.initialConductance;
        }
        if (initial <= 0) return 0;
        return Math.min(1, now / initial);
    }

    private void reportStates(StepContext context) {
        for (Map.Entry<String, VentStatus> e : removedLocalVents.entrySet()) {
            context.outbox().emit(new VentEvents.VentStateChanged(context.time(), volcanoId, e.getKey(), e.getValue(),
                    VentStatus.REMOVED, Double.NaN));
        }
        removedLocalVents.clear();
        boolean erupting = chamber.erupting();
        Set<String> outlets = new TreeSet<>();
        for (VentSite vent : outlets()) outlets.add(vent.id());
        for (VentSite vent : allVents()) {
            VentStatus status = statusOf(vent.id(), erupting && !eruptionVents.isEmpty() && outlets.contains(vent.id()));
            VentStatus previous = ventStates.put(vent.id(), status);
            if (previous != null && previous != status) {
                FissureFeeder feeder = feeders.get(vent.id());
                context.outbox().emit(new VentEvents.VentStateChanged(context.time(), volcanoId, vent.id(), previous,
                        status, feeder == null ? Double.NaN : feeder.widestWidth()));
            }
        }
    }

    private VentStatus statusOf(String ventId, boolean outlet) {
        if (sealed.contains(ventId)) return VentStatus.SEALED;
        FissureFeeder feeder = feeders.get(ventId);
        if (feeder != null && feeder.frozen()) return VentStatus.FROZEN;
        LocalVent local = localVents.get(ventId);
        if (local != null && local.throat().frozen()) return VentStatus.FROZEN;
        if (!outlet) return VentStatus.IDLE;
        return feeder != null && feeder.waning() ? VentStatus.WANING : VentStatus.ACTIVE;
    }

    /** True if magma can leave through {@code ventId}: not sealed and, for a fissure, not frozen. */
    private boolean open(String ventId) {
        if (sealed.contains(ventId)) return false;
        LocalVent local = localVents.get(ventId);
        if (local != null) return !local.throat().frozen();
        FissureFeeder feeder = feeders.get(ventId);
        return feeder == null || !feeder.frozen();
    }

    /**
     * Shares of the flow through each outlet, by hydraulic conductance: a summit conduit as a pipe
     * (π r⁴ / 8), a fissure feeder as the slots still open (Σ w³ ℓ / 12).
     */
    private double[] shares(List<VentSite> vents) {
        double[] w = new double[vents.size()];
        double r = chamber.config().conduitRadius();
        double sum = 0;
        for (int i = 0; i < w.length; i++) {
            String id = vents.get(i).id();
            LocalVent local = localVents.get(id);
            FissureFeeder feeder = feeders.get(id);
            w[i] = local != null ? local.conductance()
                    : feeder == null ? Math.PI * r * r * r * r / 8 : feeder.conductance();
            sum += w[i];
        }
        for (int i = 0; i < w.length; i++) w[i] = sum > 0 ? w[i] / sum : 1.0 / w.length;
        return w;
    }

    private VentSite find(String ventId) {
        for (VentSite vent : allVents()) if (vent.id().equals(ventId)) return vent;
        return null;
    }

    /**
     * All vents of this volcano: the configured ones, fissures opened by dikes (not removed), and the
     * vents their flow localised into.
     */
    public List<VentSite> allVents() {
        List<VentSite> all = new ArrayList<>(baseVents);
        if (dikes != null) all.addAll(dikes.openedVents());
        for (LocalVent local : localVents.values()) all.add(local.site());
        return all;
    }

    /**
     * Vents magma leaves through (or will at the next onset): the current eruption's vents still open,
     * or between eruptions every open vent (open summit vents and fissures whose feeders are still hot).
     */
    List<VentSite> outlets() {
        List<VentSite> out = new ArrayList<>();
        for (VentSite vent : allVents()) {
            if (!open(vent.id())) continue;
            if (eruptionVents.isEmpty() ? vent.kind() != VentKind.FISSURE || feeders.containsKey(vent.id())
                    : eruptionVents.contains(vent.id())) {
                out.add(vent);
            }
        }
        return out;
    }

    /** Vents currently erupting (or about to): open outlets of the current eruption. */
    public List<VentSite> activeVents() {
        return eruptionVents.isEmpty() ? List.of() : outlets();
    }

    /** Lifecycle state of {@code ventId} at the last step ({@code null} if unknown). */
    public VentStatus ventStatus(String ventId) {
        // a seal applies at once; the cached state catches up at the coupler's next step
        if (sealed.contains(ventId) && find(ventId) != null) return VentStatus.SEALED;
        VentStatus known = ventStates.get(ventId);
        if (known != null) return known;
        return find(ventId) == null ? null : statusOf(ventId, false);
    }

    public boolean sealed(String ventId) {
        return sealed.contains(ventId);
    }

    /** Physical magma flux (m³/s DRE) through {@code ventId} at the last step; 0 if none. */
    public double ventFluxM3PerS(String ventId) {
        return ventFlux.getOrDefault(ventId, 0.0);
    }

    /** Widest open width (m) of a fissure's feeder; NaN for summit vents, 0 once frozen. */
    public double feederWidthM(String ventId) {
        FissureFeeder feeder = feeders.get(ventId);
        return feeder == null ? Double.NaN : feeder.widestWidth();
    }

    /** {open segments, total segments} of a fissure's feeder, or {@code null} for summit vents. */
    public int[] feederSegments(String ventId) {
        FissureFeeder feeder = feeders.get(ventId);
        return feeder == null ? null : new int[] {feeder.openSegments(), feeder.width.length};
    }

    /** The partition of the current eruption's flow at the vent; {@code null} when not erupting. */
    public VentPartition.Result partition() {
        return lastPartition;
    }

    // ── Lava ──

    private void updateLava(List<VentSite> vents, double[] rates) {
        // One lava source per crater, and per open segment of a fissure's feeder (the frozen stretches pour
        // nothing), each with its own rate (m³/s DRE): a fissure's flow shares out by segment conductance (w³).
        Set<String> wanted = new TreeSet<>();
        double l = metersPerColumn();
        for (int i = 0; i < vents.size(); i++) {
            VentSite vent = vents.get(i);
            double perVent = rates[i];
            if (perVent <= MIN_LAVA_RATE) continue;
            FissureFeeder feeder = feeders.get(vent.id());
            if (feeder == null) {
                feedLava(wanted, sourceId(vent), perVent,
                        () -> LavaSource.atVent(vent, l, perVent, chamber.temperatureC(), chamber.silicaWt(), chamber.ventWaterWt()));
            } else {
                double sum = 0;
                for (double w : feeder.width) if (w > FissureFeeder.FREEZE_WIDTH_M) sum += w * w * w;
                for (int s = 0; s < feeder.width.length; s++) {
                    double w = feeder.width[s];
                    if (w <= FissureFeeder.FREEZE_WIDTH_M || !(sum > 0)) continue;
                    double rate = perVent * w * w * w / sum;
                    if (rate <= MIN_LAVA_RATE) continue;
                    double from = s * feeder.segmentLengthM - vent.fissureLengthM() / 2;
                    double to = from + feeder.segmentLengthM;
                    feedLava(wanted, sourceId(vent) + "#" + s, rate,
                            () -> LavaSource.alongFissure(vent.id(), vent, from, to, l, rate, chamber.temperatureC(),
                                    chamber.silicaWt(), chamber.ventWaterWt()));
                }
            }
            if (geothermal != null) {
                int x = vent.position().columnX(l);
                int z = vent.position().columnZ(l);
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

    /** Keeps lava source {@code sourceId} pouring at {@code rate}, creating it from {@code source} if new. */
    private void feedLava(Set<String> wanted, String sourceId, double rate, java.util.function.Supplier<LavaSource> source) {
        wanted.add(sourceId);
        if (activeLavaSources.add(sourceId)) {
            int unit = units.unit(DepositType.LAVA, chamber.eruptionStartTime(), chamber.temperatureC());
            lava.addSource(source.get().withId(sourceId).withUnit(unit));
        } else {
            lava.setRate(sourceId, rate);
        }
    }

    private void stopLava() {
        for (String sourceId : activeLavaSources) lava.removeSource(sourceId);
        activeLavaSources.clear();
    }

    // ── Sustained columns ──

    private void updateExplosive(VentSite vent, VentPartition.Result p, double columnMassRate) {
        Column c = columns.computeIfAbsent(vent.id(), k -> new Column());
        boolean restart = c.rate <= 0
                || Math.abs(columnMassRate - c.rate) > PHASE_UPDATE_THRESHOLD * c.rate
                || Math.abs(p.collapseFraction() - c.collapse) > 0.1
                || Math.abs(p.columnGasFraction() - c.gas) > PHASE_UPDATE_THRESHOLD * c.gas;
        if (restart) {
            c.rate = columnMassRate;
            c.collapse = p.collapseFraction();
            c.gas = p.columnGasFraction();
            if (vent.id().equals(burstVentId)) burstPhaseUntil = -1; // the sustained column takes over its ash puff
        }

        // The phase's gas thrust is set by the jet: an equivalent overpressure that expands the
        // column's gas to its exit velocity.
        double overpressure = equivalentOverpressureMPa(p.columnVelocity(), p.columnGasFraction(), p.columnTemperatureC());
        ExplosivePhase phase = new ExplosivePhase(vent, columnMassRate, Math.min(1, p.columnGasFraction()), overpressure,
                p.columnTemperatureC(), chamber.silicaWt(), 0, p.grainSize());
        ExplosivePhase lofted = phase.withMassEruptionRate(columnMassRate * (1 - p.collapseFraction()));
        if (restart) {
            tephra.startPhase(lofted);
            updateCollapse(vent, c, columnMassRate * p.collapseFraction(), p.columnTemperatureC());
        } else {
            tephra.retunePhase(lofted);
        }
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

    private void updateCollapse(VentSite vent, Column c, double massRate, double temperatureC) {
        if (pdc == null) return;
        if (c.collapseSource != null) {
            pdc.removeSource(c.collapseSource);
            c.collapseSource = null;
        }
        if (massRate > 0 && c.collapse > 0.01) {
            c.collapseSource = pdc.columnCollapse(volcanoId, vent.position(), Math.max(metersPerColumn(), vent.craterRadiusM()),
                    massRate, 1.0, temperatureC);
        }
    }

    /** Ends vent {@code ventId}'s column. */
    private void stopExplosive(String ventId) {
        Column c = columns.remove(ventId);
        if (c == null) return;
        tephra.stopPhase(ventId);
        if (c.collapseSource != null && pdc != null) pdc.removeSource(c.collapseSource);
    }

    private void stopExplosive() {
        for (String id : new ArrayList<>(columns.keySet())) stopExplosive(id);
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
                burst.silicaWt(), slug ? 40 : 120, true);
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
        if (explosionListener != null) explosionListener.accept(vent.position(), energy);
        context.outbox().emit(new SurfaceEvents.ExplosiveBurst(context.time(), volcanoId,
                slug ? BurstKind.STROMBOLIAN : BurstKind.VULCANIAN, vent.position(), burst.ejectaMassKg(),
                burst.gasMassKg(), speed, energy));
    }

    /** Cock's-tail jets of water-fragmented magma: one salvo of the coarse wet ejecta of this step. */
    private void fireJets(StepContext context, VentSite vent, double mass, double speed) {
        // the jets' coarse wet ejecta land around the vent with their whole mass; a few tracked bombs show them
        tephra.proximalFallout(vent, mass, speed, 40, 15, Math.max(LAPILLI_MIN_M, 4 * LAPILLI_MIN_M), LAPILLI_MIN_M,
                VentPartition.BALLISTIC_SIZE);
        tephra.launchSalvo(vent, mass, speed, 40, 15, chamber.silicaWt(), MAX_BOMBS_PER_SALVO, false);
        double energy = 0.5 * mass * speed * speed;
        if (seismicity != null) seismicity.queueExplosion(vent.position(), energy);
        if (explosionListener != null) explosionListener.accept(vent.position(), energy);
        context.outbox().emit(new SurfaceEvents.ExplosiveBurst(context.time(), volcanoId, BurstKind.SURTSEYAN_JET,
                vent.position(), mass, 0, speed, energy));
    }

    private java.util.function.BiConsumer<Point3, Double> explosionListener;

    /** Receives each discrete vent explosion (position, kinetic energy J), e.g. to excavate a crater. */
    public void setExplosionListener(java.util.function.BiConsumer<Point3, Double> listener) {
        this.explosionListener = listener;
    }

    private void startAshPuff(double now, VentSite vent, double ashMassKg, double durationSeconds, double gasFraction,
            double overpressureMPa, double temperatureC, double silicaWt, GrainSizeDistribution grain) {
        // the puff injects its whole ash mass over its duration
        tephra.startPhase(new ExplosivePhase(vent, ashMassKg / durationSeconds, Math.min(1, gasFraction), overpressureMPa,
                temperatureC, silicaWt, 0, grain));
        burstPhaseUntil = Math.max(burstPhaseUntil, now + durationSeconds);
        burstVentId = vent.id();
    }

    private void endBurstPhaseIfDue(double now, boolean sustained) {
        if (burstPhaseUntil < 0 || now < burstPhaseUntil) return;
        if (!sustained && burstVentId != null && !columns.containsKey(burstVentId)) tephra.stopPhase(burstVentId);
        burstPhaseUntil = -1;
        burstVentId = null;
    }

    // ── Magma–water interaction ──

    /**
     * Water around the vent: depth of water standing over the crater floor, the share of the ring just
     * outside the crater that is submerged (open to the sea or lake), and the water table below the
     * vent from the groundwater model.
     */
    private VentPartition.Water surveyWater(VentSite vent) {
        WorldModel world = terrain == null ? null : terrain.world();
        double lc = world == null ? 1 : world.spec().metersPerColumn();
        int cx = vent.position().columnX(lc);
        int cz = vent.position().columnZ(lc);
        double table = ground != null && ground.known(cx, cz)
                ? ground.waterTableDepthM(cx, cz) : Double.POSITIVE_INFINITY;
        if (world == null || !world.isKnown(cx, cz)) {
            waterDepthM = 0;
            openWaterFraction = 0;
            return new VentPartition.Water(0, 0, table);
        }
        double l = lc;
        int crater = craterColumns(vent);
        int ventColumns = 0;
        int ventSubmerged = 0;
        double depthSum = 0;
        double levelSum = 0;
        int rimColumns = 0;
        int rimSubmerged = 0;
        int outer = crater + 3;
        for (int dz = -outer; dz <= outer; dz++) {
            for (int dx = -outer; dx <= outer; dx++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > outer * outer) continue;
                int x = cx + dx;
                int z = cz + dz;
                if (!world.isKnown(x, z)) continue;
                double level = world.waterZ(x, z);
                double depth = Double.isFinite(level) ? Math.max(0, level - world.surfaceZ(x, z)) : 0;
                if (d2 <= crater * crater) {
                    ventColumns++;
                    if (depth > 0) {
                        ventSubmerged++;
                        depthSum += depth;
                        levelSum += level;
                    }
                } else {
                    rimColumns++;
                    if (depth > 0) rimSubmerged++;
                }
            }
        }
        boolean wet = ventColumns > 0 && ventSubmerged * 2 >= ventColumns;
        waterDepthM = wet ? depthSum / ventSubmerged : 0;
        // Open water reaches the vent freely only if the crater's water connects to the open sea; a lagoon
        // closed off by the ring is refilled only by seepage.
        openWaterFraction = wet && rimColumns > 0 && connectedToOpenWater(world, cx, cz, crater + MAX_RING_COLUMNS)
                ? Math.max(rimSubmerged / (double) rimColumns, 1.0 / rimColumns) : 0;

        // The vent's fill of loose tephra, and how much of its mouth is water-saturated (a slurry).
        double top = world.surfaceZ(cx, cz);
        double fill = 0;
        double pores = 0;
        for (int k = world.layerCount(cx, cz) - 1; k > 0; k--) {
            LayerView layer = world.layer(cx, cz, k);
            if (!layer.loose() || !layer.materialInfo().solid()) break;
            fill += layer.thickness();
            pores += layer.thickness() * layer.porosity();
        }
        double level = wet ? levelSum / ventSubmerged : Double.isFinite(table) ? top - table : Double.NaN;
        double mouth = crater * l;
        double band = Math.min(fill, mouth);
        double slurry = band > 0 && Double.isFinite(level) ? Math.max(0, Math.min(band, level - (top - band))) / mouth : 0;
        double porosity = fill > 0 ? pores / fill : 0;
        // Water drawn out of a crater cut off from open water comes back only by seepage through the edifice.
        double seepage = wet && openWaterFraction > 0 ? Double.NaN : seepageIntoCrater(world, cx, cz, crater, top - band);
        // the ground's layers under the vent, from the top down: the aquifer the conduit can draw water from
        int layers = world.layerCount(cx, cz);
        double[] bottoms = new double[layers];
        double[] conductivity = new double[layers];
        double below = 0;
        for (int k = layers - 1, i = 0; k >= 0; k--, i++) {
            LayerView layer = world.layer(cx, cz, k);
            below += layer.thickness();
            bottoms[i] = below;
            double logK = layer.materialInfo().log10HydraulicConductivity();
            conductivity[i] = Double.isFinite(logK) ? Math.pow(10, logK) : 0;
        }
        return new VentPartition.Water(waterDepthM, openWaterFraction, table, slurry, porosity, seepage, bottoms,
                conductivity);
    }

    /**
     * A crater lake cut off from the sea is a finite pool: the magma rising through it boils it at up to
     * {@code c_m (T − 100) / (c_w · 80 K + L)} kg of water per kg (~0.5), so it dries out within minutes to
     * hours unless seepage keeps up (Surtsey 1964: once the ring sealed the vent off, the crater lagoon
     * gave way to lava fountains and a lava lake; Thorarinsson 1967).
     */
    private void boilCraterLake(VentSite vent, double magmaKg, double temperatureC) {
        WorldModel world = terrain == null ? null : terrain.world();
        if (world == null || !(magmaKg > 0)) return;
        double boil = magmaKg * VentPartition.MAGMA_HEAT_CAPACITY * Math.max(0, temperatureC - 100)
                / (VentPartition.WATER_HEAT_CAPACITY * 80 + VentPartition.WATER_LATENT_HEAT);
        double l = world.spec().metersPerColumn();
        double area = l * l;
        int cx = vent.position().columnX(l);
        int cz = vent.position().columnZ(l);
        int crater = craterColumns(vent);
        double lake = 0;
        for (int dz = -crater; dz <= crater; dz++) {
            for (int dx = -crater; dx <= crater; dx++) {
                if (dx * dx + dz * dz > crater * crater || !world.isKnown(cx + dx, cz + dz)) continue;
                double level = world.waterZ(cx + dx, cz + dz);
                if (Double.isFinite(level)) lake += Math.max(0, level - world.surfaceZ(cx + dx, cz + dz));
            }
        }
        lake *= area * VentPartition.WATER_DENSITY;
        if (!(lake > 0)) return;
        double keep = Math.max(0, 1 - boil / lake);
        for (int dz = -crater; dz <= crater; dz++) {
            for (int dx = -crater; dx <= crater; dx++) {
                int x = cx + dx;
                int z = cz + dz;
                if (dx * dx + dz * dz > crater * crater || !world.isKnown(x, z)) continue;
                double level = world.waterZ(x, z);
                double floor = world.surfaceZ(x, z);
                if (!Double.isFinite(level) || level <= floor) continue;
                world.setWaterZ(x, z, keep > 1e-3 ? floor + (level - floor) * keep : Double.NaN);
            }
        }
    }

    /**
     * Whether water standing at column ({@code cx}, {@code cz}) connects through submerged columns (8-connected)
     * to water beyond {@code radius} columns, i.e. to the open sea or a lake, rather than being a lagoon
     * enclosed by land.
     */
    static boolean connectedToOpenWater(WorldModel world, int cx, int cz, int radius) {
        // most often a straight line of water leads out (one of the paths the search below would find)
        for (int d = 0; d < 8; d++) {
            for (int k = 1; ; k++) {
                int x = cx + RING_DX[d] * k;
                int z = cz + RING_DZ[d] * k;
                if (!world.isKnown(x, z)) return true;
                double level = world.waterZ(x, z);
                if (!(Double.isFinite(level) && level > world.surfaceZ(x, z))) break;
                if ((x - cx) * (x - cx) + (z - cz) * (z - cz) > radius * radius) return true;
            }
        }
        java.util.ArrayDeque<long[]> queue = new java.util.ArrayDeque<>();
        me.alex4386.typhon.engine.math.LongHashSet seen = new me.alex4386.typhon.engine.math.LongHashSet();
        queue.add(new long[] {cx, cz});
        seen.add(floodKey(cx, cz));
        while (!queue.isEmpty()) {
            long[] p = queue.poll();
            int x = (int) p[0];
            int z = (int) p[1];
            if ((x - cx) * (x - cx) + (z - cz) * (z - cz) > radius * radius) return true;
            for (int d = 0; d < 8; d++) {
                int nx = x + RING_DX[d];
                int nz = z + RING_DZ[d];
                if (!seen.add(floodKey(nx, nz))) continue;
                if (!world.isKnown(nx, nz)) return true; // water running off the simulated area: the open sea
                double level = world.waterZ(nx, nz);
                if (Double.isFinite(level) && level > world.surfaceZ(nx, nz)) queue.add(new long[] {nx, nz});
            }
        }
        return false;
    }

    private static long floodKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    /**
     * Sea water seeping through the saturated edifice into a crater drawn down to {@code floorZ} (kg/s):
     * Darcy flow {@code ρ K Δh / L} through the crater wall below sea level ({@code 2π r Δh}), with
     * {@code L} the ring's mean width at sea level and {@code K} the hydraulic conductivity of its
     * surface deposits (Freeze &amp; Cherry 1979; Surtsey's tephra ~10⁻⁴ m/s, Jakobsson &amp; Moore 1986).
     */
    static double seepageIntoCrater(WorldModel world, int cx, int cz, int crater, double floorZ) {
        double sea = world.spec().seaLevelZ();
        if (!Double.isFinite(sea) || !(sea > floorZ)) return 0;
        double l = world.spec().metersPerColumn();
        double head = sea - floorZ;
        double width = 0;
        double conductivity = 0;
        int rays = 0;
        for (int d = 0; d < 8; d++) {
            // across the lagoon (if any) to the ring, then across the land of the ring to the sea
            int steps = 0;
            int first = -1;
            for (int r = crater + 1; r <= crater + MAX_RING_COLUMNS; r++) {
                int rx = cx + RING_DX[d] * r;
                int rz = cz + RING_DZ[d] * r;
                if (!world.isKnown(rx, rz)) break;
                boolean land = world.surfaceZ(rx, rz) >= sea;
                if (land) {
                    if (first < 0) first = r;
                    steps++;
                } else if (first >= 0) {
                    break;
                }
            }
            if (first < 0) continue;
            int x = cx + RING_DX[d] * first;
            int z = cz + RING_DZ[d] * first;
            int n = world.layerCount(x, z);
            if (n == 0) continue;
            conductivity += Math.pow(10, world.layer(x, z, n - 1).materialInfo().log10HydraulicConductivity());
            width += Math.max(1, steps) * l * (Math.abs(RING_DX[d]) + Math.abs(RING_DZ[d]) == 2 ? Math.sqrt(2) : 1);
            rays++;
        }
        if (rays == 0) return 0;
        double k = conductivity / rays;
        double length = width / rays;
        double area = 2 * Math.PI * crater * l * head;
        return VentPartition.WATER_DENSITY * k * head / length * area;
    }

    /**
     * Lays the wet fallout of vent {@code vent}: magma quenched and granulated where it meets the water, and
     * the coarse share of a collapsing wet column, piled up around the vent (Kokelaar 1983, 1986). Jet ejecta
     * are not part of it: they fly ballistically ({@link #fireJets}). The pile covers the crater floor (it
     * falls back into the vent and fills it as a slurry, Kokelaar 1983) and thins beyond the rim exponentially
     * over the vent's own size (the crater radius, at least one column); repose relaxation
     * then shapes it to its angle of repose. A fissure builds a ridge along its whole length (distances to
     * its segment). Under water ({@code waterDepthM > 0}) it is hyaloclastite; in air, loose wet ash (a tuff
     * ring).
     */
    private void buildTuffRing(StepContext context, VentSite vent, double massKg, double waterDepthM) {
        if (terrain == null) return;
        WorldModel world = terrain.world();
        double l = world.spec().metersPerColumn();
        double volume = massKg / TUFF_BULK_DENSITY;
        double crater = Math.max(l, vent.craterRadiusM());
        double scale = crater;
        double outer = crater + APRON_REACH * scale;
        Point3 c = vent.position();
        double half = vent.kind() == VentKind.FISSURE ? vent.fissureLengthM() / 2 : 0;
        double ux = Math.cos(vent.fissureAngleRad());
        double uz = Math.sin(vent.fissureAngleRad());
        int cx = c.columnX(l);
        int cz = c.columnZ(l);
        int reach = (int) Math.ceil((outer + half) / l);
        List<long[]> cells = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0;
        for (int dz = -reach; dz <= reach; dz++) {
            for (int dx = -reach; dx <= reach; dx++) {
                int x = cx + dx;
                int z = cz + dz;
                double px = (x + 0.5) * l - c.x();
                double pz = (z + 0.5) * l - c.z();
                double along = Math.max(-half, Math.min(half, px * ux + pz * uz));
                double r = Math.hypot(px - along * ux, pz - along * uz);
                if (r > outer || !world.isKnown(x, z)) continue;
                double w = Math.exp(-Math.max(0, r - crater) / scale);
                cells.add(new long[] {x, z});
                weights.add(w);
                total += w;
            }
        }
        if (total <= 0) return;
        boolean submarine = waterDepthM > 0;
        int unit = units.unit(submarine ? DepositType.HYALOCLASTITE : DepositType.FALL, context.time(), Double.NaN);
        var material = submarine ? MaterialTable.HYALOCLASTITE : MaterialTable.ASH;
        double area = l * l;
        for (int i = 0; i < cells.size(); i++) {
            double thickness = volume * weights.get(i) / total / area;
            if (!(thickness > 0)) continue;
            world.deposit((int) cells.get(i)[0], (int) cells.get(i)[1], thickness, material, unit,
                    LayerFlags.LOOSE, WET_TEPHRA_POROSITY, 0);
        }
    }

    private void setPhreatomagmatic(StepContext context, boolean active, VentSite vent) {
        if (active == phreatomagmatic) return;
        phreatomagmatic = active;
        Point3 at = (vent != null ? vent : referenceSite()).position();
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
        return !columns.isEmpty();
    }

    /** True while water fragments a large share of the erupting magma. */
    public boolean phreatomagmatic() {
        return phreatomagmatic;
    }

    public boolean columnCollapsing() {
        for (Column c : columns.values()) if (c.collapseSource != null) return true;
        return false;
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
    /** Share of the erupting magma fragmented by water at the last step (0 when not erupting). */
    public double wetShare() {
        VentPartition.Result p = lastPartition;
        return p != null && p.magmaMassFlux() > 0 ? p.waterFragmentedMassFlux() / p.magmaMassFlux() : 0;
    }

    /** Water at the main vent at the last step: open depth, rim openness, saturated fill, seepage. */
    public VentPartition.Water ventWater() {
        return lastWater;
    }

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
        JsonArray known = new JsonArray();
        knownFissures.forEach(known::add);
        out.add("knownFissureIds", known);
        JsonObject feederState = new JsonObject();
        for (Map.Entry<String, FissureFeeder> e : feeders.entrySet()) feederState.add(e.getKey(), e.getValue().save());
        out.add("feeders", feederState);
        JsonArray local = new JsonArray();
        for (LocalVent v : localVents.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", v.site().id());
            o.add("position", v.site().position().toJson());
            o.addProperty("radiusM", v.site().craterRadiusM());
            o.addProperty("fissureId", v.fissureId());
            o.add("throat", v.throat().save());
            o.addProperty("referenceConductance", v.referenceConductance());
            local.add(o);
        }
        out.add("localVents", local);
        JsonArray sealedVents = new JsonArray();
        sealed.forEach(sealedVents::add);
        out.add("sealedVents", sealedVents);
        JsonObject states = new JsonObject();
        for (Map.Entry<String, VentStatus> e : ventStates.entrySet()) states.addProperty(e.getKey(), e.getValue().name());
        out.add("ventStates", states);
        JsonObject flux = new JsonObject();
        for (Map.Entry<String, Double> e : ventFlux.entrySet()) flux.addProperty(e.getKey(), e.getValue());
        out.add("ventFlux", flux);
        out.addProperty("flankPending", flankPending);
        JsonObject columnStates = new JsonObject();
        for (Map.Entry<String, Column> e : columns.entrySet()) {
            JsonObject c = new JsonObject();
            c.addProperty("rate", e.getValue().rate);
            c.addProperty("collapse", e.getValue().collapse);
            c.addProperty("gas", e.getValue().gas);
            if (e.getValue().collapseSource != null) c.addProperty("collapseSource", e.getValue().collapseSource);
            columnStates.add(e.getKey(), c);
        }
        out.add("columns", columnStates);
        out.addProperty("burstPhaseUntil", burstPhaseUntil);
        if (burstVentId != null) out.addProperty("burstVentId", burstVentId);
        out.addProperty("phreatomagmatic", phreatomagmatic);
        out.addProperty("waterDepthM", waterDepthM);
        out.addProperty("openWaterFraction", openWaterFraction);
        out.addProperty("nextSteamEventTime", nextSteamEventTime);
        out.addProperty("slugBursts", slugBursts);
        out.addProperty("plugBursts", plugBursts);
    }

    @Override
    public void loadState(StateReader reader) {
        JsonObject in = reader.json();
        activeLavaSources.clear();
        for (JsonElement e : in.getAsJsonArray("lavaSources")) activeLavaSources.add(e.getAsString());
        eruptionVents.clear();
        for (JsonElement e : in.getAsJsonArray("eruptionVents")) eruptionVents.add(e.getAsString());
        knownFissures.clear();
        feeders.clear();
        sealed.clear();
        ventStates.clear();
        ventFlux.clear();
        for (JsonElement e : in.getAsJsonArray("knownFissureIds")) knownFissures.add(e.getAsString());
        for (Map.Entry<String, JsonElement> e : in.getAsJsonObject("feeders").entrySet()) {
            feeders.put(e.getKey(), FissureFeeder.load(e.getValue().getAsJsonObject()));
        }
        localVents.clear();
        for (JsonElement e : in.getAsJsonArray("localVents")) {
            JsonObject o = e.getAsJsonObject();
            VentSite site = VentSite.crater(o.get("id").getAsString(), Point3.fromJson(o.get("position")),
                    o.get("radiusM").getAsDouble());
            String fissureId = o.get("fissureId").getAsString();
            VentThroat throat;
            if (o.has("throat")) {
                throat = VentThroat.load(o.getAsJsonObject("throat"));
            } else { // saves from before throats cooled: the conductance it kept, on its fissure's wall rock
                FissureFeeder feeder = feeders.get(fissureId);
                double radius = Math.pow(8 * o.get("conductance").getAsDouble() / Math.PI, 0.25);
                throat = feeder != null ? new VentThroat(radius, feeder.heightM, feeder.wallRockC, feeder.age)
                        : new VentThroat(radius, chamber.config().lithostaticDepth(), chamber.conduitWallTemperatureC(), 0);
            }
            localVents.put(site.id(), new LocalVent(site, fissureId, throat, o.get("referenceConductance").getAsDouble()));
        }
        for (JsonElement e : in.getAsJsonArray("sealedVents")) sealed.add(e.getAsString());
        for (Map.Entry<String, JsonElement> e : in.getAsJsonObject("ventStates").entrySet()) {
            ventStates.put(e.getKey(), VentStatus.valueOf(e.getValue().getAsString()));
        }
        for (Map.Entry<String, JsonElement> e : in.getAsJsonObject("ventFlux").entrySet()) {
            ventFlux.put(e.getKey(), e.getValue().getAsDouble());
        }
        flankPending = in.get("flankPending").getAsBoolean();
        columns.clear();
        if (in.has("columns")) {
            for (Map.Entry<String, JsonElement> e : in.getAsJsonObject("columns").entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                Column c = new Column();
                c.rate = o.get("rate").getAsDouble();
                c.collapse = o.get("collapse").getAsDouble();
                c.gas = o.get("gas").getAsDouble();
                c.collapseSource = o.has("collapseSource") ? o.get("collapseSource").getAsString() : null;
                columns.put(e.getKey(), c);
            }
        }
        // saves from before one column per vent: the column restarts at the next step (its vent unknown)
        staleCollapseSource = in.has("collapseSource") ? in.get("collapseSource").getAsString() : null;
        burstPhaseUntil = in.get("burstPhaseUntil").getAsDouble();
        burstVentId = in.has("burstVentId") ? in.get("burstVentId").getAsString() : null;
        phreatomagmatic = in.get("phreatomagmatic").getAsBoolean();
        waterDepthM = in.get("waterDepthM").getAsDouble();
        openWaterFraction = in.get("openWaterFraction").getAsDouble();
        nextSteamEventTime = in.get("nextSteamEventTime").getAsDouble();
        slugBursts = in.get("slugBursts").getAsLong();
        plugBursts = in.get("plugBursts").getAsLong();
        lastPartition = null;
    }
}
