package me.alex4386.typhon.engine.sim;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Dispatch latency of an (almost) empty parallel region. */
@Tag("perf")
class ParallelLatencyPerfTest {
    @Test
    void emptyRegionLatency() {
        for (int threads : new int[] {2, 4}) {
            Parallel p = Parallel.of(threads);
            double[] sink = new double[64];
            for (int warm = 0; warm < 20_000; warm++) p.forEach(64, i -> sink[i] += i);
            int regions = 100_000;
            long start = System.nanoTime();
            for (int r = 0; r < regions; r++) p.forEach(64, i -> sink[i] += i);
            double micros = (System.nanoTime() - start) / 1e3 / regions;
            System.out.printf("threads=%d: %.2f µs per region%n", threads, micros);
        }
    }
}
