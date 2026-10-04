package me.alex4386.typhon.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import me.alex4386.typhon.server.protocol.Codecs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end protocol tests against a real server on an ephemeral port. */
class ProtocolTest {
    @TempDir
    static Path worlds;

    static SimServer server;

    @BeforeAll
    static void start() {
        server = new SimServer(new SimServer.Config("127.0.0.1", 0, worlds, null, "kilauea", 1, 50_000, 20));
        server.createPreset("kilauea", 1);
        server.start();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private TestClient attached() throws Exception {
        TestClient c = new TestClient(server.port(), SimServer.SUBPROTOCOL);
        c.send("{\"type\":\"hello\",\"protocol\":1,\"client\":\"test\"}");
        c.awaitType("welcome", 10);
        c.send("{\"type\":\"attach\",\"sessionId\":\"s1\"}");
        c.awaitType("attached", 30);
        return c;
    }

    @Test
    void handshakeNegotiatesSubprotocolAndListsFields() throws Exception {
        try (TestClient c = new TestClient(server.port(), SimServer.SUBPROTOCOL)) {
            assertEquals(SimServer.SUBPROTOCOL, c.subprotocol());
            c.send("{\"type\":\"listSessions\"}");
            assertEquals("protocol", c.awaitType("error", 5).json().get("code").getAsString(), "hello must come first");
            c.send("{\"type\":\"hello\",\"protocol\":1,\"client\":\"test\"}");
            JsonObject welcome = c.awaitType("welcome", 5).json();
            assertEquals(1, welcome.get("protocol").getAsInt());
            Set<Integer> fields = new HashSet<>();
            for (JsonElement e : welcome.getAsJsonArray("fields")) fields.add(e.getAsInt());
            assertTrue(fields.contains(1) && fields.contains(2) && fields.contains(10));
            assertFalse(fields.contains(9), "water table needs the subsurface model");
            c.send("{\"type\":\"listSessions\"}");
            JsonArray sessions = c.awaitType("sessions", 5).json().getAsJsonArray("sessions");
            assertEquals("s1", sessions.get(0).getAsJsonObject().get("id").getAsString());
            c.send("{\"type\":\"nonsense\"}");
            assertEquals("badRequest", c.awaitType("error", 5).json().get("code").getAsString());
        }
    }

    @Test
    void wrongProtocolVersionIsRejected() throws Exception {
        try (TestClient c = new TestClient(server.port(), SimServer.SUBPROTOCOL)) {
            c.send("{\"type\":\"hello\",\"protocol\":99,\"client\":\"test\"}");
            assertEquals("protocol", c.awaitType("error", 5).json().get("code").getAsString());
        }
    }

    @Test
    void handshakeWithoutSubprotocolFails() {
        assertThrows(Exception.class, () -> new TestClient(server.port(), null).close());
    }

    @Test
    void attachBurstAndStreams() throws Exception {
        try (TestClient c = new TestClient(server.port(), SimServer.SUBPROTOCOL)) {
            c.send("{\"type\":\"hello\",\"protocol\":1,\"client\":\"test\"}");
            c.awaitType("welcome", 5);
            c.send("{\"type\":\"attach\",\"sessionId\":\"s1\"}");
            JsonObject world = c.awaitType("attached", 30).json().getAsJsonObject("world");
            assertTrue(world.get("cellSize").getAsDouble() > 0);
            assertTrue(world.getAsJsonArray("volcanoes").size() >= 1);
            assertTrue(world.getAsJsonArray("materials").size() > 5);
            JsonObject units = c.awaitType("units", 10).json();
            assertTrue(units.get("replace").getAsBoolean());
            assertTrue(units.getAsJsonArray("units").size() >= 1, "pre-existing geology units");
            JsonObject state = c.awaitType("state", 10).json();
            assertTrue(state.getAsJsonObject("volcanoes").size() >= 1);
            c.awaitType("events", 10);
            c.awaitType("replayInfo", 10);
            JsonObject clock = c.awaitType("clock", 10).json();
            assertEquals(0.05, clock.get("baseStep").getAsDouble(), 1e-12);
        }
    }

    @Test
    void tilesStreamWithFlowControlAndDecode() throws Exception {
        try (TestClient c = attached()) {
            c.send("{\"type\":\"subscribe\",\"fields\":[1,10,2]}");
            int received = 0;
            Set<Integer> fields = new HashSet<>();
            float min = Float.POSITIVE_INFINITY;
            float max = Float.NEGATIVE_INFINITY;
            while (received < ClientConnection.TILE_WINDOW) {
                TestClient.Msg m = c.await(x -> x.binary() != null && x.binary()[0] == Codecs.FRAME_TILE, 20);
                Codecs.TileFrame f = Codecs.decodeTileFrame(m.binary());
                fields.add(f.field());
                if (f.field() == 1) {
                    for (float v : f.values()) {
                        min = Math.min(min, v);
                        max = Math.max(max, v);
                    }
                }
                assertTrue(f.version() >= 1);
                received++;
            }
            assertTrue(fields.contains(1));
            assertTrue(max > min, "elevation varies");
            // The credit window is full: nothing more until the client acknowledges.
            Thread.sleep(400);
            assertTrue(c.inbox.stream().noneMatch(x -> x.binary() != null && x.binary()[0] == Codecs.FRAME_TILE),
                    "server must respect the window");
            c.send("{\"type\":\"flow\",\"tilesProcessed\":" + received + "}");
            c.await(x -> x.binary() != null && x.binary()[0] == Codecs.FRAME_TILE, 20);
        }
    }

    @Test
    void sectionRequestReturnsRaster() throws Exception {
        try (TestClient c = attached()) {
            JsonObject info = server.session("s1").worldInfo();
            JsonArray range = info.getAsJsonArray("elevationRange");
            double zMin = range.get(0).getAsDouble() - 300;
            double zMax = range.get(1).getAsDouble() + 100;
            JsonObject vent = info.getAsJsonArray("volcanoes").get(0).getAsJsonObject()
                    .getAsJsonArray("vents").get(0).getAsJsonObject();
            double x = vent.getAsJsonArray("at").get(0).getAsDouble();
            double y = vent.getAsJsonArray("at").get(1).getAsDouble();
            c.send(String.format(java.util.Locale.ROOT,
                    "{\"type\":\"section\",\"requestId\":7,\"polyline\":[[%f,%f],[%f,%f]],\"zMin\":%f,\"zMax\":%f,\"nu\":64,\"nz\":32}",
                    x - 300, y, x + 300, y, zMin, zMax));
            TestClient.Msg m = c.await(x2 -> x2.binary() != null && x2.binary()[0] == Codecs.FRAME_SECTION, 30);
            Codecs.SectionFrame s = Codecs.decodeSectionFrame(m.binary());
            assertEquals(7, s.requestId());
            assertEquals(64, s.nu());
            assertEquals(32, s.nz());
            JsonObject meta = Json.GSON.fromJson(s.metaJson(), JsonObject.class);
            assertEquals(600, meta.get("length").getAsDouble(), 1e-6);
            assertTrue(meta.getAsJsonArray("units").size() >= 1);
            boolean chamber = false;
            for (JsonElement o : meta.getAsJsonArray("overlays")) {
                if (o.getAsJsonObject().get("kind").getAsString().equals("chamber")) chamber = true;
            }
            assertTrue(chamber, "the section through the vent passes over the chamber");
            // Bottom rows are ground, top rows air.
            int air = 0;
            int solid = 0;
            for (int i = 0; i < 64; i++) {
                if ((s.flags()[31 * 64 + i] & SectionBuilder.FLAG_AIR) != 0) air++;
                if ((s.flags()[i] & SectionBuilder.FLAG_AIR) == 0) solid++;
            }
            assertEquals(64, air);
            assertEquals(64, solid);
            for (float z : s.surfaceZ()) assertTrue(Float.isFinite(z));

            c.send("{\"type\":\"section\",\"requestId\":8,\"polyline\":[[0,0]],\"zMin\":0,\"zMax\":1,\"nu\":8,\"nz\":8}");
            JsonObject err = c.awaitType("error", 5).json();
            assertEquals("badRequest", err.get("code").getAsString());
            assertEquals(8, err.get("requestId").getAsLong());
        }
    }

    @Test
    void commandsAreAcknowledged() throws Exception {
        try (TestClient c = attached()) {
            String vid = server.session("s1").live().volcanoes().get(0).volcanoId();
            c.send("{\"type\":\"command\",\"requestId\":11,\"command\":{\"kind\":\"forceDike\",\"volcanoId\":\"" + vid + "\"}}");
            JsonObject ack = c.await(m -> m.type().equals("ack") && m.json().get("requestId").getAsLong() == 11, 10).json();
            assertTrue(ack.get("ok").getAsBoolean() || ack.has("message"));
            c.send("{\"type\":\"command\",\"requestId\":12,\"command\":{\"kind\":\"startEruption\",\"volcanoId\":\"nope\"}}");
            JsonObject bad = c.await(m -> m.type().equals("ack") && m.json().get("requestId").getAsLong() == 12, 10).json();
            assertFalse(bad.get("ok").getAsBoolean());
            assertEquals("unknownVolcano", c.awaitType("error", 5).json().get("code").getAsString());
            c.send("{\"type\":\"command\",\"requestId\":13,\"command\":{\"kind\":\"setWind\",\"speed\":12,\"bearingDeg\":90}}");
            assertTrue(c.await(m -> m.type().equals("ack") && m.json().get("requestId").getAsLong() == 13, 10).json()
                    .get("ok").getAsBoolean());
            JsonObject state = c.await(m -> m.type().equals("state")
                    && m.json().getAsJsonObject("world").getAsJsonObject("wind").get("speed").getAsDouble() == 12, 10).json();
            assertEquals(90, state.getAsJsonObject("world").getAsJsonObject("wind").get("bearingDeg").getAsDouble(), 1e-9);
            c.send("{\"type\":\"command\",\"requestId\":14,\"command\":{\"kind\":\"dig\",\"at\":["
                    + server.session("s1").map().x(server.session("s1").map().minX + 5) + ","
                    + server.session("s1").map().y(server.session("s1").map().minZ + 5) + "],\"radius\":20,\"depth\":10}}");
            JsonObject dig = c.await(m -> m.type().equals("ack") && m.json().get("requestId").getAsLong() == 14, 10).json();
            assertTrue(dig.get("ok").getAsBoolean(), dig.toString());
        }
    }

    @Test
    void transportPauseAndStepAdvanceExactly() throws Exception {
        try (TestClient c = attached()) {
            c.send("{\"type\":\"transport\",\"mode\":\"PAUSED\"}");
            c.await(m -> m.type().equals("clock") && m.json().get("mode").getAsString().equals("PAUSED"), 10);
            Session s = server.session("s1");
            assertTrue(s.runner().awaitPaused(10, java.util.concurrent.TimeUnit.SECONDS));
            double before = s.live().engine().time();
            c.send("{\"type\":\"step\",\"seconds\":1}");
            long deadline = System.currentTimeMillis() + 10_000;
            while (s.live().engine().time() < before + 1 - 1e-9 && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertTrue(s.runner().awaitPaused(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(before + 1, s.live().engine().time(), 1e-9);
            c.send("{\"type\":\"transport\",\"mode\":\"REALTIME\",\"speed\":50}");
            JsonObject clock = c.await(m -> m.type().equals("clock") && m.json().get("mode").getAsString().equals("REALTIME"), 10)
                    .json();
            assertEquals(50, clock.get("speed").getAsDouble(), 1e-9);
        }
    }

    @Test
    void saveAndLoadRoundTrip() throws Exception {
        try (TestClient c = attached()) {
            c.send("{\"type\":\"save\",\"requestId\":21,\"name\":\"probe\"}");
            JsonObject ack = c.await(m -> m.type().equals("ack") && m.json().get("requestId").getAsLong() == 21, 60).json();
            assertTrue(ack.get("ok").getAsBoolean(), ack.toString());
            assertTrue(java.nio.file.Files.exists(worlds.resolve("probe").resolve("state").resolve("meta.json")));
            c.send("{\"type\":\"load\",\"requestId\":22,\"name\":\"probe\"}");
            JsonObject loaded = c.await(m -> m.type().equals("ack") && m.json().get("requestId").getAsLong() == 22, 60).json();
            assertTrue(loaded.get("ok").getAsBoolean(), loaded.toString());
            assertNotNull(c.awaitType("attached", 30));
        }
    }
}
