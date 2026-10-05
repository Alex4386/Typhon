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
     * <p>The chamber heats the grid cells it overlaps (and the bottom of the columns above it) with
     * at most {@code wallPowerW}: the heat it loses through its wall, from the magma model's own
     * energy budget. The share of that power entering the grid is the share of the chamber's surface
     * above the grid bottom. {@code NaN} leaves the supply unbounded (cells relax to the chamber
     * temperature every step, the classic fixed-temperature body).
     *
     * @param surfaceElevation ground elevation above the chamber (m), the image plane
     * @param wallPowerW heat the chamber gives to its surroundings (W), or {@code NaN}
     */
    record Chamber(double x, double z, double centerElevation, double surfaceElevation, double radiusM,
            double temperatureC, double wallPowerW) {
        /** A fixed-temperature chamber with unbounded heat supply. */
        public Chamber(double x, double z, double centerElevation, double surfaceElevation, double radiusM,
                double temperatureC) {
            this(x, z, centerElevation, surfaceElevation, radiusM, temperatureC, Double.NaN);
        }
    }

    /**
     * Heat carried up a vent's conduit and hydrothermal plumbing (magmatic gas, convecting
     * hydrothermal fluid): {@code powerW} spread with a Gaussian footprint of {@code sigmaM} and
     * uniformly over the top {@code pipeDepthM} of the ground. The fluids are no hotter than
     * {@code temperatureC} (the magma's), so no cell is heated above it; {@code NaN} = no limit.
     */
    record Vent(double x, double z, double powerW, double sigmaM, double pipeDepthM, double temperatureC) {
        /** A vent whose heat is not temperature-limited. */
        public Vent(double x, double z, double powerW, double sigmaM, double pipeDepthM) {
            this(x, z, powerW, sigmaM, pipeDepthM, Double.NaN);
        }
    }

    List<Chamber> chambers();

    List<Vent> vents();
}
