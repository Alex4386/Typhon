package me.alex4386.typhon.engine.world;

/**
 * Supplies the stratigraphic unit a subsystem's deposits belong to right now: which volcano laid
 * them down, in which eruption, and how. Subsystems that deposit material (tephra, mass flows,
 * dikes) hold one; the volcano assembly attaches it when the engine is built (it is not persisted —
 * units themselves live in the {@link UnitTable} and are found again by {@link Provenance}).
 */
@FunctionalInterface
public interface UnitSource {
    /**
     * Unit for a deposit of {@code type} emplaced at {@code timeSeconds} at {@code emplacementC}
     * (NaN when not applicable). Repeated calls during one eruption return the same unit.
     */
    int unit(DepositType type, double timeSeconds, double emplacementC);

    /** Every deposit unattributed ({@link UnitTable#UNATTRIBUTED}); the default before wiring. */
    UnitSource UNATTRIBUTED = (type, time, temperature) -> UnitTable.UNATTRIBUTED;
}
