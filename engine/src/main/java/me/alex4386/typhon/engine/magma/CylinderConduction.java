package me.alex4386.typhon.engine.magma;

/**
 * Heat conducted out of a cylinder of radius {@code a} held at {@code ΔT} above an infinite medium (a conduit
 * or a vent's throat in its wall rock): {@code q = (k ΔT / a) f(κ t / a²)} (Carslaw &amp; Jaeger 1959, §13.5).
 */
public final class CylinderConduction {
    private CylinderConduction() {}

    /** {@code log10 τ} of {@link #FLUX_TABLE}'s entries. */
    private static final double[] TAU_LOG10 = {-3, -2.5229, -2, -1.5229, -1, -0.5229, 0, 0.4771, 1, 1.4771, 2, 2.4771,
            3, 3.4771, 4};
    /**
     * Dimensionless heat flux {@code q a / (k ΔT)} out of a cylinder of radius {@code a} held at {@code ΔT} above
     * an infinite medium, at {@code τ = κ t / a²}: the exact solution (Jaeger &amp; Clarke 1942), tabulated by
     * integrating the radial diffusion equation.
     */
    private static final double[] FLUX_TABLE = {18.34, 10.81, 6.135, 3.739, 2.251, 1.477, 0.9843, 0.7165, 0.5341,
            0.4262, 0.3456, 0.2934, 0.2510, 0.2215, 0.1960};
    private static final double EULER_GAMMA = 0.5772156649;

    /**
     * {@code q a / (k ΔT)} out of a cylinder at {@code τ = κ t / a²}: {@code 1/√(πτ) + 1/2} at early times (a
     * planar wall plus its curvature), {@code 2/(ln 4τ − 2γ) − 2γ/(ln 4τ − 2γ)²} at late times (Carslaw &amp;
     * Jaeger 1959, §13.5), the tabulated exact solution between.
     */
    public static double flux(double tau) {
        double lg = Math.log10(tau);
        if (lg <= TAU_LOG10[0]) return 1 / Math.sqrt(Math.PI * tau) + 0.5;
        int last = TAU_LOG10.length - 1;
        if (lg >= TAU_LOG10[last]) { // the asymptote, matched to the last entry
            return FLUX_TABLE[last] * lateFlux(tau) / lateFlux(Math.pow(10, TAU_LOG10[last]));
        }
        int i = 0;
        while (TAU_LOG10[i + 1] < lg) i++;
        double u = (lg - TAU_LOG10[i]) / (TAU_LOG10[i + 1] - TAU_LOG10[i]);
        // the flux is close to a power law between entries: interpolate its logarithm
        return Math.exp(Math.log(FLUX_TABLE[i]) * (1 - u) + Math.log(FLUX_TABLE[i + 1]) * u);
    }

    private static double lateFlux(double tau) {
        double d = Math.log(4 * tau) - 2 * EULER_GAMMA;
        return 2 / d - 2 * EULER_GAMMA / (d * d);
    }

}
