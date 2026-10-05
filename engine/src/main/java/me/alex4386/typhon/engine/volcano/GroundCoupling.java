package me.alex4386.typhon.engine.volcano;

/**
 * What surface processes (lava, pyroclastic flows, dikes) exchange with the ground and its water:
 * the subsurface model implements it. Calls are made sequentially, in a deterministic order, from
 * the engine thread.
 */
public interface GroundCoupling {
    /** No ground model: no water, heat goes nowhere. */
    GroundCoupling NONE = new GroundCoupling() {
        @Override public double surfaceWaterDepthM(int x, int z) { return 0; }
        @Override public double removeSurfaceWater(int x, int z, double volumeM3) { return 0; }
        @Override public void addGroundHeat(int x, int z, double joules) {}
        @Override public void addIntrusionHeat(double x, double z, double depthM, double areaM2, double widthM,
                double temperatureC) {}
    };

    /** Depth (m) of standing water (sea, lakes, ponds, poured water) on a column. */
    double surfaceWaterDepthM(int x, int z);

    /** Removes up to {@code volumeM3} of surface water at a column (boiled or displaced); returns the volume removed. */
    double removeSurfaceWater(int x, int z, double volumeM3);

    /** Heat (J) conducted into the ground under a column. */
    void addGroundHeat(int x, int z, double joules);

    /**
     * Width (columns) of the ground model's heat cells: callers may sum heat over aligned blocks of
     * this size and hand it over once per block (at the block's centre column).
     */
    default int heatCellColumns() {
        return 1;
    }

    /**
     * Heat of an intruded sheet segment cooling into its host rock: centred on column {@code (x, z)}
     * at {@code depthM} physical metres below the ground, {@code areaM2} of sheet {@code widthM}
     * thick, emplaced at {@code temperatureC} (it releases {@code ρ (c ΔT + L) · width · area}).
     */
    void addIntrusionHeat(double x, double z, double depthM, double areaM2, double widthM, double temperatureC);
}
