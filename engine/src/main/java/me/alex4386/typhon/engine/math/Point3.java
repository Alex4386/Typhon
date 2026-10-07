package me.alex4386.typhon.engine.math;

/**
 * A point in the engine's world frame, in metres: {@code x} east, {@code y} up (elevation), {@code z}
 * south (Minecraft's axes, real units). Column {@code (cx, cz)} of an {@code L}-metre grid covers
 * {@code x ∈ [cx·L, (cx+1)·L)}, {@code z ∈ [cz·L, (cz+1)·L)}; see {@link ColumnIndex}.
 *
 * <p>Positions of things that are not on the grid (vents, chambers, dike tips, hypocentres, stations,
 * features, landed bombs) are points; grid cells are {@link ColumnIndex}es. The two are never mixed.
 */
public record Point3(double x, double y, double z) {
    public static final Point3 ORIGIN = new Point3(0, 0, 0);

    /** The centre of column {@code (cx, cz)} of an {@code l}-metre grid, at elevation {@code y} (m). */
    public static Point3 columnCentre(int cx, int cz, double y, double l) {
        return new Point3((cx + 0.5) * l, y, (cz + 0.5) * l);
    }

    /** The centre of column {@code c}, at elevation {@code y} (m). */
    public static Point3 columnCentre(ColumnIndex c, double y, double l) {
        return columnCentre(c.x(), c.z(), y, l);
    }

    /**
     * The point at fractional block coordinates {@code (bx, by, bz)} of an {@code l}-metre grid, where an
     * integer coordinate is the centre of that block: block {@code (x, y, z)} is the cube
     * {@code [x·l, (x+1)·l) × [y·l, (y+1)·l) × [z·l, (z+1)·l)}.
     */
    public static Point3 ofBlocks(double bx, double by, double bz, double l) {
        return new Point3((bx + 0.5) * l, (by + 0.5) * l, (bz + 0.5) * l);
    }

    /** The centre of block {@code b} of an {@code l}-metre grid. */
    public static Point3 ofBlock(BlockPos b, double l) {
        return ofBlocks(b.x(), b.y(), b.z(), l);
    }

    /** Fractional block x (inverse of {@link #ofBlocks}). */
    public double blockX(double l) {
        return x / l - 0.5;
    }

    /** Fractional block y (inverse of {@link #ofBlocks}). */
    public double blockY(double l) {
        return y / l - 0.5;
    }

    /** Fractional block z (inverse of {@link #ofBlocks}). */
    public double blockZ(double l) {
        return z / l - 0.5;
    }

    /** The block of an {@code l}-metre grid this point lies in. */
    public BlockPos block(double l) {
        return new BlockPos((int) Math.floor(x / l), (int) Math.floor(y / l), (int) Math.floor(z / l));
    }

    /** The column of an {@code l}-metre grid this point lies in. */
    public ColumnIndex column(double l) {
        return new ColumnIndex((int) Math.floor(x / l), (int) Math.floor(z / l));
    }

    /** Column x of an {@code l}-metre grid. */
    public int columnX(double l) {
        return (int) Math.floor(x / l);
    }

    /** Column z of an {@code l}-metre grid. */
    public int columnZ(double l) {
        return (int) Math.floor(z / l);
    }

    public Point3 withY(double newY) {
        return new Point3(x, newY, z);
    }

    public Point3 offset(double dx, double dy, double dz) {
        return new Point3(x + dx, y + dy, z + dz);
    }

    public double horizontalDistance(Point3 other) {
        return Math.hypot(x - other.x, z - other.z);
    }

    public double distance(Point3 other) {
        double dx = x - other.x;
        double dy = y - other.y;
        double dz = z - other.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
