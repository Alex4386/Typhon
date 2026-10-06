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
    /** Discharge (m²/s) below which a tile's water counts as at rest. */
    static final double SETTLED_DISCHARGE = 1e-6;
    /**
     * A step's routing ends early once the rest of it could change no column's depth by more than this
     * (m), at the current largest rate of change: the flow is quasi-steady.
     */
    static final double STEADY_DEPTH_M = 0.01;
    /** Largest |dh/dt| (m/s) of the last substep. */
    private double lastChangeRate;
    /** Diagnostics of the last step: substeps routed, tiles routed, deepest flowing water (m). */
    int lastSubsteps;
    int lastRoutedTiles;
    double lastMaxDepth;

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
        /** {@code level[i]} is set (kept with it in {@link SurfaceWater#refresh}); cheaper to test than NaN. */
        final boolean[] fixedMask = new boolean[AREA];
        /** Deepest open-water column with a land neighbour (CFL), cached for one step; -1 = recompute. */
        double fixedFlowDepth = -1;
        /** Scratch, refreshed by parallel scans: the tile holds water / any non-zero discharge. */
        boolean wetScratch;
        boolean fluxScratch;
        /** 1 where the column holds water or a non-zero discharge (kept current through substeps). */
        final byte[] wet = new byte[AREA];
        /**
         * 1 for open-water columns that may border land (a known non-fixed neighbour in the tile, or
         * on the tile edge); refreshed with the bed. They stay active for the flow solver.
         */
        final byte[] coast = new byte[AREA];
        /** Scratch per substep: indices of the columns the flow solver visits. */
        final int[] active = new int[AREA];
        int activeCount;
        /** Outflow scaling of the current substep (scratch). */
        final double[] limiter = new double[AREA];
        long version = Long.MIN_VALUE;
        /**
         * Water at rest (all discharges below {@link #SETTLED_DISCHARGE}): skipped by the flow solver
         * until water is added, the bed changes or an active neighbour wakes it.
         */
        boolean settled;
        /** Scratch: woken by an active neighbour this step. */
        boolean wake;
        /** Scratch of the parallel infiltration pass: volume (m³) per column, folded sequentially. */
        final double[] infiltratedScratch = new double[AREA];
        double infiltratedPart;
        double evaporatedPart;
        double maxDepthScratch;
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
            return fixedMask[i];
        }

        boolean dry() {
            for (int i = 0; i < AREA; i++) {
                if (depth[i] > 0 || qEast[i] != 0 || qSouth[i] != 0) return false;
            }
            return true;
        }

        /** Refreshes {@link #wetScratch}, {@link #fluxScratch} and the per-column {@link #wet} flags. */
        void scan() {
            boolean anyWet = false;
            boolean anyFlux = false;
            for (int i = 0; i < AREA; i++) {
                boolean w = depth[i] > 0;
                boolean f = qEast[i] != 0 || qSouth[i] != 0;
                wet[i] = (byte) (w || f ? 1 : 0);
                anyWet |= w;
                anyFlux |= f;
            }
            wetScratch = anyWet;
            fluxScratch = anyFlux;
        }
    }

    private final WorldModel world;
    private final SubsurfaceConfig config;
    final Parallel parallel;
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
        this.parallel = new Parallel(config);
    }

    /** Columns of water that is still moving (tiles not at rest) at least {@code minDepth} deep; open water excluded. */
    void reportMoving(double minDepth, me.alex4386.typhon.engine.expansion.ExpansionActivity.Sink sink) {
        for (Tile t : tiles.values()) {
            if (t.settled) continue;
            for (int i = 0; i < AREA; i++) {
                if (t.depth[i] >= minDepth && !t.fixed(i)) sink.active(t.tx * TILE + i % TILE, t.tz * TILE + i / TILE);
            }
        }
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
        if (t.version != Long.MIN_VALUE) t.settled = false; // the bed changed under the water
        t.version = version;
        t.fixedFlowDepth = -1;

        for (int i = 0; i < AREA; i++) {
            int x = t.tx * TILE + (i & 31);
            int z = t.tz * TILE + (i >> 5);
            boolean known = world.isKnown(x, z);
            t.known[i] = known;
            t.bed[i] = known ? world.surfaceZ(x, z) + world.uplift(x, z) : Double.NaN;
            t.level[i] = known ? fixedLevel(x, z, t.bed[i]) : Double.NaN;
            t.fixedMask[i] = !Double.isNaN(t.level[i]);

            // Discharges are state (inertia) and must survive a refresh; only cells that are not
            // part of the world lose them. Open-water cells never hold depth of their own.
            if (!known) {
                t.qEast[i] = 0;
                t.qSouth[i] = 0;
            }
            if (!known || t.fixed(i)) t.depth[i] = 0;
        }
        for (int i = 0; i < AREA; i++) {
            int lx = i & 31;
            int lz = i >> 5;
            boolean edge = lx == 0 || lx == 31 || lz == 0 || lz == 31;
            boolean coastal = t.fixedMask[i] && (edge
                    || land(t, i + 1) || land(t, i - 1)
                    || land(t, i + TILE) || land(t, i - TILE));
            t.coast[i] = (byte) (coastal ? 1 : 0);
        }
    }

    private static boolean land(Tile t, int j) {
        return t.known[j] && !t.fixedMask[j];
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
            t.settled = false;
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
                    if (open || members.size() >= config.reservoirColumns) {
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

    double lastChangeRate() {
        return lastChangeRate;
    }

    /** Whether any surface water moves (an unsettled tile): lakes at rest and the open sea do not. */
    boolean moving() {
        for (Tile t : tiles.values()) if (!t.settled) return true;
        return false;
    }

    void step(double dt, Infiltration infiltration) {
        if (tiles.isEmpty()) return;
        // Wet tiles get their 4 neighbours (transient dry halo) so water can spread. (Tiles are
        // scanned in parallel; the lists are then built in key order.)
        List<Tile> existing = new ArrayList<>(tiles.values());
        parallel.forEach(existing, (idx, t) -> t.scan());
        List<Tile> wet = new ArrayList<>();
        for (Tile t : existing) if (t.wetScratch) wet.add(t);
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        java.util.Set<Long> halo = new java.util.HashSet<>();
        for (Tile t : wet) {
            for (int[] d : around) halo.add(key(t.tx + d[0], t.tz + d[1]));
        }
        // Dry tiles kept from the previous step (see the end of this method) stand in for the fresh
        // halo tiles this step would create: reset them as if new, and drop the ones no longer needed,
        // so the set of tiles and their state are exactly those of re-creating the halo every step.
        tiles.values().removeIf(t -> {
            if (t.wetScratch || t.fluxScratch) return false;
            long k = key(t.tx, t.tz);
            if (!halo.contains(k) || world.stacks().tileVersion(t.tx, t.tz) == 0) return true;
            t.settled = false;
            t.wake = false;
            return false;
        });
        if (tiles.isEmpty()) return;
        for (Tile t : wet) {
            for (int[] d : around) {
                int nx = t.tx + d[0];
                int nz = t.tz + d[1];
                if (!tiles.containsKey(key(nx, nz)) && world.stacks().tileVersion(nx, nz) != 0) tileOrCreate(nx, nz);
            }
        }
        // Per tile, in parallel: refresh reads the world model and writes only its own tile; the map
        // of tiles is not modified meanwhile.
        List<Tile> all = new ArrayList<>(tiles.values());
        parallel.forEach(all, (idx, t) -> {
            refresh(t);
            t.east = tiles.get(key(t.tx + 1, t.tz));
            t.west = tiles.get(key(t.tx - 1, t.tz));
            t.south = tiles.get(key(t.tx, t.tz + 1));
            t.north = tiles.get(key(t.tx, t.tz - 1));
            t.fixedFlowDepth = -1; // neighbours (land next to open water) may have changed
        });
        // Active tiles wake their neighbours, so every face next to moving water is computed by an
        // active tile; settled tiles (lakes at rest) are skipped by the flow solver.
        for (Tile t : tiles.values()) {
            if (t.settled) continue;
            for (Tile n : new Tile[] {t.east, t.west, t.south, t.north}) if (n != null) n.wake = true;
        }
        List<Tile> list = new ArrayList<>();
        for (Tile t : tiles.values()) {
            if (t.wake) t.settled = false;
            t.wake = false;
            if (!t.settled) list.add(t);
        }
        double dx = world.spec().metersPerColumn();
        // Routing covers at most surfaceWaterRoutingSeconds of a long step: shallow flows settle within
        // minutes, so a quiet day-long step keeps them quasi-steady without a day of CFL sub-steps.
        // Volumes stay exact: sources, infiltration and evaporation integrate over the whole step.
        boolean longStep = dt > config.surfaceWaterRoutingSeconds;
        double remaining = Math.min(dt, config.surfaceWaterRoutingSeconds);
        int guard = 0;
        // Open-water columns next to land have a fixed depth: their part of the CFL depth is cached
        // per tile; the moving water's part comes out of each substep's gather (phase 3).
        double maxDepth = Math.max(config.minFlowDepthM, maxFlowDepth(list));
        while (remaining > 1e-9 && guard++ < 10_000_000) {
            double stable = config.surfaceWaterCfl * dx / Math.sqrt(SubsurfaceGrid.GRAVITY * maxDepth);
            double h = Math.min(remaining, stable);
            maxDepth = Math.max(config.minFlowDepthM, substep(list, h, dx));
            remaining -= h;
            // quasi-steady within a long step: the rest of its routing would change no column by 1 cm
            if (longStep && lastChangeRate * remaining < STEADY_DEPTH_M) break;
        }
        lastSubsteps = guard;
        lastRoutedTiles = list.size();
        lastMaxDepth = maxDepth;
        parallel.forEach(list, (idx, t) -> {
            double maxQ = 0;
            for (int i = 0; i < AREA; i++) maxQ = Math.max(maxQ, Math.max(Math.abs(t.qEast[i]), Math.abs(t.qSouth[i])));
            if (maxQ < SETTLED_DISCHARGE) {
                java.util.Arrays.fill(t.qEast, 0);
                java.util.Arrays.fill(t.qSouth, 0);
                t.settled = true;
            }
        });
        double evaporation = config.evaporationMmPerHour / 1000.0 / 3600.0 * dt;
        double area = cellArea();
        // Infiltration capacity and evaporation per tile in parallel (capacity only reads the solver
        // grid); handing the water to the groundwater model and the budget totals stay sequential,
        // in tile and column order.
        parallel.forEach(all, (idx, t) -> {
            double infPart = 0;
            double evPart = 0;
            for (int i = 0; i < AREA; i++) {
                double d = t.depth[i];
                t.infiltratedScratch[i] = 0;
                if (d <= 0) continue;
                int x = t.tx * TILE + (i & 31);
                int z = t.tz * TILE + (i >> 5);
                double cap = infiltration.capacity(x, z) * dt;
                double inf = Math.min(d, cap);
                if (inf > 0) {
                    d -= inf;
                    infPart += inf * area;
                    t.infiltratedScratch[i] = inf * area;
                }
                double ev = Math.min(d, evaporation);
                d -= ev;
                evPart += ev * area;
                if (d < 1e-9) {
                    evPart += d * area; // negligible films dry up
                    d = 0;
                }
                t.depth[i] = d;
            }
            t.infiltratedPart = infPart;
            t.evaporatedPart = evPart;
        });
        for (Tile t : all) {
            infiltrated += t.infiltratedPart;
            evaporated += t.evaporatedPart;
            if (t.infiltratedPart == 0) continue;
            for (int i = 0; i < AREA; i++) {
                double v = t.infiltratedScratch[i];
                if (v > 0) infiltration.accept(t.tx * TILE + (i & 31), t.tz * TILE + (i >> 5), v);
            }
        }
        // Dry tiles next to water would be re-created (and re-read from the world) as halo tiles next
        // step; keep them instead (the start of the next step resets or drops them).
        List<Tile> after = new ArrayList<>(tiles.values());
        parallel.forEach(after, (idx, t) -> t.scan());
        tiles.values().removeIf(t -> !t.wetScratch && !t.fluxScratch && !nextToWater(t));
    }

    private boolean nextToWater(Tile t) {
        return wetTile(tiles.get(key(t.tx + 1, t.tz))) || wetTile(tiles.get(key(t.tx - 1, t.tz)))
                || wetTile(tiles.get(key(t.tx, t.tz + 1))) || wetTile(tiles.get(key(t.tx, t.tz - 1)));
    }

    /** Wet as of the last {@link Tile#scan} (removeIf only drops dry tiles, so wet ones are current). */
    private static boolean wetTile(Tile t) {
        return t != null && t.wetScratch;
    }

    /** Deepest water that can flow this substep: lake/river depths and open water next to land. */
    private double maxFlowDepth(List<Tile> list) {
        parallel.forEach(list, (idx, t) -> {
            double max = 0;
            for (int i = 0; i < AREA; i++) if (!t.fixedMask[i] && t.depth[i] > max) max = t.depth[i];
            t.maxDepthScratch = Math.max(max, fixedFlowDepth(t));
        });
        double max = 0; // exact: max does not depend on the order
        for (Tile t : list) max = Math.max(max, t.maxDepthScratch);
        return max;
    }

    /** Deepest open-water column of the tile next to land (cached for the current step). */
    private double fixedFlowDepth(Tile t) {
        if (t.fixedFlowDepth >= 0) return t.fixedFlowDepth;
        double max = 0;
        for (int i = 0; i < AREA; i++) {
            if (t.fixedMask[i] && hasLandNeighbour(t, i)) max = Math.max(max, t.level[i] - t.bed[i]);
        }
        t.fixedFlowDepth = max;
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

    /** Work items {tile index, first, end} over the tiles' active-cell lists (bounded for balance). */
    private static List<int[]> activeItems(List<Tile> list) {
        int grain = 256;
        List<int[]> items = new ArrayList<>();
        for (int t = 0; t < list.size(); t++) {
            int n = list.get(t).activeCount;
            for (int a = 0; a < n; a += grain) items.add(new int[] {t, a, Math.min(n, a + grain)});
        }
        return items;
    }

    /** Whether column {@code i} (local, may be one step outside the tile) seeds activity. */
    private static boolean seeds(Tile t, int lx, int lz) {
        Tile o = t;
        if (lx < 0) { o = t.west; lx += TILE; }
        else if (lx >= TILE) { o = t.east; lx -= TILE; }
        if (lz < 0) { o = o == null ? null : t.north; lz += TILE; }
        else if (lz >= TILE) { o = o == null ? null : t.south; lz -= TILE; }
        if (o == null) return false;
        int j = (lz << 5) | lx;
        return o.wet[j] != 0 || o.coast[j] != 0;
    }

    /**
     * Active cells of a tile for the next substep: columns holding water or flow, open water at a
     * coast, and their four neighbours (water moves at most one column per substep). Every other
     * column has zero discharges and dry neighbours, so the solver would compute nothing for it.
     */
    private static void collectActive(Tile t) {
        int n = 0;
        for (int i = 0; i < AREA; i++) {
            boolean active = t.wet[i] != 0 || t.coast[i] != 0;
            if (!active) {
                int lx = i & 31;
                int lz = i >> 5;
                active = (lx < 31 ? (t.wet[i + 1] | t.coast[i + 1]) != 0 : seeds(t, lx + 1, lz))
                        || (lx > 0 ? (t.wet[i - 1] | t.coast[i - 1]) != 0 : seeds(t, lx - 1, lz))
                        || (lz < 31 ? (t.wet[i + TILE] | t.coast[i + TILE]) != 0 : seeds(t, lx, lz + 1))
                        || (lz > 0 ? (t.wet[i - TILE] | t.coast[i - TILE]) != 0 : seeds(t, lx, lz - 1));
            }
            if (active) t.active[n++] = i;
        }
        t.activeCount = n;
    }

    /** One substep; returns the deepest water (CFL depth) after it. */
    private double substep(List<Tile> list, double dt, double dx) {
        double g = SubsurfaceGrid.GRAVITY;
        double n2 = config.manningN * config.manningN;
        double minDepth = config.minFlowDepthM;
        // Each phase writes only the columns of its item, so items run in parallel and the result
        // does not depend on the thread count. Only active columns are visited (see collectActive).
        parallel.forEach(list, (idx, t) -> collectActive(t));
        List<int[]> items = activeItems(list);
        // 1. Face discharges (east and south face of every active column). Reads only state no item writes.
        parallel.forEach(items, (idx, item) -> {
            Tile t = list.get(item[0]);
            int[] active = t.active;
            for (int a = item[1]; a < item[2]; a++) {
                int i = active[a];
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
        });
        // 2. Outflow limiter: scale the outgoing faces of each column so its depth stays ≥ 0 (only
        // columns that can send water: active ones).
        parallel.forEach(items, (idx, item) -> {
            Tile t = list.get(item[0]);
            int[] active = t.active;
            double[] f = t.limiter;
            for (int a = item[1]; a < item[2]; a++) {
                int i = active[a];
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
        });
        // 3. Every column gathers the water through its four faces. Both sides of a face compute the
        // same amount, so water is conserved exactly; open-water exchange is booked by the land side.
        // A column writes only its own depth (and activity flag) and reads only discharges and
        // limiters, so items run in parallel; their open-water books are folded in item order.
        double area = cellArea();
        double[][] sea = new double[items.size()][4]; // {inflow from, outflow to open water, max depth, max |dh/dt|}
        parallel.forEach(items, (idx, item) -> {
            Tile t = list.get(item[0]);
            int[] active = t.active;
            double[] book = sea[idx];
            double max = 0;
            double change = 0;
            for (int a = item[1]; a < item[2]; a++) {
                int i = active[a];
                if (!t.known[i] || t.fixedMask[i]) {
                    t.wet[i] = (byte) (t.qEast[i] != 0 || t.qSouth[i] != 0 ? 1 : 0);
                    continue;
                }
                int lx = i & 31;
                int lz = i >> 5;
                double delta = 0;
                // East and south faces: owned here, + = out of this column.
                Tile te = lx < 31 ? t : t.east;
                int je = lx < 31 ? i + 1 : i - 31;
                delta -= moved(t, i, te, je, t.qEast[i], dt, dx, area, book, true);
                Tile ts = lz < 31 ? t : t.south;
                int js = lz < 31 ? i + TILE : i - 31 * TILE;
                delta -= moved(t, i, ts, js, t.qSouth[i], dt, dx, area, book, true);
                // West and north faces: owned by the neighbour, + = into this column.
                Tile tw = lx > 0 ? t : t.west;
                int jw = lx > 0 ? i - 1 : i + 31;
                if (tw != null) delta += moved(tw, jw, t, i, tw.qEast[jw], dt, dx, area, book, false);
                Tile tn = lz > 0 ? t : t.north;
                int jn = lz > 0 ? i - TILE : i + 31 * TILE;
                if (tn != null) delta += moved(tn, jn, t, i, tn.qSouth[jn], dt, dx, area, book, false);
                double d = t.depth[i] + delta;
                d = d < 0 ? 0 : d;
                double rate = Math.abs(d - t.depth[i]) / dt;
                if (rate > change) change = rate;
                t.depth[i] = d;
                if (d > max) max = d;
                t.wet[i] = (byte) (d > 0 || t.qEast[i] != 0 || t.qSouth[i] != 0 ? 1 : 0);
            }
            book[2] = max;
            book[3] = change;
        });
        double max = 0;
        double change = 0;
        for (double[] book : sea) {
            seaInflow += book[0];
            seaOutflow += book[1];
            max = Math.max(max, book[2]);
            change = Math.max(change, book[3]);
        }
        lastChangeRate = change;
        for (Tile t : list) max = Math.max(max, fixedFlowDepth(t));
        return max;
    }

    /**
     * Depth (m over one column) moved through the face from {@code (a, i)} to {@code (b, j)} this
     * substep ({@code q} owned by {@code a}). When the far side is open water, the exchange is
     * booked: {@code book[0]} inflow from it, {@code book[1]} outflow to it.
     */
    private static double moved(Tile a, int i, Tile b, int j, double q, double dt, double dx, double area,
            double[] book, boolean landIsA) {
        if (q == 0 || b == null) return 0;
        double scale = q > 0 ? a.limiter[i] : b.limiter[j];
        double depth = q * scale * dt / dx; // + = from a to b
        Tile open = landIsA ? b : a;
        int k = landIsA ? j : i;
        if (open.fixed(k)) {
            double volume = depth * area; // + = a → b
            double towardsOpen = landIsA ? volume : -volume;
            if (towardsOpen > 0) book[1] += towardsOpen;
            else book[0] -= towardsOpen;
        }
        return depth;
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
        // hf^(7/3) as hf²·∛hf: same value to rounding, several times cheaper than pow
        return (q - g * hf * dt * slope) / (1 + g * dt * n2 * Math.abs(q) / (hf * hf * Math.cbrt(hf)));
    }
}
