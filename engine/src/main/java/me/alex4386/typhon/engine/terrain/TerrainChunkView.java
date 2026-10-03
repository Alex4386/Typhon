package me.alex4386.typhon.engine.terrain;

import me.alex4386.typhon.engine.world.BlockId;

/**
 * Allocation-free, read-only access to one chunk of {@link TerrainModel} columns, for hot simulation
 * loops. Indices are {@code (z & 15) * 16 + (x & 15)}.
 *
 * <p>{@link #version()} changes whenever a column of this chunk changes, so callers can cache
 * derived data and re-read only when the version (or the view instance, after a fresh snapshot
 * replaced the chunk) differs.
 */
public interface TerrainChunkView {
    int chunkX();

    int chunkZ();

    /** Modification counter of this chunk instance. */
    int version();

    int groundY(int index);

    /** Water surface y, or {@link TerrainColumn#NO_WATER}. */
    int waterY(int index);

    BlockId surface(int index);

    /** Copies all 256 ground heights into {@code dst[offset..offset+256)}. */
    void copyGroundY(int[] dst, int offset);

    /** Copies all 256 water surfaces into {@code dst[offset..offset+256)}. */
    void copyWaterY(int[] dst, int offset);

    static int index(int x, int z) {
        return ((z & 15) << 4) | (x & 15);
    }
}
