package me.alex4386.typhon.engine.save;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * In-memory state of one subsystem: its JSON object plus its spatial fields. Implements both
 * {@link StateWriter} (filled by {@code saveState}) and {@link StateReader} (fed to
 * {@code loadState}); {@link SaveFormat} moves it to and from a {@link SaveStore}.
 */
public final class SubsystemState implements StateWriter, StateReader {
    private final JsonObject json;
    private final TreeMap<String, FieldData> fields = new TreeMap<>();

    public SubsystemState() {
        this(new JsonObject());
    }

    public SubsystemState(JsonObject json) {
        this.json = json;
    }

    @Override
    public JsonObject json() {
        return json;
    }

    @Override
    public FieldData field(String name, int schemaVersion) {
        FieldData field = fields.get(name);
        if (field == null) {
            field = new FieldData(schemaVersion);
            fields.put(name, field);
        } else if (field.schemaVersion != schemaVersion) {
            throw new IllegalStateException("Field " + name + " opened with schema " + schemaVersion
                    + " and " + field.schemaVersion);
        }
        return field;
    }

    @Override
    public FieldData field(String name) {
        return fields.get(name);
    }

    public Map<String, FieldData> fields() {
        return Collections.unmodifiableMap(fields);
    }

    /** A spatial field: schema version plus chunks keyed by (chunkZ, chunkX) order. */
    public static final class FieldData implements StateWriter.Field, StateReader.Field {
        private final int schemaVersion;
        private final TreeMap<Long, Entry> chunks = new TreeMap<>();

        public FieldData(int schemaVersion) {
            this.schemaVersion = schemaVersion;
        }

        static long key(int chunkX, int chunkZ) {
            // Sort by z then x, with signed coordinates mapped to unsigned order.
            return ((long) (chunkZ ^ Integer.MIN_VALUE) << 32) | ((chunkX ^ Integer.MIN_VALUE) & 0xffffffffL);
        }

        @Override
        public void put(int chunkX, int chunkZ, FieldChunk chunk) {
            chunks.put(key(chunkX, chunkZ), new Entry(chunkX, chunkZ, chunk));
        }

        @Override
        public int schemaVersion() {
            return schemaVersion;
        }

        @Override
        public List<Entry> chunks() {
            return new ArrayList<>(chunks.values());
        }

        @Override
        public FieldChunk get(int chunkX, int chunkZ) {
            Entry entry = chunks.get(key(chunkX, chunkZ));
            return entry == null ? null : entry.data();
        }
    }
}
