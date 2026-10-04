package me.alex4386.typhon.engine.subsurface;

/**
 * Cumulative water accounting of the subsurface model (m³). Every volume entering or leaving the
 * modelled area is counted, so {@link #imbalance()} — inflows − outflows − stored — stays at
 * rounding level.
 *
 * @param rain rainfall on the modelled area
 * @param poured water added through {@code addWater} (buckets, hosts)
 * @param seeded standing water imported with the terrain (lakes)
 * @param initialGroundwater groundwater present in columns when they were first created
 * @param seaInflow water flowing from the sea onto land (surface)
 * @param deficit water created to keep a water table above the grid bottom (should stay 0)
 * @param evaporated evaporation from standing water
 * @param boiled groundwater turned into steam that left the ground
 * @param seaOutflow surface water reaching the sea
 * @param seaGroundwater net groundwater discharge into fixed-head (sea) columns
 * @param removed water taken out through {@code removeWater}
 * @param rejected water offered where the world is unknown (never entered; informational)
 * @param surfaceStorage water currently on the surface
 * @param groundStorage water currently in the aquifer and vadose zone (above the grid bottom)
 */
public record WaterBudget(
        double rain,
        double poured,
        double seeded,
        double initialGroundwater,
        double seaInflow,
        double deficit,
        double evaporated,
        double boiled,
        double seaOutflow,
        double seaGroundwater,
        double removed,
        double rejected,
        double surfaceStorage,
        double groundStorage) {

    public double inflow() {
        return rain + poured + seeded + initialGroundwater + seaInflow + deficit;
    }

    public double outflow() {
        return evaporated + boiled + seaOutflow + seaGroundwater + removed;
    }

    public double storage() {
        return surfaceStorage + groundStorage;
    }

    public double imbalance() {
        return inflow() - outflow() - storage();
    }
}
