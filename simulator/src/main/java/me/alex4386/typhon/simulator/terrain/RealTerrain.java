package me.alex4386.typhon.simulator.terrain;

import me.alex4386.typhon.engine.terrain.GroundColumn;
import me.alex4386.typhon.engine.world.Material;

/**
 * Real-scale synthetic landscapes: elevations in metres above sea level on columns
 * {@code metersPerColumn} wide.
 *
 * <p>Shapes are simple profiles fitted to published edifice dimensions (see the presets that use
 * them); seeded fractal noise in metres adds gullies and lobes so flows do not run in perfect lines.
 */
public final class RealTerrain {
    /** Elevation (m) at a horizontal position (m) relative to the domain centre; +x east, +z south. */
    @FunctionalInterface
    public interface Elevation {
        double at(double xm, double zm);
    }

    /** Surface material of a column ({@code null} = the world's default surface). */
    @FunctionalInterface
    public interface Paint {
        Material at(double xm, double zm, double elevation, boolean submerged);
    }

    private RealTerrain() {}

    /**
     * Samples {@code elevation} at column centres over a domain {@code 2·halfExtentColumns} columns
     * wide (rounded up to whole tiles), adds {@code roughnessM} of fractal noise with feature size
     * {@code roughnessScaleM} and floods columns below {@code seaLevelZ} (unless {@code NaN}).
     */
    public static ColumnGrid build(double metersPerColumn, int halfExtentColumns, long seed, Elevation elevation,
            double roughnessM, double roughnessScaleM, double seaLevelZ, Paint paint) {
        ValueNoise noise = new ValueNoise(seed);
        ColumnGrid.Relief relief = (xm, zm) -> {
            double e = elevation.at(xm, zm);
            if (roughnessM > 0) e += roughnessM * noise.fbm(xm, zm, roughnessScaleM, 4);
            return e;
        };
        ColumnGrid.Source source = (x, z) -> {
            double xm = (x + 0.5) * metersPerColumn;
            double zm = (z + 0.5) * metersPerColumn;
            double e = relief.elevation(xm, zm);
            boolean submerged = !Double.isNaN(seaLevelZ) && e < seaLevelZ;
            return new GroundColumn(x, z, e, submerged ? seaLevelZ : Double.NaN, paint.at(xm, zm, e, submerged));
        };
        return ColumnGrid.generateCentered(halfExtentColumns, metersPerColumn, source, relief);
    }

    /**
     * Floods every column whose ground lies below {@code waterZ} (m) within a circle — a lake floored with
     * {@code floor}. On a generated grid the lake becomes part of the generator, so columns materialised
     * later get it too.
     */
    public static void lake(ColumnGrid grid, double centerXm, double centerZm, double radiusM, double waterZ,
            Material floor) {
        double l = grid.metersPerColumn();
        ColumnGrid.Source base = grid.source();
        if (base != null) {
            grid.fill((x, z) -> {
                GroundColumn c = base.column(x, z);
                double dx = (x + 0.5) * l - centerXm;
                double dz = (z + 0.5) * l - centerZm;
                if (dx * dx + dz * dz > radiusM * radiusM || c.surfaceZ() >= waterZ) return c;
                return new GroundColumn(x, z, c.surfaceZ(), waterZ, floor);
            });
            return;
        }
        for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
            for (int x = grid.minX(); x <= grid.maxX(); x++) {
                double dx = (x + 0.5) * l - centerXm;
                double dz = (z + 0.5) * l - centerZm;
                if (dx * dx + dz * dz > radiusM * radiusM) continue;
                if (grid.surfaceZ(x, z) < waterZ) grid.set(x, z, grid.surfaceZ(x, z), waterZ, floor);
            }
        }
    }

    // ── profiles ──

    /** Distance (m) from a point. */
    public static double dist(double xm, double zm, double cx, double cz) {
        double dx = xm - cx;
        double dz = zm - cz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Concave stratovolcano: {@code base + (summit − base)·(1 − d/R)^p} inside radius {@code R}.
     */
    public static double cone(double d, double summit, double base, double radius, double concavity) {
        if (d >= radius) return base;
        return base + (summit - base) * Math.pow(1 - d / radius, concavity);
    }

    /**
     * Bowl-shaped crater or pit: lowers {@code surface} by {@code depth} at the centre, smoothly to 0 at
     * {@code radius}, never below {@code floorZ}.
     */
    public static double crater(double surface, double d, double radius, double depth) {
        if (d >= radius) return surface;
        double u = d / radius;
        return surface - depth * (1 - u * u * u * u);
    }

    /**
     * Steep-walled collapse (caldera / pit crater): flat floor at {@code floorZ} inside
     * {@code radius − wall}, walls over {@code wall} metres up to the surrounding surface.
     */
    public static double pit(double surface, double d, double radius, double wall, double floorZ) {
        if (d >= radius || floorZ >= surface) return surface;
        if (d <= radius - wall) return floorZ;
        double t = (d - (radius - wall)) / wall;
        double s = t * t * (3 - 2 * t);
        return floorZ + (surface - floorZ) * s;
    }
}
