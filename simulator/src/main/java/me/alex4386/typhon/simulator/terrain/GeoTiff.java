package me.alex4386.typhon.simulator.terrain;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Minimal, dependency-free reader for single-band elevation GeoTIFFs (SRTM, Copernicus GLO-30,
 * USGS 3DEP, GDAL exports).
 *
 * <p>Supported: classic TIFF and BigTIFF, either byte order; strips or tiles; 8/16/32/64-bit
 * unsigned, signed and IEEE float samples (only the first sample of each pixel is read); compression
 * none, LZW, Deflate (Adobe and old-style) and PackBits; horizontal (2) and floating-point (3)
 * predictors. GeoTIFF georeferencing comes from ModelPixelScale + ModelTiepoint or a
 * non-rotated ModelTransformation, the GeoKey directory (model type, raster type, EPSG code) and
 * the GDAL_NODATA tag. Only the first image (full resolution in a cloud-optimised GeoTIFF) is read.
 *
 * <p>Not supported: JPEG/WebP/LERC/ZSTD compression, rotated or sheared transforms, separate planes
 * with several samples.
 */
public final class GeoTiff {
    private GeoTiff() {}

    /**
     * Georeferencing of the raster: the upper-left corner of pixel (0, 0) in CRS units and the pixel
     * size (columns grow east by {@code pixelX}, rows grow south by {@code pixelY}).
     *
     * @param geographic {@code true} when CRS units are degrees of longitude/latitude
     * @param epsg EPSG code of the CRS, or 0 when unknown
     */
    public record GeoRef(double originX, double originY, double pixelX, double pixelY, boolean geographic, int epsg) {
        /** CRS x of the centre of column {@code col}. */
        public double x(double col) {
            return originX + (col + 0.5) * pixelX;
        }

        /** CRS y of the centre of row {@code row}. */
        public double y(double row) {
            return originY - (row + 0.5) * pixelY;
        }

        /** Fractional column whose centre is CRS {@code x}. */
        public double col(double x) {
            return (x - originX) / pixelX - 0.5;
        }

        /** Fractional row whose centre is CRS {@code y}. */
        public double row(double y) {
            return (originY - y) / pixelY - 0.5;
        }
    }

    /**
     * A decoded raster, row 0 at the top (north).
     *
     * @param data row-major samples ({@code NaN} where the file had no data)
     * @param geo georeferencing, or {@code null} for a plain TIFF
     */
    public record Raster(int width, int height, float[] data, GeoRef geo) {
        public float get(int row, int col) {
            return data[row * width + col];
        }
    }

    // TIFF tags
    static final int IMAGE_WIDTH = 256;
    static final int IMAGE_LENGTH = 257;
    static final int BITS_PER_SAMPLE = 258;
    static final int COMPRESSION = 259;
    static final int STRIP_OFFSETS = 273;
    static final int SAMPLES_PER_PIXEL = 277;
    static final int ROWS_PER_STRIP = 278;
    static final int STRIP_BYTE_COUNTS = 279;
    static final int PLANAR_CONFIGURATION = 284;
    static final int PREDICTOR = 317;
    static final int TILE_WIDTH = 322;
    static final int TILE_LENGTH = 323;
    static final int TILE_OFFSETS = 324;
    static final int TILE_BYTE_COUNTS = 325;
    static final int SAMPLE_FORMAT = 339;
    static final int MODEL_PIXEL_SCALE = 33550;
    static final int MODEL_TIEPOINT = 33922;
    static final int MODEL_TRANSFORMATION = 34264;
    static final int GEO_KEY_DIRECTORY = 34735;
    static final int GDAL_NODATA = 42113;

    // GeoKeys
    static final int GT_MODEL_TYPE = 1024;
    static final int GT_RASTER_TYPE = 1025;
    static final int GEOGRAPHIC_TYPE = 2048;
    static final int PROJECTED_CS_TYPE = 3072;

    static final int COMPRESSION_NONE = 1;
    static final int COMPRESSION_LZW = 5;
    static final int COMPRESSION_DEFLATE = 8;
    static final int COMPRESSION_DEFLATE_OLD = 32946;
    static final int COMPRESSION_PACKBITS = 32773;

