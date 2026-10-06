package me.alex4386.typhon.engine.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.alert.AlertEvents.AlertLevelChanged;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.seismic.SeismicConfig;
import me.alex4386.typhon.engine.seismic.SeismicityModel;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.testing.StubMagmaState;
import org.junit.jupiter.api.Test;
import me.alex4386.typhon.engine.testing.Saves;

class AlertLevelEstimatorTest {
    private static final double STRENGTH = 15;

    private static List<AlertLevelChanged> advance(Engine engine, double seconds) {
        List<AlertLevelChanged> changes = new ArrayList<>();
        for (int i = 0; i < Math.round(seconds * 20); i++) {
            for (EngineEvent e : engine.step().events()) {
                if (e instanceof AlertLevelChanged c) changes.add(c);
            }
        }
        return changes;
    }

    private static Engine engine(AlertLevelEstimator estimator) {
        return Engine.builder(0).add(estimator).build();
    }

    @Test
    void levelsFollowOverpressureAndEruption() {
        StubMagmaState magma = StubMagmaState.basalt();
        AlertLevelEstimator estimator = new AlertLevelEstimator(AlertConfig.defaults("v").withStepPeriodSeconds(1), magma, null);
        Engine engine = engine(estimator);
        assertNull(estimator.level());

        List<AlertLevelChanged> first = advance(engine, 1);
        assertEquals(AlertLevel.DORMANT, estimator.level());
        assertNull(first.get(0).previous());

        double[] ratios = {0.35, 0.65, 0.95};
        AlertLevel[] expected = {AlertLevel.MINOR_ACTIVITY, AlertLevel.MAJOR_ACTIVITY, AlertLevel.ERUPTION_IMMINENT};
        for (int i = 0; i < ratios.length; i++) {
            magma.overpressure = ratios[i] * STRENGTH;
            advance(engine, 1);
            assertEquals(expected[i], estimator.level(), "upgrades are immediate");
        }

        magma.eruptionRate = 10;
        advance(engine, 1);
        assertEquals(AlertLevel.ERUPTING, estimator.level());
    }

    @Test
    void hysteresisPreventsFlickerAroundAThreshold() {
        StubMagmaState magma = StubMagmaState.basalt();
        magma.overpressure = 0.65 * STRENGTH;
        AlertLevelEstimator estimator = new AlertLevelEstimator(AlertConfig.defaults("v").withStepPeriodSeconds(1), magma, null);
        Engine engine = engine(estimator);

        List<AlertLevelChanged> changes = new ArrayList<>(advance(engine, 1));
        for (int i = 0; i < 60; i++) {
            magma.overpressure = (i % 2 == 0 ? 0.58 : 0.62) * STRENGTH;
            changes.addAll(advance(engine, 10));
        }
        assertEquals(1, changes.size(), "only the initial estimate");
        assertEquals(AlertLevel.MAJOR_ACTIVITY, estimator.level());
    }

    @Test
    void downgradesStepOneLevelPerDwell() {
        StubMagmaState magma = StubMagmaState.basalt();
        magma.overpressure = 0.65 * STRENGTH;
        AlertLevelEstimator estimator = new AlertLevelEstimator(AlertConfig.defaults("v").withStepPeriodSeconds(1), magma, null);
        Engine engine = engine(estimator);
        advance(engine, 1);

        magma.overpressure = 0.05 * STRENGTH;
        advance(engine, 100);
        assertEquals(AlertLevel.MAJOR_ACTIVITY, estimator.level());
        advance(engine, 30);
        assertEquals(AlertLevel.MINOR_ACTIVITY, estimator.level());
        advance(engine, 100);
        assertEquals(AlertLevel.MINOR_ACTIVITY, estimator.level());
        advance(engine, 30);
        assertEquals(AlertLevel.DORMANT, estimator.level());

        // A renewed rise cancels a pending downgrade and upgrades at once.
        magma.overpressure = 0.95 * STRENGTH;
        advance(engine, 1);
        assertEquals(AlertLevel.ERUPTION_IMMINENT, estimator.level());
    }

    @Test
    void endedEruptionWindsDownGradually() {
        StubMagmaState magma = StubMagmaState.basalt();
        magma.eruptionRate = 10;
        AlertLevelEstimator estimator = new AlertLevelEstimator(AlertConfig.defaults("v").withStepPeriodSeconds(1).withDowngradeDwellSeconds(10), magma, null);
        Engine engine = engine(estimator);
        advance(engine, 1);
        assertEquals(AlertLevel.ERUPTING, estimator.level());

        magma.eruptionRate = 0;
        List<AlertLevelChanged> changes = advance(engine, 60);
        List<AlertLevel> path = changes.stream().map(AlertLevelChanged::current).toList();
        assertEquals(List.of(AlertLevel.ERUPTION_IMMINENT, AlertLevel.MAJOR_ACTIVITY, AlertLevel.MINOR_ACTIVITY,
                AlertLevel.DORMANT), path);
    }

    @Test
    void crystallisedQuietSystemIsExtinct() {
        StubMagmaState magma = StubMagmaState.basalt();
        magma.crystals = 0.58;
        AlertLevelEstimator estimator = new AlertLevelEstimator(AlertConfig.defaults("v").withStepPeriodSeconds(1), magma, null);
        advance(engine(estimator), 1);
        assertEquals(AlertLevel.EXTINCT, estimator.level());
    }

    @Test
    void seismicityAloneCanRaiseTheAlert() {
        StubMagmaState magma = StubMagmaState.basalt();
        magma.overpressureRate = 0.3; // fast pressurisation, but overpressure itself still low
        SeismicityModel seismic = new SeismicityModel(
                SeismicConfig.builder("v", new BlockPos(0, 100, 0)).swarmTriggerProbability(0).build(), magma);
        AlertLevelEstimator estimator = new AlertLevelEstimator(AlertConfig.defaults("v").withStepPeriodSeconds(1), magma, seismic);
        Engine engine = Engine.builder(1).add(seismic).add(estimator).build();

        advance(engine, 600);
        assertEquals(AlertLevel.ERUPTION_IMMINENT, estimator.level());
    }

    @Test
    void pendingDowngradeSurvivesSaveAndRestore() {
        StubMagmaState magma = StubMagmaState.basalt();
        magma.overpressure = 0.65 * STRENGTH;
        AlertLevelEstimator original = new AlertLevelEstimator(AlertConfig.defaults("v").withStepPeriodSeconds(1), magma, null);
        Engine engine = engine(original);
        advance(engine, 1);
        magma.overpressure = 0;
        advance(engine, 100);

        AlertLevelEstimator restored = new AlertLevelEstimator(AlertConfig.defaults("v").withStepPeriodSeconds(1), magma, null);
        Engine resumed = Engine.builder(0).add(restored)
                .restore(Saves.save(engine)).build();
        assertEquals(AlertLevel.MAJOR_ACTIVITY, restored.level());
        for (int i = 0; i < 20 * 300; i++) assertEquals(engine.step(), resumed.step());
        assertEquals(original.level(), restored.level());
    }
}
