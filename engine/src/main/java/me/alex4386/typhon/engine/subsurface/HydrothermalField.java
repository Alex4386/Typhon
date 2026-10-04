package me.alex4386.typhon.engine.subsurface;

/**
 * Read access to the subsurface state that hydrothermal manifestations depend on, plus the heat
 * hook surface flows use. Horizontal coordinates are world-model column indices; depths are metres
 * below the local ground surface.
 */
public interface HydrothermalField {
    /** Whether the column lies inside the modelled area. */
    boolean known(int x, int z);

    /** Ground temperature (°C) at {@code depthM} below the surface. */
    double temperatureC(int x, int z, double depthM);

    /** Depth of the water table below the ground (m); negative when it stands above (springs, lakes). */
    double waterTableDepthM(int x, int z);

    /** Fraction of the pore space filled with steam at {@code depthM}, 0–1. */
    double steamFraction(int x, int z, double depthM);

    /** Steam leaving the ground above the column (kg/s per m²), from boiling below. */
    double steamFluxKgPerSm2(int x, int z);

    /** Standing/flowing water depth above the ground (m), the sea depth below sea level. */
    double surfaceWaterDepthM(int x, int z);

    /** Adds heat (J) to the top of the ground at a column (lava, pyroclastic flows resting on it). */
    void addSurfaceHeat(int x, int z, double joules);
}