    public static Raster read(Path file) throws IOException {
        return read(Files.readAllBytes(file), file.toString());
    }

    public static Raster read(byte[] bytes, String name) throws IOException {
        if (bytes.length < 8) throw new IOException(name + ": not a TIFF file");
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        if (bytes[0] == 'I' && bytes[1] == 'I') {
            buf.order(ByteOrder.LITTLE_ENDIAN);
        } else if (bytes[0] == 'M' && bytes[1] == 'M') {
            buf.order(ByteOrder.BIG_ENDIAN);
        } else {
            throw new IOException(name + ": not a TIFF file (bad byte-order mark)");
        }
        int magic = Short.toUnsignedInt(buf.getShort(2));
        boolean big;
        long ifd;
        if (magic == 42) {
            big = false;
            ifd = Integer.toUnsignedLong(buf.getInt(4));
        } else if (magic == 43) {
            big = true;
            if (buf.getShort(4) != 8) throw new IOException(name + ": unsupported BigTIFF offset size");
            ifd = buf.getLong(8);
        } else {
            throw new IOException(name + ": not a TIFF file (magic " + magic + ")");
        }
        Map<Integer, Entry> tags = readIfd(buf, ifd, big, name);
        return decode(buf, tags, name);
    }

    /** One IFD entry, values decoded lazily. */
    record Entry(int tag, int type, long count, long valueOffset, boolean inline) {}

    private static Map<Integer, Entry> readIfd(ByteBuffer buf, long offset, boolean big, String name) throws IOException {
        checkRange(buf, offset, big ? 8 : 2, name);
        long n = big ? buf.getLong((int) offset) : Short.toUnsignedInt(buf.getShort((int) offset));
        int entrySize = big ? 20 : 12;
        long pos = offset + (big ? 8 : 2);
        checkRange(buf, pos, n * entrySize, name);
        Map<Integer, Entry> tags = new HashMap<>();
        for (long i = 0; i < n; i++) {
            int p = (int) (pos + i * entrySize);
            int tag = Short.toUnsignedInt(buf.getShort(p));
            int type = Short.toUnsignedInt(buf.getShort(p + 2));
            long count = big ? buf.getLong(p + 4) : Integer.toUnsignedLong(buf.getInt(p + 4));
            int size = typeSize(type);
            long total = size * count;
            int inlineBytes = big ? 8 : 4;
            boolean inline = total <= inlineBytes;
            long value = inline ? p + (big ? 12 : 8)
                    : (big ? buf.getLong(p + 12) : Integer.toUnsignedLong(buf.getInt(p + 8)));
            tags.put(tag, new Entry(tag, type, count, value, inline));
        }
        return tags;
    }

    static int typeSize(int type) {
        return switch (type) {
            case 1, 2, 6, 7 -> 1;  // BYTE, ASCII, SBYTE, UNDEFINED
            case 3, 8 -> 2;        // SHORT, SSHORT
            case 4, 9, 11 -> 4;    // LONG, SLONG, FLOAT
            case 5, 10, 12, 16, 17, 18 -> 8; // RATIONAL, SRATIONAL, DOUBLE, LONG8, SLONG8, IFD8
            default -> 1;
        };
    }

    private static long[] longs(ByteBuffer buf, Entry e, String name) throws IOException {
        int size = typeSize(e.type());
        checkRange(buf, e.valueOffset(), size * e.count(), name);
        long[] out = new long[(int) e.count()];
        int base = (int) e.valueOffset();
        for (int i = 0; i < out.length; i++) {
            int p = base + i * size;
            out[i] = switch (e.type()) {
                case 1, 7 -> Byte.toUnsignedLong(buf.get(p));
                case 6 -> buf.get(p);
                case 3 -> Short.toUnsignedLong(buf.getShort(p));
                case 8 -> buf.getShort(p);
                case 4 -> Integer.toUnsignedLong(buf.getInt(p));
                case 9 -> buf.getInt(p);
                case 16, 17, 18 -> buf.getLong(p);
                default -> throw new IOException(name + ": tag " + e.tag() + " has non-integer type " + e.type());
            };
        }
        return out;
    }

