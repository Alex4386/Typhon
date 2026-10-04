package me.alex4386.typhon.engine.magma;

import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.HistoricalEvent;
import me.alex4386.typhon.engine.volcano.EruptiveRegime;

/** Events emitted by {@link MagmaChamber}. */
public final class MagmaEvents {
    private MagmaEvents() {}

    public enum Cause {
        /** Overpressure exceeded the roof's tensile strength / fell below the end threshold. */
        AUTOMATIC,
        /** Started or stopped by a command. */
        FORCED,
        /** Started because a dike reached the surface and opened a flank vent. */
        DIKE
    }

    public record EruptionStarted(double time, String volcanoId, double overpressureMPa, Cause cause)
            implements HistoricalEvent {}

    /**
     * @param eruptedVolume dense-rock-equivalent volume erupted (m³)
     * @param durationSeconds simulated seconds between start and end
     */
    public record EruptionEnded(double time, String volcanoId, double eruptedVolume, double durationSeconds, Cause cause)
            implements HistoricalEvent {}

    /** Periodic telemetry snapshot of the chamber (for dashboards and logging). */
    public record ChamberSample(
            double time,
            String volcanoId,
            double overpressureMPa,
            double overpressureRateMPaPerSecond,
            double temperatureC,
            double silicaWt,
            double waterWt,
            double exsolvedWaterWt,
            double crystalFraction,
            double viscosityLog10,
            double supplyRate,
            double eruptionRate,
            double eruptedVolume,
            boolean erupting,
            EruptiveRegime regime,
            double ventWaterWt,
            double conduitOpenness)
            implements EngineEvent {}

    /**
     * The way magma leaves the conduit changed (e.g. Plinian column → dome extrusion).
     *
     * @param ascentVelocity mean magma ascent speed in the conduit (m/s)
     * @param ventWaterWt dissolved H₂O reaching the fragmentation level (wt%)
     */
    public record EruptiveRegimeChanged(double time, String volcanoId, EruptiveRegime previous, EruptiveRegime current,
            double ascentVelocity, double ventWaterWt) implements HistoricalEvent {}
}
