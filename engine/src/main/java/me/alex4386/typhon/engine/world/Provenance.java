package me.alex4386.typhon.engine.world;

import java.util.List;
import java.util.Objects;

/**
 * Find-or-create lookups for stratigraphic units, so every deposit of one process in one eruption of
 * one volcano shares a unit (a band in cross-sections) without anyone keeping extra state: the
 * {@link UnitTable} is persisted with the world, and these lookups find the unit again after a
 * restore.
 */
public final class Provenance {
    private Provenance() {}

    /**
     * The unit of {@code volcanoId}'s eruption {@code eruptionId} of the given {@code type}, created
     * with the given emplacement data if it does not exist yet.
     */
    public static int unitFor(WorldModel world, String volcanoId, int eruptionId, DepositType type, double timeSeconds,
            double emplacementC, double silicaWt) {
        int found = find(world.units(), volcanoId, eruptionId, type);
        if (found >= 0) return found;
        return world.newUnit(new UnitRecord(volcanoId, eruptionId, type, timeSeconds, emplacementC, silicaWt));
    }

    /**
     * A unit of a different type from the same eruption as {@code unit} (e.g. the hyaloclastite or
     * tube roof of a lava flow), inheriting its emplacement data. Unattributed units map to an
     * unattributed unit of the requested type.
     */
    public static int sibling(WorldModel world, int unit, DepositType type, double timeSeconds) {
        UnitRecord base = world.unit(unit);
        if (base.type() == type) return unit;
        return unitFor(world, base.volcanoId(), base.eruptionId(), type,
                base.volcanoId() == null ? timeSeconds : base.timeSeconds(), base.emplacementC(), base.silicaWt());
    }

    /** Newest unit matching volcano, eruption and type, or {@code -1}. */
    static int find(UnitTable units, String volcanoId, int eruptionId, DepositType type) {
        List<UnitRecord> all = units.all();
        for (int i = all.size() - 1; i >= 1; i--) {
            UnitRecord u = all.get(i);
            if (u.type() == type && u.eruptionId() == eruptionId && Objects.equals(u.volcanoId(), volcanoId)) return i;
        }
        return -1;
    }
}
