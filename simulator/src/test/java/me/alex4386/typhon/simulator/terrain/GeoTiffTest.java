package me.alex4386.typhon.simulator.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GeoTiffTest {
    @TempDir
    Path dir;

    /** A smooth synthetic elevation field with negative values and a little high-frequency detail. */
    static double[] field(int w, int h, double scale, double offset) {
        double[] d = new double[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                d[y * w + x] = offset + scale * (Math.sin(x * 0.3) * 40 + Math.cos(y * 0.2) * 25 + (x * 7 + y * 13) % 11);
            }
        }
        return d;
    }

    private static void assertRaster(double[] expected, GeoTiff.Raster r, double tolerance) {
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], r.data()[i], tolerance, "sample " + i);
        }
    }

    @Test
    void int16StripsWithGeographicGeoKeysAndNoData() throws IOException {
        int w = 21;
        int h = 13;
        double[] data = field(w, h, 1, -20);
        data[5] = -32768;
        TiffWriter.Spec spec = TiffWriter.Spec.plain(ByteOrder.LITTLE_ENDIAN, 16, 2)
                .geo(new double[] {1 / 3600.0, 1 / 3600.0}, new double[] {0, 0, -156.0, 20.0}, true, 4326, false)
                .nodata("-32768");
        GeoTiff.Raster r = GeoTiff.read(TiffWriter.write(w, h, data, spec), "int16");
        assertEquals(w, r.width());
        assertEquals(h, r.height());
        assertTrue(Float.isNaN(r.data()[5]), "nodata becomes NaN");
        data[5] = Double.NaN;
        for (int i = 0; i < data.length; i++) {
            if (i != 5) assertEquals(Math.round(data[i]), r.data()[i], 0.0, "sample " + i);
        }
        GeoTiff.GeoRef g = r.geo();
        assertNotNull(g);
        assertTrue(g.geographic());
        assertEquals(4326, g.epsg());
        assertEquals(-156.0, g.originX(), 1e-12);
        assertEquals(20.0, g.originY(), 1e-12);
        assertEquals(-156.0 + 0.5 / 3600, g.x(0), 1e-12);
        assertEquals(20.0 - 2.5 / 3600, g.y(2), 1e-12);
    }

    @Test
    void float32TiledDeflateWithFloatingPointPredictorBigEndian() throws IOException {
        int w = 37;
        int h = 23;
        double[] data = field(w, h, 1.37, 1000.25);
        TiffWriter.Spec spec = TiffWriter.Spec.plain(ByteOrder.BIG_ENDIAN, 32, 3).compression(8, 3).tiled(16)
                .geo(new double[] {30, 30}, new double[] {0, 0, 260000, 2150000}, false, 32605, false);
        GeoTiff.Raster r = GeoTiff.read(TiffWriter.write(w, h, data, spec), "float32");
        assertRaster(data, r, 1e-3);
        assertEquals(32605, r.geo().epsg());
        assertTrue(!r.geo().geographic());
        assertEquals(30, r.geo().pixelX(), 0);
    }

    @Test
    void float32LittleEndianStripsWithFloatPredictorAndLzw() throws IOException {
        int w = 50;
        int h = 9;
        double[] data = field(w, h, 0.5, -3.5);
        TiffWriter.Spec spec = TiffWriter.Spec.plain(ByteOrder.LITTLE_ENDIAN, 32, 3).compression(5, 3);
        assertRaster(data, GeoTiff.read(TiffWriter.write(w, h, data, spec), "f32-lzw"), 1e-4);
    }

    @Test
    void int16AndUint16WithLzwDeflatePackBitsAndHorizontalPredictor() throws IOException {
        int w = 70;
        int h = 40;
        double[] signed = field(w, h, 3, -100);
        double[] unsigned = field(w, h, 3, 1200);
        int[][] codecs = {{1, 1}, {5, 1}, {5, 2}, {8, 1}, {8, 2}, {32773, 1}};
        for (int[] c : codecs) {
            for (ByteOrder order : new ByteOrder[] {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN}) {
                for (int tile : new int[] {0, 32}) {
                    TiffWriter.Spec s = TiffWriter.Spec.plain(order, 16, 2).compression(c[0], c[1]).tiled(tile);
                    GeoTiff.Raster r = GeoTiff.read(TiffWriter.write(w, h, signed, s), "int16 " + c[0] + "/" + c[1]);
                    for (int i = 0; i < signed.length; i++) assertEquals(Math.round(signed[i]), r.data()[i], 0.0);
                    TiffWriter.Spec u = TiffWriter.Spec.plain(order, 16, 1).compression(c[0], c[1]).tiled(tile);
                    GeoTiff.Raster ru = GeoTiff.read(TiffWriter.write(w, h, unsigned, u), "uint16");
                    for (int i = 0; i < unsigned.length; i++) assertEquals(Math.round(unsigned[i]), ru.data()[i], 0.0);
                }
            }
        }
    }

    @Test
    void lzwHandlesLongInputsAcrossCodeWidthsAndClears() throws IOException {
        // > 4094 distinct strings forces 10-, 11-, 12-bit codes and a Clear code
        byte[] raw = new byte[60_000];
        long x = 12345;
        for (int i = 0; i < raw.length; i++) {
            x = x * 6364136223846793005L + 1442695040888963407L;
            raw[i] = (byte) ((i % 7 == 0) ? (x >>> 59) : (i & 0x3f));
        }
        byte[] decoded = GeoTiff.lzwDecode(TiffWriter.lzw(raw), raw.length, "lzw");
        assertEquals(raw.length, decoded.length);
        for (int i = 0; i < raw.length; i++) assertEquals(raw[i], decoded[i], "byte " + i);
    }

    /** Independent encoder: the JDK's ImageIO TIFF writer (LZW, Deflate, PackBits, tiles). */
    @Test
    void readsTiffsWrittenByImageIo() throws IOException {
        int w = 83;
        int h = 61;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_USHORT_GRAY);
        WritableRaster raster = image.getRaster();
        for (int y = 0; y < h; y++) {
            for (int xx = 0; xx < w; xx++) raster.setSample(xx, y, 0, (xx * 977 + y * 131 + (xx * y) % 17) & 0xffff);
        }
        String[][] cases = {{"Uncompressed", "0"}, {"LZW", "0"}, {"Deflate", "0"}, {"PackBits", "0"}, {"LZW", "32"},
                {"Deflate", "16"}};
        for (String[] c : cases) {
            byte[] bytes = writeImageIo(image, c[0], Integer.parseInt(c[1]));
            GeoTiff.Raster r = GeoTiff.read(bytes, "imageio-" + c[0]);
            assertEquals(w, r.width());
            assertEquals(h, r.height());
            for (int y = 0; y < h; y++) {
                for (int xx = 0; xx < w; xx++) {
                    assertEquals(raster.getSample(xx, y, 0), r.get(y, xx), 0.0, c[0] + " tile " + c[1] + " at " + xx + "," + y);
                }
            }
        }
    }

    private static byte[] writeImageIo(BufferedImage image, String compression, int tile) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("tiff").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (compression.equals("Uncompressed")) {
                param.setCompressionMode(ImageWriteParam.MODE_DISABLED);
            } else {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionType(compression);
            }
            if (tile > 0) {
                param.setTilingMode(ImageWriteParam.MODE_EXPLICIT);
                param.setTiling(tile, tile, 0, 0);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    @Test
    void pixelIsPointMovesTheOriginByHalfAPixel() throws IOException {
        TiffWriter.Spec spec = TiffWriter.Spec.plain(ByteOrder.LITTLE_ENDIAN, 16, 2)
                .geo(new double[] {10, 10}, new double[] {0, 0, 500, 900}, false, 32633, true);
        GeoTiff.GeoRef g = GeoTiff.read(TiffWriter.write(4, 4, new double[16], spec), "pip").geo();
        assertEquals(495, g.originX(), 1e-9);
        assertEquals(905, g.originY(), 1e-9);
        assertEquals(500, g.x(0), 1e-9);
    }

    @Test
    void rejectsGarbageAndUnsupportedCodecs() {
        assertThrows(IOException.class, () -> GeoTiff.read(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9}, "junk"));
        byte[] tiff = TiffWriter.write(4, 4, new double[16], TiffWriter.Spec.plain(ByteOrder.LITTLE_ENDIAN, 16, 2));
        // patch compression to JPEG (7)
        ByteBuffer b = ByteBuffer.wrap(tiff).order(ByteOrder.LITTLE_ENDIAN);
        int ifd = b.getInt(4);
        int n = b.getShort(ifd);
        for (int i = 0; i < n; i++) {
            int p = ifd + 2 + 12 * i;
            if (b.getShort(p) == 259) b.putShort(p + 8, (short) 7);
        }
        IOException e = assertThrows(IOException.class, () -> GeoTiff.read(tiff, "jpeg"));
        assertTrue(e.getMessage().contains("compression 7"), e.getMessage());
    }

    // ── DemImporter on GeoTIFF / HGT ──

    @Test
    void geographicGeoTiffGetsMetricCellsAndCropsAroundALatLon() throws IOException {
        int w = 120;
        int h = 120;
        double[] data = new double[w * h];
        for (int y = 0; y < h; y++) {
            for (int xx = 0; xx < w; xx++) data[y * w + xx] = 10 * xx + y; // rises east and south
        }
        double step = 1 / 3600.0; // 1 arc-second
        TiffWriter.Spec spec = TiffWriter.Spec.plain(ByteOrder.LITTLE_ENDIAN, 32, 3).compression(8, 3)
                .geo(new double[] {step, step}, new double[] {0, 0, 15.0, 39.0}, true, 4326, false);
        Path file = dir.resolve("dem.tif");
        Files.write(file, TiffWriter.write(w, h, data, spec));
        DemImporter.Dem dem = DemImporter.readGeoTiff(file);
        double lat = 39.0 - 60 * step;
        assertEquals(step * 111_320 * Math.cos(Math.toRadians(lat)), dem.cellX(), 0.05);
        assertEquals(step * 110_574, dem.cellY(), 1e-6);

        double[] rc = dem.locate(39.0 - 30.5 * step, 15.0 + 70.5 * step); // centre of pixel (30, 70)
        assertEquals(30, rc[0], 1e-9);
        assertEquals(70, rc[1], 1e-9);
        DemImporter.Dem crop = DemImporter.crop(dem, rc[0], rc[1], 300);
        double[] c2 = crop.locate(39.0 - 30.5 * step, 15.0 + 70.5 * step);
        assertEquals(data[30 * w + 70], DemImporter.bilinear(crop, c2[0], c2[1]), 1e-3);
    }

    @Test
    void gridKeepsElevationsInMetresAndFloodsBelowSeaLevel() {
        double[][] e = new double[64][64];
        for (int r = 0; r < 64; r++) for (int c = 0; c < 64; c++) e[r][c] = (c - 32) * 10.0; // -320 .. 310 m
        DemImporter.Dem dem = new DemImporter.Dem(e, 10);
        double[] centre = dem.centre();
        ColumnGrid g = DemImporter.toGrid(dem, 10, centre[0], centre[1], 24, 0, DemImporter.SurfacePainter.DEFAULT);
        // column x covers [x*10, x*10+10) m east of centre; the DEM rises 1 m per m eastwards
        int x = 5;
        double meters = DemImporter.bilinear(dem, centre[0] + 0.5, centre[1] + x + 0.5);
        assertEquals(meters, g.surfaceZ(x, 0), 1e-9);
        assertTrue(Double.isNaN(g.waterZ(x, 0)), "dry above sea level");
        int sub = -10;
        assertTrue(g.surfaceZ(sub, 0) < 0);
        assertEquals(0, g.waterZ(sub, 0), 1e-9, "flooded up to sea level");
    }

    @Test
    void coarseColumnsAverageTheDemAndFineColumnsInterpolate() {
        double[][] e = new double[40][40];
        for (int r = 0; r < 40; r++) for (int c = 0; c < 40; c++) e[r][c] = (r + c) % 2 == 0 ? 100 : 0; // checkerboard
        DemImporter.Dem dem = new DemImporter.Dem(e, 5);
        // 20 m columns average 4x4 DEM cells of a checkerboard: exactly 50 m
        assertEquals(50, DemImporter.boxAverage(dem, 10.5, 10.5, 4, 4), 1e-9);
        ColumnGrid coarse = DemImporter.toGrid(dem, 20, 19.5, 19.5, 4, Double.NaN, DemImporter.SurfacePainter.DEFAULT);
        assertEquals(50, coarse.surfaceZ(0, 0), 1e-9);
    }

    @Test
    void fillNoDataGrowsFromNeighbours() {
        double[][] e = {{1, 1, 1}, {1, Double.NaN, 1}, {1, 1, 1}};
        DemImporter.Dem filled = DemImporter.fillNoData(new DemImporter.Dem(e, 10), -5);
        assertEquals(1, filled.elevation()[1][1], 1e-12);
        double[][] all = {{Double.NaN, Double.NaN}, {Double.NaN, Double.NaN}};
        assertEquals(-5, DemImporter.fillNoData(new DemImporter.Dem(all, 10), -5).elevation()[0][0]);
    }

    @Test
    void readsSrtmHgtTilesWithTheCornerFromTheName() throws IOException {
        int n = 1201; // 3 arc-second tile
        ByteBuffer b = ByteBuffer.allocate(n * n * 2).order(ByteOrder.BIG_ENDIAN);
        for (int r = 0; r < n; r++) for (int c = 0; c < n; c++) b.putShort((short) (r == 7 && c == 9 ? -32768 : r + c));
        Path file = dir.resolve("N38E015.hgt");
        Files.write(file, b.array());
        DemImporter.Dem dem = DemImporter.readHgt(file);
        assertEquals(n, dem.rows());
        assertTrue(Double.isNaN(dem.elevation()[7][9]));
        assertEquals(10, dem.elevation()[3][7]);
        // north-west sample centre is exactly (39 N, 15 E)
        double[] rc = dem.locate(39.0, 15.0);
        assertEquals(0, rc[0], 1e-9);
        assertEquals(0, rc[1], 1e-9);
        double[] se = dem.locate(38.0, 16.0);
        assertEquals(n - 1, se[0], 1e-6);
        assertEquals(n - 1, se[1], 1e-6);
        assertEquals(3.0 / 3600 * 110_574, dem.cellY(), 1e-6);
    }
}
