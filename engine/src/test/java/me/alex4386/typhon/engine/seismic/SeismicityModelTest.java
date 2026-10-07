package me.alex4386.typhon.engine.seismic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.random.SimRandom;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.testing.StubMagmaState;
import org.junit.jupiter.api.Test;
import me.alex4386.typhon.engine.testing.Saves;
import me.alex4386.typhon.engine.save.InMemorySaveStore;

class SeismicityModelTest {
    private static final Point3 VENT = new Point3(0, 100, 0);

    private static SeismicConfig.Builder config() {
        return SeismicConfig.builder("v", VENT);
    }

    private static List<SeismicEvent> run(Engine engine, int seconds) {
        List<SeismicEvent> events = new ArrayList<>();
        for (int i = 0; i < seconds * 20; i++) {
            for (EngineEvent event : engine.step().events()) {
                if (event instanceof SeismicEvent s) events.add(s);
            }
        }
        return events;
    }

    /** Advances exactly one model step (the model steps every 10 ticks). */
    private static void stepOnce(Engine engine) {
        for (int i = 0; i < 10; i++) engine.step();
    }

    private static long count(List<SeismicEvent> events, SeismicEventType type) {
        return events.stream().filter(e -> e.type() == type).count();
    }

    @Test
    void gutenbergRichterBValueIsRecoverable() {
        SimRandom random = new SimRandom(1);
        for (double b : new double[] {0.8, 1.0, 1.5}) {
            int n = 100_000;
            double sum = 0;
            for (int i = 0; i < n; i++) {
                double m = GutenbergRichter.sample(random, b, 0, 9);
                assertTrue(m >= 0 && m <= 9);
                sum += m;
            }
            assertEquals(b, GutenbergRichter.estimateBValue(sum / n, 0), 0.03, "b=" + b);
        }
    }

    @Test
    void truncationIsRespected() {
        SimRandom random = new SimRandom(2);
        for (int i = 0; i < 50_000; i++) {
            double m = GutenbergRichter.sample(random, 0.5, 1, 2);
            assertTrue(m >= 1 && m <= 2);
        }
    }

    @Test
    void vtRateGrowsWithPressurisationAndAcceleratesTowardFailure() {
        StubMagmaState magma = StubMagmaState.basalt();
        SeismicityModel model = new SeismicityModel(config().swarmTriggerProbability(0).build(), magma);
        Engine engine = Engine.builder(3).add(model).build();

        stepOnce(engine);
        double quiet = model.expectedVtRate();

        magma.overpressureRate = 1e-4; // ~9 MPa a day: a fast pressurisation
        magma.overpressure = 3;
        stepOnce(engine);
        double pressurising = model.expectedVtRate();

        magma.overpressure = 13.5;
        stepOnce(engine);
        double nearFailure = model.expectedVtRate();

        assertTrue(pressurising > quiet * 10);
        assertTrue(nearFailure > pressurising * 5);
        assertEquals(10, model.acceleration(), 1e-9);
        magma.overpressure = 20;
        assertEquals(20, model.acceleration(), 1e-9);
        magma.overpressure = -1;
        assertEquals(1, model.acceleration(), 1e-9);
    }

    @Test
    void observedVtCountsAndSmoothedRateMatchTheModel() {
        StubMagmaState magma = StubMagmaState.basalt();
        magma.overpressureRate = 0.02;
        SeismicityModel model = new SeismicityModel(config().swarmTriggerProbability(0).build(), magma);
        Engine engine = Engine.builder(4).add(model).build();

        List<SeismicEvent> events = run(engine, 3600);
        double expectedPerSecond = model.expectedVtRate();
        assertEquals(expectedPerSecond * 3600, count(events, SeismicEventType.VT), 4 * Math.sqrt(expectedPerSecond * 3600));
        assertEquals(expectedPerSecond * 60, model.vtRatePerMinute(), expectedPerSecond * 60 * 0.5);
    }

    @Test
    void quietVolcanoIsNearlySilent() {
        SeismicityModel model = new SeismicityModel(config().build(), StubMagmaState.basalt());
        Engine engine = Engine.builder(5).add(model).build();
        List<SeismicEvent> events = run(engine, 600);
        assertTrue(events.size() < 5);
        assertTrue(model.rsam() < 1);
    }

    @Test
    void eruptionsProduceLpTremorAndHighRsam() {
        StubMagmaState magma = StubMagmaState.basalt();
        magma.eruptionRate = 25;
        SeismicityModel model = new SeismicityModel(config().build(), magma);
        Engine engine = Engine.builder(6).add(model).build();

        List<SeismicEvent> events = run(engine, 1800);
        assertTrue(count(events, SeismicEventType.LP) > 100);
        assertTrue(count(events, SeismicEventType.TREMOR) >= 3);
        assertEquals(0, count(events, SeismicEventType.EXPLOSION), "fluid basalt does not explode");
        assertTrue(model.rsam() > 2);
        events.stream().filter(e -> e.type() == SeismicEventType.TREMOR)
                .forEach(e -> assertTrue(e.durationSeconds() > 0));

        magma.eruptionRate = 0;
        run(engine, 30);
        assertTrue(!model.tremorActive(), "tremor stops with the eruption");
    }

