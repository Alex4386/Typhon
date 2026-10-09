package me.alex4386.typhon.engine.lava;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.lava.LavaEvents.ChunkCoord;
import me.alex4386.typhon.engine.math.ColumnIndex;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.Outbox;
import me.alex4386.typhon.engine.random.SimRandom;
import me.alex4386.typhon.engine.sim.Parallel;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.GroundCoupling;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.LayerFlags;
import me.alex4386.typhon.engine.world.LayerView;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.Provenance;
import me.alex4386.typhon.engine.world.UnitTable;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.engine.save.FieldChunk;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;

/**
 * Lava flow as a 2.5D cellular automaton over surface columns (after MAGFLOW / SCIARA).
 *
 * <p>The automaton runs in physical units: a column is {@code L × L} m ({@link #metersPerColumn()}) and its
 * molten core is {@code h} m thick, so an erupted volume reaches real runout distances. Every column holds a molten core with temperature, SiO₂ and H₂O on top of the world-model ground
 * surface ({@link WorldModel#surfaceZ}, uplift included, real metres — so it flows over ash, ignimbrite and
 * earlier lava of any thickness), and optionally a rigid crust above the core. Each cell also carries
 * the stratigraphic unit (volcano, eruption) of its melt, taken from its {@link LavaSource} and
 * following the dominant inflow. Each step:
 *
 * <ol>
 *   <li><b>Effusion</b>: sources add lava, mixing heat and composition by volume.
 *   <li><b>Flux</b>: for each of the 8 neighbours with a lower melt surface (diagonals at
 *       {@code √2·L}), a Bingham fluid flows only if {@code h > h_cr = τ_y / (ρ g sinθ)}; the
 *       discharge per unit width is
 *       {@code q = ρ g sinθ h³ / (3η) · (1 − 3/2·(h_cr/h) + 1/2·(h_cr/h)³)} (MAGFLOW) through a face
 *       {@code L/2} wide. Equal face widths make a linear flux isotropic (Σcos²φ over the downhill
 *       half of the 8 directions is 2 for any slope direction), and diagonals let lava move
 *       diagonally even when each axis component alone is below the yield threshold, which removes
 *       the axis-aligned fingers of a 4-neighbour grid. The volume moved is limited to
 *       {@code relaxation · Δhead} per neighbour by one common factor for all of a cell's directions
 *       (so the physical split between directions is kept) and scaled so the total never exceeds
 *       {@code h}. A
 *       crust does not change the hydraulic head: melt keeps flowing beneath it (tube flow).
 *   <li><b>Update</b>: each cell gathers its inflows — a pure gather, so the result does not depend
 *       on iteration order and volume is conserved.
 *   <li><b>Cooling</b>:
 *       <ul>
 *         <li><i>Open or submerged columns</i> (thin, fast, at a vent, or under water) cool as a
 *             whole: radiation {@code εσ(T⁴ − T_a⁴)} plus free convection or, submerged,
 *             {@code h_w(T − T_w)}, plus basal conduction, over {@code ρ c_eff h} with latent heat
 *             between liquidus and solidus.
 *         <li><i>Quiet columns</i> (Biot number {@code h_s H / k} at least {@link #CRUST_BIOT}, i.e. thicker
 *             than the lumped-capacitance limit, and slower than
 *             {@link LavaConfig#crustDisruptionVelocity()}) grow a crust instead (Stefan problem): the
 *             conduction through the crust, balanced against the radiation and convection of its surface
 *             (solved for the surface temperature), freezes melt onto the crust base at
 *             {@code ρ (L + c (T − T_sol))} per m³, while the insulated core only loses heat through its
 *             base. A quiet pond's crust grows as ≈ √t (≈0.45 m after a day at k_c = 1 W/m·K), and
 *             insulated flows stay hot and travel farther.
 *         <li><i>Basal conduction</i>: melt and substrate meet as two half-spaces; the flux into the
 *             rock is {@code E (T − T_g) / √(π t)} with {@code E = e_l e_g / (e_l + e_g)},
 *             {@code e = √(kρc)} and {@code t} the time the column has been covered (its boundary layer
 *             grows as {@code √(π κ t)}; Carslaw &amp; Jaeger 1959).
 *         <li>Melt pushing up into its crust lifts it (inflation); a faster flow, a vent or water
 *             tears the crust up and re-mixes it.
 *       </ul>
 *       Below the solidus the column solidifies: its exact thickness is deposited into the world
 *       model as a {@link DepositType#LAVA} layer of its unit (rock from {@link LavaRocks#rockMaterial}). Lava
 *       quenched in water sheds part of its volume as {@link DepositType#HYALOCLASTITE} onto the
 *       steepest lower submerged neighbour, so sustained ocean entry builds a delta seaward.
 *   <li><b>Tubes</b>: when the melt under a crust drains away (supply stopped, flow moved on), a
 *       roof that spans the column under its own weight ({@link LavaConfig#minRoofThickness}:
 *       bending stress {@code ρ g L²/(2t)} below the roof's tensile strength) over a void at least
 *       {@link #MIN_TUBE_VOID_M} high stays standing: the world model gets a cavity ({@link DepositType#CAVITY}) under a
 *       {@link DepositType#TUBE_ROOF} layer, and {@link #tubes()} lists them. Thinner roofs collapse
 *       into the column.
 * </ol>
 *
 * <p>Columns whose ground is not known act as walls; chunks of them are requested with a
 * {@link LavaEvents.TerrainNeeded} event. Tube voids are listed by {@link #tubes()}.
 *
 * <p>References: Del Negro et al. (2008), Bull. Volcanol. 70:805-812 (MAGFLOW cellular automaton); Hon et al. (1994), GSA Bull. 106:351-370 (crust growth, inflation, tubes). See {@code docs/references.md}.
 */
public final class LavaFlow implements Subsystem {
    public static final String ID = "lava";

    private static final int STATE_FORMAT = 7;
    private static final double SIGMA = 5.670374419e-8;
    private static final double G = 9.81;
    private static final double KELVIN = 273.15;
    private static final double GAP_EPS = 1e-6;
    /** Share of a cell's melt a newer unit must contribute to take the cell over (see gather). */
    private static final double UNIT_SHARE = 0.1;
    private static final double WATER_HEAT_CAPACITY = 4186;
    /**
     * Air at a film temperature of ≈700 K (Incropera &amp; DeWitt, Table A.4): conductivity (W/m·K), kinematic
     * viscosity and thermal diffusivity (m²/s), for free convection off a hot surface.
     */
    private static final double AIR_K = 0.0524;
    private static final double AIR_NU = 68.1e-6;
    private static final double AIR_ALPHA = 97.0e-6;
    /**
     * Below this Biot number {@code h_s·H/k} a column cools as a whole (lumped capacitance, the textbook
     * Bi &lt; 0.1 criterion); above it the surface cools faster than heat arrives from inside and a crust forms.
     */
    static final double CRUST_BIOT = 0.1;
    private static final double WATER_LATENT_HEAT = 2.26e6;
    private static final int DIRS = LavaChunk.DIRECTIONS;
    // 4 orthogonal then 4 diagonal; d ^ 1 is the opposite direction
    private static final int[] DX = {-1, 1, 0, 0, -1, 1, -1, 1};
    private static final int[] DZ = {0, 0, -1, 1, -1, 1, 1, -1};
    /** Neighbour distance in cells. */
    private static final double[] DIST = {1, 1, 1, 1, Math.sqrt(2), Math.sqrt(2), Math.sqrt(2), Math.sqrt(2)};
    /** Face width per direction as a fraction of L (equal widths: isotropic linear flux). */
    private static final double FACE = 0.5;
    private static final int AREA = LavaChunk.AREA;
    /** Minimum chunks per parallel task (a chunk is 256 cells of work). */
    private static final int CHUNK_GRAIN = 1;
    private static final Comparator<LavaChunk> BY_KEY = Comparator.comparingLong(c -> c.key);

    private final TerrainModel terrain;
    private LavaConfig config;
    private final LavaRheology rheology;

    private final ChunkMap chunks = new ChunkMap();

    /**
     * Lava chunks by packed chunk key. {@code Long.hashCode} of the packed {@code (cx << 32 | cz)} key
     * is {@code cx ^ cz}, so whole diagonals of chunks would share a bucket; keys are spread with the
     * SplitMix64 finalizer (a bijection) before hashing. Iteration order is not used for results
     * (callers sort by {@link LavaChunk#key}).
     */
    private static final class ChunkMap {
        private final HashMap<Long, LavaChunk> map = new HashMap<>();

        private static long mix(long z) {
            z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
            z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
            return z ^ (z >>> 31);
        }

        LavaChunk get(long key) {
            return map.get(mix(key));
        }

        void put(long key, LavaChunk chunk) {
            map.put(mix(key), chunk);
        }

        void remove(long key) {
            map.remove(mix(key));
        }

        Collection<LavaChunk> values() {
            return map.values();
        }

        boolean isEmpty() {
            return map.isEmpty();
        }

        void clear() {
            map.clear();
        }
    }
    private final Map<String, LavaSource> sources = new LinkedHashMap<>();
    private final List<ColumnIndex> origins = new ArrayList<>();
    private final TreeSet<Long> requestedTerrain = new TreeSet<>();
    /** Columns holding a lava tube cavity (packed x/z) → {bottomZ, topZ} (m); the cavities live in the world model. */
    private final TreeMap<Long, double[]> tubeColumns = new TreeMap<>();
    private double emittedVolume;
    private double solidifiedVolume;

