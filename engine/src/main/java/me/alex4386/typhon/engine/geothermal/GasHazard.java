package me.alex4386.typhon.engine.geothermal;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineEvent;

/**
 * A zone with elevated gas concentration. Each event replaces the previous one for the same zone
 * and species and is valid for at most {@code durationSeconds}; a concentration of 0 clears the
 * zone. Hosts decide the consequences (nausea, poison, plant die-off, tool corrosion).
 *
 * @param center centre of the hazard (above the hottest ground), one block above ground
 * @param radius horizontal radius in blocks
 * @param concentrationPpm peak concentration in the zone (0 = cleared)
 */
public record GasHazard(
        long tick, BlockPos center, double radius, GasSpecies species, double concentrationPpm, double durationSeconds)
        implements EngineEvent {}
