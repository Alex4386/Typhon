package me.alex4386.typhon.engine.subsurface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.WorldModel;
import org.junit.jupiter.api.Test;

/** Heat conduction against analytic solutions. */
class SubsurfaceHeatTest {
    private static final double YEAR = 365.25 * 86400;
    private static final double BASALT_KAPPA = MaterialTable.BASALT.diffusivity();

    /** Abramowitz &amp; Stegun 7.1.26 (|error| ≤ 1.5e-7). */
    static double erf(double x) {
        double sign = Math.signum(x);
        x = Math.abs(x);
        double t = 1 / (1 + 0.3275911 * x);
        double y = 1 - ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t
                * Math.exp(-x * x);
        return sign * y;
    }

    private static SubsurfaceConfig dry() {
        SubsurfaceConfig c = SubsurfaceTestWorld.config();
        c.initialWaterTableDepthM = 1e5; // water table at the grid bottom: no water, no convection
        return c;
    }

    @Test
    void halfSpaceCoolingFollowsErfc() {
        SubsurfaceConfig c = dry();
        c.surfaceExchangeWm2K = 1e7; // surface held at 0 °C
        WorldModel world = SubsurfaceTestWorld.uniform("basalt", 10, 10, Double.NaN, 1, 1, (x, z) -> 0);
        Subsurface s = new Subsurface(world, c);
        s.prepare();
        SolverChunk ch = s.grid().chunk(0, 0);
        java.util.Arrays.fill(ch.temperature, 0, s.levels(), 100.0);

        double day = 86400;
        double t = 0;
        while (t < YEAR - 1) {
            s.macroStep(day, false);
            t += day;
        }
        for (int k = 0; k < s.levels() && s.levelCenterDepth(k) < 40; k++) {
            double depth = s.levelCenterDepth(k);
            double expected = 100 * erf(depth / (2 * Math.sqrt(BASALT_KAPPA * t)));
            assertEquals(expected, ch.temperature[k], 1.5, "depth " + depth);
        }
    }

    @Test
    void linearGeothermIsSteady() {
        SubsurfaceConfig c = dry();
        c.surfaceTemperatureC = 15;
        c.gradientCPerKm = 30;
        WorldModel world = SubsurfaceTestWorld.uniform("basalt", 10, 10, Double.NaN, 1, 1, (x, z) -> 100);
        Subsurface s = new Subsurface(world, c);
        s.equilibrate(1000 * YEAR, 10 * YEAR);
        for (int k = 0; k < s.levels(); k++) {
            double depth = s.levelCenterDepth(k);
            assertEquals(15 + 0.03 * depth, s.temperatureC(0, 0, depth), 0.1, "depth " + depth);
        }
    }

    @Test
    void chamberHaloReachesTheConductiveSteadyState() {
        SubsurfaceConfig c = dry();
        c.surfaceExchangeWm2K = 1e7;
        int n = 21; // 200 m columns: 4.2 km square
        WorldModel world = SubsurfaceTestWorld.uniform("basalt", 200, 200, Double.NaN, n, n, (x, z) -> 0);
        Subsurface s = new Subsurface(world, c);
        s.prepare(); // background only: no chamber yet
        HeatSources.Chamber chamber = new HeatSources.Chamber(10.5, 10.5, -1800, 0, 400, 900);
        s.setHeatSources("v", new FixedSources(List.of(chamber), List.of()));
        s.equilibrate(300_000 * YEAR, 500 * YEAR); // steps short enough that operator splitting is accurate

        SubsurfaceHeat heat = new SubsurfaceHeat(s.grid(), c);
        List<HeatSources.Chamber> list = List.of(chamber);
        double[][] probes = {{10.5, 10.5, -1000}, {14.5, 10.5, -1800}, {10.5, 13.5, -1300}};
        for (double[] p : probes) {
            double analytic = heat.halo(list, p[0], p[1], p[2]);
            double modelled = s.temperatureC((int) p[0], (int) p[1], -p[2]);
            assertTrue(analytic > 50, "probe should sit in the halo");
            assertEquals(analytic, modelled, 0.25 * analytic, "probe " + java.util.Arrays.toString(p));
        }
    }

    @Test
    void dikeSheetAnomalyDecaysAsInverseSquareRootOfTime() {
        SubsurfaceConfig c = dry();
        c.hotChangeC = 1e-6;
        WorldModel world = SubsurfaceTestWorld.uniform("basalt", 10, 10, Double.NaN, 100, 3, (x, z) -> 0);
        Subsurface s = new Subsurface(world, c);
        s.prepare();
        List<Subsurface.SheetSample> sheet = new ArrayList<>();
        for (int z = 0; z < 3; z++) {
            for (int k = 0; k < s.levels(); k++) {
                sheet.add(new Subsurface.SheetSample(50.5, z + 0.5, -s.levelCenterDepth(k), s.grid().thickness(k)));
            }
        }
        s.addSheetHeat(sheet, 1.0, 1100);
        int level = s.grid().levelAtDepth(800);
        double depth = s.levelCenterDepth(level);

        double t1 = 3e9;
        double t2 = 4 * t1;
        double dt = 1e8;
        double t = 0;
        double a1 = 0;
        while (t < t2 - 1) {
            s.macroStep(dt, false);
            t += dt;
            if (Math.abs(t - t1) < 1) a1 = s.temperatureC(50, 1, depth);
        }
        double a2 = s.temperatureC(50, 1, depth);
        assertTrue(a1 > 2, "anomaly should be measurable: " + a1);
        assertEquals(2.0, a1 / a2, 0.3, "planar source: ΔT ∝ t^-1/2 (" + a1 + " → " + a2 + ")");
    }

    @Test
    void boilingPointFollowsTheDepthCurve() {
        assertEquals(WaterSaturation.boilingPointC(0, 0), SubsurfaceHeat.boilingPoint(0, 0), 1e-12);
        assertEquals(120.4, SubsurfaceHeat.boilingPoint(10, 0), 0.5);
        assertTrue(SubsurfaceHeat.boilingPoint(0, 2000) < 94, "water boils cooler at altitude");
    }

    record FixedSources(List<Chamber> chambers, List<Vent> vents) implements HeatSources {}
}
