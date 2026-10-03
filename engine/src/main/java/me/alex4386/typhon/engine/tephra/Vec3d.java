package me.alex4386.typhon.engine.tephra;

import me.alex4386.typhon.engine.math.BlockPos;

/** Immutable 3D vector in metres (blocks) or metres per second. */
public record Vec3d(double x, double y, double z) {
    public static final Vec3d ZERO = new Vec3d(0, 0, 0);

    public Vec3d add(Vec3d o) {
        return new Vec3d(x + o.x, y + o.y, z + o.z);
    }

    public Vec3d subtract(Vec3d o) {
        return new Vec3d(x - o.x, y - o.y, z - o.z);
    }

    public Vec3d scale(double s) {
        return new Vec3d(x * s, y * s, z * s);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double horizontalLength() {
        return Math.sqrt(x * x + z * z);
    }

    /** Block containing this point. */
    public BlockPos toBlockPos() {
        return new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }
}