    @Test
    void viscousWetMagmaProducesExplosionQuakes() {
        StubMagmaState magma = StubMagmaState.wetDacite();
        magma.eruptionRate = 5;
        assertTrue(SeismicityModel.explosivity(magma) > 0.5);
        assertTrue(SeismicityModel.explosivity(StubMagmaState.basalt()) < 0.05);

        SeismicityModel model = new SeismicityModel(config().build(), magma);
        Engine engine = Engine.builder(7).add(model).build();
        List<SeismicEvent> events = run(engine, 1200);
        List<SeismicEvent> explosions = events.stream().filter(e -> e.type() == SeismicEventType.EXPLOSION).toList();
        assertTrue(explosions.size() > 10);
        for (SeismicEvent e : explosions) {
            assertTrue(e.magnitude() >= 1 && e.magnitude() <= 3.5);
            assertTrue(e.hypocenter().y() <= VENT.y() && e.hypocenter().y() > VENT.y() - 100);
        }
    }

    @Test
    void hypocentresSitBetweenChamberAndVentWithLpShallowerThanVt() {
        StubMagmaState magma = StubMagmaState.basalt();
        magma.overpressureRate = 0.05;
        magma.eruptionRate = 10;
        SeismicityModel model = new SeismicityModel(config().build(), magma);
        Engine engine = Engine.builder(8).add(model).build();
        List<SeismicEvent> events = run(engine, 1800);

        double vtDepth = events.stream().filter(e -> e.type() == SeismicEventType.VT)
                .mapToDouble(e -> e.hypocenter().y()).average().orElseThrow();
        double lpDepth = events.stream().filter(e -> e.type() == SeismicEventType.LP)
                .mapToDouble(e -> e.hypocenter().y()).average().orElseThrow();
        assertTrue(vtDepth > magma.center.y() - 100 && vtDepth < VENT.y());
        assertTrue(lpDepth > vtDepth + 400, "LP events are shallow");
    }

    @Test
    void swarmsBoostRatesAndAreRichInSmallEvents() {
        StubMagmaState magma = StubMagmaState.basalt();
        magma.overpressureRate = 1e-4; // ~9 MPa a day: a fast pressurisation
        SeismicityModel model = new SeismicityModel(config().swarmTriggerProbability(1).swarmMeanDurationSeconds(600).build(), magma);
        Engine engine = Engine.builder(9).add(model).build();
        List<SeismicEvent> events = run(engine, 3600);
        List<SeismicEvent> swarm = events.stream().filter(SeismicEvent::swarm).toList();
        assertTrue(swarm.size() > 50, "swarms occurred");
        double mean = swarm.stream().mapToDouble(SeismicEvent::magnitude).average().orElseThrow();
        assertEquals(1.5, GutenbergRichter.estimateBValue(mean, 0), 0.3);
    }

    @Test
    void intensityDecaysWithDistanceAndGrowsWithMagnitude() {
        Point3 source = Point3.ORIGIN;
        double near = SeismicIntensity.intensityAt(3, source, new Point3(50, 0, 0));
        double mid = SeismicIntensity.intensityAt(3, source, new Point3(1000, 0, 0));
        double far = SeismicIntensity.intensityAt(3, source, new Point3(20000, 0, 0));
        assertTrue(near > mid && mid > far);
        assertTrue(SeismicIntensity.intensityAt(4, source, new Point3(1000, 0, 0)) > mid);
        assertEquals(1, SeismicIntensity.intensityAt(5, source, source));
        assertEquals(0, SeismicIntensity.intensityAt(0, source, new Point3(50000, 0, 0)));
        assertEquals(SeismicIntensity.amplitudeAt(2, source, new Point3(30, 0, 0)),
                SeismicIntensity.amplitudeAt(2, source, new Point3(90, 0, 0)), 1e-12, "near-field saturation");
    }

    @Test
    void deterministicAndResumable() {
        StubMagmaState magma = StubMagmaState.wetDacite();
        magma.overpressureRate = 1e-4; // ~9 MPa a day: a fast pressurisation
        magma.overpressure = 10;
        magma.eruptionRate = 3;

        Engine a = Engine.builder(10).add(new SeismicityModel(config().build(), magma)).build();
        Engine b = Engine.builder(10).add(new SeismicityModel(config().build(), magma)).build();
        for (int i = 0; i < 20 * 300; i++) assertEquals(a.step(), b.step());

        InMemorySaveStore saved = Saves.save(a);
        Engine resumed = Engine.builder(10).add(new SeismicityModel(config().build(), magma))
                .restore(saved).build();
        List<EngineFrame> expected = new ArrayList<>();
        List<EngineFrame> actual = new ArrayList<>();
        for (int i = 0; i < 20 * 300; i++) {
            expected.add(a.step());
            actual.add(resumed.step());
        }
        assertEquals(expected, actual);
    }

    @Test
    void emitsPeriodicRsamSamples() {
        SeismicityModel model = new SeismicityModel(config().samplePeriodSeconds(5).build(), StubMagmaState.basalt());
        Engine engine = Engine.builder(11).add(model).build();
        int samples = 0;
        for (int i = 0; i < 1000; i++) {
            for (EngineEvent e : engine.step().events()) if (e instanceof RsamSample) samples++;
        }
        assertEquals(10, samples);
    }
}
