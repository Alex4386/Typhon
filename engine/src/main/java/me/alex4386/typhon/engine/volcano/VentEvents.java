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
    public record VentStateChanged(double time, String volcanoId, String ventId, VentStatus previous,
            VentStatus current, double feederWidthM) implements HistoricalEvent {}
}
