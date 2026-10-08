package me.alex4386.typhon.engine.subsurface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import me.alex4386.typhon.engine.world.ColumnStacks;
import me.alex4386.typhon.engine.world.LayerView;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialClass;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.WorldModel;

/**
 * The coarse 3-D grid the heat and groundwater solvers run on.
 *
 * <p>Horizontally, solver column {@code (gx, gz)} covers {@code r × r} surface columns of the world
 * model ({@code r = round(solverSpacing / metersPerColumn)}, so a solver column is {@code dx = r·L}
 * metres wide). Vertically each column has {@link #levels()} terrain-following levels whose
 * thickness grows geometrically with depth below the column's mean ground surface. Columns are
 * stored sparsely in {@link SolverChunk}s of 16×16 and exist wherever the world model knows a
 * surface column.
 *
 * <p>Material properties of each cell are those of the stratigraphic layer at the cell centre in
 * the block's representative surface column (the known column nearest the block centre). They are
 * recomputed whenever a tile of the world model changes; voids (lava tubes, tunnels) therefore
 * insulate and do not conduct water.
 */
final class SubsurfaceGrid {
    static final double WATER_DENSITY = 1000;
    static final double WATER_HEAT_CAPACITY = 4186;
    static final double GRAVITY = 9.81;

    private final WorldModel world;
    private final SubsurfaceConfig config;
    private final int ratio;
    private final double dx;
    private final double[] thickness;
    private final double[] topDepth;
    private final double[] centerDepth;
    private final double totalDepth;
    private final TreeMap<Long, SolverChunk> chunks = new TreeMap<>();
    /** Last world-model tile version applied, by stacks tile key. */
    private final Map<Long, Long> seenTileVersion = new HashMap<>();

    /** Fills a freshly discovered column's state (temperature profile, water table). */
    interface ColumnInitializer {
        void initialize(SolverChunk chunk, int c);
    }

    SubsurfaceGrid(WorldModel world, SubsurfaceConfig config) {
        this.world = world;
        this.config = config;
        double l = world.spec().metersPerColumn();
        this.ratio = Math.max(1, (int) Math.round(world.spec().solverSpacing() / l));
        this.dx = ratio * l;
        int n = config.levels;
        thickness = new double[n];
        topDepth = new double[n];
        centerDepth = new double[n];
        double depth = 0;
        double dz = config.firstLevelM;
        for (int k = 0; k < n; k++) {
            thickness[k] = dz;
            topDepth[k] = depth;
            centerDepth[k] = depth + dz / 2;
            depth += dz;
            dz *= config.levelGrowth;
        }
        totalDepth = depth;
    }

    WorldModel world() {
        return world;
    }

    int levels() {
        return thickness.length;
    }

    /** Surface columns per solver column side. */
    int ratio() {
        return ratio;
    }

    /** Solver column width (m). */
    double dx() {
        return dx;
    }

    double area() {
        return dx * dx;
    }

    double thickness(int k) {
        return thickness[k];
    }

    double topDepth(int k) {
        return topDepth[k];
    }

    double centerDepth(int k) {
        return centerDepth[k];
    }

    /** Depth of the grid bottom below the surface (m). */
    double totalDepth() {
        return totalDepth;
    }

    /** Level containing {@code depth} metres below the surface, or -1 below the grid. */
    int levelAtDepth(double depth) {
        if (depth < 0) return 0;
        for (int k = 0; k < thickness.length; k++) {
            if (depth < topDepth[k] + thickness[k]) return k;
        }
        return -1;
    }

    // ── Chunks ──

    static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    SolverChunk chunk(int cx, int cz) {
        return chunks.get(key(cx, cz));
    }

    /** Chunk holding solver column {@code (gx, gz)}, or {@code null}. */
    SolverChunk chunkOf(int gx, int gz) {
        return chunks.get(key(Math.floorDiv(gx, SolverChunk.SIZE), Math.floorDiv(gz, SolverChunk.SIZE)));
    }

    /** Chunk of solver column {@code (gx, gz)}, answering from {@code near} when it holds the column. */
    SolverChunk neighbourChunk(SolverChunk near, int gx, int gz) {
        if (Math.floorDiv(gx, SolverChunk.SIZE) == near.cx && Math.floorDiv(gz, SolverChunk.SIZE) == near.cz) return near;
        return chunkOf(gx, gz);
    }

