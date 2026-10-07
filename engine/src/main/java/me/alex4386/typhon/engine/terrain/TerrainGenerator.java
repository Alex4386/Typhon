package me.alex4386.typhon.engine.terrain;

/**
 * The host's terrain generator: the ground column at any column index, as a pure function of the indices
 * (and whatever the generator was built from, e.g. the world seed or a DEM). Calling it again for the same
 * column, at any time and on any thread, must give the same column, so that ground the engine materialises
 * on demand is reproducible from the world definition alone. See {@code WorldExpansion}.
 */
@FunctionalInterface
public interface TerrainGenerator {
    GroundColumn column(int x, int z);
}
