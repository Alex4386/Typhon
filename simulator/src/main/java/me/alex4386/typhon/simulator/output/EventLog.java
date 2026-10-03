package me.alex4386.typhon.simulator.output;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.BlockState;

/**
 * Streams events to newline-delimited JSON: one object per line, {@code "type"} (the record's simple
 * name) first, then the record components serialised by Gson.
 */
public final class EventLog implements Consumer<EngineEvent>, Closeable {
    static final Gson GSON = new GsonBuilder()
            .serializeSpecialFloatingPointValues()
            .registerTypeAdapter(BlockId.class, (com.google.gson.JsonSerializer<BlockId>)
                    (src, type, ctx) -> new com.google.gson.JsonPrimitive(src.toString()))
            .registerTypeAdapter(BlockState.class, (com.google.gson.JsonSerializer<BlockState>)
                    (src, type, ctx) -> new com.google.gson.JsonPrimitive(src.toString()))
            .create();

    private final BufferedWriter out;
    private final Set<String> skipped;
    private long written;

    /** @param skippedTypes simple names of event types not to write (they are still counted elsewhere) */
    public EventLog(Path file, Set<String> skippedTypes) throws IOException {
        this.out = Files.newBufferedWriter(file);
        this.skipped = Set.copyOf(skippedTypes);
    }

    public static JsonObject toJson(EngineEvent event) {
        JsonObject line = new JsonObject();
        line.addProperty("type", event.getClass().getSimpleName());
        JsonElement body = GSON.toJsonTree(event);
        for (Map.Entry<String, JsonElement> e : body.getAsJsonObject().entrySet()) line.add(e.getKey(), e.getValue());
        return line;
    }

    @Override
    public void accept(EngineEvent event) {
        if (skipped.contains(event.getClass().getSimpleName())) return;
        try {
            out.write(GSON.toJson(toJson(event)));
            out.write('\n');
            written++;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public long written() {
        return written;
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
