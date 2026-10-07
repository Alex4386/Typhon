package me.alex4386.typhon.engine.lava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import org.junit.jupiter.api.Test;

/** The lava field must produce bit-identical frames and state for every thread count. */
class LavaThreadInvarianceTest {
    private record Run(List<EngineFrame> frames, String hash) {}

    /**
     * Two vents on a ramp running into the sea across several chunks: flux, gather, crusts, tubes,
     * solidification, hyaloclastite and ocean entries are all exercised.
     */
    private static Run run(int threads) {
        LavaTestWorld world = new LavaTestWorld(-2, -2, 3, 2, (x, z) -> 90 - x / 2 + Math.abs(z) / 4,
                (x, z) -> x >= 40 ? 78 : LavaTestWorld.NO_WATER);
        world.coarse(20);
        LavaConfig config = LavaConfig.defaults().toBuilder().coolingScale(100)
                .tubeMinRoofThickness(0.5).build();
        LavaFlow lava = new LavaFlow(world.terrain, config);
        Engine engine = world.engine(lava, 42, threads);
        lava.addSource(LavaSource.at("a", new BlockPos(-20, 0, -6), 6, 1150, 50, 0.1));
        lava.addSource(LavaSource.at("b", new BlockPos(-24, 0, 9), 3, 1120, 52, 0.2));
        world.run(engine, 600);
        lava.removeSource("a");
        lava.removeSource("b");
        world.run(engine, 600);
        return new Run(world.frames, engine.stateHash());
    }

    private static boolean has(Run run, Class<?> type) {
        return run.frames.stream().flatMap(f -> f.events().stream()).anyMatch(type::isInstance);
    }

    @Test
    void framesAndStateDoNotDependOnThreadCount() {
        Run one = run(1);
        assertFalse(one.frames.stream().allMatch(f -> f.events().isEmpty()), "lava should report");
        assertTrue(has(one, LavaEvents.LavaSolidified.class), "scenario should solidify lava");
        assertTrue(has(one, LavaEvents.LavaOceanEntry.class), "scenario should enter the sea");
        for (int threads : new int[] {2, 3, 4, 7}) {
            Run other = run(threads);
            assertEquals(one.hash, other.hash, "state hash with " + threads + " threads");
            assertEquals(one.frames, other.frames, "frames with " + threads + " threads");
        }
    }
}
