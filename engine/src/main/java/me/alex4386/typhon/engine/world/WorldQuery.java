package me.alex4386.typhon.engine.world;

/**
 * Read access to the world model. Horizontal coordinates are integer column indices (a column is
 * {@link WorldSpec#metersPerColumn()} wide); elevations are real metres.
 */
public interface WorldQuery {
    WorldSpec spec();

    boolean isKnown(int x, int z);

    /**
     * Ground surface elevation, {@code NaN} if unknown. Includes {@link #uplift}: every elevation of the world
     * model is of the deformed ground.
     */
    double surfaceZ(int x, int z);

    /** Accumulated ground deformation (m): already part of {@link #surfaceZ} and every other elevation. */
    double uplift(int x, int z);

    /** Standing-water surface elevation, {@code NaN} if dry. */
    double waterZ(int x, int z);

    int layerCount(int x, int z);

    /** Layer {@code i}, 0 = bottom. */
    LayerView layer(int x, int z, int i);

    /** Material at a point: {@link MaterialTable#AIR} above the ground or in unknown columns. */
    Material materialAt(int x, int z, double elevation);

    /** Unit at a point, {@code -1} above the ground or in unknown columns. */
    int unitAt(int x, int z, double elevation);

    /** Whether there is solid ground (rock, tephra, soil, ice; not a cavity) at a point. */
    boolean isSolid(int x, int z, double elevation);

    ColumnProfile column(int x, int z);

    /**
     * Vertical section along a polyline given as {@code {x0, z0, x1, z1, ...}} in metres,
     * sampled at {@code nu} evenly spaced points and {@code nz} rows between {@code zMin} and
     * {@code zMax}.
     */
    SectionRaster section(double[] polylineXZ, double zMin, double zMax, int nu, int nz);

    UnitRecord unit(int id);

    /** Change counter of a column (for caches). */
    int version(int x, int z);
}
