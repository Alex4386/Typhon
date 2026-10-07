package me.alex4386.typhon.engine.output;

import java.util.List;
import me.alex4386.typhon.engine.sim.SimTime;

/**
 * Everything the engine produced during one step, handed to the host as a unit: the events. The changed
 * state itself is read from the world model (a block host projects it, module {@code mc-projection}).
 *
 * @param step engine step index
 * @param timeMicros simulation time at the start of the step
 */
public record EngineFrame(long step, long timeMicros, List<EngineEvent> events) {
    public EngineFrame {
        events = List.copyOf(events);
    }

    /** Simulation time at the start of the step, in seconds. */
    public double time() {
        return SimTime.seconds(timeMicros);
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }
}
