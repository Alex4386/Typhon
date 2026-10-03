package me.alex4386.typhon.engine.magma;

import me.alex4386.typhon.engine.output.EngineEvent;

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

    public record EruptionStarted(long tick, String volcanoId, double overpressureMPa, Cause cause)
            implements EngineEvent {}

    /**
     * @param eruptedVolume dense-rock-equivalent volume erupted (m³)
     * @param durationTicks engine ticks between start and end
     */
    public record EruptionEnded(long tick, String volcanoId, double eruptedVolume, long durationTicks, Cause cause)
            implements EngineEvent {}

    /** Periodic telemetry snapshot of the chamber (for dashboards and logging). */
    public record ChamberSample(
            long tick,
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
            boolean erupting)
            implements EngineEvent {}
}
