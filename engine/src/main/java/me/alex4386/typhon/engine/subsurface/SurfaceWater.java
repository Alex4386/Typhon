package me.alex4386.typhon.engine.subsurface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import me.alex4386.typhon.engine.world.ColumnStacks;
import me.alex4386.typhon.engine.world.WorldModel;

/**
 * Standing and flowing surface water (lakes, rivers, rain runoff, a player's bucket) on the world
 * model's surface columns.
 *
 * <p>Local-inertial shallow water (Bates, Horritt &amp; Fewtrell 2010): per face,
 * {@code q ← (q − g·h_f·Δt·∂η/∂x) / (1 + g·Δt·n²·|q| / h_f^{7/3})} with flow depth
 * {@code h_f = max(η) − max(z_b)}, then {@code ∂d/∂t = −∇·q}. Sub-steps keep
 * {@code Δt ≤ C·Δx/√(g·d_max)}; outflow is limited so depths stay non-negative (mass is conserved
 * exactly). Columns below sea level are a fixed-level boundary; unknown columns are walls. Water
 * infiltrates into the vadose zone of the solver column below where the water table lies below the
 * ground, and evaporates at the climate rate.
 *
 * <p>Storage is sparse 32×32 tiles aligned with the world model's tiles; only tiles holding water
 * (and their neighbours, transiently) exist.
 */
final class SurfaceWater {
    static final int TILE = ColumnStacks.TILE;
    static final int AREA = TILE * TILE;

    static final class Tile {
        final int tx;
        final int tz;
        final double[] depth = new double[AREA];
        /** Unit-width discharge (m²/s) through the east / south face of each column. */
        final double[] qEast = new double[AREA];
        final double[] qSouth = new double[AREA];
        // Derived from the world model
        final double[] bed = new double[AREA];
        final boolean[] known = new boolean[AREA];
        final boolean[] sea = new boolean[AREA];
        long version = Long.MIN_VALUE;

        Tile(int tx, int tz) {
            this.tx = tx;
            this.tz = tz;
        }

        boolean dry() {
            for (int i = 0; i < AREA; i++) {
                if (depth[i] > 0 || qEast[i] != 0 || qSouth[i] != 0) return false;
            }
            return true;
        }
    }

    private final WorldModel world;
    private final SubsurfaceConfig config;
    private final TreeMap<Long, Tile> tiles = new TreeMap<>();
    /** World-model tiles whose standing water has been imported. */
    final TreeSet<Long> seeded = new TreeSet<>();

    // Budget (m³, cumulative since construction or load)
    double poured;
    double runoff;
    double springInflow;
    double evaporated;
    double infiltrated;
    double seaOutflow;
    double seaInflow;
    double seededVolume;
    double rejected;

    SurfaceWater(WorldModel world, SubsurfaceConfig config) {
        this.world = world;
        this.config = config;
    }

    static long key(int tx, int tz) {
        return ((long) tx << 32) | (tz & 0xffffffffL);
    }

    static int local(int x, int z) {
        return (Math.floorMod(z, TILE) << 5) | Math.floorMod(x, TILE);
    }

    double cellArea() {
        double l = world.spec().metersPerColumn();
        return l * l;
    }

    Tile tile(int x, int z) {
        return tiles.get(key(Math.floorDiv(x, TILE), Math.floorDiv(z, TILE)));
    }

    Iterable<Tile> tiles() {
        return tiles.values();
    }

    int tileCount() {
        return tiles.size();
    }

    void putTile(Tile t) {
        tiles.put(key(t.tx, t.tz), t);
    }

    private Tile tileOrCreate(int tx, int tz) {
        Tile t = tiles.get(key(tx, tz));
        if (t == null) {
            t = new Tile(tx, tz);
            tiles.put(key(tx, tz), t);
            refresh(t);
        }
        return t;
    }

    /** Re-reads bed elevations of a tile from the world model when it changed. */
    void refresh(Tile t) {
        long version = world.stacks().tileVersion(t.tx, t.tz);
        if (version == t.version) return;
        t.version = version;
        double sea = world.spec().seaLevelZ();
        for (int i = 0; i < AREA; i++) {
            int x = t.tx * TILE + (i & 31);
            int z = t.tz * TILE + (i >> 5);
            boolean known = world.isKnown(x, z);
            t.known[i] = known;
            t.bed[i] = known ? world.surfaceZ(x, z) + world.uplift(x, z) : Double.NaN;
            t.sea[i] = known && !Double.isNaN(sea) && t.bed[i] < sea;
            if (!known || t.sea[i]) {
                t.depth[i] = 0;
                t.qEast[i] = 0;
                t.qSouth[i] = 0;
            }
        }
    }

    // ── Queries ──

