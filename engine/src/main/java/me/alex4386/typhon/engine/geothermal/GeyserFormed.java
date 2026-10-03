package me.alex4386.typhon.engine.geothermal;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineEvent;

/**
 * A vanilla geyser structure was placed: magma block below {@code potentSulfur}, then
 * {@code waterBlocks} (1–4) water sources above. The game itself drives the eruptions.
 */
public record GeyserFormed(long tick, BlockPos potentSulfur, int waterBlocks) implements EngineEvent {}