    // per-step state
    private final TreeMap<Long, OceanEntry> oceanEntries = new TreeMap<>();
    private long oceanGeneration; // bumped whenever oceanEntries is cleared (invalidates chunk caches)
    private final SolidStats solidAcc = new SolidStats();
    private final TreeSet<Long> neededTerrain = new TreeSet<>();
    private long stamp = Long.MIN_VALUE + 1;
    private double currentTime;

    // the fluidity seen last step, which sizes this step's flow sub-steps (persisted)
    private GroundCoupling ground = GroundCoupling.NONE; // transient
    private int heatCell = 1; // columns per side of the blocks heat is summed over
    private double lastMaxDiffusivity;
    /**
     * Fastest cooling pace of the last step (1/s): the sub-iterations per second the most rapidly cooling or
     * crusting column needed ({@link LavaConfig#coolingStepK}); 0 before any lava has cooled.
     */
    private double lastCoolPace;
    private int lastSubsteps = 1;
    private double eventSeconds;
    private long chunkGeneration = 1; // bumped whenever a lava chunk is created (neighbour-cache validity)

    public LavaFlow(TerrainModel terrain) {
        this(terrain, LavaConfig.defaults(), MagflowRheology.INSTANCE);
    }

    public LavaFlow(TerrainModel terrain, LavaConfig config) {
        this(terrain, config, MagflowRheology.INSTANCE);
    }

    public LavaFlow(TerrainModel terrain, LavaConfig config, LavaRheology rheology) {
        this.terrain = terrain;
        this.config = config;
        this.rheology = rheology;
    }

