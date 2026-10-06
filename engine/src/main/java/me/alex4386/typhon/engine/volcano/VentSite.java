package me.alex4386.typhon.engine.volcano;

import java.util.Objects;
import me.alex4386.typhon.engine.math.BlockPos;

/**
 * Geometry of a vent at the surface.
 *
 * @param id stable vent identifier, unique within a volcano
 * @param position centre of the vent (crater floor / fissure midpoint)
 * @param craterRadius crater radius in blocks (fissure half-width for fissures)
 * @param fissureAngleRad fissure strike, radians clockwise from +X (ignored for craters)
 * @param fissureLength fissure length in blocks (ignored for craters)
 */
public record VentSite(String id, BlockPos position, VentKind kind, int craterRadius, double fissureAngleRad, int fissureLength,
        boolean emergent) {
    public VentSite {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(kind, "kind");
        if (craterRadius < 0) throw new IllegalArgumentException("craterRadius must be >= 0");
        if (fissureLength < 0) throw new IllegalArgumentException("fissureLength must be >= 0");
    }

    public VentSite(String id, BlockPos position, VentKind kind, int craterRadius, double fissureAngleRad, int fissureLength) {
        this(id, position, kind, craterRadius, fissureAngleRad, fissureLength, false);
    }

    /**
     * A vent that does not exist yet: where the conduit from a user-placed chamber will meet the ground.
     * Nothing is carved for it; it shows once magma first reaches the surface there, and the crater and
     * edifice around it form from the eruption's own deposits and explosions.
     */
    public static VentSite emergent(String id, BlockPos position, int radius) {
        return new VentSite(id, position, VentKind.CRATER, radius, 0, 0, true);
    }

    public static VentSite crater(String id, BlockPos position, int radius) {
        return new VentSite(id, position, VentKind.CRATER, radius, 0, 0);
    }

    public static VentSite fissure(String id, BlockPos position, double angleRad, int length) {
        return new VentSite(id, position, VentKind.FISSURE, 1, angleRad, length);
    }
}
