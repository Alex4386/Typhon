package me.alex4386.typhon.engine.alert;

import com.google.gson.JsonObject;
import me.alex4386.typhon.engine.seismic.SeismicityModel;
import me.alex4386.typhon.engine.sim.SimTime;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.volcano.MagmaState;

/**
 * Derives the volcano's status from monitoring observables, the way an observatory sets its alert
 * level: chamber overpressure (a proxy for deformation), smoothed VT rate and RSAM.
 *
 * <p>An eruption is always {@link AlertLevel#ERUPTING}. Otherwise upgrades are immediate, while
 * downgrades need every indicator to stay below {@code threshold × downgradeFactor} for the dwell
 * time and then step down one level at a time, so the status does not flicker around thresholds. A
 * quiet, largely crystallised chamber is {@link AlertLevel#EXTINCT}.
 *
 * <p>It also suggests the eruption style the magma would produce now (see
 * {@link EruptionStyleClassifier}).
 */
public final class AlertLevelEstimator implements Subsystem {
    private final AlertConfig config;
    private final MagmaState magma;
    private final SeismicityModel seismicity;

    private AlertLevel level;
    private EruptionStyle style;
    private long downgradeSinceTick = -1;

    /** @param seismicity seismic observables, or {@code null} to rely on overpressure alone */
    public AlertLevelEstimator(AlertConfig config, MagmaState magma, SeismicityModel seismicity) {
        this.config = config;
        this.magma = magma;
        this.seismicity = seismicity;
    }

    @Override
    public String id() {
        return "alert:" + config.volcanoId();
    }

    @Override
    public int interval() {
        return config.stepIntervalTicks();
    }

    @Override
    public void step(StepContext context) {
        long tick = context.tick();
        double pressureRatio = magma.overpressureMPa() / config.failureOverpressureMPa();
        double vtRate = seismicity == null ? 0 : seismicity.vtRatePerMinute();
        double rsam = seismicity == null ? 0 : seismicity.rsam();

        AlertLevel next = nextLevel(tick, pressureRatio, vtRate, rsam);
        if (next != level) {
            context.outbox().emit(new AlertEvents.AlertLevelChanged(
                    tick, config.volcanoId(), level, next, pressureRatio, vtRate, rsam));
            level = next;
        }

        EruptionStyle suggested = EruptionStyleClassifier.classify(magma);
        if (suggested != style) {
            context.outbox().emit(new AlertEvents.EruptionStyleSuggested(tick, config.volcanoId(), style, suggested));
            style = suggested;
        }
    }

    private AlertLevel nextLevel(long tick, double pressureRatio, double vtRate, double rsam) {
        if (magma.erupting()) {
            downgradeSinceTick = -1;
            return AlertLevel.ERUPTING;
        }

        AlertLevel raw = indicated(pressureRatio, vtRate, rsam, 1);
        if (level == null) {
            return raw;
        }
        if (raw.isAbove(level)) {
            downgradeSinceTick = -1;
            return raw;
        }

        AlertLevel sustained = indicated(pressureRatio, vtRate, rsam, config.downgradeFactor());
        if (!level.isAbove(sustained)) {
            downgradeSinceTick = -1;
            return level;
        }
        if (downgradeSinceTick < 0) {
            downgradeSinceTick = tick;
        }
        double dwellTicks = config.downgradeDwellSeconds() * SimTime.TICKS_PER_SECOND;
        if (tick - downgradeSinceTick < dwellTicks) {
            return level;
        }
        downgradeSinceTick = tick; // dwell again before the next step down
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

    /** Style the magma would erupt in now, or {@code null} before the first step. */
    public EruptionStyle suggestedStyle() {
        return style;
    }

    @Override
    public void saveState(JsonObject out) {
        if (level != null) out.addProperty("level", level.name());
        if (style != null) out.addProperty("style", style.name());
        out.addProperty("downgradeSinceTick", downgradeSinceTick);
    }

    @Override
    public void loadState(JsonObject in) {
        level = in.has("level") ? AlertLevel.valueOf(in.get("level").getAsString()) : null;
        style = in.has("style") ? EruptionStyle.valueOf(in.get("style").getAsString()) : null;
        downgradeSinceTick = in.get("downgradeSinceTick").getAsLong();
    }
}