    @Override
    public boolean reconfigure(Object c) {
        if (!(c instanceof LavaConfig n)) return false;
        config = n;
        return true;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void registerCommands(CommandBus bus) {
        bus.register(LavaCommands.StartEffusion.class, c -> addSource(c.source()));
        bus.register(LavaCommands.StopEffusion.class, c -> removeSource(c.sourceId()));
        bus.register(LavaCommands.SetEffusionRate.class, c -> setRate(c.sourceId(), c.rateM3PerS()));
    }

    // ── Public API ──

    @Override
    public LavaConfig config() {
        return config;
    }

    /** Column width L (m), from the world model. */
    public double metersPerColumn() {
        return world().spec().metersPerColumn();
    }

    private double area() {
        double l = metersPerColumn();
        return l * l;
    }

    private WorldModel world() {
        return terrain.world();
    }

    public void addSource(LavaSource source) {
        sources.put(source.id(), source);
        for (ColumnIndex cell : source.cells()) {
            if (!origins.contains(cell)) origins.add(cell);
        }
    }

    public void removeSource(String sourceId) {
        sources.remove(sourceId);
    }

    public void setRate(String sourceId, double rateM3PerS) {
        LavaSource source = sources.get(sourceId);
        if (source != null) sources.put(sourceId, source.withRate(rateM3PerS));
    }

    public Collection<LavaSource> sources() {
        return Collections.unmodifiableCollection(sources.values());
    }

    /**
     * Injects {@code volumeM3} m³ of lava into a column (e.g. a molten bomb or a lava-dome collapse). Returns
     * false if the column's ground is unknown.
     */
    public boolean addLava(int x, int z, double volumeM3, double temperatureC, double silicaWt, double waterWt) {
        return addLava(x, z, volumeM3, temperatureC, silicaWt, waterWt, UnitTable.UNATTRIBUTED);
    }

    /** {@link #addLava} attributing the melt to stratigraphic {@code unit} (when it dominates the cell). */
    public boolean addLava(int x, int z, double volumeM3, double temperatureC, double silicaWt, double waterWt,
            int unit) {
        if (volumeM3 <= 0) return true;
        LavaChunk c = chunkFor(x >> 4, z >> 4);
        if (c == null) return false;
        if (c.freshStamp != stamp) refresh(c);
        int i = index(x, z);
        if (Double.isNaN(c.bed[i])) return false;
        double h0 = c.thickness[i];
        double added = volumeM3 / area();
        double h = h0 + added;
        if (h0 <= 0 || (added >= UNIT_SHARE * h && unit > c.unit[i])) c.unit[i] = unit;
        c.temperature[i] = (c.temperature[i] * h0 + temperatureC * added) / h;
        c.silica[i] = (c.silica[i] * h0 + silicaWt * added) / h;
        c.water[i] = (c.water[i] * h0 + waterWt * added) / h;
        c.thickness[i] = h;
        if (h0 <= 0) c.lavaCells++;
        emittedVolume += volumeM3;
        return true;
    }

    /** Molten core thickness (real m). */
    public double thickness(int x, int z) {
        LavaChunk c = chunks.get(LavaChunk.key(x >> 4, z >> 4));
        return c == null ? 0 : c.thickness[index(x, z)];
    }

    public double temperatureC(int x, int z) {
        LavaChunk c = chunks.get(LavaChunk.key(x >> 4, z >> 4));
        return c == null ? 0 : c.temperature[index(x, z)];
    }

    /** Rigid crust above the molten core (real m). */
    public double crustThickness(int x, int z) {
        LavaChunk c = chunks.get(LavaChunk.key(x >> 4, z >> 4));
        return c == null ? 0 : c.crust[index(x, z)];
    }

    /** Stratigraphic unit of the melt in a column ({@link UnitTable#UNATTRIBUTED} if none). */
    public int unitAt(int x, int z) {
        LavaChunk c = chunks.get(LavaChunk.key(x >> 4, z >> 4));
        return c == null ? UnitTable.UNATTRIBUTED : c.unit[index(x, z)];
    }

    /** Molten volume (real m³). Emitted = molten + crust + solidified. */
    /** Lava field totals for dashboards. Volumes in real m³. */
    public record Snapshot(double moltenVolumeM3, int activeCells, double emittedM3, double solidifiedM3, int tubes) {}

    @Override
    public Snapshot snapshot() {
        return new Snapshot(totalLavaVolume(), activeCellCount(), emittedVolume(), solidifiedVolume(), tubeColumns.size());
    }

    public double totalLavaVolume() {
        double sum = 0;
        for (LavaChunk c : sortedChunks(chunks.values())) {
            for (int i = 0; i < AREA; i++) sum += c.thickness[i];
        }
        return sum * area();
    }

    /** Volume currently held in crusts over molten cores (real m³). */
    public double crustVolume() {
        double sum = 0;
        for (LavaChunk c : sortedChunks(chunks.values())) {
            for (int i = 0; i < AREA; i++) sum += c.crust[i];
        }
        return sum * area();
    }

    /**
     * World expansion activity: chunks holding molten lava, and chunks a flow is waiting for (unknown
     * terrain it would flow into).
     */
    public void reportActivity(me.alex4386.typhon.engine.expansion.ExpansionActivity.Sink sink) {
        for (LavaChunk c : chunks.values()) {
            if (c.lavaCells > 0) sink.active((c.cx << 4) + 8, (c.cz << 4) + 8);
        }
        for (long key : requestedTerrain) sink.active(((int) (key >> 32) << 4) + 8, ((int) key << 4) + 8);
    }

    public int activeCellCount() {
        int n = 0;
        for (LavaChunk c : chunks.values()) n += c.lavaCells;
        return n;
    }

    public int crustedCellCount() {
        int n = 0;
        for (LavaChunk c : chunks.values()) n += c.crustCells;
        return n;
    }

    /** Lava emitted so far (real m³). */
    public double emittedVolume() {
        return emittedVolume;
    }

    /** Lava solidified so far (real m³). */
    public double solidifiedVolume() {
        return solidifiedVolume;
    }

    /**
     * Hollow lava-tube voids left by drained flows, in column order, with their elevation ranges (m).
     * The cavities themselves are {@link DepositType#CAVITY} layers in the world model; a
     * column whose cavity was later filled or collapsed by other edits drops out of the list.
     */
    public List<LavaTube> tubes() {
        List<LavaTube> list = new ArrayList<>();
        WorldModel world = world();
        for (Map.Entry<Long, double[]> e : tubeColumns.entrySet()) {
            int x = (int) (e.getKey() >> 32);
            int z = (int) (long) e.getKey();
            if (hasCavity(world, x, z)) list.add(new LavaTube(x, z, e.getValue()[0], e.getValue()[1]));
        }
        return Collections.unmodifiableList(list);
    }

    private static boolean hasCavity(WorldModel world, int x, int z) {
        int n = world.layerCount(x, z);
        for (int k = 0; k < n; k++) {
            LayerView layer = world.layer(x, z, k);
            if (layer.material() == MaterialTable.VOID.id() && world.unit(layer.unit()).type() == DepositType.CAVITY) {
                return true;
            }
        }
        return false;
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    // ── Step ──

    /**
     * The ground model the field exchanges heat and water with (transient: re-attach when the engine
     * is built). Cooling lava conducts its base loss into the ground; lava in standing water (lakes,
     * ponds, the sea) is quenched and boils the water off; columns under such water count as
     * submerged. Default {@link GroundCoupling#NONE}.
     */
    public void setGround(GroundCoupling ground) {
        this.ground = ground == null ? GroundCoupling.NONE : ground;
        // Heat is summed over aligned blocks of the ground model's cell width (a power of two that
        // divides a chunk; wider cells take one block per chunk, handed over at its centre).
        int width = Math.max(1, this.ground.heatCellColumns());
        int cell = 1;
        while (cell * 2 <= Math.min(16, width)) cell *= 2;
        this.heatCell = cell;
    }

    /** Index of column {@code i}'s heat cell within its chunk. */
    private int heatCellOf(int i) {
        int per = 16 / heatCell;
        return ((i >> 4) / heatCell) * per + (i & 15) / heatCell;
    }

    public GroundCoupling ground() {
        return ground;
    }

    /** Flow sub-steps used in the last step. */
    public int lastSubsteps() {
        return lastSubsteps;
    }

    /**
     * While lava moves, the flux sub-steps ({@link LavaConfig#maxSubsteps()} at most) must stay within
     * the explicit stability limit of the most fluid lava seen last step. While any lava is molten, a step
     * stays within the cooling sub-iterations of its fastest-cooling column (half the {@link
     * #MAX_COOL_ITERATIONS} budget at last step's pace): a crusted pond cooling for months takes long steps,
     * fresh lava radiating at 1000 °C short ones. Before any lava has cooled, {@link #MOLTEN_STEP_SECONDS}.
     */
    @Override
    public double maxStepSeconds() {
        if (activeCellCount() == 0 && sourcesIdle()) return Double.POSITIVE_INFINITY;
        double limit = lastCoolPace > 0 ? 0.5 * MAX_COOL_ITERATIONS / lastCoolPace : MOLTEN_STEP_SECONDS;
        if (lastMaxDiffusivity > 0) {
            double stable = config.relaxation() * metersPerColumn() * metersPerColumn() / lastMaxDiffusivity;
            limit = Math.min(limit, config.maxSubsteps() * stable);
        }
        return limit;
    }

    /** Longest step while lava is molten but its cooling pace is not known yet (s). */
    static final double MOLTEN_STEP_SECONDS = 120;

    private boolean sourcesIdle() {
        for (LavaSource source : sources.values()) if (source.rateM3PerS() > 0) return false;
        return true;
    }

    // ── Step ──

    @Override
    public void step(StepContext context) {
        double now = context.time();
        currentTime = now;
        stamp = context.step();
        double dt = context.dtSeconds();
        eventSeconds += dt;
        Outbox outbox = context.outbox();
        neededTerrain.clear();

        // 0. effusion: each source injects what its volcano erupted this step
        for (LavaSource source : sources.values()) {
            if (source.rateM3PerS() <= 0) continue;
            double perCell = source.rateM3PerS() * dt / source.cells().size();
            for (ColumnIndex cell : source.cells()) {
                if (!addLava(cell.x(), cell.z(), perCell, source.temperatureC(), source.silicaWt(), source.waterWt(),
                        source.unit())) {
                    neededTerrain.add(LavaChunk.key(cell.x() >> 4, cell.z() >> 4));
                    continue;
                }
                chunks.get(LavaChunk.key(cell.x() >> 4, cell.z() >> 4)).sourceStamp[index(cell.x(), cell.z())] = stamp;
            }
        }

        refreshSurfaceWater();

        // Parallel phases only write their own chunk and read neighbours through the slots prepared
        // (sequentially) beforehand; anything order-dependent is deferred to a sequential pass in
        // chunk-key order, so results do not depend on the thread count.
        Parallel parallel = context.parallel();

        // 1–2. flow sub-steps (flux, gather, swap). The explicit flux is stable for
        // Δt ≤ relaxation·L²/D, D = ρgh³/3η, for the most fluid moving lava seen last step (h capped
        // at the resolved lobe thickness; thicker channels and ponds level through the relaxation cap).
        int substeps = 1;
        if (lastMaxDiffusivity > 0) {
            double stable = config.relaxation() * metersPerColumn() * metersPerColumn() / lastMaxDiffusivity;
            substeps = (int) Math.min(config.maxSubsteps(), Math.max(1, Math.ceil(dt / stable)));
        }
        lastSubsteps = substeps;
        double dtSub = dt / substeps;
        double maxDiffusivity = 0;
        TreeMap<Long, LavaChunk> touched = new TreeMap<>();
        for (int sub = 0; sub < substeps; sub++) {
            List<LavaChunk> active = new ArrayList<>();
            for (LavaChunk c : chunks.values()) if (c.lavaCells > 0) active.add(c);
            active.sort(BY_KEY);
            for (LavaChunk c : active) {
                ensureFresh(c);
                prepareFlux(c);
            }
            parallel.forEach(active, CHUNK_GRAIN, c -> computeFlux(c, dtSub));
            for (LavaChunk c : active) {
                maxDiffusivity = Math.max(maxDiffusivity, c.maxDiffusivity);
                for (int slot = 0; slot < 9; slot++) {
                    if ((c.touchOut & (1 << slot)) != 0) c.neighbours[slot].touchedStamp = stamp;
                }
            }

            List<LavaChunk> update = new ArrayList<>();
            for (LavaChunk c : chunks.values()) if (c.isActive() || c.touchedStamp == stamp) update.add(c);
            update.sort(BY_KEY);
            for (LavaChunk c : update) {
                ensureFresh(c);
                prepareNeighbours(c);
            }
            parallel.forEach(update, CHUNK_GRAIN, c -> gather(c, dtSub));
            for (LavaChunk c : update) {
                foldOceanInflow(c);
                touched.put(c.key, c);
            }
            parallel.forEach(update, CHUNK_GRAIN, c -> {
                c.swapBuffers();
                c.recount();
            });
        }
        lastMaxDiffusivity = maxDiffusivity;
        List<LavaChunk> update = new ArrayList<>(touched.values());

        // 3. cooling, crust, tubes & solidification over the whole physical step: heat balance in
        // parallel (it reads neighbours' speeds only), then the resulting solidification /
        // drained-tube actions (world edits, random draws, block changes) in order
        parallel.forEach(update, CHUNK_GRAIN, c -> {
            c.actionCount = 0;
            c.coolPace = 0;
            if (c.isActive()) coolHeat(c, dt);
        });
        double pace = 0;
        for (LavaChunk c : update) pace = Math.max(pace, c.coolPace);
        lastCoolPace = pace;
        SolidStats stats = solidAcc;
        List<LavaTube> formed = new ArrayList<>();
        for (LavaChunk c : update) {
            applyCoolActions(c, context.random(), outbox, stats, formed);
            foldOceanHeat(c);
            flushGroundExchange(c);
        }

        // 4. counts (the engine renders no blocks: hosts project the lava field themselves, mc-projection)
        parallel.forEach(update, CHUNK_GRAIN, LavaChunk::recount);

        // 5. events & cleanup
        double interval = config.eventPeriodSeconds();
        if (context.crossed(interval)) {
            if (stats.cells > 0) {
                outbox.emit(new LavaEvents.LavaSolidified(now, interval, stats.cells, stats.volume));
            }
            stats.clear();
            emitOceanEntries(now, interval, outbox);
        }
        if (!formed.isEmpty()) {
            outbox.emit(new LavaEvents.LavaTubesFormed(now, formed));
        }
        if (context.crossed(config.frontEventPeriodSeconds())) {
            emitFront(now, update, outbox);
        }
        if (!neededTerrain.isEmpty()) {
            List<ChunkCoord> fresh = new ArrayList<>();
            for (long key : neededTerrain) {
                if (requestedTerrain.add(key)) fresh.add(new ChunkCoord((int) (key >> 32), (int) key));
            }
            if (!fresh.isEmpty()) outbox.emit(new LavaEvents.TerrainNeeded(now, fresh));
        }
        for (LavaChunk c : update) {
            if (!c.hasPersistentState()) chunks.remove(c.key);
        }
    }

    /**
     * Sequential: resolves (creating them if terrain is known) the neighbour chunks this chunk's flux
     * can reach — those next to an edge cell that will try to flow.
     */
    private void prepareFlux(LavaChunk c) {
        double minFlow = config.minFlowThickness();
        int needed = 0;
        for (int i = 0; i < AREA; i++) {
            int lx = i & 15;
            int lz = i >> 4;
            if (lx != 0 && lx != 15 && lz != 0 && lz != 15) continue;
            if (c.thickness[i] < minFlow || Double.isNaN(c.bed[i])) continue;
            for (int d = 0; d < DIRS; d++) {
                int nx = lx + DX[d];
                int nz = lz + DZ[d];
                if (nx >= 0 && nx <= 15 && nz >= 0 && nz <= 15) continue;
                needed |= 1 << slot(nx, nz);
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            if (slot == 4 || (needed & (1 << slot)) == 0) continue;
            int ox = slot % 3 - 1;
            int oz = slot / 3 - 1;
            neighbourAt(c, ox < 0 ? -1 : ox > 0 ? 16 : 0, oz < 0 ? -1 : oz > 0 ? 16 : 0, true);
        }
        c.touchOut = 0;
    }

    /** Sequential: resolves all eight neighbour chunks (without creating any) for read-only phases. */
    private void prepareNeighbours(LavaChunk c) {
        for (int slot = 0; slot < 9; slot++) {
            if (slot == 4) continue;
            int ox = slot % 3 - 1;
            int oz = slot / 3 - 1;
            neighbourAt(c, ox < 0 ? -1 : ox > 0 ? 16 : 0, oz < 0 ? -1 : oz > 0 ? 16 : 0, false);
        }
    }

    private static int slot(int nx, int nz) {
        int ox = nx < 0 ? -1 : nx > 15 ? 1 : 0;
        int oz = nz < 0 ? -1 : nz > 15 ? 1 : 0;
        return (oz + 1) * 3 + (ox + 1);
    }

    /** Parallel-safe neighbour lookup: only the slots resolved by a prepare pass this step. */
    private LavaChunk peekNeighbour(LavaChunk c, int nx, int nz) {
        if (c.neighbourStamp != stamp) throw new IllegalStateException("neighbours of chunk not prepared this step");
        return c.neighbours[slot(nx, nz)];
    }

    private void computeFlux(LavaChunk c, double dt) {
        double[] flux = new double[DIRS];
        c.fluxStamp = stamp;
        c.maxDiffusivity = 0;
        Arrays.fill(c.outflow, 0);
        Arrays.fill(c.speed, 0);
        double rhoG = config.densityKgM3() * G;
        double minFlow = config.minFlowThickness();
        double relaxation = config.relaxation();
        double subH = config.substepFlowThicknessM();
        double cell = metersPerColumn();
        double perDischarge = FACE * dt / cell; // thickness moved per unit discharge: q·(FACE·L)·dt / L²

        for (int i = 0; i < AREA; i++) {
            double h = c.thickness[i];
            if (h < minFlow || Double.isNaN(c.bed[i])) continue;
            double head = c.bed[i] + h;
            int lx = i & 15;
            int lz = i >> 4;

            double eta = Double.NaN;
            double tau = 0;
            boolean moving = false;
            double total = 0;
            double capScale = 1;
            double discharge = 0; // physical q·width/L summed over directions (m²/s), before numerical caps
            for (int d = 0; d < DIRS; d++) {
                flux[d] = 0;
                int nx = lx + DX[d];
                int nz = lz + DZ[d];
                LavaChunk nc = c;
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                    nc = peekNeighbour(c, nx, nz);
                    if (nc == null) continue;
                }
                int j = ((nz & 15) << 4) | (nx & 15);
                if (Double.isNaN(nc.bed[j])) continue;
                double dh = head - (nc.bed[j] + nc.thickness[j]);
                if (dh <= 0) continue;
                if (eta != eta) { // rheology only for cells that have somewhere to flow
                    double t = c.temperature[i];
                    eta = rheology.viscosityPaS(t, c.silica[i], c.water[i]);
                    tau = rheology.yieldStrengthPa(t, c.silica[i]);
                }
                double run = DIST[d] * cell;
                double sin = dh / Math.sqrt(dh * dh + run * run);
                double drive = rhoG * sin;
                double ratio = tau / (drive * h); // h_cr / h
                if (ratio >= 1) continue;
                double q = drive * h * h * h / (3 * eta) * (1 - 1.5 * ratio + 0.5 * ratio * ratio * ratio);
                if (!moving) { // flow diffusivity of this moving lava at the resolved lobe thickness: sizes the sub-steps
                    moving = true;
                    double hr = Math.min(h, subH);
                    double diffusivity = rhoG * hr * hr * hr / (3 * eta);
                    if (diffusivity > c.maxDiffusivity) c.maxDiffusivity = diffusivity;
                }
                discharge += q * FACE;
                double v = q * perDischarge;
                if (v > 0) {
                    flux[d] = v;
                    total += v;
                    // One factor for the whole cell keeps the physical split between directions when
                    // the relaxation cap binds; capping each direction separately would send equal
                    // shares down the axis and both diagonals and fan the flow out at ±45°.
                    capScale = Math.min(capScale, dh * relaxation / v);
                }
            }
            c.speed[i] = discharge / h;
            if (total <= 0) continue;
            total *= capScale;
            double scale = capScale * (total > h ? h / total : 1);
            for (int d = 0; d < DIRS; d++) {
                if (flux[d] <= 0) continue;
                c.outflow[d * AREA + i] = flux[d] * scale;
                int nx = lx + DX[d];
                int nz = lz + DZ[d];
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                    c.touchOut |= 1 << slot(nx, nz);
                }
            }
        }
    }

    private void gather(LavaChunk c, double dt) {
        double[] inVolume = new double[DIRS];
        int[] inUnit = new int[DIRS];
        c.oceanInflowSeen = false;
        c.oceanInflow = 0;
        c.oceanMaxFlux = -1;
        c.oceanExplosive = false;
        boolean ownFlux = c.fluxStamp == stamp;
        for (int i = 0; i < AREA; i++) {
            double h = c.thickness[i];
            double out = ownFlux ? outflowOf(c, i) : 0;
            double keep = h - out;
            if (keep < 1e-15) keep = 0;
            double volume = keep;
            double heat = keep * c.temperature[i];
            double silica = keep * c.silica[i];
            double water = keep * c.water[i];
            // The cell's unit: the newest unit (units are numbered in creation order) contributing at
            // least UNIT_SHARE of the melt — a new eruption's lava mixing into an older pond turns it
            // into the new flow unit, instead of flickering between the two.
            int unit = keep > 0 ? c.unit[i] : -1;

            int lx = i & 15;
            int lz = i >> 4;
            double inflow = 0;
            int inflows = 0;
            for (int d = 0; d < DIRS; d++) {
                int nx = lx + DX[d];
                int nz = lz + DZ[d];
                LavaChunk nc = c;
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                    nc = peekNeighbour(c, nx, nz);
                    if (nc == null) continue;
                }
                if (nc.fluxStamp != stamp) continue;
                int k = ((nz & 15) << 4) | (nx & 15);
                double in = nc.outflow[(d ^ 1) * AREA + k];
                if (in <= 0) continue;
                inflow += in;
                heat += in * nc.temperature[k];
                silica += in * nc.silica[k];
                water += in * nc.water[k];
                inVolume[inflows] = in;
                inUnit[inflows++] = nc.unit[k];
            }
            volume += inflow;
            double threshold = UNIT_SHARE * volume;
            for (int n = 0; n < inflows; n++) {
                if (inVolume[n] >= threshold && inUnit[n] > unit) unit = inUnit[n];
            }

            c.nextThickness[i] = volume;
            c.nextUnit[i] = unit < 0 ? c.unit[i] : unit;
            // the bed keeps its heated boundary layer while lava (or its crust) stays on it; fresh cover starts anew
            c.nextContact[i] = volume > 0 && (h > 0 || c.crust[i] > 0) ? c.contact[i] : 0;
            if (volume > 0) {
                c.nextTemperature[i] = heat / volume;
                c.nextSilica[i] = silica / volume;
                c.nextWater[i] = water / volume;
                if (inflow > 0 && submerged(c, i)) recordWaterInflow(c, i, inflow * area(), dt);
            } else {
                c.nextTemperature[i] = 0;
                c.nextSilica[i] = 0;
                c.nextWater[i] = 0;
            }
        }
    }

