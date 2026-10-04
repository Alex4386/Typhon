package me.alex4386.typhon.server;

import java.util.EnumMap;
import java.util.Map;
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

    private final GridMapping map;
    private final long base;
    private final Map<Field, FieldTiles> fields = new EnumMap<>(Field.class);
    private long maxVersion;

    /**
     * @param base versions start above this, so a store replacing another (after a load) never
     *     goes backwards for clients still holding the old tiles
     */
    TileStore(GridMapping map, long base) {
        this.map = map;
        this.base = base;
        this.maxVersion = base;
    }

    synchronized long maxVersion() {
        return maxVersion;
    }

    GridMapping map() {
        return map;
    }

    int tileCount() {
        return map.tilesX * map.tilesY;
    }

    /**
     * Encodes freshly sampled tiles; returns how many tile versions changed. {@code force} bumps every
     * tile (after a replay jump or load, so clients replace what they hold).
     */
    synchronized int update(Field field, float[][] tiles, double time, boolean force) {
        FieldTiles ft = fields.computeIfAbsent(field, f -> new FieldTiles(tileCount()));
        int changed = 0;
        int t = map.tileSize;
        for (int i = 0; i < tiles.length; i++) {
            double a = field.codec == Codecs.U8_LINEAR ? 0 : Codecs.DEFAULT_TMAX;
            Codecs.Payload payload = Codecs.encode(tiles[i], field.codec, a, 1);
            long h = fnv(payload);
            if (!force && ft.frame[i] != null && ft.hash[i] == h) continue;
            ft.version[i] = Math.max(ft.version[i], base) + 1;
            maxVersion = Math.max(maxVersion, ft.version[i]);
            ft.hash[i] = h;
            ft.frame[i] = Codecs.tileFrame(field.id, payload, i % map.tilesX, i / map.tilesX, ft.version[i], t, t, time);
            changed++;
        }
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

    private static long fnv(Codecs.Payload p) {
        long h = 0xcbf29ce484222325L;
        h = (h ^ p.codec()) * 0x100000001b3L;
        h = (h ^ Float.floatToIntBits(p.param0())) * 0x100000001b3L;
        h = (h ^ Float.floatToIntBits(p.param1())) * 0x100000001b3L;
        for (byte b : p.bytes()) h = (h ^ (b & 0xff)) * 0x100000001b3L;
        return h;
    }
}
