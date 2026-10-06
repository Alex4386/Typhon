package me.alex4386.typhon.engine.subsurface;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.engine.world.WorldSpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Budget check from the plan: a 10 × 10 km world at 10 m surface / 40 m solver resolution (62 500
 * solver columns, 1.5 M cells) should fit in &lt; 200 MB; macro-step time is reported (target 30 ms).
 * Run with {@code ./gradlew :engine:perfTest}.
 */
@Tag("perf")
class SubsurfacePerformanceTest {
    /** One macro step of about a week, as a quiet world takes them. */
    private static final double MACRO_SPAN = 6e5;

    @Test
    void tenKilometreWorldFitsTheBudget() {
        int n = 1000;
        WorldSpec spec = new WorldSpec(10, 40, -5000, Double.NaN,
                List.of(new WorldSpec.GeologyLayer("granite", -800, 0.01)), "andesite", "soil", 2);
        Runtime rt = Runtime.getRuntime();
        System.gc();
        long before = rt.totalMemory() - rt.freeMemory();
        WorldModel world = SubsurfaceTestWorld.build(spec, n, n,
                (x, z) -> 300 + 1200 * Math.exp(-((x - 500.0) * (x - 500.0) + (z - 500.0) * (z - 500.0)) / 2.0e5));
        System.gc();
        long worldBytes = rt.totalMemory() - rt.freeMemory() - before;

        SubsurfaceConfig c = new SubsurfaceConfig();
        c.rainfallMmPerHour = 0; // a settled water table: only the vent area keeps changing
        Subsurface s = new Subsurface(world, c);
        // Typical: a vent's hydrothermal system; level of detail steps only chunks near it.
        s.setHeatSources("v", new SubsurfaceHeatTest.FixedSources(List.of(),
                List.of(new HeatSources.Vent(500, 500, 5e8, 300, 400))));
        s.prepare();
        System.gc();
        long totalBytes = rt.totalMemory() - rt.freeMemory() - before;

        // Typical: after the initial demotion (demoteAfter steps), level of detail steps only chunks near the volcano.
        for (int i = 0; i < 50; i++) s.macroStep(MACRO_SPAN, true);
        int steps = 10;
        long t0 = System.nanoTime();
        for (int i = 0; i < steps; i++) s.macroStep(MACRO_SPAN, true);
        double lodMs = (System.nanoTime() - t0) / 1e6 / steps;
        System.out.printf("PERF phases of the last step: prepare %.1f ms, heat %.1f ms, groundwater %.1f ms%n",
                s.lastTimings[0] / 1e6, s.lastTimings[1] / 1e6, s.lastTimings[2] / 1e6);
        long[] h = s.heatTimings();
        System.out.printf("PERF heat: stability %.1f ms, lateral %.1f ms (%d substeps), vertical %.1f ms, boiling %.1f ms%n",
                h[0] / 1e6, h[1] / 1e6, h[4], h[2] / 1e6, h[3] / 1e6);
        int[] activity = s.activityCounts();
        // Worst case: a large chamber whose halo warms the whole domain, every chunk hot.
        s.setHeatSources("v", new SubsurfaceHeatTest.FixedSources(
                List.of(new HeatSources.Chamber(500, 500, 1500 - 6000, 1500, 1500, 1000)),
                List.of(new HeatSources.Vent(500, 500, 5e8, 300, 400))));
        c.hotChangeC = -1;
        for (int i = 0; i < 2; i++) s.macroStep(MACRO_SPAN, true);
        long t1 = System.nanoTime();
        for (int i = 0; i < steps; i++) s.macroStep(MACRO_SPAN, true);
        double fullMs = (System.nanoTime() - t1) / 1e6 / steps;

        System.out.printf("PERF world model %.1f MB, world + subsurface %.1f MB, %d solver columns%n",
                worldBytes / 1e6, totalBytes / 1e6, s.solverColumns());
        System.out.printf("PERF macro step: %.1f ms with LOD (hot/warm/dormant chunks %d/%d/%d), %.1f ms all hot%n",
                lodMs, activity[2], activity[1], activity[0], fullMs);
        assertTrue(totalBytes < 200e6, "memory " + totalBytes / 1e6 + " MB");
        // The plan's target was 30 ms; measured ≈ 0.2–0.4 s on 4 cores (see the engine README). Guard
        // against regressions only.
        assertTrue(lodMs < 1000, "LOD macro step " + lodMs + " ms");
        assertTrue(fullMs < 2000, "all-hot macro step " + fullMs + " ms");
    }
}
