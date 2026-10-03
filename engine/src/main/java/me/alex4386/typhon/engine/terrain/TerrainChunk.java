package me.alex4386.typhon.engine.terrain;

import java.util.Arrays;
import me.alex4386.typhon.engine.world.BlockId;

/** Column data for one 16×16 chunk, in flat arrays indexed by {@code (z & 15) * 16 + (x & 15)}. */
public final class TerrainChunk implements TerrainChunkView {
    public static final int SIZE = 16;
    static final int AREA = SIZE * SIZE;

    private final int chunkX;
    private final int chunkZ;
    final int[] groundY = new int[AREA];
    final int[] waterY = new int[AREA];
    final BlockId[] surface = new BlockId[AREA];
    private int version;

    public TerrainChunk(int chunkX, int chunkZ) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        Arrays.fill(waterY, TerrainColumn.NO_WATER);
        Arrays.fill(surface, BlockId.AIR);
    }

    @Override
    public int chunkX() {
        return chunkX;
    }

    @Override
    public int chunkZ() {
        return chunkZ;
    }

    static int index(int x, int z) {
        return ((z & 15) << 4) | (x & 15);
    }

    /** Sets a column given in world coordinates (must lie inside this chunk). */
    public void set(int x, int z, TerrainColumn column) {
        if ((x >> 4) != chunkX || (z >> 4) != chunkZ) {
            throw new IllegalArgumentException("Column " + x + "," + z + " is outside chunk " + chunkX + "," + chunkZ);
        }
        int i = index(x, z);
        version++;
        groundY[i] = column.groundY();
        waterY[i] = column.waterY();
        surface[i] = column.surface();
    }

    public TerrainColumn get(int x, int z) {
        int i = index(x, z);
        return new TerrainColumn(groundY[i], waterY[i], surface[i]);
    }

    @Override
    public int version() {
        return version;
    }

    @Override
    public int groundY(int index) {
        return groundY[index];
    }

    @Override
    public int waterY(int index) {
        return waterY[index];
    }

    @Override
    public BlockId surface(int index) {
        return surface[index];
    }

    @Override
    public void copyGroundY(int[] dst, int offset) {
        System.arraycopy(groundY, 0, dst, offset, AREA);
    }

    @Override
    public void copyWaterY(int[] dst, int offset) {
        System.arraycopy(waterY, 0, dst, offset, AREA);
    }

    public TerrainChunk copy() {
        TerrainChunk copy = new TerrainChunk(chunkX, chunkZ);
        System.arraycopy(groundY, 0, copy.groundY, 0, AREA);
        System.arraycopy(waterY, 0, copy.waterY, 0, AREA);
        System.arraycopy(surface, 0, copy.surface, 0, AREA);
        return copy;
    }
}
