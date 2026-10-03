package me.alex4386.typhon.engine.seismic;

import com.google.gson.JsonObject;
import me.alex4386.typhon.engine.magma.MeltViscosity;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.random.SimRandom;
import me.alex4386.typhon.engine.sim.SimTime;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.volcano.MagmaState;

/**
 * Generates volcanic seismicity from the state of a magma system.
 *
 * <p>Events arrive as Poisson processes whose rates follow the physics driving them:
 *
 * <ul>
 *   <li><b>VT</b>: background + {@code vtPerMPa · max(0, dP/dt)}, multiplied by the failure-forecast
 *       acceleration {@code 1 / (1 − P/P_f)} so rates climb hyperbolically as the roof nears
 *       failure; each VT may trigger a swarm that multiplies the rate for a while.
 *   <li><b>LP</b>: background + {@code lpPerEruptionRate · Q}; fluid movement in the conduit.
 *   <li><b>Tremor</b>: sustained episodes during eruptions with onset rate ∝ √Q and amplitude
 *       growing with log Q.
 *   <li><b>Explosions</b>: during eruptions of viscous, water-rich magma (explosivity from
 *       {@link MeltViscosity} and dissolved H₂O).
 * </ul>
 *
 * <p>Magnitudes follow truncated Gutenberg–Richter laws with per-type b-values. Hypocentres scatter
 * along the chamber–vent axis: VTs mostly deep near the chamber roof, LPs and tremor shallow in the
 * conduit, explosions just below the vent. RSAM and smoothed event rates are tracked for alert
 * estimation and telemetry.
 */
public final class SeismicityModel implements Subsystem {
    private final SeismicConfig config;
    private final MagmaState magma;

    private double rsam;
    private double vtRatePerMinute;
    private double lpRatePerMinute;
    private double explosionRatePerMinute;
    private long swarmUntilTick = Long.MIN_VALUE;
    private long tremorUntilTick = Long.MIN_VALUE;
    private double tremorMagnitude;
    private double expectedVtRate;
    private double expectedLpRate;
    private double expectedExplosionRate;
    private long lastTick = -1;

    public SeismicityModel(SeismicConfig config, MagmaState magma) {
        this.config = config;
        this.magma = magma;
    }

    public SeismicConfig config() {
        return config;
    }

    @Override
    public String id() {
        return "seismic:" + config.volcanoId();
    }

    @Override
    public int interval() {
        return config.stepIntervalTicks();
    }