    private static double[] doubles(ByteBuffer buf, Entry e, String name) throws IOException {
        int size = typeSize(e.type());
        checkRange(buf, e.valueOffset(), size * e.count(), name);
        double[] out = new double[(int) e.count()];
        int base = (int) e.valueOffset();
        for (int i = 0; i < out.length; i++) {
            int p = base + i * size;
            out[i] = switch (e.type()) {
                case 11 -> buf.getFloat(p);
                case 12 -> buf.getDouble(p);
                case 5 -> Integer.toUnsignedLong(buf.getInt(p)) / (double) Integer.toUnsignedLong(buf.getInt(p + 4));
                case 10 -> buf.getInt(p) / (double) buf.getInt(p + 4);
                default -> longs(buf, new Entry(e.tag(), e.type(), 1, p, true), name)[0];
            };
        }
        return out;
    }

    private static String ascii(ByteBuffer buf, Entry e, String name) throws IOException {
        checkRange(buf, e.valueOffset(), e.count(), name);
        byte[] b = new byte[(int) e.count()];
        buf.get((int) e.valueOffset(), b);
        String s = new String(b, StandardCharsets.US_ASCII);
        int nul = s.indexOf('\0');
        return (nul >= 0 ? s.substring(0, nul) : s).trim();
    }

    private static long first(ByteBuffer buf, Map<Integer, Entry> tags, int tag, long fallback, String name)
            throws IOException {
        Entry e = tags.get(tag);
        return e == null ? fallback : longs(buf, e, name)[0];
    }

    private static void checkRange(ByteBuffer buf, long offset, long length, String name) throws IOException {
        if (offset < 0 || length < 0 || offset + length > buf.capacity()) {
            throw new IOException(name + ": truncated or corrupt TIFF (offset " + offset + ", length " + length + ")");
        }
    }

