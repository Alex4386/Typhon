package me.alex4386.typhon.server.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Value codecs and binary frame layouts of protocol v1 (docs/protocol.md §5–6). Byte-for-byte
 * compatible with {@code visualizer/src/protocol/frames.ts}.
 */
public final class Codecs {
    public static final int FRAME_TILE = 1;
    public static final int FRAME_SECTION = 2;
    public static final int FRAME_VERSION = 1;
    public static final int FLAG_ZLIB = 1;
    public static final int TILE_HEADER_BYTES = 44;
    public static final int SECTION_HEADER_BYTES = 28;
    public static final double DEFAULT_TMAX = 1300;

    // Codec ids (§5.3)
    public static final int ELEVATION_U16_CM_DELTA = 1;
    public static final int DEPTH_U16_MM_SPARSE = 2;
    public static final int DEPTH_U16_MM_DENSE = 3;
    public static final int TEMPERATURE_U8_LOG = 4;
    public static final int U16_RAW = 5;
    public static final int F32_RAW = 6;
    public static final int U8_LINEAR = 7;

    private static final ThreadLocal<Deflater> DEFLATER = ThreadLocal.withInitial(() -> new Deflater(6));

    private Codecs() {}

    /** An encoded (uncompressed) payload plus the codec parameters that go into the frame header. */
    public record Payload(int codec, float param0, float param1, byte[] bytes) {}

    public static int tempToU8(double t, double tmax) {
        double c = Math.min(Math.max(t, 0), tmax);
        return (int) Math.round(255 * Math.log1p(c) / Math.log1p(tmax));
    }

    public static double u8ToTemp(int v, double tmax) {
        return Math.exp(v / 255.0 * Math.log1p(tmax)) - 1;
    }

    public static int unitToU8(double v) {
        return (int) Math.round(Math.min(1, Math.max(0, v)) * 255);
    }

    /**
     * Encodes {@code values} with {@code codec}. {@code a}/{@code b} are codec options: Tmax for the
     * temperature codec, min/max for {@link #U8_LINEAR}.
     */
    public static Payload encode(float[] values, int codec, double a, double b) {
        int n = values.length;
        ByteBuffer out;
        switch (codec) {
            case ELEVATION_U16_CM_DELTA -> {
                // The header stores param0 as f32, so quantise against the f32 minimum the client sees.
                float min = Float.POSITIVE_INFINITY;
                for (float v : values) if (v < min) min = v;
                if (!Float.isFinite(min)) min = 0;
                out = buffer(n * 2);
                int prev = 0;
                for (float v : values) {
                    int cm = (int) Math.min(65535, Math.max(0, Math.round((v - (double) min) * 100)));
                    out.putShort((short) ((cm - prev) & 0xffff));
                    prev = cm;
                }
                return new Payload(codec, min, 0, out.array());
            }
            case DEPTH_U16_MM_SPARSE -> {
                if (n > 65536) throw new IllegalArgumentException("sparse codec needs n <= 65536");
                int count = 0;
                for (float v : values) if (v > 0.0005) count++;
                out = buffer(4 + count * 4);
                out.putInt(count);
                for (int i = 0; i < n; i++) {
                    if (values[i] > 0.0005) {
                        out.putShort((short) i);
                        out.putShort((short) Math.min(65535, Math.round(values[i] * 1000.0)));
                    }
                }
                return new Payload(codec, 0, 0, out.array());
            }
            case DEPTH_U16_MM_DENSE -> {
                out = buffer(n * 2);
                for (float v : values) out.putShort((short) Math.min(65535, Math.max(0, Math.round(v * 1000.0))));
                return new Payload(codec, 0, 0, out.array());
            }
            case TEMPERATURE_U8_LOG -> {
                double tmax = a > 0 ? a : DEFAULT_TMAX;
                byte[] bytes = new byte[n];
                for (int i = 0; i < n; i++) bytes[i] = (byte) tempToU8(values[i], tmax);
                return new Payload(codec, (float) tmax, 0, bytes);
            }
            case U16_RAW -> {
                out = buffer(n * 2);
                for (float v : values) out.putShort((short) (((int) v) & 0xffff));
                return new Payload(codec, 0, 0, out.array());
            }
            case F32_RAW -> {
                out = buffer(n * 4);
                for (float v : values) out.putFloat(v);
                return new Payload(codec, 0, 0, out.array());
            }
            case U8_LINEAR -> {
                double range = b - a == 0 ? 1 : b - a;
                byte[] bytes = new byte[n];
                for (int i = 0; i < n; i++) {
                    double t = (values[i] - a) / range;
                    bytes[i] = (byte) Math.round(Math.min(1, Math.max(0, t)) * 255);
                }
                return new Payload(codec, (float) a, (float) b, bytes);
            }
            default -> throw new IllegalArgumentException("Unknown codec " + codec);
        }
    }

