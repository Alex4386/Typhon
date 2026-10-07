package me.alex4386.typhon.engine.geothermal;

import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;

/**
 * A zone with elevated gas concentration. Each event replaces the previous one for the same zone
 * and species and is valid for at most {@code durationSeconds}; a concentration of 0 clears the
 * zone. Hosts decide the consequences (nausea, poison, plant die-off, tool corrosion).
 *
 * @param center centre of the hazard on the ground (m)
 * @param radiusM horizontal radius (m)
 * @param concentrationPpm peak concentration in the zone (0 = cleared)
 */
public record GasHazard(
        double time, Point3 center, double radiusM, GasSpecies species, double concentrationPpm, double durationSeconds)
        implements EngineEvent {}
