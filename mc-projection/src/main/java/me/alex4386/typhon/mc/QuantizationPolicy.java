package me.alex4386.typhon.mc;

/**
 * How a continuous surface elevation becomes whole blocks (plus an optional partial top).
 *
 * <p>Blocks are {@code blockSize} metres tall and block {@code y} spans {@code [y·L, (y+1)·L)}.
 */
public interface QuantizationPolicy {
    /** Block quantisation of a surface: the top full block and the eighths of a partial block on it. */
    record Level(int groundY, int partialEighths) {}

    Level quantize(double surfaceZ, double blockSize);

    /** Whole blocks, a block counted once more than half of it is filled (no partial blocks). */
    QuantizationPolicy HALF_BLOCK = (z, l) -> new Level((int) Math.floor(z / l + 0.5) - 1, 0);

    /**
     * Whole blocks plus a partial top in eighths (shown as layer blocks, e.g. snow-like heights), so a host
     * can show the surface to L/8.
     */
    QuantizationPolicy LAYERS = (z, l) -> {
        double blocks = z / l;
        int full = (int) Math.floor(blocks);
        int eighths = (int) Math.round((blocks - full) * 8);
        if (eighths == 8) {
            full++;
            eighths = 0;
        }
        return new Level(full - 1, eighths);
    };
}
