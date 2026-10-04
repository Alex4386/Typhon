package me.alex4386.typhon.engine.world;

/**
 * Write access to the world model. All edits keep each column a contiguous stack of layers from the
 * datum to the surface.
 */
public interface WorldEdit {
    /** Registers a new stratigraphic unit and returns its id. */
    int newUnit(UnitRecord unit);

    /**
     * Lays {@code thickness} metres of {@code material} on top of a known column as part of
     * {@code unit} (default flags and porosity of the material).
     */
    boolean deposit(int x, int z, double thickness, Material material, int unit);

    /** {@link #deposit} with explicit flags, porosity (0–1) and welding (0–1). */
    boolean deposit(int x, int z, double thickness, Material material, int unit, int flags, double porosity,
            double welding);

    /**
     * Removes up to {@code thickness} metres from the top; with {@code looseOnly}, only unconsolidated
     * layers (lahar entrainment, rain wash).
     */
    ErodeResult erode(int x, int z, double thickness, boolean looseOnly);

    /**
     * Turns {@code [zLo, zHi]} into a cavity (lava tube, tunnel). If the range reaches the surface the
     * column is dug down to {@code zLo} instead (an open pit, not a buried void).
     */
    ErodeResult carve(int x, int z, double zLo, double zHi, int unit);

    /**
     * Fills {@code [zLo, zHi]} with {@code material}, replacing whatever was there (including
     * cavities); the part above the surface, if any, is deposited.
     */
    ErodeResult fill(int x, int z, double zLo, double zHi, Material material, int unit);

    /**
     * Adds {@code volumeM3} of water at a column (rain, a player's bucket). Held until the surface
     * water model consumes it (see {@link WorldModel#setWaterSink}).
     */
    void addWater(int x, int z, double volumeM3);
}
