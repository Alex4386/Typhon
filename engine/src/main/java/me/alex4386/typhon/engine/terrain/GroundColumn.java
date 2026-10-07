package me.alex4386.typhon.engine.terrain;

import me.alex4386.typhon.engine.world.Material;

/**
 * One column of ground handed to the engine by its host: the column's grid index, its surface and water
 * elevations in metres, and the material at the surface.
 *
 * @param x column index east (column {@code x} spans {@code [x·L, (x+1)·L)} metres)
 * @param z column index south
 * @param surfaceZ ground surface elevation (m)
 * @param waterZ standing water surface elevation (m), {@code NaN} where dry
 * @param cover material at the surface, or {@code null} for the world's default surface material
 */
public record GroundColumn(int x, int z, double surfaceZ, double waterZ, Material cover) {
    public GroundColumn {
        if (!Double.isFinite(surfaceZ)) throw new IllegalArgumentException("surfaceZ must be finite");
    }

    public static GroundColumn dry(int x, int z, double surfaceZ, Material cover) {
        return new GroundColumn(x, z, surfaceZ, Double.NaN, cover);
    }

    public boolean submerged() {
        return Double.isFinite(waterZ) && waterZ > surfaceZ;
    }
}
