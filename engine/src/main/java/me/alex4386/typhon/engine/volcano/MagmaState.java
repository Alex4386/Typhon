package me.alex4386.typhon.engine.volcano;

import me.alex4386.typhon.engine.magma.conduit.ConduitInput;
import me.alex4386.typhon.engine.magma.conduit.ConduitSolution;
import me.alex4386.typhon.engine.math.BlockPos;

/**
 * Read-only view of a volcano's magma system, shared by the subsystems that react to it (seismicity,
 * geothermal activity, lava effusion, ...).
 *
 * <p>Units: 1 block = 1 m; pressures in MPa; temperatures in °C; compositions in weight percent;
 * rates per second.
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

    /**
     * Real depth (m) of the chamber centre below the surface, which may differ from the world
     * position of {@link #chamberCenter()} when the vertical scale is compressed; {@code NaN} if unknown.
     */
    default double physicalDepthM() {
        return Double.NaN;
    }

    /** Chamber volume (m³), {@code NaN} if unknown. */
    /**
     * Magma accommodated by inelastic chamber growth after wall rupture (m³), beyond what the
     * elastic overpressure stores. Inflates the ground like any other volume change.
     */
    default double inelasticVolumeChangeM3() {
        return 0;
    }

    default double volumeM3() {
        return Double.NaN;
    }

    /**
     * Heat the chamber currently loses through its wall into the surrounding rock (W, physical
     * time); {@code NaN} if the magma model does not track its own heat loss. Subsurface models
     * receive this power, so the ground is heated by exactly the heat the chamber gives up.
     */
    default double wallHeatPowerW() {
        return Double.NaN;
    }

    default boolean erupting() {
        return eruptionRate() > 0;
    }

    /** Descriptor of the conduit flow; {@link EruptiveRegime#UNKNOWN} if not modelled. Never drives behaviour. */
    default EruptiveRegime eruptiveRegime() {
        return EruptiveRegime.UNKNOWN;
    }

    /**
     * The steady conduit flow feeding the vent while erupting (physical units), or {@code null} when
     * not erupting or when the magma model does not resolve conduit flow.
     */
    default ConduitSolution conduitFlow() {
        return null;
    }

    /**
     * The flow the conduit would carry if it failed now (the eruption the magma system is heading
     * for); {@code null} if it could not flow or the model does not resolve it.
     */
    default ConduitSolution forecastFlow() {
        return null;
    }

    /**
     * Ambient pressure at the vent (Pa) the conduit flow exits into: the atmosphere plus any water
     * standing over the vent.
     */
    default double ventAmbientPressurePa() {
        return ConduitInput.ATMOSPHERE_PA;
    }

    /**
     * Dissolved H₂O (wt%) still in the magma when it reaches the vent, after conduit outgassing.
     * Controls surface explosivity; defaults to the chamber value when conduit flow is not modelled.
     */
    default double ventWaterWt() {
        return waterWt();
    }
}
