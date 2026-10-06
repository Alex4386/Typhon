package me.alex4386.typhon.simulator.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.alex4386.typhon.engine.terrain.TerrainChunk;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainSnapshot;
import me.alex4386.typhon.engine.world.BlockId;

/**
 * A rectangular, chunk-aligned grid of surface columns: what a host would sample from its world.
 *
 * <p>The grid covers {@code [minX, minX + size) × [minZ, minZ + size)}; {@code minX}, {@code minZ}
 * and {@code size} are multiples of 16 so it maps onto whole chunks.
 */
public final class ColumnGrid {
    private final int minX;
    private final int minZ;
    private final int size;
    private final int[] ground;
    private final int[] water;
    private final BlockId[] surface;
    private Relief relief;

    /**
     * The continuous surface a generator sampled the grid from: ground-block top in blocks at
     * fractional column coordinates (column {@code x} spans {@code [x, x+1)}), defined beyond the grid
     * too. Multiply by the block size for metres.
     */
    @FunctionalInterface
    public interface Relief {
        double topBlocks(double cx, double cz);
    }

    public ColumnGrid(int minX, int minZ, int size) {
        if (size <= 0 || size % 16 != 0) throw new IllegalArgumentException("size must be a positive multiple of 16");
        if (minX % 16 != 0 || minZ % 16 != 0) throw new IllegalArgumentException("origin must be chunk-aligned");
        this.minX = minX;
        this.minZ = minZ;
        this.size = size;
        this.ground = new int[size * size];
        this.water = new int[size * size];
        this.surface = new BlockId[size * size];
        Arrays.fill(water, TerrainColumn.NO_WATER);
        Arrays.fill(surface, BlockId.minecraft("stone"));
    }

    /** Square grid of {@code 2 * halfExtent} blocks centred on the origin (rounded up to whole chunks). */
    public static ColumnGrid centered(int halfExtent) {
        int half = Math.max(16, ((halfExtent + 15) / 16) * 16);
        return new ColumnGrid(-half, -half, 2 * half);
    }

    /** Attaches the generator's continuous surface (see {@link Relief}); returns this grid. */
    public ColumnGrid withRelief(Relief relief) {
        this.relief = relief;
        return this;
    }

    /** The generator's continuous surface, or {@code null} (DEMs, edited grids). */
    public Relief relief() {
        return relief;
    }

    public int minX() { return minX; }
    public int minZ() { return minZ; }
    public int maxX() { return minX + size - 1; }
    public int maxZ() { return minZ + size - 1; }
    public int size() { return size; }

    public boolean contains(int x, int z) {
        return x >= minX && x < minX + size && z >= minZ && z < minZ + size;
    }

    private int index(int x, int z) {
        if (!contains(x, z)) throw new IndexOutOfBoundsException("Column " + x + "," + z + " is outside the grid");
        return (z - minZ) * size + (x - minX);
    }

    public int ground(int x, int z) { return ground[index(x, z)]; }
    public int water(int x, int z) { return water[index(x, z)]; }
    public BlockId surface(int x, int z) { return surface[index(x, z)]; }

    public TerrainColumn column(int x, int z) {
        int i = index(x, z);
        return new TerrainColumn(ground[i], water[i], surface[i]);
    }

    public void set(int x, int z, int groundY, int waterY, BlockId surfaceId) {
        int i = index(x, z);
        ground[i] = groundY;
        water[i] = waterY;
        surface[i] = surfaceId;
    }

    /** Floods every column whose ground lies below {@code seaLevel} up to it. */
    public void flood(int seaLevel) {
        for (int i = 0; i < ground.length; i++) {
            if (ground[i] < seaLevel) water[i] = seaLevel;
        }
    }

    public int minGround() {
        return Arrays.stream(ground).min().orElse(0);
    }

    public int maxGround() {
        return Arrays.stream(ground).max().orElse(0);
    }

    public ColumnGrid copy() {
        ColumnGrid c = new ColumnGrid(minX, minZ, size);
        System.arraycopy(ground, 0, c.ground, 0, ground.length);
        System.arraycopy(water, 0, c.water, 0, water.length);
        System.arraycopy(surface, 0, c.surface, 0, surface.length);
        c.relief = relief;
        return c;
    }

    /** The whole grid as the snapshot command a host would send at start-up. */
    public TerrainSnapshot toSnapshot() {
        List<TerrainChunk> chunks = new ArrayList<>();
        for (int cz = minZ >> 4; cz <= maxZ() >> 4; cz++) {
            for (int cx = minX >> 4; cx <= maxX() >> 4; cx++) {
                TerrainChunk chunk = new TerrainChunk(cx, cz);
                for (int z = cz << 4; z < (cz << 4) + 16; z++) {
                    for (int x = cx << 4; x < (cx << 4) + 16; x++) {
                        chunk.set(x, z, column(x, z));
                    }
                }
                chunks.add(chunk);
            }
        }
        return new TerrainSnapshot(chunks);
    }
}
