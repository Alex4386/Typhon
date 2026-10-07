package me.alex4386.typhon.server;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;

/**
 * Maps between the engine's column/block grid and the protocol's real-scale frame.
 *
 * <p>The engine indexes columns by integer {@code (x, z)} with {@code +z} pointing south (Minecraft
 * convention) and block {@code y} spanning elevations {@code [y·L, (y+1)·L)}. The protocol uses metres
 * with {@code x} east, {@code y} north and {@code z} up. Column {@code (cx, cz)} therefore covers
 * {@code x ∈ [cx·L, (cx+1)·L)} and {@code y ∈ [−(cz+1)·L, −cz·L)}.
 *
 * <p>Protocol tiles are {@code T × T} columns; tile {@code (0, 0)} sits at the south-west corner of the
 * initial core (the <em>anchor</em>, fixed for the session, so tile coordinates never change when the
 * simulated area grows). Row {@code r} of protocol tile {@code ty} is world column
 * {@code cz = anchorMaxZ − (ty·T + r)}; the mapping covers protocol tiles
 * {@code [minTx, minTx + tilesX) × [minTy, minTy + tilesY)}, which may be negative after growth.
 * Methods taking a tile index without "protocol" in their name use the local index
 * {@code tx − minTx} (0 at {@link #minX}).
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
    /** Column x of the west edge of protocol tile column 0 (fixed for a session). */
    public final int anchorX;
    /** Column z of the south edge (largest z) of protocol tile row 0 (fixed for a session). */
    public final int anchorMaxZ;
    /** Protocol coordinates of the mapping's first tile column and row. */
    public final int minTx;
    public final int minTy;

    /** A mapping anchored at its own south-west corner (protocol tiles start at 0). */
    public GridMapping(double cell, int tileSize, int minX, int minZ, int maxX, int maxZ) {
        this.cell = cell;
        this.tileSize = tileSize;
        this.minX = minX;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxZ = maxZ;
        this.tilesX = (maxX - minX + tileSize) / tileSize;
        this.tilesY = (maxZ - minZ + tileSize) / tileSize;
        this.anchorX = minX;
        this.anchorMaxZ = maxZ;
        this.minTx = 0;
        this.minTy = 0;
    }

    /**
     * A mapping with the same anchor as {@code anchor} covering protocol tiles
     * {@code [minTx, maxTx] × [minTy, maxTy]} (the simulated area after growth).
     */
    public GridMapping(GridMapping anchor, int minTx, int minTy, int maxTx, int maxTy) {
        this.cell = anchor.cell;
        this.tileSize = anchor.tileSize;
        this.anchorX = anchor.anchorX;
        this.anchorMaxZ = anchor.anchorMaxZ;
        this.minTx = minTx;
        this.minTy = minTy;
        this.tilesX = maxTx - minTx + 1;
        this.tilesY = maxTy - minTy + 1;
        this.minX = anchorX + minTx * tileSize;
        this.maxX = anchorX + (maxTx + 1) * tileSize - 1;
        this.maxZ = anchorMaxZ - minTy * tileSize;
        this.minZ = anchorMaxZ - (maxTy + 1) * tileSize + 1;
    }

    /** The smallest anchored mapping covering columns {@code [minX, maxX] × [minZ, maxZ]} (and at least this one). */
    public GridMapping covering(int minX, int minZ, int maxX, int maxZ) {
        int t = tileSize;
        int tx0 = Math.min(this.minTx, Math.floorDiv(minX - anchorX, t));
        int tx1 = Math.max(this.minTx + tilesX - 1, Math.floorDiv(maxX - anchorX, t));
        int ty0 = Math.min(this.minTy, Math.floorDiv(anchorMaxZ - maxZ, t));
        int ty1 = Math.max(this.minTy + tilesY - 1, Math.floorDiv(anchorMaxZ - minZ, t));
        return new GridMapping(this, tx0, ty0, tx1, ty1);
    }

    /** World x (m) of the origin (south-west corner of protocol tile 0, 0). */
    public double originX() {
        return anchorX * cell;
    }

    /** World y (m) of the origin. */
    public double originY() {
        return -(anchorMaxZ + 1) * cell;
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
        double z = (p.y() + 0.5) * cell;
        return new double[] {x(p.x()), y(p.z()), stretchZ(p.x() + 0.5, p.z() + 0.5, z)};
    }

    /** World coordinates of an engine point in metres (x east, y up, z south). */
    public double[] point(Point3 p) {
        return new double[] {p.x(), -p.z(), stretchZ(p.x() / cell, p.z() / cell, p.y())};
    }

    /** World coordinates of an engine-space point given in fractional block units. */
    public double[] point(double bx, double by, double bz) {
        return new double[] {bx * cell, -bz * cell, stretchZ(bx, bz, by * cell)};
    }

    // ── Subsurface depth stretch ──
    // The engine compresses the magma system's depth into the block world (a chamber 4 km deep sits a
    // few hundred metres under the vent) while lengths at the surface are real. Points below the
    // ground are drawn at their physical depth: depth below the local ground × depthScale.

    private float[] ground;
    private int groundStep = 1;
    private int groundW;
    private int groundH;
    private double depthScale = 1;

    /**
     * Enables the depth stretch: {@code ground} holds surface elevations (m) sampled every
     * {@code step} columns from (minX, minZ), row-major over z; {@code scale} ≥ 1 multiplies depths
     * below it.
     */
    public void setDepthStretch(float[] ground, int step, int width, int height, double scale) {
        this.ground = ground;
        this.groundStep = step;
        this.groundW = width;
        this.groundH = height;
        this.depthScale = Math.max(1, scale);
    }

    public double depthScale() {
        return depthScale;
    }

    /** Surface elevation (m) near column ({@code cx}, {@code cz}), or NaN without a stretch. */
    public double groundAt(double cx, double cz) {
        if (ground == null) return Double.NaN;
        int i = Math.max(0, Math.min(groundW - 1, (int) Math.floor((cx - minX) / groundStep)));
        int j = Math.max(0, Math.min(groundH - 1, (int) Math.floor((cz - minZ) / groundStep)));
        return ground[j * groundW + i];
    }

    /** Elevation {@code z} (m) at column ({@code cx}, {@code cz}) with depth below ground stretched. */
    public double stretchZ(double cx, double cz, double z) {
        if (depthScale == 1 || ground == null) return z;
        double g = groundAt(cx, cz);
        if (!Double.isFinite(g) || z >= g) return z;
        // Keep the first couple of blocks unstretched so surface features stay on the ground.
        double d = g - z;
        double near = 2 * cell;
        return d <= near ? z : g - (near + (d - near) * depthScale);
    }
}
