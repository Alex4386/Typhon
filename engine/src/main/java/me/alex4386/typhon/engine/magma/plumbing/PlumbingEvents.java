package me.alex4386.typhon.engine.magma.plumbing;

import me.alex4386.typhon.engine.output.HistoricalEvent;

/** Events of a volcano's magma plumbing. */
public final class PlumbingEvents {
    private PlumbingEvents() {}

    /** A pathway between two chambers froze shut after its flow stalled. */
    public record ConnectionFroze(double time, String volcanoId, String connectionId) implements HistoricalEvent {}
}
