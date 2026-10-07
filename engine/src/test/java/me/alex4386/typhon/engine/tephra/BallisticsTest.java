package me.alex4386.typhon.engine.tephra;

import static me.alex4386.typhon.engine.tephra.TephraTestSupport.events;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.tephra.TephraEvents.BombLanded;
import me.alex4386.typhon.engine.tephra.TephraEvents.BombLaunched;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.world.BlockId;
import org.junit.jupiter.api.Test;

class BallisticsTest {
    private static final double G = 9.81;

    /** Horizontal range over flat ground at the launch height, integrating Ballistics.rk4 directly. */
    private static double range(double speed, double angleDeg, double diameter, double cd) {
        double a = Math.toRadians(angleDeg);
        double k = Ballistics.dragFactor(diameter, 2500, cd, 1.2);
        double[] s = {0, 0, 0, speed * Math.cos(a), speed * Math.sin(a), 0};
        double dt = 1.0 / 80;
        for (int i = 0; i < 1_000_000; i++) {
            double px = s[0], py = s[1];
            Ballistics.rk4(s, dt, k, G, 0, 0);
            if (s[1] < 0) {
                double t = py / (py - s[1]);
                return px + t * (s[0] - px);
            }
        }
        throw new AssertionError("never landed");
    }

    @Test
    void dragFreeTrajectoryMatchesAnalyticRange() {
        for (double angle : new double[] {20, 45, 70}) {
            double v = 60;
            double analytic = v * v * Math.sin(Math.toRadians(2 * angle)) / G;
            assertEquals(analytic, range(v, angle, 0.5, 0), 0.05, "angle " + angle);
        }
    }

    @Test
    void dragShortensRangeAndBigBombsFlyFarther() {
        double vacuum = range(80, 45, 0.5, 0);
        double small = range(80, 45, 0.1, 1.0);
        double medium = range(80, 45, 0.5, 1.0);
        double large = range(80, 45, 2.0, 1.0);
        assertTrue(medium < vacuum, "drag must shorten range");
        assertTrue(small < medium && medium < large, "larger bombs travel farther: " + small + " " + medium + " " + large);
    }

    @Test
    void gasThrustExitSpeedsAreInTheExpectedRange() {
        double strombolian = Ballistics.gasThrustExitSpeed(0.01, 1150, 0.5);
        double vulcanian = Ballistics.gasThrustExitSpeed(0.03, 950, 5);
        assertTrue(strombolian > 100 && strombolian < 200, "strombolian " + strombolian);
        assertTrue(vulcanian > 250 && vulcanian < 450, "vulcanian " + vulcanian);
        assertTrue(vulcanian > strombolian);
        assertEquals(0, Ballistics.gasThrustExitSpeed(0, 1000, 5));
    }

    private static TephraConfig vacuum() {
        TephraConfig config = new TephraConfig();
        config.dragCoefficient = 0;
        return config;
    }

    private static List<EngineFrame> run(Engine engine, int ticks) {
        List<EngineFrame> frames = new ArrayList<>();
        for (int i = 0; i < ticks; i++) frames.add(engine.step());
        return frames;
    }

    @Test
    void subsystemBombLandsAtAnalyticRangeAndPredictsItsFlight() {
        TerrainModel terrain = new TerrainModel();
        TephraSubsystem tephra = new TephraSubsystem("tephra", terrain, vacuum());
        Engine engine = Engine.builder(1).add(terrain).add(tephra).build();
        engine.submit(TephraTestSupport.flat(20, 63));
        tephra.setWind(0, 0, 0);
        double v = 50, c = v / Math.sqrt(2);
        tephra.launchBomb(new Vec3d(0.5, 64, 0.5), new Vec3d(c, c, 0), 0.3, 50);

        List<EngineFrame> frames = run(engine, 400);
        BombLaunched launched = events(frames, BombLaunched.class).get(0);
        BombLanded landed = events(frames, BombLanded.class).get(0);

        double analyticX = 0.5 + v * v / G;
        assertEquals(analyticX, launched.predictedLanding().x(), 0.05);
        assertEquals((int) Math.floor(analyticX), landed.position().x());
        assertEquals(63, landed.position().y());
        double flightSeconds = 2 * c / G;
        assertEquals(flightSeconds, launched.expectedFlightSeconds(), 0.05);
        // landed during the step that started expectedFlight − one step after launch
        assertEquals(launched.time() + launched.expectedFlightSeconds() - 0.05, landed.time(), 1e-9);
        assertEquals(v, landed.impactSpeed(), 0.05);
        assertEquals(0, tephra.inFlightBombs());
    }

