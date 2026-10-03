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

    @Test
    void restoreResumesSequence() {
        SimRandom a = new SimRandom(5);
        for (int i = 0; i < 17; i++) a.nextLong();
        SimRandom b = new SimRandom(5);
        b.restore(a.state());
        for (int i = 0; i < 100; i++) assertEquals(a.nextLong(), b.nextLong());
    }

    @Test
    void boundedIntsStayInRangeAndCoverIt() {
        SimRandom random = new SimRandom(3);
        int[] counts = new int[7];
        for (int i = 0; i < 70_000; i++) counts[random.nextInt(7)]++;
        for (int c : counts) assertEquals(10_000, c, 500);
        for (int i = 0; i < 10_000; i++) {
            int v = random.nextInt(-5, 5);
            org.junit.jupiter.api.Assertions.assertTrue(v >= -5 && v < 5);
        }
    }

    @Test
    void poissonAndExponentialHaveExpectedMeans() {
        SimRandom random = new SimRandom(11);
        int n = 50_000;
        double poisson = 0, poissonLarge = 0, exp = 0;
        for (int i = 0; i < n; i++) {
            poisson += random.nextPoisson(3.5);
            poissonLarge += random.nextPoisson(80);
            exp += random.nextExponential(2.0);
        }
        assertEquals(3.5, poisson / n, 0.05);
        assertEquals(80, poissonLarge / n, 0.5);
        assertEquals(0.5, exp / n, 0.01);
    }
}
