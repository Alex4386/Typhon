package me.alex4386.typhon.engine.magma;

/**
 * A discrete gas-driven explosion at the top of the conduit, produced by the chamber's conduit model
 * and turned into ballistics, ash and an explosion quake by the surface coupling.
 *
 * @param time simulation time (s) at which the burst happened
 * @param kind mechanism
 * @param gasMassKg gas released (kg)
 * @param ejectaMassKg pyroclasts ejected (kg, real)
 * @param overpressureMPa gas overpressure driving the burst
 * @param temperatureC magma temperature
 * @param silicaWt SiO₂ of the ejecta
 * @param durationSeconds physical duration of the burst
 */
public record ConduitBurst(
        double time,
        Kind kind,
        double gasMassKg,
        double ejectaMassKg,
        double overpressureMPa,
        double temperatureC,
        double silicaWt,
        double durationSeconds) {

    public enum Kind {
        /** A segregated gas slug bursting at the free surface of fluid magma. */
        SLUG,
        /** Failure of a stiff plug over gas accumulated beneath it. */
        PLUG
    }

    /** Gas mass fraction of the erupted mixture. */
    public double gasMassFraction() {
        return gasMassKg / (gasMassKg + ejectaMassKg);
    }
}
