package me.alex4386.typhon.simulator.terrain;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.WorldSpec;

/**
 * Imports real elevation data (GeoTIFF, SRTM {@code .hgt}, ESRI ASCII grid, PNG heightmap) as a
 * {@link ColumnGrid}.
 *
 * <p>Two vertical mappings exist:
 * <ul>
 *   <li>{@link #toRealGrid} (real-scale worlds): elevation {@code z} metres lies in ground block
 *       {@code y} with {@code (y + 1)·L ≥ z}, i.e. sea level (0 m) is the top of block −1, exactly the
 *       world model's convention ({@link WorldSpec#groundBlock}). Columns are {@code L} metres wide.
 *   <li>{@link #toGrid} (compact Minecraft-style presets): sea level maps to {@link #SEA_LEVEL_Y} and
 *       heights are clamped to Minecraft's build range.
 * </ul>
 *
 * <p>Geographic DEMs (degrees) are converted to metres with a local equirectangular approximation at
 * the DEM's (or crop's) centre latitude, accurate to well under 1% over the few-km domains used here.
 */
public final class DemImporter {
    /** Minecraft's sea level (compact mapping only). */
    public static final int SEA_LEVEL_Y = 62;
    static final int MIN_Y = -60;
    static final int MAX_Y = 318;

    /** WGS84 metres per degree of latitude / of longitude at the equator (mean values). */
    static final double METERS_PER_DEG_LAT = 110_574;
    static final double METERS_PER_DEG_LON = 111_320;

    private DemImporter() {}

    /**
     * A grid of elevations in metres (row 0 = north), {@code NaN} where unknown.
     *
     * @param cellX column spacing (m, east)
     * @param cellY row spacing (m, south)
     * @param geo georeferencing of row/col 0 (pixel corner) or {@code null}
     */
    public record Dem(double[][] elevation, double cellX, double cellY, GeoTiff.GeoRef geo) {
        public Dem(double[][] elevation, double cellSize) {
            this(elevation, cellSize, cellSize, null);
        }

        public int rows() { return elevation.length; }
        public int cols() { return elevation[0].length; }

        /** Horizontal cell size; the column spacing for anisotropic grids. */
        public double cellSizeMeters() { return cellX; }

        public double min() {
            double m = Double.POSITIVE_INFINITY;
            for (double[] row : elevation) for (double v : row) if (!Double.isNaN(v)) m = Math.min(m, v);
            return m;
        }

        public double max() {
            double m = Double.NEGATIVE_INFINITY;
            for (double[] row : elevation) for (double v : row) if (!Double.isNaN(v)) m = Math.max(m, v);
            return m;
        }

        public int noDataCount() {
            int n = 0;
            for (double[] row : elevation) for (double v : row) if (Double.isNaN(v)) n++;
            return n;
        }

        /**
         * Fractional (row, col) of a geographic point, using pixel-centre coordinates; requires a
         * geographic georeference.
         */
        public double[] locate(double lat, double lon) {
            if (geo == null) throw new IllegalStateException("DEM has no georeferencing");
            if (!geo.geographic()) throw new IllegalStateException("DEM is not in geographic coordinates (EPSG:"
                    + geo.epsg() + "); give the centre in its own units instead");
            return new double[] {geo.row(lat), geo.col(lon)};
        }

        /** Fractional (row, col) of a point in the DEM's own CRS units. */
        public double[] locateCrs(double x, double y) {
            if (geo == null) throw new IllegalStateException("DEM has no georeferencing");
            return new double[] {geo.row(y), geo.col(x)};
        }

        /** Pixel-centre (row, col) of the DEM's middle. */
        public double[] centre() {
            return new double[] {rows() / 2.0 - 0.5, cols() / 2.0 - 0.5};
        }    }

    /** What to read and how: crop centre and size, unit conversion, no-data handling. */
    public record ReadOptions(double defaultCell, double pngMinMeters, double pngMaxMeters) {
        public static final ReadOptions DEFAULT = new ReadOptions(30, 0, 3000);
    }

