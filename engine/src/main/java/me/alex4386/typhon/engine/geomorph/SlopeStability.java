package me.alex4386.typhon.engine.geomorph;

/**
 * Limit-equilibrium slope stability (effective-stress Mohr–Coulomb).
 *
 * <p><b>Infinite slope</b> (shallow slab of vertical thickness {@code z} on a plane parallel to a
 * surface inclined at β; e.g. Duncan, Wright &amp; Brandon 2014, <i>Soil Strength and Slope
 * Stability</i>, §6.3), with pore pressure {@code u = r_u γ z cos²β} on the slip plane and a
 * pseudo-static horizontal seismic coefficient {@code k_h}:
 *
 * <pre>
 *        c' + [γ z (cos²β − k_h sinβ cosβ) − u] tanφ'
 *   FS = ───────────────────────────────────────────
 *               γ z (sinβ cosβ + k_h cos²β)
 * </pre>
 *
 * For a dry cohesionless slope {@code FS = tanφ/tanβ}: it stands at its angle of repose. With
 * slope-parallel seepage and the water table at the surface, {@code r_u = γ_w/γ}.
 *
 * <p><b>Culmann planar wedge</b> for steep slopes of height {@code H} and face angle β (the whole
 * slope sliding on a plane at θ through the toe; Culmann 1866, in Taylor 1948): weight per unit width
 * {@code W = ½ γ H² (cot θ − cot β)}, plane length {@code L = H / sin θ},
 *
 * <pre>
 *   FS(θ) = [c' L + (W cosθ − k_h W sinθ − U) tanφ'] / (W sinθ + k_h W cosθ),   U = r_u W cosθ
 * </pre>
 *
 * minimised over θ. Dry and static the critical height is {@code H_c = 4c/γ · sinβ cosφ / (1 − cos(β − φ))}.
 */
public final class SlopeStability {
    private SlopeStability() {}

    public static final double GRAVITY = 9.81;
    public static final double WATER_DENSITY = 1000;

    /** Infinite-slope factor of safety; {@code +∞} for a flat surface or zero depth. */
    public static double infiniteSlope(double cohesionPa, double tanPhi, double unitWeight, double depth, double beta,
            double ru, double kh) {
        if (!(depth > 0) || !(beta > 0)) return Double.POSITIVE_INFINITY;
        double s = Math.sin(beta);
        double c = Math.cos(beta);
        double gz = unitWeight * depth;
        double driving = gz * (s * c + kh * c * c);
        if (!(driving > 0)) return Double.POSITIVE_INFINITY;
        double normal = gz * (c * c - kh * s * c) - ru * gz * c * c;
        return (cohesionPa + Math.max(0, normal) * tanPhi) / driving;
    }

    /** Minimum Culmann factor of safety over planes θ ∈ (0, β); {@code +∞} for non-positive H or β. */
    public static double culmann(double cohesionPa, double tanPhi, double unitWeight, double height, double beta,
            double ru, double kh) {
        return culmannMinimum(cohesionPa, tanPhi, unitWeight, height, beta, ru, kh)[0];
    }

    /** {@code {FS_min, θ_crit}} of the Culmann wedge. */
    public static double[] culmannMinimum(double cohesionPa, double tanPhi, double unitWeight, double height,
            double beta, double ru, double kh) {
        if (!(height > 0) || !(beta > 0)) return new double[] {Double.POSITIVE_INFINITY, 0};
        double best = Double.POSITIVE_INFINITY;
        double bestTheta = beta / 2;
        int n = 48;
        double cotB = 1 / Math.tan(beta);
        for (int k = 1; k < n; k++) {
            double theta = beta * k / n;
            double w = 0.5 * unitWeight * height * height * (1 / Math.tan(theta) - cotB);
            if (!(w > 0)) continue;
            double st = Math.sin(theta);
            double ct = Math.cos(theta);
            double driving = w * st + kh * w * ct;
            if (!(driving > 0)) continue;
            double normal = w * ct - kh * w * st - ru * w * ct;
            double fs = (cohesionPa * height / st + Math.max(0, normal) * tanPhi) / driving;
            if (fs < best) {
                best = fs;
                bestTheta = theta;
            }
        }
        return new double[] {best, bestTheta};
    }

    /** Culmann's dry, static critical height {@code 4c/γ · sinβ cosφ / (1 − cos(β − φ))} (m). */
    public static double culmannCriticalHeight(double cohesionPa, double phi, double unitWeight, double beta) {
        if (beta <= phi) return Double.POSITIVE_INFINITY;
        return 4 * cohesionPa / unitWeight * Math.sin(beta) * Math.cos(phi) / (1 - Math.cos(beta - phi));
    }

    /**
     * Steepest infinite-slope angle (rad) a slab of depth {@code depth} holds at ({@code FS = 1}),
     * found by bisection; {@code π/2} if it stands vertically.
     */
    public static double criticalAngle(double cohesionPa, double tanPhi, double unitWeight, double depth, double ru,
            double kh) {
        double lo = 0;
        double hi = Math.PI / 2 - 1e-6;
        if (infiniteSlope(cohesionPa, tanPhi, unitWeight, depth, hi, ru, kh) >= 1) return Math.PI / 2;
        for (int i = 0; i < 40; i++) {
            double mid = 0.5 * (lo + hi);
            if (infiniteSlope(cohesionPa, tanPhi, unitWeight, depth, mid, ru, kh) >= 1) lo = mid;
            else hi = mid;
        }
        return lo;
    }
}
