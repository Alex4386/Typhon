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
        if (dike.openingM() <= 0) return Displacement.ZERO;
        // strike unit vector in (east, north): strike is clockwise from +X in x/z, i.e. (cos, sin) in x/z,
        // and north = −z.
        double sx = StrictMath.cos(dike.strikeRad());
        double sn = -StrictMath.sin(dike.strikeRad());
        double nx = -sn; // normal (rotated +90° in east/north)
        double nn = sx;

        double along = eastM * sx + northM * sn;
        double across = eastM * nx + northM * nn;

        double half = dike.strikeLengthM() / 2;
        double d1 = Math.max(0.5, dike.topDepthM());
        double d2 = Math.max(d1 + 1, dike.bottomDepthM());
        double beyond = Math.abs(along) - half;
        double taper = beyond <= 0 ? 1 : StrictMath.exp(-(beyond * beyond) / (d1 * d1 + 1));

        double k = dike.openingM() / Math.PI * taper;
        double perpendicular = k * (StrictMath.atan(across / d1) - StrictMath.atan(across / d2));
        double up = k * (d2 * d2 / (across * across + d2 * d2) - d1 * d1 / (across * across + d1 * d1));
        return new Displacement(perpendicular * nx, perpendicular * nn, up);
    }
}
