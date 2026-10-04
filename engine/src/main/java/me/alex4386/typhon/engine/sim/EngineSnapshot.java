package me.alex4386.typhon.engine.sim;

import java.util.Map;

/**
 * Immutable summary of the engine at a step boundary, for UIs that render slower than the engine
 * runs: the simulation time plus each subsystem's {@link Subsystem#snapshot()} (by id, in
 * registration order; subsystems without a snapshot are omitted).
 */
public record EngineSnapshot(long step, long timeMicros, Map<String, Object> subsystems) {
    public EngineSnapshot {
        subsystems = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(subsystems));
    }

    public double time() {
        return SimTime.seconds(timeMicros);
    }

    /** The snapshot of subsystem {@code id} cast to {@code type}, or {@code null}. */
    public <T> T get(String id, Class<T> type) {
        Object value = subsystems.get(id);
        return type.isInstance(value) ? type.cast(value) : null;
    }
}