    /** Decodes an uncompressed payload into {@code n} values (inverse of {@link #encode}). */
    public static float[] decode(byte[] bytes, int codec, int n, float param0, float param1) {
        float[] out = new float[n];
        ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        switch (codec) {
            case ELEVATION_U16_CM_DELTA -> {
                int cm = 0;
                for (int i = 0; i < n; i++) {
                    cm = (cm + Short.toUnsignedInt(in.getShort())) & 0xffff;
                    out[i] = (float) (param0 + cm / 100.0);
                }
            }
            case DEPTH_U16_MM_SPARSE -> {
                int count = in.getInt();
                for (int k = 0; k < count; k++) {
                    int i = Short.toUnsignedInt(in.getShort());
                    int mm = Short.toUnsignedInt(in.getShort());
                    if (i < n) out[i] = mm / 1000f;
                }
            }
            case DEPTH_U16_MM_DENSE -> {
                for (int i = 0; i < n; i++) out[i] = Short.toUnsignedInt(in.getShort()) / 1000f;
            }
            case TEMPERATURE_U8_LOG -> {
                double tmax = param0 > 0 ? param0 : DEFAULT_TMAX;
                for (int i = 0; i < n; i++) out[i] = (float) u8ToTemp(Byte.toUnsignedInt(bytes[i]), tmax);
            }
            case U16_RAW -> {
                for (int i = 0; i < n; i++) out[i] = Short.toUnsignedInt(in.getShort());
            }
            case F32_RAW -> {
                for (int i = 0; i < n; i++) out[i] = in.getFloat();
            }
            case U8_LINEAR -> {
                for (int i = 0; i < n; i++) out[i] = param0 + Byte.toUnsignedInt(bytes[i]) / 255f * (param1 - param0);
            }
            default -> throw new IllegalArgumentException("Unknown codec " + codec);
        }
        return out;
    }

    // ── Tile frames (§5.1) ──

    /** A complete tile frame (header + zlib payload). */
    public static byte[] tileFrame(int field, Payload payload, int tileX, int tileY, long version, int width,
            int height, double time) {
        return tileFrame(field, payload, tileX, tileY, version, width, height, time, 0);
    }

    /**
     * A tile frame of pyramid level {@code level} (§5.5): stored as an i16 in the header's bytes 2–3,
     * which are 0 — the core's columns — in every frame of protocol 1 without levels.
     */
    public static byte[] tileFrame(int field, Payload payload, int tileX, int tileY, long version, int width,
            int height, double time, int level) {
        byte[] compressed = zlib(payload.bytes());
        ByteBuffer out = buffer(TILE_HEADER_BYTES + compressed.length);
        out.put((byte) FRAME_TILE);
        out.put((byte) FRAME_VERSION);
        out.putShort((short) level);
        out.putShort((short) field);
        out.put((byte) payload.codec());
        out.put((byte) FLAG_ZLIB);
        out.putInt(tileX);
        out.putInt(tileY);
        out.putInt((int) version);
        out.putShort((short) width);
        out.putShort((short) height);
        out.putFloat(payload.param0());
        out.putFloat(payload.param1());
        out.putDouble(time);
        out.putInt(compressed.length);
        out.put(compressed);
        return out.array();
    }

    /** Parsed tile frame (tests and diagnostics). */
    public record TileFrame(int field, int codec, int tileX, int tileY, long version, int width, int height,
            double time, float[] values, int level) {}

    public static TileFrame decodeTileFrame(byte[] frame) {
        ByteBuffer in = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        if (in.get(0) != FRAME_TILE) throw new IllegalArgumentException("not a tile frame");
        if (in.get(1) != FRAME_VERSION) throw new IllegalArgumentException("unsupported frame version");
        int field = Short.toUnsignedInt(in.getShort(4));
        int codec = Byte.toUnsignedInt(in.get(6));
        int flags = Byte.toUnsignedInt(in.get(7));
        int tx = in.getInt(8);
        int ty = in.getInt(12);
        long version = Integer.toUnsignedLong(in.getInt(16));
        int width = Short.toUnsignedInt(in.getShort(20));
        int height = Short.toUnsignedInt(in.getShort(22));
        float p0 = in.getFloat(24);
        float p1 = in.getFloat(28);
        double time = in.getDouble(32);
        int len = in.getInt(40);
        byte[] payload = new byte[len];
        System.arraycopy(frame, TILE_HEADER_BYTES, payload, 0, len);
        if ((flags & FLAG_ZLIB) != 0) payload = unzlib(payload);
        return new TileFrame(field, codec, tx, ty, version, width, height, time,
                decode(payload, codec, width * height, p0, p1), in.getShort(2));
    }

