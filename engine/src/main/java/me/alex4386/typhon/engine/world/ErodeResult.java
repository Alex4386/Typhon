package me.alex4386.typhon.engine.world;

import java.util.Map;

/**
 * What an erosion (or carve) removed from a column.
 *
 * @param removedM total thickness removed (m), voids included
 * @param byMaterial thickness removed per solid material id (m), voids excluded — multiply by the
 *     column area for volume
 */
public record ErodeResult(double removedM, Map<Short, Double> byMaterial) {
    public static final ErodeResult NONE = new ErodeResult(0, Map.of());

    public ErodeResult {
        byMaterial = Map.copyOf(byMaterial);
    }

    public double solidRemovedM() {
        double sum = 0;
        for (double v : byMaterial.values()) sum += v;
        return sum;
    }
}