    /** Water depth (m) above the ground of a column; below sea level the sea depth. */
    double depth(int x, int z) {
        Tile t = tile(x, z);
        if (t != null) {
            int i = local(x, z);
            if (t.sea[i]) return world.spec().seaLevelZ() - t.bed[i];
            return t.depth[i];
        }
        double sea = world.spec().seaLevelZ();
        if (!Double.isNaN(sea) && world.isKnown(x, z)) {
            double bed = world.surfaceZ(x, z);
            if (bed < sea) return sea - bed;
        }
        return 0;
    }

    /** Total stored surface water (m³), excluding the sea. */
    double volume() {
        double sum = 0;
        for (Tile t : tiles.values()) for (int i = 0; i < AREA; i++) sum += t.depth[i];
        return sum * cellArea();
    }

    // ── Sources ──

    /** Adds water at a column (bucket, runoff, spring). Returns false (counted as rejected) if unknown/sea. */
    boolean add(int x, int z, double volumeM3, Source source) {
        if (!(volumeM3 > 0)) return false;
        Tile t = tileOrCreate(Math.floorDiv(x, TILE), Math.floorDiv(z, TILE));
        int i = local(x, z);
        if (!t.known[i]) {
            rejected += volumeM3;
            return false;
        }
        if (t.sea[i]) {
            seaOutflow += volumeM3; // straight into the sea
            switch (source) {
                case POURED -> poured += volumeM3;
                case RUNOFF -> runoff += volumeM3;
                case SPRING -> springInflow += volumeM3;
            }
            return true;
        }
        t.depth[i] += volumeM3 / cellArea();
        switch (source) {
            case POURED -> poured += volumeM3;
            case RUNOFF -> runoff += volumeM3;
            case SPRING -> springInflow += volumeM3;
        }
        return true;
    }

    enum Source { POURED, RUNOFF, SPRING }

    /** Imports standing water of world-model tiles not seen before (lakes from the host's terrain). */
    void seedLakes() {
        for (long key : world.stacks().tileKeys()) {
            if (seeded.contains(key)) continue;
            seeded.add(key);
            int tx = ColumnStacks.keyTileX(key);
            int tz = ColumnStacks.keyTileZ(key);
            Tile t = null;
            for (int i = 0; i < AREA; i++) {
                int x = tx * TILE + (i & 31);
                int z = tz * TILE + (i >> 5);
                if (!world.isKnown(x, z)) continue;
                double w = world.waterZ(x, z);
                double bed = world.surfaceZ(x, z) + world.uplift(x, z);
                if (Double.isNaN(w) || !(w > bed)) continue;
                if (t == null) t = tileOrCreate(tx, tz);
                if (t.sea[i]) continue;
                double d = w - bed;
                t.depth[i] += d;
                seededVolume += d * cellArea();
            }
        }
    }

    // ── Step ──

    interface Infiltration {
        /** Infiltration capacity (m/s) at a surface column, 0 where the ground is saturated. */
        double capacity(int x, int z);

        void accept(int x, int z, double volumeM3);
    }

