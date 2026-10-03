package me.alex4386.typhon.engine.sim;

import me.alex4386.typhon.engine.output.Outbox;
import me.alex4386.typhon.engine.random.SimRandom;

/**
 * Per-step view handed to a {@link Subsystem}.
 *
 * @param tick engine tick being simulated
 * @param dtSeconds simulated time since this subsystem's previous step
 * @param random the subsystem's own deterministic random stream
 * @param outbox sink for block changes and events
 */
public record StepContext(long tick, double dtSeconds, SimRandom random, Outbox outbox) {}