    @Override
    public void step(StepContext context) {
        long tick = context.tick();
        double dt = context.dtSeconds();
        SimRandom random = context.random();
        lastTick = tick;

        boolean erupting = magma.erupting();
        double eruptionRate = Math.max(0, magma.eruptionRate());

        expectedVtRate = vtRate(tick);
        expectedLpRate = Math.min(config.maxEventRate(), config.backgroundLpRate() + config.lpPerEruptionRate() * eruptionRate);
        expectedExplosionRate = erupting ? Math.min(config.maxEventRate(), config.explosionRate() * explosivity(magma)) : 0;

        double transientAmplitude = 0;
        int vtCount = random.nextPoisson(expectedVtRate * dt);
        for (int i = 0; i < vtCount; i++) {
            boolean swarm = swarmActive(tick);
            double b = swarm ? config.swarmBValue() : config.vtBValue();
            double m = GutenbergRichter.sample(random, b, config.minMagnitude(), config.maxMagnitude());
            BlockPos hypocenter = hypocenter(random, 0, 0.7, 1.0);
            transientAmplitude += emit(context, SeismicEventType.VT, m, hypocenter, transientTicks(0.5, m), swarm);
            if (!swarm && random.chance(config.swarmTriggerProbability())) {
                swarmUntilTick = tick + SimTime.secondsToTicks(random.nextExponential(1 / config.swarmMeanDurationSeconds()));
            }
        }

        int lpCount = random.nextPoisson(expectedLpRate * dt);
        for (int i = 0; i < lpCount; i++) {
            double m = GutenbergRichter.sample(random, config.lpBValue(), config.minMagnitude(), config.lpMaxMagnitude());
            BlockPos hypocenter = hypocenter(random, 0.6, 1.0, 0.4);
            transientAmplitude += emit(context, SeismicEventType.LP, m, hypocenter, transientTicks(1.0, m), false);
        }

        int explosionCount = random.nextPoisson(expectedExplosionRate * dt);
        for (int i = 0; i < explosionCount; i++) {
            double m = GutenbergRichter.sample(
                    random, config.explosionBValue(), config.explosionMinMagnitude(), config.explosionMaxMagnitude());
            BlockPos hypocenter = config.conduitTop().offset(0, -random.nextInt(0, 10), 0);
            transientAmplitude += emit(context, SeismicEventType.EXPLOSION, m, hypocenter, transientTicks(1.0, m), false);
        }

        if (!erupting && tremorActive(tick)) {
            tremorUntilTick = tick;
        }
        if (erupting && !tremorActive(tick)) {
            double onsetRate = config.tremorEpisodeRate() * Math.sqrt(eruptionRate);
            if (random.chance(1 - Math.exp(-onsetRate * dt))) {
                double seconds = random.nextExponential(1 / config.tremorMeanDurationSeconds());
                int durationTicks = (int) Math.max(1, SimTime.secondsToTicks(seconds));
                tremorUntilTick = tick + durationTicks;
                tremorMagnitude = config.tremorBaseMagnitude() + 0.5 * Math.log10(1 + eruptionRate);
                emit(context, SeismicEventType.TREMOR, tremorMagnitude, hypocenter(random, 0.8, 1.0, 0.2), durationTicks, false);
            }
        }

        double rsamDecay = Math.exp(-dt / config.rsamWindowSeconds());
        rsam = rsam * rsamDecay + transientAmplitude / config.rsamWindowSeconds();
        if (tremorActive(tick)) {
            rsam += Math.pow(10, tremorMagnitude) * (1 - rsamDecay);
        }

        double rateDecay = Math.exp(-dt / config.rateWindowSeconds());
        double perMinute = 60 / config.rateWindowSeconds();
        vtRatePerMinute = vtRatePerMinute * rateDecay + vtCount * perMinute;
        lpRatePerMinute = lpRatePerMinute * rateDecay + lpCount * perMinute;
        explosionRatePerMinute = explosionRatePerMinute * rateDecay + explosionCount * perMinute;

        int sampleInterval = config.sampleIntervalTicks();
        if (sampleInterval > 0 && Math.floorDiv(tick, sampleInterval) != Math.floorDiv(tick - interval(), sampleInterval)) {
            context.outbox().emit(new RsamSample(tick, config.volcanoId(), rsam, vtRatePerMinute, lpRatePerMinute,
                    explosionRatePerMinute, tremorActive(tick), swarmActive(tick)));
        }
    }

    /** Expected VT rate (events/s) for the current magma state. */
    private double vtRate(long tick) {
        double pressurisation = Math.max(0, magma.overpressureRateMPaPerSecond());
        double rate = config.backgroundVtRate() + config.vtPerMPa() * pressurisation * acceleration();
        if (swarmActive(tick)) rate *= config.swarmRateMultiplier();
        return Math.min(config.maxEventRate(), rate);
    }

    /** Failure-forecast acceleration factor {@code 1 / (1 − P/P_f)}, capped. */
    public double acceleration() {
        double ratio = magma.overpressureMPa() / config.failureOverpressureMPa();
        if (ratio <= 0) return 1;
        double remaining = 1 - ratio;
        return remaining <= 1 / config.maxAcceleration() ? config.maxAcceleration() : 1 / remaining;
    }

    /**
     * Explosivity in {@code [0, 1]} of the magma currently feeding the vent: high for viscous,
     * water-rich magma (Vulcanian–Plinian), near zero for fluid basalt.
     */
    public static double explosivity(MagmaState magma) {
        double viscosity = MeltViscosity.log10(magma.silicaWt(), magma.waterWt(), magma.temperatureC(), magma.crystalFraction());
        double viscous = clamp01((viscosity - 3) / 4);
        double wet = clamp01(magma.waterWt() / 4);
        return viscous * wet;
    }

    private double emit(StepContext context, SeismicEventType type, double magnitude, BlockPos hypocenter, int durationTicks, boolean swarm) {
        SeismicEvent event = new SeismicEvent(context.tick(), config.volcanoId(), type, magnitude, hypocenter, durationTicks, swarm);
        context.outbox().emit(event);
        return event.amplitude();
    }

