package me.alex4386.typhon.engine.lava;

import me.alex4386.typhon.engine.command.EngineCommand;

/** Commands accepted by {@link LavaFlow}. */
public final class LavaCommands {
    private LavaCommands() {}

    /** Starts (or replaces) an effusive source. */
    public record StartEffusion(LavaSource source) implements EngineCommand {}

    /** Stops a source; lava already erupted keeps flowing and cooling. */
    public record StopEffusion(String sourceId) implements EngineCommand {}

    /** Changes the effusion rate of a running source. */
    public record SetEffusionRate(String sourceId, double rateM3PerS) implements EngineCommand {}
}
