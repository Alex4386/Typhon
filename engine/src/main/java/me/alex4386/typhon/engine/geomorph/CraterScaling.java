package me.alex4386.typhon.engine.geomorph;

/**
 * Size of an explosion crater from the energy of the explosion.
 *
 * <p>Sato &amp; Taniguchi (1997, GRL 24, "Relationship between crater size and ejecta volume of recent
 * magmatic and phreato-magmatic eruptions: implications for energy partitioning") fitted chemical,
 * nuclear and volcanic explosion craters over 15 orders of magnitude in energy with
 * {@code E = 4.45·10⁶ D^{3.05}} (E in J, D in m), i.e. near cube-root scaling: one tonne of TNT
 * (4.2 GJ) makes a ≈ 9 m crater, a 10¹⁵ J paroxysm one ≈ 560 m across. The relation holds for
 * explosions near the optimal scaled depth {@code d/E^{1/3} ≈ 4·10⁻³ m J^{-1/3}} (Goto, Taniguchi &amp;
 * Kurokawa 2001, JVGR 108); shallower or deeper blasts make smaller craters, so it is an upper bound.
 * Crater depth is a fraction of the diameter: {@code ≈ 0.2–0.3} in blast experiments and young maars
 * (Graettinger et al. 2014, G-cubed 15); we use 0.25.
 *
 * <p>Ejecta thin away from the rim as {@code t ∝ (r/R)^{-3}} (McGetchin, Settle &amp; Head 1973,
 * EPSL 20), mostly within 5 radii.
 */
public final class CraterScaling {
    private CraterScaling() {}

    /** {@code E = ENERGY_COEFFICIENT · D^ENERGY_EXPONENT} (Sato &amp; Taniguchi 1997). */
    public static final double ENERGY_COEFFICIENT = 4.45e6;
    public static final double ENERGY_EXPONENT = 3.05;
    public static final double DEPTH_TO_DIAMETER = 0.25;
    public static final double EJECTA_DECAY_EXPONENT = 3;
    public static final double EJECTA_OUTER_RADII = 5;

    /** Crater diameter (m) of an explosion of {@code energyJ}. */
    public static double diameter(double energyJ) {
        return energyJ > 0 ? Math.pow(energyJ / ENERGY_COEFFICIENT, 1 / ENERGY_EXPONENT) : 0;
    }

    /** Crater depth (m) below the pre-explosion surface. */
    public static double depth(double energyJ) {
        return DEPTH_TO_DIAMETER * diameter(energyJ);
    }

    /** Paraboloid crater floor: depth below the rim level at {@code r} for a crater of radius R, depth d. */
    public static double profileDepth(double r, double radius, double depth) {
        if (r >= radius) return 0;
        double q = r / radius;
        return depth * (1 - q * q);
    }

    /** Relative ejecta thickness at {@code r ≥ R}: {@code (r/R)^{-3}}. */
    public static double ejectaWeight(double r, double radius) {
        return Math.pow(Math.max(1, r / radius), -EJECTA_DECAY_EXPONENT);
    }
}
