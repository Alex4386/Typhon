package me.alex4386.typhon.engine.geothermal;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineEvent;

/**
 * A volume with elevated gas concentration, valid for {@code durationSeconds} (until the next
 * update). Hosts decide the consequences (nausea, poison, plant die-off, tool corrosion).
 *
 * @param center centre of the hazard, one block above ground
 * @param radius horizontal radius in blocks
 * @param concentrationPpm concentration at the centre
 */
public record GasHazard(
        long tick, BlockPos center, double radius, GasSpecies species, double concentrationPpm, double durationSeconds)
        implements EngineEvent {}
