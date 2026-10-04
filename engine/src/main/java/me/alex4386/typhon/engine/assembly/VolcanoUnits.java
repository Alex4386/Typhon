package me.alex4386.typhon.engine.assembly;

import java.util.Objects;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.Provenance;
import me.alex4386.typhon.engine.world.UnitSource;
import me.alex4386.typhon.engine.world.WorldModel;

/**
 * Attributes a volcano's deposits to its current eruption: one stratigraphic unit per (eruption,
 * deposit type), created on first use with the eruption's start time and the magma's silica. Between
 * eruptions (e.g. rain-triggered lahars) deposits belong to the most recent eruption; before the
 * first one to eruption 0.
 */
public final class VolcanoUnits implements UnitSource {
    private final WorldModel world;
    private final String volcanoId;
    private final MagmaChamber chamber;

    public VolcanoUnits(WorldModel world, String volcanoId, MagmaChamber chamber) {
        this.world = Objects.requireNonNull(world);
        this.volcanoId = Objects.requireNonNull(volcanoId);
        this.chamber = Objects.requireNonNull(chamber);
    }

    @Override
    public int unit(DepositType type, double timeSeconds, double emplacementC) {
        int eruption = chamber.eruptionCount();
        double start = eruption > 0 ? chamber.eruptionStartTime() : timeSeconds;
        return Provenance.unitFor(world, volcanoId, eruption, type, start, emplacementC, chamber.silicaWt());
    }
}
