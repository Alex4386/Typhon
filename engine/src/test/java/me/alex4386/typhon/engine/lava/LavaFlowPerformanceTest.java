package me.alex4386.typhon.engine.lava;

import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.sim.Engine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Throughput smoke test (tagged {@code perf}; run with {@code ./gradlew :engine:perfTest}). */
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

        // Best of several batches, so a busy machine does not hide the steady-state cost.
        int batches = 5;
        int steps = 60;
        double bestPerStepMs = Double.MAX_VALUE;
        double bestRate = 0;
        long cells = 0;
        long total = 0;
        double seconds = 0;
        for (int b = 0; b < batches; b++) {
            long start = System.nanoTime();
            long cellSteps = 0;
            for (int i = 0; i < steps; i++) {
                cellSteps += lava.activeCellCount();
                engine.step();
            }
            double s = (System.nanoTime() - start) / 1e9;
            seconds += s;
            total += cellSteps;
            cells = cellSteps / steps;
            bestPerStepMs = Math.min(bestPerStepMs, s * 1000 / steps);
            bestRate = Math.max(bestRate, cellSteps / s / 1e6);
        }
        System.out.printf("lava perf: best %.2f M cell-steps/s, %.2f ms/step (avg %d cells; %d cell-steps in %.3f s)%n",
                bestRate, bestPerStepMs, cells, total, seconds);
        assertTrue(cells >= 10_000);
        assertTrue(seconds < 30, "took " + seconds + " s");
    }
}
