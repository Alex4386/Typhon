package me.alex4386.typhon.engine.world;

import java.util.List;
import java.util.Objects;

/**
 * Where a volcano's own edifice lies in imported columns: a circle around the volcano in which the
 * rock between the basement cake (or {@code baseZ}) and the surface cover is the volcano's material
 * instead of the world's default country rock.
 *
 * <p>The edifice is the part of each column above {@code baseZ} — the pre-volcano surface the cone was
 * built on. Below it (and above the basement cake) lies the world's country rock
 * ({@link WorldSpec#edificeMaterial()}). With {@code baseZ = NaN} the edifice reaches down to the
 * basement cake.
 *
 * @param volcanoId volcano the edifice belongs to (recorded on its stratigraphic unit)
 * @param centerX centre column x
 * @param centerZ centre column z
 * @param radiusColumns radius in columns; {@code +∞} covers the whole world
 * @param baseZ elevation of the edifice's base (m), {@code NaN} = the top of the basement cake
 * @param material material name in {@link MaterialTable}
 */
public record Edifice(String volcanoId, double centerX, double centerZ, double radiusColumns, double baseZ,
        String material) {
    public Edifice {
        Objects.requireNonNull(volcanoId, "volcanoId");
        MaterialTable.require(material);
        if (!(radiusColumns > 0)) throw new IllegalArgumentException("edifice radius must be > 0");
    }

    double distance(int x, int z) {
        double dx = x + 0.5 - centerX;
        double dz = z + 0.5 - centerZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * The edifice a column belongs to: among bounded edifices containing it the one it lies deepest
     * in (smallest distance / radius); otherwise the nearest unbounded one; {@code null} if none.
     */
    static Edifice at(List<Edifice> edifices, int x, int z) {
        Edifice best = null;
        double bestRatio = Double.POSITIVE_INFINITY;
        for (Edifice e : edifices) {
            if (Double.isInfinite(e.radiusColumns())) continue;
            double d = e.distance(x, z);
            if (d > e.radiusColumns()) continue;
            double ratio = d / e.radiusColumns();
            if (ratio < bestRatio) {
                bestRatio = ratio;
                best = e;
            }
        }
        if (best != null) return best;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Edifice e : edifices) {
            if (!Double.isInfinite(e.radiusColumns())) continue;
            double d = e.distance(x, z);
            if (d < bestDistance) {
                bestDistance = d;
                best = e;
            }
        }
        return best;
    }
}
