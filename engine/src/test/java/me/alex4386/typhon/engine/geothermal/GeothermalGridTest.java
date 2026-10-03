package me.alex4386.typhon.engine.geothermal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GeothermalGridTest {
    private static GeothermalGrid pulse(double value) {
        GeothermalGrid grid = new GeothermalGrid(0, 0, 21, 21, 4);
        grid.setExcess(grid.index(10, 10), value);
        return grid;
    }

    @Test
    void heatSpreadsOutwardAndIsConservedWithoutLoss() {
        GeothermalGrid grid = pulse(100);
        int center = grid.index(10, 10);
        grid.diffuse(600, 0.05, 0, new double[grid.cellCount()]);

        assertTrue(grid.excess(center) < 100);
        assertTrue(grid.excess(grid.index(11, 10)) > 0);
        assertTrue(grid.excess(grid.index(11, 10)) > grid.excess(grid.index(13, 10)));
        assertEquals(grid.excess(grid.index(9, 10)), grid.excess(grid.index(11, 10)), 1e-12); // symmetric
        assertEquals(100, grid.totalExcess(), 1e-6);
    }

    @Test
    void surfaceLossDecaysHeat() {
        GeothermalGrid lossless = pulse(100);
        GeothermalGrid lossy = pulse(100);
        lossless.diffuse(3600, 0.05, 0, new double[lossless.cellCount()]);
        lossy.diffuse(3600, 0.05, 1.0 / 7200, new double[lossy.cellCount()]);
        // Loss is uniform, so it scales the whole field by ≈ exp(−λt) on top of boundary leakage.
        assertEquals(Math.exp(-0.5), lossy.totalExcess() / lossless.totalExcess(), 0.01);
    }

    @Test
    void hugeStepsStayStableAndNonNegative() {
        GeothermalGrid grid = pulse(100);
        assertTrue(grid.substepsFor(1e6, 0.05, 1e-4) > 1);
        grid.diffuse(1e6, 0.05, 1e-4, new double[grid.cellCount()]);
        for (int i = 0; i < grid.cellCount(); i++) {
            double e = grid.excess(i);
            assertTrue(Double.isFinite(e) && e >= 0 && e <= 100, "cell " + i + " = " + e);
        }
    }

    @Test
    void steadyStateMatchesSourceOverLoss() {
        GeothermalGrid grid = new GeothermalGrid(0, 0, 5, 5, 4);
        double[] source = new double[grid.cellCount()];
        java.util.Arrays.fill(source, 0.01);
        grid.diffuse(1e6, 0, 1e-3, source);
        assertEquals(10.0, grid.excess(grid.index(2, 2)), 1e-6);
    }

    @Test
    void blockToCellMapping() {
        GeothermalGrid grid = GeothermalGrid.centeredOn(0, 0, 32, 4);
        assertEquals(16, grid.sizeX());
        assertEquals(-32, grid.minX());
        assertEquals(grid.index(0, 0), grid.indexOfBlock(-32, -29));
        assertEquals(grid.index(8, 8), grid.indexOfBlock(0, 3));
        assertEquals(-1, grid.indexOfBlock(32, 0));
        assertEquals(-1, grid.indexOfBlock(-33, 0));
    }
}
