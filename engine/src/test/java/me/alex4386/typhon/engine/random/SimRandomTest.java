package me.alex4386.typhon.engine.random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class SimRandomTest {
    @Test
    void sameSeedSameSequence() {
        SimRandom a = new SimRandom(42);
        SimRandom b = new SimRandom(42);
        for (int i = 0; i < 1000; i++) {
            assertEquals(a.nextLong(), b.nextLong());
            assertEquals(a.nextGaussian(), b.nextGaussian());
        }
    }

    @Test
    void forkIsIndependentOfParentConsumptionAndForkOrder() {
        SimRandom parentA = new SimRandom(7);
        SimRandom lavaA = parentA.fork("lava");

        SimRandom parentB = new SimRandom(7);
        parentB.nextLong();
        parentB.fork("seismic");
        SimRandom lavaB = parentB.fork("lava");

        for (int i = 0; i < 100; i++) {
            assertEquals(lavaA.nextLong(), lavaB.nextLong());
        }
    }

    @Test
    void differentLabelsDiverge() {
        SimRandom root = new SimRandom(7);
        assertNotEquals(root.fork("lava").nextLong(), root.fork("seismic").nextLong());
    }

    @Test
    void gaussianHasRoughlyUnitMoments() {
        SimRandom random = new SimRandom(1);
        int n = 200_000;
        double sum = 0, sumSq = 0;
        for (int i = 0; i < n; i++) {
            double g = random.nextGaussian();
            sum += g;
            sumSq += g * g;
        }
        double mean = sum / n;
        assertEquals(0.0, mean, 0.01);
        assertEquals(1.0, sumSq / n - mean * mean, 0.02);
    }
}
