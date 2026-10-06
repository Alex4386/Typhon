package me.alex4386.typhon.server;

import java.util.EnumMap;
import java.util.Map;
import me.alex4386.typhon.engine.sim.Parallel;
import me.alex4386.typhon.server.protocol.Codecs;
import me.alex4386.typhon.server.protocol.Field;

/**
 * Latest encoded frame and version per (field, tile). Versions only move forward and are bumped
 * when the <em>encoded</em> values change, so float drift below codec precision is not resent
 * (docs/protocol.md §5.1). Thread-safe.
 */
final class TileStore {
    private static final class FieldTiles {
        final long[] version;
        final long[] hash;
        final byte[][] frame;

        FieldTiles(int tiles) {
            version = new long[tiles];
            hash = new long[tiles];
            frame = new byte[tiles][];
        }
    }

    /** Encoding and compression of tiles run in parallel (pure per tile; versions assigned in order). */
    private static final Parallel ENCODERS = Parallel.of(Parallel.defaultThreads());

    private final GridMapping map;
    private final long base;
    /** Pyramid level (0 = the core's columns, §5.5) and the tile range this store holds. */
    private final int level;
    private final int minTx;
    private final int minTy;
    private final int tilesX;
    private final int tilesY;
    private final Map<Field, FieldTiles> fields = new EnumMap<>(Field.class);
    private long maxVersion;

    /**
     * @param base versions start above this, so a store replacing another (after a load) never
     *     goes backwards for clients still holding the old tiles
     */
    TileStore(GridMapping map, long base) {
        this(map, base, 0, map.minTx, map.minTy, map.tilesX, map.tilesY);
    }

    /** A store for level {@code level}, tiles {@code [minTx, minTx+tilesX) × [minTy, minTy+tilesY)}. */
    TileStore(GridMapping map, long base, int level, int minTx, int minTy, int tilesX, int tilesY) {
        this.map = map;
        this.base = base;
        this.maxVersion = base;
        this.level = level;
        this.minTx = minTx;
        this.minTy = minTy;
        this.tilesX = tilesX;
        this.tilesY = tilesY;
    }

    int level() {
        return level;
    }

    int minTx() {
        return minTx;
    }

    int minTy() {
        return minTy;
    }

    int tilesX() {
        return tilesX;
    }

    int tilesY() {
        return tilesY;
    }

    synchronized long maxVersion() {
        return maxVersion;
    }

    GridMapping map() {
        return map;
    }

    int tileCount() {
        return tilesX * tilesY;
    }

    /**
     * Encodes freshly sampled tiles; returns how many tile versions changed. {@code force} bumps every
     * tile (after a replay jump or load, so clients replace what they hold).
     */
    synchronized int update(Field field, float[][] tiles, double time, boolean force) {
        FieldTiles ft = fields.computeIfAbsent(field, f -> new FieldTiles(tileCount()));
        int t = map.tileSize;
        int n = tiles.length;
        double a = field.codec == Codecs.U8_LINEAR ? 0 : Codecs.DEFAULT_TMAX;
        Codecs.Payload[] payloads = new Codecs.Payload[n];
        long[] hashes = new long[n];
        ENCODERS.forEach(n, i -> {
            payloads[i] = Codecs.encode(tiles[i], codec(field, tiles[i]), a, 1);
            hashes[i] = fnv(payloads[i]);
        });
        int[] changedTiles = new int[n];
        int changed = 0;
        for (int i = 0; i < n; i++) {
            if (!force && ft.frame[i] != null && ft.hash[i] == hashes[i]) continue;
            ft.version[i] = Math.max(ft.version[i], base) + 1;
            maxVersion = Math.max(maxVersion, ft.version[i]);
            ft.hash[i] = hashes[i];
            changedTiles[changed++] = i;
        }
        ENCODERS.forEach(changed, k -> {
            int i = changedTiles[k];
            ft.frame[i] = Codecs.tileFrame(field.id, payloads[i], minTx + i % tilesX, minTy + i / tilesX, ft.version[i], t,
                    t, time, level);
        });
        return changed;
    }

    /** Makes every stored tile resend at a higher version (contents unchanged until next update). */
    synchronized void invalidateAll() {
        for (FieldTiles ft : fields.values()) {
            for (int i = 0; i < ft.frame.length; i++) ft.hash[i] = 0;
        }
    }

    synchronized long version(Field field, int tile) {
        FieldTiles ft = fields.get(field);
        return ft == null ? 0 : ft.version[tile];
    }

    synchronized byte[] frame(Field field, int tile) {
        FieldTiles ft = fields.get(field);
        return ft == null ? null : ft.frame[tile];
    }

    synchronized boolean has(Field field) {
        return fields.containsKey(field);
    }

    /**
     * The field's codec, except elevation tiles spanning more than the centimetre codec's 655 m (coarse
     * levels, steep real-scale tiles), which go out as raw floats instead of being clipped.
     */
    static int codec(Field field, float[] values) {
        if (field.codec != Codecs.ELEVATION_U16_CM_DELTA) return field.codec;
        float min = Float.POSITIVE_INFINITY;
        float max = Float.NEGATIVE_INFINITY;
        for (float v : values) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        return max - min > 655 ? Codecs.F32_RAW : field.codec;
    }

    private static long fnv(Codecs.Payload p) {
        long h = 0xcbf29ce484222325L;
        h = (h ^ p.codec()) * 0x100000001b3L;
        h = (h ^ Float.floatToIntBits(p.param0())) * 0x100000001b3L;
        h = (h ^ Float.floatToIntBits(p.param1())) * 0x100000001b3L;
        for (byte b : p.bytes()) h = (h ^ (b & 0xff)) * 0x100000001b3L;
        return h;
    }
}
