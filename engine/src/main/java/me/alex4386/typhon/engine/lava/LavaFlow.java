package me.alex4386.typhon.engine.lava;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
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
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.Outbox;
import me.alex4386.typhon.engine.random.SimRandom;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.terrain.TerrainChunkView;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.world.BlockState;
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
 * <p>The automaton runs in real units: a column is {@code L × L} m ({@link #metersPerBlock()}), its
 * molten core is {@code h} real metres thick, and one block of rock is {@code L} m thick, so a real
 * erupted volume {@code V} occupies {@code V / L³} blocks and flows reach real runout distances.
 * Every column holds a molten core with temperature, SiO₂ and H₂O on top of the world-model ground
 * surface ({@link WorldModel#surfaceZ} + uplift, real metres — so it flows over ash, ignimbrite and
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
 *             whole: radiation {@code εσ(T⁴ − T_a⁴)} or, submerged, {@code h_w(T − T_w)}, plus
 *             basal conduction, over {@code ρ c_eff h} with latent heat between liquidus and
 *             solidus.
 *         <li><i>Quiet columns</i> (at least {@link LavaConfig#crustMinThickness()} thick and slower
 *             than {@link LavaConfig#crustDisruptionVelocity()}) grow a crust instead (Stefan
 *             problem): the surface loss {@code (T − T_a) / (h_c/k_c + 1/h_rad)} freezes melt onto
 *             the crust base at {@code ρ (L + c (T − T_sol))} per m³, while the insulated core only
 *             loses heat through its base. A quiet pond's crust grows as ≈ √t (≈0.45 m after a day
 *             at k_c = 1 W/m·K), and insulated flows stay hot and travel farther.
 *         <li>Melt pushing up into its crust lifts it (inflation); a faster flow, a vent or water
 *             tears the crust up and re-mixes it.
 *       </ul>
 *       Below the solidus the column solidifies: its exact thickness is deposited into the world
 *       model as a {@link DepositType#LAVA} layer of its unit (rock from {@link
 *       LavaPalette#rockMaterial}), and whole blocks of it are shown via {@link LavaPalette}. Lava
 *       quenched in water sheds part of its volume as {@link DepositType#HYALOCLASTITE} onto the
 *       steepest lower submerged neighbour, so sustained ocean entry builds a delta seaward.
 *   <li><b>Tubes</b>: when the melt under a crust drains away (supply stopped, flow moved on), a
 *       roof at least {@link LavaConfig#tubeMinRoofThickness()} thick over at least one block of
 *       void stays standing: the world model gets a cavity ({@link DepositType#CAVITY}) under a
 *       {@link DepositType#TUBE_ROOF} layer, and {@link #tubes()} lists them. Thinner roofs collapse
 *       into the column.
 *   <li><b>Rendering</b>: molten columns are shown as lava (or a magma-block skin once crusted or
 *       cooled past the crust temperature), drained voids as air and thick crust as roof rock; only
 *       columns whose visible state changed emit block changes.
 * </ol>
 *
 * <p>Columns in chunks the host has not sent terrain for act as walls and are requested with a
 * {@link LavaEvents.TerrainNeeded} event.
 *
 * <p>Hosts must not report engine-rendered lava, magma-block skin or roof rock above
 * {@link TerrainModel} ground as ground in later terrain snapshots, or the melt would be counted
 * twice; tube voids are listed by {@link #tubes()}.
 */
public final class LavaFlow implements Subsystem {
    public static final String ID = "lava";

    private static final int STATE_FORMAT = 5;
    private static final double SIGMA = 5.670374419e-8;
    private static final double G = 9.81;
    private static final double KELVIN = 273.15;
    private static final double GAP_EPS = 1e-6;
    private static final double WATER_HEAT_CAPACITY = 4186;
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
    private static final int UNKNOWN = LavaChunk.UNKNOWN;
    private static final Comparator<LavaChunk> BY_KEY = Comparator.comparingLong(c -> c.key);

    private final TerrainModel terrain;
    private final LavaConfig config;
    private final LavaRheology rheology;
    private double metersPerBlock;
    private boolean scaleLocked;

    private final Map<Long, LavaChunk> chunks = new HashMap<>();
    private final Map<String, LavaSource> sources = new LinkedHashMap<>();
    private final List<BlockPos> origins = new ArrayList<>();
    private final TreeSet<Long> requestedTerrain = new TreeSet<>();
    /** Columns holding a lava tube cavity (packed x/z); the cavities themselves live in the world model. */
    private final TreeMap<Long, int[]> tubeColumns = new TreeMap<>(); // shown block range {bottomY, topY}
    private double emittedVolume;
    private double solidifiedVolume;

    // per-step scratch
    private final double[] flux = new double[DIRS];
    private final TreeMap<Long, OceanEntry> oceanEntries = new TreeMap<>();
    private long oceanGeneration; // bumped whenever oceanEntries is cleared (invalidates chunk caches)
    private final SolidStats solidAcc = new SolidStats();
    private final TreeSet<Long> neededTerrain = new TreeSet<>();
    private long stamp = Long.MIN_VALUE + 1;
    private double currentTime;

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
        this.metersPerBlock = config.metersPerBlock();
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

    /** Real metres per block: column width and the thickness of one block of rock. */
    public double metersPerBlock() {
        return metersPerBlock;
    }

    /**
     * Sets the grid scale from a volcano's {@code VolcanoScaling}. The lava field is shared by every
     * volcano in an engine, so all of them must use the same scale: the first call fixes it and a
     * different value afterwards is rejected. Must be called before any lava exists.
     */
    public void setMetersPerBlock(double metersPerBlock) {
        if (!(metersPerBlock > 0)) throw new IllegalArgumentException("metersPerBlock must be > 0");
        if (metersPerBlock == this.metersPerBlock) {
            scaleLocked = true;
            return;
        }
        if (scaleLocked) {
            throw new IllegalArgumentException("Lava field is already scaled at " + this.metersPerBlock
                    + " m/block; volcanoes sharing it cannot use " + metersPerBlock);
        }
        if (emittedVolume > 0 || !chunks.isEmpty()) {
            throw new IllegalStateException("Cannot rescale a lava field that already holds lava");
        }
        this.metersPerBlock = metersPerBlock;
        this.scaleLocked = true;
    }

    /** Converts a real volume (m³) to blocks at this field's scale. */
    public double toBlocks(double volumeM3) {
        return volumeM3 / (metersPerBlock * metersPerBlock * metersPerBlock);
    }

    private double area() {
        return metersPerBlock * metersPerBlock;
    }

    private WorldModel world() {
        return terrain.world();
    }

    /** Rock above the ground block's top that is not shown as a block (real m, ≥ 0). */
    private double partialAboveBlock(LavaChunk c, int i) {
        return Math.max(0, c.bed[i] - (c.ground[i] + 1) * metersPerBlock);
    }

    public void addSource(LavaSource source) {
        sources.put(source.id(), source);
        for (BlockPos cell : source.cells()) {
            BlockPos origin = new BlockPos(cell.x(), 0, cell.z());
            if (!origins.contains(origin)) origins.add(origin);
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
     * Injects {@code volumeM3} real m³ of lava into a column (e.g. a molten bomb or a lava-dome
     * collapse). Returns false if the column's terrain is unknown.
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
        if (c.ground[i] == UNKNOWN) return false;
        double h0 = c.thickness[i];
        double added = volumeM3 / area();
        double h = h0 + added;
        if (added >= h0) c.unit[i] = unit;
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

    /**
     * Lava rock laid down in the column that is not yet shown as a whole block (real m). The rock
     * itself is already in the world model.
     */
    public double partialSolid(int x, int z) {
        LavaChunk c = chunks.get(LavaChunk.key(x >> 4, z >> 4));
        return c == null ? 0 : c.solid[index(x, z)];
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
     * Hollow lava-tube voids left by drained flows, in column order, as the block ranges shown to
     * hosts. The cavities themselves are {@link DepositType#CAVITY} layers in the world model; a
     * column whose cavity was later filled or collapsed by other edits drops out of the list.
     */
    public List<LavaTube> tubes() {
        List<LavaTube> list = new ArrayList<>();
        WorldModel world = world();
        for (Map.Entry<Long, int[]> e : tubeColumns.entrySet()) {
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

    @Override
    public void step(StepContext context) {
        double now = context.time();
        currentTime = now;
        stamp = context.step();
        double dt = context.dtSeconds() * config.timeScale();
        Outbox outbox = context.outbox();
        neededTerrain.clear();

        // 0. effusion
        for (LavaSource source : sources.values()) {
            if (source.rateM3PerS() <= 0) continue;
            double perCell = source.rateM3PerS() * dt / source.cells().size();
            for (BlockPos cell : source.cells()) {
                if (!addLava(cell.x(), cell.z(), perCell, source.temperatureC(), source.silicaWt(), source.waterWt(),
                        source.unit())) {
                    neededTerrain.add(LavaChunk.key(cell.x() >> 4, cell.z() >> 4));
                    continue;
                }
                chunks.get(LavaChunk.key(cell.x() >> 4, cell.z() >> 4)).sourceStamp[index(cell.x(), cell.z())] = stamp;
            }
        }

        // 1. flux
        List<LavaChunk> active = new ArrayList<>();
        for (LavaChunk c : chunks.values()) if (c.lavaCells > 0) active.add(c);
        for (LavaChunk c : active) {
            ensureFresh(c);
            computeFlux(c, dt);
        }

        // 2. gather + swap
        List<LavaChunk> update = new ArrayList<>();
        for (LavaChunk c : chunks.values()) if (c.isActive() || c.touchedStamp == stamp) update.add(c);
        update.sort(BY_KEY);
        for (LavaChunk c : update) {
            ensureFresh(c);
            gather(c, dt);
        }
        for (LavaChunk c : update) {
            c.swapBuffers();
            c.recount();
        }

        // 3. cooling, crust, tubes & solidification
        SolidStats stats = solidAcc;
        List<LavaTube> formed = new ArrayList<>();
        for (LavaChunk c : update) {
            if (c.isActive()) cool(c, dt, context.random(), outbox, stats, formed);
        }
        for (LavaChunk c : update) c.recount();

        // 4. rendering
        for (LavaChunk c : update) render(c, outbox);

        // 5. events & cleanup
        double interval = config.eventPeriodSeconds();
        if (context.crossed(interval)) {
            if (stats.cells > 0) {
                outbox.emit(new LavaEvents.LavaSolidified(now, interval, stats.cells, stats.volume, stats.blocks));
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

    private void computeFlux(LavaChunk c, double dt) {
        c.fluxStamp = stamp;
        Arrays.fill(c.outflow, 0);
        Arrays.fill(c.speed, 0);
        double rhoG = config.densityKgM3() * G;
        double minFlow = config.minFlowThickness();
        double relaxation = config.relaxation();
        double cell = metersPerBlock;
        double perDischarge = FACE * dt / cell; // thickness moved per unit discharge: q·(FACE·L)·dt / L²

        for (int i = 0; i < AREA; i++) {
            double h = c.thickness[i];
            if (h < minFlow || c.ground[i] == UNKNOWN) continue;
            double head = c.bed[i] + h;
            int lx = i & 15;
            int lz = i >> 4;

            double eta = Double.NaN;
            double tau = 0;
            double total = 0;
            double capScale = 1;
            double discharge = 0; // physical q·width/L summed over directions (m²/s), before numerical caps
            for (int d = 0; d < DIRS; d++) {
                flux[d] = 0;
                int nx = lx + DX[d];
                int nz = lz + DZ[d];
                LavaChunk nc = c;
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                    nc = neighbourAt(c, nx, nz, true);
                    if (nc == null) continue;
                }
                int j = ((nz & 15) << 4) | (nx & 15);
                if (nc.ground[j] == UNKNOWN) continue;
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
                    neighbourAt(c, nx, nz, false).touchedStamp = stamp;
                }
            }
        }
    }

    private void gather(LavaChunk c, double dt) {
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
            // the cell's unit follows its largest contribution (own melt first, then inflows in order)
            int unit = c.unit[i];
            double unitVolume = keep;

            int lx = i & 15;
            int lz = i >> 4;
            double inflow = 0;
            for (int d = 0; d < DIRS; d++) {
                int nx = lx + DX[d];
                int nz = lz + DZ[d];
                LavaChunk nc = c;
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                    nc = neighbourAt(c, nx, nz, false);
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
                if (in > unitVolume) {
                    unitVolume = in;
                    unit = nc.unit[k];
                }
            }
            volume += inflow;

            c.nextThickness[i] = volume;
            c.nextUnit[i] = unit;
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

    /** Lava flowing into a submerged column this step. */
    private void recordWaterInflow(LavaChunk c, int i, double volumeM3, double dt) {
        OceanEntry e = oceanEntry(c);
        e.inflowM3 += volumeM3;
        double flux = dt > 0 ? volumeM3 / dt : 0;
        if (flux > e.maxFluxM3s) {
            e.maxFluxM3s = flux;
            e.maxPos = BlockPos.pack(c.worldX(i), c.ground[i] + 1, c.worldZ(i));
        }
        if (flux >= config.littoralExplosionFluxM3s()) e.explosive = true;
    }

    /** Heat a submerged column released into the water this step. */
    private void recordWaterHeat(LavaChunk c, double heatJ) {
        if (heatJ > 0) oceanEntry(c).heatJ += heatJ;
    }

    /** One {@link LavaEvents.LavaOceanEntry} per zone with molten lava in water, then resets. */
    private void emitOceanEntries(double now, double interval, Outbox outbox) {
        if (oceanEntries.isEmpty()) return;
        double seconds = interval * config.timeScale();
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
                        largest = BlockPos.pack(c.worldX(i), c.ground[i] + 1, c.worldZ(i));
                    }
                }
            }
            if (columns == 0 && e.inflowM3 < minVolume) continue; // only quench films: not worth an event
            if (emitted++ >= config.maxWaterEventsPerStep()) break;
            double powerW = seconds > 0 ? e.heatJ / seconds : 0;
            double steam = powerW / (WATER_HEAT_CAPACITY * Math.max(0, 100 - config.waterC()) + WATER_LATENT_HEAT);
            BlockPos pos = BlockPos.unpack(e.maxFluxM3s >= 0 ? e.maxPos : largest);
            outbox.emit(new LavaEvents.LavaOceanEntry(now, interval, pos, columns, molten,
                    seconds > 0 ? e.inflowM3 / seconds : 0, powerW / 1e6, steam, e.explosive));
        }
        oceanEntries.clear();
        oceanGeneration++;
    }

    private void cool(LavaChunk c, double dt, SimRandom random, Outbox outbox, SolidStats stats, List<LavaTube> formed) {
        double rho = config.densityKgM3();
        double cs = config.coolingScale();
        double ambient = config.ambientC();
        double ambientK4 = Math.pow(ambient + KELVIN, 4);
        for (int i = 0; i < AREA; i++) {
            double h = c.thickness[i];
            double hc = c.crust[i];
            if (h <= 0 && hc <= 0) continue;
            boolean submerged = submerged(c, i);

            if (hc > 0) {
                double meltTop = c.bed[i] + h;
                double crustBase = c.roofTop[i] - hc;
                if (meltTop > crustBase) c.roofTop[i] += meltTop - crustBase; // inflation lifts the roof
                // A young crust is torn up by fast flow; a roof thick enough to span the flow is
                // anchored to its levees and lets the melt run beneath it (tube flow).
                boolean anchored = hc >= config.tubeMinRoofThickness();
                boolean torn = submerged || isSource(c, i)
                        || (!anchored && localSpeed(c, i) > config.crustDisruptionVelocity());
                if (torn && h > 0) {
                    remelt(c, i);
                    h = c.thickness[i];
                    hc = 0;
                }
            }
            if (h <= 0) { // the melt flowed away from under its crust
                resolveDrained(c, i, random, outbox, stats, formed);
                continue;
            }

            double t = c.temperature[i];
            double si = c.silica[i];
            double liquidus = rheology.liquidusC(si);
            double solidus = rheology.solidusC(si);
            double meltTop = c.bed[i] + h;
            double gap = hc > 0 ? (c.roofTop[i] - hc) - meltTop : 0;
            boolean underVoid = hc > 0 && gap > GAP_EPS;

            double tK = t + KELVIN;
            double qTop;
            if (submerged) {
                qTop = config.waterHeatTransferWM2K() * (t - config.waterC());
            } else if (underVoid) {
                qTop = 0; // melt under a drained roof faces a hot void, not the sky
            } else {
                double radiative = config.emissivity() * SIGMA * (tK * tK * tK * tK - ambientK4);
                qTop = hc > 0 && t > ambient
                        ? (t - ambient) / (hc / config.crustConductivityWMK() + (t - ambient) / radiative)
                        : radiative;
            }
            double qBase = config.groundConductivityWMK() * (t - ambient) / config.groundBoundaryLayerM();
            double cEff = config.specificHeatJKgK();
            if (t < liquidus && t > solidus) cEff += config.latentHeatJKg() / (liquidus - solidus);

            boolean anchored = hc >= config.tubeMinRoofThickness();
            boolean quiet = config.crustEnabled() && !submerged && !underVoid && h + hc >= config.crustMinThickness()
                    && !isSource(c, i) && (anchored || localSpeed(c, i) <= config.crustDisruptionVelocity());

            double rate; // physical core cooling rate, K/s
            if (quiet && t > solidus && qTop > 0) {
                // Stefan: the surface loss freezes melt onto the crust base; the core cools through its base.
                double freezeHeat = rho * (config.latentHeatJKg() + config.specificHeatJKgK() * (t - solidus));
                double grow = Math.min(h, qTop * cs * dt / freezeHeat);
                if (hc <= 0) {
                    c.roofTop[i] = meltTop;
                    c.crustKind[i] = LavaPalette.crustKind(si);
                }
                h -= grow;
                hc += grow;
                c.thickness[i] = h;
                c.crust[i] = hc;
                rate = qBase / (rho * cEff * Math.max(h, 0.01));
            } else {
                rate = (qTop + qBase) / (rho * cEff * Math.max(h, 0.01));
            }
            double floor = submerged ? config.waterC() : ambient;
            double cooled = Math.max(t - rate * cs * dt, floor);
            if (submerged) recordWaterHeat(c, rho * cEff * h * (t - cooled) * area());
            c.temperature[i] = cooled;

            if (h <= 0) {
                // the whole melt froze into the crust
                solidifyColumn(c, i, si, submerged, false, h + hc >= config.columnarMinThickness(), random, outbox, stats);
            } else if (underVoid && (h < config.tubeDrainThickness() || c.temperature[i] <= solidus)) {
                resolveDrained(c, i, random, outbox, stats, formed);
            } else if (c.temperature[i] <= solidus) {
                boolean quenched = submerged || rate > config.quenchRateKPerS();
                boolean columnar = !quenched && h + hc >= config.columnarMinThickness();
                solidifyColumn(c, i, si, submerged, quenched, columnar, random, outbox, stats);
            }
        }
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
                nc = neighbourAt(c, nx, nz, false);
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
        double silica = film > 0 ? c.silica[i] : LavaPalette.crustSilica(kind);
        boolean submerged = submerged(c, i);
        clearMelt(c, i);
        stats.volume += film * area();
        solidifiedVolume += film * area();
        depositRock(c, i, film, LavaPalette.rockMaterial(silica, submerged, false), c.unit[i], 0);
        raiseGround(c, i, silica, submerged, false, false, null, random, outbox, stats);

        // A roof that is strong enough to stand is shown as at least one block.
        double l = metersPerBlock;
        int roofEnd = (int) Math.ceil(c.roofTop[i] / l - 0.5);
        int roofBottom = Math.min((int) Math.ceil((c.roofTop[i] - hc) / l - 0.5), roofEnd - 1);
        int voidBottom = c.ground[i] + 1;
        double gap = (c.roofTop[i] - hc) - c.bed[i];
        if (hc >= config.tubeMinRoofThickness() && roofBottom > voidBottom && gap > GAP_EPS) {
            formTube(c, i, kind, voidBottom, roofBottom, roofEnd, outbox, formed);
        } else {
            // roof too thin or no void: it caves in onto the floor
            c.crust[i] = 0;
            c.roofTop[i] = 0;
            solidifiedVolume += hc * area();
            stats.volume += hc * area();
            double crustSilica = LavaPalette.crustSilica(kind);
            depositRock(c, i, hc, LavaPalette.rockMaterial(crustSilica, submerged, false), c.unit[i], 0);
            raiseGround(c, i, crustSilica, submerged, false, false, null, random, outbox, stats);
        }
        stats.cells++;
    }

    /**
     * Converts a drained column into a roofed void: the world model gets the cavity and the roof as
     * layers of the flow's eruption, and the roof top becomes the surface.
     */
    private void formTube(LavaChunk c, int i, byte kind, int voidBottom, int roofBottom, int roofEnd, Outbox outbox,
            List<LavaTube> formed) {
        int x = c.worldX(i);
        int z = c.worldZ(i);
        BlockState roof = LavaPalette.roof(kind);
        int oldBottom = c.renderBottom[i];
        int oldEnd = oldBottom + c.renderCount(i);
        for (int y = Math.min(voidBottom, oldBottom); y < Math.max(roofEnd, oldEnd); y++) {
            if (y < voidBottom) continue;
            BlockState old = renderedState(c, i, y);
            BlockState next;
            if (y < roofBottom) {
                next = BlockState.AIR;
            } else if (y < roofEnd) {
                next = roof;
            } else {
                next = c.waterY[i] != TerrainColumn.NO_WATER && y <= c.waterY[i] ? LavaPalette.WATER : LavaPalette.AIR;
            }
            BlockPos pos = new BlockPos(x, y, z);
            if (old == null) {
                if (!next.equals(BlockState.AIR)) outbox.setBlock(BlockChange.set(pos, next));
            } else if (!old.equals(next)) {
                outbox.setBlock(BlockChange.replace(pos, old.id(), next));
            }
        }

        double hc = c.crust[i];
        double cavity = Math.max(0, (c.roofTop[i] - hc) - c.bed[i]);
        WorldModel world = world();
        int lavaUnit = c.unit[i];
        if (cavity > 0) {
            world.deposit(x, z, cavity, MaterialTable.VOID,
                    Provenance.sibling(world, lavaUnit, DepositType.CAVITY, currentTime), 0, 1, 0);
        }
        Material roofRock = LavaPalette.rockMaterial(LavaPalette.crustSilica(kind), false, false);
        world.deposit(x, z, hc, roofRock, Provenance.sibling(world, lavaUnit, DepositType.TUBE_ROOF, currentTime),
                LayerFlags.FRACTURED, roofRock.porosity(), 1);
        rereadBed(c, i);
        tubeColumns.put(columnKey(x, z), new int[] {voidBottom, roofBottom - 1});

        solidifiedVolume += hc * area();
        c.crust[i] = 0;
        c.roofTop[i] = 0;
        c.solid[i] = 0;
        int surface = roofEnd - 1;
        c.ground[i] = surface;
        terrain.updateBlockCache(x, z, surface, roof.id());
        setRender(c, i, roofEnd, 0, 0, 0, (byte) 0, (byte) 0);
        formed.add(new LavaTube(x, z, voidBottom, roofBottom - 1));
    }

    private void clearMelt(LavaChunk c, int i) {
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
        depositRock(c, i, total, LavaPalette.rockMaterial(silica, submerged, quenched), c.unit[i],
                columnar ? LayerFlags.FRACTURED : 0);
        raiseGround(c, i, silica, submerged, quenched, columnar, null, random, outbox, stats);
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
        double bestTop = surface - 0.5 * metersPerBlock;
        for (int d = 0; d < DIRS; d++) {
            int nx = lx + DX[d];
            int nz = lz + DZ[d];
            LavaChunk nc = c;
            if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                nc = neighbourAt(c, nx, nz, true);
                if (nc == null) continue;
            }
            int j = ((nz & 15) << 4) | (nx & 15);
            if (nc.ground[j] == UNKNOWN || !submerged(nc, j)) continue;
            double top = nc.bed[j] + nc.thickness[j];
            if (top < bestTop) {
                bestTop = top;
                best = nc;
                bestIndex = j;
            }
        }
        if (best == null) return 0;
        int unit = Provenance.sibling(world(), c.unit[i], DepositType.HYALOCLASTITE, currentTime);
        depositRock(best, bestIndex, volume, MaterialTable.HYALOCLASTITE, unit, 0);
        raiseGround(best, bestIndex, 0, true, true, false, LavaPalette.HYALOCLASTITE, random, outbox, stats);
        return volume;
    }

    /**
     * Lays {@code thickness} m of solid rock into the world model (the exact amount, as a layer of
     * {@code unit}) and counts it towards the column's next whole block.
     */
    private void depositRock(LavaChunk c, int i, double thickness, Material material, int unit, int flags) {
        if (!(thickness > 0)) return;
        int x = c.worldX(i);
        int z = c.worldZ(i);
        world().deposit(x, z, thickness, material, unit, flags, material.porosity(), 1.0);
        rereadBed(c, i); // the stacks hold elevations as floats; stay identical to a fresh read (restores)
        c.solid[i] += thickness;
    }

    /**
     * Shows whole blocks ({@code L} m each) of a column's not-yet-shown rock: places the block and
     * moves the terrain's block cache up (the world model already holds the rock).
     */
    private void raiseGround(LavaChunk c, int i, double silica, boolean submerged, boolean quenched, boolean columnar,
            BlockState forced, SimRandom random, Outbox outbox, SolidStats stats) {
        int x = c.worldX(i);
        int z = c.worldZ(i);
        double s = c.solid[i];
        double l = metersPerBlock;
        while (s >= l * (1 - 1e-9)) {
            s -= l;
            int y = c.ground[i] + 1;
            c.ground[i] = y;
            BlockState rock = forced != null ? forced : LavaPalette.rock(silica, submerged, quenched, columnar, random);
            terrain.updateBlockCache(x, z, y, rock.id());
            outbox.setBlock(BlockChange.set(new BlockPos(x, y, z), rock));
            stats.blocks++;
        }
        c.solid[i] = Math.max(0, s);
    }

    // ── Rendering ──

    private void render(LavaChunk c, Outbox outbox) {
        double l = metersPerBlock;
        double minThickness = config.renderMinThickness() * l;
        for (int i = 0; i < AREA; i++) {
            int g = c.ground[i];
            if (g == UNKNOWN) continue;
            double h = c.thickness[i];
            double hc = c.crust[i];
            int bottom = g + 1;
            int melt = 0;
            int gap = 0;
            int roof = 0;
            byte top = 0;
            byte roofKind = 0;
            double partial = partialAboveBlock(c, i);
            if (h >= minThickness) {
                melt = Math.max(1, (int) Math.ceil((partial + h) / l - 1e-9));
            }
            if (hc > 0) {
                roofKind = c.crustKind[i];
                int roofBottom = (int) Math.ceil((c.roofTop[i] - hc) / l - 0.5);
                int roofEnd = (int) Math.ceil(c.roofTop[i] / l - 0.5);
                if (roofEnd > roofBottom) {
                    roofBottom = Math.max(roofBottom, bottom);
                    melt = Math.min(melt, roofBottom - bottom);
                    gap = roofBottom - bottom - melt;
                    roof = Math.max(0, roofEnd - roofBottom);
                }
            }
            if (melt > 0) {
                if (gap > 0 || roof > 0) {
                    top = (byte) (1 << 3); // full lava block under a gap or roof
                } else {
                    double total = (partial + h) / l;
                    double frac = total - (melt - 1);
                    double si = c.silica[i];
                    double crustT = rheology.solidusC(si) + 0.3 * (rheology.liquidusC(si) - rheology.solidusC(si));
                    if (c.temperature[i] >= crustT && hc < config.crustRenderThickness()) {
                        int level = (int) Math.max(0, Math.min(7, Math.round((1 - frac) * 7)));
                        top = (byte) ((1 << 3) | level);
                    } else {
                        top = (byte) (2 << 3);
                    }
                }
            }

            int oldBottom = c.renderBottom[i];
            int oldCount = c.renderCount(i);
            if (oldBottom == bottom && c.renderMelt[i] == melt && c.renderGap[i] == gap && c.renderRoof[i] == roof
                    && c.renderTop[i] == top && c.renderRoofKind[i] == roofKind) {
                continue;
            }
            int newCount = melt + gap + roof;
            if (oldCount == 0 && newCount == 0) {
                c.renderBottom[i] = bottom;
                continue;
            }

            int x = c.worldX(i);
            int z = c.worldZ(i);
            int oldEnd = oldBottom + oldCount;
            int newEnd = bottom + newCount;
            for (int y = oldBottom; y < oldEnd; y++) {
                if (y <= g) continue; // overwritten by rock
                BlockState old = renderedState(c, i, y);
                BlockState next = y >= bottom && y < newEnd ? layoutState(bottom, melt, gap, top, roofKind, y) : null;
                if (next == null) {
                    next = c.waterY[i] != TerrainColumn.NO_WATER && y <= c.waterY[i] ? LavaPalette.WATER : LavaPalette.AIR;
                }
                if (!next.equals(old)) outbox.setBlock(BlockChange.replace(new BlockPos(x, y, z), old.id(), next));
            }
            for (int y = bottom; y < newEnd; y++) {
                if (y >= oldBottom && y < oldEnd) continue;
                BlockState next = layoutState(bottom, melt, gap, top, roofKind, y);
                if (next.equals(BlockState.AIR)) continue;
                outbox.setBlock(BlockChange.set(new BlockPos(x, y, z), next));
            }
            setRender(c, i, bottom, melt, gap, roof, top, roofKind);
        }
    }

    private static void setRender(LavaChunk c, int i, int bottom, int melt, int gap, int roof, byte top, byte roofKind) {
        c.renderBottom[i] = bottom;
        c.renderMelt[i] = (short) melt;
        c.renderGap[i] = (short) gap;
        c.renderRoof[i] = (short) roof;
        c.renderTop[i] = top;
        c.renderRoofKind[i] = roofKind;
    }

    /** What the last render placed at {@code y}, or {@code null} outside the rendered range. */
    private static BlockState renderedState(LavaChunk c, int i, int y) {
        int bottom = c.renderBottom[i];
        if (y < bottom || y >= bottom + c.renderCount(i)) return null;
        return layoutState(bottom, c.renderMelt[i], c.renderGap[i], c.renderTop[i], c.renderRoofKind[i], y);
    }

    private static BlockState layoutState(int bottom, int melt, int gap, byte top, byte roofKind, int y) {
        int k = y - bottom;
        if (k < melt) {
            if (k != melt - 1) return LavaPalette.lava(0);
            return (top >> 3) == 2 ? LavaPalette.MAGMA_CRUST : LavaPalette.lava(top & 7);
        }
        if (k < melt + gap) return BlockState.AIR;
        return LavaPalette.roof(roofKind);
    }

    private void emitFront(double now, List<LavaChunk> update, Outbox outbox) {
        if (origins.isEmpty()) return;
        double best = -1;
        BlockPos front = null;
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
                for (BlockPos o : origins) {
                    double dx = x - o.x();
                    double dz = z - o.z();
                    nearest = Math.min(nearest, dx * dx + dz * dz);
                }
                if (nearest > best) {
                    best = nearest;
                    front = new BlockPos(x, c.ground[i] + 1, z);
                }
            }
        }
        if (front != null) {
            outbox.emit(new LavaEvents.LavaFlowFront(now, front, Math.sqrt(best) * metersPerBlock, cells, volume));
        }
    }

    // ── Chunk management ──

    private LavaChunk chunkFor(int cx, int cz) {
        long key = LavaChunk.key(cx, cz);
        LavaChunk c = chunks.get(key);
        if (c != null) return c;
        if (!terrain.isKnown(cx << 4, cz << 4)) return null;
        requestedTerrain.remove(key);
        c = new LavaChunk(cx, cz);
        chunks.put(key, c);
        return c;
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
            c.neighbourStamp = stamp;
        }
        int slot = (oz + 1) * 3 + (ox + 1);
        LavaChunk n = c.neighbours[slot];
        if (n != null) return n;
        int ncx = c.cx + ox;
        int ncz = c.cz + oz;
        if (create) {
            n = chunkFor(ncx, ncz);
            if (n == null) {
                neededTerrain.add(LavaChunk.key(ncx, ncz));
                return null;
            }
        } else {
            n = chunks.get(LavaChunk.key(ncx, ncz));
            if (n == null) return null;
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
     * Re-reads the chunk's terrain (block cache: ground and water) if the terrain chunk was replaced
     * or modified, and its bed (world-model surface + uplift) if any column under it was edited.
     */
    private void refresh(LavaChunk c) {
        c.freshStamp = stamp;
        TerrainChunkView view = terrain.chunkView(c.cx, c.cz);
        if (view == null) {
            if (c.terrainView != null || c.ground[0] != UNKNOWN) {
                Arrays.fill(c.ground, UNKNOWN);
                Arrays.fill(c.waterY, TerrainColumn.NO_WATER);
                Arrays.fill(c.bed, Double.NaN);
            }
            c.terrainView = null;
            c.bedVersion = Long.MIN_VALUE;
            return;
        }
        boolean terrainChanged = view != c.terrainView || view.version() != c.terrainVersion;
        if (terrainChanged) {
            view.copyGroundY(c.ground, 0);
            view.copyWaterY(c.waterY, 0);
            c.terrainView = view;
            c.terrainVersion = view.version();
        }
        WorldModel world = world();
        int x0 = c.cx << 4;
        int z0 = c.cz << 4;
        long version = world.stacks().versionSum(x0, z0, 16, 16);
        if (!terrainChanged && version == c.bedVersion) return;
        c.bedVersion = version;
        for (int i = 0; i < AREA; i++) {
            int x = x0 | (i & 15);
            int z = z0 | (i >> 4);
            double surface = world.surfaceZ(x, z);
            c.bed[i] = surface == surface
                    ? surface + world.uplift(x, z)
                    : c.ground[i] == UNKNOWN ? Double.NaN : (c.ground[i] + 1) * metersPerBlock;
        }
    }

    private void rereadBed(LavaChunk c, int i) {
        WorldModel world = world();
        int x = c.worldX(i);
        int z = c.worldZ(i);
        double surface = world.surfaceZ(x, z);
        if (surface == surface) c.bed[i] = surface + world.uplift(x, z);
    }

    /** Fed by an effusive source this step: vents stay open (no crust). */
    private boolean isSource(LavaChunk c, int i) {
        return c.sourceStamp[i] == stamp;
    }

    private static boolean submerged(LavaChunk c, int i) {
        int w = c.waterY[i];
        return w != TerrainColumn.NO_WATER && w > c.ground[i];
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
        int blocks;
        double volume;

        void clear() {
            cells = 0;
            blocks = 0;
            volume = 0;
        }
    }

    // ── Persistence ──

    /** Schema of the per-chunk {@code cells} field. */
    private static final int CELLS_SCHEMA = 2;

    @Override
    public void saveState(StateWriter writer) {
        JsonObject out = writer.json();
        out.addProperty("format", STATE_FORMAT);
        out.addProperty("metersPerBlock", metersPerBlock);
        out.addProperty("emitted", emittedVolume);
        out.addProperty("solidified", solidifiedVolume);
        JsonArray solidAccArray = new JsonArray();
        solidAccArray.add(solidAcc.cells);
        solidAccArray.add(solidAcc.blocks);
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
        for (Map.Entry<Long, int[]> e : tubeColumns.entrySet()) {
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
                    .doubles("solid", c.solid.clone())
                    .doubles("crust", c.crust.clone())
                    .doubles("roofTop", c.roofTop.clone())
                    .ints("renderBottom", c.renderBottom.clone())
                    .ints("renderMelt", widen(c.renderMelt))
                    .ints("renderGap", widen(c.renderGap))
                    .ints("renderRoof", widen(c.renderRoof))
                    .bytes("renderTop", c.renderTop.clone())
                    .bytes("renderRoofKind", c.renderRoofKind.clone())
                    .bytes("crustKind", c.crustKind.clone())
                    .ints("unit", c.unit.clone()));
        }
    }

    private static int[] widen(short[] values) {
        int[] out = new int[values.length];
        for (int i = 0; i < values.length; i++) out[i] = values[i];
        return out;
    }

    private static void narrow(int[] values, short[] target) {
        for (int i = 0; i < target.length; i++) target[i] = (short) values[i];
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
        double savedScale = in.get("metersPerBlock").getAsDouble();
        if (savedScale != metersPerBlock) {
            throw new IllegalArgumentException(
                    "Lava state was saved at " + savedScale + " m/block, but the field is at " + metersPerBlock);
        }
        emittedVolume = in.get("emitted").getAsDouble();
        solidifiedVolume = in.get("solidified").getAsDouble();
        solidAcc.clear();
        oceanEntries.clear();
        oceanGeneration++;
        {
            JsonArray acc = in.getAsJsonArray("solidAcc");
            solidAcc.cells = acc.get(0).getAsInt();
            solidAcc.blocks = acc.get(1).getAsInt();
            solidAcc.volume = acc.get(2).getAsDouble();
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
            tubeColumns.put(a.get(0).getAsLong(), new int[] {a.get(1).getAsInt(), a.get(2).getAsInt()});
        }

        StateReader.Field cells = reader.field("cells");
        if (cells == null) return;
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
            System.arraycopy(f.doubles("solid"), 0, c.solid, 0, AREA);
            System.arraycopy(f.doubles("crust"), 0, c.crust, 0, AREA);
            System.arraycopy(f.doubles("roofTop"), 0, c.roofTop, 0, AREA);
            System.arraycopy(f.ints("unit"), 0, c.unit, 0, AREA);
            System.arraycopy(f.ints("renderBottom"), 0, c.renderBottom, 0, AREA);
            narrow(f.ints("renderMelt"), c.renderMelt);
            narrow(f.ints("renderGap"), c.renderGap);
            narrow(f.ints("renderRoof"), c.renderRoof);
            System.arraycopy(f.bytes("renderTop"), 0, c.renderTop, 0, AREA);
            System.arraycopy(f.bytes("renderRoofKind"), 0, c.renderRoofKind, 0, AREA);
            System.arraycopy(f.bytes("crustKind"), 0, c.crustKind, 0, AREA);
            c.recount();
            chunks.put(c.key, c);
        }
    }

    private static JsonArray positions(List<BlockPos> list) {
        JsonArray array = new JsonArray();
        for (BlockPos p : list) {
            JsonArray a = new JsonArray();
            a.add(p.x());
            a.add(p.y());
            a.add(p.z());
            array.add(a);
        }
        return array;
    }

    private static List<BlockPos> readPositions(JsonArray array) {
        List<BlockPos> list = new ArrayList<>();
        for (JsonElement e : array) {
            JsonArray a = e.getAsJsonArray();
            list.add(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
        }
        return list;
    }
}
