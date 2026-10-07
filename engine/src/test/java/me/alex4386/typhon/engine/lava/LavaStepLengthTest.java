package me.alex4386.typhon.engine.lava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.ColumnIndex;
import me.alex4386.typhon.engine.sim.Engine;
import org.junit.jupiter.api.Test;

/** The lava field runs in physical time; long steps are sub-stepped so the result does not depend on them. */
class LavaStepLengthTest {
    private static final double BASALT_T = 1150;
    private static final double BASALT_SI = 50;

    /** Gentle slope down toward +x, 2 m per column. */
    private static LavaTestWorld slope() {
        return new LavaTestWorld(-1, -1, 4, 1, (x, z) -> 200 - Math.max(0, x) / 2);
    }

    @Test
    void sourcesEmitTheirPhysicalRate() {
        LavaTestWorld world = slope();
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults());
        Engine engine = world.engine(lava, 1);
        lava.addSource(LavaSource.at("vent", new ColumnIndex(0, 0), 2, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, 20);
        assertEquals(2, lava.emittedVolume(), 1e-9, "1 s of a 2 m³/s source");
    }

    @Test
    void longStepsAreSubSteppedAndConserveVolume() {
        LavaTestWorld world = slope().coarse(20); // 1 s steps
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(0));
        Engine engine = world.engine(lava, 3);
        lava.addSource(LavaSource.at("vent", new ColumnIndex(0, 0), 2, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, 400);
        assertTrue(lava.lastSubsteps() > 1, "fluid basalt in 1 s steps needs sub-steps: " + lava.lastSubsteps());
        assertEquals(lava.emittedVolume(), lava.totalLavaVolume() + lava.solidifiedVolume() + lava.crustVolume(),
                1e-6 * lava.emittedVolume());
    }

    @Test
    void longStepsReachTheSameStateAsShortOnes() {
        // 200 s in 0.5 s steps versus in 50 ms steps: same emplaced flow (within sub-step differences)
        double coarse = runOut(10, 20 * 20);
        double fine = runOut(1, 20 * 200);
        assertEquals(fine, coarse, Math.max(2, 0.15 * fine), "runout " + coarse + " vs " + fine);
    }

    private static double runOut(double stepFactor, int steps) {
        LavaTestWorld world = slope().coarse(stepFactor);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(0));
        Engine engine = world.engine(lava, 3);
        lava.addSource(LavaSource.at("vent", new ColumnIndex(0, 0), 1, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, steps);
        return LavaTestWorld.maxX(lava, -16, 79, -16, 31);
    }

    @Test
    void aPondFreezesOverAWeekNotInTwoMinutes() {
        double twoMinutes = frozenFraction(1);
        double week = frozenFraction(5000);
        assertTrue(twoMinutes < 0.05, "a 2 m pond does not freeze in 2 minutes: " + twoMinutes);
        assertTrue(week > 0.95, "it does in a week: " + week);
    }

    private static double frozenFraction(double stepFactor) {
        LavaTestWorld world = new LavaTestWorld(-1, -1, 1, 1, (x, z) -> 100).coarse(stepFactor);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults());
        Engine engine = world.engine(lava, 1);
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) lava.addLava(x, z, 2, BASALT_T, BASALT_SI, 0.1);
        }
        double total = lava.totalLavaVolume();
        world.run(engine, 20 * 120);
        return 1 - lava.totalLavaVolume() / total;
    }

    @Test
    void subSteppingIsThreadCountInvariant() {
        String one = signature(1);
        assertEquals(one, signature(3));
        assertEquals(one, signature(4));
    }

    private static String signature(int threads) {
        LavaTestWorld world = slope().coarse(50);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(20));
        Engine engine = world.engine(lava, 9, threads);
        lava.addSource(LavaSource.at("vent", new ColumnIndex(0, 0), 3, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, 300);
        return engine.stateHash() + "/" + world.frames.hashCode();
    }
}
