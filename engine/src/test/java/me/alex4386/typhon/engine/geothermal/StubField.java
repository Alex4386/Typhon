package me.alex4386.typhon.engine.geothermal;

import me.alex4386.typhon.engine.subsurface.HydrothermalField;
import me.alex4386.typhon.engine.terrain.TerrainModel;

/**
 * Test double for the subsurface model: reservoir temperature and liquid saturation set per column
 * by functions (uniform by default), so feature tests can pin the hydrothermal state.
 */
final class StubField implements HydrothermalField {
    interface ColumnValue {
        double at(int x, int z);
    }

    private final TerrainModel terrain;
    private final double reservoirDepthM;
    ColumnValue temperature;
    ColumnValue water;
    ColumnValue surfaceWater = (x, z) -> 0;
    double surfaceHeat;

    StubField(TerrainModel terrain, double reservoirDepthM, double temperatureC, double water) {
        this.terrain = terrain;
        this.reservoirDepthM = reservoirDepthM;
        this.temperature = (x, z) -> temperatureC;
        this.water = (x, z) -> water;
    }

    void setTemperature(double temperatureC) {
        temperature = (x, z) -> temperatureC;
    }

    @Override
    public boolean known(int x, int z) {
        return terrain.isKnown(x, z);
    }

    @Override
    public double temperatureC(int x, int z, double depthM) {
        return temperature.at(x, z);
    }

    /** Inverse of the saturation mapping in {@link Geothermal}: {@code d = 2R(1 − w)}. */
    @Override
    public double waterTableDepthM(int x, int z) {
        return 2 * reservoirDepthM * (1 - water.at(x, z));
    }

    @Override
    public double steamFraction(int x, int z, double depthM) {
        return 0;
    }

    @Override
    public double steamFluxKgPerSm2(int x, int z) {
        return 0;
    }

    @Override
    public double surfaceWaterDepthM(int x, int z) {
        return surfaceWater.at(x, z);
    }

    @Override
    public void addSurfaceHeat(int x, int z, double joules) {
        surfaceHeat += joules;
    }
}
