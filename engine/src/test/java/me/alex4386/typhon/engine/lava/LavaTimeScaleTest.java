package me.alex4386.typhon.engine.lava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicReference;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.sim.Engine;
import org.junit.jupiter.api.Test;

/** The lava field runs on its volcanoes' clocks; flow is sub-stepped so compression stays physical. */
class LavaTimeScaleTest {
    private static final double BASALT_T = 1150;
    private static final double BASALT_SI = 50;

    /** Gentle slope down toward +x, 2 m per column. */
    private static LavaTestWorld slope() {
        return new LavaTestWorld(-1, -1, 4, 1, (x, z) -> 200 - Math.max(0, x) / 2);
    }

    @Test
    void fieldFollowsTheEffusingVolcanoClockThenTheSlowestClock() {
        LavaTestWorld world = slope();
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults());
        AtomicReference<Double> a = new AtomicReference<>(20.0);
        lava.registerClock("a", a::get);
        lava.registerClock("b", () -> 5000.0);
        Engine engine = world.engine(lava, 1);

        lava.addSource(LavaSource.at("a/vent", new BlockPos(0, 0, 0), 2, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, 20);
        assertEquals(20, lava.lastCompression(), 1e-12, "effusing volcano's eruptive clock");
        // 1 engine second at ×20 of a 2 m³/s physical source: 40 m³, whatever the field rule
        assertEquals(40, lava.emittedVolume(), 1e-9);

        lava.removeSource("a/vent");
        a.set(5000.0 / 4);
        world.run(engine, 1);
        assertEquals(1250, lava.lastCompression(), 1e-12, "no effusion: the slowest clock");
    }

    @Test
    void withoutClocksTheConfiguredTimeScaleApplies() {
        LavaTestWorld world = slope();
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withTimeScale(7));
        Engine engine = world.engine(lava, 1);
        lava.addSource(LavaSource.at("vent", new BlockPos(0, 0, 0), 1, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, 20);
        assertEquals(7, lava.lastCompression(), 1e-12);
        assertEquals(7, lava.emittedVolume(), 1e-9);
    }

    @Test
    void compressedFlowIsSubSteppedAndConservesVolume() {
        LavaTestWorld world = slope();
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withTimeScale(20).withCoolingScale(0));
        Engine engine = world.engine(lava, 3);
        lava.addSource(LavaSource.at("vent", new BlockPos(0, 0, 0), 2, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, 400);
        assertTrue(lava.lastSubsteps() > 1, "fluid basalt at ×20 needs sub-steps: " + lava.lastSubsteps());
        assertEquals(lava.emittedVolume(), lava.totalLavaVolume() + lava.solidifiedVolume() + lava.crustVolume(),
                1e-6 * lava.emittedVolume());
    }

    @Test
    void compressionReachesTheSamePhysicalStateAsRunningLonger() {
        // 20 s at ×10 versus 200 s at ×1: same physical time, same emplaced flow (within the
        // differences of their sub-step sizes)
        double fast = runOut(10, 20 * 20);
        double slow = runOut(1, 20 * 200);
        assertEquals(slow, fast, Math.max(2, 0.15 * slow), "runout " + fast + " vs " + slow);
    }

    private static double runOut(double timeScale, int steps) {
        LavaTestWorld world = slope();
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withTimeScale(timeScale).withCoolingScale(0));
        Engine engine = world.engine(lava, 3);
        lava.addSource(LavaSource.at("vent", new BlockPos(0, 0, 0), 1, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, steps);
        return LavaTestWorld.maxX(lava, -16, 79, -16, 31);
    }

    @Test
    void compressedCoolingFreezesAPondThatPhysicalSpeedLeavesMolten() {
        double physical = frozenFraction(1);
        double compressed = frozenFraction(5000);
        assertTrue(physical < 0.05, "a 2 m pond does not freeze in 2 minutes: " + physical);
        assertTrue(compressed > 0.95, "at ×5000 two engine minutes are a week: " + compressed);
    }

    private static double frozenFraction(double timeScale) {
        LavaTestWorld world = new LavaTestWorld(-1, -1, 1, 1, (x, z) -> 100);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withTimeScale(timeScale));
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
        LavaTestWorld world = slope();
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withTimeScale(50).withCoolingScale(20));
        Engine engine = world.engine(lava, 9, threads);
        lava.addSource(LavaSource.at("vent", new BlockPos(0, 0, 0), 3, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, 300);
        return engine.stateHash() + "/" + world.frames.hashCode();
    }
}
