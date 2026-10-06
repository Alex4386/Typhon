package me.alex4386.typhon.engine.save;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * On-disk layout of a save:
 *
 * <pre>
 * meta.json                                   format, seed, time, base step, subsystems + configs
 * subsystems/&lt;id&gt;.json                        random state + scalar state + field schema versions
 * fields/&lt;id&gt;/&lt;field&gt;/r.&lt;rx&gt;.&lt;rz&gt;.bin          region files: compressed typed arrays, 32×32 chunks
 * log/events.ndjson                           append-only history of {@code HistoricalEvent}s
 * </pre>
 *
 * Ids and field names are percent-encoded so they are safe file names.
 */
public final class SaveFormat {
    public static final String META = "meta.json";
    public static final String HISTORY = "log/events.ndjson";
    public static final int REGION_SIZE = 32;

    private static final int REGION_MAGIC = 0x54595246; // "TYRF"
    private static final int REGION_FORMAT = 1;
    private static final byte T_DOUBLE = 1, T_FLOAT = 2, T_INT = 3, T_LONG = 4, T_BYTE = 5;

    static final Gson GSON = new GsonBuilder().serializeSpecialFloatingPointValues().disableHtmlEscaping().create();

    private SaveFormat() {}

    public record LoadedSubsystem(long randomState, SubsystemState state) {}

    // ── Paths ──

