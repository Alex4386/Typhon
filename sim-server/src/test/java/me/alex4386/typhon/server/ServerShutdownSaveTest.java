package me.alex4386.typhon.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stopping the server keeps the progress of its world sessions. */
class ServerShutdownSaveTest {
    @TempDir
    Path worlds;

    @Test
    void closingTheServerSavesWorldSessions() throws Exception {
        SimServer server = new SimServer(new SimServer.Config("127.0.0.1", 0, worlds, null, "kilauea", 1, 50_000, 20, 4));
        server.start();
        long savedStep;
        try (TestClient c = new TestClient(server.port(), SimServer.SUBPROTOCOL)) {
            c.send("{\"type\":\"hello\",\"protocol\":1,\"client\":\"test\"}");
            c.awaitType("welcome", 10);
            c.send("{\"type\":\"createSession\",\"requestId\":1,\"preset\":\"kilauea\",\"name\":\"beta\",\"paused\":true}");
            String id = c.awaitType("attached", 120).json().get("sessionId").getAsString();
            Session s = server.session(id);
            c.send("{\"type\":\"step\",\"seconds\":10}");
            long deadline = System.nanoTime() + 60_000_000_000L;
            while (s.time() < 200 * 0.05 - 1e-9 && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(s.time() >= 200 * 0.05 - 1e-9, "the session advanced: " + s.time());
            savedStep = s.runner().completedStep();
        } finally {
            server.close();
        }
        JsonObject meta = JsonParser.parseString(Files.readString(worlds.resolve("beta/state/meta.json"))).getAsJsonObject();
        // a fresh world directory is saved at its first step; the shutdown save carries the run's progress
        assertTrue(meta.get("step").getAsLong() >= savedStep, "the shutdown save holds the session's progress: " + meta);
    }
}