    void step(double dt, Infiltration infiltration) {
        if (tiles.isEmpty()) return;
        // Active tiles and their 4-neighbours (transient dry halo).
        List<Long> wet = new ArrayList<>();
        for (Tile t : tiles.values()) {
            refresh(t);
            for (int i = 0; i < AREA; i++) {
                if (t.depth[i] > 0) {
                    wet.add(key(t.tx, t.tz));
                    break;
                }
            }
        }
        for (long k : wet) {
            int tx = (int) (k >> 32);
            int tz = (int) k;
            int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : around) {
                int nx = tx + d[0];
                int nz = tz + d[1];
                if (!tiles.containsKey(key(nx, nz)) && world.stacks().tileVersion(nx, nz) != 0) tileOrCreate(nx, nz);
            }
        }
        double dx = world.spec().metersPerColumn();
        double remaining = dt;
        int guard = 0;
        while (remaining > 1e-9 && guard++ < 10_000) {
            double maxDepth = config.minFlowDepthM;
            for (Tile t : tiles.values()) for (double d : t.depth) if (d > maxDepth) maxDepth = d;
            double stable = config.surfaceWaterCfl * dx / Math.sqrt(SubsurfaceGrid.GRAVITY * maxDepth);
            double h = Math.min(remaining, stable);
            substep(h, dx);
            remaining -= h;
        }
        double evaporation = config.evaporationMmPerHour / 1000.0 / 3600.0 * dt;
        double area = cellArea();
        for (Tile t : tiles.values()) {
            for (int i = 0; i < AREA; i++) {
                double d = t.depth[i];
                if (d <= 0) continue;
                int x = t.tx * TILE + (i & 31);
                int z = t.tz * TILE + (i >> 5);
                double cap = infiltration.capacity(x, z) * dt;
                double inf = Math.min(d, cap);
                if (inf > 0) {
                    d -= inf;
                    infiltrated += inf * area;
                    infiltration.accept(x, z, inf * area);
                }
                double ev = Math.min(d, evaporation);
                d -= ev;
                evaporated += ev * area;
                if (d < 1e-9) {
                    evaporated += d * area; // negligible films dry up
                    d = 0;
                }
                t.depth[i] = d;
            }
        }
        tiles.values().removeIf(Tile::dry);
    }

    private double bed(Tile t, int i) {
        return t.bed[i];
    }

    private void substep(double dt, double dx) {
        double g = SubsurfaceGrid.GRAVITY;
        double n2 = config.manningN * config.manningN;
        double sea = world.spec().seaLevelZ();
        double minDepth = config.minFlowDepthM;
        List<Tile> list = new ArrayList<>(tiles.values());
        // 1. Face discharges.
        for (Tile t : list) {
            for (int i = 0; i < AREA; i++) {
                if (!t.known[i]) {
                    t.qEast[i] = 0;
                    t.qSouth[i] = 0;
                    continue;
                }
                int x = t.tx * TILE + (i & 31);
                int z = t.tz * TILE + (i >> 5);
                t.qEast[i] = faceFlux(t, i, x + 1, z, t.qEast[i], dt, dx, g, n2, sea, minDepth);
                t.qSouth[i] = faceFlux(t, i, x, z + 1, t.qSouth[i], dt, dx, g, n2, sea, minDepth);
            }
        }
        // 2. Outflow limiter: scale the outgoing faces of each wet column so its depth stays ≥ 0.
        java.util.IdentityHashMap<Tile, double[]> factor = new java.util.IdentityHashMap<>();
        for (Tile t : list) {
            double[] f = new double[AREA];
            Arrays.fill(f, 1);
            for (int i = 0; i < AREA; i++) {
                if (!t.known[i] || t.sea[i]) continue;
                int x = t.tx * TILE + (i & 31);
                int z = t.tz * TILE + (i >> 5);
                double out = Math.max(0, t.qEast[i]) + Math.max(0, t.qSouth[i])
                        + Math.max(0, -q(x - 1, z, true)) + Math.max(0, -q(x, z - 1, false));
                out *= dt / dx;
                if (out > t.depth[i]) f[i] = out > 0 ? t.depth[i] / out : 0;
            }
            factor.put(t, f);
        }
        // 3. Apply fluxes (each face once, from the column on its west/north side).
        for (Tile t : list) {
            double[] f = factor.get(t);
            for (int i = 0; i < AREA; i++) {
                int x = t.tx * TILE + (i & 31);
                int z = t.tz * TILE + (i >> 5);
                transfer(t, i, f, x + 1, z, t.qEast[i], dt, dx, factor);
                transfer(t, i, f, x, z + 1, t.qSouth[i], dt, dx, factor);
            }
        }
    }

    private double q(int x, int z, boolean eastFace) {
        Tile t = tile(x, z);
        if (t == null) return 0;
        int i = local(x, z);
        return eastFace ? t.qEast[i] : t.qSouth[i];
    }

    private double faceFlux(Tile t, int i, int nx, int nz, double q, double dt, double dx, double g, double n2,
            double sea, double minDepth) {
        Tile o = tile(nx, nz);
        if (o == null) return 0;
        int j = local(nx, nz);
        if (!o.known[j]) return 0;
        if (t.sea[i] && o.sea[j]) return 0;
        double etaI = t.sea[i] ? sea : bed(t, i) + t.depth[i];
        double etaJ = o.sea[j] ? sea : bed(o, j) + o.depth[j];
        double hf = Math.max(etaI, etaJ) - Math.max(bed(t, i), bed(o, j));
        if (hf <= minDepth) return 0;
        double slope = (etaJ - etaI) / dx;
        double next = (q - g * hf * dt * slope) / (1 + g * dt * n2 * Math.abs(q) / Math.pow(hf, 7.0 / 3.0));
        return next;
    }

    private void transfer(Tile t, int i, double[] f, int nx, int nz, double q, double dt, double dx,
            java.util.IdentityHashMap<Tile, double[]> factor) {
        if (q == 0) return;
        Tile o = tile(nx, nz);
        if (o == null) return;
        int j = local(nx, nz);
        double scale = q > 0 ? f[i] : factor.get(o)[j];
        double depthMoved = q * scale * dt / dx; // m over one column, + = from (t,i) to (o,j)
        double volume = depthMoved * cellArea();
        if (t.sea[i]) {
            seaInflow += Math.max(0, volume);
            seaOutflow += Math.max(0, -volume);
        } else {
            t.depth[i] -= depthMoved;
            if (t.depth[i] < 0) t.depth[i] = 0;
        }
        if (o.sea[j]) {
            seaOutflow += Math.max(0, volume);
            seaInflow += Math.max(0, -volume);
        } else {
            o.depth[j] += depthMoved;
            if (o.depth[j] < 0) o.depth[j] = 0;
        }
    }
}
