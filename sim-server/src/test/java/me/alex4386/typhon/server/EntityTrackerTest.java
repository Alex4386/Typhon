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
}
