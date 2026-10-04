package me.alex4386.typhon.engine.alert;

import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.HistoricalEvent;

/** Events emitted by {@link AlertLevelEstimator}. */
public final class AlertEvents {
    private AlertEvents() {}

    /**
     * The estimated status changed.
     *
     * @param previous level before the change, or {@code null} for the first estimate
     * @param pressureRatio overpressure / failure overpressure at the time of the change
     * @param vtRatePerMinute smoothed VT rate at the time of the change
     * @param rsam RSAM at the time of the change
     */
    public record AlertLevelChanged(
            double time,
            String volcanoId,
            AlertLevel previous,
            AlertLevel current,
            double pressureRatio,
            double vtRatePerMinute,
            double rsam)
            implements HistoricalEvent {}

    /** The suggested eruption style changed. {@code previous} is {@code null} for the first suggestion. */
    public record EruptionStyleSuggested(double time, String volcanoId, EruptionStyle previous, EruptionStyle current)
            implements EngineEvent {}
}
