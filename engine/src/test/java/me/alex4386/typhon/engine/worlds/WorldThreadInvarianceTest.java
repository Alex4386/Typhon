package me.alex4386.typhon.engine.worlds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.alex4386.typhon.engine.output.EngineFrame;
import org.junit.jupiter.api.Test;

/**
 * A complete two-volcano world (chambers, dikes, lava, tephra, mass flows, geothermal, subsurface
 * heat/groundwater/surface water, lanes) is bit-identical for every thread count.
 */
class WorldThreadInvarianceTest {
    private record Run(List<EngineFrame> frames, String hash) {}

    private static Run run(int threads) {
        String previous = System.getProperty("typhon.threads");
        System.setProperty("typhon.threads", Integer.toString(threads));
        try {
            World world = World.create(WorldTest.world(), WorldTest.twins(),
                    WorldTest.terrain(WorldTest.world(), WorldTest.twins()));
            assertEquals(threads, world.engine().parallel().threads());
            List<EngineFrame> frames = world.engine().runFor(120);
            return new Run(frames, world.engine().stateHash());
        } finally {
            if (previous == null) System.clearProperty("typhon.threads");
            else System.setProperty("typhon.threads", previous);
        }
    }

    @Test
    void wholeWorldDoesNotDependOnThreadCount() {
        Run one = run(1);
        assertTrue(one.frames.stream().anyMatch(f -> !f.events().isEmpty()), "the world should be active");
        for (int threads : new int[] {2, 4}) {
            Run other = run(threads);
            assertEquals(one.hash, other.hash, "state hash with " + threads + " threads");
            assertEquals(one.frames, other.frames, "frames with " + threads + " threads");
        }
    }
}
