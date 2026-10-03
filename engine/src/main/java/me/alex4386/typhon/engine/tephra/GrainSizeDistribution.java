package me.alex4386.typhon.engine.tephra;

import java.util.Arrays;

/**
 * Relative mass weights of airborne (non-ballistic) tephra per {@link GrainClass}, indexed by
 * ordinal. Weights are stored as given and normalised on read ({@link #fractions()}), so saving and
 * restoring the weights is bit-exact.
 */
public record GrainSizeDistribution(double[] weights) {
    /** Coarse, lapilli-dominated (Strombolian / Hawaiian fountaining). */
    public static final GrainSizeDistribution STROMBOLIAN = of(0.55, 0.30, 0.12, 0.03);
    /** Mixed (Vulcanian). */
    public static final GrainSizeDistribution VULCANIAN = of(0.25, 0.30, 0.30, 0.15);
    /** Highly fragmented (Plinian). */
    public static final GrainSizeDistribution PLINIAN = of(0.15, 0.20, 0.35, 0.30);

    public GrainSizeDistribution {
        if (weights.length != GrainClass.COUNT) {
            throw new IllegalArgumentException("Expected " + GrainClass.COUNT + " weights");
        }
        double sum = 0;
        for (double w : weights) {
            if (!(w >= 0)) throw new IllegalArgumentException("Weights must be non-negative");
            sum += w;
        }
        if (!(sum > 0)) throw new IllegalArgumentException("Weights must not all be zero");
        weights = weights.clone();
    }

    public static GrainSizeDistribution of(double lapilli, double coarse, double medium, double fine) {
        return new GrainSizeDistribution(new double[] {lapilli, coarse, medium, fine});
    }

    /** Mass fractions summing to 1. */
    public double[] fractions() {
        double sum = 0;
        for (double w : weights) sum += w;
        double[] f = new double[weights.length];
        for (int i = 0; i < f.length; i++) f[i] = weights[i] / sum;
        return f;
    }

    public double fraction(GrainClass grainClass) {
        return fractions()[grainClass.ordinal()];
    }

    /**
     * Fragmentation-based estimate: more silicic and more volatile-rich magma fragments more finely.
     * Interpolates Strombolian → Vulcanian → Plinian.
     *
     * @param silicaWt SiO₂ wt%
     * @param waterWt dissolved H₂O wt%
     */
    public static GrainSizeDistribution forMagma(double silicaWt, double waterWt) {
        double silicic = clamp01((silicaWt - 50) / 20);
        double wet = clamp01((waterWt - 1) / 4);
        double t = clamp01(0.6 * silicic + 0.4 * wet);
        double[] from = t < 0.5 ? STROMBOLIAN.fractions() : VULCANIAN.fractions();
        double[] to = t < 0.5 ? VULCANIAN.fractions() : PLINIAN.fractions();
        double u = t < 0.5 ? t * 2 : (t - 0.5) * 2;
        double[] mixed = new double[GrainClass.COUNT];
        for (int i = 0; i < mixed.length; i++) mixed[i] = from[i] + (to[i] - from[i]) * u;
        return new GrainSizeDistribution(mixed);
    }

    private static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    @Override
    public double[] weights() {
        return weights.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GrainSizeDistribution other && Arrays.equals(weights, other.weights);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(weights);
    }

    @Override
    public String toString() {
        return "GrainSizeDistribution" + Arrays.toString(fractions());
    }
}
