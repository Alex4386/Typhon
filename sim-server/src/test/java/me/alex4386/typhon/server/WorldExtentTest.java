package me.alex4386.typhon.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import me.alex4386.typhon.server.protocol.Codecs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** When the simulated area grows, watchers get a worldExtent message and the new tiles, at stable coordinates. */
class WorldExtentTest {
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

    @Test
    void growthExtendsTheTileRangeAndStreamsTheNewTiles() throws Exception {
        try (TestClient c = new TestClient(server.port(), SimServer.SUBPROTOCOL)) {
            c.send("{\"type\":\"hello\",\"protocol\":1,\"client\":\"test\"}");
            c.awaitType("welcome", 10);
            c.send("{\"type\":\"attach\",\"sessionId\":\"s1\"}");
            JsonObject world = c.awaitType("attached", 30).json().getAsJsonObject("world");
            JsonObject tiles = world.getAsJsonObject("tiles");
            int maxTx = tiles.get("maxTx").getAsInt();
            int minTx = tiles.get("minTx").getAsInt();
            assertEquals(minTx, 0);
            JsonElement origin = world.get("origin");
            assertTrue(world.getAsJsonArray("simulated").size() > 0);
            c.send("{\"type\":\"transport\",\"mode\":\"PAUSED\"}");
            c.send("{\"type\":\"subscribe\",\"fields\":[1]}");

            // something active just east of the core (as a lava front would be)
            Session s = server.session("s1");
            int east = s.map().maxX + 4;
            int row = (s.map().minZ + s.map().maxZ) / 2;
            s.call(sc -> {
                sc.expansion().addSource(sink -> sink.active(east, row));
                return null;
            }).get();
            c.send("{\"type\":\"step\",\"seconds\":2}");

            JsonObject extent = c.awaitType("worldExtent", 60).json();
            JsonObject grown = extent.getAsJsonObject("tiles");
            assertTrue(grown.get("maxTx").getAsInt() > maxTx, extent.toString());
            assertEquals(minTx, grown.get("minTx").getAsInt(), "growth east keeps the west edge");
            assertTrue(extent.getAsJsonObject("expansion").get("addedTiles").getAsInt() > 0);
            boolean newTileSimulated = false;
            for (JsonElement e : extent.getAsJsonArray("simulated")) {
                if (e.getAsJsonArray().get(0).getAsInt() > maxTx) newTileSimulated = true;
            }
            assertTrue(newTileSimulated, "the new tiles are listed as simulated");

            // the new column of tiles streams at its protocol coordinates; the origin did not move
            int newTx = grown.get("maxTx").getAsInt();
            boolean seen = false;
            long deadline = System.nanoTime() + 60_000_000_000L;
            c.send("{\"type\":\"flow\",\"tilesProcessed\":" + c.binaries.get() + "}");
            while (!seen && System.nanoTime() < deadline) {
                TestClient.Msg m = c.await(x -> x.binary() != null, 30);
                Codecs.TileFrame f = Codecs.decodeTileFrame(m.binary());
                c.send("{\"type\":\"flow\",\"tilesProcessed\":" + c.binaries.get() + "}");
                if (f.level() == 0 && f.field() == 1 && f.tileX() == newTx) seen = true;
            }
            assertTrue(seen, "a tile of the new column arrives");
            c.send("{\"type\":\"attach\",\"sessionId\":\"s1\"}");
            JsonObject again = c.awaitType("attached", 30).json().getAsJsonObject("world");
            assertEquals(origin, again.get("origin"));
            assertEquals(newTx, again.getAsJsonObject("tiles").get("maxTx").getAsInt());
        }
    }
}
