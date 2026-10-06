package me.alex4386.typhon.simulator.scenario;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import me.alex4386.typhon.engine.worlds.World;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Heap and step cost of one real-scale world at a given core size (sizing the default world windows;
 * see {@code RealPresets.WORLD_CORE_EXTENT_M}). Opt-in:
 * {@code TYPHON_MEM_PROBE=<preset>:<coreExtentM>[:<steps>]}, e.g. {@code kilauea-real:24000:400}.
 */
class WorldMemoryProbe {
    @TempDir
    Path dir;

    @Test
    void heapOfOneWorld() throws Exception {
        String spec = System.getenv("TYPHON_MEM_PROBE");
        Assumptions.assumeTrue(spec != null && !spec.isBlank(), "set TYPHON_MEM_PROBE to run");
        String[] p = spec.split(":");
        String preset = p[0];
        double core = Double.parseDouble(p[1]);
        int steps = p.length > 2 ? Integer.parseInt(p[2]) : 400;

        long before = usedAfterGc();
        Path world = dir.resolve("w");
        WorldScenarios.writeFromPreset(Presets.get(preset), 1, world);
        Path yaml = world.resolve("world.yaml");
        String text = Files.readString(yaml).replaceAll("(?m)^  coreExtentM: .*\\n", "");
        text = text.replaceFirst("(?m)^terrain:\\n", "terrain:\n  coreExtentM: " + core + "\n");
        Files.writeString(yaml, text);

        long t0 = System.nanoTime();
        var definition = new me.alex4386.typhon.engine.worlds.WorldDirectory(world).readWorld();
        WorldScenarios.terrain(definition, world);
        System.out.printf(Locale.ROOT, "MEMPROBE generate grid %d ms%n", (System.nanoTime() - t0) / 1_000_000);
        long ta = System.nanoTime();
        Scenario scenario = WorldScenarios.open(world, World.ChangePolicy.REJECT);
        System.out.printf(Locale.ROOT, "MEMPROBE open %d ms%n", (System.nanoTime() - ta) / 1_000_000);
        for (int i = 0; i < 40; i++) {
            long ts = System.nanoTime();
            scenario.engine().step(); // import, first subsurface pass, warm-up
            long d = (System.nanoTime() - ts) / 1_000_000;
            if (d > 200) System.out.printf(Locale.ROOT, "MEMPROBE step %d took %d ms%n", i, d);
        }
        long setup = (System.nanoTime() - t0) / 1_000_000;
        long t1 = System.nanoTime();
        for (int i = 0; i < steps; i++) scenario.engine().step();
        long ms = (System.nanoTime() - t1) / 1_000_000;
        System.out.printf(Locale.ROOT, "MEMPROBE setup (open + 40 steps) %d ms, then %.1f ms/step%n", setup, ms / (double) steps);
        long used = usedAfterGc() - before;
        int size = scenario.initialTerrain().size();
        System.out.printf(Locale.ROOT, "MEMPROBE %s core=%.0f m columns=%d^2=%d heap=%.0f MB (%.2f KB/column) steps=%d in %d ms%n",
                preset, core, size, size * size, used / 1e6, used / 1024.0 / ((double) size * size), steps, ms);
        // keep the scenario reachable until measured
        if (scenario.engine().time() < 0) throw new AssertionError();
    }

    private static long usedAfterGc() throws InterruptedException {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 4; i++) {
            System.gc();
            Thread.sleep(100);
        }
        return rt.totalMemory() - rt.freeMemory();
    }
}
