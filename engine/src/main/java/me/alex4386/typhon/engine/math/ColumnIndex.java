package me.alex4386.typhon.engine.math;

/**
 * A column of the surface grid: integer indices, not metres. Column {@code (x, z)} of an {@code L}-metre
 * grid covers {@code [x·L, (x+1)·L) × [z·L, (z+1)·L)}. Things that are not grid cells are {@link Point3}s.
 */
public record ColumnIndex(int x, int z) {
    /** The centre of this column at elevation {@code y} (m). */
    public Point3 centre(double y, double l) {
        return Point3.columnCentre(x, z, y, l);
    }

    public ColumnIndex offset(int dx, int dz) {
        return new ColumnIndex(x + dx, z + dz);
    }

    /** Packed into a long (x high, z low), e.g. for primitive map keys. */
    public long key() {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    public static ColumnIndex ofKey(long key) {
        return new ColumnIndex((int) (key >> 32), (int) key);
    }
}
