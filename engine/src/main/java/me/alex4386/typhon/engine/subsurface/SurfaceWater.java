package me.alex4386.typhon.engine.subsurface;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * {@code Δt ≤ C·Δx/√(g·h_max)}; outflow is limited so depths stay non-negative (mass is conserved
 * exactly). Unknown columns are walls.
 *
 * <p>Open water is a fixed-level boundary: columns below the world's sea level, and water bodies
 * imported with the terrain that reach the edge of the known world (the host's ocean or a lake
 * that continues beyond the modelled area). Imported water bodies enclosed by the known world are
 * ordinary lakes and evolve. Water infiltrates into the vadose zone where the water table lies
 * below the ground and evaporates at the climate rate.
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
        /** Fixed water level of open-water columns (sea, open imported water), NaN elsewhere. */
        final double[] level = new double[AREA];
        /** Outflow scaling of the current substep (scratch). */
        final double[] limiter = new double[AREA];
        long version = Long.MIN_VALUE;
        // Neighbour tiles, refreshed at the start of every step
        Tile east;
        Tile west;
        Tile south;
        Tile north;

        Tile(int tx, int tz) {
            this.tx = tx;
            this.tz = tz;
            Arrays.fill(level, Double.NaN);
        }

        boolean fixed(int i) {
            return !Double.isNaN(level[i]);
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
    /** Fixed levels of open water imported with the terrain, by tile key (NaN = not open water). */
    final TreeMap<Long, double[]> openWater = new TreeMap<>();

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

    /** Fixed level of a column: open imported water, else the sea below sea level, else NaN. */
    private double fixedLevel(int x, int z, double bed) {
        double[] open = openWater.get(key(Math.floorDiv(x, TILE), Math.floorDiv(z, TILE)));
        if (open != null && !Double.isNaN(open[local(x, z)])) return open[local(x, z)];
        double sea = world.spec().seaLevelZ();
        return !Double.isNaN(sea) && bed < sea ? sea : Double.NaN;
    }

    /** Re-reads bed elevations of a tile from the world model when it changed. */
    void refresh(Tile t) {
        long version = world.stacks().tileVersion(t.tx, t.tz);
        if (version == t.version) return;
        t.version = version;
        for (int i = 0; i < AREA; i++) {
            int x = t.tx * TILE + (i & 31);
            int z = t.tz * TILE + (i >> 5);
            boolean known = world.isKnown(x, z);
            t.known[i] = known;
            t.bed[i] = known ? world.surfaceZ(x, z) + world.uplift(x, z) : Double.NaN;
            t.level[i] = known ? fixedLevel(x, z, t.bed[i]) : Double.NaN;
            // Discharges are state (inertia) and must survive a refresh; only cells that are not
            // part of the world lose them. Open-water cells never hold depth of their own.
            if (!known) {
                t.qEast[i] = 0;
                t.qSouth[i] = 0;
            }
            if (!known || t.fixed(i)) t.depth[i] = 0;
        }
    }

    // ── Queries ──

    /** Water depth (m) above the ground of a column; for open water the depth below its level. */
    double depth(int x, int z) {
        Tile t = tile(x, z);
        if (t != null) {
            int i = local(x, z);
            if (t.fixed(i)) return Math.max(0, t.level[i] - t.bed[i]);
            return t.depth[i];
        }
        if (!world.isKnown(x, z)) return 0;
        double bed = world.surfaceZ(x, z) + world.uplift(x, z);
        double level = fixedLevel(x, z, bed);
        return Double.isNaN(level) ? 0 : Math.max(0, level - bed);
    }

    /** Whether a column is open water (fixed level). */
    boolean open(int x, int z) {
        Tile t = tile(x, z);
        if (t != null) return t.fixed(local(x, z));
        if (!world.isKnown(x, z)) return false;
        return !Double.isNaN(fixedLevel(x, z, world.surfaceZ(x, z) + world.uplift(x, z)));
    }

    /** Total stored surface water (m³), excluding open water. */
    double volume() {
        double sum = 0;
        for (Tile t : tiles.values()) for (int i = 0; i < AREA; i++) sum += t.depth[i];
        return sum * cellArea();
    }

    // ── Sources ──

    enum Source { POURED, RUNOFF, SPRING }

    /** Adds water at a column (bucket, runoff, spring). Returns false (counted as rejected) if unknown. */
    boolean add(int x, int z, double volumeM3, Source source) {
        if (!(volumeM3 > 0)) return false;
        Tile t = tileOrCreate(Math.floorDiv(x, TILE), Math.floorDiv(z, TILE));
        int i = local(x, z);
        if (!t.known[i]) {
            rejected += volumeM3;
            return false;
        }
        if (t.fixed(i)) {
            seaOutflow += volumeM3; // straight into the open water
        } else {
            t.depth[i] += volumeM3 / cellArea();
        }
        switch (source) {
            case POURED -> poured += volumeM3;
            case RUNOFF -> runoff += volumeM3;
            case SPRING -> springInflow += volumeM3;
        }
        return true;
    }

    /**
     * Imports standing water of world-model tiles not seen before. Water bodies (4-connected columns
     * with water above the ground) that touch an unknown column — the edge of the modelled area —
     * become open water at their level; enclosed ones are seeded as lake water.
     */
    void seedLakes() {
        List<Long> fresh = new ArrayList<>();
        for (long key : world.stacks().tileKeys()) {
            if (seeded.add(key)) fresh.add(key);
        }
        if (fresh.isEmpty()) return;
        java.util.Set<Long> freshSet = new java.util.HashSet<>(fresh);
        Map<Long, Integer> component = new HashMap<>();
        int next = 0;
        for (long key : fresh) {
            int tx = ColumnStacks.keyTileX(key);
            int tz = ColumnStacks.keyTileZ(key);
            for (int i = 0; i < AREA; i++) {
                int x = tx * TILE + (i & 31);
                int z = tz * TILE + (i >> 5);
                long column = columnKey(x, z);
                if (component.containsKey(column) || waterAbove(x, z) <= 0) continue;
                // Flood-fill this water body.
                List<long[]> members = new ArrayList<>();
                boolean open = false;
                ArrayDeque<long[]> queue = new ArrayDeque<>();
                queue.add(new long[] {x, z});
                component.put(column, next);
                while (!queue.isEmpty()) {
                    long[] p = queue.poll();
                    members.add(p);
                    int px = (int) p[0];
                    int pz = (int) p[1];
                    int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
                    for (int[] d : around) {
                        int nx = px + d[0];
                        int nz = pz + d[1];
                        if (!world.isKnown(nx, nz)) {
                            open = true;
                            continue;
                        }
                        long nk = columnKey(nx, nz);
                        if (component.containsKey(nk) || waterAbove(nx, nz) <= 0) continue;
                        component.put(nk, next);
                        queue.add(new long[] {nx, nz});
                    }
                }
                next++;
                members.sort((a, b) -> a[1] != b[1] ? Long.compare(a[1], b[1]) : Long.compare(a[0], b[0]));
                for (long[] p : members) {
                    int mx = (int) p[0];
                    int mz = (int) p[1];
                    double w = world.waterZ(mx, mz);
                    if (open) {
                        openWater.computeIfAbsent(key(Math.floorDiv(mx, TILE), Math.floorDiv(mz, TILE)), k -> {
                            double[] levels = new double[AREA];
                            Arrays.fill(levels, Double.NaN);
                            return levels;
                        })[local(mx, mz)] = w;
                    } else {
                        // Water of tiles seeded earlier is already in the field.
                        if (!freshSet.contains(key(Math.floorDiv(mx, TILE), Math.floorDiv(mz, TILE)))) continue;
                        Tile t = tileOrCreate(Math.floorDiv(mx, TILE), Math.floorDiv(mz, TILE));
                        int li = local(mx, mz);
                        if (t.fixed(li)) continue;
                        double d = waterAbove(mx, mz);
                        t.depth[li] += d;
                        seededVolume += d * cellArea();
                    }
                }
            }
        }
        // Open levels change which cells are fixed: re-derive existing tiles.
        for (Tile t : tiles.values()) t.version = Long.MIN_VALUE;
    }

    private double waterAbove(int x, int z) {
        if (!world.isKnown(x, z)) return 0;
        double w = world.waterZ(x, z);
        double bed = world.surfaceZ(x, z) + world.uplift(x, z);
        return Double.isNaN(w) ? 0 : Math.max(0, w - bed);
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    // ── Step ──

    interface Infiltration {
        /** Infiltration capacity (m/s) at a surface column, 0 where the ground is saturated. */
        double capacity(int x, int z);

        void accept(int x, int z, double volumeM3);
    }

    void step(double dt, Infiltration infiltration) {
        if (tiles.isEmpty()) return;
        // Wet tiles get their 4 neighbours (transient dry halo) so water can spread.
        List<Tile> wet = new ArrayList<>();
        for (Tile t : tiles.values()) {
            for (int i = 0; i < AREA; i++) {
                if (t.depth[i] > 0) {
                    wet.add(t);
                    break;
                }
            }
        }
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (Tile t : wet) {
            for (int[] d : around) {
                int nx = t.tx + d[0];
                int nz = t.tz + d[1];
                if (!tiles.containsKey(key(nx, nz)) && world.stacks().tileVersion(nx, nz) != 0) tileOrCreate(nx, nz);
            }
        }
        for (Tile t : tiles.values()) {
            refresh(t);
            t.east = tiles.get(key(t.tx + 1, t.tz));
            t.west = tiles.get(key(t.tx - 1, t.tz));
            t.south = tiles.get(key(t.tx, t.tz + 1));
            t.north = tiles.get(key(t.tx, t.tz - 1));
        }
        List<Tile> list = new ArrayList<>(tiles.values());
        double dx = world.spec().metersPerColumn();
        double remaining = dt;
        int guard = 0;
        while (remaining > 1e-9 && guard++ < 10_000) {
            double maxDepth = Math.max(config.minFlowDepthM, maxFlowDepth(list));
            double stable = config.surfaceWaterCfl * dx / Math.sqrt(SubsurfaceGrid.GRAVITY * maxDepth);
            double h = Math.min(remaining, stable);
            substep(list, h, dx);
            remaining -= h;
        }
        double evaporation = config.evaporationMmPerHour / 1000.0 / 3600.0 * dt;
        double area = cellArea();
        for (Tile t : list) {
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

    /** Deepest water that can flow this substep: lake/river depths and open water next to land. */
    private double maxFlowDepth(List<Tile> list) {
        double max = 0;
        for (Tile t : list) {
            for (int i = 0; i < AREA; i++) {
                if (t.fixed(i)) {
                    if (hasLandNeighbour(t, i)) max = Math.max(max, t.level[i] - t.bed[i]);
                } else if (t.depth[i] > max) {
                    max = t.depth[i];
                }
            }
        }
        return max;
    }

    private boolean hasLandNeighbour(Tile t, int i) {
        int lx = i & 31;
        int lz = i >> 5;
        return landAt(lx < 31 ? t : t.east, lx < 31 ? i + 1 : i - 31)
                || landAt(lx > 0 ? t : t.west, lx > 0 ? i - 1 : i + 31)
                || landAt(lz < 31 ? t : t.south, lz < 31 ? i + TILE : i - 31 * TILE)
                || landAt(lz > 0 ? t : t.north, lz > 0 ? i - TILE : i + 31 * TILE);
    }

    private static boolean landAt(Tile t, int i) {
        return t != null && t.known[i] && !t.fixed(i);
    }

    private void substep(List<Tile> list, double dt, double dx) {
        double g = SubsurfaceGrid.GRAVITY;
        double n2 = config.manningN * config.manningN;
        double minDepth = config.minFlowDepthM;
        // 1. Face discharges (east and south face of every column).
        for (Tile t : list) {
            for (int i = 0; i < AREA; i++) {
                if (!t.known[i]) {
                    t.qEast[i] = 0;
                    t.qSouth[i] = 0;
                    continue;
                }
                int lx = i & 31;
                int lz = i >> 5;
                Tile te = lx < 31 ? t : t.east;
                int je = lx < 31 ? i + 1 : i - 31;
                Tile ts = lz < 31 ? t : t.south;
                int js = lz < 31 ? i + TILE : i - 31 * TILE;
                t.qEast[i] = faceFlux(t, i, te, je, t.qEast[i], dt, dx, g, n2, minDepth);
                t.qSouth[i] = faceFlux(t, i, ts, js, t.qSouth[i], dt, dx, g, n2, minDepth);
            }
        }
        // 2. Outflow limiter: scale the outgoing faces of each column so its depth stays ≥ 0.
        for (Tile t : list) {
            double[] f = t.limiter;
            for (int i = 0; i < AREA; i++) {
                f[i] = 1;
                if (!t.known[i] || t.fixed(i)) continue;
                int lx = i & 31;
                int lz = i >> 5;
                Tile tw = lx > 0 ? t : t.west;
                int jw = lx > 0 ? i - 1 : i + 31;
                Tile tn = lz > 0 ? t : t.north;
                int jn = lz > 0 ? i - TILE : i + 31 * TILE;
                double out = Math.max(0, t.qEast[i]) + Math.max(0, t.qSouth[i])
                        + (tw == null ? 0 : Math.max(0, -tw.qEast[jw]))
                        + (tn == null ? 0 : Math.max(0, -tn.qSouth[jn]));
                out *= dt / dx;
                if (out > t.depth[i]) f[i] = out > 0 ? t.depth[i] / out : 0;
            }
        }
        // 3. Apply fluxes (each face once, from the column on its west/north side).
        double area = cellArea();
        for (Tile t : list) {
            for (int i = 0; i < AREA; i++) {
                int lx = i & 31;
                int lz = i >> 5;
                if (t.qEast[i] != 0) transfer(t, i, lx < 31 ? t : t.east, lx < 31 ? i + 1 : i - 31, t.qEast[i], dt, dx, area);
                if (t.qSouth[i] != 0) {
                    transfer(t, i, lz < 31 ? t : t.south, lz < 31 ? i + TILE : i - 31 * TILE, t.qSouth[i], dt, dx, area);
                }
            }
        }
    }

    private static double faceFlux(Tile t, int i, Tile o, int j, double q, double dt, double dx, double g, double n2,
            double minDepth) {
        if (o == null || !o.known[j]) return 0;
        boolean fi = t.fixed(i);
        boolean fj = o.fixed(j);
        if (fi && fj) return 0;
        if (!fi && !fj && t.depth[i] <= 0 && o.depth[j] <= 0 && q == 0) return 0;
        double etaI = fi ? t.level[i] : t.bed[i] + t.depth[i];
        double etaJ = fj ? o.level[j] : o.bed[j] + o.depth[j];
        double hf = Math.max(etaI, etaJ) - Math.max(t.bed[i], o.bed[j]);
        if (hf <= minDepth) return 0;
        double slope = (etaJ - etaI) / dx;
        return (q - g * hf * dt * slope) / (1 + g * dt * n2 * Math.abs(q) / Math.pow(hf, 7.0 / 3.0));
    }

    private void transfer(Tile t, int i, Tile o, int j, double q, double dt, double dx, double area) {
        if (o == null) return;
        double scale = q > 0 ? t.limiter[i] : o.limiter[j];
        double depthMoved = q * scale * dt / dx; // m over one column, + = from (t,i) to (o,j)
        double volume = depthMoved * area;
        if (t.fixed(i)) {
            seaInflow += Math.max(0, volume);
            seaOutflow += Math.max(0, -volume);
        } else {
            t.depth[i] -= depthMoved;
            if (t.depth[i] < 0) t.depth[i] = 0;
        }
        if (o.fixed(j)) {
            seaOutflow += Math.max(0, volume);
            seaInflow += Math.max(0, -volume);
        } else {
            o.depth[j] += depthMoved;
            if (o.depth[j] < 0) o.depth[j] = 0;
        }
    }
}
