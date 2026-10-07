package me.alex4386.typhon.engine.massflow;

import java.util.List;
import java.util.Objects;
import me.alex4386.typhon.engine.math.ColumnIndex;

/**
 * A sustained release of flowing material, e.g. a collapsing eruption column feeding a PDC or a
 * breaching crater lake feeding a lahar.
 *
 * @param id unique id within one flow field
 * @param cells columns the material is released into; the rate is split evenly
 * @param rateM3PerS bulk flow volume per second (real m³)
 * @param temperatureC temperature of the released material
 * @param sedimentFraction sediment volume fraction of the released material (lahars; 0 for PDCs)
 */
public record FlowSource(String id, List<ColumnIndex> cells, double rateM3PerS, double temperatureC,
        double sedimentFraction) {
    public FlowSource {
        Objects.requireNonNull(id, "id");
        cells = List.copyOf(cells);
        if (cells.isEmpty()) throw new IllegalArgumentException("A flow source needs at least one cell");
        if (!(rateM3PerS >= 0)) throw new IllegalArgumentException("rate must be >= 0");
        if (!(sedimentFraction >= 0 && sedimentFraction <= 1)) {
            throw new IllegalArgumentException("sedimentFraction must be in [0, 1]");
        }
    }

    public static FlowSource at(String id, ColumnIndex position, double rateM3PerS, double temperatureC,
            double sedimentFraction) {
        return new FlowSource(id, List.of(position), rateM3PerS, temperatureC, sedimentFraction);
    }

    public FlowSource withRate(double rateM3PerS) {
        return new FlowSource(id, cells, rateM3PerS, temperatureC, sedimentFraction);
    }
}