    private static double outflowOf(LavaChunk c, int i) {
        double sum = 0;
        for (int d = 0; d < DIRS; d++) sum += c.outflow[d * AREA + i];
        return sum;
    }

    // ── Ocean entry aggregation ──

    /** Per-zone (chunk) accumulator for {@link LavaEvents.LavaOceanEntry}. */
    private static final class OceanEntry {
        double inflowM3;
        double heatJ;
        double maxFluxM3s = -1;
        long maxPos;
        boolean explosive;
    }

    private OceanEntry oceanEntry(LavaChunk c) {
        // cached on the chunk: this runs for every submerged molten column every step
        if (c.oceanGeneration != oceanGeneration || c.ocean == null) {
            c.ocean = oceanEntries.computeIfAbsent(c.key, k -> new OceanEntry());
            c.oceanGeneration = oceanGeneration;
        }
        return (OceanEntry) c.ocean;
    }

    /** Lava flowing into a submerged column this step (chunk-local; see {@link #foldOceanInflow}). */
    private void recordWaterInflow(LavaChunk c, int i, double volumeM3, double dt) {
        c.oceanInflowSeen = true;
        c.oceanInflow += volumeM3;
        double flux = dt > 0 ? volumeM3 / dt : 0;
        if (flux > c.oceanMaxFlux) {
            c.oceanMaxFlux = flux;
            c.oceanMaxPos = columnKey(c.worldX(i), c.worldZ(i));
        }
        if (flux >= config.littoralExplosionFluxM3s()) c.oceanExplosive = true;
    }

    /** Sequential: adds the chunk's ocean inflow of this step to its zone accumulator. */
    private void foldOceanInflow(LavaChunk c) {
        if (!c.oceanInflowSeen) return;
        OceanEntry e = oceanEntry(c);
        e.inflowM3 += c.oceanInflow;
        if (c.oceanMaxFlux > e.maxFluxM3s) {
            e.maxFluxM3s = c.oceanMaxFlux;
            e.maxPos = c.oceanMaxPos;
        }
        if (c.oceanExplosive) e.explosive = true;
        c.oceanInflowSeen = false;
    }

    /** Heat a submerged column released into the water this step (chunk-local). */
    private void recordWaterHeat(LavaChunk c, double heatJ) {
        if (heatJ > 0) c.oceanHeat += heatJ;
    }

