package me.alex4386.typhon.engine.seismic;

import me.alex4386.typhon.engine.output.EngineEvent;

/**
 * Periodic seismic telemetry, the engine's analogue of an observatory's RSAM plot.
 *
 * @param rsam real-time seismic amplitude: time-averaged ground-motion amplitude (counts)
 * @param vtRatePerMinute smoothed VT event rate
 * @param lpRatePerMinute smoothed LP event rate
 * @param explosionRatePerMinute smoothed explosion rate
 * @param tremorActive whether a tremor episode is in progress
 * @param swarmActive whether an earthquake swarm is in progress
 */
public record RsamSample(
        long tick,
        String volcanoId,
        double rsam,
        double vtRatePerMinute,
        double lpRatePerMinute,
        double explosionRatePerMinute,
        boolean tremorActive,
        boolean swarmActive)
        implements EngineEvent {}
