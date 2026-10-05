package me.alex4386.typhon.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import me.alex4386.typhon.engine.worlds.World;
import me.alex4386.typhon.simulator.scenario.Presets;
import me.alex4386.typhon.simulator.scenario.WorldScenarios;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** World sessions persist replay keyframes in the world directory and can seek them. */
class WorldReplayTest {
    @TempDir
    Path tmp;

    private static long keyframeDirs(Path world) throws Exception {
        Path root = world.resolve(Session.REPLAY_DIR);
        if (!Files.isDirectory(root)) return 0;
        try (Stream<Path> s = Files.list(root)) {
            return s.count();
        }
    }

    private static int keyframeCount(Session s) {
        JsonArray k = s.replayInfo().getAsJsonArray("keyframes");
        return k.size();
    }

    @Test
    void keyframesArePersistedSeekableAndPrunedOnReopen() throws Exception {
        Path dir = tmp.resolve("kilauea");
        WorldScenarios.writeFromPreset(Presets.get("kilauea"), 1, dir);

        try (Session s = Session.world("w", dir, World.ChangePolicy.ACCEPT, 1000)) {
            s.transport("UNBOUNDED", null);
            long deadline = System.nanoTime() + 240_000_000_000L;
            while (keyframeCount(s) < 2 && System.nanoTime() < deadline) {
                s.maybeKeyframe();
                Thread.sleep(50);
            }
            assertTrue(keyframeCount(s) >= 2, "two keyframes (t = 0 and ≥ 300 s)");
            assertTrue(keyframeDirs(dir) >= 2, "world keyframes are written to <world>/replay");

            double first = s.replayInfo().getAsJsonArray("keyframes").get(0).getAsDouble();
            s.enterReplay();
            double landed = s.seek(0);
            assertEquals(first, landed, 1e-6, "seeking before the first keyframe lands on it");
            assertEquals(landed, s.time(), 1e-9);
            s.exitReplay();
        }

        // The world was never saved, so it reopens at t = 0: later keyframes describe a run that
        // no longer exists and are dropped.
        try (Session again = Session.world("w2", dir, World.ChangePolicy.ACCEPT, 1)) {
            JsonArray kept = again.replayInfo().getAsJsonArray("keyframes");
            for (var k : kept) assertTrue(k.getAsDouble() <= again.time() + 1e-6, "no keyframe from the unsaved future");
            assertEquals(kept.size(), keyframeDirs(dir), "pruned keyframes are deleted from disk");
        }
    }
}
