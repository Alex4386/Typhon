package me.alex4386.typhon.engine.geothermal;

/**
 * A hydrothermal feature occupying block column {@code (x, z)}.
 *
 * @param y height of the feature's anchor block (surface block; potent sulfur for geysers)
 * @param level feature-specific stage: spike height for sulfur deposits, water depth for geysers,
 *     1 once a fumarole's opening is sulfur-coated, otherwise 0
 */
public record PlacedFeature(int x, int y, int z, HydrothermalFeature kind, int level) {
    PlacedFeature withLevel(int newLevel) {
        return new PlacedFeature(x, y, z, kind, newLevel);
    }

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}
