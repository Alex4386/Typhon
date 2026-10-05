package me.alex4386.typhon.engine.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ParallelTest {
    @Test
    void freshPoolsRunTheirFirstRegionImmediately() {
        // a pool's first region is published while its workers may still be starting up
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            for (int threads = 5; threads <= 9; threads++) {
                Parallel p = Parallel.of(threads);
                int[] hits = new int[1000];
                p.forEach(hits.length, i -> hits[i]++);
                for (int h : hits) assertEquals(1, h);
            }
        });
    }

    @Test
    void everyItemRunsExactlyOnceUnderLoad() {
        Parallel p = Parallel.of(4);
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            for (int round = 0; round < 20_000; round++) {
                int n = 1 + round % 37;
                AtomicInteger sum = new AtomicInteger();
                p.forEach(n, i -> sum.addAndGet(i + 1));
                assertEquals(n * (n + 1) / 2, sum.get());
            }
        });
    }

    @Test
    void sumIsIndependentOfThreadCount() {
        double one = Parallel.sequential().sum(10_000, i -> 1.0 / (i + 1));
        for (int threads : new int[] {2, 3, 4, 8}) {
            assertEquals(Double.doubleToLongBits(one),
                    Double.doubleToLongBits(Parallel.of(threads).sum(10_000, i -> 1.0 / (i + 1))));
        }
    }

    @Test
    void mapKeepsOrderAndNestedRegionsRunInline() {
        Parallel p = Parallel.of(4);
        List<Integer> out = p.map(List.of(1, 2, 3, 4, 5, 6, 7, 8), x -> {
            int[] inner = new int[1];
            p.forEach(10, i -> inner[0] += i); // nested: inline, so this unsynchronised sum is safe
            return x * 100 + inner[0];
        });
        assertEquals(List.of(145, 245, 345, 445, 545, 645, 745, 845), out);
    }

    @Test
    void failuresPropagateAndThePoolStaysUsable() {
        Parallel p = Parallel.of(4);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> p.forEach(100, i -> {
            if (i == 57) throw new IllegalStateException("boom");
        }));
        assertEquals("boom", e.getMessage());
        int[] hits = new int[100];
        p.forEach(hits.length, i -> hits[i]++);
        for (int h : hits) assertTrue(h == 1);
    }
}
