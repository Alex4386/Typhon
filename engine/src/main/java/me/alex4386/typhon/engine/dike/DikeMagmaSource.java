package me.alex4386.typhon.engine.dike;

import java.util.Objects;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.math.Point3;

/**
 * What a dike needs from its magma chamber: driving pressure, magma properties and the ability to
 * draw the intruded volume out of the reservoir.
 */
public interface DikeMagmaSource {
    String volcanoId();

    /** Chamber centre in world coordinates (dikes start here). */
    Point3 chamberCenter();

    double overpressureMPa();

    /** Overpressure at which the chamber roof fails (MPa). */
    double tensileStrengthMPa();

    /** Physical depth of the chamber below the surface (m). */
    double chamberDepthM();

    /** log10 of the magma viscosity (Pa·s). */
    double viscosityLog10();

    double silicaWt();

    /**
     * Total H₂O of the melt (wt%, dissolved and exsolved). Whatever exceeds solubility at the pressure along
     * the dike is gas and lightens the magma there. 0 by default (volatile-free, bubble-free magma).
     */
    default double meltWaterWt() {
        return 0;
    }

    /** Total CO₂ of the melt (wt%, dissolved and exsolved); 0 by default. */
    default double meltCo2Wt() {
        return 0;
    }

    /** True while the chamber erupts through its summit conduit (no new dikes then). */
    boolean erupting();

    /** Removes {@code volume} m³ from the chamber; returns the overpressure drop (MPa). */
    double withdraw(double volume);

    /**
     * True if a molten conduit leads from the chamber to an open crater: magma can then reach the surface
     * without a dike (a forced eruption goes that way). False by default (a dike is the only way up).
     */
    default boolean conduitToSurface() {
        return false;
    }

    /** Magma pushed out of the ruptured chamber walls, waiting for a dike (m³). */
    default double ruptureExcessM3() {
        return 0;
    }

    /** Hands the waiting rupture magma to a dike; returns its volume (m³). */
    default double takeRuptureExcess() {
        return 0;
    }

    /**
     * Radius (m) of the chamber (a sphere of its volume): dikes leave from its roof and are fed over at most its
     * diameter. {@code NaN} when unknown (a point source: dikes start at the chamber depth, breadth unbounded by
     * the source).
     */
    default double chamberRadiusM() {
        return Double.NaN;
    }

    /** Magma temperature (°C) at intrusion; basaltic by default. */
    default double temperatureC() {
        return 1150;
    }

    static DikeMagmaSource of(MagmaChamber chamber) {
        Objects.requireNonNull(chamber, "chamber");
        return new DikeMagmaSource() {
            @Override public String volcanoId() { return chamber.config().volcanoId(); }
            @Override public Point3 chamberCenter() { return chamber.chamberCenter(); }
            @Override public double overpressureMPa() { return chamber.overpressureMPa(); }
            @Override public double tensileStrengthMPa() { return chamber.config().tensileStrengthMPa(); }
            @Override public double chamberDepthM() { return chamber.config().lithostaticDepth(); }
            @Override public double viscosityLog10() { return chamber.viscosityLog10(); }
            @Override public double silicaWt() { return chamber.silicaWt(); }
            @Override public double meltWaterWt() { return chamber.meltWaterWt(); }
            @Override public double meltCo2Wt() { return chamber.meltCo2Wt(); }
            @Override public boolean erupting() { return chamber.erupting(); }
            @Override public double withdraw(double volume) { return chamber.withdraw(volume); }
            @Override public double temperatureC() { return chamber.temperatureC(); }
            @Override public double chamberRadiusM() { return StrictMath.cbrt(3 * chamber.volumeM3() / (4 * Math.PI)); }
            @Override public double ruptureExcessM3() { return chamber.ruptureExcessM3(); }
            @Override public boolean conduitToSurface() {
                return chamber.conduitOpenness() > 0 && !chamber.summitBlocked();
            }
            @Override public double takeRuptureExcess() { return chamber.takeRuptureExcess(); }
        };
    }
}