    private static int transientTicks(double baseSeconds, double magnitude) {
        return (int) Math.max(2, SimTime.secondsToTicks(baseSeconds + 0.5 * Math.max(0, magnitude)));
    }

    /**
     * Picks a hypocentre along the chamber → conduit-top axis at fraction {@code t ∈ [tMin, tMax]}
     * (0 = chamber, 1 = vent), skewed towards {@code tMin}, with lateral scatter scaled by
     * {@code spreadScale}.
     */
    private BlockPos hypocenter(SimRandom random, double tMin, double tMax, double spreadScale) {
        BlockPos bottom = magma.chamberCenter();
        BlockPos top = config.conduitTop();
        double u = random.nextDouble();
        double t = tMin + (tMax - tMin) * u * u;
        double spread = config.hypocenterSpread() * spreadScale * (1 - 0.5 * t);
        double x = bottom.x() + t * (top.x() - bottom.x()) + random.nextGaussian() * spread;
        double y = bottom.y() + t * (top.y() - bottom.y()) + random.nextGaussian() * spread * 0.5;
        double z = bottom.z() + t * (top.z() - bottom.z()) + random.nextGaussian() * spread;
        int yi = (int) Math.round(Math.max(BlockPos.MIN_Y, Math.min(BlockPos.MAX_Y, y)));
        return new BlockPos((int) Math.round(x), yi, (int) Math.round(z));
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(1, v);
    }

    // ── Observables ──

    /** Real-time seismic amplitude (time-averaged ground-motion amplitude, counts). */
    public double rsam() {
        return rsam;
    }

    public double vtRatePerMinute() {
        return vtRatePerMinute;
    }

    public double lpRatePerMinute() {
        return lpRatePerMinute;
    }

    public double explosionRatePerMinute() {
        return explosionRatePerMinute;
    }

    /** Expected (model) VT rate at the last step, events/s. */
    public double expectedVtRate() {
        return expectedVtRate;
    }

    /** Expected (model) LP rate at the last step, events/s. */
    public double expectedLpRate() {
        return expectedLpRate;
    }

    /** Expected (model) explosion rate at the last step, events/s. */
    public double expectedExplosionRate() {
        return expectedExplosionRate;
    }

    public boolean swarmActive() {
        return swarmActive(lastTick);
    }

    public boolean tremorActive() {
        return tremorActive(lastTick);
    }

    /** Equivalent magnitude of the current tremor episode (meaningful while {@link #tremorActive()}). */
    public double tremorMagnitude() {
        return tremorMagnitude;
    }

    private boolean swarmActive(long tick) {
        return tick < swarmUntilTick;
    }

    private boolean tremorActive(long tick) {
        return tick < tremorUntilTick;
    }

    // ── Persistence ──

    @Override
    public void saveState(JsonObject out) {
        out.addProperty("rsam", rsam);
        out.addProperty("vtRatePerMinute", vtRatePerMinute);
        out.addProperty("lpRatePerMinute", lpRatePerMinute);
        out.addProperty("explosionRatePerMinute", explosionRatePerMinute);
        out.addProperty("swarmUntilTick", swarmUntilTick);
        out.addProperty("tremorUntilTick", tremorUntilTick);
        out.addProperty("tremorMagnitude", tremorMagnitude);
        out.addProperty("expectedVtRate", expectedVtRate);
        out.addProperty("expectedLpRate", expectedLpRate);
        out.addProperty("expectedExplosionRate", expectedExplosionRate);
        out.addProperty("lastTick", lastTick);
    }

    @Override
    public void loadState(JsonObject in) {
        rsam = in.get("rsam").getAsDouble();
        vtRatePerMinute = in.get("vtRatePerMinute").getAsDouble();
        lpRatePerMinute = in.get("lpRatePerMinute").getAsDouble();
        explosionRatePerMinute = in.get("explosionRatePerMinute").getAsDouble();
        swarmUntilTick = in.get("swarmUntilTick").getAsLong();
        tremorUntilTick = in.get("tremorUntilTick").getAsLong();
        tremorMagnitude = in.get("tremorMagnitude").getAsDouble();
        expectedVtRate = in.get("expectedVtRate").getAsDouble();
        expectedLpRate = in.get("expectedLpRate").getAsDouble();
        expectedExplosionRate = in.get("expectedExplosionRate").getAsDouble();
        lastTick = in.get("lastTick").getAsLong();
    }
}
