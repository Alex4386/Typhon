package me.alex4386.typhon.simulator.terrain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.alex4386.typhon.engine.terrain.GroundColumn;
import me.alex4386.typhon.engine.terrain.GroundImport;
import me.alex4386.typhon.engine.terrain.TerrainGenerator;
import me.alex4386.typhon.engine.world.Material;

/**
 * A rectangular grid of ground columns {@code metersPerColumn} wide: surface and water elevations (m)
 * and the surface material, what a host hands the engine at start-up.
 *
 * <p>The grid covers columns {@code [minX, minX + size) × [minZ, minZ + size)} (column {@code x} spans
 * {@code [x·L, (x+1)·L)} metres); {@code minX}, {@code minZ} and {@code size} are multiples of
 * {@link #TILE} so the grid maps onto whole tiles.
 */
public final class ColumnGrid {
    /** Tile width (columns) grids are aligned to. */
    public static final int TILE = 16;

    private final int minX;
    private final int minZ;
    private final int size;
    private final double metersPerColumn;
    private final double[] surface;
    private final double[] water;
    private final Material[] cover;
    private Relief relief;
    private Source source;

    /**
     * The continuous surface a generator sampled the grid from: elevation (m) at a horizontal position
     * (m; +x east, +z south), defined beyond the grid too.
     */
    @FunctionalInterface
    public interface Relief {
        double elevation(double xm, double zm);
    }

    /**
     * The generator itself: the column at any (x, z), a pure function of the indices (and the seed it was
     * built with). A grid built from a source is a window onto an unbounded landscape; the engine
     * materialises further columns from it on demand ({@link TerrainGenerator}).
     */
    @FunctionalInterface
    public interface Source extends TerrainGenerator {}

    /** A {@code size}-wide grid at ({@code minX}, {@code minZ}) filled from {@code source}. */
    public static ColumnGrid generate(int minX, int minZ, int size, double metersPerColumn, Source source,
            Relief relief) {
        ColumnGrid grid = new ColumnGrid(minX, minZ, size, metersPerColumn);
        grid.relief = relief;
        grid.fill(source);
        return grid;
    }

    /** Square grid of {@code 2 * halfExtent} columns centred on the origin, filled from {@code source}. */
    public static ColumnGrid generateCentered(int halfExtent, double metersPerColumn, Source source, Relief relief) {
        int half = roundHalf(halfExtent);
        return generate(-half, -half, 2 * half, metersPerColumn, source, relief);
    }

    /** {@code halfExtent} rounded up to whole tiles (at least one). */
    public static int roundHalf(int halfExtent) {
        return Math.max(TILE, ((halfExtent + TILE - 1) / TILE) * TILE);
    }

    /** Fills every column from {@code source} and keeps it as this grid's generator; returns this grid. */
    public ColumnGrid fill(Source source) {
        this.source = source;
        for (int z = minZ; z < minZ + size; z++) {
            for (int x = minX; x < minX + size; x++) {
                GroundColumn c = source.column(x, z);
                set(x, z, c.surfaceZ(), c.waterZ(), c.cover());
            }
        }
        return this;
    }

    /** The generator this grid was filled from, or {@code null} (edited grids). */
    public Source source() {
        return source;
    }

    /**
     * The same landscape over a different centred window of {@code 2 * halfExtent} columns (rounded up
     * to whole tiles); requires a {@link #source()}.
     */
    public ColumnGrid window(int halfExtent) {
        if (source == null) throw new IllegalStateException("this grid has no generator to re-window");
        return generateCentered(halfExtent, metersPerColumn, source, relief);
    }

