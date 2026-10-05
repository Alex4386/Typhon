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

    /**
     * The estimated eruption style or explosivity changed. Estimated from what the eruption does (or,
     * before one, from the flow the conduit would carry if it failed now); see {@link
     * EruptionClassifier}.
     *
     * @param previous style before the change, {@code null} for the first estimate
     * @param current most probable style ({@link EruptionStyle#MIXED} when none dominates)
     * @param probabilities probability of each style (sums to 1)
     * @param vei estimated Volcanic Explosivity Index (Newhall &amp; Self 1982), 0–8
     * @param forecast true before an eruption (a forecast of the next one), false while erupting
     */
    public record EruptionStyleEstimated(double time, String volcanoId, EruptionStyle previous, EruptionStyle current,
            java.util.Map<EruptionStyle, Double> probabilities, int vei, boolean forecast) implements HistoricalEvent {}
}
