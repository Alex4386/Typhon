package me.alex4386.typhon.engine.subsurface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.engine.world.WorldSpec;
import org.junit.jupiter.api.Test;

/** Groundwater, surface water and the water budget. */
class GroundwaterTest {
    private static final double DAY = 86400;

    /**
     * Dupuit recharge mound: a sediment aquifer on impermeable granite at −100 m between two
     * fixed heads (sea columns at both ends) with uniform recharge {@code R}; at steady state
     * {@code h² = h₀² + (R/K)·x·(L − x)} with {@code h} measured from the aquifer base.
     */
    @Test
    void rechargeMoundMatchesDupuitParabola() {
        int nx = 22;
        double dx = 20;
        WorldSpec spec = new WorldSpec(dx, dx, -5000, 0, List.of(new WorldSpec.GeologyLayer("granite", -100, 0.01)),
                "sediment", "sediment", 1);
        WorldModel world = SubsurfaceTestWorld.build(spec, nx, 1, (x, z) -> x == 0 || x == nx - 1 ? -0.1 : 30);
        SubsurfaceConfig c = SubsurfaceTestWorld.config();
        c.rainfallMmPerHour = 0.5;
        c.vadoseLagSeconds = 3600;
        c.groundwaterIterations = 400;
        Subsurface s = new Subsurface(world, c);
        s.prepare();
        for (int i = 0; i < 30 * 365; i++) s.macroStep(DAY, true);

        double r = c.rainfallMmPerHour / 1000 / 3600;
        double k = 1e-6; // sediment
        double base = -100;
        double h0 = 0 - base;
        double length = (nx - 1) * dx;
        for (int x = 2; x < nx - 2; x += 3) {
            double distance = x * dx;
            double expected = base + Math.sqrt(h0 * h0 + r / k * distance * (length - distance));
            double modelled = s.waterTableZ(x, 0);
            double mound = expected - 0;
            assertEquals(expected, modelled, 0.2 * mound + 0.5, "x = " + x);
        }
        assertTrue(s.springDischarge() == 0, "the mound stays below the ground");
    }

    /** Island cone with sea, rain, a bucket of water, evaporation and a boiling hydrothermal system. */
    @Test
    void waterBudgetBalancesEverything() {
        int n = 24;
        WorldModel world = SubsurfaceTestWorld.uniform("basalt", 10, 20, 0, n, n,
                (x, z) -> 40 - 4 * Math.hypot(x - 11.5, z - 11.5));
        SubsurfaceConfig c = SubsurfaceTestWorld.config();
        c.surfaceTemperatureC = 15;
        c.gradientCPerKm = 30;
        c.rainfallMmPerHour = 20;
        c.evaporationMmPerHour = 1;
        c.initialWaterTableDepthM = 5;
        Subsurface s = new Subsurface(world, c);
        s.setHeatSources("v", new SubsurfaceHeatTest.FixedSources(List.of(),
                List.of(new HeatSources.Vent(11.5, 11.5, 2e9, 30, 300))));
        Engine engine = Engine.builder(7).adaptive(3600).add(s).build();
        engine.runFor(2e5); // a couple of days
        s.addWater(10, 10, 500);
        engine.runFor(1e6); // then twelve more
        WaterBudget b = s.budget();
        assertTrue(b.rain() > 0 && b.poured() == 500 && b.evaporated() > 0, b.toString());
        assertTrue(b.boiled() > 0, "the hydrothermal system should boil: " + b);
        assertTrue(b.seaOutflow() > 0 || b.seaGroundwater() > 0, "water should reach the sea: " + b);
        assertEquals(0, b.deficit(), 1e-9);
        assertEquals(0, b.imbalance(), 1e-6 * b.inflow(), b.toString());
    }

    /**
     * A window in a regional aquifer: the edge keeps its water table and resupplies a boiling
     * hydrothermal system in the middle, which a closed (no-flow) edge cannot.
     */
    @Test
    void regionalBoundaryResuppliesBoiling() {
        double closed = edgeHeadAfterBoiling(false);
        double regional = edgeHeadAfterBoiling(true);
        assertEquals(-20, regional, 1e-9, "the regional edge keeps its level");
        assertTrue(closed < regional - 1, "a closed window drains: edge at " + closed);
    }

    private static double edgeHeadAfterBoiling(boolean regionalBoundary) {
        int n = 24;
        WorldModel world = SubsurfaceTestWorld.uniform("basalt", 50, 100, Double.NaN, n, n, (x, z) -> 0);
        SubsurfaceConfig c = SubsurfaceTestWorld.config();
        c.initialWaterTableDepthM = 20;
        c.regionalBoundary = regionalBoundary;
        Subsurface s = new Subsurface(world, c);
        s.setHeatSources("v", new SubsurfaceHeatTest.FixedSources(List.of(),
                List.of(new HeatSources.Vent(12, 12, 5e8, 100, 300))));
        s.prepare();
        for (int d = 0; d < 365; d++) s.macroStep(DAY, false);
        assertTrue(s.budget().boiled() > 0, "the vent should boil groundwater");
        assertEquals(0, s.budget().imbalance(), 1e-6 * s.budget().inflow(), s.budget().toString());
        return s.waterTableZ(0, 0);
    }

    /** Poured water infiltrates, raises the water table and comes back out as a spring. */
    @Test
    void pouredWaterRaisesTheWaterTableAndFeedsSprings() {
        int n = 8;
        WorldModel world = SubsurfaceTestWorld.uniform("soil", 10, 10, Double.NaN, n, n, (x, z) -> 10 - 0.05 * x);
        SubsurfaceConfig c = SubsurfaceTestWorld.config();
        c.initialWaterTableDepthM = 2;
        c.vadoseLagSeconds = 600;
        Subsurface s = new Subsurface(world, c);
        Engine engine = Engine.builder(1).add(s).build();
        engine.runFor(1);
        double before = s.waterTableZ(3, 3);
        s.addWater(3, 3, 2000);
        double peakSurface = 0;
        for (int i = 0; i < 6 * 60; i++) {
            engine.runFor(60);
            peakSurface = Math.max(peakSurface, s.surfaceWaterDepthM(4, 3));
        }
        assertTrue(peakSurface > 0, "the poured water should spread over the surface");
        assertTrue(s.waterTableZ(3, 3) > before + 0.5, "water table " + before + " → " + s.waterTableZ(3, 3));
        assertTrue(s.springDischarge() > 0, "saturated ground should discharge springs");
        assertEquals(0, s.budget().imbalance(), 1e-6 * s.budget().inflow());
    }
}