    /** Sequential: adds the chunk's released heat of this step to its zone accumulator. */
    private void foldOceanHeat(LavaChunk c) {
        if (c.oceanHeat > 0) oceanEntry(c).heatJ += c.oceanHeat;
        c.oceanHeat = 0;
    }

    /** One {@link LavaEvents.LavaOceanEntry} per zone with molten lava in water, then resets. */
    private void emitOceanEntries(double now, double interval, Outbox outbox) {
        if (oceanEntries.isEmpty()) return;
        double seconds = eventSeconds;
        eventSeconds = 0;
        double minVolume = config.waterEntryMinVolumeM3();
        int emitted = 0;
        for (Map.Entry<Long, OceanEntry> entry : oceanEntries.entrySet()) {
            OceanEntry e = entry.getValue();
            LavaChunk c = chunks.get(entry.getKey());
            int columns = 0;
            double molten = 0;
            long largest = 0;
            double largestVolume = -1;
            if (c != null) {
                for (int i = 0; i < AREA; i++) {
                    double v = c.thickness[i] * area();
                    if (v < minVolume || !submerged(c, i)) continue;
                    columns++;
                    molten += v;
                    if (v > largestVolume) {
                        largestVolume = v;
                        largest = columnKey(c.worldX(i), c.worldZ(i));
                    }
                }
            }
            if (columns == 0 && e.inflowM3 < minVolume) continue; // only quench films: not worth an event
            if (emitted++ >= config.maxWaterEventsPerStep()) break;
            double powerW = seconds > 0 ? e.heatJ / seconds : 0;
            double steam = powerW / (WATER_HEAT_CAPACITY * Math.max(0, 100 - config.waterC()) + WATER_LATENT_HEAT);
            Point3 pos = surfacePoint(e.maxFluxM3s >= 0 ? e.maxPos : largest);
            outbox.emit(new LavaEvents.LavaOceanEntry(now, interval, pos, columns, molten,
                    seconds > 0 ? e.inflowM3 / seconds : 0, powerW / 1e6, steam, e.explosive));
        }
        oceanEntries.clear();
        oceanGeneration++;
    }

    /** Upper bound on heat-balance sub-iterations of one column per step. */
    private static final int MAX_COOL_ITERATIONS = 64;

    private static final int ACT_DRAIN = 1;
    private static final int ACT_SOLIDIFY = 2;
    private static final int ACT_SUBMERGED = 4;
    private static final int ACT_QUENCHED = 8;
    private static final int ACT_COLUMNAR = 16;

    private static void defer(LavaChunk c, int i, int flags) {
        c.actions[c.actionCount++] = (i << 5) | flags;
    }

    /**
     * Sequential, in cell order: the solidification and drained-column actions {@link #coolHeat}
     * deferred (they edit the world model, draw random numbers and emit block changes).
     */
    private void applyCoolActions(LavaChunk c, SimRandom random, Outbox outbox, SolidStats stats,
            List<LavaTube> formed) {
        for (int n = 0; n < c.actionCount; n++) {
            int a = c.actions[n];
            int i = a >>> 5;
            if ((a & ACT_DRAIN) != 0) {
                resolveDrained(c, i, random, outbox, stats, formed);
            } else {
                solidifyColumn(c, i, c.silica[i], (a & ACT_SUBMERGED) != 0, (a & ACT_QUENCHED) != 0,
                        (a & ACT_COLUMNAR) != 0, random, outbox, stats);
            }
        }
        c.actionCount = 0;
    }

    /**
     * Heat balance, crust growth and inflation of every column of the chunk (parallel-safe: writes
     * only this chunk); columns that freeze or drain are queued for {@link #applyCoolActions}.
     */
    private void coolHeat(LavaChunk c, double dt) {
        c.actionCount = 0;
        double rho = config.densityKgM3();
        double cs = config.coolingScale();
        double ambient = config.ambientC();
        double stepK = config.coolingStepK();
        boolean track = ground != GroundCoupling.NONE;
        double kLava = config.crustConductivityWMK();
        // melt and rock brought into contact: the interface sits between them by their thermal effusivities
        // e = √(kρc), and the flux into the rock decays as the boundary layer below grows (√(π κ t))
        double eLava = Math.sqrt(kLava * rho * config.specificHeatJKgK());
        double eGround = Math.sqrt(config.groundConductivityWMK() * config.groundVolumetricHeatCapacityJM3K());
        double contactEffusivity = eLava * eGround / (eLava + eGround);
        double roofSpan = config.minRoofThickness(metersPerColumn());
        for (int i = 0; i < AREA; i++) {
            double h = c.thickness[i];
            double hc = c.crust[i];
            if (h <= 0 && hc <= 0) continue;
            boolean submerged = submerged(c, i);
            boolean source = isSource(c, i);
            double speed = Double.NaN; // neighbourhood flow speed, read only when a crust decision needs it
            int quadrant = heatCellOf(i);

            if (hc > 0) {
                double meltTop = c.bed[i] + h;
                double crustBase = c.roofTop[i] - hc;
                if (meltTop > crustBase) c.roofTop[i] += meltTop - crustBase; // inflation lifts the roof
                // A young crust is torn up by fast flow; a roof thick enough to span the flow is
                // anchored to its levees and lets the melt run beneath it (tube flow).
                boolean anchored = hc >= roofSpan;
                if (!submerged && !source && !anchored) speed = localSpeed(c, i);
                boolean torn = submerged || source || (!anchored && speed > config.crustDisruptionVelocity());
                if (torn && h > 0) {
                    remelt(c, i);
                    h = c.thickness[i];
                    hc = 0;
                }
            }
            if (h <= 0) { // the melt flowed away from under its crust
                defer(c, i, ACT_DRAIN);
                continue;
            }

            double si = c.silica[i];
            double liquidus = rheology.liquidusC(si);
            double solidus = rheology.solidusC(si);
            double floor = submerged ? config.waterC() : ambient;
            double t = c.temperature[i];
            double maxRate = 0;
            boolean underVoid = false;
            double age = c.contact[i];
            // Integrate the heat balance over the (possibly compressed) step in sub-iterations that
            // each cool the core by at most coolingStepK and grow the crust by a bounded amount.
            double remaining = dt;
            for (int iter = 0; remaining > 0 && h > 0; iter++) {
                double meltTop = c.bed[i] + h;
                double gap = hc > 0 ? (c.roofTop[i] - hc) - meltTop : 0;
                underVoid = hc > 0 && gap > GAP_EPS;

                double qTop;
                if (submerged) {
                    qTop = config.waterHeatTransferWM2K() * (t - config.waterC());
                } else if (underVoid) {
                    qTop = 0; // melt under a drained roof faces a hot void, not the sky
                } else if (hc > 0 && t > ambient) {
                    // conduction through the crust balances what its surface loses
                    double ts = crustSurfaceC(t, hc, kLava, ambient);
                    qTop = kLava * (t - ts) / hc;
                } else {
                    qTop = surfaceLoss(t, ambient);
                }
                // basal conduction, averaged over the rest of the step: E·ΔT·2(√(a+Δt) − √a)/(Δt·√π)
                double qBase = remaining > 0 && t > ambient
                        ? contactEffusivity * (t - ambient) * 2 * (Math.sqrt(age + remaining) - Math.sqrt(age))
                                / (remaining * Math.sqrt(Math.PI))
                        : 0;
                double cEff = config.specificHeatJKgK();
                if (t < liquidus && t > solidus) cEff += config.latentHeatJKg() / (liquidus - solidus);

                boolean anchored = hc >= roofSpan;
                // a crust forms where the surface loses heat faster than conduction brings it up (Biot number)
                double hSurface = t > ambient ? surfaceLoss(t, ambient) / (t - ambient) : 0;
                boolean quiet = config.crustEnabled() && !submerged && !underVoid
                        && (h + hc) * hSurface / kLava >= CRUST_BIOT && !source;
                if (quiet && !anchored) {
                    if (speed != speed) speed = localSpeed(c, i);
                    quiet = speed <= config.crustDisruptionVelocity();
                }
                boolean growing = quiet && t > solidus && qTop > 0;

                double rate = growing // physical core cooling rate, K/s
                        ? qBase / (rho * cEff * Math.max(h, 0.01))
                        : (qTop + qBase) / (rho * cEff * Math.max(h, 0.01));
                maxRate = Math.max(maxRate, rate);
                double step = remaining;
                if (rate * cs * step > stepK) step = stepK / (rate * cs);
                // sub-iterations per second this column needs at its present cooling and crusting rates
                double pace = rate * cs / stepK;
                double freezeHeat = 0;
                if (growing) {
                    freezeHeat = rho * (config.latentHeatJKg() + config.specificHeatJKgK() * (t - solidus));
                    double maxGrow = Math.max(0.02, 0.2 * hc);
                    if (qTop * cs * step / freezeHeat > maxGrow) step = maxGrow * freezeHeat / (qTop * cs);
                    pace = Math.max(pace, qTop * cs / (maxGrow * freezeHeat));
                }
                c.coolPace = Math.max(c.coolPace, pace);
                if (iter >= MAX_COOL_ITERATIONS - 1) step = remaining; // bounded work per column
                step = Math.min(step, remaining);

                if (growing) {
                    // Stefan: the surface loss freezes melt onto the crust base; the core cools through its base.
                    double grow = Math.min(h, qTop * cs * step / freezeHeat);
                    if (hc <= 0) {
                        c.roofTop[i] = meltTop;
                        c.crustKind[i] = LavaRocks.crustKind(si);
                    }
                    h -= grow;
                    hc += grow;
                }
                double cooled = Math.max(t - rate * cs * step, floor);
                if (submerged) recordWaterHeat(c, rho * cEff * h * (t - cooled) * area());
                if (track) {
                    // Physical energy (J) of this sub-iteration: the base conduction goes into the
                    // ground, the surface loss of a column standing in water goes into the water.
                    double seconds = cs * step * area();
                    c.groundHeat[quadrant] += Math.max(0, qBase) * seconds;
                    if (c.surfaceWater[i] >= SUBMERGED_WATER_M) c.boilHeat[i] += Math.max(0, qTop) * seconds;
                }
                t = cooled;
                remaining -= step;
                age += step;
                if (t <= solidus || (underVoid && h < config.tubeDrainThickness())) break;
            }
            c.thickness[i] = h;
            c.crust[i] = hc;
            c.temperature[i] = t;
            c.contact[i] = age;

            if (h <= 0) {
                // the whole melt froze into the crust
                defer(c, i, ACT_SOLIDIFY | (submerged ? ACT_SUBMERGED : 0) | (submerged ? 0 : ACT_COLUMNAR));
            } else if (underVoid && (h < config.tubeDrainThickness() || t <= solidus)) {
                defer(c, i, ACT_DRAIN);
            } else if (t <= solidus) {
                boolean quenched = submerged || maxRate > config.quenchRateKPerS();
                // thermal contraction joints every lava that cools through its solidus without quenching
                boolean columnar = !quenched;
                defer(c, i, ACT_SOLIDIFY | (submerged ? ACT_SUBMERGED : 0) | (quenched ? ACT_QUENCHED : 0)
                        | (columnar ? ACT_COLUMNAR : 0));
            }
        }
    }

