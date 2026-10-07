package me.alex4386.typhon.engine.geothermal;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;

/** A hydrothermal feature was buried (destroyed) by a lava flow reaching its column. */
public record HydrothermalFeatureBuried(double time, HydrothermalFeature feature, Point3 pos) implements EngineEvent {}
