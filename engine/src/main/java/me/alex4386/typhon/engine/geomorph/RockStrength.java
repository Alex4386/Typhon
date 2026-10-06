package me.alex4386.typhon.engine.geomorph;

import me.alex4386.typhon.engine.world.LayerFlags;
import me.alex4386.typhon.engine.world.LayerView;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialClass;
import me.alex4386.typhon.engine.world.MaterialTable;

/**
 * Mohr–Coulomb strength of a stratigraphic layer as a function of what it is and what has happened to
 * it: material, consolidation (loose / fractured / welded), hydrothermal alteration and temperature.
 *
 * <p>Values are rock-<i>mass</i> strengths (the scale of slopes, metres to hundreds of metres), not
 * intact-specimen strengths:
 *
 * <ul>
 *   <li>Loose granular deposits are cohesionless and stand at their angle of repose: scoria and
 *       cinder cones 30–35° (Porter 1972, GSA Bull. 83; Bemis &amp; Ferencz 2017), angular talus and
 *       rock-avalanche debris 35–40°, fine ash ≈ 33°.
 *   <li>Fresh lava rock mass: c ≈ 0.6 MPa, φ ≈ 40°; completely hydrothermally altered rock mass:
 *       c ≈ 0.04 MPa, φ ≈ 22° — the more-than-tenfold loss of Watters, Zimbelman, Bowman &amp;
 *       Crowley (2000, Pure Appl. Geophys. 157, "Rock mass strength assessment and significance to
 *       edifice stability, Mount Rainier and Mount Hood"), with friction angles of altered,
 *       clay-bearing volcanic rock from del Potro &amp; Hürlimann (2008, J. Volcanol. Geotherm. Res. 177)
 *       and Reid, Sisson &amp; Brien (2001, Geology 29). Fracturing (the {@link LayerFlags#FRACTURED}
 *       flag: cooling joints, explosion damage) divides cohesion by four (GSI ≈ 40 vs 60 in
 *       Hoek–Brown terms).
 *   <li>Alteration {@code A ∈ [0, 1]} interpolates geometrically in cohesion (it spans orders of
 *       magnitude) and linearly in friction angle.
 *   <li>Heat: thermal microcracking above ≈ 300 °C halves cohesion by 800 °C (see the review of
 *       Heap &amp; Violay 2021, Bull. Volcanol. 83, "The mechanical behaviour and failure modes of
 *       volcanic rocks"); above the solidus the rock loses its frame as melt appears, strength vanishing
 *       at 50 % melt (the rheological lock-up at ≈ 50 % crystals; Marsh 1981, Lejeune &amp; Richet
 *       1995).
 * </ul>
 */
public final class RockStrength {
    private RockStrength() {}

    /** Effective Mohr–Coulomb parameters and unit weight of a layer. */
    public record Strength(double cohesionPa, double frictionRad, double dryDensity, double porosity) {
        public double tanPhi() {
            return Math.tan(frictionRad);
        }
    }

    /** Friction angle of completely altered (clay-rich) volcanic rock (°). */
    public static final double ALTERED_FRICTION_DEG = 22;
    /** Rock-mass cohesion of completely altered volcanic rock (Pa). */
    public static final double ALTERED_COHESION_PA = 4e4;
    /** Alteration implied by a layer flagged {@link LayerFlags#ALTERED}. */
    public static final double FLAGGED_ALTERATION = 0.7;

    /**
     * Strength of {@code layer} with field alteration {@code alteration} at temperature
     * {@code temperatureC}.
     */
    public static Strength of(LayerView layer, double alteration, double temperatureC) {
        Material m = layer.materialInfo();
        double a = alteration;
        if ((layer.flags() & LayerFlags.ALTERED) != 0) a = Math.max(a, FLAGGED_ALTERATION);
        return of(m, layer.loose() || (m.loose() && layer.welding() <= 0), (layer.flags() & LayerFlags.FRACTURED) != 0,
                layer.welding(), layer.porosity(), a, temperatureC);
    }

