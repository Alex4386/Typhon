package me.alex4386.typhon.engine.volcano;

import java.util.Objects;
import me.alex4386.typhon.engine.math.Point3;

/**
 * Geometry of a vent at the surface, in metres.
 *
 * @param id stable vent identifier, unique within a volcano
 * @param position centre of the vent (crater floor / fissure midpoint)
 * @param craterRadiusM crater radius (m; fissure half-width for fissures)
 * @param fissureAngleRad fissure strike, radians clockwise from +x (ignored for craters)
 * @param fissureLengthM fissure length (m; ignored for craters)
 */
public record VentSite(String id, Point3 position, VentKind kind, double craterRadiusM, double fissureAngleRad,
        double fissureLengthM) {
    public VentSite {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(kind, "kind");
        if (!(craterRadiusM >= 0)) throw new IllegalArgumentException("craterRadiusM must be >= 0");
        if (!(fissureLengthM >= 0)) throw new IllegalArgumentException("fissureLengthM must be >= 0");
    }

    public static VentSite crater(String id, Point3 position, double radiusM) {
        return new VentSite(id, position, VentKind.CRATER, radiusM, 0, 0);
    }

    public static VentSite fissure(String id, Point3 position, double angleRad, double lengthM, double halfWidthM) {
        return new VentSite(id, position, VentKind.FISSURE, halfWidthM, angleRad, lengthM);
    }

    public VentSite withPosition(Point3 p) {
        return new VentSite(id, p, kind, craterRadiusM, fissureAngleRad, fissureLengthM);
    }
}
