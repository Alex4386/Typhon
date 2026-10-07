package me.alex4386.typhon.engine.seismic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.magma.MeltViscosity;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.random.SimRandom;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.magma.conduit.ConduitSolution;
import me.alex4386.typhon.engine.volcano.EruptiveRegime;
import me.alex4386.typhon.engine.volcano.MagmaState;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;

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
    private SeismicConfig config;
    private final MagmaState magma;

    private final List<Point3> induced = new ArrayList<>();
    private final List<QueuedExplosion> explosions = new ArrayList<>();
    private double rsam;
    private double vtRatePerMinute;
    private double lpRatePerMinute;
    private double explosionRatePerMinute;
    /** End of the current swarm / tremor episode (seconds; negative = none). */
    private double swarmUntil = -1;
    private double tremorUntil = -1;
    private double tremorMagnitude;
    private double expectedVtRate;
    private double expectedLpRate;
    private double expectedExplosionRate;
    private double lastTime = -1;

    /** Metres per block of the volcano's geometry (chamber centre, conduit top), for hypocentres in metres. */
    private final double metersPerBlock;

    public SeismicityModel(SeismicConfig config, MagmaState magma, double metersPerBlock) {
        this.config = config;
        this.magma = magma;
        this.metersPerBlock = metersPerBlock;
    }

    @Override
    public SeismicConfig config() {
        return config;
    }

    @Override
    public boolean reconfigure(Object c) {
        if (!(c instanceof SeismicConfig n) || !n.volcanoId().equals(config.volcanoId())) return false;
        config = n;
        return true;
    }

    @Override
    public String id() {
        return "seismic:" + config.volcanoId();
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
    public RsamSample snapshot() {
        return new RsamSample(Math.max(0, lastTime), config.volcanoId(), rsam, vtRatePerMinute, lpRatePerMinute,
                explosionRatePerMinute, tremorActive(), swarmActive());
    }

    @Override
    public void step(StepContext context) {
        double now = context.time();
        double dt = context.dtSeconds();
        SimRandom random = context.random();
        lastTime = now;

        boolean erupting = magma.erupting();
        double eruptionRate = Math.max(0, magma.eruptionRate());

        expectedVtRate = vtRate(now);
        expectedLpRate = Math.min(config.maxEventRate(), config.backgroundLpRate() + config.lpPerEruptionRate() * eruptionRate);
        // Magma fragmenting in the conduit crackles with explosion quakes; discrete explosions (slug
        // bursts, plug failures, magma-water jets) are reported by the surface coupling via
        // queueExplosion. Without a resolved conduit, every eruption may crackle.
        ConduitSolution flow = magma.conduitFlow();
        boolean continuous = magma.eruptiveRegime() == EruptiveRegime.UNKNOWN
                || (flow != null && flow.fragmented() && flow.fragmentationDepthM() > 0);
        expectedExplosionRate = erupting && continuous
                ? Math.min(config.maxEventRate(), config.explosionRate() * explosivity(magma)) : 0;

        double transientAmplitude = 0;
        int vtCount = random.nextPoisson(expectedVtRate * dt);
        for (int i = 0; i < vtCount; i++) {
            double t = within(now, dt, i, vtCount, random);
            boolean swarm = swarmActive(t);
            double b = swarm ? config.swarmBValue() : config.vtBValue();
            double m = GutenbergRichter.sample(random, b, config.minMagnitude(), config.maxMagnitude());
            Point3 hypocenter = hypocenter(random, 0, 0.7, 1.0);
            transientAmplitude += emit(context, t, SeismicEventType.VT, m, hypocenter, transientSeconds(0.5, m), swarm);
            if (!swarm && random.chance(config.swarmTriggerProbability())) {
                swarmUntil = t + random.nextExponential(1 / config.swarmMeanDurationSeconds());
            }
        }

        // Induced VT events (e.g. dike-tip fracturing) at the hypocentres other subsystems reported.
        for (Point3 hypocenter : induced) {
            double m = GutenbergRichter.sample(random, config.swarmBValue(), config.minMagnitude(), config.maxMagnitude());
            transientAmplitude += emit(context, SeismicEventType.VT, m, hypocenter, transientSeconds(0.5, m), true);
        }
        vtCount += induced.size();
        induced.clear();

        int lpCount = random.nextPoisson(expectedLpRate * dt);
        for (int i = 0; i < lpCount; i++) {
            double t = within(now, dt, i, lpCount, random);
            double m = GutenbergRichter.sample(random, config.lpBValue(), config.minMagnitude(), config.lpMaxMagnitude());
            Point3 hypocenter = hypocenter(random, 0.6, 1.0, 0.4);
            transientAmplitude += emit(context, t, SeismicEventType.LP, m, hypocenter, transientSeconds(1.0, m), false);
        }

        int explosionCount = random.nextPoisson(expectedExplosionRate * dt);
        for (int i = 0; i < explosionCount; i++) {
            double t = within(now, dt, i, explosionCount, random);
            double m = GutenbergRichter.sample(
                    random, config.explosionBValue(), config.explosionMinMagnitude(), config.explosionMaxMagnitude());
            // centre of a block 0–9 blocks below the vent's ground block
            Point3 hypocenter = config.conduitTop().offset(0, -(random.nextInt(0, 10) + 0.5) * metersPerBlock, 0);
            transientAmplitude += emit(context, t, SeismicEventType.EXPLOSION, m, hypocenter, transientSeconds(1.0, m), false);
        }
        for (QueuedExplosion q : explosions) {
            transientAmplitude += emit(context, SeismicEventType.EXPLOSION, q.magnitude(), q.hypocenter(),
                    transientSeconds(1.0, q.magnitude()), false);
        }
        explosionCount += explosions.size();
        explosions.clear();

        if (!erupting && tremorActive(now)) {
            tremorUntil = now;
        }
        if (erupting && !tremorActive(now)) {
            // Tremor amplitude and how readily it sets in follow the magma flux.
            double onsetRate = config.tremorEpisodeRate() * Math.sqrt(eruptionRate);
            if (random.chance(1 - Math.exp(-onsetRate * dt))) {
                double seconds = random.nextExponential(1 / config.tremorMeanDurationSeconds());
                double duration = Math.max(0.05, seconds);
                tremorUntil = now + duration;
                tremorMagnitude = config.tremorBaseMagnitude() + 0.5 * Math.log10(1 + eruptionRate);
                emit(context, SeismicEventType.TREMOR, tremorMagnitude, hypocenter(random, 0.8, 1.0, 0.2), duration, false);
            }
        }

        double rsamDecay = Math.exp(-dt / config.rsamWindowSeconds());
        rsam = rsam * rsamDecay + transientAmplitude / config.rsamWindowSeconds();
        if (tremorActive(now)) {
            rsam += Math.pow(10, tremorMagnitude) * (1 - rsamDecay);
        }

        double rateDecay = Math.exp(-dt / config.rateWindowSeconds());
        double perMinute = 60 / config.rateWindowSeconds();
        vtRatePerMinute = vtRatePerMinute * rateDecay + vtCount * perMinute;
        lpRatePerMinute = lpRatePerMinute * rateDecay + lpCount * perMinute;
        explosionRatePerMinute = explosionRatePerMinute * rateDecay + explosionCount * perMinute;

        if (context.crossed(config.samplePeriodSeconds())) {
            context.outbox().emit(new RsamSample(now, config.volcanoId(), rsam, vtRatePerMinute, lpRatePerMinute,
                    explosionRatePerMinute, tremorActive(now), swarmActive(now)));
        }
    }

    /**
     * Queues VT events at given hypocentres (e.g. a propagating dike's tip); they are emitted with
     * swarm statistics on this model's next step and count towards VT rate and RSAM.
     */
    public void queueInducedVt(List<Point3> hypocenters) {
        induced.addAll(hypocenters);
    }

    /**
     * Queues an explosion quake for a discrete explosion of kinetic energy {@code energyJ} at
     * {@code hypocenter}. The radiated seismic energy is {@link #EXPLOSION_SEISMIC_EFFICIENCY} of the
     * kinetic energy, converted with {@code log10 E = 1.5 M + 4.8} (Gutenberg–Richter) and clamped to
     * the configured explosion magnitude range.
     */
    public void queueExplosion(Point3 hypocenter, double energyJ) {
        double seismic = Math.max(1, energyJ * EXPLOSION_SEISMIC_EFFICIENCY);
        double m = (Math.log10(seismic) - 4.8) / 1.5;
        m = Math.max(config.minMagnitude(), Math.min(config.explosionMaxMagnitude(), m));
        explosions.add(new QueuedExplosion(hypocenter, m));
    }

    private java.util.function.Consumer<SeismicEvent> quakeListener;

    /**
     * Receives every discrete earthquake (VT, LP, explosion quake) as it is emitted, e.g. for slopes to
     * feel the shaking. Called on this subsystem's step; the listener must only queue the event.
     */
    public void setQuakeListener(java.util.function.Consumer<SeismicEvent> listener) {
        this.quakeListener = listener;
    }

    /** Fraction of an explosion's kinetic energy radiated seismically (observed ~1e-5–1e-3). */
    public static final double EXPLOSION_SEISMIC_EFFICIENCY = 1e-4;

    private record QueuedExplosion(Point3 hypocenter, double magnitude) {}

    /** Expected VT rate (events/s) for the current magma state. */
    private double vtRate(double now) {
        double pressurisation = Math.max(0, magma.overpressureRateMPaPerSecond());
        double rate = config.backgroundVtRate() + config.vtPerMPa() * pressurisation * acceleration();
        if (swarmActive(now)) rate *= config.swarmRateMultiplier();
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
        double wet = clamp01(magma.ventWaterWt() / 4);
        return viscous * wet;
    }

    /**
     * Time of the {@code i}-th of {@code n} random events in the step {@code [now, now + dt)}: uniform
     * within its share of the step, so a long quiet step spreads its quakes over its length, in order.
     */
    private static double within(double now, double dt, int i, int n, SimRandom random) {
        return now + dt * (i + random.nextDouble()) / n;
    }

    private double emit(StepContext context, SeismicEventType type, double magnitude, Point3 hypocenter, double durationSeconds, boolean swarm) {
        return emit(context, context.time(), type, magnitude, hypocenter, durationSeconds, swarm);
    }

    private double emit(StepContext context, double time, SeismicEventType type, double magnitude, Point3 hypocenter,
            double durationSeconds, boolean swarm) {
        SeismicEvent event = new SeismicEvent(time, config.volcanoId(), type, magnitude, hypocenter, durationSeconds, swarm);
        context.outbox().emit(event);
        if (quakeListener != null && type != SeismicEventType.TREMOR) quakeListener.accept(event);
        return event.amplitude();
    }

    private static double transientSeconds(double baseSeconds, double magnitude) {
        return Math.max(0.1, baseSeconds + 0.5 * Math.max(0, magnitude));
    }

    /**
     * Picks a hypocentre along the chamber → conduit-top axis at fraction {@code t ∈ [tMin, tMax]}
     * (0 = chamber, 1 = vent), skewed towards {@code tMin}, with lateral scatter scaled by
     * {@code spreadScale}.
     */
    private Point3 hypocenter(SimRandom random, double tMin, double tMax, double spreadScale) {
        Point3 bottom = magma.chamberCenter();
        Point3 top = config.conduitTop();
        double u = random.nextDouble();
        double t = tMin + (tMax - tMin) * u * u;
        double spread = config.hypocenterSpread() * spreadScale * (1 - 0.5 * t) * metersPerBlock; // spread is in blocks
        double x = bottom.x() + t * (top.x() - bottom.x()) + random.nextGaussian() * spread;
        double y = bottom.y() + t * (top.y() - bottom.y()) + random.nextGaussian() * spread * 0.5;
        double z = bottom.z() + t * (top.z() - bottom.z()) + random.nextGaussian() * spread;
        return new Point3(x, y, z); // continuous: no rounding to blocks
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
        return swarmActive(lastTime);
    }

    public boolean tremorActive() {
        return tremorActive(lastTime);
    }

    /** Equivalent magnitude of the current tremor episode (meaningful while {@link #tremorActive()}). */
    public double tremorMagnitude() {
        return tremorMagnitude;
    }

    private boolean swarmActive(double now) {
        return now < swarmUntil;
    }

    private boolean tremorActive(double now) {
        return now < tremorUntil;
    }

    // ── Persistence ──

    @Override
    public void saveState(StateWriter writer) {
        JsonObject out = writer.json();
        out.addProperty("rsam", rsam);
        out.addProperty("vtRatePerMinute", vtRatePerMinute);
        out.addProperty("lpRatePerMinute", lpRatePerMinute);
        out.addProperty("explosionRatePerMinute", explosionRatePerMinute);
        out.addProperty("swarmUntil", swarmUntil);
        out.addProperty("tremorUntil", tremorUntil);
        out.addProperty("tremorMagnitude", tremorMagnitude);
        out.addProperty("expectedVtRate", expectedVtRate);
        out.addProperty("expectedLpRate", expectedLpRate);
        out.addProperty("expectedExplosionRate", expectedExplosionRate);
        out.addProperty("lastTime", lastTime);
        JsonArray pendingInduced = new JsonArray();
        for (Point3 p : induced) pendingInduced.add(xyz(p));
        out.add("induced", pendingInduced);
        JsonArray pendingExplosions = new JsonArray();
        for (QueuedExplosion q : explosions) {
            JsonObject o = new JsonObject();
            o.add("at", xyz(q.hypocenter()));
            o.addProperty("m", q.magnitude());
            pendingExplosions.add(o);
        }
        out.add("explosions", pendingExplosions);
    }

    @Override
    public void loadState(StateReader reader) {
        JsonObject in = reader.json();
        rsam = in.get("rsam").getAsDouble();
        vtRatePerMinute = in.get("vtRatePerMinute").getAsDouble();
        lpRatePerMinute = in.get("lpRatePerMinute").getAsDouble();
        explosionRatePerMinute = in.get("explosionRatePerMinute").getAsDouble();
        swarmUntil = in.get("swarmUntil").getAsDouble();
        tremorUntil = in.get("tremorUntil").getAsDouble();
        tremorMagnitude = in.get("tremorMagnitude").getAsDouble();
        expectedVtRate = in.get("expectedVtRate").getAsDouble();
        expectedLpRate = in.get("expectedLpRate").getAsDouble();
        expectedExplosionRate = in.get("expectedExplosionRate").getAsDouble();
        lastTime = in.get("lastTime").getAsDouble();
        induced.clear();
        if (in.has("induced")) {
            for (JsonElement e : in.getAsJsonArray("induced")) induced.add(point(e));
        }
        explosions.clear();
        if (in.has("explosions")) {
            for (JsonElement e : in.getAsJsonArray("explosions")) {
                JsonObject o = e.getAsJsonObject();
                explosions.add(new QueuedExplosion(point(o.get("at")), o.get("m").getAsDouble()));
            }
        }
    }

    private static JsonArray xyz(Point3 p) {
        JsonArray a = new JsonArray();
        a.add(p.x());
        a.add(p.y());
        a.add(p.z());
        return a;
    }

    /** A saved hypocentre: {@code [x, y, z]} in metres, or (older saves) a packed block position. */
    private Point3 point(JsonElement e) {
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            return new Point3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
        }
        return Point3.ofBlock(BlockPos.unpack(e.getAsLong()), metersPerBlock);
    }
}