    /**
     * Heat loss (W/m²) of a lava surface at {@code surfaceC} into air at {@code ambientC}: radiation plus
     * turbulent free convection off a hot horizontal plate, {@code h = 0.14 k (g β ΔT / ν α)^{1/3}}
     * (McAdams; Incropera &amp; DeWitt), with β = 1/T_film. Radiation dominates (hundreds vs ~10 W/m²·K).
     */
    private double surfaceLoss(double surfaceC, double ambientC) {
        if (!(surfaceC > ambientC)) return 0;
        double tsK = surfaceC + KELVIN;
        double taK = ambientC + KELVIN;
        double radiative = config.emissivity() * SIGMA * (tsK * tsK * tsK * tsK - taK * taK * taK * taK);
        double dT = surfaceC - ambientC;
        double film = 0.5 * (tsK + taK);
        double hConv = 0.14 * AIR_K * Math.cbrt(G * dT / (film * AIR_NU * AIR_ALPHA));
        return radiative + hConv * dT;
    }

    /**
     * Surface temperature (°C) of a crust {@code crustM} thick over melt at {@code meltC}: the conduction
     * through the crust, {@code k (T − T_s) / H}, equals the surface loss (bisection; the loss rises with T_s).
     */
    private double crustSurfaceC(double meltC, double crustM, double k, double ambientC) {
        double lo = ambientC;
        double hi = meltC;
        for (int n = 0; n < 32; n++) {
            double mid = 0.5 * (lo + hi);
            if (surfaceLoss(mid, ambientC) > k * (meltC - mid) / crustM) hi = mid;
            else lo = mid;
        }
        return 0.5 * (lo + hi);
    }

