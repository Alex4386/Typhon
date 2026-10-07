package me.alex4386.typhon.engine.alert;

import com.google.gson.JsonObject;
import me.alex4386.typhon.engine.seismic.SeismicityModel;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.volcano.MagmaState;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;

/**
 * Derives the volcano's status from monitoring observables, the way an observatory sets its alert
 * level: chamber overpressure (a proxy for deformation), smoothed VT rate and RSAM.
 *
 * <p>An eruption is always {@link AlertLevel#ERUPTING}. Otherwise upgrades are immediate, while
 * downgrades need every indicator to stay below {@code threshold × downgradeFactor} for the dwell
 * time and then step down one level at a time, so the status does not flicker around thresholds. A
 * quiet, largely crystallised chamber is {@link AlertLevel#EXTINCT}.
 *
 * <p>Its snapshot also carries the estimated eruption style when an {@link EruptionClassifier} is
 * attached; the estimate is made from the eruption itself, not from the alert observables.
 *
 * <p>References: Gardner &amp; Guffanti (2006), USGS Fact Sheet 2006-3139 (alert-level system); Endo &amp; Murray (1991), Bull. Volcanol. 53:533-545 (RSAM). See {@code docs/references.md}.
 */
public final class AlertLevelEstimator implements Subsystem {
    private AlertConfig config;
    private final MagmaState magma;
    private final SeismicityModel seismicity;

    private AlertLevel level;
    private EruptionClassifier classifier;
    /** Whether magma is rising towards the surface through a dike (none: never). */
    private java.util.function.BooleanSupplier dikeRising = () -> false;
    /** Time at which a downgrade became possible (negative = not pending). */
    private double downgradeSince = -1;

    /** @param seismicity seismic observables, or {@code null} to rely on overpressure alone */
    public AlertLevelEstimator(AlertConfig config, MagmaState magma, SeismicityModel seismicity) {
        this.config = config;
        this.magma = magma;
        this.seismicity = seismicity;
    }

    @Override
    public boolean reconfigure(Object c) {
        if (!(c instanceof AlertConfig n) || !n.volcanoId().equals(config.volcanoId())) return false;
        config = n;
        return true;
    }

    @Override
    public String id() {
        return "alert:" + config.volcanoId();
    }

    /** 0D, touches only this volcano's magma/seismic/alert chain: may run beside other volcanoes'. */
    @Override
    public String concurrencyLane() {
        return "volcano:" + config.volcanoId();
    }

    @Override
    public double periodSeconds() {
        return config.stepPeriodSeconds();
    }

    @Override
    public Object config() {
        return config;
    }

    /** Current level and estimated style ({@code null} until estimated) for dashboards. */
    public record Snapshot(AlertLevel level, EruptionStyle style) {}

    @Override
    public Snapshot snapshot() {
        return new Snapshot(level, suggestedStyle());
    }

    /**
     * Tells the estimator when a dike is rising from the chamber: magma on its way to the surface is the
     * imminent-eruption signal whatever the chamber pressure does (the dike draws the chamber down).
     */
    public void setDikeRising(java.util.function.BooleanSupplier rising) {
        this.dikeRising = java.util.Objects.requireNonNull(rising);
    }

    /** Attaches the style estimate reported alongside the level. */
    public void setClassifier(EruptionClassifier classifier) {
        this.classifier = classifier;
    }

    @Override
    public void step(StepContext context) {
        double now = context.time();
        double failure = magma.nextFailureOverpressureMPa();
        // towards the failure that comes next: a molten conduit's plug, or else the chamber walls
        double pressureRatio = magma.overpressureMPa() / (failure > 0 ? failure : config.failureOverpressureMPa());
        double vtRate = seismicity == null ? 0 : seismicity.vtRatePerMinute();
        double rsam = seismicity == null ? 0 : seismicity.rsam();

        AlertLevel next = nextLevel(now, pressureRatio, vtRate, rsam);
        if (next != level) {
            context.outbox().emit(new AlertEvents.AlertLevelChanged(
                    now, config.volcanoId(), level, next, pressureRatio, vtRate, rsam));
            level = next;
        }
    }

    private AlertLevel nextLevel(double now, double pressureRatio, double vtRate, double rsam) {
        if (magma.erupting()) {
            downgradeSince = -1;
            return AlertLevel.ERUPTING;
        }

        AlertLevel raw = dikeRising.getAsBoolean() ? AlertLevel.ERUPTION_IMMINENT : indicated(pressureRatio, vtRate, rsam, 1);
        if (level == null) {
            return raw;
        }
        if (raw.isAbove(level)) {
            downgradeSince = -1;
            return raw;
        }

        AlertLevel sustained = dikeRising.getAsBoolean() ? AlertLevel.ERUPTION_IMMINENT
                : indicated(pressureRatio, vtRate, rsam, config.downgradeFactor());
        if (!level.isAbove(sustained)) {
            downgradeSince = -1;
            return level;
        }
        if (downgradeSince < 0) {
            downgradeSince = now;
        }
        if (now - downgradeSince < config.downgradeDwellSeconds()) {
            return level;
        }
        downgradeSince = now; // dwell again before the next step down
        return AlertLevel.max(sustained, level.lower());
    }

    /** The level indicated by the observables with every threshold scaled by {@code factor}. */
    private AlertLevel indicated(double pressureRatio, double vtRate, double rsam, double factor) {
        if (pressureRatio >= config.imminentPressureRatio() * factor
                || vtRate >= config.imminentVtPerMinute() * factor
                || rsam >= config.imminentRsam() * factor) {
            return AlertLevel.ERUPTION_IMMINENT;
        }
        if (pressureRatio >= config.majorPressureRatio() * factor
                || vtRate >= config.majorVtPerMinute() * factor
                || rsam >= config.majorRsam() * factor) {
            return AlertLevel.MAJOR_ACTIVITY;
        }
        if (pressureRatio >= config.minorPressureRatio() * factor
                || vtRate >= config.minorVtPerMinute() * factor
                || rsam >= config.minorRsam() * factor) {
            return AlertLevel.MINOR_ACTIVITY;
        }
        return magma.crystalFraction() >= config.extinctCrystalFraction() ? AlertLevel.EXTINCT : AlertLevel.DORMANT;
    }

    /** Current status, or {@code null} before the first step. */
    public AlertLevel level() {
        return level;
    }

    /**
     * Estimated style of the ongoing eruption, or forecast of the next one; {@code null} without a
     * classifier or before its first estimate.
     */
    public EruptionStyle suggestedStyle() {
        return classifier == null ? null : classifier.style();
    }

    @Override
    public void saveState(StateWriter writer) {
        JsonObject out = writer.json();
        if (level != null) out.addProperty("level", level.name());
        out.addProperty("downgradeSince", downgradeSince);
    }

    @Override
    public void loadState(StateReader reader) {
        JsonObject in = reader.json();
        level = in.has("level") ? AlertLevel.valueOf(in.get("level").getAsString()) : null;
        downgradeSince = in.get("downgradeSince").getAsDouble();
    }
}
