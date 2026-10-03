package me.alex4386.typhon.engine.massflow;

import java.util.List;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineEvent;

/** Events emitted by {@link PyroclasticFlows} and {@link Lahars}. */
public final class MassFlowEvents {
    private MassFlowEvents() {}

    /** What set a flow off. */
    public enum Trigger {
        COLUMN_COLLAPSE,
        DOME_COLLAPSE,
        RAIN,
        MELTWATER,
        LAKE_BREAKOUT,
        MANUAL
    }

    /**
     * One flowing column, for hosts that render the flow or apply hazards (burns, drowning, burial).
     *
     * @param pos top block of the ground under the flow
     * @param depthM flow depth (real metres)
     * @param speed flow speed (m/s)
     * @param temperatureC flow temperature (PDCs; ambient for lahars)
     * @param sedimentFraction sediment volume fraction (lahars; 0 for PDCs)
     */
    public record FlowCell(BlockPos pos, double depthM, double speed, double temperatureC, double sedimentFraction) {}

    /** A pyroclastic density current started (instantaneous {@code volumeM3} and/or a sustained rate). */
    public record PdcStarted(long tick, String flowId, Trigger trigger, BlockPos position, double volumeM3,
            double rateM3PerS, double temperatureC) implements EngineEvent {}

    /**
     * Periodic PDC telemetry. {@code cells} lists the flowing columns farthest from the release
     * points first, capped by {@link MassFlowConfig#maxReportedCells}.
     */
    public record PdcFront(long tick, String flowId, BlockPos front, double runoutM, int activeCells, double volumeM3,
            double maxSpeed, double maxTemperatureC, List<FlowCell> cells) implements EngineEvent {
        public PdcFront {
            cells = List.copyOf(cells);
        }
    }

    /** PDC material deposited during one step (ignimbrite). */
    public record PdcDeposit(long tick, String flowId, int cells, double volumeM3, int blocks) implements EngineEvent {}

    /** A PDC running over water flashes it to steam (and loses mass). */
    public record PdcSteam(long tick, String flowId, BlockPos position, double volumeM3) implements EngineEvent {}

    /** A lahar started. */
    public record LaharStarted(long tick, String flowId, Trigger trigger, BlockPos position, double volumeM3,
            double rateM3PerS) implements EngineEvent {}

    /** Periodic lahar telemetry; same layout as {@link PdcFront}. */
    public record LaharFront(long tick, String flowId, BlockPos front, double runoutM, int activeCells, double volumeM3,
            double maxSpeed, double meanSedimentFraction, List<FlowCell> cells) implements EngineEvent {
        public LaharFront {
            cells = List.copyOf(cells);
        }
    }

    /** Lahar material deposited during one step. */
    public record LaharDeposit(long tick, String flowId, int cells, double volumeM3, int blocks) implements EngineEvent {}

    /** The flow reached chunks the host has not sent terrain for; they act as walls until it does. */
    public record TerrainNeeded(long tick, String flowId, List<ChunkCoord> chunks) implements EngineEvent {
        public TerrainNeeded {
            chunks = List.copyOf(chunks);
        }
    }

    public record ChunkCoord(int x, int z) {}
}
