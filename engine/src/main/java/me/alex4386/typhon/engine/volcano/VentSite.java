package me.alex4386.typhon.engine.volcano;

import java.util.Objects;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;

/**
 * Geometry of a vent at the surface.
 *
 * @param id stable vent identifier, unique within a volcano
 * @param position the vent's surface point in metres (x east, y up, z south): the crater floor centre or the
 *     fissure midpoint, at the ground elevation there
 * @param craterRadius crater radius in columns (fissure half-width for fissures)
 * @param fissureAngleRad fissure strike, radians clockwise from +X (ignored for craters)
 * @param fissureLength fissure length in columns (ignored for craters)
 */
public record VentSite(String id, Point3 position, VentKind kind, int craterRadius, double fissureAngleRad, int fissureLength,
        boolean emergent) {
    public VentSite {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(kind, "kind");
        if (craterRadius < 0) throw new IllegalArgumentException("craterRadius must be >= 0");
        if (fissureLength < 0) throw new IllegalArgumentException("fissureLength must be >= 0");
    }

    public VentSite(String id, Point3 position, VentKind kind, int craterRadius, double fissureAngleRad, int fissureLength) {
        this(id, position, kind, craterRadius, fissureAngleRad, fissureLength, false);
    }

    /**
     * The vent's ground block on an {@code l}-metre grid: its column, and the block whose top is the ground
     * at the vent ({@code ceil(y/l) − 1}, as {@code WorldSpec#groundBlock}).
     */
    public BlockPos block(double l) {
        return position.surfaceBlock(l);
    }

    /** This vent moved to {@code newPosition} (m). */
    public VentSite at(Point3 newPosition) {
        return new VentSite(id, newPosition, kind, craterRadius, fissureAngleRad, fissureLength, emergent);
    }

    /**
     * A vent that does not exist yet: where the conduit from a user-placed chamber will meet the ground.
     * Nothing is carved for it; it shows once magma first reaches the surface there, and the crater and
     * edifice around it form from the eruption's own deposits and explosions.
     */
    public static VentSite emergent(String id, Point3 position, int radius) {
        return new VentSite(id, position, VentKind.CRATER, radius, 0, 0, true);
    }

    public static VentSite crater(String id, Point3 position, int radius) {
        return new VentSite(id, position, VentKind.CRATER, radius, 0, 0);
    }

    public static VentSite fissure(String id, Point3 position, double angleRad, int length) {
        return new VentSite(id, position, VentKind.FISSURE, 1, angleRad, length);
    }
}