    /** Reads any supported DEM by file extension. */
    public static Dem read(Path file, ReadOptions options) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".tif") || name.endsWith(".tiff")) return readGeoTiff(file);
        if (name.endsWith(".hgt")) return readHgt(file);
        if (name.endsWith(".png")) return readPng(file, options.pngMinMeters(), options.pngMaxMeters(), options.defaultCell());
        return readAscii(file, options.defaultCell());
    }

    /** Reads a single-band GeoTIFF; geographic rasters get metric cell sizes at their centre latitude. */
    public static Dem readGeoTiff(Path file) throws IOException {
        GeoTiff.Raster raster = GeoTiff.read(file);
        double[][] e = new double[raster.height()][raster.width()];
        for (int r = 0; r < raster.height(); r++) {
            for (int c = 0; c < raster.width(); c++) {
                float v = raster.get(r, c);
                e[r][c] = Float.isNaN(v) || v < -1e4 ? Double.NaN : v; // SRTM voids are -32768
            }
        }
        GeoTiff.GeoRef geo = raster.geo();
        if (geo == null) return new Dem(e, 30, 30, null);
        if (!geo.geographic()) return new Dem(e, geo.pixelX(), geo.pixelY(), geo);
        double lat = geo.y(raster.height() / 2.0 - 0.5);
        return new Dem(e, geo.pixelX() * metersPerDegLon(lat), geo.pixelY() * METERS_PER_DEG_LAT, geo);
    }

    private static final Pattern HGT_NAME = Pattern.compile("([NS])(\\d{2})([EW])(\\d{3})", Pattern.CASE_INSENSITIVE);

    /**
     * Reads an SRTM {@code .hgt} tile (big-endian int16, 1201² = 3" or 3601² = 1"); the tile's south-west
     * corner comes from its name, e.g. {@code N19W156.hgt}. Voids (−32768) become {@code NaN}.
     */
    public static Dem readHgt(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int n = (int) Math.round(Math.sqrt(bytes.length / 2.0));
        if (n * n * 2 != bytes.length || n < 2) throw new IOException(file + ": not an SRTM .hgt tile (" + bytes.length + " bytes)");
        Matcher m = HGT_NAME.matcher(file.getFileName().toString());
        if (!m.find()) throw new IOException(file + ": .hgt file name must contain the tile corner, e.g. N19W156");
        double south = Integer.parseInt(m.group(2)) * (m.group(1).equalsIgnoreCase("S") ? -1 : 1);
        double west = Integer.parseInt(m.group(4)) * (m.group(3).equalsIgnoreCase("W") ? -1 : 1);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        double[][] e = new double[n][n];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                short v = buf.getShort((r * n + c) * 2);
                e[r][c] = v == Short.MIN_VALUE ? Double.NaN : v;
            }
        }
        double step = 1.0 / (n - 1);
        // .hgt samples are pixel centres on whole degrees: the corner lies half a sample outside
        GeoTiff.GeoRef geo = new GeoTiff.GeoRef(west - step / 2, south + 1 + step / 2, step, step, true, 4326);
        double lat = south + 0.5;
        return new Dem(e, step * metersPerDegLon(lat), step * METERS_PER_DEG_LAT, geo);
    }

    static double metersPerDegLon(double lat) {
        return METERS_PER_DEG_LON * Math.cos(Math.toRadians(lat));
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
     * Replaces {@code NaN} cells by the mean of their known neighbours, repeatedly (voids shrink from
     * their edges inwards); cells still unknown after that become {@code fallback}.
     */
    public static Dem fillNoData(Dem dem, double fallback) {
        int rows = dem.rows();
        int cols = dem.cols();
        double[][] e = new double[rows][];
        for (int r = 0; r < rows; r++) e[r] = dem.elevation()[r].clone();
        boolean changed = true;
        for (int pass = 0; pass < 256 && changed; pass++) {
            changed = false;
            double[][] next = new double[rows][];
            for (int r = 0; r < rows; r++) next[r] = e[r].clone();
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (!Double.isNaN(e[r][c])) continue;
                    double sum = 0;
                    int n = 0;
                    for (int dr = -1; dr <= 1; dr++) {
                        for (int dc = -1; dc <= 1; dc++) {
                            int rr = r + dr;
                            int cc = c + dc;
                            if (rr < 0 || cc < 0 || rr >= rows || cc >= cols) continue;
                            double v = e[rr][cc];
                            if (!Double.isNaN(v)) { sum += v; n++; }
                        }
                    }
                    if (n > 0) {
                        next[r][c] = sum / n;
                        changed = true;
                    }
                }
            }
            e = next;
        }
        for (double[] row : e) for (int c = 0; c < cols; c++) if (Double.isNaN(row[c])) row[c] = fallback;
        return new Dem(e, dem.cellX(), dem.cellY(), dem.geo());
    }

    /**
     * Cuts a window of {@code halfWidthMeters} either side of a centre given as a fractional pixel-centre
     * (row, col). The window is clamped to the DEM; the georeference follows the crop.
     */
    public static Dem crop(Dem dem, double centreRow, double centreCol, double halfWidthMeters) {
        int halfCols = (int) Math.ceil(halfWidthMeters / dem.cellX()) + 1;
        int halfRows = (int) Math.ceil(halfWidthMeters / dem.cellY()) + 1;
        int r0 = Math.max(0, (int) Math.floor(centreRow) - halfRows);
        int r1 = Math.min(dem.rows(), (int) Math.ceil(centreRow) + halfRows + 1);
        int c0 = Math.max(0, (int) Math.floor(centreCol) - halfCols);
        int c1 = Math.min(dem.cols(), (int) Math.ceil(centreCol) + halfCols + 1);
        if (r1 - r0 < 2 || c1 - c0 < 2) throw new IllegalArgumentException("crop centre lies outside the DEM");
        double[][] e = new double[r1 - r0][];
        for (int r = r0; r < r1; r++) e[r - r0] = java.util.Arrays.copyOfRange(dem.elevation()[r], c0, c1);
        GeoTiff.GeoRef g = dem.geo();
        GeoTiff.GeoRef geo = g == null ? null : new GeoTiff.GeoRef(g.originX() + c0 * g.pixelX(),
                g.originY() - r0 * g.pixelY(), g.pixelX(), g.pixelY(), g.geographic(), g.epsg());
        return new Dem(e, dem.cellX(), dem.cellY(), geo);
    }

    /** Surface block for an imported column. */
    @FunctionalInterface
    public interface SurfacePainter {
        BlockId paint(int x, int z, double elevation, boolean submerged);

        /** Sand under water, grass in the lowlands, bare stone higher up. */
        SurfacePainter DEFAULT = (x, z, elevation, submerged) -> BlockId.minecraft(
                submerged ? "sand" : elevation < 400 ? "grass_block" : "stone");
    }

    /**
     * Real-scale mapping: resamples the DEM onto {@code metersPerColumn} columns centred on the given
     * pixel-centre (row, col) — box-averaging when columns are coarser than the DEM, bilinear otherwise —
     * covering at most {@code halfExtentColumns} columns either side (rounded up to whole chunks).
     * Columns whose ground lies below {@code seaLevelZ} (unless {@code NaN}) are flooded up to it.
     */
    public static ColumnGrid toRealGrid(Dem dem, double metersPerColumn, double centreRow, double centreCol,
            int halfExtentColumns, double seaLevelZ, SurfacePainter painter) {
        ColumnGrid grid = ColumnGrid.centered(halfExtentColumns);
        int waterY = Double.isNaN(seaLevelZ) ? TerrainColumn.NO_WATER : groundBlock(seaLevelZ, metersPerColumn);
        double spanC = metersPerColumn / dem.cellX();
        double spanR = metersPerColumn / dem.cellY();
        for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
            for (int x = grid.minX(); x <= grid.maxX(); x++) {
                double col = centreCol + (x + 0.5) * spanC;
                double row = centreRow + (z + 0.5) * spanR;
                double meters = spanC > 1 || spanR > 1 ? boxAverage(dem, row, col, spanR, spanC) : bilinear(dem, row, col);
                int y = groundBlock(meters, metersPerColumn);
                boolean submerged = !Double.isNaN(seaLevelZ) && meters < seaLevelZ;
                grid.set(x, z, y, submerged ? waterY : TerrainColumn.NO_WATER, painter.paint(x, z, meters, submerged));
            }
        }
        return grid;
    }

    /** Ground block whose top is the surface at {@code elevation} (same as {@link WorldSpec#groundBlock}). */
    public static int groundBlock(double elevation, double metersPerColumn) {
        return (int) Math.ceil(elevation / metersPerColumn - 1e-6) - 1;
    }

    /**
     * Compact (Minecraft-style) mapping: resamples a DEM onto blocks of {@code metersPerBlock},
     * bilinearly, keeping at most {@code maxHalfExtent} blocks either side of the centre; sea level is
     * {@link #SEA_LEVEL_Y} and heights are clamped to the build range.
     */
    public static ColumnGrid toGrid(Dem dem, double metersPerBlock, int maxHalfExtent) {
        double widthBlocks = dem.cols() * dem.cellX() / metersPerBlock;
        double heightBlocks = dem.rows() * dem.cellY() / metersPerBlock;
        int half = (int) Math.min(maxHalfExtent, Math.max(widthBlocks, heightBlocks) / 2);
        ColumnGrid grid = ColumnGrid.centered(half);
        BlockId grass = BlockId.minecraft("grass_block");
        BlockId stone = BlockId.minecraft("stone");
        BlockId sand = BlockId.minecraft("sand");
        for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
            for (int x = grid.minX(); x <= grid.maxX(); x++) {
                double col = (x * metersPerBlock) / dem.cellX() + dem.cols() / 2.0;
                double row = (z * metersPerBlock) / dem.cellY() + dem.rows() / 2.0;
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

    /** Mean of the DEM samples whose centres fall in the footprint; bilinear if none does. */
    static double boxAverage(Dem dem, double row, double col, double spanR, double spanC) {
        int r0 = (int) Math.ceil(row - spanR / 2);
        int r1 = (int) Math.ceil(row + spanR / 2);
        int c0 = (int) Math.ceil(col - spanC / 2);
        int c1 = (int) Math.ceil(col + spanC / 2);
        double sum = 0;
        int n = 0;
        for (int r = Math.max(0, r0); r < Math.min(dem.rows(), r1); r++) {
            double[] line = dem.elevation()[r];
            for (int c = Math.max(0, c0); c < Math.min(dem.cols(), c1); c++) {
                double v = line[c];
                if (!Double.isNaN(v)) { sum += v; n++; }
            }
        }
        return n > 0 ? sum / n : bilinear(dem, row, col);
    }

    static double bilinear(Dem dem, double row, double col) {
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
