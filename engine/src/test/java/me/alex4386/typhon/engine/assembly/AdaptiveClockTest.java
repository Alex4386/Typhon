package me.alex4386.typhon.engine.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.magma.ConduitConfig;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionStarted;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.save.SaveStore;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.sim.EngineRunner;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.testing.Saves;
import org.junit.jupiter.api.Test;

/**
 * One physical clock with adaptive steps: a quiet volcano takes long steps, an erupting one short
 * ones, and the choice depends on the state only, so runs are deterministic, thread-invariant and
 * resume bit for bit across a change of step length.
 */
class AdaptiveClockTest {
    static final double YEAR = 3.156e7;

    record Run(Engine engine, VolcanoSystem volcano, TerrainModel terrain) {}

    static Run build(long seed, MagmaChamberConfig chamber, int threads, SaveStore restore) {
        return build(seed, chamber, threads, restore, true);
    }

    static Run build(long seed, MagmaChamberConfig chamber, int threads, SaveStore restore, boolean geothermal) {
        TerrainModel terrain = new TerrainModel();
        LavaFlow lava = new LavaFlow(terrain);
        VolcanoSystem volcano = VolcanoSystem.builder("test", List.of(VolcanoSystemTest.CRATER), terrain, lava)
                .chamber(chamber)
                .dikesEnabled(true)
                .geothermalEnabled(geothermal)
                .build();
        Engine.Builder builder = Engine.builder(seed).adaptive(86_400).threads(threads).add(terrain);
        volcano.addTo(builder).add(lava);
        if (restore != null) builder.restore(restore);
        Engine engine = builder.build();
        if (restore == null) engine.submit(VolcanoSystemTest.cone());
        return new Run(engine, volcano, terrain);
    }

    /**
     * The test basalt with its conduit frozen solid, 0.1 MPa below the pressure that ruptures its walls: about
     * two weeks of quiet recharge, then a dike breaks out and opens a flank eruption.
     */
    static MagmaChamberConfig slowBasalt() {
        MagmaChamberConfig solid = VolcanoSystemTest.basalt().toBuilder().conduit(ConduitConfig.DEFAULT).build();
        return solid.toBuilder().initialOverpressureMPa(MagmaChamber.ruptureCap(solid) - 0.1).build();
    }

    /** A chamber far below failure: recharges quietly for years. */
    static MagmaChamberConfig quiet() {
        return MagmaChamberConfig.builder("test", new Point3(0, -3000, 0))
                .initialOverpressureMPa(2)
                .supplyVariability(0)
                .build();
    }

    /** The slow basalt's dike opens its flank eruption at ≈ 1 167 300 s (13.5 days); this is minutes into it. */
    static final double PAST_ONSET = 1_167_600;

    static List<EngineFrame> until(Engine engine, double time) {
        List<EngineFrame> frames = new ArrayList<>();
        while (engine.time() < time) frames.add(engine.step());
        return frames;
    }

    @Test
    void aQuietYearTakesLongStepsAndRunsFast() {
        Run r = build(1, quiet(), 1, null, false);
        long t0 = System.nanoTime();
        List<EngineFrame> frames = new ArrayList<>();
        while (r.engine().time() < YEAR) {
            frames.add(r.engine().step());
            if (frames.size() % 200 == 0) {
                System.out.printf("CLOCK quiet t=%.0f s after %d steps (%.1f s wall), surface %s, limits %s%n", r.engine().time(),
                        frames.size(), (System.nanoTime() - t0) / 1e9,
                        r.volcano().subsurface() == null ? "-" : java.util.Arrays.toString(r.volcano().subsurface().surfaceRoutingStats()),
                        r.engine().stepLimits());
            }
        }
        double wall = (System.nanoTime() - t0) / 1e9;
        System.out.printf("CLOCK quiet year: %d steps, %.2f s wall, last step %.0f s%n",
                frames.size(), wall, r.engine().lastStepSeconds());

        assertFalse(r.volcano().chamber().erupting());
        // 50 ms ticks would be 6.3e8 steps; quiet steps of most of a day keep a year to hundreds.
        assertTrue(frames.size() < 1500, "steps in a quiet year: " + frames.size());
        assertTrue(r.engine().lastStepSeconds() >= 40_000, "quiet step " + r.engine().lastStepSeconds() + " s");
        assertTrue(wall < 120, "a quiet year took " + wall + " s");
    }

