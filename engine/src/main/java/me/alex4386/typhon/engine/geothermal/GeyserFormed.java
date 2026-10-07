package me.alex4386.typhon.engine.geothermal;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.HistoricalEvent;

/**
 * A vanilla geyser structure was placed: magma block below {@code potentSulfur}, then
 * {@code waterBlocks} (1–4) water sources above. The game itself drives the eruptions.
 */
public record GeyserFormed(double time, Point3 potentSulfur, int waterBlocks) implements HistoricalEvent {}
