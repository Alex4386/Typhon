package me.alex4386.plugin.typhon.web.server;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class TyphonSDPCodec {

    /**
     * Compress an SDP string using raw DEFLATE + base64url encoding.
     */
    public static String compress(String sdp) {
        byte[] input = sdp.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true); // raw deflate (no zlib header)
        deflater.setInput(input);
        deflater.finish();

        ByteArrayOutputStream bos = new ByteArrayOutputStream(input.length);
        byte[] buf = new byte[1024];
        while (!deflater.finished()) {
            int count = deflater.deflate(buf);
            bos.write(buf, 0, count);
        }
        deflater.end();

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray());
    }

    /**
     * Decompress a base64url-encoded raw DEFLATE string back to SDP.
     */
    public static String decompress(String encoded) throws Exception {
        byte[] compressed = Base64.getUrlDecoder().decode(encoded);
        Inflater inflater = new Inflater(true); // raw inflate (no zlib header)
        inflater.setInput(compressed);

        ByteArrayOutputStream bos = new ByteArrayOutputStream(compressed.length * 4);
        byte[] buf = new byte[1024];
        while (!inflater.finished()) {
            int count = inflater.inflate(buf);
            if (count == 0 && inflater.needsInput()) break;
            bos.write(buf, 0, count);
        }
        inflater.end();

        return bos.toString(StandardCharsets.UTF_8.name());
    }
}
