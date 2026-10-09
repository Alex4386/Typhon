package me.alex4386.typhon.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The configuration API: the server validates, classifies (live / reload / reinit) and applies changes
 * the least disruptive way, over HTTP and WS; the schema's prediction matches what an apply does.
 */
class ConfigApiTest {
    @TempDir
    static Path worlds;

    static SimServer server;
    static final HttpClient HTTP = HttpClient.newHttpClient();

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

    /** Closes a test's world session (its memory goes with it). */
    private static void close(String id) {
        try {
            server.closeSession(id, false);
        } catch (RuntimeException e) {
            // already gone
        }
    }

    /** A paused world session from the Kīlauea preset; returns its id. */
    private static String world(TestClient c, String name) throws Exception {
        c.send("{\"type\":\"createSession\",\"requestId\":1,\"preset\":\"kilauea\",\"name\":\"" + name + "\",\"paused\":true}");
        return c.awaitType("attached", 120).json().get("sessionId").getAsString();
    }

    record Reply(int status, JsonObject body) {}

    private static Reply http(String method, String id, String query, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api/sessions/" + id
                + "/config" + (query == null ? "" : "?" + query)));
        b = body == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.method(method, HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonElement parsed = r.body().isEmpty() ? null : JsonParser.parseString(r.body());
        if (parsed == null || !parsed.isJsonObject()) throw new AssertionError("HTTP " + r.statusCode() + ": " + r.body());
        return new Reply(r.statusCode(), parsed.getAsJsonObject());
    }

    private static String volcanoPatch(String vid, String path, Object value) {
        return "{\"volcanoes\":{\"" + vid + "\":{\"" + path + "\":" + value + "}}}";
    }

    private static String onlyVolcano(String id) {
        return server.session(id).live().volcanoes().get(0).volcanoId();
    }

    @Test
    void liveChangesApplyInPlaceFast() throws Exception {
        try (TestClient c = client()) {
            String id = world(c, "live");
            try {
                String vid = onlyVolcano(id);
                Reply got = http("GET", id, null, null);
                assertEquals(200, got.status());
                double supply = got.body().getAsJsonObject("volcanoes").getAsJsonObject(vid).getAsJsonObject("magma")
                        .getAsJsonObject("chamber").get("supplyRate").getAsDouble();

                var before = server.session(id).live();
                double time = server.session(id).time();
                Reply r = http("PATCH", id, null, volcanoPatch(vid, "magma.chamber.supplyRate", supply * 2 + 0.01));
                assertEquals(200, r.status(), r.body().toString());
                assertEquals("live", r.body().get("applied").getAsString());
                assertTrue(r.body().get("ms").getAsDouble() < 1000, "a live change is quick: " + r.body().get("ms"));
                assertSame(before, server.session(id).live(), "applied in place, the world was not reopened");
                assertEquals(time, server.session(id).time(), 1e-9);
                assertEquals(supply * 2 + 0.01, before.volcanoes().get(0).chamber().supplyRate(), 1e-9);
                JsonObject change = r.body().getAsJsonArray("changes").get(0).getAsJsonObject();
                assertEquals("volcano." + vid + ".magma.chamber.supplyRate", change.get("id").getAsString());
                assertEquals("live", change.getAsJsonObject("impact").get("kind").getAsString());
                assertFalse(change.getAsJsonObject("impact").get("message").getAsString().isEmpty());

                // the same over WS
                c.send("{\"type\":\"setConfig\",\"requestId\":7,\"world\":{\"climate\":{\"rainfallMmPerHour\":4}}}");
                JsonObject ws = c.await(m -> m.type().equals("configResult") && m.json().get("requestId").getAsLong() == 7, 30).json();
                assertTrue(ws.get("ok").getAsBoolean(), ws.toString());
                assertEquals("live", ws.get("applied").getAsString());
                assertSame(before, server.session(id).live());
                assertEquals(4.0, before.session().definition().climate().rainfallMmPerHour(), 1e-12);
            } finally {
                close(id);
            }
        }
    }

    @Test
    void blockDikesCommandIsTheDikesBlockedSetting() throws Exception {
        try (TestClient c = client()) {
            String id = world(c, "block");
            try {
                String vid = onlyVolcano(id);
                c.send("{\"type\":\"command\",\"requestId\":61,\"command\":{\"kind\":\"blockDikes\",\"volcanoId\":\"" + vid + "\",\"blocked\":true}}");
                JsonObject ack = c.await(m -> m.type().equals("ack") && m.json().get("requestId").getAsLong() == 61, 30).json();
                assertTrue(ack.get("ok").getAsBoolean(), ack.toString());
                JsonObject dikes = http("GET", id, null, null).body().getAsJsonObject("volcanoes").getAsJsonObject(vid).getAsJsonObject("dikes");
                assertTrue(dikes.get("blocked").getAsBoolean(), "the command wrote the setting: " + dikes);
                assertTrue(server.session(id).live().volcanoes().get(0).dikes().nucleationBlocked(), "and it is in effect");
                c.send("{\"type\":\"command\",\"requestId\":62,\"command\":{\"kind\":\"blockDikes\",\"volcanoId\":\"" + vid + "\",\"blocked\":false}}");
                c.await(m -> m.type().equals("ack") && m.json().get("requestId").getAsLong() == 62, 30);
                assertFalse(server.session(id).live().volcanoes().get(0).dikes().nucleationBlocked(), "one source: unblocking clears it");
            } finally {
                close(id);
            }
        }
    }

    @Test
    void dryRunAppliesNothingAndInvalidPatchesAreAtomic() throws Exception {
        try (TestClient c = client()) {
            String id = world(c, "dry");
            try {
                String vid = onlyVolcano(id);
                var live = server.session(id).live();
                double temperature = live.volcanoes().get(0).chamber().config().initialTemperatureC();

                Reply dry = http("PATCH", id, "dryRun=true", volcanoPatch(vid, "magma.chamber.initialTemperatureC", temperature + 10));
                assertEquals(200, dry.status(), dry.body().toString());
                assertEquals("reinit", dry.body().get("plan").getAsString());
                assertTrue(dry.body().get("dryRun").getAsBoolean());
                assertFalse(dry.body().has("applied"));
                assertTrue(dry.body().getAsJsonArray("consequences").get(0).getAsJsonObject().get("message").getAsString()
                        .startsWith("Restarts"));
                assertSame(live, server.session(id).live());
                assertEquals(temperature, live.volcanoes().get(0).chamber().config().initialTemperatureC());

                double supply = live.volcanoes().get(0).chamber().supplyRate();
                Reply bad = http("PATCH", id, null, "{\"volcanoes\":{\"" + vid + "\":{\"magma.chamber.supplyRate\":" + (supply + 1)
                        + ",\"magma.chamber.silicaNonsense\":3,\"dikes.poissonRatio\":true}}}");
                assertEquals(422, bad.status(), bad.body().toString());
                List<String> paths = new ArrayList<>();
                for (JsonElement e : bad.body().getAsJsonArray("errors")) paths.add(e.getAsJsonObject().get("path").getAsString());
                assertTrue(paths.contains("volcano." + vid + ".magma.chamber.silicaNonsense"), paths.toString());
                assertTrue(paths.contains("volcano." + vid + ".dikes.poissonRatio"), paths.toString());
                assertEquals(supply, live.volcanoes().get(0).chamber().supplyRate(), 0, "nothing of a rejected request applies");
            } finally {
                close(id);
            }
        }
    }

    @Test
    void resetsNeedConfirmationAndOnlyResetTheirTarget() throws Exception {
        try (TestClient c = client()) {
            String id = world(c, "reinit");
            try {
                String vid = onlyVolcano(id);
                // let something happen first, so a reset is visible
                c.send("{\"type\":\"step\",\"steps\":400}");
                long deadline = System.nanoTime() + 60_000_000_000L;
                while (server.session(id).time() < 19.9 && System.nanoTime() < deadline) Thread.sleep(20);
                double pressure = server.session(id).live().volcanoes().get(0).chamber().overpressureMPa();
                int cells = server.session(id).live().volcanoes().get(0).tephra().config().gridCells;

                String patch = volcanoPatch(vid, "tephra.gridCells", cells + 16);
                Reply ask = http("PATCH", id, null, patch);
                assertEquals(409, ask.status(), ask.body().toString());
                assertTrue(ask.body().get("needsConfirmation").getAsBoolean());
                String token = ask.body().get("token").getAsString();
                assertTrue(ask.body().getAsJsonArray("consequences").get(0).getAsJsonObject().get("message").getAsString()
                        .contains("ash"));

                Reply done = http("PATCH", id, "confirm=" + token, patch);
                assertEquals(200, done.status(), done.body().toString());
                assertEquals("reinit", done.body().get("applied").getAsString());
                var after = server.session(id).live().volcanoes().get(0);
                assertEquals(cells + 16, after.tephra().config().gridCells);
                assertEquals(pressure, after.chamber().overpressureMPa(), 1e-12, "only the ash grid was reset, the chamber kept its state");

                // a reload (generation input) keeps everything
                double time = server.session(id).time();
                Reply reload = http("PATCH", id, null, "{\"world\":{\"geology.surfaceThickness\":7}}");
                assertEquals(200, reload.status(), reload.body().toString());
                assertEquals("reload", reload.body().get("applied").getAsString());
                assertEquals(time, server.session(id).time(), 1e-9);
                assertEquals(pressure, server.session(id).live().volcanoes().get(0).chamber().overpressureMPa(), 1e-12);
            } finally {
                close(id);
            }
        }
    }

    // ── The schema's prediction is what happens ──

    private static Object perturbed(JsonObject spec) {
        if ("boolean".equals(spec.get("type").getAsString())) return !spec.get("value").getAsBoolean();
        double v = spec.has("value") && !spec.get("value").isJsonNull() ? spec.get("value").getAsDouble()
                : spec.has("computed") && !spec.get("computed").isJsonNull() ? spec.get("computed").getAsDouble() : 1.5;
        double next;
        if (spec.has("step")) next = v + 1;
        else next = v == 0 ? 0.1 : v * 1.25;
        if (spec.has("max") && !spec.get("max").isJsonNull() && next > spec.get("max").getAsDouble()) {
            next = spec.has("step") ? v - 1 : v * 0.8;
        }
        if (spec.has("min") && !spec.get("min").isJsonNull() && next < spec.get("min").getAsDouble()) next = spec.get("min").getAsDouble();
        if (spec.has("step")) return (long) Math.round(next);
        return next;
    }

    private static Reply apply(String id, String param, Object value, boolean dryRun) throws Exception {
        JsonObject values = new JsonObject();
        values.add(param, Json.GSON.toJsonTree(value));
        String body = body(values).toString();
        Reply r = http("PATCH", id, dryRun ? "dryRun=true" : null, body);
        if (dryRun) return r;
        if (r.status() == 409) r = http("PATCH", id, "confirm=" + r.body().get("token").getAsString(), body);
        return r;
    }

    private static java.util.Map<String, JsonObject> specs(String id) {
        java.util.Map<String, JsonObject> out = new java.util.LinkedHashMap<>();
        for (JsonElement x : Tuning.schema(server.session(id)).getAsJsonArray("params")) {
            out.put(x.getAsJsonObject().get("id").getAsString(), x.getAsJsonObject());
        }
        return out;
    }

    private static Object valueOf(JsonObject spec) {
        if (!spec.has("value") || spec.get("value").isJsonNull()) return null; // auto: back to computing
        return "boolean".equals(spec.get("type").getAsString()) ? spec.get("value").getAsBoolean() : spec.get("value").getAsDouble();
    }

    /**
     * Every parameter: live ones are applied (and must be applied live); reload/reinit ones are planned
     * with a dry run (the plan must match the prediction). {@link #everyParameterIsAppliedAsPredicted}
     * applies those too (slow: each rebuilds the world twice).
     */
    @Test
    void everyParameterDoesWhatTheSchemaPredicts() throws Exception {
        predictions("predict", false);
    }

    @Test
    @org.junit.jupiter.api.Tag("slow")
    void everyParameterIsAppliedAsPredicted() throws Exception {
        predictions("predict-all", true);
    }

    private static void predictions(String name, boolean applyRebuilds) throws Exception {
        try (TestClient c = client()) {
            String id = world(c, name);
            try {
                java.util.Map<String, JsonObject> specs = specs(id);
                List<String> params = new ArrayList<>(specs.keySet());
                List<String> mismatches = new ArrayList<>();
                JsonObject restoreLive = new JsonObject();
                int live = 0;
                int other = 0;
                int invalid = 0;
                for (String param : params) {
                    c.inbox.clear(); // schema pushes pile up otherwise
                    JsonObject now = specs.get(param);
                    if (now == null) continue; // gone with a part an earlier change removed (and restored)
                    String predicted = now.get("apply").getAsString();
                    assertNotNull(now.get("impact"), param);
                    assertEquals(predicted, now.getAsJsonObject("impact").get("kind").getAsString(), param);
                    Object original = valueOf(now);
                    if (!applyRebuilds && !"live".equals(predicted)) {
                        Reply dry = apply(id, param, perturbed(now), true);
                        if (dry.status() == 422) {
                            invalid++;
                            continue;
                        }
                        String plan = dry.body().get("plan").getAsString();
                        if (!predicted.equals(plan)) mismatches.add(param + ": predicted " + predicted + ", planned " + plan);
                        other++;
                        continue;
                    }
                    Reply r = apply(id, param, perturbed(now), false);
                    if (r.status() == 422) {
                        invalid++;
                        continue;
                    }
                    String applied = r.body().has("applied") ? r.body().get("applied").getAsString() : "none";
                    if (!predicted.equals(applied)) mismatches.add(param + ": predicted " + predicted + ", applied " + applied + " " + r.body());
                    if ("live".equals(applied)) {
                        live++;
                        restoreLive.add(param, Json.GSON.toJsonTree(original)); // restored together at the end
                    } else {
                        other++;
                        // a rebuild may add or remove parameters: restore now and look again
                        Reply back = apply(id, param, original, false);
                        if (back.status() != 200) mismatches.add(param + ": could not restore: " + back.body());
                        specs = specs(id);
                    }
                }
                if (!restoreLive.isEmpty()) {
                    Reply back = http("PATCH", id, null, body(restoreLive).toString());
                    assertEquals(200, back.status(), back.body().toString());
                    assertEquals("live", back.body().get("applied").getAsString(), "putting live values back is live too");
                }
                assertTrue(mismatches.isEmpty(), mismatches.size() + " mismatches:\n  " + String.join("\n  ", mismatches));
                assertTrue(live > 100, "live changes: " + live + ", reload/reinit: " + other + ", invalid: " + invalid);
                assertTrue(other > 5, "reload/reinit changes: " + other);
                assertTrue(invalid < (live + other) / 10, "few perturbations are invalid: " + invalid);
            } finally {
                close(id);
            }
        }
    }

    /** A {@code setParams}-style map (param id → value) as a config patch body. */
    private static JsonObject body(JsonObject values) {
        ConfigApi.Request req = ConfigApi.Request.fromParams(values, false, null);
        JsonObject body = new JsonObject();
        if (req.world() != null) body.add("world", req.world());
        if (req.volcanoes() != null) body.add("volcanoes", req.volcanoes());
        return body;
    }

}
