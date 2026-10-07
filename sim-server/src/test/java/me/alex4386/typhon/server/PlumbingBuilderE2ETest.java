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
            java.util.Map<String, JsonObject> seen = new java.util.LinkedHashMap<>();
            c.await(m -> {
                if (!m.type().equals("entities") || !m.json().has("upsert")) return false;
                for (JsonElement e : m.json().getAsJsonArray("upsert")) seen.put(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
                return seen.containsKey("connection:" + vid + ":" + lid) && seen.containsKey("chamber:" + vid);
            }, 120);
            JsonObject entities = new JsonObject();
            com.google.gson.JsonArray upsert = new com.google.gson.JsonArray();
            seen.values().forEach(upsert::add);
            entities.add("upsert", upsert);
            assertTrue(has(entities, "chamber:" + vid + ":deep"), "the deep chamber is an entity");

            // a wider conduit is a live dial
            Reply wider = http(port, "PATCH", base + "/connections/" + lid, "{\"fields\":{\"radiusM\":3}}");
            assertEquals(200, wider.status(), wider.body().toString());
            assertEquals("live", wider.body().get("applied").getAsString(), wider.body().toString());

            // the deep chamber's own settings are in the schema, laid out on its Inspector tabs
            c.send("{\"type\":\"getSchema\"}");
            JsonObject spec = c.await(m -> m.type().equals("schema") && m.json().toString().contains("magma.chambers[deep]"), 60).json();
            assertPanelsReachable(spec, entities, vid);
            JsonObject deepSupply = param(spec, "volcano." + vid + ".magma.chambers[deep].supplyRate");
            assertEquals("volcano." + vid + ".magma.chambers[deep]", deepSupply.get("owner").getAsString());
            assertEquals("supply", deepSupply.get("tab").getAsString());
            assertEquals("primary", deepSupply.get("tier").getAsString());
            JsonObject deepEntity = entity(entities, "chamber:" + vid + ":deep");
            assertTrue(deepEntity.getAsJsonArray("paramOwners").toString().contains("magma.chambers[deep]"), deepEntity.toString());
            assertTrue(deepEntity.getAsJsonArray("related").toString().contains("connection:" + vid + ":" + lid), "its pathway is related");

            // editing that chamber's supply changes only that chamber
            double mainSupply = server.session(id).live().volcanoes().get(0).chamber().supplyRate();
            c.send("{\"type\":\"setParams\",\"requestId\":41,\"values\":{\"volcano." + vid + ".magma.chambers[deep].supplyRate\":7}}");
            JsonObject ack = c.await(m -> m.type().equals("ack") && m.json().get("requestId").getAsLong() == 41, 60).json();
            assertTrue(ack.get("ok").getAsBoolean(), ack.toString());
            var live = server.session(id).live().volcanoes().get(0);
            assertEquals(7, live.chambers().get("deep").config().supplyRate(), 1e-9);
            assertEquals(mainSupply, live.chamber().supplyRate(), 1e-9, "the main chamber keeps its supply");

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

    /**
     * Every placed volcano param belongs to an owner some entity shows, on a tab that entity's layout has,
     * and each layout field is a measure, a derived value or a widget.
     */
    private static void assertPanelsReachable(JsonObject schema, JsonObject entities, String vid) {
        JsonObject kinds = schema.getAsJsonObject("objectPanels");
        java.util.Map<String, java.util.Set<String>> tabsByOwner = new java.util.HashMap<>();
        for (JsonElement el : entities.getAsJsonArray("upsert")) {
            JsonObject e = el.getAsJsonObject();
            if (!e.has("paramOwners") || !kinds.has(e.get("kind").getAsString())) continue;
            java.util.Set<String> tabs = new java.util.HashSet<>();
            for (JsonElement t : kinds.getAsJsonObject(e.get("kind").getAsString()).getAsJsonArray("tabs")) {
                JsonObject tab = t.getAsJsonObject();
                tabs.add(tab.get("id").getAsString());
                for (JsonElement s : tab.getAsJsonArray("sections")) {
                    for (JsonElement f : s.getAsJsonObject().getAsJsonArray("fields")) {
                        JsonObject field = f.getAsJsonObject();
                        assertTrue(field.has("measure") || field.has("widget"), field.toString());
                    }
                }
            }
            for (JsonElement o : e.getAsJsonArray("paramOwners")) tabsByOwner.computeIfAbsent(o.getAsString(), k -> new java.util.HashSet<>()).addAll(tabs);
        }
        // the volcano as a whole and the world, which are not entities
        for (var o : schema.getAsJsonObject("objectOwners").entrySet()) {
            String kind = o.getKey().contains(":") ? o.getKey().substring(0, o.getKey().indexOf(':')) : o.getKey();
            java.util.Set<String> tabs = new java.util.HashSet<>();
            for (JsonElement t : kinds.getAsJsonObject(kind).getAsJsonArray("tabs")) tabs.add(t.getAsJsonObject().get("id").getAsString());
            for (JsonElement owner : o.getValue().getAsJsonArray()) tabsByOwner.computeIfAbsent(owner.getAsString(), k -> new java.util.HashSet<>()).addAll(tabs);
        }
        int checked = 0;
        for (JsonElement el : schema.getAsJsonArray("params")) {
            JsonObject p = el.getAsJsonObject();
            boolean world = !p.has("volcanoId");
            if (!world && !vid.equals(p.get("volcanoId").getAsString())) continue;
            assertTrue(p.has("owner") && p.has("tab") && p.has("tier"), "placed: " + p.get("id"));
            java.util.Set<String> tabs = tabsByOwner.get(p.get("owner").getAsString());
            assertTrue(tabs != null, "some object shows " + p.get("owner") + " (" + p.get("id") + ")");
            assertTrue(tabs.contains(p.get("tab").getAsString()), p.get("id") + " on a tab its object has: " + p.get("tab"));
            checked++;
        }
        assertTrue(checked > 20, "checked " + checked);
    }

    private static JsonObject param(JsonObject schema, String id) {
        for (JsonElement el : schema.getAsJsonArray("params")) if (id.equals(el.getAsJsonObject().get("id").getAsString())) return el.getAsJsonObject();
        throw new AssertionError("no param " + id);
    }

    private static JsonObject entity(JsonObject entities, String id) {
        for (JsonElement e : entities.getAsJsonArray("upsert")) if (id.equals(e.getAsJsonObject().get("id").getAsString())) return e.getAsJsonObject();
        throw new AssertionError("no entity " + id);
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
