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
 *
 * <p>Serialisation and I/O run on a background writer thread so they do not slow the engine loop;
 * events are immutable records and are written in the order they were accepted.
 */
public final class EventLog implements Consumer<EngineEvent>, Closeable {
    static final Gson GSON = new GsonBuilder()
            .serializeSpecialFloatingPointValues()
            .registerTypeAdapter(BlockId.class, (com.google.gson.JsonSerializer<BlockId>)
                    (src, type, ctx) -> new com.google.gson.JsonPrimitive(src.toString()))
            .registerTypeAdapter(BlockState.class, (com.google.gson.JsonSerializer<BlockState>)
                    (src, type, ctx) -> new com.google.gson.JsonPrimitive(src.toString()))
            .create();

    private static final EngineEvent END = new EngineEvent() {
        @Override public double time() { return 0; }
    };

    private final BufferedWriter out;
    private final Set<String> skipped;
    private final java.util.concurrent.BlockingQueue<EngineEvent> queue = new java.util.concurrent.LinkedBlockingQueue<>(65_536);
    private final Thread writer;
    private volatile Throwable failure;
    private long written;

    /** @param skippedTypes simple names of event types not to write (they are still counted elsewhere) */
    public EventLog(Path file, Set<String> skippedTypes) throws IOException {
        this.out = Files.newBufferedWriter(file);
        this.skipped = Set.copyOf(skippedTypes);
        this.writer = new Thread(this::drain, "typhon-event-log");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    private void drain() {
        try {
            while (true) {
                EngineEvent event = queue.take();
                if (event == END) break;
                out.write(GSON.toJson(toJson(event)));
                out.write('\n');
            }
        } catch (Throwable t) {
            failure = t;
        }
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
        if (failure != null) throw new UncheckedIOException(new IOException("event log writer failed", failure));
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException("interrupted", e));
        }
        written++;
    }

    public long written() {
        return written;
    }

    @Override
    public void close() throws IOException {
        try {
            queue.put(END);
            writer.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        out.close();
        if (failure != null) throw new IOException("event log writer failed", failure);
    }
}
