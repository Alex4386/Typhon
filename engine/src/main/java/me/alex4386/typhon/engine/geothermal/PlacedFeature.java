package me.alex4386.typhon.engine.geothermal;

/**
 * A hydrothermal feature at surface column {@code (x, z)}.
 *
 * @param elevation elevation of the feature's opening or surface (m)
 * @param level feature-specific stage: sulfur build-up stages for sulfur deposits, vent depth (m) for
 *     geysers, 1 once a fumarole's opening is sulfur-coated, otherwise 0
 */
public record PlacedFeature(int x, int z, double elevation, HydrothermalFeature kind, int level) {
    PlacedFeature withLevel(int newLevel) {
        return new PlacedFeature(x, z, elevation, kind, newLevel);
    }

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}
