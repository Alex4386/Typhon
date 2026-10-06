package me.alex4386.typhon.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One server, several worlds: catalog, creating/attaching/closing sessions, tuning parameters. */
class MultiWorldTest {
    @TempDir
    static Path worlds;

    static SimServer server;

    @BeforeAll
    static void start() {
        server = new SimServer(new SimServer.Config("127.0.0.1", 0, worlds, null, "kilauea", 1, 50_000, 20, 4));
        server.start();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static TestClient client() throws Exception {
        TestClient c = new TestClient(server.port(), SimServer.SUBPROTOCOL);
        c.send("{\"type\":\"hello\",\"protocol\":1,\"client\":\"test\"}");
        c.awaitType("welcome", 10);
        return c;
    }

    private static JsonObject ack(TestClient c, long requestId, int seconds) throws Exception {
        return c.await(m -> (m.type().equals("ack") || m.type().equals("error")) && m.json().has("requestId")
                && m.json().get("requestId").getAsLong() == requestId, seconds).json();
    }

    private static JsonObject param(JsonObject schema, String id) {
        for (JsonElement e : schema.getAsJsonArray("params")) {
            if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject();
        }
        return null;
    }

    @Test
    void worldsAreCreatedFromPresetsTunedAndClosed() throws Exception {
        try (TestClient c = client()) {
            c.send("{\"type\":\"listCatalog\"}");
            JsonObject catalog = c.awaitType("catalog", 10).json();
            assertTrue(catalog.getAsJsonArray("presets").size() > 0);

            // A preset becomes a world directory with the requested time compression.
            c.send("{\"type\":\"createSession\",\"requestId\":1,\"preset\":\"kilauea\",\"name\":\"alpha\","
                    + "\"timeCompression\":{\"dormant\":1234},\"paused\":true}");
            JsonObject created = ack(c, 1, 120);
            assertTrue(created.get("ok").getAsBoolean(), created.toString());
            String id = c.awaitType("attached", 60).json().get("sessionId").getAsString();
            assertTrue(Files.isRegularFile(worlds.resolve("alpha").resolve("world.yaml")));
            assertTrue(Files.readString(worlds.resolve("alpha").resolve("world.yaml")).contains("1234"));

            // The attach burst ends with the parameter schema.
            JsonObject schema = c.awaitType("schema", 30).json();
            assertTrue(schema.get("tunable").getAsBoolean(), schema.toString());
            String vid = server.session(id).live().volcanoes().get(0).volcanoId();
            JsonObject supply = param(schema, "volcano." + vid + ".magma.chamber.supplyRate");
            assertNotNull(supply, "supply rate is tunable");
            assertEquals("hot", supply.get("apply").getAsString());
            assertEquals("hot", param(schema, "volcano." + vid + ".magma.chamber.rechargeSilicaWt").get("apply").getAsString());
            assertEquals("restart", param(schema, "volcano." + vid + ".magma.chamber.volume").get("apply").getAsString());
            assertNotNull(param(schema, "world.scaling.dormantTimeCompression"));
            JsonArray inject = schema.getAsJsonObject("commands").getAsJsonArray("injectMagma");
            assertTrue(inject.toString().contains("\"temperatureC\""), inject.toString());

            // Opening the same directory again reuses the loaded session.
            c.send("{\"type\":\"createSession\",\"requestId\":2,\"world\":\"alpha\",\"attach\":false}");
            assertEquals("Session " + id, ack(c, 2, 30).get("message").getAsString());

            // Magma injection with a chosen composition; out-of-range values are refused.
            c.send("{\"type\":\"command\",\"requestId\":3,\"command\":{\"kind\":\"injectMagma\",\"volcanoId\":\"" + vid
                    + "\",\"volumeM3\":1e6,\"temperatureC\":900,\"silicaWt\":66,\"waterWt\":5}}");
            assertTrue(ack(c, 3, 10).get("ok").getAsBoolean());
            c.send("{\"type\":\"command\",\"requestId\":4,\"command\":{\"kind\":\"injectMagma\",\"volcanoId\":\"" + vid
                    + "\",\"volumeM3\":1e6,\"silicaWt\":99}}");
            assertFalse(ack(c, 4, 10).get("ok").getAsBoolean());

            // A hot change reopens the world with the new value and keeps the clock.
            double before = server.session(id).time();
            double newSupply = supply.get("value").getAsDouble() * 2 + 0.01;
            c.send("{\"type\":\"setParams\",\"requestId\":5,\"values\":{\"volcano." + vid + ".magma.chamber.supplyRate\":"
                    + newSupply + "}}");
            JsonObject applied = ack(c, 5, 120);
            assertTrue(applied.get("ok").getAsBoolean(), applied.toString());
            JsonObject after = c.await(m -> m.type().equals("schema")
                    && param(m.json(), "volcano." + vid + ".magma.chamber.supplyRate").get("value").getAsDouble() == newSupply, 60)
                    .json();
            assertEquals(1, after.getAsJsonArray("audit").size());
            assertEquals(supply.get("value").getAsDouble(),
                    param(after, "volcano." + vid + ".magma.chamber.supplyRate").get("default").getAsDouble(), 1e-12);
            assertTrue(server.session(id).time() >= before - 1e-9, "state was kept");

            // Computed parameters: null while the engine computes them, a number overrides, null goes back.
            String wall = "volcano." + vid + ".magma.chamber.wallRuptureRatio";
            JsonObject auto = param(after, wall);
            assertTrue(auto.get("auto").getAsBoolean(), auto.toString());
            assertTrue(computed(auto), auto.toString());
            assertEquals(2.0, auto.get("computed").getAsDouble(), 1e-9);
            assertEquals("hot", auto.get("apply").getAsString());
            c.send("{\"type\":\"setParams\",\"requestId\":51,\"values\":{\"" + wall + "\":1.5}}");
            JsonObject ack51 = ack(c, 51, 120);
            assertTrue(ack51.has("ok") && ack51.get("ok").getAsBoolean(), ack51.toString());
            JsonObject overridden = c.await(m -> m.type().equals("schema")
                    && !computed(param(m.json(), wall)), 60).json();
            assertEquals(1.5, param(overridden, wall).get("value").getAsDouble(), 1e-12);
            assertEquals(1.5, param(overridden, wall).get("computed").getAsDouble(), 1e-12, "the limit in use");
            c.send("{\"type\":\"setParams\",\"requestId\":52,\"values\":{\"" + wall + "\":null}}");
            JsonObject ack52 = ack(c, 52, 120);
            assertTrue(ack52.has("ok") && ack52.get("ok").getAsBoolean(), ack52.toString());
            c.await(m -> m.type().equals("schema") && computed(param(m.json(), wall)), 60);

            // Re-init changes need an explicit restart.
            c.send("{\"type\":\"setParams\",\"requestId\":6,\"values\":{\"volcano." + vid
                    + ".magma.chamber.initialTemperatureC\":1100}}");
            JsonObject refused = ack(c, 6, 10);
            assertEquals("error", refused.get("type").getAsString());
            c.send("{\"type\":\"setParams\",\"requestId\":7,\"restart\":true,\"values\":{\"volcano." + vid
                    + ".magma.chamber.initialTemperatureC\":1100,\"volcano." + vid + ".magma.chamber.supplyRate\":null}}");
            JsonObject restarted = ack(c, 7, 120);
            assertTrue(restarted.get("ok").getAsBoolean(), restarted.toString());
            assertTrue(restarted.get("message").getAsString().startsWith("Restarted"));

            // Pause, then close: watchers are detached and the world can be deleted.
            c.send("{\"type\":\"sessionControl\",\"requestId\":8,\"sessionId\":\"" + id + "\",\"action\":\"pause\"}");
            assertTrue(ack(c, 8, 10).get("ok").getAsBoolean());
            c.send("{\"type\":\"sessionControl\",\"requestId\":9,\"sessionId\":\"" + id + "\",\"action\":\"close\"}");
            assertEquals(id, c.awaitType("detached", 60).json().get("sessionId").getAsString());
            assertTrue(ack(c, 9, 60).get("ok").getAsBoolean());
            c.send("{\"type\":\"deleteWorld\",\"requestId\":10,\"name\":\"alpha\"}");
            assertTrue(ack(c, 10, 30).get("ok").getAsBoolean());
            assertFalse(Files.exists(worlds.resolve("alpha")));
        }
    }

    /** A computed (auto) parameter has no value (null or absent) while the engine computes it. */
    private static boolean computed(JsonObject spec) {
        return !spec.has("value") || spec.get("value").isJsonNull();
    }

    @Test
    void inMemorySessionsAreNotTunable() throws Exception {
        try (TestClient c = client()) {
            c.send("{\"type\":\"createSession\",\"requestId\":1,\"preset\":\"kilauea\",\"inMemory\":true}");
            assertTrue(ack(c, 1, 120).get("ok").getAsBoolean());
            JsonObject schema = c.awaitType("schema", 30).json();
            assertFalse(schema.get("tunable").getAsBoolean());
            assertTrue(schema.getAsJsonObject("commands").has("injectMagma"));
            c.send("{\"type\":\"setParams\",\"requestId\":2,\"values\":{\"world.climate.rainfallMmPerHour\":3}}");
            assertEquals("unsupported", ack(c, 2, 10).get("code").getAsString());
            String id = schema.get("sessionId").getAsString();
            c.send("{\"type\":\"sessionControl\",\"requestId\":3,\"sessionId\":\"" + id + "\",\"action\":\"closeWithoutSaving\"}");
            c.awaitType("detached", 30);
        }
    }
}