    /** Incremented whenever solver columns appear or disappear (for cached topologies). */
    long structureVersion;

    /** All chunks in key order. */
    Iterable<SolverChunk> chunks() {
        return chunks.values();
    }

    int chunkCount() {
        return chunks.size();
    }

    void putChunk(SolverChunk chunk) {
        structureVersion++;
        chunks.put(key(chunk.cx, chunk.cz), chunk);
    }

    SolverChunk chunkOrCreate(int cx, int cz) {
        return chunks.computeIfAbsent(key(cx, cz), k -> new SolverChunk(cx, cz, levels()));
    }

    /** Solver column containing surface column {@code x}. */
    int solverCoord(int surface) {
        return Math.floorDiv(surface, ratio);
    }

    /** Whether solver column {@code (gx, gz)} exists. */
    boolean exists(int gx, int gz) {
        SolverChunk ch = chunkOf(gx, gz);
        return ch != null && ch.exists[SolverChunk.column(gx, gz)];
    }

    // ── Background state ──

    /** Conductive background geotherm (°C) at {@code depth} metres below the surface. */
    double backgroundTemperature(double depth) {
        return config.surfaceTemperatureC + config.gradientCPerKm * Math.max(0, depth) / 1000.0;
    }

    /** Elevation of the centre of level {@code k} of a column (m). */
    double cellElevation(SolverChunk ch, int c, int k) {
        return ch.surfaceZ[c] - centerDepth[k];
    }

    // ── Discovery and properties ──

    /**
     * Brings the grid up to date with the world model: creates solver columns for newly known
     * surface columns (initialising their state through {@code initializer}) and recomputes the
     * material properties of every column whose world-model tile changed.
     *
     * @return number of solver columns refreshed
     */
    int refresh(ColumnInitializer initializer) {
        ColumnStacks stacks = world.stacks();
        int refreshed = 0;
        List<long[]> dirty = new ArrayList<>();
        for (long tileKey : stacks.tileKeys()) {
            int tx = ColumnStacks.keyTileX(tileKey);
            int tz = ColumnStacks.keyTileZ(tileKey);
            long version = stacks.tileVersion(tx, tz);
            Long seen = seenTileVersion.get(tileKey);
            if (seen != null && seen == version) continue;
            seenTileVersion.put(tileKey, version);
            int x0 = tx * ColumnStacks.TILE;
            int z0 = tz * ColumnStacks.TILE;
            int g0x = solverCoord(x0);
            int g1x = solverCoord(x0 + ColumnStacks.TILE - 1);
            int g0z = solverCoord(z0);
            int g1z = solverCoord(z0 + ColumnStacks.TILE - 1);
            for (int gz = g0z; gz <= g1z; gz++) {
                for (int gx = g0x; gx <= g1x; gx++) dirty.add(new long[] {gx, gz});
            }
        }
        // Columns straddling tiles may be listed twice; refreshing is idempotent. Inside a changed tile, only
        // columns whose own footprint changed are recomputed (a column depends on nothing else).
        for (long[] g : dirty) {
            int gx = (int) g[0];
            int gz = (int) g[1];
            long footprint = stacks.footprint(gx * ratio, gz * ratio, ratio, ratio);
            int c = SolverChunk.column(gx, gz);
            SolverChunk ch = chunkOf(gx, gz);
            if (ch != null && ch.footprintValid[c] && ch.footprint[c] == footprint) continue;
            if (refreshColumn(gx, gz, initializer)) refreshed++;
            ch = chunkOf(gx, gz);
            if (ch != null) {
                ch.footprint[c] = footprint;
                ch.footprintValid[c] = true;
            }
        }
        return refreshed;
    }

    /** Forgets which tiles were seen, so the next {@link #refresh} recomputes every column. */
    void invalidate() {
        seenTileVersion.clear();
        for (SolverChunk ch : chunks.values()) java.util.Arrays.fill(ch.footprintValid, false);
    }