    public static Strength of(Material m, boolean loose, boolean fractured, double welding, double porosity,
            double alteration, double temperatureC) {
        double c;
        double phiDeg;
        if (m.materialClass() == MaterialClass.WATER || m.materialClass() == MaterialClass.AIR
                || m.materialClass() == MaterialClass.VOID) {
            return new Strength(0, 0, 0, 1);
        }
        if (loose) {
            phiDeg = looseFrictionDeg(m);
            c = looseCohesionPa(m);
        } else {
            double[] cp = rockMass(m, welding);
            c = cp[0];
            phiDeg = cp[1];
            if (fractured) c *= 0.25;
        }

        double a = clamp01(alteration);
        if (a > 0) {
            if (c > ALTERED_COHESION_PA) {
                c = Math.exp((1 - a) * Math.log(c) + a * Math.log(ALTERED_COHESION_PA));
            } else if (c < 5e3) {
                c += (5e3 - c) * a; // loose deposits altered to clay gain a little binder cohesion
            }
            if (phiDeg > ALTERED_FRICTION_DEG) phiDeg = phiDeg + (ALTERED_FRICTION_DEG - phiDeg) * a;
        }

        if (temperatureC > 300) c *= 1 - 0.5 * clamp01((temperatureC - 300) / 500);
        if (!Double.isNaN(m.solidusC()) && temperatureC > m.solidusC()) {
            double melt = clamp01((temperatureC - m.solidusC()) / (m.liquidusC() - m.solidusC()));
            double frame = Math.max(0, 1 - melt / 0.5);
            c *= frame;
            phiDeg = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(phiDeg)) * frame));
        }
        return new Strength(Math.max(0, c), Math.toRadians(phiDeg), m.densityKgM3(), clamp01(porosity));
    }

    /** Angle of repose of loose deposits of {@code m} (°). */
    static double looseFrictionDeg(Material m) {
        if (m == MaterialTable.SCORIA) return 34;
        if (m == MaterialTable.PUMICE) return 35;
        if (m == MaterialTable.ASH) return 33;
        if (m == MaterialTable.GRAVEL) return 35;
        if (m == MaterialTable.DEBRIS) return 37;
        if (m == MaterialTable.LAHAR_DEPOSIT) return 33;
        if (m == MaterialTable.SOIL) return 30;
        if (m == MaterialTable.CLAY) return 18;
        if (m == MaterialTable.ICE) return 30;
        if (m.materialClass() == MaterialClass.ROCK) return 37; // angular talus of broken rock
        return 33;
    }

    static double looseCohesionPa(Material m) {
        if (m == MaterialTable.SOIL) return 5e3; // root-free silty soil
        if (m == MaterialTable.CLAY) return 1e4;
        if (m == MaterialTable.LAHAR_DEPOSIT) return 2e3;
        if (m == MaterialTable.ASH) return 1e3; // interlocking and suction in fine ash
        return 0;
    }

    /** Rock-mass {@code {cohesion Pa, friction °}} of consolidated {@code m}. */
    static double[] rockMass(Material m, double welding) {
        if (m == MaterialTable.TUFF || m == MaterialTable.SCORIA || m == MaterialTable.PUMICE || m == MaterialTable.ASH) {
            double w = clamp01(welding);
            return new double[] {5e4 + 3.5e5 * w, 34 + 5 * w}; // non-welded to welded ignimbrite / agglutinate
        }
        if (m == MaterialTable.HYALOCLASTITE) return new double[] {1e5, 33};
        if (m == MaterialTable.CLAY) return new double[] {1.5e4, 15}; // smectite-rich, residual-like
        if (m == MaterialTable.SULFUR) return new double[] {1e5, 30};
        if (m == MaterialTable.SEDIMENT) return new double[] {2e5, 32};
        if (m == MaterialTable.LAHAR_DEPOSIT || m == MaterialTable.DEBRIS || m == MaterialTable.GRAVEL) {
            return new double[] {3e4, 35}; // compacted, matrix-supported
        }
        if (m == MaterialTable.SOIL) return new double[] {1e4, 30};
        if (m == MaterialTable.ICE) return new double[] {1e5, 25};
        if (m == MaterialTable.GRANITE || m == MaterialTable.GABBRO) return new double[] {2e6, 45};
        return new double[] {6e5, 40}; // lava rock mass (basalt … rhyolite, obsidian)
    }

    static double clamp01(double v) {
        return v <= 0 ? 0 : v >= 1 ? 1 : v;
    }
}
