package me.alex4386.typhon.engine.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.alert.AlertEvents.AlertLevelChanged;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionStarted;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.seismic.SeismicConfig;
import me.alex4386.typhon.engine.seismic.SeismicEvent;
import me.alex4386.typhon.engine.seismic.SeismicEventType;
import me.alex4386.typhon.engine.seismic.SeismicityModel;
import me.alex4386.typhon.engine.sim.Engine;
import org.junit.jupiter.api.Test;
import me.alex4386.typhon.engine.testing.Saves;

/** Chamber, seismicity and alert estimation coupled with default parameters. */
class VolcanoLifecycleTest {
    private static final BlockPos CHAMBER = new BlockPos(0, -40, 0);
    private static final BlockPos VENT = new BlockPos(0, 100, 0);

    private record Volcano(Engine engine, MagmaChamber chamber, SeismicityModel seismic, AlertLevelEstimator alert) {}

    private static Volcano build(long seed) {
        // 1 km³: the small end of real chambers, so a full unrest → eruption cycle fits in a test.
        MagmaChamber chamber = new MagmaChamber(MagmaChamberConfig.builder("v", CHAMBER).volume(1e9).build());
        SeismicityModel seismic = new SeismicityModel(SeismicConfig.builder("v", VENT).build(), chamber);
        AlertLevelEstimator alert = new AlertLevelEstimator(AlertConfig.defaults("v"), chamber, seismic);
        Engine engine = Engine.builder(seed).add(chamber).add(seismic).add(alert).build();
        return new Volcano(engine, chamber, seismic, alert);
    }

    @Test
    void unrestEscalatesThroughTheAlertLevelsBeforeTheEruption() {
        Volcano volcano = build(1);
        List<AlertLevelChanged> changes = new ArrayList<>();
        List<SeismicEvent> quakes = new ArrayList<>();
        EruptionStarted started = null;

        for (int i = 0; i < 20 * 3600 * 2 && started == null; i++) {
            for (EngineEvent e : volcano.engine().step().events()) {
                if (e instanceof AlertLevelChanged c) changes.add(c);
                else if (e instanceof SeismicEvent s) quakes.add(s);
                else if (e instanceof EruptionStarted s) started = s;
            }
        }

        assertTrue(started != null, "the default system erupts within two hours of play");
        List<AlertLevel> path = changes.stream().map(AlertLevelChanged::current).toList();
        assertEquals(AlertLevel.ERUPTING, path.get(path.size() - 1));
        assertEquals(AlertLevel.ERUPTION_IMMINENT, path.get(path.size() - 2), "an imminent warning precedes the eruption");
        assertTrue(path.contains(AlertLevel.MAJOR_ACTIVITY));
        assertTrue(path.indexOf(AlertLevel.MAJOR_ACTIVITY) < path.indexOf(AlertLevel.ERUPTION_IMMINENT));

        double eruptionTime = started.time();
        double window = 600;
        long early = quakes.stream().filter(q -> q.type() == SeismicEventType.VT && q.time() < window).count();
        long late = quakes.stream().filter(q -> q.type() == SeismicEventType.VT && q.time() >= eruptionTime - window).count();
        assertTrue(late > early * 3, "VT seismicity accelerates before failure (early=" + early + ", late=" + late + ")");
    }

    @Test
    void wholeSystemResumesBitForBitAcrossAnEruption() {
        Volcano reference = build(42);
        for (int i = 0; i < 20 * 3000; i++) reference.engine().step();

        Volcano resumed = build(42);
        Engine restored = Engine.builder(42)
                .add(resumed.chamber())
                .add(resumed.seismic())
                .add(resumed.alert())
                .restore(Saves.save(reference.engine()))
                .build();

        for (int i = 0; i < 20 * 1800; i++) {
            assertEquals(reference.engine().step(), restored.step());
        }
        assertTrue(reference.chamber().erupting() || reference.chamber().eruptedVolume() > 0, "window covers an eruption");
        assertEquals(reference.alert().level(), resumed.alert().level());
    }
}
