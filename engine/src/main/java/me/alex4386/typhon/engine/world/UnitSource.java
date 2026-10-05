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

    /** Every deposit unattributed ({@link UnitTable#UNATTRIBUTED}, a plain fill). */
    UnitSource UNATTRIBUTED = (type, time, temperature) -> UnitTable.UNATTRIBUTED;

    /**
     * Deposits typed by process but without a volcano or eruption (one shared unit per type): the
     * default before a volcano assembly attaches its own source, so a stand-alone field still lays
     * down recognisable (and, for loose types, erodible) layers.
     */
    static UnitSource typed(WorldModel world) {
        return (type, time, temperature) -> Provenance.unitFor(world, null, -1, type, 0, Double.NaN, Double.NaN);
    }
}