    private boolean refreshColumn(int gx, int gz, ColumnInitializer initializer) {
        int x0 = gx * ratio;
        int z0 = gz * ratio;
        double sum = 0;
        int known = 0;
        double lowest = Double.POSITIVE_INFINITY;
        int lowX = 0;
        int lowZ = 0;
        int repX = 0;
        int repZ = 0;
        double bestCenter = Double.POSITIVE_INFINITY;
        double lake = Double.NaN;
        double centre = (ratio - 1) / 2.0;
        for (int dz = 0; dz < ratio; dz++) {
            for (int dxc = 0; dxc < ratio; dxc++) {
                int x = x0 + dxc;
                int z = z0 + dz;
                if (!world.isKnown(x, z)) continue;
                double s = world.surfaceZ(x, z);
                sum += s;
                known++;
                if (s < lowest) {
                    lowest = s;
                    lowX = x;
                    lowZ = z;
                }
                double dc = (dxc - centre) * (dxc - centre) + (dz - centre) * (dz - centre);
                if (dc < bestCenter) {
                    bestCenter = dc;
                    repX = x;
                    repZ = z;
                }
                double w = world.waterZ(x, z);
                if (!Double.isNaN(w) && w > s && (Double.isNaN(lake) || w > lake)) lake = w;
            }
        }
        if (known == 0) {
            SolverChunk ch = chunkOf(gx, gz);
            if (ch != null && ch.exists[SolverChunk.column(gx, gz)]) {
                ch.exists[SolverChunk.column(gx, gz)] = false;
                structureVersion++;
            }
            return false;
        }
        SolverChunk ch = chunkOrCreate(Math.floorDiv(gx, SolverChunk.SIZE), Math.floorDiv(gz, SolverChunk.SIZE));
        int c = SolverChunk.column(gx, gz);
        if (!ch.exists[c]) structureVersion++;
        ch.exists[c] = true;
        ch.surfaceZ[c] = sum / known;
        ch.knownColumns[c] = known;
        ch.outletX[c] = lowX;
        ch.outletZ[c] = lowZ;
        double sea = world.spec().seaLevelZ();
        ch.sea[c] = !Double.isNaN(sea) && ch.surfaceZ[c] < sea;
        ch.lakeZ[c] = lake;

        int n = levels();
        double repSurface = world.surfaceZ(repX, repZ);
        for (int k = 0; k < n; k++) {
            double elevation = Math.min(ch.surfaceZ[c] - centerDepth[k], repSurface - 1e-3);
            int layer = world.stacks().layerIndexAt(repX, repZ, elevation);
            Material m;
            double porosity;
            double voidFraction = 0;
            if (layer < 0) {
                m = MaterialTable.get(world.layer(repX, repZ, 0).material());
                porosity = m.porosity();
            } else {
                LayerView view = world.stacks().layer(repX, repZ, layer);
                m = MaterialTable.get(view.material());
                porosity = view.porosity();
                voidFraction = view.voidFraction();
            }
            int i = c * n + k;
            boolean cavity = m.materialClass() == MaterialClass.VOID || m.materialClass() == MaterialClass.AIR;
            double solidFraction = cavity ? 0 : 1 - voidFraction;
            ch.conductivity[i] = (float) (cavity ? m.conductivityWmK()
                    : solidFraction * m.conductivityWmK() + (1 - solidFraction) * MaterialTable.AIR.conductivityWmK());
            ch.density[i] = (float) m.densityKgM3();
            ch.heatCapacity[i] = (float) (m.densityKgM3() * m.heatCapacityJkgK() * (cavity ? 1 : solidFraction)
                    + (cavity ? 0 : (1 - solidFraction) * MaterialTable.AIR.densityKgM3() * MaterialTable.AIR.heatCapacityJkgK()));
            ch.porosity[i] = (float) (cavity ? 0 : Math.max(0, Math.min(1, porosity)));
            double logK = m.log10HydraulicConductivity();
            ch.hydraulicK[i] = (float) (cavity || Double.isNaN(logK) || m.materialClass() == MaterialClass.WATER
                    ? 0 : MaterialTable.hydraulicConductivity(m));
            ch.solidus[i] = (float) m.solidusC();
            ch.liquidus[i] = (float) m.liquidusC();
        }
        if (!ch.initialized[c]) {
            ch.initialized[c] = true;
            initializer.initialize(ch, c);
        }
        return true;
    }
}
