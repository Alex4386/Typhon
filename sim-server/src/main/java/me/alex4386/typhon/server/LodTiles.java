package me.alex4386.typhon.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.world.SurfaceDetail;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.server.protocol.Field;
import me.alex4386.typhon.simulator.scenario.Scenario;
import me.alex4386.typhon.simulator.terrain.ContextTerrain;

/**
 * The tile pyramid around the core's columns (docs/protocol.md §5.5): coarse levels {@code ℓ > 0}
 * (cells of {@code 2^ℓ} columns) reaching out over the {@link ContextTerrain}, and fine levels
 * {@code ℓ < 0} (cells of {@code 2^ℓ} columns) over each volcano's {@link SurfaceDetail}.
 *
 * <p>Every level shares the core's origin (the session's fixed anchor, {@link GridMapping#anchorX}) and
 * tile size, so level-ℓ cell {@code (i, j)} covers
 * {@code x ∈ origin.x + [i·c, (i+1)·c)}, {@code y ∈ origin.y + [j·c, (j+1)·c)} with
 * {@code c = cellSize·2^ℓ}, and tile {@code (tx, ty)} holds cells {@code i = tx·T + col},
 * {@code j = ty·T + row}. Coarse values are conservative: inside the core a coarse cell is the mean
 * of its columns (volume per area is preserved), outside it the context's mean. Fine elevations
 * average to their column, so levels meet without seams.
 */
final class LodTiles {
    /** Fields offered on coarse levels: all are volumes per area or elevations, so means are exact. */
    static final Set<Field> COARSE_FIELDS = EnumSet.of(Field.SURFACE_ELEVATION, Field.LAVA_DEPTH, Field.PDC_DEPTH,
            Field.LAHAR_DEPTH, Field.ASH_DEPTH, Field.UPLIFT);
    /** Fields offered on fine levels (the rest are column properties: use level 0). */
    static final Set<Field> FINE_FIELDS = EnumSet.of(Field.SURFACE_ELEVATION);

    /** One level of the pyramid. */
    static final class Level {
        final int level;
        /** Columns per cell ({@code 2^level}). */
        final double factor;
        final int minI;
        final int minJ;
        final int maxI;
        final int maxJ;
        final Set<Field> fields;
        final List<SurfaceDetail> details;
        final TileStore store;
        final int[] order;
        /** Context mean elevation per cell for cells reaching outside the core (coarse levels). */
        float[] contextElevation;

        Level(int level, int minI, int minJ, int maxI, int maxJ, Set<Field> fields, List<SurfaceDetail> details,
                GridMapping map, long base, double[] focus) {
            this.level = level;
            this.factor = Math.scalb(1.0, level);
            int t = map.tileSize;
            int minTx = Math.floorDiv(minI, t);
            int minTy = Math.floorDiv(minJ, t);
            int tilesX = Math.floorDiv(maxI, t) - minTx + 1;
            int tilesY = Math.floorDiv(maxJ, t) - minTy + 1;
            this.minI = minTx * t;
            this.minJ = minTy * t;
            this.maxI = (minTx + tilesX) * t - 1;
            this.maxJ = (minTy + tilesY) * t - 1;
            this.fields = fields;
            this.details = details;
            this.store = new TileStore(map, base, level, minTx, minTy, tilesX, tilesY);
            this.order = order(map, focus);
        }

        int width() {
            return maxI - minI + 1;
        }

        int height() {
            return maxJ - minJ + 1;
        }

        /** Tiles nearest the focus point (protocol metres) first. */
        private int[] order(GridMapping map, double[] focus) {
            int n = store.tileCount();
            Integer[] idx = new Integer[n];
            double[] dist = new double[n];
            double span = map.tileSize * map.cell * factor;
            for (int i = 0; i < n; i++) {
                idx[i] = i;
                double cx = map.originX() + (store.minTx() + i % store.tilesX() + 0.5) * span;
                double cy = map.originY() + (store.minTy() + i / store.tilesX() + 0.5) * span;
                dist[i] = Math.hypot(cx - focus[0], cy - focus[1]);
            }
            java.util.Arrays.sort(idx, Comparator.comparingDouble(i -> dist[i]));
            int[] out = new int[n];
            for (int i = 0; i < n; i++) out[i] = idx[i];
            return out;
        }

        /** Fractional column x of the west edge of cell column {@code i}. */
        double columnX(GridMapping map, double i) {
            return map.anchorX + i * factor;
        }

