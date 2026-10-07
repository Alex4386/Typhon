package me.alex4386.typhon.engine.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.alert.AlertEvents.AlertLevelChanged;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.magma.MagmaEvents;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionStarted;
import me.alex4386.typhon.engine.save.SaveStore;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.testing.TestGround;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.seismic.SeismicEvent;
import me.alex4386.typhon.engine.seismic.SeismicEventType;
import me.alex4386.typhon.engine.sim.Engine;
import org.junit.jupiter.api.Test;
import me.alex4386.typhon.engine.testing.Saves;

/**
 * A placed chamber with default parameters and the whole system around it: recharge, unrest seismicity and
 * alert levels, the walls failing, the dike that breaks out and the eruption from its fissure.
 */
class VolcanoLifecycleTest {
    /** 20 m columns of flat ground at 100 m over ±2.5 km. */
    private static final double COLUMN_M = 20;
    private static final int HALF = 125;
    private static final double GROUND_Z = 100;
    private static final Point3 CHAMBER = new Point3(0, GROUND_Z - 4000, 0);

    private record Volcano(Engine engine, VolcanoSystem system) {
        MagmaChamber chamber() {
            return system.chamber();
        }

        AlertLevelEstimator alert() {
            return system.alert();
        }
    }

    private static Volcano build(long seed, SaveStore restore) {
        TerrainModel terrain = TestGround.terrain(COLUMN_M);
        LavaFlow lava = new LavaFlow(terrain);
        // 1 km³: the small end of real chambers, so a full unrest → eruption cycle fits in a test. No vent and
        // no conduit: the magma comes out through a dike once the walls fail.
        VolcanoSystem system = VolcanoSystem.builder("v", List.of(), terrain, lava)
                .chamber(MagmaChamberConfig.builder("v", CHAMBER).volume(1e9).lithostaticDepth(4000).build())
                .dikesEnabled(true)
                .geothermalEnabled(false)
                .massFlowsEnabled(false)
                .geomorphologyEnabled(false)
                .build();
        Engine.Builder builder = Engine.builder(seed).adaptive(Engine.DEFAULT_MAX_STEP_SECONDS).add(terrain);
        system.addTo(builder).add(lava);
        if (restore != null) builder.restore(restore);
        Engine engine = builder.build();
        if (restore == null) {
            engine.submit(TestGround.columns(-HALF, -HALF, HALF - 1, HALF - 1, (x, z) -> GROUND_Z, TestGround.DRY));
        }
        return new Volcano(engine, system);
    }

    private static Volcano build(long seed) {
        return build(seed, null);
    }

    @Test
    void unrestEscalatesThroughTheAlertLevelsBeforeTheEruption() {
        Volcano volcano = build(1);
        List<AlertLevelChanged> changes = new ArrayList<>();
        List<SeismicEvent> quakes = new ArrayList<>();
        EruptionStarted started = null;

        while (volcano.engine().time() < HORIZON && started == null) {
            for (EngineEvent e : volcano.engine().step().events()) {
                if (e instanceof AlertLevelChanged c) changes.add(c);
                else if (e instanceof SeismicEvent s) quakes.add(s);
                else if (e instanceof EruptionStarted s) started = s;
            }
        }

        assertTrue(started != null, "the default system erupts within " + HORIZON / YEAR + " years");
        assertEquals(MagmaEvents.Cause.DIKE, started.cause(), "through the dike that broke out of the walls");
        List<AlertLevel> path = changes.stream().map(AlertLevelChanged::current).toList();
        assertEquals(AlertLevel.ERUPTING, path.get(path.size() - 1));
        assertEquals(AlertLevel.ERUPTION_IMMINENT, path.get(path.size() - 2), "an imminent warning precedes the eruption");
        assertTrue(path.contains(AlertLevel.MAJOR_ACTIVITY));
        assertTrue(path.indexOf(AlertLevel.MAJOR_ACTIVITY) < path.indexOf(AlertLevel.ERUPTION_IMMINENT));

        double eruptionTime = started.time();
        double window = 30 * 86_400; // a month at either end of the year-long recharge
        long early = quakes.stream().filter(q -> q.type() == SeismicEventType.VT && q.time() < window).count();
        long late = quakes.stream().filter(q -> q.type() == SeismicEventType.VT && q.time() >= eruptionTime - window).count();
        assertTrue(late > early * 3, "VT seismicity accelerates before failure (early=" + early + ", late=" + late + ")");
    }

    static final double YEAR = 3.156e7;
    /** Recharge to wall failure (twice the rock's tensile strength) takes the default supply about two years. */
    static final double HORIZON = 4 * YEAR;

    /** Time of the first eruption onset of a fresh system with {@code seed} (within the horizon). */
    private static double onset(long seed) {
        Volcano probe = build(seed);
        while (probe.engine().time() < HORIZON) {
            for (EngineEvent e : probe.engine().step().events()) if (e instanceof EruptionStarted s) return s.time();
        }
        throw new AssertionError("no eruption within the horizon");
    }

    @Test
    void wholeSystemResumesBitForBitAcrossAnEruption() {
        double onset = onset(42);
        Volcano reference = build(42);
        // Saved a day before the onset (long quiet steps), resumed through it (short eruptive ones).
        while (reference.engine().time() < onset - 86_400) reference.engine().step();

        Volcano resumed = build(42, Saves.save(reference.engine()));
        Engine restored = resumed.engine();

        while (reference.engine().time() < onset + 3600) {
            assertEquals(reference.engine().step(), restored.step());
        }
        assertTrue(reference.chamber().erupting() || reference.chamber().eruptedVolume() > 0, "window covers an eruption");
        assertEquals(reference.alert().level(), resumed.alert().level());
    }
}
