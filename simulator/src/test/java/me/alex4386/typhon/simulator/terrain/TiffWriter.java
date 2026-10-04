package me.alex4386.typhon.simulator.terrain;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.Deflater;

/** Test helper: writes small single-band (Geo)TIFFs with the layouts and codecs the reader supports. */
final class TiffWriter {
    /**
     * @param bits 16 or 32
     * @param format 1 = unsigned, 2 = signed, 3 = float
     * @param compression 1 none, 5 LZW, 8 Deflate, 32773 PackBits
     * @param tile tile size (0 = strips of {@code rowsPerStrip})
     * @param pixelScale {sx, sy} or null for no georeferencing
     * @param tiepoint {i, j, x, y} raster point (i, j) at model (x, y)
     */
    record Spec(ByteOrder order, int bits, int format, int compression, int predictor, int tile, int rowsPerStrip,
            double[] pixelScale, double[] tiepoint, boolean geographic, int epsg, boolean pixelIsPoint, String nodata) {
        static Spec plain(ByteOrder order, int bits, int format) {
            return new Spec(order, bits, format, 1, 1, 0, 4, null, null, false, 0, false, null);
        }

        Spec compression(int c, int p) {
            return new Spec(order, bits, format, c, p, tile, rowsPerStrip, pixelScale, tiepoint, geographic, epsg,
                    pixelIsPoint, nodata);
        }

        Spec tiled(int size) {
            return new Spec(order, bits, format, compression, predictor, size, rowsPerStrip, pixelScale, tiepoint,
                    geographic, epsg, pixelIsPoint, nodata);
        }

        Spec geo(double[] scale, double[] tie, boolean geographic, int epsg, boolean pixelIsPoint) {
            return new Spec(order, bits, format, compression, predictor, tile, rowsPerStrip, scale, tie, geographic,
                    epsg, pixelIsPoint, nodata);
        }

        Spec nodata(String value) {
            return new Spec(order, bits, format, compression, predictor, tile, rowsPerStrip, pixelScale, tiepoint,
                    geographic, epsg, pixelIsPoint, value);
        }
    }

    private TiffWriter() {}