    private static Raster decode(ByteBuffer buf, Map<Integer, Entry> tags, String name) throws IOException {
        int width = (int) first(buf, tags, IMAGE_WIDTH, -1, name);
        int height = (int) first(buf, tags, IMAGE_LENGTH, -1, name);
        if (width <= 0 || height <= 0) throw new IOException(name + ": missing image size");
        if ((long) width * height > 400_000_000L) throw new IOException(name + ": image too large (" + width + "x" + height + ")");
        int bits = (int) first(buf, tags, BITS_PER_SAMPLE, 1, name);
        int samples = (int) first(buf, tags, SAMPLES_PER_PIXEL, 1, name);
        int format = (int) first(buf, tags, SAMPLE_FORMAT, 1, name);
        int compression = (int) first(buf, tags, COMPRESSION, COMPRESSION_NONE, name);
        int predictor = (int) first(buf, tags, PREDICTOR, 1, name);
        int planar = (int) first(buf, tags, PLANAR_CONFIGURATION, 1, name);
        if (bits % 8 != 0 || bits > 64) throw new IOException(name + ": unsupported BitsPerSample " + bits);
        if (planar == 2 && samples > 1) throw new IOException(name + ": separate-plane images are not supported");
        if (format != 1 && format != 2 && format != 3) throw new IOException(name + ": unsupported SampleFormat " + format);
        if (format == 3 && bits != 32 && bits != 64) throw new IOException(name + ": float samples must be 32 or 64 bit");
        int bytesPerSample = bits / 8;
        int pixelBytes = bytesPerSample * samples;

        float[] data = new float[width * height];
        boolean tiled = tags.containsKey(TILE_OFFSETS);
        int blockW;
        int blockH;
        long[] offsets;
        long[] counts;
        if (tiled) {
            blockW = (int) first(buf, tags, TILE_WIDTH, -1, name);
            blockH = (int) first(buf, tags, TILE_LENGTH, -1, name);
            if (blockW <= 0 || blockH <= 0) throw new IOException(name + ": missing tile size");
            offsets = longs(buf, tags.get(TILE_OFFSETS), name);
            Entry countEntry = tags.get(TILE_BYTE_COUNTS);
            if (countEntry == null) throw new IOException(name + ": missing TileByteCounts");
            counts = longs(buf, countEntry, name);
        } else {
            Entry offsetEntry = tags.get(STRIP_OFFSETS);
            if (offsetEntry == null) throw new IOException(name + ": missing StripOffsets");
            blockW = width;
            blockH = (int) Math.min(height, first(buf, tags, ROWS_PER_STRIP, height, name));
            offsets = longs(buf, offsetEntry, name);
            Entry countEntry = tags.get(STRIP_BYTE_COUNTS);
            counts = countEntry != null ? longs(buf, countEntry, name) : null;
        }
        int across = (width + blockW - 1) / blockW;
        int down = (height + blockH - 1) / blockH;
        if (offsets.length < (long) across * down) throw new IOException(name + ": too few strips/tiles");

        ByteOrder order = buf.order();
        for (int b = 0; b < across * down; b++) {
            int bx = b % across;
            int by = b / across;
            int rows = tiled ? blockH : Math.min(blockH, height - by * blockH);
            int rowBytes = blockW * pixelBytes;
            int expected = rowBytes * rows;
            long count = counts != null ? counts[b] : expected;
            checkRange(buf, offsets[b], count, name);
            byte[] raw = new byte[(int) count];
            buf.get((int) offsets[b], raw);
            byte[] block = switch (compression) {
                case COMPRESSION_NONE -> raw;
                case COMPRESSION_LZW -> lzwDecode(raw, expected, name);
                case COMPRESSION_DEFLATE, COMPRESSION_DEFLATE_OLD -> inflate(raw, expected, name);
                case COMPRESSION_PACKBITS -> packBitsDecode(raw, expected);
                default -> throw new IOException(name + ": unsupported compression " + compression
                        + " (supported: none, LZW, Deflate, PackBits)");
            };
            if (block.length < expected) block = java.util.Arrays.copyOf(block, expected);
            ByteBuffer bb = ByteBuffer.wrap(block);
            if (predictor == 3) {
                if (format != 3) throw new IOException(name + ": floating-point predictor on integer samples");
                undoFloatPredictor(block, rows, blockW, samples, bytesPerSample);
                bb.order(ByteOrder.BIG_ENDIAN); // the predictor stores bytes most-significant first
            } else {
                bb.order(order);
                if (predictor == 2) undoHorizontalPredictor(bb, rows, blockW, samples, bytesPerSample);
                else if (predictor != 1) throw new IOException(name + ": unsupported predictor " + predictor);
            }
            for (int r = 0; r < rows; r++) {
                int row = by * blockH + r;
                if (row >= height) break;
                for (int c = 0; c < blockW; c++) {
                    int col = bx * blockW + c;
                    if (col >= width) break;
                    int p = r * rowBytes + c * pixelBytes;
                    data[row * width + col] = (float) sample(bb, p, bits, format);
                }
            }
        }

        Entry nodataEntry = tags.get(GDAL_NODATA);
        if (nodataEntry != null) {
            String text = ascii(buf, nodataEntry, name);
            if (!text.isEmpty() && !text.equalsIgnoreCase("nan")) {
                double nodata = Double.parseDouble(text);
                float nd = (float) nodata;
                for (int i = 0; i < data.length; i++) {
                    if (data[i] == nd || (Math.abs(nodata) > 1e30 && Math.abs(data[i]) > 1e30)) data[i] = Float.NaN;
                }
            }
        }
        return new Raster(width, height, data, georef(buf, tags, width, height, name));
    }

    private static double sample(ByteBuffer bb, int p, int bits, int format) {
        return switch (format) {
            case 3 -> bits == 32 ? bb.getFloat(p) : bb.getDouble(p);
            case 2 -> switch (bits) {
                case 8 -> bb.get(p);
                case 16 -> bb.getShort(p);
                case 32 -> bb.getInt(p);
                default -> bb.getLong(p);
            };
            default -> switch (bits) {
                case 8 -> Byte.toUnsignedInt(bb.get(p));
                case 16 -> Short.toUnsignedInt(bb.getShort(p));
                case 32 -> Integer.toUnsignedLong(bb.getInt(p));
                default -> bb.getLong(p);
            };
        };
    }

