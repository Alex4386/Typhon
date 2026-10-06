package me.alex4386.typhon.engine.geomorph;

/**
 * Peak ground acceleration from an earthquake and the pseudo-static coefficient it imposes on slopes.
 *
 * <p>Attenuation of Joyner &amp; Boore (1981, BSSA 71, "Peak horizontal acceleration and velocity
 * from strong-motion records…"): {@code log₁₀ A[g] = −1.02 + 0.249 M − log₁₀ r − 0.00255 r},
 * {@code r = √(d² + 7.3²)} km with {@code d} the epicentral distance. It was fitted to M 5.0–7.7;
 * applied to small volcanic earthquakes it extrapolates, but keeps the right trends (PGA rising with
 * magnitude and falling with distance).
 *
 * <p>Pseudo-static coefficient {@code k_h = 0.5 · PGA/g} (Hynes-Griffin &amp; Franklin 1984, USACE
 * MP GL-84-13): slopes with a pseudo-static factor of safety above 1 at this coefficient suffer only
 * small (Newmark) displacements.
 */
public final class GroundMotion {
    private GroundMotion() {}

    /** Peak horizontal ground acceleration (g) at epicentral distance {@code distanceKm}. */
    public static double pga(double magnitude, double distanceKm) {
        double r = Math.sqrt(distanceKm * distanceKm + 7.3 * 7.3);
        return Math.pow(10, -1.02 + 0.249 * magnitude - Math.log10(r) - 0.00255 * r);
    }

    /** Pseudo-static horizontal coefficient for a PGA (g). */
    public static double pseudoStatic(double pgaG) {
        return 0.5 * pgaG;
    }

    /** Epicentral distance (km) at which PGA falls to {@code pgaG}, 0 if it never reaches it. */
    public static double radiusKm(double magnitude, double pgaG) {
        if (pga(magnitude, 0) < pgaG) return 0;
        double lo = 0;
        double hi = 1000;
        for (int i = 0; i < 60; i++) {
            double mid = 0.5 * (lo + hi);
            if (pga(magnitude, mid) >= pgaG) lo = mid;
            else hi = mid;
        }
        return lo;
    }
}
