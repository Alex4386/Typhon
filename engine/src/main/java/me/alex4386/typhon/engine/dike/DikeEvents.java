package me.alex4386.typhon.engine.dike;

import java.util.List;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.HistoricalEvent;
import me.alex4386.typhon.engine.volcano.VentSite;

/** Events emitted by {@link DikePropagation}. Depths are real metres below the surface. */
public final class DikeEvents {
    private DikeEvents() {}

    public enum StallReason {
        /** Driving pressure (chamber overpressure + buoyancy) fell below the stall threshold. */
        INSUFFICIENT_PRESSURE,
        /** The tip became too slow to outrun freezing: the magma in it solidifies against the wall rock. */
        FROZE,
        /** Stopped by the user ({@link DikeCommands.ArrestDike} or {@link DikeCommands.RemoveDike}). */
        ARRESTED
    }

    public record DikeStarted(double time, String volcanoId, int dikeId, Point3 origin, double overpressureMPa)
            implements HistoricalEvent {}

    /**
     * The dike advanced during the last step.
     *
     * @param hypocenters VT-like fracture events along the newly opened segment; the seismic model or a
     *     host can turn these into a migrating earthquake swarm
     */
    public record DikeAdvanced(double time, String volcanoId, int dikeId, Point3 tip, double depthM,
            double speedMPerS, double openingM, double volumeM3, List<Point3> hypocenters) implements EngineEvent {
        public DikeAdvanced {
            hypocenters = List.copyOf(hypocenters);
        }
    }

    public record DikeStalled(double time, String volcanoId, int dikeId, Point3 tip, double depthM, double volumeM3,
            StallReason reason) implements HistoricalEvent {}

    /** A stalled dike, still molten, is driven on again (renewed chamber pressure or buoyancy). */
    public record DikeResumed(double time, String volcanoId, int dikeId, Point3 tip, double depthM)
            implements HistoricalEvent {}

    /** A stalled dike's sheet solidified: it is an intrusion now and can no longer move. */
    public record DikeSolidified(double time, String volcanoId, int dikeId, Point3 tip, double depthM, double volumeM3)
            implements HistoricalEvent {}

    /** The dike reached the surface: a new fissure vent opens there. */
    public record FissureOpened(double time, String volcanoId, int dikeId, VentSite vent, double volumeM3)
            implements HistoricalEvent {}
}
