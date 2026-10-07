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
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.dike.Dike;
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
import me.alex4386.typhon.engine.volcano.VolcanoScaling;
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
    /** Seconds between steam telemetry events. */
    static final double STEAM_EVENT_SECONDS = 20;
    /** Bulk density of fresh wet tuff (kg/m³). */
    static final double TUFF_BULK_DENSITY = 1500;
    /** Width (blocks) of the tuff ring beyond the crater rim. */
    static final int TUFF_RING_WIDTH = 6;
    static final int MAX_TUFF_BLOCKS_PER_STEP = 64;
    /** Porosity of fresh, wet Surtseyan tephra (ash and lapilli; ~0.4–0.5, Jakobsson &amp; Moore 1986). */
    static final double WET_TEPHRA_POROSITY = 0.45;
    /** Furthest a crater's ring is followed outwards when measuring its width at sea level (columns). */
    static final int MAX_RING_COLUMNS = 64;
    private static final int[] RING_DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] RING_DZ = {0, 1, 1, 1, 0, -1, -1, -1};
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
    /** Most segments a fissure feeder is resolved into along strike. */
    static final int MAX_FEEDER_SEGMENTS = 16;

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
    private VolcanoScaling scaling;

    private final TreeSet<String> activeLavaSources = new TreeSet<>();
    private final Set<String> eruptionVents = new LinkedHashSet<>();
    /** Fissure vent ids already picked up from the dikes. */
    private final TreeSet<String> knownFissures = new TreeSet<>();
    /** Thermal state of each dike-fed fissure's feeder, by vent id. */
    private final TreeMap<String, FissureFeeder> feeders = new TreeMap<>();
    /** Vents sealed by the user. */
    private final TreeSet<String> sealed = new TreeSet<>();
    /** Last reported state of each vent. */
    private final TreeMap<String, VentStatus> ventStates = new TreeMap<>();
    /** Physical magma flux (m³/s DRE) leaving through each outlet at the last step. */
    private final TreeMap<String, Double> ventFlux = new TreeMap<>();
    private boolean flankPending;
    /** Column in progress: simulated mass rate (kg/s), collapse share, gas fraction. */
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
    /** Water at the main vent at the last step (reporting). */
    private VentPartition.Water lastWater = VentPartition.Water.DRY;
    /** Discrete explosions fired so far, by mechanism (for observers such as the style estimate). */
    private long slugBursts;
    private long plugBursts;
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
    /** Live retune: the volcano's scaling (length scales) from the next step. */
    public void setScaling(VolcanoScaling scaling, double ballisticFraction) {
        this.scaling = java.util.Objects.requireNonNull(scaling);
    }

    public void setGround(HydrothermalField ground) {
        this.ground = ground;
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
        if (dikes == null) return false;
        for (VentSite vent : baseVents) if (vent.id().equals(ventId)) return false;
        return dikes.removeFissure(ventId);
    }

    @Override
    public void step(StepContext context) {
        watchFissures(context);
        List<ConduitBurst> bursts = chamber.drainBursts();

        List<VentSite> vents = outlets();
        VentSite main = vents.isEmpty() ? baseVents.get(0) : vents.get(0);
        VentPartition.Water water = surveyWater(main);
        lastWater = water;
        chamber.setVentEnvironment(VentPartition.ambientPressurePa(water.surfaceDepthM()),
                water.waterTableDepthM());

        double rate = chamber.eruptionRate();
        ConduitSolution flow = chamber.conduitFlow();
        if (rate <= 0 || flow == null || vents.isEmpty()) {
            stopLava();
            stopExplosive();
            endBurstPhaseIfDue(context.time(), false);
            setPhreatomagmatic(context, false, null);
            lastPartition = null;
            if (!flankPending && !chamber.erupting()) eruptionVents.clear();
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

        VentPartition.Result p = VentPartition.partition(flow, chamber.ventAmbientPressurePa(),
                chamber.config().conduitRadius(), chamber.silicaWt(), water, this::criticalGasFraction);
        lastPartition = p;
        // The chamber's actual outflow (linearised between conduit solutions) sets the totals; the
        // partition sets the shares.
        double magmaMassRate = chamber.eruptionRate() * ExplosivePhase.DRE_DENSITY;
        double scale = p.magmaMassFlux() > 0 ? magmaMassRate / p.magmaMassFlux() : 0;
        double stepSeconds = context.dtSeconds();
        if (water.openFraction() <= 0 && water.surfaceDepthM() > 0) {
            boilCraterLake(main, magmaMassRate * stepSeconds, flow.exitTemperatureC());
        }

        double lavaRate = p.lavaMassFlux() * scale / ExplosivePhase.DRE_DENSITY;
        if (lavaRate > MIN_LAVA_RATE) updateLava(vents, weights, lavaRate);
        else stopLava();

        double column = p.columnMassFlux() * scale;
        boolean sustained = column >= MIN_COLUMN_MASS_FLUX;
        if (sustained) updateExplosive(main, p, column);
        else stopExplosive();

        double ballistic = p.ballisticMassFlux() * scale * stepSeconds;
        if (ballistic > 0) {
            // Cooled fall-back of the fountain: its whole mass lands as loose scoria lapilli around the vent
            // (and back into it); a few tracked bombs show the larger clasts.
            double median = Double.isFinite(p.medianClastM()) ? Math.max(p.medianClastM(), LAPILLI_MIN_M) : LAPILLI_MIN_M;
            tephra.proximalFallout(main, ballistic, p.ballisticSpeed(), 0, 15, median, LAPILLI_MIN_M,
                    Math.max(2 * LAPILLI_MIN_M, VentPartition.BALLISTIC_SIZE));
            tephra.launchSalvo(main, ballistic, p.ballisticSpeed(), 0, 15, chamber.silicaWt(), MAX_BOMBS_PER_SALVO);
        }

        double wetShare = p.magmaMassFlux() > 0 ? p.waterFragmentedMassFlux() / p.magmaMassFlux() : 0;
        setPhreatomagmatic(context, phreatomagmatic ? wetShare >= PHREATOMAGMATIC_STOP_SHARE
                : wetShare >= PHREATOMAGMATIC_START_SHARE, main);
        if (p.jetMassFlux() > 0) fireJets(context, main, p.jetMassFlux() * scale * stepSeconds, p.jetSpeed());
        if (p.wetFalloutMassFlux() > 0) buildTuffRing(context, main, p.wetFalloutMassFlux() * scale * stepSeconds);
        if (p.steamMassFlux() > 0 && context.time() >= nextSteamEventTime) {
            context.outbox().emit(new SurfaceEvents.PhreatomagmaticSteam(
                    context.time(), volcanoId, main.position(), p.steamMassFlux() * scale, waterDepthM));
            nextSteamEventTime = context.time() + STEAM_EVENT_SECONDS;
        }

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
        double length = vent.fissureLength() * scaling.metersPerBlock();
        int segments = Math.max(1, Math.min(MAX_FEEDER_SEGMENTS, vent.fissureLength() / 2));
        return FissureFeeder.open(dike.openingM(), length, dike.heightM(), segments, context.random());
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
        pushOutlets();
        reportStates(context);
    }

    /** Tells the chamber how much of its conduit's conductance the open outlets can carry. */
    private void pushOutlets() {
        redirectIfStranded();
        boolean summitOpen = false;
        for (VentSite vent : baseVents) summitOpen |= !sealed.contains(vent.id());
        chamber.setSummitBlocked(!summitOpen);
        chamber.setOutletCapacity(outletCapacity());
    }

    /**
     * When every vent of an ongoing eruption froze, was sealed or was removed, the pressurised magma
     * takes the path left open: the summit vents, unless they are sealed too (then the eruption ends
     * with its pressure kept, {@link MagmaChamber#setOutletCapacity}).
     */
    private void redirectIfStranded() {
        if (!chamber.erupting() || eruptionVents.isEmpty()) return;
        for (VentSite vent : allVents()) if (eruptionVents.contains(vent.id()) && open(vent.id())) return;
        for (VentSite vent : baseVents) if (!sealed.contains(vent.id())) eruptionVents.add(vent.id());
    }

    /**
     * Share of the chamber's conduit conductance the outlets can carry: 1 while an open summit vent
     * takes part, otherwise the fissure feeders' conductance relative to when they opened (the conduit
     * model describes the freshly opened dike), 0 when no outlet is left.
     */
    double outletCapacity() {
        double now = 0;
        double initial = 0;
        for (VentSite vent : outlets()) {
            FissureFeeder feeder = feeders.get(vent.id());
            if (feeder == null) return 1; // a summit vent
            now += feeder.conductance();
            initial += feeder.initialConductance;
        }
        if (initial <= 0) return 0;
        return Math.min(1, now / initial);
    }

    private void reportStates(StepContext context) {
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
        if (!outlet) return VentStatus.IDLE;
        return feeder != null && feeder.waning() ? VentStatus.WANING : VentStatus.ACTIVE;
    }

    /** True if magma can leave through {@code ventId}: not sealed and, for a fissure, not frozen. */
    private boolean open(String ventId) {
        if (sealed.contains(ventId)) return false;
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
            FissureFeeder feeder = feeders.get(vents.get(i).id());
            w[i] = feeder == null ? Math.PI * r * r * r * r / 8 : feeder.conductance();
            sum += w[i];
        }
        for (int i = 0; i < w.length; i++) w[i] = sum > 0 ? w[i] / sum : 1.0 / w.length;
        return w;
    }

    private VentSite find(String ventId) {
        for (VentSite vent : allVents()) if (vent.id().equals(ventId)) return vent;
        return null;
    }

    /** All vents of this volcano: the configured ones plus fissures opened by dikes (not removed). */
    public List<VentSite> allVents() {
        List<VentSite> all = new ArrayList<>(baseVents);
        if (dikes != null) all.addAll(dikes.openedVents());
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

    private void updateLava(List<VentSite> vents, double[] shares, double totalRate) {
        // Lava sources take the eruption rate (m³/s DRE), split across the vents.
        Set<String> wanted = new TreeSet<>();
        for (int i = 0; i < vents.size(); i++) {
            VentSite vent = vents.get(i);
            double perVent = totalRate * shares[i];
            if (perVent <= MIN_LAVA_RATE) continue;
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

    private void updateExplosive(VentSite vent, VentPartition.Result p, double columnMassRate) {
        boolean restart = explosiveRate <= 0
                || Math.abs(columnMassRate - explosiveRate) > PHASE_UPDATE_THRESHOLD * explosiveRate
                || Math.abs(p.collapseFraction() - explosiveCollapse) > 0.1
                || Math.abs(p.columnGasFraction() - explosiveGas) > PHASE_UPDATE_THRESHOLD * explosiveGas;
        if (!restart) return;
        explosiveRate = columnMassRate;
        explosiveCollapse = p.collapseFraction();
        explosiveGas = p.columnGasFraction();
        burstPhaseUntil = -1; // the sustained column takes over any ash puff

        // The phase's gas thrust is set by the jet: an equivalent overpressure that expands the
        // column's gas to its exit velocity.
        double overpressure = equivalentOverpressureMPa(p.columnVelocity(), p.columnGasFraction(), p.columnTemperatureC());
        ExplosivePhase phase = new ExplosivePhase(vent, columnMassRate, Math.min(1, p.columnGasFraction()), overpressure,
                p.columnTemperatureC(), chamber.silicaWt(), 0, p.grainSize());
        tephra.startPhase(phase.withMassEruptionRate(columnMassRate * (1 - p.collapseFraction())));
        updateCollapse(vent, columnMassRate * p.collapseFraction(), p.columnTemperatureC());
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
        tephra.launchSalvo(vent, mass, speed, 40, 15, chamber.silicaWt(), MAX_BOMBS_PER_SALVO);
        double energy = 0.5 * mass * speed * speed;
        if (seismicity != null) seismicity.queueExplosion(vent.position(), energy);
        if (explosionListener != null) explosionListener.accept(vent.position(), energy);
        context.outbox().emit(new SurfaceEvents.ExplosiveBurst(context.time(), volcanoId, BurstKind.SURTSEYAN_JET,
                vent.position(), mass, 0, speed, energy));
    }

    private java.util.function.BiConsumer<BlockPos, Double> explosionListener;

    /** Receives each discrete vent explosion (position, kinetic energy J), e.g. to excavate a crater. */
    public void setExplosionListener(java.util.function.BiConsumer<BlockPos, Double> listener) {
        this.explosionListener = listener;
    }

    private void startAshPuff(double now, VentSite vent, double ashMassKg, double durationSeconds, double gasFraction,
            double overpressureMPa, double temperatureC, double silicaWt, GrainSizeDistribution grain) {
        // the puff injects its whole ash mass over its duration
        tephra.startPhase(new ExplosivePhase(vent, ashMassKg / durationSeconds, Math.min(1, gasFraction), overpressureMPa,
                temperatureC, silicaWt, 0, grain));
        burstPhaseUntil = Math.max(burstPhaseUntil, now + durationSeconds);
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
        WorldModel world = terrain == null ? null : terrain.world();
        if (world == null || !world.isKnown(c.x(), c.z())) {
            waterDepthM = 0;
            openWaterFraction = 0;
            return new VentPartition.Water(0, 0, table);
        }
        double l = world.spec().metersPerColumn();
        int crater = Math.max(1, vent.craterRadius());
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
                int x = c.x() + dx;
                int z = c.z() + dz;
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
        openWaterFraction = wet && rimColumns > 0 && connectedToOpenWater(world, c, crater + MAX_RING_COLUMNS)
                ? Math.max(rimSubmerged / (double) rimColumns, 1.0 / rimColumns) : 0;

        // The vent's fill of loose tephra, and how much of its mouth is water-saturated (a slurry).
        double top = world.surfaceZ(c.x(), c.z());
        double fill = 0;
        double pores = 0;
        for (int k = world.layerCount(c.x(), c.z()) - 1; k > 0; k--) {
            LayerView layer = world.layer(c.x(), c.z(), k);
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
        double seepage = wet && openWaterFraction > 0 ? Double.NaN : seepageIntoCrater(world, c, crater, top - band);
        return new VentPartition.Water(waterDepthM, openWaterFraction, table, slurry, porosity, seepage);
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
        double area = world.spec().metersPerColumn() * world.spec().metersPerColumn();
        BlockPos c = vent.position();
        int crater = Math.max(1, vent.craterRadius());
        double lake = 0;
        for (int dz = -crater; dz <= crater; dz++) {
            for (int dx = -crater; dx <= crater; dx++) {
                if (dx * dx + dz * dz > crater * crater || !world.isKnown(c.x() + dx, c.z() + dz)) continue;
                double level = world.waterZ(c.x() + dx, c.z() + dz);
                if (Double.isFinite(level)) lake += Math.max(0, level - world.surfaceZ(c.x() + dx, c.z() + dz));
            }
        }
        lake *= area * VentPartition.WATER_DENSITY;
        if (!(lake > 0)) return;
        double keep = Math.max(0, 1 - boil / lake);
        for (int dz = -crater; dz <= crater; dz++) {
            for (int dx = -crater; dx <= crater; dx++) {
                int x = c.x() + dx;
                int z = c.z() + dz;
                if (dx * dx + dz * dz > crater * crater || !world.isKnown(x, z)) continue;
                double level = world.waterZ(x, z);
                double floor = world.surfaceZ(x, z);
                if (!Double.isFinite(level) || level <= floor) continue;
                world.setWaterZ(x, z, keep > 1e-3 ? floor + (level - floor) * keep : Double.NaN);
            }
        }
    }

    /**
     * Whether water standing at {@code c} connects through submerged columns (8-connected) to water beyond
     * {@code radius} columns, i.e. to the open sea or a lake, rather than being a lagoon enclosed by land.
     */
    static boolean connectedToOpenWater(WorldModel world, BlockPos c, int radius) {
        java.util.ArrayDeque<long[]> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        queue.add(new long[] {c.x(), c.z()});
        seen.add(BlockPos.pack(c.x(), 0, c.z()));
        while (!queue.isEmpty()) {
            long[] p = queue.poll();
            int x = (int) p[0];
            int z = (int) p[1];
            if ((x - c.x()) * (x - c.x()) + (z - c.z()) * (z - c.z()) > radius * radius) return true;
            for (int d = 0; d < 8; d++) {
                int nx = x + RING_DX[d];
                int nz = z + RING_DZ[d];
                if (!seen.add(BlockPos.pack(nx, 0, nz))) continue;
                if (!world.isKnown(nx, nz)) return true; // water running off the simulated area: the open sea
                double level = world.waterZ(nx, nz);
                if (Double.isFinite(level) && level > world.surfaceZ(nx, nz)) queue.add(new long[] {nx, nz});
            }
        }
        return false;
    }

    /**
     * Sea water seeping through the saturated edifice into a crater drawn down to {@code floorZ} (kg/s):
     * Darcy flow {@code ρ K Δh / L} through the crater wall below sea level ({@code 2π r Δh}), with
     * {@code L} the ring's mean width at sea level and {@code K} the hydraulic conductivity of its
     * surface deposits (Freeze &amp; Cherry 1979; Surtsey's tephra ~10⁻⁴ m/s, Jakobsson &amp; Moore 1986).
     */
    static double seepageIntoCrater(WorldModel world, BlockPos c, int crater, double floorZ) {
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
                int rx = c.x() + RING_DX[d] * r;
                int rz = c.z() + RING_DZ[d] * r;
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
            int x = c.x() + RING_DX[d] * first;
            int z = c.z() + RING_DZ[d] * first;
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
     * Wet jet and base-surge fallout around a water-fragmenting vent: loose wet tephra (and quench-granulated
     * lava) of physical thickness, in a ring peaking just outside the crater rim; some falls back into the
     * crater and fills the vent as a slurry (Kokelaar 1983). Slopes relax to the angle of repose in
     * geomorphology. The block view gets a tuff block once a block is more than half full.
     */
    private void buildTuffRing(StepContext context, VentSite vent, double massKg) {
        if (terrain == null) return;
        WorldModel world = terrain.world();
        double mpb = scaling.metersPerBlock();
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
                if (r > outer || !world.isKnown(c.x() + dx, c.z() + dz)) continue;
                double w = Math.exp(-(r - peak) * (r - peak) / 8.0);
                cells.add(new long[] {c.x() + dx, c.z() + dz});
                weights.add(w);
                total += w;
            }
        }
        if (total <= 0) return;
        int unit = units.unit(DepositType.FALL, context.time(), Double.NaN);
        int placed = 0;
        for (int i = 0; i < cells.size(); i++) {
            int x = (int) cells.get(i)[0];
            int z = (int) cells.get(i)[1];
            double thickness = bulkBlocks * weights.get(i) / total * mpb;
            if (!(thickness > 0)) continue;
            world.deposit(x, z, thickness, MaterialTable.ASH, unit, LayerFlags.LOOSE, WET_TEPHRA_POROSITY, 0);
            placed += mirrorTuff(context, world, x, z, MAX_TUFF_BLOCKS_PER_STEP - placed);
        }
    }

    /** Raises the block view of a column to its surface (whole blocks more than half full); returns blocks placed. */
    private int mirrorTuff(StepContext context, WorldModel world, int x, int z, int budget) {
        TerrainColumn column = terrain.column(x, z);
        if (column == null || budget <= 0) return 0;
        double l = world.spec().metersPerColumn();
        int target = (int) Math.floor(world.surfaceZ(x, z) / l - 0.5);
        int placed = 0;
        while (column.groundY() < target && placed < budget) {
            int y = column.groundY() + 1;
            terrain.updateBlockCache(x, z, y, TUFF);
            column = terrain.column(x, z);
            placed++;
        }
        return placed;
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
        knownFissures.clear();
        feeders.clear();
        sealed.clear();
        ventStates.clear();
        ventFlux.clear();
        if (in.has("knownFissureIds")) {
            for (JsonElement e : in.getAsJsonArray("knownFissureIds")) knownFissures.add(e.getAsString());
        } else if (in.has("knownFissures") && dikes != null) {
            // Older saves counted fissures; their feeders open afresh on the next step.
            List<VentSite> opened = dikes.openedVents();
            int n = Math.min(opened.size(), in.get("knownFissures").getAsInt());
            for (VentSite vent : opened.subList(0, n)) knownFissures.add(vent.id());
        }
        if (in.has("feeders")) {
            for (Map.Entry<String, JsonElement> e : in.getAsJsonObject("feeders").entrySet()) {
                feeders.put(e.getKey(), FissureFeeder.load(e.getValue().getAsJsonObject()));
            }
        }
        if (in.has("sealedVents")) {
            for (JsonElement e : in.getAsJsonArray("sealedVents")) sealed.add(e.getAsString());
        }
        if (in.has("ventStates")) {
            for (Map.Entry<String, JsonElement> e : in.getAsJsonObject("ventStates").entrySet()) {
                ventStates.put(e.getKey(), VentStatus.valueOf(e.getValue().getAsString()));
            }
        }
        if (in.has("ventFlux")) {
            for (Map.Entry<String, JsonElement> e : in.getAsJsonObject("ventFlux").entrySet()) {
                ventFlux.put(e.getKey(), e.getValue().getAsDouble());
            }
        }
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
    }
}
