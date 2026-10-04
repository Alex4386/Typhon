package me.alex4386.typhon.engine.geothermal;

import java.util.Arrays;

/**
 * Coarse 2D feature grid: per cell, the shallow-reservoir excess temperature over ambient (°C) and
 * liquid saturation (0..1) last sampled from the subsurface model.
 *
 * <p>Cell {@code (i, j)} covers blocks {@code [minX + i·s, minX + (i+1)·s) × [minZ + j·s, ...)} for
 * cell size {@code s}.
 */
public final class GeothermalGrid {
    private final int minX;
    private final int minZ;
    private final int sizeX;
    private final int sizeZ;
    private final int cellSize;
    final double[] excess;
    final double[] water;

    public GeothermalGrid(int minX, int minZ, int sizeX, int sizeZ, int cellSize) {
        if (sizeX < 1 || sizeZ < 1 || cellSize < 1) throw new IllegalArgumentException("grid must be non-empty");
        this.minX = minX;
        this.minZ = minZ;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        this.cellSize = cellSize;
        this.excess = new double[sizeX * sizeZ];
        this.water = new double[sizeX * sizeZ];
    }

    /** Grid covering {@code [cx - radius, cx + radius)} in both axes. */
    public static GeothermalGrid centeredOn(int centerX, int centerZ, int radius, int cellSize) {
        int cells = Math.max(1, (2 * radius + cellSize - 1) / cellSize);
        int extent = cells * cellSize;
        return new GeothermalGrid(centerX - extent / 2, centerZ - extent / 2, cells, cells, cellSize);
    }

    public int minX() { return minX; }
    public int minZ() { return minZ; }
    public int sizeX() { return sizeX; }
    public int sizeZ() { return sizeZ; }
    public int cellSize() { return cellSize; }
    public int cellCount() { return excess.length; }

    public int index(int i, int j) {
        return j * sizeX + i;
    }

    public int cellI(int index) {
        return index % sizeX;
    }

    public int cellJ(int index) {
        return index / sizeX;
    }

    public boolean containsBlock(int x, int z) {
        int i = Math.floorDiv(x - minX, cellSize);
        int j = Math.floorDiv(z - minZ, cellSize);
        return i >= 0 && i < sizeX && j >= 0 && j < sizeZ;
    }

    /** Index of the cell containing block column {@code (x, z)}, or -1 outside the grid. */
    public int indexOfBlock(int x, int z) {
        int i = Math.floorDiv(x - minX, cellSize);
        int j = Math.floorDiv(z - minZ, cellSize);
        if (i < 0 || i >= sizeX || j < 0 || j >= sizeZ) return -1;
        return index(i, j);
    }

    public int cellMinX(int index) {
        return minX + cellI(index) * cellSize;
    }

    public int cellMinZ(int index) {
        return minZ + cellJ(index) * cellSize;
    }

    public int cellCenterX(int index) {
        return cellMinX(index) + cellSize / 2;
    }

    public int cellCenterZ(int index) {
        return cellMinZ(index) + cellSize / 2;
    }

    public double excess(int index) {
        return excess[index];
    }

    public void setExcess(int index, double value) {
        excess[index] = value;
    }

    public double water(int index) {
        return water[index];
    }

    public void setWater(int index, double value) {
        water[index] = value;
    }

    public void fillExcess(double value) {
        Arrays.fill(excess, value);
    }

    public void fillWater(double value) {
        Arrays.fill(water, value);
    }

    /** Excess temperature at a block column (0 outside the grid). */
    public double excessAtBlock(int x, int z) {
        int index = indexOfBlock(x, z);
        return index < 0 ? 0 : excess[index];
    }

    public double totalExcess() {
        double sum = 0;
        for (double e : excess) sum += e;
        return sum;
    }

    public double maxExcess() {
        double max = 0;
        for (double e : excess) max = Math.max(max, e);
        return max;
    }
}
