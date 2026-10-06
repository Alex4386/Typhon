package me.alex4386.typhon.server;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Minimal protocol v1 client for tests (JDK WebSocket). */
final class TestClient implements AutoCloseable {
    /** A received message: JSON text or a binary frame. */
    record Msg(JsonObject json, byte[] binary) {
        String type() {
            return json == null ? "binary" : json.get("type").getAsString();
        }
    }

    final BlockingQueue<Msg> inbox = new LinkedBlockingQueue<>();
    /** Binary frames received so far (for flow-control acknowledgements). */
    final java.util.concurrent.atomic.AtomicLong binaries = new java.util.concurrent.atomic.AtomicLong();
    private final WebSocket ws;

    TestClient(int port, String subprotocol) throws Exception {
        WebSocket.Builder b = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5));
        if (subprotocol != null) b.subprotocols(subprotocol);
        ws = b.buildAsync(URI.create("ws://localhost:" + port + "/ws"), new WebSocket.Listener() {
            private final StringBuilder text = new StringBuilder();
            private final ByteArrayOutputStream bin = new ByteArrayOutputStream();

            @Override
            public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                text.append(data);
                if (last) {
                    inbox.add(new Msg(Json.GSON.fromJson(text.toString(), JsonObject.class), null));
                    text.setLength(0);
                }
                w.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onBinary(WebSocket w, ByteBuffer data, boolean last) {
                byte[] chunk = new byte[data.remaining()];
                data.get(chunk);
                bin.writeBytes(chunk);
                if (last) {
                    binaries.incrementAndGet();
                    inbox.add(new Msg(null, bin.toByteArray()));
                    bin.reset();
                }
                w.request(1);
                return null;
            }
        }).get(10, TimeUnit.SECONDS);
    }

    String subprotocol() {
        return ws.getSubprotocol();
    }

    void send(String json) throws Exception {
        ws.sendText(json, true).get(5, TimeUnit.SECONDS);
    }

    void send(JsonObject json) throws Exception {
        send(Json.GSON.toJson(json));
    }

    /** Waits for the first message matching {@code p}, discarding others. */
    Msg await(Predicate<Msg> p, long seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            Msg m = inbox.poll(100, TimeUnit.MILLISECONDS);
            if (m != null && p.test(m)) return m;
        }
        throw new AssertionError("timed out waiting for message");
    }

    Msg awaitType(String type, long seconds) throws InterruptedException {
        return await(m -> m.type().equals(type), seconds);
    }

    @Override
    public void close() {
        CompletableFuture<WebSocket> f = ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        try {
            f.get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            ws.abort();
        }
    }
}
