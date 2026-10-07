package me.alex4386.typhon.engine.geothermal;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;

/** A hydrothermal feature appeared at {@code pos} (its surface block). */
public record HydrothermalFeatureFormed(double time, HydrothermalFeature feature, Point3 pos) implements EngineEvent {}
