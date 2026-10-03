package me.alex4386.typhon.engine.output;

import java.util.List;

/** Everything the engine produced during one tick, handed to the host as a unit. */
public record EngineFrame(long tick, List<BlockChange> blockChanges, List<EngineEvent> events) {
    public EngineFrame {
        blockChanges = List.copyOf(blockChanges);
        events = List.copyOf(events);
    }

    public boolean isEmpty() {
        return blockChanges.isEmpty() && events.isEmpty();
    }
}