    // ── Section frames (§6) ──

    /** Section body arrays, row 0 = bottom, pixel {@code k·nu + i}. */
    public record SectionBody(String metaJson, float[] surfaceZ, float[] waterTableZ, byte[] material, int[] unit,
            float[] temperatureC, float[] saturation, float[] steam, byte[] flags) {}

    public static byte[] sectionFrame(long requestId, int nu, int nz, double time, SectionBody s) {
        byte[] meta = s.metaJson().getBytes(StandardCharsets.UTF_8);
        int px = nu * nz;
        ByteBuffer body = buffer(4 + meta.length + nu * 8 + px * 7);
        body.putInt(meta.length);
        body.put(meta);
        for (int i = 0; i < nu; i++) body.putFloat(s.surfaceZ()[i]);
        for (int i = 0; i < nu; i++) body.putFloat(s.waterTableZ()[i]);
        body.put(s.material(), 0, px);
        for (int p = 0; p < px; p++) body.putShort((short) (s.unit()[p] & 0xffff));
        for (int p = 0; p < px; p++) body.put((byte) tempToU8(s.temperatureC()[p], DEFAULT_TMAX));
        for (int p = 0; p < px; p++) body.put((byte) unitToU8(s.saturation()[p]));
        for (int p = 0; p < px; p++) body.put((byte) unitToU8(s.steam()[p]));
        body.put(s.flags(), 0, px);
        byte[] compressed = zlib(body.array());
        ByteBuffer out = buffer(SECTION_HEADER_BYTES + compressed.length);
        out.put((byte) FRAME_SECTION);
        out.put((byte) FRAME_VERSION);
        out.putShort((short) 0);
        out.putInt((int) requestId);
        out.putShort((short) nu);
        out.putShort((short) nz);
        out.put((byte) FLAG_ZLIB);
        out.put(new byte[3]);
        out.putDouble(time);
        out.putInt(compressed.length);
        out.put(compressed);
        return out.array();
    }

    /** Parsed section frame (tests). */
    public record SectionFrame(long requestId, int nu, int nz, double time, String metaJson, float[] surfaceZ,
            float[] waterTableZ, byte[] material, int[] unit, byte[] temperatureU8, byte[] saturationU8,
            byte[] steamU8, byte[] flags) {}

    public static SectionFrame decodeSectionFrame(byte[] frame) {
        ByteBuffer h = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
        if (h.get(0) != FRAME_SECTION) throw new IllegalArgumentException("not a section frame");
        long requestId = Integer.toUnsignedLong(h.getInt(4));
        int nu = Short.toUnsignedInt(h.getShort(8));
        int nz = Short.toUnsignedInt(h.getShort(10));
        int flags = Byte.toUnsignedInt(h.get(12));
        double time = h.getDouble(16);
        int len = h.getInt(24);
        byte[] body = new byte[len];
        System.arraycopy(frame, SECTION_HEADER_BYTES, body, 0, len);
        if ((flags & FLAG_ZLIB) != 0) body = unzlib(body);
        ByteBuffer in = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
        int metaLen = in.getInt();
        byte[] meta = new byte[metaLen];
        in.get(meta);
        int px = nu * nz;
        float[] surface = new float[nu];
        float[] water = new float[nu];
        for (int i = 0; i < nu; i++) surface[i] = in.getFloat();
        for (int i = 0; i < nu; i++) water[i] = in.getFloat();
        byte[] material = new byte[px];
        in.get(material);
        int[] unit = new int[px];
        for (int p = 0; p < px; p++) unit[p] = Short.toUnsignedInt(in.getShort());
        byte[] t = new byte[px];
        byte[] sat = new byte[px];
        byte[] steam = new byte[px];
        byte[] fl = new byte[px];
        in.get(t);
        in.get(sat);
        in.get(steam);
        in.get(fl);
        return new SectionFrame(requestId, nu, nz, time, new String(meta, StandardCharsets.UTF_8), surface, water,
                material, unit, t, sat, steam, fl);
    }

    // ── zlib ──

    public static byte[] zlib(byte[] data) {
        Deflater d = DEFLATER.get();
        d.reset();
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 4));
        byte[] buf = new byte[8192];
        while (!d.finished()) {
            int n = d.deflate(buf);
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    public static byte[] unzlib(byte[] data) {
        Inflater inf = new Inflater();
        try {
            inf.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 4);
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("bad zlib data", e);
        } finally {
            inf.end();
        }
    }

    private static ByteBuffer buffer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }
}