    static byte[] write(int width, int height, double[] data, Spec spec) {
        int bps = spec.bits() / 8;
        int blockW = spec.tile() > 0 ? spec.tile() : width;
        int blockH = spec.tile() > 0 ? spec.tile() : Math.min(height, spec.rowsPerStrip());
        int across = (width + blockW - 1) / blockW;
        int down = (height + blockH - 1) / blockH;
        List<byte[]> blocks = new ArrayList<>();
        for (int by = 0; by < down; by++) {
            for (int bx = 0; bx < across; bx++) {
                int rows = spec.tile() > 0 ? blockH : Math.min(blockH, height - by * blockH);
                byte[] raw = encodeBlock(data, width, height, bx * blockW, by * blockH, blockW, rows, spec, bps);
                blocks.add(compress(raw, spec.compression()));
            }
        }

        ByteBuffer header = ByteBuffer.allocate(8).order(spec.order());
        header.put(spec.order() == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        header.put(spec.order() == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        header.putShort((short) 42);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(header.array());
        long[] offsets = new long[blocks.size()];
        long[] counts = new long[blocks.size()];
        for (int i = 0; i < blocks.size(); i++) {
            offsets[i] = out.size();
            counts[i] = blocks.get(i).length;
            out.writeBytes(blocks.get(i));
            if (out.size() % 2 == 1) out.write(0);
        }

        TreeMap<Integer, Object[]> tags = new TreeMap<>(); // tag -> {type, values}
        tags.put(256, new Object[] {4, new long[] {width}});
        tags.put(257, new Object[] {4, new long[] {height}});
        tags.put(258, new Object[] {3, new long[] {spec.bits()}});
        tags.put(259, new Object[] {3, new long[] {spec.compression()}});
        tags.put(262, new Object[] {3, new long[] {1}});
        tags.put(277, new Object[] {3, new long[] {1}});
        tags.put(339, new Object[] {3, new long[] {spec.format()}});
        if (spec.predictor() != 1) tags.put(317, new Object[] {3, new long[] {spec.predictor()}});
        if (spec.tile() > 0) {
            tags.put(322, new Object[] {3, new long[] {blockW}});
            tags.put(323, new Object[] {3, new long[] {blockH}});
            tags.put(324, new Object[] {4, offsets});
            tags.put(325, new Object[] {4, counts});
        } else {
            tags.put(273, new Object[] {4, offsets});
            tags.put(278, new Object[] {4, new long[] {blockH}});
            tags.put(279, new Object[] {4, counts});
        }
        if (spec.pixelScale() != null) {
            tags.put(33550, new Object[] {12, new double[] {spec.pixelScale()[0], spec.pixelScale()[1], 0}});
            double[] t = spec.tiepoint();
            tags.put(33922, new Object[] {12, new double[] {t[0], t[1], 0, t[2], t[3], 0}});
            List<Long> keys = new ArrayList<>(List.of(1L, 1L, 0L, 0L));
            addKey(keys, 1024, spec.geographic() ? 2 : 1);
            addKey(keys, 1025, spec.pixelIsPoint() ? 2 : 1);
            if (spec.epsg() != 0) addKey(keys, spec.geographic() ? 2048 : 3072, spec.epsg());
            keys.set(3, (long) ((keys.size() - 4) / 4));
            tags.put(34735, new Object[] {3, keys.stream().mapToLong(Long::longValue).toArray()});
        }
        if (spec.nodata() != null) tags.put(42113, new Object[] {2, spec.nodata()});

        // IFD followed by out-of-line values
        long ifdOffset = out.size();
        int n = tags.size();
        long valuesOffset = ifdOffset + 2 + 12L * n + 4;
        ByteArrayOutputStream values = new ByteArrayOutputStream();
        ByteBuffer ifd = ByteBuffer.allocate(2 + 12 * n + 4).order(spec.order());
        ifd.putShort((short) n);
        Map<Integer, Integer> sizes = new HashMap<>(Map.of(2, 1, 3, 2, 4, 4, 12, 8));
        for (Map.Entry<Integer, Object[]> e : tags.entrySet()) {
            int type = (int) e.getValue()[0];
            byte[] bytes = valueBytes(type, e.getValue()[1], spec.order());
            int count = type == 2 ? bytes.length : bytes.length / sizes.get(type);
            ifd.putShort((short) (int) e.getKey());
            ifd.putShort((short) type);
            ifd.putInt(count);
            if (bytes.length <= 4) {
                byte[] padded = new byte[4];
                System.arraycopy(bytes, 0, padded, 0, bytes.length);
                ifd.put(padded);
            } else {
                ifd.putInt((int) (valuesOffset + values.size()));
                values.writeBytes(bytes);
                if (values.size() % 2 == 1) values.write(0);
            }
        }
        ifd.putInt(0);
        out.writeBytes(ifd.array());
        out.writeBytes(values.toByteArray());
        byte[] file = out.toByteArray();
        ByteBuffer.wrap(file).order(spec.order()).putInt(4, (int) ifdOffset);
        return file;
    }

    private static void addKey(List<Long> keys, int id, long value) {
        keys.add((long) id);
        keys.add(0L);
        keys.add(1L);
        keys.add(value);
    }

    private static byte[] valueBytes(int type, Object v, ByteOrder order) {
        if (type == 2) {
            byte[] s = ((String) v).getBytes(StandardCharsets.US_ASCII);
            byte[] b = new byte[s.length + 1];
            System.arraycopy(s, 0, b, 0, s.length);
            return b;
        }
        if (type == 12) {
            double[] d = (double[]) v;
            ByteBuffer b = ByteBuffer.allocate(8 * d.length).order(order);
            for (double x : d) b.putDouble(x);
            return b.array();
        }
        long[] l = (long[]) v;
        int size = type == 3 ? 2 : 4;
        ByteBuffer b = ByteBuffer.allocate(size * l.length).order(order);
        for (long x : l) {
            if (size == 2) b.putShort((short) x); else b.putInt((int) x);
        }
        return b.array();
    }

    private static byte[] encodeBlock(double[] data, int width, int height, int x0, int y0, int blockW, int rows,
            Spec spec, int bps) {
        boolean floatPredictor = spec.predictor() == 3;
        ByteBuffer bb = ByteBuffer.allocate(blockW * rows * bps)
                .order(floatPredictor ? ByteOrder.BIG_ENDIAN : spec.order());
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < blockW; c++) {
                int x = x0 + c;
                int y = y0 + r;
                double v = (x < width && y < height) ? data[y * width + x] : 0;
                int p = (r * blockW + c) * bps;
                if (spec.format() == 3) {
                    if (bps == 4) bb.putFloat(p, (float) v); else bb.putDouble(p, v);
                } else if (bps == 2) {
                    bb.putShort(p, (short) Math.round(v));
                } else {
                    bb.putInt(p, (int) Math.round(v));
                }
            }
        }
        byte[] raw = bb.array();
        int rowBytes = blockW * bps;
        if (spec.predictor() == 2) {
            for (int r = 0; r < rows; r++) {
                for (int c = blockW - 1; c >= 1; c--) {
                    int p = r * rowBytes + c * bps;
                    int q = p - bps;
                    if (bps == 2) bb.putShort(p, (short) (bb.getShort(p) - bb.getShort(q)));
                    else bb.putInt(p, bb.getInt(p) - bb.getInt(q));
                }
            }
        } else if (floatPredictor) {
            byte[] tmp = new byte[rowBytes];
            for (int r = 0; r < rows; r++) {
                int base = r * rowBytes;
                for (int c = 0; c < blockW; c++) {
                    for (int b = 0; b < bps; b++) tmp[b * blockW + c] = raw[base + c * bps + b];
                }
                for (int i = rowBytes - 1; i >= 1; i--) tmp[i] = (byte) (tmp[i] - tmp[i - 1]);
                System.arraycopy(tmp, 0, raw, base, rowBytes);
            }
        }
        return raw;
    }

