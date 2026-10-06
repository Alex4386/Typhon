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
 * Building a multi-chamber volcano through the server: an empty ocean, a chamber placed by position,
 * a deeper chamber added to it, a conduit between them, a live dial on the conduit, a move the server
 * says resets only that chamber, and removing the deep chamber with the server's confirmation.
 */
class PlumbingBuilderE2ETest {
    @TempDir
    Path worlds;

    record Reply(int status, JsonObject body) {}

    @Test
    void placeTwoChambersConnectTuneMoveAndRemove() throws Exception {
        SimServer server = new SimServer(new SimServer.Config("127.0.0.1", 0, worlds, null, Main.DEFAULT_PRESET, 1, 50_000, 20, 2));
        server.start();
        int port = server.port();
        try (TestClient c = new TestClient(port, SimServer.SUBPROTOCOL)) {
            c.send("{\"type\":\"hello\",\"protocol\":1,\"client\":\"test\"}");
            c.awaitType("welcome", 10);
            Reply created = http(port, "POST", "/api/worlds", "{\"template\":\"ocean\",\"name\":\"sea\",\"params\":{\"coreExtentM\":2000}}");
            assertEquals(201, created.status(), created.body().toString());
            String id = created.body().get("sessionId").getAsString();
            c.send("{\"type\":\"attach\",\"sessionId\":\"" + id + "\"}");
            c.awaitType("attached", 120);
            c.send("{\"type\":\"transport\",\"mode\":\"PAUSED\"}");

            // the schema publishes positions, the component forms and the magma presets
            JsonObject schema = c.awaitType("schema", 30).json();
            String place = schema.getAsJsonObject("commands").getAsJsonArray("placeChamber").toString();
            assertTrue(place.contains("\"x\"") && place.contains("\"y\"") && place.contains("\"depthM\""), place);
            assertTrue(schema.getAsJsonObject("components").getAsJsonArray("connection").toString().contains("\"radiusM\""));
            assertTrue(schema.getAsJsonArray("magmaPresets").size() >= 4);

            // the main chamber of a new volcano, placed by numbers
            Reply placed = http(port, "POST", "/api/sessions/" + id + "/volcanoes", "{\"x\":100,\"y\":-50,\"depthM\":1500}");
            assertEquals(201, placed.status(), placed.body().toString());
            String vid = placed.body().get("volcanoId").getAsString();
            String base = "/api/sessions/" + id + "/volcanoes/" + vid;

            // a deep chamber under it, fed from the mantle
            Reply deep = http(port, "POST", base + "/chambers",
                    "{\"chamberId\":\"deep\",\"x\":100,\"y\":-50,\"fields\":{\"depthM\":4500,\"volumeM3\":1e10,\"supplyRateM3PerS\":3}}");
            assertEquals(201, deep.status(), deep.body().toString());
            assertEquals("reload", deep.body().get("applied").getAsString(), "adding a chamber keeps every other state");
            String yaml = Files.readString(worlds.resolve("sea/volcanoes/" + vid + ".yaml"));
            assertTrue(yaml.contains("chambers:") && yaml.contains("deep"), yaml);

            // a conduit from it to the main chamber
            Reply link = http(port, "POST", base + "/connections", "{\"from\":\"deep\",\"to\":\"main\",\"kind\":\"conduit\"}");
            assertEquals(201, link.status(), link.body().toString());
            String lid = link.body().get("connectionId").getAsString();
            JsonObject entities = c.await(m -> m.type().equals("entities") && has(m.json(), "connection:" + vid + ":" + lid), 120).json();
            assertTrue(has(entities, "chamber:" + vid + ":deep"), "the deep chamber is an entity");

            // a wider conduit is a live dial
            Reply wider = http(port, "PATCH", base + "/connections/" + lid, "{\"fields\":{\"radiusM\":3}}");
            assertEquals(200, wider.status(), wider.body().toString());
            assertEquals("live", wider.body().get("applied").getAsString(), wider.body().toString());

            // moving the deep chamber: the server's plan resets only that chamber
            Reply plan = http(port, "PATCH", base + "/chambers/deep?dryRun=true", "{\"x\":300,\"y\":-50}");
            assertEquals(200, plan.status(), plan.body().toString());
            assertEquals("reinit", plan.body().get("plan").getAsString(), plan.body().toString());
            String message = plan.body().toString();
            assertTrue(message.contains("chamber deep") && message.contains("the other chambers carry on"), message);

            // removing it: the server asks to confirm, then deletes it and its pathway
            Reply ask = http(port, "DELETE", base + "/chambers/deep", null);
            assertEquals(409, ask.status(), ask.body().toString());
            Reply removed = http(port, "DELETE", base + "/chambers/deep?confirm=" + ask.body().get("token").getAsString(), null);
            assertEquals(200, removed.status(), removed.body().toString());
            String after = Files.readString(worlds.resolve("sea/volcanoes/" + vid + ".yaml"));
            assertFalse(after.contains("chambers:") || after.contains("connections:"), "the chamber and its pathway are gone");
        } finally {
            server.close();
        }
    }

    private static boolean has(JsonObject entities, String id) {
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
