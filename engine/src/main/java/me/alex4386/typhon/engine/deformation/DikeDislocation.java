package me.alex4386.typhon.engine.deformation;

/**
 * Surface displacement of a vertical opening dike, in a 2-D plane-strain approximation extended
 * along strike with a Gaussian taper beyond the dike's ends.
 *
 * <p>For a dike of opening {@code b} from top depth {@code d₁} to bottom depth {@code d₂}, at
 * perpendicular distance {@code x}:
 * <pre>
 *   u_⊥ = (b / π) [atan(x / d₁) − atan(x / d₂)]
 *   u_z = (b / π) [d₂² / (x² + d₂²) − d₁² / (x² + d₁²)]
 * </pre>
 * The walls are pushed apart (±b/2 at a dike that reaches the surface) and the ground rises in two
 * lobes flanking a low above the dike, the characteristic pattern of a shallow intrusion. This is a
 * parametric approximation of the elastic dislocation solution, adequate for monitoring signals and
 * landscape effects, not for inversion: the exact 3-D solution for a rectangular tensile dislocation is
 * Okada (1985), Bull. Seismol. Soc. Am. 75; the Gaussian taper beyond the ends and the depth floors below
 * are approximations (not derived), the floors numerical.
 */
public final class DikeDislocation {
    private DikeDislocation() {}

    /**
     * @param eastM east offset (m) of the observation point from the dike's top-edge midpoint
     * @param northM north offset (m)
     */
    public static Displacement displacement(DikeGeometry dike, double eastM, double northM) {
        Resolved resolved = Resolved.of(dike);
        return resolved == null ? Displacement.ZERO : resolved.displacement(eastM, northM);
    }

    /**
     * A dike with its strike and depths worked out once, for evaluating it at many points; {@code null} for a
     * closed dike (no displacement).
     */
    public static final class Resolved {
        /** Numerical: beyond this exponent the taper's exp underflows to exactly 0. */
        private static final double TAPER_UNDERFLOW = 746;

        private final double sx, sn, nx, nn, half, d1, d2, taperScale, k;

        private Resolved(DikeGeometry dike) {
            // strike unit vector in (east, north): strike is clockwise from +X in x/z, i.e. (cos, sin) in x/z,
            // and north = −z.
            sx = StrictMath.cos(dike.strikeRad());
            sn = -StrictMath.sin(dike.strikeRad());
            nx = -sn; // normal (rotated +90° in east/north)
            nn = sx;
            half = dike.strikeLengthM() / 2;
            d1 = Math.max(0.5, dike.topDepthM());
            d2 = Math.max(d1 + 1, dike.bottomDepthM());
            taperScale = d1 * d1 + 1;
            k = dike.openingM() / Math.PI;
        }

        public static Resolved of(DikeGeometry dike) {
            return dike.openingM() <= 0 ? null : new Resolved(dike);
        }

        private double taper(double along) {
            double beyond = Math.abs(along) - half;
            if (beyond <= 0) return 1;
            double exponent = (beyond * beyond) / taperScale;
            return exponent > TAPER_UNDERFLOW ? 0 : StrictMath.exp(-exponent);
        }

        public Displacement displacement(double eastM, double northM) {
            double along = eastM * sx + northM * sn;
            double across = eastM * nx + northM * nn;
            double kt = k * taper(along);
            double perpendicular = kt * (StrictMath.atan(across / d1) - StrictMath.atan(across / d2));
            return new Displacement(perpendicular * nx, perpendicular * nn, vertical(kt, across));
        }

        /** The vertical component of {@link #displacement} alone. */
        public double uplift(double eastM, double northM) {
            double kt = k * taper(eastM * sx + northM * sn);
            return kt == 0 ? 0 : vertical(kt, eastM * nx + northM * nn);
        }

        private double vertical(double kt, double across) {
            return kt * (d2 * d2 / (across * across + d2 * d2) - d1 * d1 / (across * across + d1 * d1));
        }
    }
}