        /** Fractional column z of the north edge (smallest z) of cell row {@code j}. */
        double columnZ(GridMapping map, double j) {
            return map.anchorMaxZ + 1 - (j + 1) * factor;
        }
    }

    private final GridMapping map;
    private final ContextTerrain context;
    private final Map<Integer, Level> levels = new TreeMap<>();

    LodTiles(Scenario scenario, GridMapping map, long base) {
        this.map = map;
        this.context = scenario.context();
        double[] centre = {map.x(context.centerX() - 0.5), map.y(context.centerZ() - 0.5)};
        int t = map.tileSize;
        for (int level = 1; level <= context.levels(); level++) {
            double f = Math.scalb(1.0, level);
            double h = context.levelHalfColumns(level);
            int minI = (int) Math.floor((context.centerX() - h - map.anchorX) / f);
            int maxI = (int) Math.ceil((context.centerX() + h - map.anchorX) / f) - 1;
            int minJ = (int) Math.floor((map.anchorMaxZ + 1 - (context.centerZ() + h)) / f);
            int maxJ = (int) Math.ceil((map.anchorMaxZ + 1 - (context.centerZ() - h)) / f) - 1;
            Level l = new Level(level, minI, minJ, maxI, maxJ, COARSE_FIELDS, List.of(), map, base, centre);
            l.contextElevation = contextElevation(l);
            levels.put(level, l);
        }
        Map<Integer, List<SurfaceDetail>> fine = new TreeMap<>();
        for (VolcanoSystem v : scenario.volcanoes()) {
            SurfaceDetail d = v.surfaceDetail();
            if (d == null || d.refinement() < 2) continue;
            int level = -Integer.numberOfTrailingZeros(d.refinement());
            fine.computeIfAbsent(level, k -> new ArrayList<>()).add(d);
        }
        for (Map.Entry<Integer, List<SurfaceDetail>> e : fine.entrySet()) {
            int level = e.getKey();
            int r = 1 << -level;
            int minI = Integer.MAX_VALUE;
            int minJ = Integer.MAX_VALUE;
            int maxI = Integer.MIN_VALUE;
            int maxJ = Integer.MIN_VALUE;
            double[] focus = null;
            for (SurfaceDetail d : e.getValue()) {
                minI = Math.min(minI, (d.minColumnX() - map.anchorX) * r);
                maxI = Math.max(maxI, (d.minColumnX() + d.columns() - map.anchorX) * r - 1);
                minJ = Math.min(minJ, (map.anchorMaxZ + 1 - (d.minColumnZ() + d.columns())) * r);
                maxJ = Math.max(maxJ, (map.anchorMaxZ + 1 - d.minColumnZ()) * r - 1);
                if (focus == null) {
                    focus = new double[] {map.x(d.minColumnX() + d.columns() / 2.0 - 0.5),
                            map.y(d.minColumnZ() + d.columns() / 2.0 - 0.5)};
                }
            }
            levels.put(level, new Level(level, minI, minJ, maxI, maxJ, FINE_FIELDS, List.copyOf(e.getValue()), map, base,
                    focus));
        }
    }

    Map<Integer, Level> levels() {
        return levels;
    }

    Level level(int level) {
        return levels.get(level);
    }

    /** The {@code lod} block of {@code WorldInfo} (§5.5). */
    JsonObject info() {
        JsonObject o = new JsonObject();
        JsonArray list = new JsonArray();
        for (Level l : levels.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("level", l.level);
            j.add("cellSize", Json.num(map.cell * l.factor));
            JsonObject tiles = new JsonObject();
            tiles.addProperty("minTx", l.store.minTx());
            tiles.addProperty("minTy", l.store.minTy());
            tiles.addProperty("maxTx", l.store.minTx() + l.store.tilesX() - 1);
            tiles.addProperty("maxTy", l.store.minTy() + l.store.tilesY() - 1);
            j.add("tiles", tiles);
            JsonArray fields = new JsonArray();
            for (Field f : l.fields) fields.add(f.id);
            j.add("fields", fields);
            j.addProperty("kind", l.level > 0 ? "context" : "detail");
            list.add(j);
        }
        o.add("levels", list);
        double half = context.halfExtentColumns() * map.cell;
        double cx = map.x(context.centerX() - 0.5);
        double cy = map.y(context.centerZ() - 0.5);
        JsonArray extent = new JsonArray();
        extent.add(Json.num(cx - half));
        extent.add(Json.num(cy - half));
        extent.add(Json.num(cx + half));
        extent.add(Json.num(cy + half));
        o.add("extent", extent);
        return o;
    }

