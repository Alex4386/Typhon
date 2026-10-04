package me.alex4386.typhon.engine.lava;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.volcano.VentKind;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.UnitTable;

/**
 * An effusive lava source: lava welling up at a set of surface columns.
 *
 * @param id unique source id (re-adding an id replaces the source)
 * @param cells columns the effusion is spread over evenly; only x/z are used
 * @param rateM3PerS dense-rock-equivalent effusion rate
 * @param temperatureC eruption temperature
 * @param silicaWt SiO₂ content
 * @param waterWt dissolved H₂O remaining at the surface
 * @param unit stratigraphic unit (volcano, eruption) the lava's rock is attributed to; {@link
 *     UnitTable#UNATTRIBUTED} when unknown
 */
public record LavaSource(String id, List<BlockPos> cells, double rateM3PerS, double temperatureC, double silicaWt,
        double waterWt, int unit) {
    public LavaSource {
        Objects.requireNonNull(id, "id");
        cells = List.copyOf(cells);
        if (cells.isEmpty()) throw new IllegalArgumentException("A lava source needs at least one cell");
        if (rateM3PerS < 0) throw new IllegalArgumentException("rate must be >= 0");
    }

    public LavaSource(String id, List<BlockPos> cells, double rateM3PerS, double temperatureC, double silicaWt,
            double waterWt) {
        this(id, cells, rateM3PerS, temperatureC, silicaWt, waterWt, UnitTable.UNATTRIBUTED);
    }

    public static LavaSource at(String id, BlockPos position, double rateM3PerS, double temperatureC, double silicaWt,
            double waterWt) {
        return new LavaSource(id, List.of(position), rateM3PerS, temperatureC, silicaWt, waterWt);
    }

    /** Source covering a vent: the inner half of a crater floor, or every column along a fissure. */
    public static LavaSource atVent(VentSite vent, double rateM3PerS, double temperatureC, double silicaWt,
            double waterWt) {
        List<BlockPos> cells = new ArrayList<>();
        BlockPos c = vent.position();
        if (vent.kind() == VentKind.CRATER) {
            int r = Math.max(0, vent.craterRadius() / 2);
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (dx * dx + dz * dz <= r * r) cells.add(c.offset(dx, 0, dz));
                }
            }
        } else {
            int half = Math.max(0, vent.fissureLength() / 2);
            double cos = StrictMath.cos(vent.fissureAngleRad());
            double sin = StrictMath.sin(vent.fissureAngleRad());
            for (int t = -half; t <= half; t++) {
                BlockPos p = c.offset((int) Math.round(t * cos), 0, (int) Math.round(t * sin));
                if (!cells.contains(p)) cells.add(p);
            }
        }
        return new LavaSource(vent.id(), cells, rateM3PerS, temperatureC, silicaWt, waterWt);
    }

    public LavaSource withId(String id) {
        return new LavaSource(id, cells, rateM3PerS, temperatureC, silicaWt, waterWt, unit);
    }

    public LavaSource withRate(double rateM3PerS) {
        return new LavaSource(id, cells, rateM3PerS, temperatureC, silicaWt, waterWt, unit);
    }

    public LavaSource withUnit(int unit) {
        return new LavaSource(id, cells, rateM3PerS, temperatureC, silicaWt, waterWt, unit);
    }
}