    @Test
    void landingRespectsTerrainHeight() {
        TerrainModel terrain = new TerrainModel();
        TephraSubsystem tephra = new TephraSubsystem("tephra", terrain, vacuum());
        Engine engine = Engine.builder(1).add(terrain).add(tephra).build();
        // Plateau 30 blocks high from x >= 100.
        engine.submit(TephraTestSupport.terrain(20, (x, z) -> x >= 100 ? 93 : 63));
        tephra.setWind(0, 0, 0);
        tephra.launchBomb(new Vec3d(0.5, 64, 0.5), new Vec3d(35, 35, 0), 0.3, 50);

        BombLanded landed = events(run(engine, 400), BombLanded.class).get(0);
        assertEquals(93, landed.position().y());
        assertTrue(landed.position().x() >= 100);
        // Shorter than the flat-ground range because it hit the plateau on the way down.
        assertTrue(landed.position().x() < 0.5 + 2 * 35 * 35 / G);
    }

    @Test
    void unknownTerrainCountsAsGroundAtLaunchHeight() {
        TerrainModel terrain = new TerrainModel();
        TephraSubsystem tephra = new TephraSubsystem("tephra", terrain, vacuum());
        Engine engine = Engine.builder(1).add(terrain).add(tephra).build();
        engine.submit(TephraTestSupport.flat(1, 40)); // only around the origin, far below launch
        tephra.setWind(0, 0, 0);
        tephra.launchBomb(new Vec3d(0.5, 100, 0.5), new Vec3d(30, 30, 0), 0.3, 50);

        List<EngineFrame> frames = run(engine, 400);
        BombLanded landed = events(frames, BombLanded.class).get(0);
        assertEquals(99, landed.position().y());
        assertEquals(0.5 + 2 * 30 * 30 / G, landed.position().x(), 1.0);
        // No block changes where the engine does not know the terrain.
        assertTrue(frames.stream().allMatch(f -> f.blockChanges().isEmpty()));
    }

    @Test
    void bigFastBombDigsAContinuousCrater() {
        TerrainModel terrain = new TerrainModel();
        TephraConfig config = vacuum();
        TephraSubsystem tephra = new TephraSubsystem("tephra", terrain, config);
        Engine engine = Engine.builder(1).add(terrain).add(tephra).build();
        engine.submit(TephraTestSupport.flat(20, 63));
        tephra.setWind(0, 0, 0);
        double a = Math.toRadians(85);
        tephra.launchBomb(new Vec3d(0.5, 64, 0.5), new Vec3d(100 * Math.cos(a), 100 * Math.sin(a), 0), 1.0, 50);

        List<EngineFrame> frames = run(engine, 2000);
        BombLanded landed = events(frames, BombLanded.class).get(0);
        assertTrue(landed.craterRadius() >= 1, "crater radius " + landed.craterRadius());
        assertEquals(1.0, landed.diameter());

        int x = landed.position().x(), z = landed.position().z();
        double surface = terrain.world().surfaceZ(x, z);
        double depth = 64 - surface;
        assertTrue(depth > 0.05, "the crater lowers the surface: " + depth);
        // paraboloid of the crater radius in metres, not whole blocks
        assertTrue(depth < 0.5 * landed.craterRadius() * 1.0 + 1e-6, "no deeper than the crater shape: " + depth);
        assertEquals(0, tephra.pendingCoolings());
    }

    @Test
    void cooledRockFollowsSilica() {
        assertEquals(BlockId.minecraft("basalt"), Ballistics.cooledBombRock(49));
        assertEquals(BlockId.minecraft("blackstone"), Ballistics.cooledBombRock(54));
        assertEquals(BlockId.minecraft("andesite"), Ballistics.cooledBombRock(60));
        assertEquals(BlockId.minecraft("tuff"), Ballistics.cooledBombRock(70));
    }

    @Test
    void craterRadiusScalesWithCubeRootOfEnergy() {
        double r1 = Ballistics.craterRadius(1e6, 0.0107);
        double r8 = Ballistics.craterRadius(8e6, 0.0107);
        assertEquals(2 * r1, r8, 1e-9);
        assertEquals(0, Ballistics.craterRadius(0, 0.0107));
    }
}
