package me.alex4386.typhon.engine.seismic;

import me.alex4386.typhon.engine.random.SimRandom;

/**
 * Truncated Gutenberg–Richter magnitude distribution, {@code log10 N(≥M) = a − b M} for
 * {@code M ∈ [mMin, mMax]}.
 */
public final class GutenbergRichter {
    private GutenbergRichter() {}

    /** Samples a magnitude by inverting the truncated exponential CDF. */
    public static double sample(SimRandom random, double bValue, double mMin, double mMax) {
        if (!(bValue > 0)) throw new IllegalArgumentException("b-value must be positive");
        if (!(mMax > mMin)) throw new IllegalArgumentException("mMax must exceed mMin");
        double truncation = 1 - Math.pow(10, -bValue * (mMax - mMin));
        double u = random.nextDouble();
        return mMin - Math.log10(1 - u * truncation) / bValue;
    }

    /**
     * Aki (1965) maximum-likelihood b-value estimate for magnitudes at or above {@code mMin}
     * (continuous, untruncated form).
     */
    public static double estimateBValue(double meanMagnitude, double mMin) {
        return Math.log10(Math.E) / (meanMagnitude - mMin);
    }
}
