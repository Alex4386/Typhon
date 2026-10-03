package me.alex4386.typhon.engine.volcano;

import me.alex4386.typhon.engine.math.BlockPos;

/**
 * Read-only view of a volcano's magma system, shared by the subsystems that react to it (seismicity,
 * geothermal activity, lava effusion, ...).
 *
 * <p>Units: 1 block = 1 m; pressures in MPa; temperatures in °C; compositions in weight percent;
 * rates per simulated second.
 */
public interface MagmaState {
    /** Centre of the magma chamber. */
    BlockPos chamberCenter();

    /** Pressure in excess of lithostatic; eruptions/dikes start when it exceeds rock strength. */
    double overpressureMPa();

    /** Rate of change of overpressure (MPa/s); drives inflation and VT seismicity. */
    double overpressureRateMPaPerSecond();

    double temperatureC();

    /** SiO₂ content (wt%): ~48 basalt, ~57 andesite, ~65 dacite, ~72 rhyolite. */
    double silicaWt();

    /** Dissolved H₂O (wt%); controls explosivity. */
    double waterWt();

    /** Crystal volume fraction in [0, 1); raises effective viscosity. */
    double crystalFraction();

    /** Current dense-rock-equivalent eruption rate (m³/s); 0 when not erupting. */
    double eruptionRate();

    default boolean erupting() {
        return eruptionRate() > 0;
    }

    /** How the magma currently leaves the conduit; {@link EruptiveRegime#UNKNOWN} if not modelled. */
    default EruptiveRegime eruptiveRegime() {
        return EruptiveRegime.UNKNOWN;
    }

    /**
     * Dissolved H₂O (wt%) still in the magma when it reaches the vent, after conduit outgassing.
     * Controls surface explosivity; defaults to the chamber value when conduit flow is not modelled.
     */
    default double ventWaterWt() {
        return waterWt();
    }
}
