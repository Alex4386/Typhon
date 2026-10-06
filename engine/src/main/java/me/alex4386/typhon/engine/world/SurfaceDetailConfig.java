package me.alex4386.typhon.engine.world;

/**
 * Where a volcano's {@link SurfaceDetail} lies and how fine it is (volcano YAML {@code detail:}).
 *
 * @param radiusM half-width of the square detail region around the primary vent (m); 0 disables it
 * @param metersPerCell target detail cell size (m); the refinement is the nearest whole number of
 *     cells per column ({@code 1…16})
 */
public record SurfaceDetailConfig(double radiusM, double metersPerCell) {
    public static final SurfaceDetailConfig DISABLED = new SurfaceDetailConfig(0, 1);

    public SurfaceDetailConfig {
        if (!(radiusM >= 0)) throw new IllegalArgumentException("detail.radiusM must be >= 0");
        if (!(metersPerCell > 0)) throw new IllegalArgumentException("detail.metersPerCell must be > 0");
    }

    /**
     * Crater-resolving default: cells of a quarter column (1–5 m) over eight crater radii (150–1500 m)
     * around the vent.
     */
    public static SurfaceDetailConfig defaults(double metersPerColumn, double craterRadiusM) {
        double cell = Math.max(1, Math.min(5, metersPerColumn / 4));
        double radius = Math.max(150, Math.min(1500, 8 * craterRadiusM));
        return new SurfaceDetailConfig(radius, cell);
    }

    public boolean enabled() {
        return radiusM > 0;
    }

    /**
     * Detail cells per column edge at column size {@code metersPerColumn}: the power of two
     * ({@code 1…16}) nearest to {@code metersPerColumn / metersPerCell}, so detail levels nest in the
     * tile pyramid (protocol level {@code −log₂ r}).
     */
    public int refinement(double metersPerColumn) {
        double ratio = metersPerColumn / metersPerCell;
        int log = (int) Math.round(Math.log(Math.max(1, ratio)) / Math.log(2));
        return 1 << Math.max(0, Math.min(4, log));
    }

    /** Region half-width in columns (at least one). */
    public int radiusColumns(double metersPerColumn) {
        return Math.max(1, (int) Math.ceil(radiusM / metersPerColumn));
    }
}
