package me.alex4386.typhon.engine.geothermal;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineEvent;

/**
 * A fumarole is venting this step. Hosts render steam/gas plumes scaled by {@code intensity}.
 *
 * @param pos first block above the vent opening
 * @param intensity 0..1
 */
public record FumaroleActivity(double time, BlockPos pos, double intensity, GasComposition gas) implements EngineEvent {}