    // ── Sampling ──

    /** Static context elevation (mean over each cell's outside-core part; NaN for cells inside the core). */
    private float[] contextElevation(Level l) {
        int w = l.width();
        float[] out = new float[w * l.height()];
        int n = (int) l.factor;
        for (int j = 0; j < l.height(); j++) {
            for (int i = 0; i < w; i++) {
                int x0 = (int) l.columnX(map, l.minI + i);
                int z0 = (int) l.columnZ(map, l.minJ + j);
                boolean inside = x0 >= map.minX && x0 + n - 1 <= map.maxX && z0 >= map.minZ && z0 + n - 1 <= map.maxZ;
                out[j * w + i] = inside ? Float.NaN : (float) context.meanElevation(x0, z0, n);
            }
        }
        return out;
    }

    /** What {@link #sampleEngine} read from the live simulation for one refresh. */
    record EngineSample(Map<Integer, float[][]> fineElevation, Map<Integer, float[]> ashOutside) {}

    /**
     * The part of a refresh that reads live state, so it runs on the engine thread: fine-level
     * elevations (when {@code SurfaceElevation} is due) and the tephra deposit at coarse cells
     * reaching outside the core (when {@code AshDepth} is due). Cheap: one detail read per column.
     */
    EngineSample sampleEngine(Scenario s, Set<Field> due, Set<Integer> wanted) {
        Map<Integer, float[][]> fine = new TreeMap<>();
        Map<Integer, float[]> ash = new TreeMap<>();
        for (int level : wanted) {
            Level l = levels.get(level);
            if (l == null) continue;
            if (l.level < 0 && due.contains(Field.SURFACE_ELEVATION)) fine.put(level, fine(s, l));
            if (l.level > 0 && due.contains(Field.ASH_DEPTH)) ash.put(level, ashOutside(s, l));
        }
        return new EngineSample(fine, ash);
    }

    /**
     * Builds the subscribed levels' tiles from the level-0 tiles of this refresh ({@code core}) and
     * the engine sample. Pure arithmetic: runs off the engine thread.
     */
    Map<Integer, Map<Field, float[][]>> assemble(Map<Field, float[][]> core, EngineSample live, Set<Integer> wanted) {
        Map<Integer, Map<Field, float[][]>> out = new TreeMap<>();
        Map<Field, double[]> sat = new EnumMap<>(Field.class);
        for (int level : wanted) {
            Level l = levels.get(level);
            if (l == null) continue;
            Map<Field, float[][]> values = new EnumMap<>(Field.class);
            if (l.level < 0) {
                float[][] e = live.fineElevation().get(level);
                if (e != null) values.put(Field.SURFACE_ELEVATION, e);
            } else {
                for (Map.Entry<Field, float[][]> e : core.entrySet()) {
                    Field f = e.getKey();
                    if (!l.fields.contains(f)) continue;
                    double[] table = sat.computeIfAbsent(f, k -> summedArea(e.getValue()));
                    values.put(f, coarse(l, f, table, live.ashOutside().get(level)));
                }
            }
            if (!values.isEmpty()) out.put(level, values);
        }
        return out;
    }

    /** Summed-area table of a level-0 field over the core's columns, (W+1)×(H+1), rows by z. */
    private double[] summedArea(float[][] tiles) {
        int w = map.maxX - map.minX + 1;
        int h = map.maxZ - map.minZ + 1;
        int t = map.tileSize;
        double[] sat = new double[(w + 1) * (h + 1)];
        for (int zz = 0; zz < h; zz++) {
            int cz = map.minZ + zz;
            int row = map.maxZ - cz;
            int ty = row / t;
            int r = row % t;
            double run = 0;
            for (int xx = 0; xx < w; xx++) {
                int tx = xx / t;
                int c = xx % t;
                run += tiles[ty * map.tilesX + tx][r * t + c];
                sat[(zz + 1) * (w + 1) + xx + 1] = sat[zz * (w + 1) + xx + 1] + run;
            }
        }
        return sat;
    }

    /** Tephra deposit (m) at the centre of every cell of a coarse level that reaches outside the core. */
    private float[] ashOutside(Scenario s, Level l) {
        int w = l.width();
        int n = (int) l.factor;
        float[] out = new float[w * l.height()];
        List<VolcanoSystem> volcanoes = s.volcanoes();
        for (int j = 0; j < l.height(); j++) {
            for (int i = 0; i < w; i++) {
                if (Float.isNaN(l.contextElevation[j * w + i])) continue; // inside the core
                int cx = (int) l.columnX(map, l.minI + i) + n / 2;
                int cz = (int) l.columnZ(map, l.minJ + j) + n / 2;
                double d = 0;
                for (VolcanoSystem v : volcanoes) d += v.tephra().depositThickness(cx, cz);
                out[j * w + i] = (float) d;
            }
        }
        return out;
    }

