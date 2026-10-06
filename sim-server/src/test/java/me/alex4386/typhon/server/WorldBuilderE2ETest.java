package me.alex4386.typhon.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The world-builder path through the server: an empty ocean world, a magma chamber placed under its
 * sea floor (its vent does not exist until magma reaches the surface), a live dial, removing the
 * volcano (a reset the server asks to confirm) and deleting the world.
 */
class WorldBuilderE2ETest {
    @TempDir
    Path worlds;

    record Reply(int status, JsonObject body) {}

    @Test
    void emptyOceanPlaceAChamberTuneItRemoveIt() throws Exception {
        SimServer server = new SimServer(new SimServer.Config("127.0.0.1", 0, worlds, null, Main.DEFAULT_PRESET, 1, 50_000, 20, 2));
        server.start();
        int port = server.port();
        try (TestClient c = new TestClient(port, SimServer.SUBPROTOCOL)) {
            c.send("{\"type\":\"hello\",\"protocol\":1,\"client\":\"test\"}");
            c.awaitType("welcome", 10);
            c.send("{\"type\":\"listCatalog\"}");
            JsonObject catalog = c.awaitType("catalog", 10).json();
            assertEquals("ocean", catalog.get("defaultTemplate").getAsString());
            assertEquals("ocean", catalog.getAsJsonArray("templates").get(0).getAsJsonObject().get("name").getAsString());

            // an empty ocean world over HTTP
            Reply created = http(port, "POST", "/api/worlds", "{\"template\":\"ocean\",\"name\":\"sea\",\"params\":{\"coreExtentM\":2000}}");
            assertEquals(201, created.status(), created.body().toString());
            String id = created.body().get("sessionId").getAsString();
            assertTrue(Files.isRegularFile(worlds.resolve("sea/world.yaml")));
            c.send("{\"type\":\"attach\",\"sessionId\":\"" + id + "\"}");
            JsonObject world = c.awaitType("attached", 120).json().getAsJsonObject("world");
            assertTrue(world.get("hasSea").getAsBoolean());
            assertEquals(0, world.get("seaLevel").getAsDouble(), 1e-9);
            assertEquals(0, world.getAsJsonArray("volcanoes").size(), "nothing in it yet");
            c.send("{\"type\":\"transport\",\"mode\":\"PAUSED\"}");

            // the schema publishes the placement fields with server defaults
            JsonObject schema = c.awaitType("schema", 30).json();
            assertTrue(schema.getAsJsonObject("commands").getAsJsonArray("placeChamber").toString().contains("\"depthM\""));

            // a chamber 3 km under the sea floor
            Reply placed = http(port, "POST", "/api/sessions/" + id + "/volcanoes", "{\"x\":0,\"y\":0,\"depthM\":3000}");
            assertEquals(201, placed.status(), placed.body().toString());
            String vid = placed.body().get("volcanoId").getAsString();
            assertEquals("reload", placed.body().get("applied").getAsString(), "adding a volcano keeps everything else");
            assertTrue(Files.isRegularFile(worlds.resolve("sea/volcanoes/" + vid + ".yaml")), "persisted like any volcano");
            JsonObject after = c.await(m -> m.type().equals("attached")
                    && m.json().getAsJsonObject("world").getAsJsonArray("volcanoes").size() == 1, 120).json();
            JsonObject v = after.getAsJsonObject("world").getAsJsonArray("volcanoes").get(0).getAsJsonObject();
            assertTrue(v.getAsJsonArray("vents").get(0).getAsJsonObject().get("emergent").getAsBoolean(),
                    "no vent yet: it forms where magma first reaches the surface");
            JsonObject entities = c.await(m -> m.type().equals("entities") && m.json().has("replace")
                    && m.json().get("replace").getAsBoolean(), 60).json();
            assertTrue(hasEntity(entities, "chamber:" + vid), "the chamber is an entity");
            assertFalse(hasEntity(entities, "vent:" + vid + ":vent"), "its vent is not shown before it exists");

            // a live dial on it
            Reply live = http(port, "PATCH", "/api/sessions/" + id + "/config", "{\"volcanoes\":{\"" + vid + "\":{\"magma.chamber.supplyRate\":4}}}");
            assertEquals("live", live.body().get("applied").getAsString(), live.body().toString());

            // removing it is a reset: the server asks to confirm, then does it
            Reply ask = http(port, "DELETE", "/api/sessions/" + id + "/volcanoes/" + vid, null);
            assertEquals(409, ask.status(), ask.body().toString());
            String token = ask.body().get("token").getAsString();
            Reply removed = http(port, "DELETE", "/api/sessions/" + id + "/volcanoes/" + vid + "?confirm=" + token, null);
            assertEquals(200, removed.status(), removed.body().toString());
            assertFalse(Files.exists(worlds.resolve("sea/volcanoes/" + vid + ".yaml")));
            c.await(m -> m.type().equals("attached") && m.json().getAsJsonObject("world").getAsJsonArray("volcanoes").isEmpty(), 120);

            // close and delete
            c.send("{\"type\":\"sessionControl\",\"requestId\":3,\"sessionId\":\"" + id + "\",\"action\":\"closeWithoutSaving\"}");
            c.await(m -> m.type().equals("ack") && m.json().has("requestId") && m.json().get("requestId").getAsLong() == 3, 60);
            c.send("{\"type\":\"deleteWorld\",\"requestId\":4,\"name\":\"sea\"}");
            JsonObject deleted = c.await(m -> (m.type().equals("ack") || m.type().equals("error")) && m.json().has("requestId")
                    && m.json().get("requestId").getAsLong() == 4, 60).json();
            assertTrue(deleted.get("ok").getAsBoolean(), deleted.toString());
            assertFalse(Files.exists(worlds.resolve("sea")));
        } finally {
            server.close();
        }
    }

    private static boolean hasEntity(JsonObject entities, String id) {
        if (!entities.has("upsert")) return false;
        for (JsonElement e : entities.getAsJsonArray("upsert")) if (id.equals(e.getAsJsonObject().get("id").getAsString())) return true;
        return false;
    }

    private static Reply http(int port, String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        b = body == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.method(method, HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        HttpResponse<String> r = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), JsonParser.parseString(r.body()).getAsJsonObject());
    }
}
