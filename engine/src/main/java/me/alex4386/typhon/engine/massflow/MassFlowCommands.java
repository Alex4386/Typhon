package me.alex4386.typhon.engine.massflow;

import me.alex4386.typhon.engine.command.EngineCommand;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.Trigger;
import me.alex4386.typhon.engine.math.BlockPos;

/** Commands accepted by mass-flow fields; {@code target} is the field's subsystem id. */
public final class MassFlowCommands {
    private MassFlowCommands() {}

    /** Releases {@code volumeM3} over a disc of {@code radius} blocks around {@code center}. */
    public record ReleaseFlow(String target, BlockPos center, int radius, double volumeM3, double temperatureC,
            double sedimentFraction, Trigger trigger) implements EngineCommand {}

    public record StartFlowSource(String target, FlowSource source, Trigger trigger) implements EngineCommand {}

    public record StopFlowSource(String target, String sourceId) implements EngineCommand {}

    public record SetFlowSourceRate(String target, String sourceId, double rateM3PerS) implements EngineCommand {}

    /** Lahars only: rainfall intensity over the whole field (mm/h; 0 stops the rain). */
    public record SetRainfall(String target, double mmPerHour) implements EngineCommand {}
}
