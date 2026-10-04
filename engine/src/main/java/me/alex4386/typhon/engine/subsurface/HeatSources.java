package me.alex4386.typhon.engine.subsurface;

import java.util.List;

/**
 * Heat sources a volcano contributes to the subsurface model, re-evaluated every macro step.
 * Horizontal positions are in world-model column coordinates; elevations and lengths are metres.
 */
public interface HeatSources {
    /**
     * A magma chamber: a sphere of radius {@code radiusM} at {@code temperatureC}. Around it the
     * steady conductive halo of a sphere below an isothermal surface (method of images) sets the
     * grid's bottom boundary and the initial state.
     *
     * @param surfaceElevation ground elevation above the chamber (m), the image plane
     */
    record Chamber(double x, double z, double centerElevation, double surfaceElevation, double radiusM,
            double temperatureC) {}

    /**
     * Heat carried up a vent's conduit and hydrothermal plumbing (magmatic gas, convecting
     * hydrothermal fluid): {@code powerW} spread with a Gaussian footprint of {@code sigmaM} and
     * uniformly over the top {@code pipeDepthM} of the ground.
     */
    record Vent(double x, double z, double powerW, double sigmaM, double pipeDepthM) {}

    List<Chamber> chambers();

    List<Vent> vents();
}
