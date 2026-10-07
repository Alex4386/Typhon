package me.alex4386.typhon.engine.lava;

/**
 * A hollow section of a drained lava tube in one column: a void from {@code bottomZ} to {@code topZ}
 * (elevations, m) under a rock roof. The world model holds the cavity as a layer; this record lists it for
 * hosts and other subsystems (collapse, cave ecology, geothermal).
 *
 * @param x column index east
 * @param z column index south
 */
public record LavaTube(int x, int z, double bottomZ, double topZ) {
    public LavaTube {
        if (!(topZ > bottomZ)) throw new IllegalArgumentException("empty tube void at " + x + "," + z);
    }

    /** Height of the void (m). */
    public double height() {
        return topZ - bottomZ;
    }
}