    private static GeoRef georef(ByteBuffer buf, Map<Integer, Entry> tags, int width, int height, String name)
            throws IOException {
        double ox;
        double oy;
        double px;
        double py;
        Entry scale = tags.get(MODEL_PIXEL_SCALE);
        Entry tie = tags.get(MODEL_TIEPOINT);
        Entry transform = tags.get(MODEL_TRANSFORMATION);
        if (scale != null && tie != null) {
            double[] s = doubles(buf, scale, name);
            double[] t = doubles(buf, tie, name);
            px = s[0];
            py = s[1];
            // tiepoint (i, j, k) -> (x, y, z): raster point (i, j) is at model (x, y)
            ox = t[3] - t[0] * px;
            oy = t[4] + t[1] * py;
        } else if (transform != null) {
            double[] m = doubles(buf, transform, name);
            if (m[1] != 0 || m[4] != 0) throw new IOException(name + ": rotated ModelTransformation is not supported");
            px = m[0];
            py = -m[5];
            ox = m[3];
            oy = m[7];
        } else {
            return null;
        }
        boolean geographic = false;
        int epsg = 0;
        boolean pixelIsPoint = false;
        Entry keys = tags.get(GEO_KEY_DIRECTORY);
        if (keys != null) {
            long[] k = longs(buf, keys, name);
            int n = (int) k[3];
            for (int i = 0; i < n && 4 + 4 * i + 3 < k.length; i++) {
                int id = (int) k[4 + 4 * i];
                int location = (int) k[4 + 4 * i + 1];
                long value = k[4 + 4 * i + 3];
                if (location != 0) continue; // value stored in another tag (params we do not need)
                switch (id) {
                    case GT_MODEL_TYPE -> geographic = value == 2;
                    case GT_RASTER_TYPE -> pixelIsPoint = value == 2;
                    case GEOGRAPHIC_TYPE -> { if (geographic || epsg == 0) epsg = (int) value; }
                    case PROJECTED_CS_TYPE -> { if (!geographic) epsg = (int) value; }
                    default -> { }
                }
            }
        } else {
            // No keys: guess geographic from the magnitude of the pixel size.
            geographic = Math.abs(px) < 0.1 && Math.abs(ox) <= 360 && Math.abs(oy) <= 90;
        }
        if (pixelIsPoint) {
            // the tie/transform refers to the pixel centre; move to the upper-left corner
            ox -= px / 2;
            oy += py / 2;
        }
        if (!(px > 0) || !(py > 0)) throw new IOException(name + ": bad pixel size " + px + " x " + py);
        return new GeoRef(ox, oy, px, py, geographic, epsg);
    }

    // ── Decoders ──

    static byte[] inflate(byte[] raw, int expected, String name) throws IOException {
        Inflater inflater = new Inflater();
        inflater.setInput(raw);
        byte[] out = new byte[Math.max(expected, 1)];
        int n = 0;
        try {
            while (!inflater.finished() && n < out.length) {
                int read = inflater.inflate(out, n, out.length - n);
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                n += read;
            }
        } catch (DataFormatException e) {
            throw new IOException(name + ": corrupt Deflate data", e);
        } finally {
            inflater.end();
        }
        return out;
    }

