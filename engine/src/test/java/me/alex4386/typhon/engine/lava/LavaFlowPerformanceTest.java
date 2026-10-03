package me.alex4386.typhon.engine.lava;

import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.sim.Engine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Smoke test for throughput; exclude with {@code -PexcludeTags=perf} style filters if needed. */
@Tag("perf")
class LavaFlowPerformanceTest {
    @Test
    void tenThousandActiveCells() {
        // 128×128 ramp with a 0.5 m sheet on 100×100 columns: every cell computes fluxes.
        LavaTestWorld world = new LavaTestWorld(0, 0, 7, 7, (x, z) -> 200 - x / 2);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(0));
        Engine engine = world.engine(lava, 1);
        for (int x = 10; x < 110; x++) {
            for (int z = 10; z < 110; z++) lava.addLava(x, z, 0.5, 1150, 50, 0.1);
        }
        world.run(engine, 20); // warm-up

        int steps = 200;
        long start = System.nanoTime();
        long cellSteps = 0;
        for (int i = 0; i < steps; i++) {
            cellSteps += lava.activeCellCount();
            engine.tick();
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        double perStepMs = seconds * 1000 / steps;
        System.out.printf("lava perf: %d cell-steps in %.3f s = %.2f M cell-steps/s, %.2f ms/step (avg %d cells)%n",
                cellSteps, seconds, cellSteps / seconds / 1e6, perStepMs, cellSteps / steps);
        assertTrue(cellSteps / steps >= 10_000);
        assertTrue(seconds < 30, "took " + seconds + " s");
    }
}