    @Test
    void anEruptionRunsAtFineSteps() {
        Run r = build(1, slowBasalt(), 1, null);
        double start = Double.NaN;
        while (r.engine().time() < 60 * 86400) {
            EngineFrame f = r.engine().step();
            if (f.events().stream().anyMatch(e -> e instanceof EruptionStarted)) {
                start = r.engine().time();
                break;
            }
        }
        System.out.printf("CLOCK basalt 0.1 MPa below wall rupture erupts after %.0f s%n", start);
        assertFalse(Double.isNaN(start), "the chamber should fail within two months");

        long t0 = System.nanoTime();
        List<EngineFrame> hour = until(r.engine(), start + 3600);
        double wall = (System.nanoTime() - t0) / 1e9;
        double longest = 0;
        for (int i = 1; i < hour.size(); i++) {
            longest = Math.max(longest, (hour.get(i).timeMicros() - hour.get(i - 1).timeMicros()) / 1e6);
        }
        System.out.printf("CLOCK eruption hour: %d steps, longest %.2f s, %.2f s wall%n", hour.size(), longest, wall);

        assertTrue(r.volcano().chamber().erupting());
        assertTrue(longest <= 20, "eruption steps stay at most 20 s: " + longest);
        assertTrue(hour.size() >= 180, "an eruption hour takes many steps: " + hour.size());
    }

    @Test
    void adaptiveStepsAreDeterministicAndThreadInvariant() {
        double end = PAST_ONSET;
        List<EngineFrame> a = until(build(5, slowBasalt(), 1, null).engine(), end);
        List<EngineFrame> b = until(build(5, slowBasalt(), 1, null).engine(), end);
        List<EngineFrame> c = until(build(5, slowBasalt(), 4, null).engine(), end);
        assertTrue(a.stream().anyMatch(f -> f.events().stream().anyMatch(e -> e instanceof EruptionStarted)),
                "the window covers the onset");
        assertEquals(a, b);
        assertEquals(a, c);
    }

    /** Frames a runner produces up to {@code end} (s) in {@code mode}, switching speed when an eruption starts. */
    static List<EngineFrame> played(EngineRunner.Mode mode, double speed, double eruptionSpeed, double end) throws Exception {
        Run r = build(3, VolcanoSystemTest.basalt(), 1, null);
        EngineRunner runner = new EngineRunner(r.engine(),
                EngineRunner.Options.defaults().withMode(mode).withSpeed(speed).withFrames(1 << 16), t -> {});
        runner.setFrameObserver(f -> {
            if (f.events().stream().anyMatch(e -> e instanceof EruptionStarted)) runner.realtime(eruptionSpeed);
        });
        runner.pauseAtTime(end);
        runner.start();
        List<EngineFrame> frames = new ArrayList<>();
        try {
            while (!runner.awaitPaused(10, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                for (EngineFrame f; (f = runner.pollFrame()) != null; ) frames.add(f);
            }
            for (EngineFrame f; (f = runner.pollFrame()) != null; ) frames.add(f);
        } finally {
            runner.close();
        }
        return frames;
    }

    @Test
    void physicsDoesNotDependOnPlayback() throws Exception {
        // As fast as possible, versus a day a second slowed to ×600 at the eruption (the session policy).
        double end = 1500; // the basalt fails at once
        List<EngineFrame> max = played(EngineRunner.Mode.UNBOUNDED, 1, 1e7, end);
        List<EngineFrame> slowed = played(EngineRunner.Mode.REALTIME, 86_400, 600, end);
        assertTrue(max.stream().anyMatch(f -> f.events().stream().anyMatch(e -> e instanceof EruptionStarted)));
        assertEquals(max.stream().filter(f -> !f.isEmpty()).toList(), slowed.stream().filter(f -> !f.isEmpty()).toList());
    }

    @Test
    void restoreAcrossAStepChangeIsBitForBit() {
        // Save while quiet (long steps); the eruption after the restore switches to short ones.
        Run reference = build(9, slowBasalt(), 1, null);
        Run first = build(9, slowBasalt(), 1, null);
        double save = 3600;
        until(first.engine(), save);
        assertFalse(first.volcano().chamber().erupting(), "save point should be before the eruption");
        InMemorySaveStore saved = Saves.save(first.engine());

        Run second = build(9, slowBasalt(), 1, saved);
        second.engine().submit(VolcanoSystemTest.resample(first.terrain()));
        double end = PAST_ONSET;
        List<EngineFrame> all = until(reference.engine(), end);
        List<EngineFrame> resumed = until(second.engine(), end);

        assertTrue(second.volcano().chamber().erupting(), "the window covers the eruption");
        List<EngineFrame> tail = all.stream().filter(f -> f.timeMicros() >= Math.round(save * 1e6)).toList();
        assertEquals(tail, resumed);
        assertEquals(reference.engine().stateHash(), second.engine().stateHash());
    }
}
