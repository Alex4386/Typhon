package me.alex4386.typhon.simulator.terrain;

import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.world.BlockId;

/**
 * Deterministic synthetic landscapes, all centred on x = z = 0.
 *
 * <p>Heights are in blocks around a {@link #BASE_Y} plain; low-amplitude fractal noise (seeded) keeps
 * flows from running in perfectly radial lines.
 */
public final class TerrainGenerators {
    public static final int BASE_Y = 64;

    static final BlockId GRASS = BlockId.minecraft("grass_block");
    static final BlockId SAND = BlockId.minecraft("sand");
    static final BlockId GRAVEL = BlockId.minecraft("gravel");

    private TerrainGenerators() {}

    /**
     * Composite stratovolcano / cinder-cone geometry.
     *
     * @param height summit (rim) height above the base, blocks
     * @param radius basal radius, blocks
     * @param craterRadius radius of the summit crater rim, blocks
     * @param craterDepth depth of the crater floor below the rim, blocks
     * @param concavity profile exponent; > 1 gives the concave flanks of a stratovolcano
     * @param rock surface block of the volcanic edifice
     */
    public record Cone(int height, int radius, int craterRadius, int craterDepth, double concavity, BlockId rock) {
        public Cone {
            if (height <= 0 || radius <= 0) throw new IllegalArgumentException("height and radius must be positive");
            if (craterRadius < 0 || craterRadius >= radius) throw new IllegalArgumentException("bad crater radius");
            if (craterDepth < 0) throw new IllegalArgumentException("craterDepth must be >= 0");
        }

        /** Height above the base at horizontal distance {@code d} from the centre. */
        double profile(double d) {
            if (d >= radius) return 0;
            double rim = height;
            if (d <= craterRadius && craterRadius > 0) {
                double u = d / craterRadius;
                return rim - craterDepth * (1 - u * u);
            }
            double t = (d - craterRadius) / (radius - craterRadius);
            return rim * Math.pow(1 - t, concavity);
        }
    }

