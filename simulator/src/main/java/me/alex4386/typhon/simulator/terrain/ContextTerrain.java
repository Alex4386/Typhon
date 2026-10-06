package me.alex4386.typhon.simulator.terrain;

/**
 * Coarse terrain around the simulated domain: what lies beyond the core's columns, out to a
 * configurable extent (tens of kilometres), so the landscape does not end at the edge of the
 * simulation.
 *
 * <p>It is a clipmap of levels {@code 1…K}: level {@code ℓ} has cells of {@code 2^ℓ} columns and
 * covers {@code 2^ℓ} times the core's half-width around the core's centre (clamped to the context
 * extent), so every level holds about as many cells as the core holds columns. The terrain is
 * static: the generator's continuous surface ({@link ColumnGrid#relief()}) where there is one,
 * otherwise the nearest core edge column (a DEM with no data beyond the domain). Inside the core,
 * the levels are block means of the live columns (computed by whoever samples the core).
 *
 * <p>Coordinates are fractional column coordinates of the core (column {@code x} spans
 * {@code [x, x+1)}), so levels align with the core's columns and tiles: a level-ℓ cell boundary is
 * a column boundary, and the core's chunk-aligned edges fall on cell boundaries for {@code ℓ ≤ 4}.
 */
public final class ContextTerrain {
    private final ColumnGrid core;
    private final double metersPerColumn;
    private final double halfExtentColumns;
    private final int levels;
    private final ColumnGrid.Relief relief;

    /**
     * @param core the simulated domain's initial terrain
     * @param metersPerColumn column size (m)
     * @param extentM full width of the context (m), centred on the core; at most the core's own width
     *     gives no context levels
     */
    public ContextTerrain(ColumnGrid core, double metersPerColumn, double extentM) {
        this.core = core;
        this.metersPerColumn = metersPerColumn;
        double coreHalf = core.size() / 2.0;
        this.halfExtentColumns = Math.max(coreHalf, extentM / metersPerColumn / 2);
        int k = 0;
        while (coreHalf * (1L << k) < halfExtentColumns && k < 12) k++;
        this.levels = k;
        this.relief = core.relief();
    }

    /** Number of coarse levels ({@code 0} = no context). */
    public int levels() {
        return levels;
    }

    public double metersPerColumn() {
        return metersPerColumn;
    }

    /** Context half-width in columns, around the core's centre. */
    public double halfExtentColumns() {
        return halfExtentColumns;
    }

    /** Core centre in fractional column coordinates. */
    public double centerX() {
        return core.minX() + core.size() / 2.0;
    }

    public double centerZ() {
        return core.minZ() + core.size() / 2.0;
    }

    /**
     * Half-width (columns) of level {@code level}'s coverage: {@code 2^ℓ} core half-widths, clamped to
     * the context extent.
     */
    public double levelHalfColumns(int level) {
        return Math.min(halfExtentColumns, core.size() / 2.0 * (1L << level));
    }

    /** Whether column ({@code x}, {@code z}) belongs to the simulated core. */
    public boolean inCore(int x, int z) {
        return core.contains(x, z);
    }

    /**
     * Initial ground elevation (m) at fractional column coordinates: the generator's surface, or the
     * nearest core column's top. Inside the core this is the initial (not the live) surface.
     */
    public double elevation(double cx, double cz) {
        if (relief != null) return relief.topBlocks(cx, cz) * metersPerColumn;
        int x = (int) Math.max(core.minX(), Math.min(core.maxX(), Math.floor(cx)));
        int z = (int) Math.max(core.minZ(), Math.min(core.maxZ(), Math.floor(cz)));
        return (core.ground(x, z) + 1) * metersPerColumn;
    }

    /**
     * Mean initial elevation (m) over the square of columns {@code [x0, x0+n) × [z0, z0+n)}, from up
     * to 4×4 samples.
     */
    public double meanElevation(int x0, int z0, int n) {
        int s = Math.min(n, 4);
        double sum = 0;
        for (int j = 0; j < s; j++) {
            for (int i = 0; i < s; i++) sum += elevation(x0 + (i + 0.5) * n / s, z0 + (j + 0.5) * n / s);
        }
        return sum / (s * s);
    }
}