    public static String encode(String name) {
        StringBuilder sb = new StringBuilder();
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_'
                    || c == '.') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return sb.toString();
    }

    /** One history log line: {@code {"type": <simple class name>, "event": <event fields>}}. */
    public static String historyLine(me.alex4386.typhon.engine.output.HistoricalEvent event) {
        JsonObject line = new JsonObject();
        line.addProperty("type", event.getClass().getSimpleName());
        line.add("event", GSON.toJsonTree(event));
        return GSON.toJson(line);
    }

    public static String subsystemPath(String id) {
        return "subsystems/" + encode(id) + ".json";
    }

    public static String fieldsPrefix(String id) {
        return "fields/" + encode(id) + "/";
    }

    static String fieldPrefix(String id, String field) {
        return fieldsPrefix(id) + encode(field) + "/";
    }

    static String regionPath(String id, String field, int rx, int rz) {
        return fieldPrefix(id, field) + "r." + rx + "." + rz + ".bin";
    }

    // ── JSON ──

    public static byte[] jsonBytes(JsonObject json) {
        return GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
    }

    public static JsonObject parse(byte[] bytes) {
        return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    public static Gson gson() {
        return GSON;
    }

    // ── Subsystems ──

    /**
     * Writes one subsystem's state. Region files whose bytes are unchanged are not rewritten, and
     * regions or fields that no longer exist are deleted.
     */
    public static void writeSubsystem(SaveStore store, String id, long randomState, SubsystemState state) {
        JsonObject root = new JsonObject();
        root.addProperty("random", randomState);
        root.add("state", state.json());
        JsonObject schemas = new JsonObject();
        for (Map.Entry<String, SubsystemState.FieldData> field : state.fields().entrySet()) {
            schemas.addProperty(field.getKey(), field.getValue().schemaVersion());
        }
        root.add("fields", schemas);
        // The subsystem's JSON first, its region files after: append-only tables in the JSON (the world's unit
        // table) then always cover the ids its region files reference, also if a save is cut off midway.
        store.write(subsystemPath(id), jsonBytes(root));

        Set<String> live = new HashSet<>();
        for (Map.Entry<String, SubsystemState.FieldData> field : state.fields().entrySet()) {
            String name = field.getKey();
            SubsystemState.FieldData data = field.getValue();
            TreeMap<Long, List<StateReader.Entry>> regions = new TreeMap<>();
            Map<Long, int[]> coords = new TreeMap<>();
            for (StateReader.Entry entry : data.chunks()) {
                int rx = Math.floorDiv(entry.chunkX(), REGION_SIZE);
                int rz = Math.floorDiv(entry.chunkZ(), REGION_SIZE);
                long key = SubsystemState.FieldData.key(rx, rz);
                regions.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
                coords.put(key, new int[] {rx, rz});
            }
            for (Map.Entry<Long, List<StateReader.Entry>> region : regions.entrySet()) {
                int[] rc = coords.get(region.getKey());
                String path = regionPath(id, name, rc[0], rc[1]);
                store.write(path, encodeRegion(data.schemaVersion(), region.getValue()));
                live.add(path);
            }
        }

        for (String path : store.list(fieldsPrefix(id))) {
            if (!live.contains(path)) store.delete(path);
        }
    }

    /** Reads one subsystem's state, or {@code null} if the save has none for {@code id}. */
    public static LoadedSubsystem readSubsystem(SaveStore store, String id) {
        byte[] bytes = store.read(subsystemPath(id));
        if (bytes == null) return null;
        JsonObject root = parse(bytes);
        SubsystemState state = new SubsystemState(root.getAsJsonObject("state"));
        JsonObject schemas = root.getAsJsonObject("fields");
        for (String name : schemas.keySet()) {
            int schema = schemas.get(name).getAsInt();
            SubsystemState.FieldData field = state.field(name, schema);
            for (String path : store.list(fieldPrefix(id, name))) {
                decodeRegion(store.read(path), schema, field);
            }
        }
        return new LoadedSubsystem(root.get("random").getAsLong(), state);
    }

    // ── Region files ──

    static byte[] encodeRegion(int schemaVersion, List<StateReader.Entry> chunks) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes, deflater))) {
            out.writeInt(REGION_MAGIC);
            out.writeInt(REGION_FORMAT);
            out.writeInt(schemaVersion);
            out.writeInt(chunks.size());
            for (StateReader.Entry entry : chunks) {
                out.writeInt(entry.chunkX());
                out.writeInt(entry.chunkZ());
                Map<String, Object> arrays = entry.data().arrays();
                out.writeInt(arrays.size());
                for (Map.Entry<String, Object> array : arrays.entrySet()) {
                    out.writeUTF(array.getKey());
                    writeArray(out, array.getValue());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deflater.end();
        }
        return bytes.toByteArray();
    }

    static void decodeRegion(byte[] data, int expectedSchema, SubsystemState.FieldData into) {
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
            if (in.readInt() != REGION_MAGIC) throw new IOException("Not a Typhon region file");
            int format = in.readInt();
            if (format != REGION_FORMAT) throw new IOException("Unsupported region format " + format);
            int schema = in.readInt();
            if (schema != expectedSchema) {
                throw new IOException("Region schema " + schema + " does not match field schema " + expectedSchema);
            }
            int count = in.readInt();
            for (int c = 0; c < count; c++) {
                int cx = in.readInt();
                int cz = in.readInt();
                int arrays = in.readInt();
                FieldChunk chunk = new FieldChunk();
                for (int a = 0; a < arrays; a++) {
                    String name = in.readUTF();
                    readArray(in, name, chunk);
                }
                into.put(cx, cz, chunk);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeArray(DataOutputStream out, Object array) throws IOException {
        switch (array) {
            case double[] a -> {
                out.writeByte(T_DOUBLE);
                out.writeInt(a.length);
                for (double v : a) out.writeLong(Double.doubleToRawLongBits(v));
            }
            case float[] a -> {
                out.writeByte(T_FLOAT);
                out.writeInt(a.length);
                for (float v : a) out.writeInt(Float.floatToRawIntBits(v));
            }
            case int[] a -> {
                out.writeByte(T_INT);
                out.writeInt(a.length);
                for (int v : a) out.writeInt(v);
            }
            case long[] a -> {
                out.writeByte(T_LONG);
                out.writeInt(a.length);
                for (long v : a) out.writeLong(v);
            }
            case byte[] a -> {
                out.writeByte(T_BYTE);
                out.writeInt(a.length);
                out.write(a);
            }
            default -> throw new IllegalArgumentException("Unsupported array type " + array.getClass());
        }
    }

    private static void readArray(DataInputStream in, String name, FieldChunk chunk) throws IOException {
        byte type = in.readByte();
        int n = in.readInt();
        switch (type) {
            case T_DOUBLE -> {
                double[] a = new double[n];
                for (int i = 0; i < n; i++) a[i] = Double.longBitsToDouble(in.readLong());
                chunk.doubles(name, a);
            }
            case T_FLOAT -> {
                float[] a = new float[n];
                for (int i = 0; i < n; i++) a[i] = Float.intBitsToFloat(in.readInt());
                chunk.floats(name, a);
            }
            case T_INT -> {
                int[] a = new int[n];
                for (int i = 0; i < n; i++) a[i] = in.readInt();
                chunk.ints(name, a);
            }
            case T_LONG -> {
                long[] a = new long[n];
                for (int i = 0; i < n; i++) a[i] = in.readLong();
                chunk.longs(name, a);
            }
            case T_BYTE -> {
                byte[] a = new byte[n];
                in.readFully(a);
                chunk.bytes(name, a);
            }
            default -> throw new IOException("Unknown array type " + type + " for " + name);
        }
    }
}
