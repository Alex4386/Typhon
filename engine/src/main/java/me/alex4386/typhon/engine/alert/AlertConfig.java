package me.alex4386.typhon.engine.alert;

import java.util.Objects;

/**
 * Thresholds of an {@link AlertLevelEstimator}. A level is reached when <em>any</em> indicator
 * crosses its threshold; it is only left after all indicators stay below
 * {@code threshold × downgradeFactor} for {@code downgradeDwellSeconds}.
 *
 * @param failureOverpressureMPa overpressure at which the roof fails (pressure ratio denominator)
 * @param minorPressureRatio overpressure / failure for MINOR_ACTIVITY
 * @param majorPressureRatio … for MAJOR_ACTIVITY
 * @param imminentPressureRatio … for ERUPTION_IMMINENT
 * @param minorVtPerMinute smoothed VT rate for MINOR_ACTIVITY
 * @param majorVtPerMinute … for MAJOR_ACTIVITY
 * @param imminentVtPerMinute … for ERUPTION_IMMINENT
 * @param minorRsam RSAM for MINOR_ACTIVITY
 * @param majorRsam … for MAJOR_ACTIVITY
 * @param imminentRsam … for ERUPTION_IMMINENT
 * @param downgradeFactor fraction of a threshold that must be undercut before stepping down
 * @param downgradeDwellSeconds time indicators must stay low before each one-level step down
 * @param extinctCrystalFraction crystal fraction above which a quiet system counts as EXTINCT
 * @param stepPeriodSeconds how often the estimator runs (seconds)
 */
public record AlertConfig(
        String volcanoId,
        double failureOverpressureMPa,
        double minorPressureRatio,
        double majorPressureRatio,
        double imminentPressureRatio,
        double minorVtPerMinute,
        double majorVtPerMinute,
        double imminentVtPerMinute,
        double minorRsam,
        double majorRsam,
        double imminentRsam,
        double downgradeFactor,
        double downgradeDwellSeconds,
        double extinctCrystalFraction,
        double stepPeriodSeconds) {

    public AlertConfig {
        Objects.requireNonNull(volcanoId, "volcanoId");
        if (!(failureOverpressureMPa > 0)) throw new IllegalArgumentException("failureOverpressureMPa must be positive");
        if (!(minorPressureRatio < majorPressureRatio && majorPressureRatio < imminentPressureRatio)) {
            throw new IllegalArgumentException("pressure ratios must increase");
        }
        if (!(minorVtPerMinute < majorVtPerMinute && majorVtPerMinute < imminentVtPerMinute)) {
            throw new IllegalArgumentException("VT thresholds must increase");
        }
        if (!(minorRsam < majorRsam && majorRsam < imminentRsam)) {
            throw new IllegalArgumentException("RSAM thresholds must increase");
        }
        if (!(downgradeFactor > 0 && downgradeFactor <= 1)) throw new IllegalArgumentException("downgradeFactor must be in (0, 1]");
        if (downgradeDwellSeconds < 0) throw new IllegalArgumentException("downgradeDwellSeconds must be >= 0");
        if (!(stepPeriodSeconds > 0)) throw new IllegalArgumentException("stepPeriodSeconds must be > 0");
    }

    public static AlertConfig defaults(String volcanoId) {
        return new AlertConfig(volcanoId, 15, 0.3, 0.6, 0.9, 0.5, 3, 10, 2, 10, 50, 0.7, 120, 0.55, 1.0);
    }

    public AlertConfig withFailureOverpressure(double mpa) {
        return new AlertConfig(volcanoId, mpa, minorPressureRatio, majorPressureRatio, imminentPressureRatio,
                minorVtPerMinute, majorVtPerMinute, imminentVtPerMinute, minorRsam, majorRsam, imminentRsam,
                downgradeFactor, downgradeDwellSeconds, extinctCrystalFraction, stepPeriodSeconds);
    }

    public AlertConfig withDowngradeDwellSeconds(double seconds) {
        return new AlertConfig(volcanoId, failureOverpressureMPa, minorPressureRatio, majorPressureRatio,
                imminentPressureRatio, minorVtPerMinute, majorVtPerMinute, imminentVtPerMinute, minorRsam, majorRsam,
                imminentRsam, downgradeFactor, seconds, extinctCrystalFraction, stepPeriodSeconds);
    }
}
