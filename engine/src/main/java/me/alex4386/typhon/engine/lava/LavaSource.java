package me.alex4386.typhon.engine.lava;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import me.alex4386.typhon.engine.math.ColumnIndex;
import me.alex4386.typhon.engine.volcano.VentKind;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.UnitTable;

/**
 * An effusive lava source: lava welling up at a set of surface columns.
 *
 * @param id unique source id (re-adding an id replaces the source)
 * @param cells columns the effusion is spread over evenly
 * @param rateM3PerS dense-rock-equivalent effusion rate
 * @param temperatureC eruption temperature
 * @param silicaWt SiO₂ content
 * @param waterWt dissolved H₂O remaining at the surface
 * @param unit stratigraphic unit (volcano, eruption) the lava's rock is attributed to; {@link
 *     UnitTable#UNATTRIBUTED} when unknown
 */
public record LavaSource(String id, List<ColumnIndex> cells, double rateM3PerS, double temperatureC, double silicaWt,
        double waterWt, int unit) {
    public LavaSource {
        Objects.requireNonNull(id, "id");
        cells = List.copyOf(cells);
        if (cells.isEmpty()) throw new IllegalArgumentException("A lava source needs at least one cell");
        if (rateM3PerS < 0) throw new IllegalArgumentException("rate must be >= 0");
    }

    public LavaSource(String id, List<ColumnIndex> cells, double rateM3PerS, double temperatureC, double silicaWt,
            double waterWt) {
        this(id, cells, rateM3PerS, temperatureC, silicaWt, waterWt, UnitTable.UNATTRIBUTED);
    }

    public static LavaSource at(String id, ColumnIndex position, double rateM3PerS, double temperatureC, double silicaWt,
            double waterWt) {
        return new LavaSource(id, List.of(position), rateM3PerS, temperatureC, silicaWt, waterWt);
    }

    /**
     * Source covering a vent on an {@code l}-metre grid: the columns within half the crater radius of its
     * centre (at least the centre column), or every column along a fissure.
     */
    public static LavaSource atVent(VentSite vent, double l, double rateM3PerS, double temperatureC, double silicaWt,
            double waterWt) {
        List<ColumnIndex> cells = new ArrayList<>();
        ColumnIndex c = vent.position().column(l);
        if (vent.kind() == VentKind.CRATER) {
            int r = (int) Math.floor(vent.craterRadiusM() / 2 / l);
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (dx * dx + dz * dz <= r * r) cells.add(c.offset(dx, dz));
                }
            }
        } else {
            double cos = StrictMath.cos(vent.fissureAngleRad());
            double sin = StrictMath.sin(vent.fissureAngleRad());
            int half = (int) Math.floor(vent.fissureLengthM() / 2 / l);
            for (int t = -half; t <= half; t++) {
                ColumnIndex p = new me.alex4386.typhon.engine.math.Point3(vent.position().x() + t * l * cos, 0,
                        vent.position().z() + t * l * sin).column(l);
                if (!cells.contains(p)) cells.add(p);
            }
        }
        return new LavaSource(vent.id(), cells, rateM3PerS, temperatureC, silicaWt, waterWt);
    }

    /**
     * Source along the stretch of a fissure from {@code fromM} to {@code toM} metres along its strike
     * (measured from its midpoint), on an {@code l}-metre grid: every column the stretch crosses, at least
     * the one under its middle.
     */
    public static LavaSource alongFissure(String id, VentSite fissure, double fromM, double toM, double l,
            double rateM3PerS, double temperatureC, double silicaWt, double waterWt) {
        double cos = StrictMath.cos(fissure.fissureAngleRad());
        double sin = StrictMath.sin(fissure.fissureAngleRad());
        List<ColumnIndex> cells = new ArrayList<>();
        int n = Math.max(1, (int) Math.ceil(Math.abs(toM - fromM) / l));
        for (int k = 0; k < n; k++) {
            double t = fromM + (k + 0.5) * (toM - fromM) / n;
            ColumnIndex p = new me.alex4386.typhon.engine.math.Point3(fissure.position().x() + t * cos, 0,
                    fissure.position().z() + t * sin).column(l);
            if (!cells.contains(p)) cells.add(p);
        }
        return new LavaSource(id, cells, rateM3PerS, temperatureC, silicaWt, waterWt);
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
