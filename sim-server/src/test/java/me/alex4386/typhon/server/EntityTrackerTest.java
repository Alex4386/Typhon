package me.alex4386.typhon.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.seismic.SeismicEvent;
import me.alex4386.typhon.engine.seismic.SeismicEventType;
import org.junit.jupiter.api.Test;

/** Lifecycle of entities in the registry: creation stamps, updates, removal, full resend. */
class EntityTrackerTest {
    private static final GridMapping MAP = new GridMapping(10, 16, 0, 0, 63, 63);

    private static JsonObject spring(String id, double t) {
        JsonObject o = EntityTracker.entity(id, "feature", "v", "Hot spring", new double[] {100, 200, 50});
        o.getAsJsonObject("props").addProperty("feature", "HOT_SPRING");
        o.getAsJsonObject("props").add("groundTemperatureC", Json.num(t));
        return o;
    }

    private static List<String> ids(JsonArray a) {
        return a.asList().stream()
                .map(e -> e.isJsonObject() ? e.getAsJsonObject().get("id").getAsString() : e.getAsString())
                .toList();
    }

    @Test
    void createUpdateRemove() {
        EntityTracker t = new EntityTracker(MAP, List.of("v"));
        Map<String, JsonObject> snap = new LinkedHashMap<>();
        snap.put("a", spring("a", 80));

        JsonObject first = t.delta(snap, 10, false);
        assertEquals(List.of("a"), ids(first.getAsJsonArray("upsert")));
        assertEquals(10, first.getAsJsonArray("upsert").get(0).getAsJsonObject().get("createdAt").getAsDouble());

        assertNull(t.delta(snap, 11, false), "nothing changed");

        snap.put("a", spring("a", 85));
        snap.put("b", spring("b", 60));
        JsonObject second = t.delta(snap, 12, false);
        assertEquals(List.of("a", "b"), ids(second.getAsJsonArray("upsert")));
        JsonObject a = second.getAsJsonArray("upsert").get(0).getAsJsonObject();
        assertEquals(10, a.get("createdAt").getAsDouble(), "creation time is kept across updates");
        assertEquals(12, a.get("updatedAt").getAsDouble());

        snap.remove("a");
        JsonObject third = t.delta(snap, 13, false);
        assertEquals(List.of("a"), ids(third.getAsJsonArray("remove")));
        assertTrue(third.getAsJsonArray("upsert").isEmpty());
        assertEquals(List.of("b"), t.ids());

        JsonObject full = t.full(snap, 14);
        assertTrue(full.get("replace").getAsBoolean());
        assertEquals(List.of("b"), ids(full.getAsJsonArray("upsert")));
        assertEquals(12, full.getAsJsonArray("upsert").get(0).getAsJsonObject().get("createdAt").getAsDouble());
    }

    @Test
    void fissuresAreNamedAfterTheirDike() {
        assertEquals("Fissure from dike 2", EntityTracker.fissureLabel("kilauea-real-dike-2"));
        assertEquals("Fissure east-rift", EntityTracker.fissureLabel("east-rift"));
    }

    @Test
    void notableQuakesAppearAndExpire() {
        EntityTracker t = new EntityTracker(MAP, List.of("v"));
        t.observe(new SeismicEvent(5, "v", SeismicEventType.VT, 3.1, new BlockPos(10, -20, 10), 1, false));
        t.observe(new SeismicEvent(5, "v", SeismicEventType.VT, 0.5, new BlockPos(11, -20, 10), 1, false));
        JsonObject d = t.delta(Map.of(), 6, false);
        assertEquals(1, d.getAsJsonArray("upsert").size(), "only notable quakes become entities");
        assertEquals("quake", d.getAsJsonArray("upsert").get(0).getAsJsonObject().get("kind").getAsString());
        JsonObject later = t.delta(Map.of(), 5 + EntityTracker.QUAKE_LIFETIME + 1, false);
        assertEquals(1, later.getAsJsonArray("remove").size(), "an expired quake is removed");
    }

    @Test
    void relatedObjectsLinkVentsDikesAndChambers() {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        java.util.function.BiFunction<String, String, JsonObject> put = (id, kind) -> {
            JsonObject o = EntityTracker.entity(id, kind, "a", id, new double[] {0, 0, 0});
            out.put(id, o);
            return o;
        };
        put.apply("volcano:a", "volcano");
        put.apply("chamber:a", "chamber").getAsJsonObject("props").addProperty("chamberId", "main");
        put.apply("chamber:a:deep", "chamber").getAsJsonObject("props").addProperty("chamberId", "deep");
        JsonObject link = put.apply("connection:a:c1", "connection");
        link.getAsJsonObject("props").addProperty("from", "deep");
        link.getAsJsonObject("props").addProperty("to", "main");
        put.apply("vent:a:f1", "fissure").getAsJsonObject("props").addProperty("ventId", "f1");
        put.apply("dike:a:3", "dike").getAsJsonObject("props").addProperty("fissure", "f1");
        put.apply("dike:a:4", "dike").getAsJsonObject("props").addProperty("fissure", "other");
        java.util.function.Function<String, List<String>> rel = id -> {
            JsonObject e = out.get(id);
            return ids(EntityTracker.related(e.get("kind").getAsString(), "a", e.getAsJsonObject("props"), out));
        };
        assertEquals(List.of("dike:a:3", "chamber:a"), rel.apply("vent:a:f1"), "a vent: the dikes feeding it, its chamber");
        assertEquals(List.of("vent:a:f1", "chamber:a"), rel.apply("dike:a:3"), "a dike: its fissure, its chamber");
        assertEquals(List.of("volcano:a", "vent:a:f1", "dike:a:3", "dike:a:4", "connection:a:c1"), rel.apply("chamber:a"));
        assertEquals(List.of("connection:a:c1"), rel.apply("chamber:a:deep"), "a further chamber: its pathways");
        assertEquals(List.of("chamber:a:deep", "chamber:a"), rel.apply("connection:a:c1"));
        assertEquals(List.of("chamber:a", "chamber:a:deep", "vent:a:f1"), rel.apply("volcano:a"));
    }
}
