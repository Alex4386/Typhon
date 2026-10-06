package me.alex4386.typhon.engine.world;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Stratigraphic column stacks: for every known column, the layers from the datum up to the ground
 * surface, bottom first. Each layer reaches from the previous layer's top (or the datum) to its own
 * {@code top}, so layers are contiguous and the last layer's top is the surface.
 *
 * <p>Storage is sparse 32×32-column tiles in compressed-sparse-row form: per tile, one
 * struct-of-arrays layer list and a {@code start} offset per column. Edits shift the tile's tail,
 * which is cheap at a few layers per column. Columns hold at most {@link #MAX_LAYERS} layers; beyond
 * that the thinnest adjacent pair of the same class is merged (the thicker one's identity wins).
 *
 * <p>Coordinates are integer column indices; elevations are real metres. Columns with no layers are
 * "unknown" (no data yet).
 */
public final class ColumnStacks {
    public static final int TILE = 32;
    public static final int TILE_AREA = TILE * TILE;
    public static final int MAX_LAYERS = 48;
    /** Thinnest layer the stacks keep (m); thinner remainders are folded into neighbours. */
    static final double EPS = 1e-4;
    /**
     * Loose deposits thinner than this landing on a loose top layer that is itself thinner join that layer
     * instead of starting a new one (m).
     */
    public static final double THIN_LAYER_M = 0.05;

    /** Minimum thickness erosion leaves in the bottom layer so a column never disappears. */
    static final double MIN_BOTTOM = 1e-3;

    private final double datumZ;
    private final Map<Long, Tile> tiles = new HashMap<>();

    public ColumnStacks(double datumZ) {
        this.datumZ = datumZ;
    }

    public double datumZ() {
        return datumZ;
    }

    // ── Tiles ──

    static final class Tile {
        final int tx;
        final int tz;
        final int[] start = new int[TILE_AREA + 1];
        float[] top = new float[64];
        short[] material = new short[64];
        int[] unit = new int[64];
        byte[] porosity = new byte[64];
        byte[] voidFrac = new byte[64];
        byte[] welding = new byte[64];
        byte[] flags = new byte[64];
        final float[] uplift = new float[TILE_AREA];
        final float[] water = new float[TILE_AREA];
        final int[] version = new int[TILE_AREA];

        Tile(int tx, int tz) {
            this.tx = tx;
            this.tz = tz;
            Arrays.fill(water, Float.NaN);
        }

        int size() {
            return start[TILE_AREA];
        }

        int count(int c) {
            return start[c + 1] - start[c];
        }

        void ensure(int needed) {
            if (needed <= top.length) return;
            int capacity = Math.max(needed, top.length * 2);
            top = Arrays.copyOf(top, capacity);
            material = Arrays.copyOf(material, capacity);
            unit = Arrays.copyOf(unit, capacity);
            porosity = Arrays.copyOf(porosity, capacity);
            voidFrac = Arrays.copyOf(voidFrac, capacity);
            welding = Arrays.copyOf(welding, capacity);
            flags = Arrays.copyOf(flags, capacity);
        }

        /** Opens {@code n} layer slots at global index {@code at} inside column {@code c}. */
        void insert(int c, int at, int n) {
            int size = size();
            ensure(size + n);
            int tail = size - at;
            System.arraycopy(top, at, top, at + n, tail);
            System.arraycopy(material, at, material, at + n, tail);
            System.arraycopy(unit, at, unit, at + n, tail);
            System.arraycopy(porosity, at, porosity, at + n, tail);
            System.arraycopy(voidFrac, at, voidFrac, at + n, tail);
            System.arraycopy(welding, at, welding, at + n, tail);
            System.arraycopy(flags, at, flags, at + n, tail);
            for (int k = c + 1; k <= TILE_AREA; k++) start[k] += n;
        }

        /** Removes {@code n} layers at global index {@code at} of column {@code c}. */
        void remove(int c, int at, int n) {
            int size = size();
            int tail = size - at - n;
            System.arraycopy(top, at + n, top, at, tail);
            System.arraycopy(material, at + n, material, at, tail);
            System.arraycopy(unit, at + n, unit, at, tail);
            System.arraycopy(porosity, at + n, porosity, at, tail);
            System.arraycopy(voidFrac, at + n, voidFrac, at, tail);
            System.arraycopy(welding, at + n, welding, at, tail);
            System.arraycopy(flags, at + n, flags, at, tail);
            for (int k = c + 1; k <= TILE_AREA; k++) start[k] -= n;
        }

        void set(int g, double topZ, short mat, int u, byte por, byte vf, byte weld, byte fl) {
            top[g] = (float) topZ;
            material[g] = mat;
            unit[g] = u;
            porosity[g] = por;
            voidFrac[g] = vf;
            welding[g] = weld;
            flags[g] = fl;
        }

        void copy(int from, int to) {
            set(to, top[from], material[from], unit[from], porosity[from], voidFrac[from], welding[from], flags[from]);
        }

        boolean sameIdentity(int a, int b) {
            return material[a] == material[b] && unit[a] == unit[b] && porosity[a] == porosity[b]
                    && voidFrac[a] == voidFrac[b] && welding[a] == welding[b] && flags[a] == flags[b];
        }
    }

    /** Packed tile coordinates; also the persistence order of tiles. */
    private long editCount;

    static long pack(int tx, int tz) {
        return ((long) tx << 32) | (tz & 0xffffffffL);
    }

    /**
     * Map key of a tile: the packed coordinates through a bijective mixer. {@code Long.hashCode} of the
     * plain packed value is {@code tx ^ tz}, which collides for every tile on a diagonal; on km-wide
     * worlds HashMap bins then degrade into trees and lookups dominate profiles.
     */
    static long key(int tx, int tz) {
        long z = pack(tx, tz);
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    static int tileCoord(int c) {
        return Math.floorDiv(c, TILE);
    }

    static int local(int x, int z) {
        return (Math.floorMod(z, TILE) << 5) | Math.floorMod(x, TILE);
    }

    Tile tile(int x, int z) {
        return tiles.get(key(tileCoord(x), tileCoord(z)));
    }

    private Tile tileOrCreate(int x, int z) {
        int tx = tileCoord(x);
        int tz = tileCoord(z);
        return tiles.computeIfAbsent(key(tx, tz), k -> {
            editCount++;
            return new Tile(tx, tz);
        });
    }

    /** Tiles sorted by key (deterministic iteration for persistence). */
    List<Tile> sortedTiles() {
        List<Tile> list = new ArrayList<>(tiles.values());
        list.sort(java.util.Comparator.comparingLong(t -> pack(t.tx, t.tz)));
        return list;
    }

    void putTile(Tile tile) {
        tiles.put(key(tile.tx, tile.tz), tile);
        editCount++;
    }

    void clear() {
        tiles.clear();
        editCount++;
    }

    /**
     * Counter that changes whenever any column or tile changes (not persisted): lets derived caches
     * skip a whole-world scan when nothing changed since they last looked.
     */
    public long editCount() {
        return editCount;
    }

    public int tileCount() {
        return tiles.size();
    }

    /**
     * Packed coordinates of all tiles, sorted (decode with {@link #keyTileX}/{@link #keyTileZ}). These
     * are the plain packed values, not the mixed map keys.
     */
    public long[] tileKeys() {
        long[] keys = new long[tiles.size()];
        int i = 0;
        for (Tile t : tiles.values()) keys[i++] = pack(t.tx, t.tz);
        Arrays.sort(keys);
        return keys;
    }

    public static int keyTileX(long key) {
        return (int) (key >> 32);
    }

    public static int keyTileZ(long key) {
        return (int) key;
    }

    /**
     * Sum of the edit counters of every column in a tile (0 if absent): changes whenever any column
     * of the tile is edited, so derived caches can be refreshed per tile.
     */
    public long tileVersion(int tileX, int tileZ) {
        Tile t = tiles.get(key(tileX, tileZ));
        if (t == null) return 0;
        long sum = t.size();
        for (int v : t.version) sum += v;
        return sum;
    }

    static byte quantize(double fraction) {
        return (byte) Math.round(Math.max(0, Math.min(1, fraction)) * 255);
    }

    static double dequantize(byte b) {
        return (b & 0xff) / 255.0;
    }

    private double bottom(Tile t, int c, int g) {
        return g == t.start[c] ? datumZ : t.top[g - 1];
    }

    // ── Queries ──

    public boolean known(int x, int z) {
        Tile t = tile(x, z);
        return t != null && t.count(local(x, z)) > 0;
    }

    public int count(int x, int z) {
        Tile t = tile(x, z);
        return t == null ? 0 : t.count(local(x, z));
    }

    /** Ground surface elevation (top of the last layer), {@code NaN} if unknown. */
    public double surface(int x, int z) {
        Tile t = tile(x, z);
        if (t == null) return Double.NaN;
        int c = local(x, z);
        return t.count(c) == 0 ? Double.NaN : t.top[t.start[c + 1] - 1];
    }

    /** Layer {@code i} (0 = bottom) of a column. */
    public LayerView layer(int x, int z, int i) {
        Tile t = tile(x, z);
        int c = local(x, z);
        if (t == null || i < 0 || i >= t.count(c)) throw new IndexOutOfBoundsException("layer " + i);
        int g = t.start[c] + i;
        return new LayerView(bottom(t, c, g), t.top[g], t.material[g], t.unit[g], dequantize(t.porosity[g]),
                dequantize(t.voidFrac[g]), dequantize(t.welding[g]), t.flags[g]);
    }

    public List<LayerView> layers(int x, int z) {
        int n = count(x, z);
        List<LayerView> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) list.add(layer(x, z, i));
        return list;
    }

    /**
     * Index (0 = bottom) of the layer containing {@code elevation}: {@code -1} at or above the surface
     * or in an unknown column; layer 0 below the datum. Binary search on layer tops.
     */
    public int layerIndexAt(int x, int z, double elevation) {
        Tile t = tile(x, z);
        if (t == null) return -1;
        int c = local(x, z);
        int s = t.start[c];
        int e = t.start[c + 1];
        if (e == s || elevation >= t.top[e - 1]) return -1;
        int lo = s;
        int hi = e - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (t.top[mid] > elevation) hi = mid;
            else lo = mid + 1;
        }
        return lo - s;
    }

    public int version(int x, int z) {
        Tile t = tile(x, z);
        return t == null ? 0 : t.version[local(x, z)];
    }

    /**
     * Sum of the change counters of a rectangle of columns: a cheap way for per-chunk caches (16×16
     * surface-flow chunks) to notice that any column under them was edited.
     */
    public long versionSum(int x0, int z0, int width, int depth) {
        long sum = 0;
        Tile t = null;
        long tileKey = Long.MIN_VALUE;
        for (int z = z0; z < z0 + depth; z++) {
            for (int x = x0; x < x0 + width; x++) {
                long k = key(tileCoord(x), tileCoord(z));
                if (k != tileKey) {
                    t = tiles.get(k);
                    tileKey = k;
                }
                if (t != null) sum += t.version[local(x, z)];
            }
        }
        return sum;
    }

    /** Coordinates {@code {tx, tz}} of every tile, sorted by key (deterministic). */
    public List<int[]> tileCoords() {
        List<int[]> list = new ArrayList<>();
        for (Tile t : sortedTiles()) list.add(new int[] {t.tx, t.tz});
        return list;
    }

    /**
     * Thickness (m) of the unconsolidated ({@link LayerFlags#LOOSE}) layers at the top of a column,
     * down to the first consolidated layer or cavity; 0 for unknown columns.
     */
    public double looseTopThickness(int x, int z) {
        Tile t = tile(x, z);
        if (t == null) return 0;
        int c = local(x, z);
        int s = t.start[c];
        double thickness = 0;
        for (int g = t.start[c + 1] - 1; g > s; g--) {
            if ((t.flags[g] & LayerFlags.LOOSE) == 0 || t.material[g] == MaterialTable.VOID.id()) break;
            thickness += t.top[g] - t.top[g - 1];
        }
        return thickness;
    }

    public double uplift(int x, int z) {
        Tile t = tile(x, z);
        return t == null ? 0 : t.uplift[local(x, z)];
    }

    public void setUplift(int x, int z, double meters) {
        Tile t = tileOrCreate(x, z);
        int c = local(x, z);
        t.uplift[c] = (float) meters;
        t.version[c]++;
        editCount++;
    }

    /** Standing-water surface elevation, {@code NaN} if dry or unknown. */
    public double water(int x, int z) {
        Tile t = tile(x, z);
        return t == null ? Double.NaN : t.water[local(x, z)];
    }

    public void setWater(int x, int z, double elevation) {
        Tile t = tileOrCreate(x, z);
        t.water[local(x, z)] = (float) elevation;
    }

    // ── Edits ──

    /** One layer for {@link #setLayers}: it reaches from the previous top (or datum) to {@code top}. */
    public record LayerSpec(double top, short material, int unit, double porosity, double welding, int flags) {}

    /** Replaces a whole column (import). Tops must increase and lie above the datum. */
    public void setLayers(int x, int z, List<LayerSpec> layers) {
        Tile t = tileOrCreate(x, z);
        int c = local(x, z);
        int existing = t.count(c);
        if (existing > 0) t.remove(c, t.start[c], existing);
        double previous = datumZ;
        List<LayerSpec> kept = new ArrayList<>();
        for (LayerSpec layer : layers) {
            if (layer.top() - previous < EPS) continue;
            kept.add(layer);
            previous = layer.top();
        }
        t.insert(c, t.start[c], kept.size());
        int g = t.start[c];
        for (LayerSpec layer : kept) {
            t.set(g++, layer.top(), layer.material(), layer.unit(), quantize(layer.porosity()), (byte) 0,
                    quantize(layer.welding()), (byte) layer.flags());
        }
        enforceCap(t, c);
        t.version[c]++;
        editCount++;
    }

    /**
     * Replaces many columns of one tile at once in a single pass over the tile (bulk import; avoids
     * shifting the tile tail once per column). {@code replacements} maps local column index (see
     * {@link #local}) to its new layers.
     */
    public void setLayersBatch(int tileX, int tileZ, Map<Integer, List<LayerSpec>> replacements) {
        Tile t = tiles.computeIfAbsent(key(tileX, tileZ), k -> new Tile(tileX, tileZ));
        int total = 0;
        for (int c = 0; c < TILE_AREA; c++) {
            List<LayerSpec> r = replacements.get(c);
            total += r != null ? r.size() : t.count(c);
        }
        Tile fresh = new Tile(tileX, tileZ);
        fresh.ensure(Math.max(64, total));
        int pos = 0;
        for (int c = 0; c < TILE_AREA; c++) {
            fresh.start[c] = pos;
            List<LayerSpec> r = replacements.get(c);
            if (r == null) {
                for (int g = t.start[c]; g < t.start[c + 1]; g++) {
                    fresh.set(pos++, t.top[g], t.material[g], t.unit[g], t.porosity[g], t.voidFrac[g], t.welding[g],
                            t.flags[g]);
                }
            } else {
                double previous = datumZ;
                for (LayerSpec layer : r) {
                    if (layer.top() - previous < EPS) continue;
                    fresh.set(pos++, layer.top(), layer.material(), layer.unit(), quantize(layer.porosity()), (byte) 0,
                            quantize(layer.welding()), (byte) layer.flags());
                    previous = layer.top();
                }
            }
            fresh.uplift[c] = t.uplift[c];
            fresh.water[c] = t.water[c];
            fresh.version[c] = t.version[c] + (r != null ? 1 : 0);
        }
        fresh.start[TILE_AREA] = pos;
        for (int c : replacements.keySet()) enforceCap(fresh, c);
        tiles.put(key(tileX, tileZ), fresh);
        editCount++;
    }

    /** Local index of column {@code (x, z)} inside its tile. */
    public static int localIndex(int x, int z) {
        return local(x, z);
    }

    /** Tile coordinate of column coordinate {@code c}. */
    public static int tileOf(int c) {
        return tileCoord(c);
    }

    /**
     * Adds {@code thickness} metres on top of a known column. Extends the top layer when it has the
     * same identity (material, unit, flags, porosity, welding), otherwise pushes a new layer.
     *
     * @return {@code false} if the column is unknown or the thickness is not positive
     */
    public boolean deposit(int x, int z, double thickness, short material, int unit, int flags, double porosity,
            double welding) {
        if (!(thickness > 0)) return false;
        Tile t = tile(x, z);
        if (t == null) return false;
        int c = local(x, z);
        if (t.count(c) == 0) return false;
        int g = t.start[c + 1] - 1;
        byte por = quantize(porosity);
        byte weld = quantize(welding);
        boolean thinLoose = thickness < THIN_LAYER_M && (flags & LayerFlags.LOOSE) != 0
                && (t.flags[g] & LayerFlags.LOOSE) != 0 && t.material[g] != MaterialTable.VOID.id()
                && material != MaterialTable.VOID.id() && t.voidFrac[g] == 0 && g > t.start[c]
                && t.top[g] - bottom(t, c, g) < THIN_LAYER_M;
        if (material != MaterialTable.VOID.id() && t.material[g] == material && t.unit[g] == unit
                && t.flags[g] == (byte) flags && t.porosity[g] == por && t.welding[g] == weld && t.voidFrac[g] == 0) {
            t.top[g] = (float) (t.top[g] + thickness);
        } else if (thinLoose) {
            // a dusting on a dusting (ash between bomb clasts, lapilli on ash): one thin loose layer, not a
            // stack of near-zero layers
            t.top[g] = (float) (t.top[g] + thickness);
        } else {
            double newTop = t.top[g] + thickness;
            t.insert(c, g + 1, 1);
            t.set(g + 1, newTop, material, unit, por, (byte) 0, weld, (byte) flags);
            enforceCap(t, c);
        }
        t.version[c]++;
        editCount++;
        return true;
    }

    /**
     * Removes up to {@code thickness} metres from the top of a column. With {@code looseOnly}, stops
     * at the first consolidated (non-{@link LayerFlags#LOOSE}) layer. Cavities exposed at the top
     * collapse into open air (removed and counted in {@link ErodeResult#removedM()}). The bottom
     * layer is never removed entirely.
     */
    public ErodeResult erode(int x, int z, double thickness, boolean looseOnly) {
        Tile t = tile(x, z);
        if (t == null || !(thickness > 0)) return ErodeResult.NONE;
        int c = local(x, z);
        if (t.count(c) == 0) return ErodeResult.NONE;
        Map<Short, Double> removedBy = new TreeMap<>();
        double removed = 0;
        double remaining = thickness;
        while (remaining > 0) {
            int s = t.start[c];
            int g = t.start[c + 1] - 1;
            short mat = t.material[g];
            boolean isVoid = mat == MaterialTable.VOID.id();
            if (looseOnly && !isVoid && (t.flags[g] & LayerFlags.LOOSE) == 0) break;
            double th = t.top[g] - bottom(t, c, g);
            if (g == s) {
                double take = Math.min(remaining, th - MIN_BOTTOM);
                if (take > 0) {
                    t.top[g] = (float) (t.top[g] - take);
                    removed += take;
                    if (!isVoid) removedBy.merge(mat, take, Double::sum);
                }
                break;
            }
            if (remaining >= th - EPS) {
                t.remove(c, g, 1);
                removed += th;
                remaining -= th;
                if (!isVoid) removedBy.merge(mat, th, Double::sum);
            } else {
                t.top[g] = (float) (t.top[g] - remaining);
                removed += remaining;
                if (!isVoid) removedBy.merge(mat, remaining, Double::sum);
                remaining = 0;
            }
        }
        removed += collapseExposedVoids(t, c);
        if (removed > 0) {
            t.version[c]++;
            editCount++;
        }
        return new ErodeResult(removed, removedBy);
    }

    /** Removes cavities left at the top of a column (an unroofed tube is just open ground). */
    private double collapseExposedVoids(Tile t, int c) {
        double removed = 0;
        while (t.count(c) > 1) {
            int g = t.start[c + 1] - 1;
            if (t.material[g] != MaterialTable.VOID.id()) break;
            removed += t.top[g] - t.top[g - 1];
            t.remove(c, g, 1);
        }
        return removed;
    }

    /**
     * Replaces everything between {@code zLo} and {@code zHi} (inside the column, below the surface)
     * with a single layer.
     *
     * @return what was replaced (solid materials only in {@code byMaterial})
     */
    public ErodeResult replaceRange(int x, int z, double zLo, double zHi, short material, int unit, int flags,
            double porosity, double welding) {
        Tile t = tile(x, z);
        if (t == null) return ErodeResult.NONE;
        int c = local(x, z);
        if (t.count(c) == 0) return ErodeResult.NONE;
        double surface = t.top[t.start[c + 1] - 1];
        zLo = Math.max(zLo, datumZ);
        zHi = Math.min(zHi, surface);
        if (zHi - zLo < EPS) return ErodeResult.NONE;

        split(t, c, zLo);
        split(t, c, zHi);
        int s = t.start[c];
        int e = t.start[c + 1];
        int first = -1;
        int last = -1;
        Map<Short, Double> replaced = new TreeMap<>();
        double total = 0;
        for (int g = s; g < e; g++) {
            double b = bottom(t, c, g);
            if (b >= zLo - EPS && t.top[g] <= zHi + EPS) {
                if (first < 0) first = g;
                last = g;
                double th = t.top[g] - b;
                total += th;
                if (t.material[g] != MaterialTable.VOID.id()) replaced.merge(t.material[g], th, Double::sum);
            }
        }
        if (first < 0) return ErodeResult.NONE;
        double topZ = t.top[last];
        t.remove(c, first + 1, last - first);
        t.set(first, topZ, material, unit, quantize(porosity), (byte) 0, quantize(welding), (byte) flags);
        mergeIdentical(t, c);
        enforceCap(t, c);
        t.version[c]++;
        editCount++;
        return new ErodeResult(total, replaced);
    }

    /** Splits the layer straddling elevation {@code z} into two layers with the same identity. */
    private void split(Tile t, int c, double z) {
        int s = t.start[c];
        int e = t.start[c + 1];
        for (int g = s; g < e; g++) {
            double b = bottom(t, c, g);
            if (b < z - EPS && t.top[g] > z + EPS) {
                t.insert(c, g, 1);
                t.copy(g + 1, g);
                t.top[g] = (float) z;
                return;
            }
        }
    }

    private void mergeIdentical(Tile t, int c) {
        int g = t.start[c];
        while (g < t.start[c + 1] - 1) {
            if (t.sameIdentity(g, g + 1)) {
                t.remove(c, g, 1); // the upper layer keeps its top and covers both
            } else {
                g++;
            }
        }
    }

    /** Merges the thinnest adjacent pairs until the column has at most {@link #MAX_LAYERS} layers. */
    private void enforceCap(Tile t, int c) {
        while (t.count(c) > MAX_LAYERS) {
            int s = t.start[c];
            int e = t.start[c + 1];
            int best = -1;
            double bestThickness = Double.POSITIVE_INFINITY;
            for (int pass = 0; pass < 2 && best < 0; pass++) {
                for (int g = s; g < e - 1; g++) {
                    MaterialClass a = MaterialTable.get(t.material[g]).materialClass();
                    MaterialClass b = MaterialTable.get(t.material[g + 1]).materialClass();
                    if (a == MaterialClass.VOID || b == MaterialClass.VOID) continue;
                    if (pass == 0 && a != b) continue;
                    double thickness = t.top[g + 1] - bottom(t, c, g);
                    if (thickness < bestThickness) {
                        bestThickness = thickness;
                        best = g;
                    }
                }
            }
            if (best < 0) best = s; // only voids left to pair: merge the bottom pair
            int lower = best;
            int upper = best + 1;
            double thLower = t.top[lower] - bottom(t, c, lower);
            double thUpper = t.top[upper] - t.top[lower];
            int keep = thUpper >= thLower ? upper : lower;
            double porosity = (dequantize(t.porosity[lower]) * thLower + dequantize(t.porosity[upper]) * thUpper)
                    / Math.max(EPS, thLower + thUpper);
            double topZ = t.top[upper];
            if (keep != lower) t.copy(upper, lower);
            t.top[lower] = (float) topZ;
            t.porosity[lower] = quantize(porosity);
            t.remove(c, upper, 1);
        }
    }
}