    /** Flat grassland with gentle undulation. */
    public static ColumnGrid plain(int halfExtent, long seed) {
        ColumnGrid grid = ColumnGrid.centered(halfExtent);
        ValueNoise noise = new ValueNoise(seed);
        for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
            for (int x = grid.minX(); x <= grid.maxX(); x++) {
                int y = BASE_Y + (int) Math.round(2 * noise.fbm(x, z, 48, 3));
                grid.set(x, z, y, TerrainColumn.NO_WATER, GRASS);
            }
        }
        return grid;
    }

    /** A cone (stratovolcano or cinder cone) standing on a plain. */
    public static ColumnGrid cone(int halfExtent, long seed, Cone cone) {
        ColumnGrid grid = ColumnGrid.centered(halfExtent);
        ValueNoise noise = new ValueNoise(seed);
        for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
            for (int x = grid.minX(); x <= grid.maxX(); x++) {
                double d = Math.sqrt((double) x * x + (double) z * z);
                double h = cone.profile(d);
                double rough = d > cone.craterRadius() ? 1.5 * noise.fbm(x, z, 24, 3) * Math.min(1, h / 8 + 0.3) : 0;
                int y = BASE_Y + (int) Math.round(h + rough + 1.5 * noise.fbm(x, z, 64, 2));
                grid.set(x, z, y, TerrainColumn.NO_WATER, h > 2 ? cone.rock() : GRASS);
            }
        }
        return grid;
    }

    /**
     * A broad, gently sloping shield volcano with a steep-walled summit pit crater (Kīlauea-like).
     *
     * @param height summit height above the base, blocks
     * @param radius basal radius, blocks
     * @param pitRadius summit pit crater radius, blocks
     * @param pitDepth pit crater depth, blocks
     */
    public static ColumnGrid shield(int halfExtent, long seed, int height, int radius, int pitRadius, int pitDepth) {
        ColumnGrid grid = ColumnGrid.centered(halfExtent);
        ValueNoise noise = new ValueNoise(seed);
        BlockId basalt = BlockId.minecraft("basalt");
        for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
            for (int x = grid.minX(); x <= grid.maxX(); x++) {
                double d = Math.sqrt((double) x * x + (double) z * z);
                double u = Math.min(1, d / radius);
                double h = height * (1 - Math.pow(u, 1.6)); // convex shield profile
                if (d < pitRadius) h -= pitDepth;
                double rough = 1.2 * noise.fbm(x, z, 32, 3);
                int y = BASE_Y + (int) Math.round(h + rough);
                grid.set(x, z, y, TerrainColumn.NO_WATER, h > 1 ? basalt : GRASS);
            }
        }
        return grid;
    }

    /**
     * A caldera: an outer cone truncated by a ring fault, with a flat floor partly filled by a lake
     * (Yellowstone/Crater Lake-like).
     *
     * @param rimHeight rim height above the base, blocks
     * @param rimRadius radius of the caldera rim, blocks
     * @param outerRadius basal radius of the outer slopes, blocks
     * @param floorDepth depth of the caldera floor below the rim, blocks
     * @param lakeDepth water depth over the lowest part of the floor, blocks (0 = dry); the floor
     *     tilts by ±3 blocks, so values above ~7 flood the whole caldera
     */
    public static ColumnGrid caldera(int halfExtent, long seed, int rimHeight, int rimRadius, int outerRadius,
            int floorDepth, int lakeDepth) {
        if (rimRadius >= outerRadius) throw new IllegalArgumentException("rimRadius must be < outerRadius");
        ColumnGrid grid = ColumnGrid.centered(halfExtent);
        ValueNoise noise = new ValueNoise(seed);
        BlockId tuff = BlockId.minecraft("tuff");
        int floorY = BASE_Y + rimHeight - floorDepth;
        // The floor tilts ±3 blocks across the caldera, so the lake fills only its low (western) side.
        int lakeY = floorY - 4 + lakeDepth;
        for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
            for (int x = grid.minX(); x <= grid.maxX(); x++) {
                double d = Math.sqrt((double) x * x + (double) z * z);
                double h;
                if (d >= outerRadius) {
                    h = 0;
                } else if (d >= rimRadius) {
                    double t = (d - rimRadius) / (outerRadius - rimRadius);
                    h = rimHeight * Math.pow(1 - t, 1.3);
                } else {
                    // ring-fault wall over the last 8 blocks, then a floor tilted slightly towards the lake side
                    double wall = Math.max(0, 1 - (rimRadius - d) / 8.0);
                    double floor = rimHeight - floorDepth + 3 * (x / (double) rimRadius) + 2 * noise.fbm(x, z, 20, 2);
                    h = floor + (rimHeight - floor) * wall * wall;
                }
                int y = BASE_Y + (int) Math.round(h + 1.2 * noise.fbm(x, z, 40, 3));
                boolean inLake = d < rimRadius && y < lakeY;
                BlockId surface = inLake ? SAND : (h > 2 ? tuff : GRASS);
                grid.set(x, z, y, inLake ? lakeY : TerrainColumn.NO_WATER, surface);
            }
        }
        return grid;
    }

    /**
     * A volcano rising from the sea floor (Surtsey-like). With {@code peakY} below {@code seaLevel} it is a
     * seamount; above, an island ringed by submerged flanks.
     *
     * @param seaLevel water surface y
     * @param seafloorY sea floor y away from the volcano
     * @param peakY summit (rim) y
     * @param radius basal radius on the sea floor, blocks
     * @param craterRadius summit crater radius, blocks
     */
    public static ColumnGrid island(int halfExtent, long seed, int seaLevel, int seafloorY, int peakY, int radius,
            int craterRadius) {
        if (peakY <= seafloorY) throw new IllegalArgumentException("peakY must be above the sea floor");
        ColumnGrid grid = ColumnGrid.centered(halfExtent);
        ValueNoise noise = new ValueNoise(seed);
        Cone cone = new Cone(peakY - seafloorY, radius, craterRadius, Math.min(4, (peakY - seafloorY) / 4), 1.4,
                BlockId.minecraft("basalt"));
        BlockId basalt = BlockId.minecraft("basalt");
        for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
            for (int x = grid.minX(); x <= grid.maxX(); x++) {
                double d = Math.sqrt((double) x * x + (double) z * z);
                double h = cone.profile(d);
                int y = seafloorY + (int) Math.round(h + 1.5 * noise.fbm(x, z, 32, 3));
                boolean submerged = y < seaLevel;
                BlockId surface = submerged ? (h > 2 ? basalt : GRAVEL) : (y <= seaLevel + 1 ? SAND : basalt);
                grid.set(x, z, y, submerged ? seaLevel : TerrainColumn.NO_WATER, surface);
            }
        }
        return grid;
    }
}
