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
