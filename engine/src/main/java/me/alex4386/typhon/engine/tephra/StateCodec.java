package me.alex4386.typhon.engine.tephra;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;

/** Compact, bit-exact encoding of mostly-empty double arrays (base64 of index/bits pairs). */
final class StateCodec {
    private StateCodec() {}

    static String encodeSparse(double[] values) {
        int count = 0;
        for (double v : values) if (Double.doubleToRawLongBits(v) != 0) count++;
        ByteBuffer buffer = ByteBuffer.allocate(count * 12);
        for (int i = 0; i < values.length; i++) {
            long bits = Double.doubleToRawLongBits(values[i]);
            if (bits != 0) buffer.putInt(i).putLong(bits);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    static void decodeSparse(String encoded, double[] into) {
        Arrays.fill(into, 0);
        ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(encoded));
        while (buffer.remaining() >= 12) {
            int index = buffer.getInt();
            into[index] = Double.longBitsToDouble(buffer.getLong());
        }
    }
}
