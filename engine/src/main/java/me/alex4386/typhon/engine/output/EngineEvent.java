package me.alex4386.typhon.engine.output;

/**
 * A semantic simulation event (tremor, ash fall, bomb launch, ...).
 *
 * <p>Events describe what happened, never how to present it. Hosts render each event at the best
 * fidelity available per viewer (vanilla effects, server-side extras, or a client mod).
 */
public interface EngineEvent {
    /** Simulation time at which the event occurred, in seconds. */
    double time();
}
