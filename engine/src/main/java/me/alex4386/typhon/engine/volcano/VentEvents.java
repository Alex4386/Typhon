package me.alex4386.typhon.engine.volcano;

import me.alex4386.typhon.engine.output.HistoricalEvent;

/** Events about vent lifecycles. */
public final class VentEvents {
    private VentEvents() {}

    /**
     * A vent changed state, e.g. a fissure's flow localised and its feeder froze, or the user sealed
     * a vent.
     *
     * @param feederWidthM widest open feeder width (m) of a dike-fed vent at the change; NaN for craters
     */
    /**
     * The flow of fissure {@code fissureId} localised: a segment kept carrying magma after the segments
     * beside it froze, and goes on erupting as the crater {@code vent} on the fissure line.
     */
    public record VentFormed(double time, String volcanoId, VentSite vent, String fissureId)
            implements HistoricalEvent {}

    public record VentStateChanged(double time, String volcanoId, String ventId, VentStatus previous,
            VentStatus current, double feederWidthM) implements HistoricalEvent {}
}
