package me.alex4386.typhon.server;

import me.alex4386.typhon.engine.math.BlockPos;

/**
 * Maps between the engine's column/block grid and the protocol's real-scale frame.
 *
 * <p>The engine indexes columns by integer {@code (x, z)} with {@code +z} pointing south (Minecraft
 * convention) and block {@code y} spanning elevations {@code [y·L, (y+1)·L)}. The protocol uses metres
 * with {@code x} east, {@code y} north and {@code z} up. Column {@code (cx, cz)} therefore covers
 * {@code x ∈ [cx·L, (cx+1)·L)} and {@code y ∈ [−(cz+1)·L, −cz·L)}.
 *
 * <p>Protocol tiles are {@code T × T} columns; tile {@code (0, 0)} sits at the world's south-west
 * corner. Row {@code r} of tile {@code ty} is world column {@code cz = maxZ − (ty·T + r)}.
 */
public final class GridMapping {
    public final double cell;
    public final int tileSize;
    public final int minX;
    public final int minZ;
    public final int maxX;
    public final int maxZ;
    public final int tilesX;
    public final int tilesY;

    public GridMapping(double cell, int tileSize, int minX, int minZ, int maxX, int maxZ) {
        this.cell = cell;
        this.tileSize = tileSize;
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
        this.tilesX = (maxX - minX + tileSize) / tileSize;
        this.tilesY = (maxZ - minZ + tileSize) / tileSize;
    }

    /** World x (m) of the origin (south-west corner of tile 0, 0). */
    public double originX() {
        return minX * cell;
    }

    /** World y (m) of the origin. */
    public double originY() {
        return -(maxZ + 1) * cell;
    }

    public int columnX(int tx, int c) {
        return minX + tx * tileSize + c;
    }

    public int columnZ(int ty, int r) {
        return maxZ - (ty * tileSize + r);
    }

    public boolean inside(int cx, int cz) {
        return cx >= minX && cx <= maxX && cz >= minZ && cz <= maxZ;
    }

    /** Column containing world point {@code (x, y)} (m). */
    public int columnAtX(double x) {
        return (int) Math.floor(x / cell);
    }

    public int columnAtY(double y) {
        return (int) Math.floor(-y / cell);
    }

    /** Fractional column coordinates of a world point (for engine polylines). */
    public double fracX(double x) {
        return x / cell;
    }

    public double fracZ(double y) {
        return -y / cell;
    }

    /** World x of the centre of column {@code cx}. */
    public double x(double cx) {
        return (cx + 0.5) * cell;
    }

    /** World y of the centre of column {@code cz}. */
    public double y(double cz) {
        return -(cz + 0.5) * cell;
    }

    /** World coordinates (m) of the centre of block {@code p}. */
    public double[] point(BlockPos p) {
        return new double[] {x(p.x()), y(p.z()), (p.y() + 0.5) * cell};
    }

    /** World coordinates of an engine-space point given in fractional block units. */
    public double[] point(double bx, double by, double bz) {
        return new double[] {bx * cell, -bz * cell, by * cell};
    }
}
