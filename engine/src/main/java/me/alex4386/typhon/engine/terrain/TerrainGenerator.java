package me.alex4386.typhon.engine.terrain;

/**
 * The host's terrain generator: the column at any (x, z), as a pure function of the coordinates (and
 * whatever the generator was built from, e.g. the world seed or a DEM). Calling it again for the same
 * column, at any time and on any thread, must give the same column, so that terrain the engine
 * materialises on demand is reproducible from the world definition alone (Minecraft-style chunk
 * generation). See {@code WorldExpansion}.
 */
@FunctionalInterface
public interface TerrainGenerator {
    TerrainColumn column(int x, int z);
}
