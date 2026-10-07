package me.alex4386.typhon.mc;

/**
 * Immutable integer block position.
 *
 * <p>Positions can be packed into a single {@code long} (26 bits X, 26 bits Z, 12 bits Y) for use as
 * primitive map keys in hot simulation code. The packed layout covers X/Z in ±33,554,431 and Y in
 * [-2048, 2047], which exceeds the Minecraft world bounds.
 */
public record BlockPos(int x, int y, int z) {
    private static final int XZ_BITS = 26;
    private static final int Y_BITS = 12;

    private static final long XZ_MASK = (1L << XZ_BITS) - 1;
    private static final long Y_MASK = (1L << Y_BITS) - 1;

    private static final int Z_SHIFT = Y_BITS;
    private static final int X_SHIFT = Y_BITS + XZ_BITS;

    public static final int MIN_Y = -(1 << (Y_BITS - 1));
    public static final int MAX_Y = (1 << (Y_BITS - 1)) - 1;
    public static final int MIN_XZ = -(1 << (XZ_BITS - 1));
    public static final int MAX_XZ = (1 << (XZ_BITS - 1)) - 1;

    public static long pack(int x, int y, int z) {
        if (x < MIN_XZ || x > MAX_XZ || z < MIN_XZ || z > MAX_XZ || y < MIN_Y || y > MAX_Y) {
            throw new IllegalArgumentException("Position out of packable range: " + x + ", " + y + ", " + z);
        }
        return ((x & XZ_MASK) << X_SHIFT) | ((z & XZ_MASK) << Z_SHIFT) | (y & Y_MASK);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> X_SHIFT);
    }

    public static int unpackY(long packed) {
        return (int) (packed << (64 - Y_BITS) >> (64 - Y_BITS));
    }

    public static int unpackZ(long packed) {
        return (int) (packed << (64 - X_SHIFT) >> (64 - XZ_BITS));
    }

    public static BlockPos unpack(long packed) {
        return new BlockPos(unpackX(packed), unpackY(packed), unpackZ(packed));
    }

    public long pack() {
        return pack(x, y, z);
    }

    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }

    public int chunkX() {
        return x >> 4;
    }

    public int chunkZ() {
        return z >> 4;
    }

    public double horizontalDistance(BlockPos other) {
        long dx = (long) x - other.x;
        long dz = (long) z - other.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
