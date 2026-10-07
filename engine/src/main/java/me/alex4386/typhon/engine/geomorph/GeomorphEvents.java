package me.alex4386.typhon.engine.geomorph;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.HistoricalEvent;

/** Events emitted by {@link Geomorphology}. */
public final class GeomorphEvents {
    private GeomorphEvents() {}

    /** How failed material moved. */
    public enum FailureStyle {
        /** Rockfall, grain flow or slump settling beside its scar (talus). */
        TALUS,
        /** Dry debris avalanche (Voellmy flow). */
        DEBRIS_AVALANCHE,
        /** Saturated failure running as a lahar. */
        DEBRIS_FLOW,
        /** Hot failure (lava dome / hot crater wall) running as a pyroclastic flow. */
        BLOCK_AND_ASH_FLOW
    }

    /**
     * The factor that tipped the slope (output only; never fed back): the one whose removal would
     * have kept the factor of safety ≥ 1 by the largest margin, or {@link #OVERSTEEPENING} when the
     * slope fails even dry, cold, unaltered and static.
     */
    public enum Trigger {
        OVERSTEEPENING,
        ALTERATION,
        THERMAL,
        PORE_PRESSURE,
        SEISMIC
    }

    /**
     * A slope failed.
     *
     * @param position scar centroid (top block)
     * @param volumeM3 failed volume (bulk, before bulking)
     * @param columns failed columns
     * @param dropM largest scar depth (m)
     * @param runoutM horizontal distance the material moved (talus) or 0 for flows (see their front events)
     * @param minFactorOfSafety lowest factor of safety in the cluster
     * @param slipDepthM mean slip-plane depth (m)
     * @param alteration mean alteration of the failed material (0–1)
     * @param saturation mean pore saturation of the failed material (0–1)
     * @param temperatureC mean temperature of the failed material
     * @param unloadingMPa vertical stress drop at the magma chamber from removing the mass (Boussinesq
     *     point load {@code 3Mg/(2π z²)}); 0 without a chamber
     */
    public record SlopeFailure(double time, String volcanoId, Point3 position, double volumeM3, int columns,
            double dropM, double runoutM, FailureStyle style, Trigger trigger, double minFactorOfSafety,
            double slipDepthM, double alteration, double saturation, double temperatureC, double unloadingMPa)
            implements HistoricalEvent {}

    /**
     * Small failures of one step below {@link GeomorphConfig#reportMinVolumeM3} each (grain flows,
     * rockfalls settling as talus), summed: the background ravelling of steep fresh slopes.
     */
    public record MassWasting(double time, String volcanoId, double volumeM3, int columns) implements EngineEvent {}

    /**
     * An explosion excavated (or enlarged) a crater.
     *
     * @param radiusM crater radius from the explosion energy (m)
     * @param depthM crater depth below the rim level (m)
     * @param excavatedM3 bulk volume removed
     */
    public record CraterExcavated(double time, String volcanoId, Point3 center, double radiusM, double depthM,
            double energyJ, double excavatedM3) implements HistoricalEvent {}

    /**
     * The chamber roof subsided as a piston (caldera or pit-crater collapse).
     *
     * @param subsidenceM roof subsidence this step (m)
     * @param totalSubsidenceM cumulative subsidence (m)
     */
    public record CalderaCollapse(double time, String volcanoId, Point3 center, double radiusM, double subsidenceM,
            double totalSubsidenceM, double volumeM3, double underpressureMPa, double criticalUnderpressureMPa)
            implements HistoricalEvent {}
}