    static byte[] packBitsDecode(byte[] raw, int expected) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(expected);
        int i = 0;
        while (i < raw.length && out.size() < expected) {
            int n = raw[i++];
            if (n >= 0) {
                int len = Math.min(n + 1, raw.length - i);
                out.write(raw, i, len);
                i += len;
            } else if (n != -128) {
                if (i >= raw.length) break;
                byte b = raw[i++];
                for (int k = 0; k < 1 - n; k++) out.write(b);
            }
        }
        return out.toByteArray();
    }

    /** TIFF LZW (MSB-first codes, 9–12 bits, early change). */
    static byte[] lzwDecode(byte[] raw, int expected, String name) throws IOException {
        final int clear = 256;
        final int eoi = 257;
        int[] prefix = new int[4096];
        byte[] suffix = new byte[4096];
        int[] length = new int[4096];
        for (int i = 0; i < 256; i++) {
            prefix[i] = -1;
            suffix[i] = (byte) i;
            length[i] = 1;
        }
        byte[] out = new byte[Math.max(expected, 16)];
        int outLen = 0;
        int next = 258;
        int bitsPerCode = 9;
        int previous = -1;
        long bitBuffer = 0;
        int bitCount = 0;
        int pos = 0;
        byte[] scratch = new byte[4096];
        while (true) {
            while (bitCount < bitsPerCode && pos < raw.length) {
                bitBuffer = (bitBuffer << 8) | (raw[pos++] & 0xff);
                bitCount += 8;
            }
            if (bitCount < bitsPerCode) break;
            int code = (int) ((bitBuffer >>> (bitCount - bitsPerCode)) & ((1 << bitsPerCode) - 1));
            bitCount -= bitsPerCode;
            if (code == eoi) break;
            if (code == clear) {
                next = 258;
                bitsPerCode = 9;
                previous = -1;
                continue;
            }
            int first;
            int entry;
            if (code < next) {
                entry = code;
            } else if (code == next && previous >= 0) {
                entry = -1; // KwKwK case: previous string + its first byte
            } else {
                throw new IOException(name + ": corrupt LZW data (code " + code + ")");
            }
            // write the string for `entry` (or previous + first(previous))
            int src = entry >= 0 ? entry : previous;
            int len = length[src];
            int total = len + (entry >= 0 ? 0 : 1);
            if (outLen + total > out.length) out = java.util.Arrays.copyOf(out, Math.max(out.length * 2, outLen + total));
            int k = src;
            for (int i = len - 1; i >= 0; i--) {
                scratch[i] = suffix[k];
                k = prefix[k];
            }
            System.arraycopy(scratch, 0, out, outLen, len);
            first = scratch[0];
            if (entry < 0) out[outLen + len] = (byte) first;
            outLen += total;
            if (previous >= 0 && next < 4096) {
                prefix[next] = previous;
                suffix[next] = (byte) first;
                length[next] = length[previous] + 1;
                next++;
            }
            previous = code;
            if (next + 1 >= (1 << bitsPerCode) && bitsPerCode < 12) bitsPerCode++;
        }
        return outLen == out.length ? out : java.util.Arrays.copyOf(out, outLen);
    }

    static void undoHorizontalPredictor(ByteBuffer bb, int rows, int width, int samples, int bytesPerSample) {
        int rowBytes = width * samples * bytesPerSample;
        for (int r = 0; r < rows; r++) {
            int base = r * rowBytes;
            for (int i = samples; i < width * samples; i++) {
                int p = base + i * bytesPerSample;
                int q = p - samples * bytesPerSample;
                switch (bytesPerSample) {
                    case 1 -> bb.put(p, (byte) (bb.get(p) + bb.get(q)));
                    case 2 -> bb.putShort(p, (short) (bb.getShort(p) + bb.getShort(q)));
                    case 4 -> bb.putInt(p, bb.getInt(p) + bb.getInt(q));
                    default -> bb.putLong(p, bb.getLong(p) + bb.getLong(q));
                }
            }
        }
    }

    /**
     * Undoes TIFF predictor 3: per row, bytes are byte-wise differenced, and the bytes of each sample
     * are stored as planes, most significant first. Leaves the row as big-endian samples.
     */
    static void undoFloatPredictor(byte[] block, int rows, int width, int samples, int bytesPerSample) {
        int values = width * samples;
        int rowBytes = values * bytesPerSample;
        byte[] tmp = new byte[rowBytes];
        for (int r = 0; r < rows; r++) {
            int base = r * rowBytes;
            for (int i = samples; i < rowBytes; i++) {
                block[base + i] = (byte) (block[base + i] + block[base + i - samples]);
            }
            for (int v = 0; v < values; v++) {
                for (int b = 0; b < bytesPerSample; b++) {
                    tmp[v * bytesPerSample + b] = block[base + b * values + v];
                }
            }
            System.arraycopy(tmp, 0, block, base, rowBytes);
        }
    }

    /** Human-readable summary (for CLI output). */
    public static String describe(Raster raster) {
        GeoRef g = raster.geo();
        if (g == null) return raster.width() + "x" + raster.height() + " (no georeferencing)";
        return String.format(Locale.ROOT, "%dx%d, origin (%.6f, %.6f), pixel %.6g x %.6g %s%s", raster.width(),
                raster.height(), g.originX(), g.originY(), g.pixelX(), g.pixelY(), g.geographic() ? "deg" : "m",
                g.epsg() != 0 ? ", EPSG:" + g.epsg() : "");
    }
}