    public ColumnGrid(int minX, int minZ, int size, double metersPerColumn) {
        if (size <= 0 || size % TILE != 0) throw new IllegalArgumentException("size must be a positive multiple of " + TILE);
        if (minX % TILE != 0 || minZ % TILE != 0) throw new IllegalArgumentException("origin must be tile-aligned");
        if (!(metersPerColumn > 0)) throw new IllegalArgumentException("metersPerColumn must be > 0");
        this.minX = minX;
        this.minZ = minZ;
        this.size = size;
        this.metersPerColumn = metersPerColumn;
        this.surface = new double[size * size];
        this.water = new double[size * size];
        this.cover = new Material[size * size];
        Arrays.fill(water, Double.NaN);
    }

    /** Square grid of {@code 2 * halfExtent} columns centred on the origin (rounded up to whole tiles). */
    public static ColumnGrid centered(int halfExtent, double metersPerColumn) {
        int half = roundHalf(halfExtent);
        return new ColumnGrid(-half, -half, 2 * half, metersPerColumn);
    }

    /** Attaches the generator's continuous surface (see {@link Relief}); returns this grid. */
    public ColumnGrid withRelief(Relief relief) {
        this.relief = relief;
        return this;
    }

    /** The generator's continuous surface, or {@code null} (edited grids). */
    public Relief relief() {
        return relief;
    }

    public int minX() { return minX; }
    public int minZ() { return minZ; }
    public int maxX() { return minX + size - 1; }
    public int maxZ() { return minZ + size - 1; }
    public int size() { return size; }
    public double metersPerColumn() { return metersPerColumn; }

    public boolean contains(int x, int z) {
        return x >= minX && x < minX + size && z >= minZ && z < minZ + size;
    }

    private int index(int x, int z) {
        if (!contains(x, z)) throw new IndexOutOfBoundsException("Column " + x + "," + z + " is outside the grid");
        return (z - minZ) * size + (x - minX);
    }

    /** Ground surface elevation (m). */
    public double surfaceZ(int x, int z) { return surface[index(x, z)]; }
    /** Standing water surface elevation (m), {@code NaN} where dry. */
    public double waterZ(int x, int z) { return water[index(x, z)]; }
    /** Surface material, or {@code null} for the world's default. */
    public Material cover(int x, int z) { return cover[index(x, z)]; }

    /** The column at (x, z): from the grid inside it, from the generator outside ({@code null} without one). */
    public GroundColumn columnAnywhere(int x, int z) {
        if (contains(x, z)) return column(x, z);
        return source == null ? null : source.column(x, z);
    }

    public GroundColumn column(int x, int z) {
        int i = index(x, z);
        return new GroundColumn(x, z, surface[i], water[i], cover[i]);
    }

    public void set(int x, int z, double surfaceZ, double waterZ, Material material) {
        int i = index(x, z);
        surface[i] = surfaceZ;
        water[i] = waterZ;
        cover[i] = material;
    }

    /** Floods every column whose ground lies below {@code seaLevelZ} (m) up to it. */
    public void flood(double seaLevelZ) {
        for (int i = 0; i < surface.length; i++) {
            if (surface[i] < seaLevelZ) water[i] = seaLevelZ;
        }
    }

    /** Lowest ground surface (m). */
    public double minSurfaceZ() {
        return Arrays.stream(surface).min().orElse(0);
    }

    /** Highest ground surface (m). */
    public double maxSurfaceZ() {
        return Arrays.stream(surface).max().orElse(0);
    }

    public ColumnGrid copy() {
        ColumnGrid c = new ColumnGrid(minX, minZ, size, metersPerColumn);
        System.arraycopy(surface, 0, c.surface, 0, surface.length);
        System.arraycopy(water, 0, c.water, 0, water.length);
        System.arraycopy(cover, 0, c.cover, 0, cover.length);
        c.relief = relief;
        c.source = source;
        return c;
    }

    /** The whole grid as the ground a host sends at start-up. */
    public GroundImport toImport() {
        List<GroundColumn> columns = new ArrayList<>(size * size);
        for (int z = minZ; z < minZ + size; z++) {
            for (int x = minX; x < minX + size; x++) columns.add(column(x, z));
        }
        return new GroundImport(columns);
    }
}
