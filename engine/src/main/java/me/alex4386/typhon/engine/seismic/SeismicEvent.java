package me.alex4386.typhon.engine.seismic;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineEvent;

/**
 * A volcanic earthquake (or the onset of a tremor episode).
 *
 * @param magnitude local magnitude; for tremor, the equivalent magnitude of the sustained amplitude
 * @param durationTicks shaking duration; long for tremor episodes, a few ticks for transients
 * @param swarm true when the event belongs to an earthquake swarm
 */
public record SeismicEvent(
        long tick,
        String volcanoId,
        SeismicEventType type,
        double magnitude,
        BlockPos hypocenter,
        int durationTicks,
        boolean swarm)
        implements EngineEvent {

    /** Radiated seismic energy (J), Gutenberg–Richter energy relation {@code log10 E = 1.5 M + 4.8}. */
    public double energyJoules() {
        return Math.pow(10, 1.5 * magnitude + 4.8);
    }

    /** Relative ground-motion amplitude at the source (arbitrary counts, {@code 10^M}). */
    public double amplitude() {
        return Math.pow(10, magnitude);
    }
}