    static byte[] compress(byte[] raw, int compression) {
        return switch (compression) {
            case 1 -> raw;
            case 8 -> {
                Deflater d = new Deflater();
                d.setInput(raw);
                d.finish();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                while (!d.finished()) out.write(buf, 0, d.deflate(buf));
                d.end();
                yield out.toByteArray();
            }
            case 5 -> lzw(raw);
            case 32773 -> packBits(raw);
            default -> throw new IllegalArgumentException("compression " + compression);
        };
    }

    /** TIFF LZW encoder (MSB-first, early change). */
    static byte[] lzw(byte[] raw) {
        BitWriter w = new BitWriter();
        Map<Integer, Integer> dict = new HashMap<>();
        int bits = 9;
        int next = 258;
        w.write(256, bits);
        if (raw.length == 0) {
            w.write(257, bits);
            return w.toByteArray();
        }
        int prefix = raw[0] & 0xff;
        for (int i = 1; i < raw.length; i++) {
            int b = raw[i] & 0xff;
            int key = (prefix << 8) | b;
            Integer code = dict.get(key);
            if (code != null) {
                prefix = code;
                continue;
            }
            w.write(prefix, bits);
            dict.put(key, next++);
            if (next >= (1 << bits) && bits < 12) bits++;
            if (next >= 4094) {
                w.write(256, bits);
                dict.clear();
                next = 258;
                bits = 9;
            }
            prefix = b;
        }
        w.write(prefix, bits);
        next++;
        if (next >= (1 << bits) && bits < 12) bits++;
        w.write(257, bits);
        return w.toByteArray();
    }

    static byte[] packBits(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < raw.length) {
            int run = 1;
            while (i + run < raw.length && run < 128 && raw[i + run] == raw[i]) run++;
            if (run >= 3) {
                out.write(1 - run);
                out.write(raw[i]);
                i += run;
            } else {
                int len = Math.min(128, raw.length - i);
                int lit = 0;
                while (lit < len) {
                    if (i + lit + 2 < raw.length && raw[i + lit] == raw[i + lit + 1] && raw[i + lit] == raw[i + lit + 2]) break;
                    lit++;
                }
                if (lit == 0) lit = 1;
                out.write(lit - 1);
                out.write(raw, i, lit);
                i += lit;
            }
        }
        return out.toByteArray();
    }

    static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long buffer;
        private int count;

        void write(int code, int bits) {
            buffer = (buffer << bits) | code;
            count += bits;
            while (count >= 8) {
                out.write((int) (buffer >>> (count - 8)) & 0xff);
                count -= 8;
            }
        }

        byte[] toByteArray() {
            if (count > 0) {
                out.write((int) (buffer << (8 - count)) & 0xff);
                count = 0;
            }
            return out.toByteArray();
        }
    }
}
