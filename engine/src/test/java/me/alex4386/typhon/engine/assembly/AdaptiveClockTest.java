package me.alex4386.typhon.engine.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.magma.MagmaEvents.EruptionStarted;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.save.SaveStore;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.testing.Saves;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;
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
        TerrainModel terrain = new TerrainModel();
        LavaFlow lava = new LavaFlow(terrain);
        VolcanoSystem volcano = VolcanoSystem.builder("test", List.of(VolcanoSystemTest.CRATER), terrain, lava)
                .chamber(chamber)
                .scaling(VolcanoScaling.DEFAULT)
                .dikesEnabled(false)
                .build();
        Engine.Builder builder = Engine.builder(seed).adaptive(86_400).threads(threads).add(terrain);
        volcano.addTo(builder).add(lava);
        if (restore != null) builder.restore(restore);
        Engine engine = builder.build();
        if (restore == null) engine.submit(VolcanoSystemTest.cone());
        return new Run(engine, volcano, terrain);
    }

    /** A chamber far below failure: recharges quietly for years. */
    static MagmaChamberConfig quiet() {
        return MagmaChamberConfig.builder("test", new BlockPos(0, 60, 0))
                .initialOverpressureMPa(2)
                .supplyVariability(0)
                .build();
    }

    /** The basalt chamber fails at ≈ 666 700 s (7.7 days); this is a few minutes into its eruption. */
    static final double PAST_ONSET = 667_000;

    static List<EngineFrame> until(Engine engine, double time) {
        List<EngineFrame> frames = new ArrayList<>();
        while (engine.time() < time) frames.add(engine.step());
        return frames;
    }

    @Test
    void aQuietYearTakesLongStepsAndRunsFast() {
        Run r = build(1, quiet(), 1, null);
        long t0 = System.nanoTime();
        List<EngineFrame> frames = until(r.engine(), YEAR);
        double wall = (System.nanoTime() - t0) / 1e9;
        System.out.printf("CLOCK quiet year: %d steps, %.2f s wall, last step %.0f s%n",
                frames.size(), wall, r.engine().lastStepSeconds());

        assertFalse(r.volcano().chamber().erupting());
        // 50 ms ticks would be 6.3e8 steps; quiet steps of most of a day keep a year to hundreds.
        assertTrue(frames.size() < 1500, "steps in a quiet year: " + frames.size());
        assertTrue(r.engine().lastStepSeconds() >= 40_000, "quiet step " + r.engine().lastStepSeconds() + " s");
        assertTrue(wall < 60, "a quiet year took " + wall + " s");
    }

    @Test
    void anEruptionRunsAtFineSteps() {
        Run r = build(1, VolcanoSystemTest.basalt(), 1, null);
        double start = Double.NaN;
        while (r.engine().time() < 60 * 86400) {
            EngineFrame f = r.engine().step();
            if (f.events().stream().anyMatch(e -> e instanceof EruptionStarted)) {
                start = r.engine().time();
                break;
            }
        }
        System.out.printf("CLOCK basalt at 14.9 MPa erupts after %.0f s%n", start);
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
        List<EngineFrame> a = until(build(5, VolcanoSystemTest.basalt(), 1, null).engine(), end);
        List<EngineFrame> b = until(build(5, VolcanoSystemTest.basalt(), 1, null).engine(), end);
        List<EngineFrame> c = until(build(5, VolcanoSystemTest.basalt(), 4, null).engine(), end);
        assertTrue(a.stream().anyMatch(f -> f.events().stream().anyMatch(e -> e instanceof EruptionStarted)),
                "the window covers the onset");
        assertEquals(a, b);
        assertEquals(a, c);
    }

    @Test
    void restoreAcrossAStepChangeIsBitForBit() {
        // Save while quiet (long steps); the eruption after the restore switches to short ones.
        Run reference = build(9, VolcanoSystemTest.basalt(), 1, null);
        Run first = build(9, VolcanoSystemTest.basalt(), 1, null);
        double save = 3600;
        until(first.engine(), save);
        assertFalse(first.volcano().chamber().erupting(), "save point should be before the eruption");
        InMemorySaveStore saved = Saves.save(first.engine());

        Run second = build(9, VolcanoSystemTest.basalt(), 1, saved);
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