    private float[][] coarse(Level l, Field f, double[] sat, float[] ashOutside) {
        int t = map.tileSize;
        int w = map.maxX - map.minX + 1;
        int n = (int) l.factor;
        double area = (double) n * n;
        float[][] tiles = new float[l.store.tileCount()][];
        for (int k = 0; k < tiles.length; k++) {
            int tx = k % l.store.tilesX();
            int ty = k / l.store.tilesX();
            float[] v = new float[t * t];
            for (int r = 0; r < t; r++) {
                for (int c = 0; c < t; c++) {
                    int i = tx * t + c;
                    int j = ty * t + r;
                    int x0 = (int) l.columnX(map, l.minI + i);
                    int z0 = (int) l.columnZ(map, l.minJ + j);
                    int ax = Math.max(x0, map.minX);
                    int bx = Math.min(x0 + n - 1, map.maxX);
                    int az = Math.max(z0, map.minZ);
                    int bz = Math.min(z0 + n - 1, map.maxZ);
                    double inside = 0;
                    int count = 0;
                    if (ax <= bx && az <= bz) {
                        int x1 = ax - map.minX;
                        int x2 = bx - map.minX + 1;
                        int z1 = az - map.minZ;
                        int z2 = bz - map.minZ + 1;
                        inside = sat[z2 * (w + 1) + x2] - sat[z1 * (w + 1) + x2] - sat[z2 * (w + 1) + x1]
                                + sat[z1 * (w + 1) + x1];
                        count = (x2 - x1) * (z2 - z1);
                    }
                    double outside = 0;
                    if (count < area) {
                        int cell = j * l.width() + i;
                        if (f == Field.SURFACE_ELEVATION) outside = l.contextElevation[cell];
                        else if (f == Field.ASH_DEPTH && ashOutside != null) outside = ashOutside[cell];
                    }
                    v[r * t + c] = (float) ((inside + outside * (area - count)) / area);
                }
            }
            tiles[k] = v;
        }
        return tiles;
    }

    /** Fine-level elevations, column by column: one surface and detail read per column. */
    private float[][] fine(Scenario s, Level l) {
        WorldModel world = s.terrain().world();
        int t = map.tileSize;
        int r = (int) Math.round(1 / l.factor);
        int w = l.width();
        float[] all = new float[w * l.height()];
        float[] cells = new float[r * r];
        int colX0 = Math.floorDiv(map.anchorX * r + l.minI, r);
        int colX1 = Math.floorDiv(map.anchorX * r + l.maxI, r);
        int colZ0 = Math.floorDiv((map.anchorMaxZ + 1) * r - l.maxJ - 1, r);
        int colZ1 = Math.floorDiv((map.anchorMaxZ + 1) * r - l.minJ - 1, r);
        for (int z = colZ0; z <= colZ1; z++) {
            for (int x = colX0; x <= colX1; x++) {
                double surface = world.surfaceZ(x, z);
                boolean detailed = false;
                for (SurfaceDetail d : l.details) {
                    if (d.containsColumn(x, z)) {
                        d.columnCells(x, z, cells);
                        detailed = true;
                        break;
                    }
                }
                double base = Double.isNaN(surface) ? context.elevation(x + 0.5, z + 0.5) : surface;
                for (int b = 0; b < r; b++) {
                    for (int a = 0; a < r; a++) {
                        int i = x * r + a - map.anchorX * r - l.minI;
                        int j = (map.anchorMaxZ + 1) * r - (z * r + b) - 1 - l.minJ;
                        if (i < 0 || i >= w || j < 0 || j >= l.height()) continue;
                        double e = detailed && !Float.isNaN(cells[b * r + a]) ? cells[b * r + a] : base;
                        all[j * w + i] = (float) e;
                    }
                }
            }
        }
        float[][] tiles = new float[l.store.tileCount()][];
        for (int k = 0; k < tiles.length; k++) {
            int tx = k % l.store.tilesX();
            int ty = k / l.store.tilesX();
            float[] v = new float[t * t];
            for (int row = 0; row < t; row++) System.arraycopy(all, (ty * t + row) * w + tx * t, v, row * t, t);
            tiles[k] = v;
        }
        return tiles;
    }
}
