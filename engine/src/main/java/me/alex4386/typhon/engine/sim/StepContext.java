package me.alex4386.typhon.engine.sim;

import me.alex4386.typhon.engine.output.Outbox;
import me.alex4386.typhon.engine.random.SimRandom;

/**
 * Per-step view handed to a {@link Subsystem}.
 *
 * @param step engine step index being simulated (monotonic; useful for ordering only)
 * @param timeMicros simulation time at the start of this step
 * @param dtMicros simulated time this subsystem's step covers (its period)
 * @param random the subsystem's own deterministic random stream
 * @param outbox sink for block changes and events
 * @param parallel the engine's deterministic executor for data-parallel work (see {@link Parallel})
 */
public record StepContext(long step, long timeMicros, long dtMicros, SimRandom random, Outbox outbox,
        Parallel parallel) {
    public StepContext(long step, long timeMicros, long dtMicros, SimRandom random, Outbox outbox) {
        this(step, timeMicros, dtMicros, random, outbox, Parallel.sequential());
    }

    /** Simulation time at the start of this step, in seconds. */
    public double time() {
        return SimTime.seconds(timeMicros);
    }

    /** Simulated time this step covers, in seconds. */
    public double dtSeconds() {
        return SimTime.seconds(dtMicros);
    }

    /** Whether a multiple of {@code periodSeconds} falls inside this step (for periodic reports). */
    public boolean crossed(double periodSeconds) {
        return SimTime.crossed(timeMicros, dtMicros, periodSeconds);
    }
}
