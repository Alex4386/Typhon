package me.alex4386.typhon.engine.output;

import java.util.List;
import me.alex4386.typhon.engine.sim.SimTime;

/**
 * Everything the engine produced during one step, handed to the host as a unit.
 *
 * @param step engine step index
 * @param timeMicros simulation time at the start of the step
 */
public record EngineFrame(long step, long timeMicros, List<BlockChange> blockChanges, List<EngineEvent> events) {
    public EngineFrame {
        blockChanges = List.copyOf(blockChanges);
        events = List.copyOf(events);
    }

    /** Simulation time at the start of the step, in seconds. */
    public double time() {
        return SimTime.seconds(timeMicros);
    }

    public boolean isEmpty() {
        return blockChanges.isEmpty() && events.isEmpty();
    }
}
