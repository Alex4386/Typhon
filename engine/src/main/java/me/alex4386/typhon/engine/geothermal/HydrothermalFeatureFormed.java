package me.alex4386.typhon.engine.geothermal;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineEvent;

/** A hydrothermal feature appeared at {@code pos} (its surface block). */
public record HydrothermalFeatureFormed(long tick, HydrothermalFeature feature, BlockPos pos) implements EngineEvent {}
