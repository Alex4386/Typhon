package me.alex4386.typhon.engine.lava;

/**
 * A hollow section of a drained lava tube in one column: air from {@code bottomY} to {@code topY}
 * (inclusive) under a rock roof.
 *
 * <p>{@link me.alex4386.typhon.engine.terrain.TerrainModel} only stores the surface, which for a tube
 * column is the top of the roof; the void beneath is recorded here so hosts and other subsystems
 * (collapse, cave ecology, geothermal) know the cavity exists.
 */
public record LavaTube(int x, int z, int bottomY, int topY) {
    public LavaTube {
        if (topY < bottomY) throw new IllegalArgumentException("empty tube void at " + x + "," + z);
    }

    public int height() {
        return topY - bottomY + 1;
    }
}
