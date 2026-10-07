package me.alex4386.typhon.engine.deformation;

/**
 * Mogi (1958) point pressure source in an elastic half-space.
 *
 * <p>For a source of volume change {@code ΔV} at depth {@code d}, at horizontal distance {@code r}:
 * <pre>
 *   u_z = (1 − ν) ΔV / π · d / (d² + r²)^{3/2}
 *   u_r = (1 − ν) ΔV / π · r / (d² + r²)^{3/2}
 * </pre>
 * so peak uplift above the source is {@code (1 − ν) ΔV / (π d²)}. The volume change of a spherical
 * chamber of radius {@code a} under pressure change {@code ΔP} is {@code ΔV = π a³ ΔP / μ}.
 */
public final class Mogi {
    private Mogi() {}

    /** Cavity volume change (m³) of a spherical chamber of volume {@code chamberVolume} under {@code ΔP}. */
    public static double volumeChange(double chamberVolumeM3, double overpressureMPa, double shearModulusPa) {
        double radius = StrictMath.cbrt(3 * chamberVolumeM3 / (4 * Math.PI));
        return Math.PI * radius * radius * radius * overpressureMPa * 1e6 / shearModulusPa;
    }

    /**
     * Surface displacement at offset ({@code eastM}, {@code northM}) from the point above the source.
     */
    public static Displacement displacement(double volumeChangeM3, double depthM, double eastM, double northM,
            double poissonRatio) {
        double g = geometry(depthM, eastM, northM);
        double c = (1 - poissonRatio) * volumeChangeM3 / Math.PI * g;
        return new Displacement(c * eastM, c * northM, upliftScale(volumeChangeM3, depthM, poissonRatio) * g);
    }

    /** The vertical component of {@link #displacement} alone (the same value). */
    public static double uplift(double volumeChangeM3, double depthM, double eastM, double northM, double poissonRatio) {
        return upliftScale(volumeChangeM3, depthM, poissonRatio) * geometry(depthM, eastM, northM);
    }

    /** {@code (1 − ν) ΔV d / π}: the uplift is this times {@link #geometry}. */
    public static double upliftScale(double volumeChangeM3, double depthM, double poissonRatio) {
        return (1 - poissonRatio) * volumeChangeM3 / Math.PI * depthM;
    }

    /** {@code 1 / (d² + r²)^{3/2}}: where a point is relative to the source, whatever its volume change. */
    public static double geometry(double depthM, double eastM, double northM) {
        double s = depthM * depthM + (eastM * eastM + northM * northM);
        return 1 / (s * StrictMath.sqrt(s));
    }
}
