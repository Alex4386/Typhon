package me.alex4386.typhon.engine.deformation;

/**
 * A vertical tensile dislocation (dike) as seen by the deformation model.
 *
 * @param centerX x of the dike's top-edge midpoint (m)
 * @param centerZ z of the dike's top-edge midpoint (m)
 * @param strikeRad strike, radians clockwise from +X (same convention as vent fissures)
 * @param strikeLengthM along-strike length (m)
 * @param topDepthM depth of the upper tip below the surface (m, 0 when it reached the surface)
 * @param bottomDepthM depth of the lower edge (m), usually the chamber depth
 * @param openingM opening (thickness) of the dike (m)
 */
public record DikeGeometry(double centerX, double centerZ, double strikeRad, double strikeLengthM, double topDepthM,
        double bottomDepthM, double openingM) {
    public DikeGeometry {
        if (!(strikeLengthM >= 0)) throw new IllegalArgumentException("strikeLengthM must be >= 0");
        if (!(topDepthM >= 0 && bottomDepthM >= topDepthM)) throw new IllegalArgumentException("invalid depths");
        if (!(openingM >= 0)) throw new IllegalArgumentException("openingM must be >= 0");
    }
}
