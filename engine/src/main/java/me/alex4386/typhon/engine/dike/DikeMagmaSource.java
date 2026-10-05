package me.alex4386.typhon.engine.dike;

import java.util.Objects;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.math.BlockPos;

/**
 * What a dike needs from its magma chamber: driving pressure, magma properties and the ability to
 * draw the intruded volume out of the reservoir.
 */
public interface DikeMagmaSource {
    String volcanoId();

    /** Chamber centre in world coordinates (dikes start here). */
    BlockPos chamberCenter();

    double overpressureMPa();

    /** Overpressure at which the chamber roof fails (MPa). */
    double tensileStrengthMPa();

    /** Physical depth of the chamber below the surface (m). */
    double chamberDepthM();

    /** log10 of the magma viscosity (Pa·s). */
    double viscosityLog10();

    double silicaWt();

    /** True while the chamber erupts through its summit conduit (no new dikes then). */
    boolean erupting();

    /** Removes {@code volume} m³ from the chamber; returns the overpressure drop (MPa). */
    double withdraw(double volume);

    /** Magma temperature (°C) at intrusion; basaltic by default. */
    default double temperatureC() {
        return 1150;
    }

    static DikeMagmaSource of(MagmaChamber chamber) {
        Objects.requireNonNull(chamber, "chamber");
        return new DikeMagmaSource() {
            @Override public String volcanoId() { return chamber.config().volcanoId(); }
            @Override public BlockPos chamberCenter() { return chamber.chamberCenter(); }
            @Override public double overpressureMPa() { return chamber.overpressureMPa(); }
            @Override public double tensileStrengthMPa() { return chamber.config().tensileStrengthMPa(); }
            @Override public double chamberDepthM() { return chamber.config().lithostaticDepth(); }
            @Override public double viscosityLog10() { return chamber.viscosityLog10(); }
            @Override public double silicaWt() { return chamber.silicaWt(); }
            @Override public boolean erupting() { return chamber.erupting(); }
            @Override public double withdraw(double volume) { return chamber.withdraw(volume); }
            @Override public double temperatureC() { return chamber.temperatureC(); }
        };
    }
}
