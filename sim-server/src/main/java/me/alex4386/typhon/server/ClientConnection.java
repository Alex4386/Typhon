package me.alex4386.typhon.server;

import com.google.gson.JsonObject;
import io.javalin.websocket.WsContext;
import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.alex4386.typhon.server.protocol.Field;

/**
 * One visualizer connection: handshake state, attached session, tile subscription and flow-control
 * counters (docs/protocol.md §5.4). Sends are serialised per connection.
 */
final class ClientConnection {
    static final int TILE_WINDOW = 48;
    private static final Logger LOG = Logger.getLogger(ClientConnection.class.getName());

    final String id;
    private final Sender sender;
    volatile boolean helloDone;
    volatile Session session;

    /** Subscription; guarded by {@code this}. */
    final Set<Field> fields = EnumSet.noneOf(Field.class);
    int minTx = Integer.MIN_VALUE;
    int minTy = Integer.MIN_VALUE;
    int maxTx = Integer.MAX_VALUE;
    int maxTy = Integer.MAX_VALUE;
    long tilesSent;
    long tilesAcked;
    final Map<Field, long[]> sentVersions = new EnumMap<>(Field.class);
    /** Pyramid levels besides 0 the client asked for (§5.5), and what it holds of them. */
    final java.util.SortedSet<Integer> levels = new java.util.TreeSet<>();
    final Map<Integer, Map<Field, long[]>> lodSent = new java.util.HashMap<>();

    /** Transport for outgoing frames (a WebSocket in production, a list in tests). */
    interface Sender {
        void text(String json) throws Exception;

        void binary(byte[] frame) throws Exception;

        boolean open();
    }

    ClientConnection(String id, Sender sender) {
        this.id = id;
        this.sender = sender;
    }

    static Sender websocket(WsContext ctx) {
        return new Sender() {
            @Override public void text(String json) {
                ctx.send(json);
            }

            @Override public void binary(byte[] frame) {
                ctx.send(ByteBuffer.wrap(frame));
            }

            @Override public boolean open() {
                return ctx.session.isOpen();
            }
        };
    }

    synchronized void send(JsonObject message) {
        if (!sender.open()) return;
        try {
            sender.text(Json.GSON.toJson(message));
        } catch (Exception e) {
            LOG.log(Level.FINE, "send failed for " + id, e);
        }
    }

    synchronized void sendBinary(byte[] frame) {
        if (!sender.open()) return;
        try {
            sender.binary(frame);
        } catch (Exception e) {
            LOG.log(Level.FINE, "send failed for " + id, e);
        }
    }

    boolean open() {
        return sender.open();
    }

    synchronized void subscribe(Set<Field> requested, int[] bounds) {
        subscribe(requested, bounds, Set.of());
    }

    synchronized void subscribe(Set<Field> requested, int[] bounds, Set<Integer> lodLevels) {
        fields.clear();
        fields.addAll(requested);
        levels.clear();
        for (int l : lodLevels) if (l != 0) levels.add(l);
        lodSent.clear();
        if (bounds != null) {
            minTx = bounds[0];
            minTy = bounds[1];
            maxTx = bounds[2];
            maxTy = bounds[3];
        } else {
            minTx = minTy = Integer.MIN_VALUE;
            maxTx = maxTy = Integer.MAX_VALUE;
        }
        tilesSent = 0;
        tilesAcked = 0;
        sentVersions.clear();
    }

    synchronized void acknowledge(long processed) {
        tilesAcked = Math.max(tilesAcked, processed);
    }

    /** Forget which tiles the client holds (after a jump or load: everything is resent). */
    synchronized void forgetTiles() {
        sentVersions.clear();
        lodSent.clear();
    }

    /**
     * Sends tiles the client does not hold at their current version, in {@code order}, while the
     * credit window allows. Returns how many were sent.
     */
    synchronized int pumpTiles(TileStore store, int[] order) {
        if (fields.isEmpty() || !sender.open()) return 0;
        GridMapping map = store.map();
        int sent = 0;
        for (int tile : order) {
            int tx = tile % map.tilesX;
            int ty = tile / map.tilesX;
            if (tx < minTx || tx > maxTx || ty < minTy || ty > maxTy) continue;
            for (Field field : fields) {
                if (tilesSent - tilesAcked >= TILE_WINDOW) return sent;
                long v = store.version(field, tile);
                if (v == 0) continue;
                long[] held = sentVersions.computeIfAbsent(field, f -> new long[store.tileCount()]);
                if (held.length != store.tileCount()) {
                    held = new long[store.tileCount()];
                    sentVersions.put(field, held);
                }
                if (held[tile] >= v) continue;
                byte[] frame = store.frame(field, tile);
                if (frame == null) continue;
                try {
                    sender.binary(frame);
                } catch (Exception e) {
                    return sent;
                }
                held[tile] = v;
                tilesSent++;
                sent++;
            }
        }
        return sent;
    }

    /**
     * Sends tiles of the subscribed pyramid levels the client does not hold, coarsest first (the
     * whole landscape appears quickly), then the fine levels. Bounds apply to level 0 only.
     */
    synchronized int pumpLod(LodTiles lod) {
        if (lod == null || levels.isEmpty() || fields.isEmpty() || !sender.open()) return 0;
        int sent = 0;
        List<Integer> order = new java.util.ArrayList<>(levels);
        order.sort((a, b) -> a > 0 && b > 0 ? Integer.compare(b, a) : a > 0 ? -1 : b > 0 ? 1 : Integer.compare(b, a));
        for (int level : order) {
            LodTiles.Level l = lod.level(level);
            if (l == null) continue;
            TileStore store = l.store;
            Map<Field, long[]> held = lodSent.computeIfAbsent(level, k -> new EnumMap<>(Field.class));
            for (int tile : l.order) {
                for (Field field : fields) {
                    if (!l.fields.contains(field)) continue;
                    if (tilesSent - tilesAcked >= TILE_WINDOW) return sent;
                    long v = store.version(field, tile);
                    if (v == 0) continue;
                    long[] h = held.computeIfAbsent(field, f -> new long[store.tileCount()]);
                    if (h[tile] >= v) continue;
                    byte[] frame = store.frame(field, tile);
                    if (frame == null) continue;
                    try {
                        sender.binary(frame);
                    } catch (Exception e) {
                        return sent;
                    }
                    h[tile] = v;
                    tilesSent++;
                    sent++;
                }
            }
        }
        return sent;
    }

    synchronized Set<Integer> subscribedLevels() {
        return Set.copyOf(levels);
    }

    synchronized Set<Field> subscribedFields() {
        return EnumSet.copyOf(fields.isEmpty() ? EnumSet.noneOf(Field.class) : fields);
    }
}
