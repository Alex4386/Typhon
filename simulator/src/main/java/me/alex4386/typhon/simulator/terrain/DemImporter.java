package me.alex4386.typhon.simulator.terrain;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.world.BlockId;

/**
 * Imports real elevation data as a {@link ColumnGrid}, centred on the DEM's middle.
 *
 * <p>Elevations are metres above sea level. Sea level (0 m) maps to {@link #SEA_LEVEL_Y}; one block
 * represents {@code metersPerBlock} metres both horizontally and vertically (the same length scale as
 * {@code VolcanoScaling.metersPerBlock}), and columns below 0 m are flooded.
 */
public final class DemImporter {
    /** Minecraft's sea level. */
    public static final int SEA_LEVEL_Y = 62;
    static final int MIN_Y = -60;
    static final int MAX_Y = 318;

    private DemImporter() {}

    /** A grid of elevations in metres (row 0 = north) with a horizontal cell size in metres. */
    public record Dem(double[][] elevation, double cellSizeMeters) {
        public int rows() { return elevation.length; }
        public int cols() { return elevation[0].length; }
    }

    /**
     * Reads an ESRI ASCII grid ({@code ncols}, {@code nrows}, ..., {@code cellsize}, optional
     * {@code NODATA_value}) or a bare whitespace-separated matrix (cell size then defaults to
     * {@code defaultCellSize}). NODATA cells become 0 m.
     */
    public static Dem readAscii(Path file, double defaultCellSize) throws IOException {
        List<String> lines = Files.readAllLines(file);
        double cellSize = defaultCellSize;
        double nodata = Double.NaN;
        int start = 0;
        while (start < lines.size()) {
            String line = lines.get(start).trim();
            if (line.isEmpty()) { start++; continue; }
            String key = line.split("\\s+")[0].toLowerCase(Locale.ROOT);
            if (!Character.isLetter(key.charAt(0))) break;
            String value = line.split("\\s+")[1];
            switch (key) {
                case "cellsize" -> cellSize = Double.parseDouble(value);
                case "nodata_value" -> nodata = Double.parseDouble(value);
                default -> { }
            }
            start++;
        }
        List<double[]> rows = new ArrayList<>();
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            double[] row = new double[parts.length];
            for (int j = 0; j < parts.length; j++) {
                double v = Double.parseDouble(parts[j]);
                row[j] = (v == nodata) ? 0 : v;
            }
            rows.add(row);
        }
        if (rows.isEmpty()) throw new IOException("DEM has no data rows: " + file);
        int cols = rows.get(0).length;
        for (double[] r : rows) {
            if (r.length != cols) throw new IOException("DEM rows have different lengths: " + file);
        }
        return new Dem(rows.toArray(new double[0][]), cellSize);
    }

    /** Reads a grayscale (8- or 16-bit) PNG heightmap; black = {@code minMeters}, white = {@code maxMeters}. */
    public static Dem readPng(Path file, double minMeters, double maxMeters, double cellSize) throws IOException {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) throw new IOException("Not a readable image: " + file);
        Raster raster = image.getRaster();
        int bits = image.getColorModel().getComponentSize(0);
        double max = (1 << bits) - 1;
        double[][] elevation = new double[image.getHeight()][image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                double v = raster.getSample(x, y, 0) / max;
                elevation[y][x] = minMeters + v * (maxMeters - minMeters);
            }
        }
        return new Dem(elevation, cellSize);
    }

    /**
     * Resamples a DEM onto blocks of {@code metersPerBlock}, bilinearly, keeping at most
     * {@code maxHalfExtent} blocks either side of the centre.
     */
    public static ColumnGrid toGrid(Dem dem, double metersPerBlock, int maxHalfExtent) {
        double widthBlocks = dem.cols() * dem.cellSizeMeters() / metersPerBlock;
        double heightBlocks = dem.rows() * dem.cellSizeMeters() / metersPerBlock;
        int half = (int) Math.min(maxHalfExtent, Math.max(widthBlocks, heightBlocks) / 2);
        ColumnGrid grid = ColumnGrid.centered(half);
        BlockId grass = BlockId.minecraft("grass_block");
        BlockId stone = BlockId.minecraft("stone");
        BlockId sand = BlockId.minecraft("sand");
        for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
            for (int x = grid.minX(); x <= grid.maxX(); x++) {
                double col = (x * metersPerBlock) / dem.cellSizeMeters() + dem.cols() / 2.0;
                double row = (z * metersPerBlock) / dem.cellSizeMeters() + dem.rows() / 2.0;
                double meters = bilinear(dem, row, col);
                int y = SEA_LEVEL_Y + (int) Math.round(meters / metersPerBlock);
                y = Math.max(MIN_Y, Math.min(MAX_Y, y));
                boolean submerged = y < SEA_LEVEL_Y;
                BlockId surface = submerged ? sand : (meters < 400 ? grass : stone);
                grid.set(x, z, y, submerged ? SEA_LEVEL_Y : TerrainColumn.NO_WATER, surface);
            }
        }
        return grid;
    }

    private static double bilinear(Dem dem, double row, double col) {
        row = Math.max(0, Math.min(dem.rows() - 1, row));
        col = Math.max(0, Math.min(dem.cols() - 1, col));
        int r0 = (int) Math.floor(row);
        int c0 = (int) Math.floor(col);
        int r1 = Math.min(dem.rows() - 1, r0 + 1);
        int c1 = Math.min(dem.cols() - 1, c0 + 1);
        double tr = row - r0;
        double tc = col - c0;
        double[][] e = dem.elevation();
        double top = e[r0][c0] + (e[r0][c1] - e[r0][c0]) * tc;
        double bottom = e[r1][c0] + (e[r1][c1] - e[r1][c0]) * tc;
        return top + (bottom - top) * tr;
    }
}
