package me.alex4386.typhon.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A client attaching after an eruption started must still get the milestones ("key events"), however
 * many routine events (plume updates, quakes, bombs, features) followed them.
 */
class KeyEventsBacklogTest {
    @TempDir
    Path worlds;

    private static JsonObject ev(String kind, double time) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.addProperty("time", time);
        o.addProperty("volcanoId", "v");
        return o;
    }

    private static JsonObject feature(String type, double time) {
        JsonObject o = ev("geothermalFeature", time);
        o.addProperty("feature", type);
        return o;
    }

    @Test
    void milestonesSurviveAFloodOfRoutineEvents() {
        Deque<JsonObject> log = new ArrayDeque<>();
        Deque<JsonObject> milestones = new ArrayDeque<>();
        Set<String> firsts = new HashSet<>();
        List<JsonObject> all = new ArrayList<>();
        all.add(ev("alertChanged", 1));
        all.add(ev("eruptionStarted", 5));
        all.add(ev("styleEstimated", 6));
        all.add(ev("dikeStarted", 7));
        all.add(feature("GEYSER", 8));
        for (int k = 0; k < 5000; k++) all.add(ev(k % 2 == 0 ? "plume" : "seismic", 10 + k * 0.1));
        all.add(feature("GEYSER", 600));
        all.add(ev("oceanEntry", 601));
        all.add(ev("oceanEntry", 602));
        all.add(ev("eruptionEnded", 700));
        for (JsonObject e : all) {
            log.addLast(e);
            if (Session.isMilestone(e, firsts)) milestones.addLast(e);
        }
        List<JsonObject> backlog = Session.selectBacklog(log, milestones, 650);
        List<String> kinds = backlog.stream().map(e -> e.get("kind").getAsString()).toList();
        assertTrue(kinds.containsAll(List.of("alertChanged", "eruptionStarted", "styleEstimated", "dikeStarted", "oceanEntry")), kinds.subList(0, 8).toString());
        assertEquals(1, backlog.stream().filter(e -> e.get("time").getAsDouble() == 8).count(), "first geyser kept once");
        assertFalse(kinds.contains("eruptionEnded"), "nothing after the replay cursor");
        assertEquals(2, kinds.stream().filter("oceanEntry"::equals).count(), "first ocean entry as milestone, the later one as recent");
        for (int i = 1; i < backlog.size(); i++)
            assertTrue(backlog.get(i - 1).get("time").getAsDouble() <= backlog.get(i).get("time").getAsDouble(), "time order");
        assertTrue(backlog.size() <= 1500 + 600 + milestones.size());
    }

    @Test
    void attachingAfterAnEruptionStartedReplaysIt() throws Exception {
        SimServer server = new SimServer(new SimServer.Config("127.0.0.1", 0, worlds, null, "stromboli", 1, 50_000, 200));
        server.createPreset("stromboli", 1);
        server.start();
        try {
            String vid;
            try (TestClient first = attach(server)) {
                vid = server.session("s1").worldInfo().getAsJsonArray("volcanoes").get(0).getAsJsonObject().get("id").getAsString();
                first.send("{\"type\":\"command\",\"requestId\":1,\"command\":{\"kind\":\"startEruption\",\"volcanoId\":\"" + vid + "\"}}");
                first.await(m -> m.json() != null && "events".equals(m.type()) && hasKind(m.json(), "eruptionStarted"), 60);
            }
            try (TestClient late = attach(server)) {
                JsonObject backlog = late.awaitType("events", 30).json();
                assertTrue(hasKind(backlog, "eruptionStarted"), "the late client's backlog has the eruption start");
            }
        } finally {
            server.close();
        }
    }

    private static boolean hasKind(JsonObject eventsMsg, String kind) {
        for (JsonElement e : eventsMsg.getAsJsonArray("events")) if (kind.equals(e.getAsJsonObject().get("kind").getAsString())) return true;
        return false;
    }

    private static TestClient attach(SimServer server) throws Exception {
        TestClient c = new TestClient(server.port(), SimServer.SUBPROTOCOL);
        c.send("{\"type\":\"hello\",\"protocol\":1,\"client\":\"test\"}");
        c.awaitType("welcome", 10);
        c.send("{\"type\":\"attach\",\"sessionId\":\"s1\"}");
        c.awaitType("attached", 60);
        return c;
    }
}