    /**
     * Fastest physical flow speed in the cell and its 8 neighbours this step: a crust plate is
     * sheared apart by fast melt beside it, not only beneath it.
     */
    private double localSpeed(LavaChunk c, int i) {
        double v = c.fluxStamp == stamp ? c.speed[i] : 0;
        int lx = i & 15;
        int lz = i >> 4;
        for (int d = 0; d < DIRS; d++) {
            int nx = lx + DX[d];
            int nz = lz + DZ[d];
            LavaChunk nc = c;
            if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                nc = peekNeighbour(c, nx, nz);
                if (nc == null) continue;
            }
            if (nc.fluxStamp != stamp) continue;
            v = Math.max(v, nc.speed[((nz & 15) << 4) | (nx & 15)]);
        }
        return v;
    }

    /** Tears the crust up and mixes it back into the melt (crust taken at the solidus). */
    private void remelt(LavaChunk c, int i) {
        double h = c.thickness[i];
        double hc = c.crust[i];
        double total = h + hc;
        double crustT = rheology.solidusC(c.silica[i]);
        c.temperature[i] = (c.temperature[i] * h + crustT * hc) / total;
        c.thickness[i] = total;
        c.crust[i] = 0;
        c.roofTop[i] = 0;
    }

    /**
     * The melt under a crust is gone (or a film): the film freezes onto the floor, then a thick enough
     * roof over a void stays as a tube, otherwise the roof collapses into the column.
     */
    private void resolveDrained(LavaChunk c, int i, SimRandom random, Outbox outbox, SolidStats stats,
            List<LavaTube> formed) {
        double film = c.thickness[i];
        double hc = c.crust[i];
        byte kind = c.crustKind[i];
        double silica = film > 0 ? c.silica[i] : LavaRocks.crustSilica(kind);
        boolean submerged = submerged(c, i);
        clearMelt(c, i);
        stats.volume += film * area();
        solidifiedVolume += film * area();
        depositRock(c, i, film, LavaRocks.rockMaterial(silica, submerged, false), c.unit[i], 0);

        // a roof strong enough to stand over a real void stays as a tube
        double gap = (c.roofTop[i] - hc) - c.bed[i];
        if (hc >= config.minRoofThickness(metersPerColumn()) && gap >= MIN_TUBE_VOID_M) {
            formTube(c, i, kind, formed);
        } else {
            // roof too thin or no void: it caves in onto the floor
            c.crust[i] = 0;
            c.roofTop[i] = 0;
            solidifiedVolume += hc * area();
            stats.volume += hc * area();
            double crustSilica = LavaRocks.crustSilica(kind);
            depositRock(c, i, hc, LavaRocks.rockMaterial(crustSilica, submerged, false), c.unit[i], 0);
        }
        stats.cells++;
    }

    /**
     * Numerical: a drained void under a standing roof must be at least this high (m) to be kept as a cavity in
     * the world model (thinner gaps are not resolved; the roof settles onto the floor).
     */
    static final double MIN_TUBE_VOID_M = 0.5;

    /**
     * Converts a drained column into a roofed void: the world model gets the cavity and the roof as
     * layers of the flow's eruption, and the roof top becomes the surface.
     */
    private void formTube(LavaChunk c, int i, byte kind, List<LavaTube> formed) {
        int x = c.worldX(i);
        int z = c.worldZ(i);

        double hc = c.crust[i];
        double voidBottom = c.bed[i];
        double cavity = Math.max(0, (c.roofTop[i] - hc) - voidBottom);
        WorldModel world = world();
        int lavaUnit = c.unit[i];
        int versionBefore = world.version(x, z);
        if (cavity > 0) {
            world.deposit(x, z, cavity, MaterialTable.VOID,
                    Provenance.sibling(world, lavaUnit, DepositType.CAVITY, currentTime), 0, 1, 0);
        }
        Material roofRock = LavaRocks.rockMaterial(LavaRocks.crustSilica(kind), false, false);
        world.deposit(x, z, hc, roofRock, Provenance.sibling(world, lavaUnit, DepositType.TUBE_ROOF, currentTime),
                LayerFlags.FRACTURED, roofRock.porosity(), 1);
        rereadBed(c, i, versionBefore);
        double voidTop = voidBottom + cavity;
        tubeColumns.put(columnKey(x, z), new double[] {voidBottom, voidTop});

        solidifiedVolume += hc * area();
        c.crust[i] = 0;
        c.roofTop[i] = 0;
        formed.add(new LavaTube(x, z, voidBottom, voidTop));
    }

    private void clearMelt(LavaChunk c, int i) {
        c.contact[i] = 0;
        c.thickness[i] = 0;
        c.temperature[i] = 0;
        c.silica[i] = 0;
        c.water[i] = 0;
    }

    /** Freezes melt and crust of a column into rock. */
    private void solidifyColumn(LavaChunk c, int i, double silica, boolean submerged, boolean quenched,
            boolean columnar, SimRandom random, Outbox outbox, SolidStats stats) {
        double total = c.thickness[i] + c.crust[i];
        clearMelt(c, i);
        c.crust[i] = 0;
        c.roofTop[i] = 0;
        solidifiedVolume += total * area();
        stats.cells++;
        stats.volume += total * area();

        if (submerged && config.hyaloclastiteFraction() > 0) {
            total -= shedHyaloclastite(c, i, total * config.hyaloclastiteFraction(), random, outbox, stats);
        }
        // cooling joints make thick, slowly cooled flows permeable
        depositRock(c, i, total, LavaRocks.rockMaterial(silica, submerged, quenched), c.unit[i],
                columnar ? LayerFlags.FRACTURED : 0);
    }

    /**
     * Quench-shattered fragments slide onto the lowest submerged neighbour below this column's
     * surface (delta foreset). Returns the thickness shed.
     */
    private double shedHyaloclastite(LavaChunk c, int i, double volume, SimRandom random, Outbox outbox,
            SolidStats stats) {
        double surface = c.bed[i];
        int lx = i & 15;
        int lz = i >> 4;
        LavaChunk best = null;
        int bestIndex = -1;
        double bestTop = surface - 0.5 * metersPerColumn();
        for (int d = 0; d < DIRS; d++) {
            int nx = lx + DX[d];
            int nz = lz + DZ[d];
            LavaChunk nc = c;
            if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                nc = neighbourAt(c, nx, nz, true);
                if (nc == null) continue;
            }
            int j = ((nz & 15) << 4) | (nx & 15);
            if (Double.isNaN(nc.bed[j]) || !submerged(nc, j)) continue;
            double top = nc.bed[j] + nc.thickness[j];
            if (top < bestTop) {
                bestTop = top;
                best = nc;
                bestIndex = j;
            }
        }
        if (best == null) return 0;
        int unit = Provenance.sibling(world(), c.unit[i], DepositType.HYALOCLASTITE, currentTime);
        // quench-shattered glass sheds as a loose breccia that avalanches down the flow front (it consolidates
        // later, by palagonitisation)
        depositRock(best, bestIndex, volume, MaterialTable.HYALOCLASTITE, unit, LayerFlags.LOOSE);
        return volume;
    }

    /** Lays {@code thickness} m of solid rock into the world model (the exact amount, as a layer of {@code unit}). */
    private void depositRock(LavaChunk c, int i, double thickness, Material material, int unit, int flags) {
        if (!(thickness > 0)) return;
        int x = c.worldX(i);
        int z = c.worldZ(i);
        int versionBefore = world().version(x, z);
        world().deposit(x, z, thickness, material, unit, flags, material.porosity(), (flags & LayerFlags.LOOSE) != 0 ? 0 : 1.0);
        // the stacks hold elevations as floats: re-read so the cache equals a fresh read (restores)
        rereadBed(c, i, versionBefore);
    }

    private void emitFront(double now, List<LavaChunk> update, Outbox outbox) {
        if (origins.isEmpty()) return;
        double best = -1;
        Point3 front = null;
        int cells = 0;
        double volume = 0;
        for (LavaChunk c : update) {
            if (c.lavaCells == 0) continue;
            for (int i = 0; i < AREA; i++) {
                double h = c.thickness[i];
                if (h <= 0) continue;
                cells++;
                volume += h * area();
                int x = c.worldX(i);
                int z = c.worldZ(i);
                double nearest = Double.MAX_VALUE;
                for (ColumnIndex o : origins) {
                    double dx = x - o.x();
                    double dz = z - o.z();
                    nearest = Math.min(nearest, dx * dx + dz * dz);
                }
                if (nearest > best) {
                    best = nearest;
                    front = Point3.columnCentre(x, z, c.bed[i] + h, metersPerColumn());
                }
            }
        }
        if (front != null) {
            outbox.emit(new LavaEvents.LavaFlowFront(now, front, Math.sqrt(best) * metersPerColumn(), cells, volume));
        }
    }

    // ── Chunk management ──

    private LavaChunk chunkFor(int cx, int cz) {
        long key = LavaChunk.key(cx, cz);
        LavaChunk c = chunks.get(key);
        if (c != null) return c;
        if (!anyKnown(cx, cz)) return null;
        requestedTerrain.remove(key);
        c = new LavaChunk(cx, cz);
        chunks.put(key, c);
        chunkGeneration++;
        return c;
    }

    /** Whether any column of chunk {@code (cx, cz)} has known ground. */
    private boolean anyKnown(int cx, int cz) {
        WorldModel world = world();
        for (int i = 0; i < AREA; i++) {
            if (world.isKnown((cx << 4) | (i & 15), (cz << 4) | (i >> 4))) return true;
        }
        return false;
    }

    /** The lava surface at the centre of a packed column (m). */
    private Point3 surfacePoint(long key) {
        int x = (int) (key >> 32);
        int z = (int) key;
        WorldModel world = world();
        double s = world.surfaceZ(x, z);
        double y = Double.isFinite(s) ? s + thickness(x, z) : 0;
        return Point3.columnCentre(x, z, y, metersPerColumn());
    }

    /**
     * Chunk containing local cell {@code (nx, nz)} just outside {@code c} (each in {@code [-1, 16]});
     * with {@code create}, makes it (or requests terrain).
     */
    private LavaChunk neighbourAt(LavaChunk c, int nx, int nz, boolean create) {
        int ox = nx < 0 ? -1 : nx > 15 ? 1 : 0;
        int oz = nz < 0 ? -1 : nz > 15 ? 1 : 0;
        if (c.neighbourStamp != stamp) {
            Arrays.fill(c.neighbours, null);
            Arrays.fill(c.missingNeighbour, 0);
            c.neighbourStamp = stamp;
        }
        int slot = (oz + 1) * 3 + (ox + 1);
        LavaChunk n = c.neighbours[slot];
        if (n != null) return n;
        // Absent neighbours are cached too (most edge cells of a flow face empty chunks); the cache
        // is valid until any chunk is created. Bit 0: absent for lookups, bit 1: absent even when
        // creating (terrain unknown, already requested).
        long missing = c.missingNeighbour[slot];
        if (missing != 0 && (missing >>> 2) == chunkGeneration && (create ? (missing & 2) != 0 : (missing & 1) != 0)) {
            return null;
        }
        int ncx = c.cx + ox;
        int ncz = c.cz + oz;
        if (create) {
            n = chunkFor(ncx, ncz);
            if (n == null) {
                neededTerrain.add(LavaChunk.key(ncx, ncz));
                c.missingNeighbour[slot] = (chunkGeneration << 2) | 3;
                return null;
            }
        } else {
            n = chunks.get(LavaChunk.key(ncx, ncz));
            if (n == null) {
                c.missingNeighbour[slot] = (chunkGeneration << 2) | 1;
                return null;
            }
        }
        ensureFresh(n);
        c.neighbours[slot] = n;
        return n;
    }

    private void ensureFresh(LavaChunk c) {
        if (c.freshStamp == stamp) return;
        refresh(c);
    }

    /**
     * Re-reads the chunk's bed (world-model surface, uplift included) and standing water level if any column
     * under it was edited.
     */
    private void refresh(LavaChunk c) {
        c.freshStamp = stamp;
        WorldModel world = world();
        long edits = world.stacks().editCount();
        if (edits == c.seenEdits) return; // nothing in the world changed since
        c.seenEdits = edits;
        int x0 = c.cx << 4;
        int z0 = c.cz << 4;
        long version = world.stacks().versionSum(x0, z0, 16, 16);
        if (version == c.bedVersion) return;
        c.bedVersion = version;
        for (int i = 0; i < AREA; i++) {
            int x = x0 | (i & 15);
            int z = z0 | (i >> 4);
            double surface = world.surfaceZ(x, z);
            c.bed[i] = surface == surface ? surface : Double.NaN;
            c.waterLevel[i] = world.waterZ(x, z);
        }
    }

    /**
     * Re-reads one column's bed after this field edited it, and accounts for the edit in the chunk's
     * version sum ({@code versionBefore} = the column's version before the edit) so the next refresh
     * does not re-read the whole chunk because of the field's own deposits.
     */
    private void rereadBed(LavaChunk c, int i, int versionBefore) {
        WorldModel world = world();
        int x = c.worldX(i);
        int z = c.worldZ(i);
        double surface = world.surfaceZ(x, z);
        if (surface == surface) c.bed[i] = surface;
        if (c.bedVersion != Long.MIN_VALUE) c.bedVersion += world.version(x, z) - versionBefore;
    }

    /** Fed by an effusive source this step: vents stay open (no crust). */
    private boolean isSource(LavaChunk c, int i) {
        return c.sourceStamp[i] == stamp;
    }

    /** Standing water at least this deep (m) on a column quenches the lava there like the sea does. */
    static final double SUBMERGED_WATER_M = 0.1;

    private static boolean submerged(LavaChunk c, int i) {
        double w = c.waterLevel[i];
        return (w == w && w > c.bed[i]) || c.surfaceWater[i] >= SUBMERGED_WATER_M;
    }

    /**
     * Sequential, at the start of a step: standing water on the columns of every chunk with lava,
     * from the ground model; quiet chunks are cleared so stale values never decide anything.
     */
    private void refreshSurfaceWater() {
        if (ground == GroundCoupling.NONE) return;
        for (LavaChunk c : sortedChunks(chunks.values())) {
            if (c.isActive()) {
                int x0 = c.cx << 4;
                int z0 = c.cz << 4;
                for (int i = 0; i < AREA; i++) {
                    c.surfaceWater[i] = c.thickness[i] > 0 || c.crust[i] > 0
                            ? (float) ground.surfaceWaterDepthM(x0 + (i & 15), z0 + (i >> 4)) : 0f;
                }
                c.surfaceWaterSet = true;
            } else if (c.surfaceWaterSet) {
                Arrays.fill(c.surfaceWater, 0f);
                c.surfaceWaterSet = false;
            }
        }
    }

    /**
     * Sequential, after cooling: hands the step's heat to the ground model — the base conduction of
     * each 8×8 quadrant into the ground, and the quench heat of submerged columns as boiled-off
     * water ({@code E / (ρ_w (c_w ΔT + L_v))}).
     */
    private void flushGroundExchange(LavaChunk c) {
        if (ground == GroundCoupling.NONE) return;
        int x0 = c.cx << 4;
        int z0 = c.cz << 4;
        int per = 16 / heatCell; // heat cells per chunk side
        for (int q = 0; q < per * per; q++) {
            if (c.groundHeat[q] > 0) {
                int lx = (q % per) * heatCell + heatCell / 2;
                int lz = (q / per) * heatCell + heatCell / 2;
                ground.addGroundHeat(x0 + lx, z0 + lz, c.groundHeat[q]);
            }
            c.groundHeat[q] = 0;
        }
        double perM3 = 1000 * (WATER_HEAT_CAPACITY * Math.max(0, 100 - config.waterC()) + WATER_LATENT_HEAT);
        for (int i = 0; i < AREA; i++) {
            double e = c.boilHeat[i];
            if (e <= 0) continue;
            c.boilHeat[i] = 0;
            ground.removeSurfaceWater(x0 + (i & 15), z0 + (i >> 4), e / perM3);
        }
    }

    private static int index(int x, int z) {
        return ((z & 15) << 4) | (x & 15);
    }

    private static List<LavaChunk> sortedChunks(Collection<LavaChunk> chunks) {
        List<LavaChunk> list = new ArrayList<>(chunks);
        list.sort(BY_KEY);
        return list;
    }

    private static final class SolidStats {
        int cells;
        double volume;

        void clear() {
            cells = 0;
            volume = 0;
        }
    }

    // ── Persistence ──

    /** Schema of the per-chunk {@code cells} field. */
    private static final int CELLS_SCHEMA = 4;

    @Override
    public void saveState(StateWriter writer) {
        JsonObject out = writer.json();
        out.addProperty("format", STATE_FORMAT);
        out.addProperty("metersPerColumn", metersPerColumn());
        out.addProperty("emitted", emittedVolume);
        out.addProperty("solidified", solidifiedVolume);
        out.addProperty("lastMaxDiffusivity", lastMaxDiffusivity);
        out.addProperty("lastCoolPace", lastCoolPace);
        out.addProperty("eventSeconds", eventSeconds);
        JsonArray solidAccArray = new JsonArray();
        solidAccArray.add(solidAcc.cells);
        solidAccArray.add(solidAcc.volume);
        out.add("solidAcc", solidAccArray);
        JsonArray oceanArray = new JsonArray();
        for (Map.Entry<Long, OceanEntry> entry : oceanEntries.entrySet()) {
            OceanEntry e = entry.getValue();
            JsonArray a = new JsonArray();
            a.add(entry.getKey());
            a.add(e.inflowM3);
            a.add(e.heatJ);
            a.add(e.maxFluxM3s);
            a.add(e.maxPos);
            a.add(e.explosive);
            oceanArray.add(a);
        }
        out.add("oceanEntries", oceanArray);

        JsonArray sourceArray = new JsonArray();
        for (LavaSource s : sources.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", s.id());
            o.add("cells", positions(s.cells()));
            o.addProperty("rate", s.rateM3PerS());
            o.addProperty("temperature", s.temperatureC());
            o.addProperty("silica", s.silicaWt());
            o.addProperty("water", s.waterWt());
            o.addProperty("unit", s.unit());
            sourceArray.add(o);
        }
        out.add("sources", sourceArray);
        out.add("origins", positions(origins));

        JsonArray requested = new JsonArray();
        for (long key : requestedTerrain) requested.add(key);
        out.add("requestedTerrain", requested);

        JsonArray tubeArray = new JsonArray();
        for (Map.Entry<Long, double[]> e : tubeColumns.entrySet()) {
            JsonArray a = new JsonArray();
            a.add(e.getKey());
            a.add(e.getValue()[0]);
            a.add(e.getValue()[1]);
            tubeArray.add(a);
        }
        out.add("tubeColumns", tubeArray);

        StateWriter.Field cells = writer.field("cells", CELLS_SCHEMA);
        for (LavaChunk c : sortedChunks(chunks.values())) {
            if (!c.hasPersistentState()) continue;
            cells.put(c.cx, c.cz, new FieldChunk()
                    .doubles("thickness", c.thickness.clone())
                    .doubles("temperature", c.temperature.clone())
                    .doubles("silica", c.silica.clone())
                    .doubles("water", c.water.clone())
                    .doubles("crust", c.crust.clone())
                    .doubles("roofTop", c.roofTop.clone())
                    .bytes("crustKind", c.crustKind.clone())
                    .ints("unit", c.unit.clone())
                    .doubles("contact", c.contact.clone()));
        }
    }

    @Override
    public void loadState(StateReader reader) {
        JsonObject in = reader.json();
        chunks.clear();
        sources.clear();
        origins.clear();
        requestedTerrain.clear();
        tubeColumns.clear();
        int format = in.get("format").getAsInt();
        if (format != STATE_FORMAT) {
            throw new IllegalArgumentException("Unsupported lava state format: " + format);
        }
        double savedScale = in.get("metersPerColumn").getAsDouble();
        if (savedScale != metersPerColumn()) {
            throw new IllegalArgumentException(
                    "Lava state was saved at " + savedScale + " m per column, but the world is at " + metersPerColumn());
        }
        emittedVolume = in.get("emitted").getAsDouble();
        solidifiedVolume = in.get("solidified").getAsDouble();
        lastMaxDiffusivity = in.get("lastMaxDiffusivity").getAsDouble();
        lastCoolPace = in.has("lastCoolPace") ? in.get("lastCoolPace").getAsDouble() : 0;
        eventSeconds = in.get("eventSeconds").getAsDouble();
        solidAcc.clear();
        oceanEntries.clear();
        oceanGeneration++;
        {
            JsonArray acc = in.getAsJsonArray("solidAcc");
            solidAcc.cells = acc.get(0).getAsInt();
            solidAcc.volume = acc.get(1).getAsDouble();
            for (JsonElement e : in.getAsJsonArray("oceanEntries")) {
                JsonArray o = e.getAsJsonArray();
                OceanEntry entry = new OceanEntry();
                entry.inflowM3 = o.get(1).getAsDouble();
                entry.heatJ = o.get(2).getAsDouble();
                entry.maxFluxM3s = o.get(3).getAsDouble();
                entry.maxPos = o.get(4).getAsLong();
                entry.explosive = o.get(5).getAsBoolean();
                oceanEntries.put(o.get(0).getAsLong(), entry);
            }
        }

        for (JsonElement e : in.getAsJsonArray("sources")) {
            JsonObject o = e.getAsJsonObject();
            LavaSource s = new LavaSource(o.get("id").getAsString(), readPositions(o.getAsJsonArray("cells")),
                    o.get("rate").getAsDouble(), o.get("temperature").getAsDouble(), o.get("silica").getAsDouble(),
                    o.get("water").getAsDouble(), o.get("unit").getAsInt());
            sources.put(s.id(), s);
        }
        origins.addAll(readPositions(in.getAsJsonArray("origins")));
        for (JsonElement e : in.getAsJsonArray("requestedTerrain")) requestedTerrain.add(e.getAsLong());
        for (JsonElement e : in.getAsJsonArray("tubeColumns")) {
            JsonArray a = e.getAsJsonArray();
            tubeColumns.put(a.get(0).getAsLong(), new double[] {a.get(1).getAsDouble(), a.get(2).getAsDouble()});
        }

        StateReader.Field cells = reader.field("cells");
        if (cells.schemaVersion() != CELLS_SCHEMA) {
            throw new IllegalArgumentException("Unsupported lava cell schema: " + cells.schemaVersion());
        }
        for (StateReader.Entry entry : cells.chunks()) {
            FieldChunk f = entry.data();
            LavaChunk c = new LavaChunk(entry.chunkX(), entry.chunkZ());
            System.arraycopy(f.doubles("thickness"), 0, c.thickness, 0, AREA);
            System.arraycopy(f.doubles("temperature"), 0, c.temperature, 0, AREA);
            System.arraycopy(f.doubles("silica"), 0, c.silica, 0, AREA);
            System.arraycopy(f.doubles("water"), 0, c.water, 0, AREA);
            System.arraycopy(f.doubles("crust"), 0, c.crust, 0, AREA);
            System.arraycopy(f.doubles("roofTop"), 0, c.roofTop, 0, AREA);
            System.arraycopy(f.ints("unit"), 0, c.unit, 0, AREA);
            System.arraycopy(f.bytes("crustKind"), 0, c.crustKind, 0, AREA);
            System.arraycopy(f.doubles("contact"), 0, c.contact, 0, AREA);
            c.recount();
            chunks.put(c.key, c);
        }
    }

    private static JsonArray positions(List<ColumnIndex> list) {
        JsonArray array = new JsonArray();
        for (ColumnIndex p : list) {
            JsonArray a = new JsonArray();
            a.add(p.x());
            a.add(p.z());
            array.add(a);
        }
        return array;
    }

    private static List<ColumnIndex> readPositions(JsonArray array) {
        List<ColumnIndex> list = new ArrayList<>();
        for (JsonElement e : array) {
            JsonArray a = e.getAsJsonArray();
            list.add(new ColumnIndex(a.get(0).getAsInt(), a.get(1).getAsInt()));
        }
        return list;
    }
}
