package me.alex4386.typhon.engine.massflow;

import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.massflow.MassFlowEvents.Trigger;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.sim.Engine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Throughput smoke test; run with {@code ./gradlew :engine:perfTest}. */
@Tag("perf")
class MassFlowPerformanceTest {
    @Test
    void tenThousandActiveCells() {
        // 128×128 ramp with a 1 m sheet released over ~100×100 columns.
        MassFlowTestWorld world = new MassFlowTestWorld(0, 0, 7, 7, (x, z) -> 200 - x / 3);
        MassFlowConfig config = MassFlowConfig.lahar();
        config.sedimentationTimescale = 1e9; // keep the sheet flowing for the measurement
        config.stopTimescale = 1e9;
        Lahars field = new Lahars("perf", world.terrain, config);
        Engine engine = world.engine(field, 1);
        field.release(new BlockPos(60, 0, 64), 56, 10_000, 15, 0.2, Trigger.MANUAL);
        world.run(engine, 20); // warm-up

        int steps = 100; // engine ticks; the field steps every config.stepIntervalTicks
        long start = System.nanoTime();
        long cellSteps = 0;
        long cellSubsteps = 0;
        for (int i = 0; i < steps; i++) {
            int cells = field.activeCellCount();
            engine.tick();
            if (i % config.stepIntervalTicks == 0) {
                cellSteps += cells;
                cellSubsteps += (long) cells * field.lastSubsteps();
            }
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        int fieldSteps = steps / config.stepIntervalTicks;
        System.out.printf("massflow perf: %.2f M cell-substeps/s, %.2f ms/field-step (avg %d cells, %.1f substeps)%n",
                cellSubsteps / seconds / 1e6, seconds * 1000 / fieldSteps, cellSteps / fieldSteps,
                (double) cellSubsteps / cellSteps);
        assertTrue(cellSteps / fieldSteps >= 5_000);
        assertTrue(seconds < 60, "took " + seconds + " s");
    }
}
