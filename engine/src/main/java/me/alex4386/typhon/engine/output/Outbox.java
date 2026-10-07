package me.alex4386.typhon.engine.output;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the events emitted during a step.
 *
 * <p>The engine emits no block changes: its state is the continuous world model, and a host that shows
 * blocks (Minecraft) projects that state itself (module {@code mc-projection}).
 */
public final class Outbox {
    private final List<EngineEvent> events = new ArrayList<>();

    public void emit(EngineEvent event) {
        events.add(event);
    }

    /** Moves everything {@code other} collected into this outbox, as if {@code other}'s calls had been made here. */
    public void absorb(Outbox other) {
        events.addAll(other.events);
        other.events.clear();
    }

    /** Takes everything collected so far as a frame. Called by the engine at the end of a step. */
    public EngineFrame drain(long step, long timeMicros) {
        EngineFrame frame = new EngineFrame(step, timeMicros, events);
        events.clear();
        return frame;
    }
}
