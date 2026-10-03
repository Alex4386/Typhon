package me.alex4386.typhon.engine.dike;

import java.util.List;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.volcano.VentSite;

/** Events emitted by {@link DikePropagation}. Depths are real metres below the surface. */
public final class DikeEvents {
    private DikeEvents() {}

    public enum StallReason {
        /** Driving pressure (chamber overpressure + buoyancy) fell below the stall threshold. */
        INSUFFICIENT_PRESSURE,
        /** The tip became too slow: magma froze against the cold wall rock. */
        FROZE
    }

    public record DikeStarted(long tick, String volcanoId, int dikeId, BlockPos origin, double overpressureMPa)
            implements EngineEvent {}

    /**
     * The dike advanced during the last step.
     *
     * @param hypocenters VT-like fracture events along the newly opened segment; the seismic model or a
     *     host can turn these into a migrating earthquake swarm
     */
    public record DikeAdvanced(long tick, String volcanoId, int dikeId, BlockPos tip, double depthM,
            double speedMPerS, double openingM, double volumeM3, List<BlockPos> hypocenters) implements EngineEvent {
        public DikeAdvanced {
            hypocenters = List.copyOf(hypocenters);
        }
    }

    public record DikeStalled(long tick, String volcanoId, int dikeId, BlockPos tip, double depthM, double volumeM3,
            StallReason reason) implements EngineEvent {}

    /** The dike reached the surface: a new fissure vent opens there. */
    public record FissureOpened(long tick, String volcanoId, int dikeId, VentSite vent, double volumeM3)
            implements EngineEvent {}
}
