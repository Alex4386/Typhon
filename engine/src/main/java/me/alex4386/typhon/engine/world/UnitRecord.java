package me.alex4386.typhon.engine.world;

import java.util.Objects;

/**
 * One stratigraphic unit: everything one process laid down in one event (a lava flow, an ash fall,
 * an ignimbrite sheet). Cross-sections colour layers by unit, so successive eruptions show as bands.
 *
 * @param volcanoId volcano that produced it, or {@code null} for non-volcanic units
 * @param eruptionId eruption counter of that volcano, or {@code -1}
 * @param timeSeconds simulation time of emplacement (start)
 * @param emplacementC emplacement temperature, {@code NaN} if not applicable
 * @param silicaWt SiO₂ content of the magma, {@code NaN} if not applicable
 */
public record UnitRecord(String volcanoId, int eruptionId, DepositType type, double timeSeconds, double emplacementC,
        double silicaWt) {
    public UnitRecord {
        Objects.requireNonNull(type, "type");
    }

    public static UnitRecord of(DepositType type) {
        return new UnitRecord(null, -1, type, 0, Double.NaN, Double.NaN);
    }
}
