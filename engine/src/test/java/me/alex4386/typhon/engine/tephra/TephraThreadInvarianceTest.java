package me.alex4386.typhon.engine.tephra;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.VentSite;
import org.junit.jupiter.api.Test;

/** Ash transport, settling and bombs must give bit-identical results for every thread count. */
class TephraThreadInvarianceTest {
    private static final VentSite VENT = VentSite.crater("v", new BlockPos(0, 100, 0), 3);

    private record Run(List<EngineFrame> frames, String hash) {}

    private static Run run(int threads) {
        TerrainModel terrain = new TerrainModel();
        TephraConfig config = new TephraConfig();
        config.gridCells = 64;
        TephraSubsystem tephra = new TephraSubsystem("tephra", terrain, config);
        Engine engine = Engine.builder(5).threads(threads).add(terrain).add(tephra).build();
        engine.submit(TephraTestSupport.flat(16, 99));
        tephra.setWind(7, 0.6, 0.3);
        tephra.startPhase(ExplosivePhase.vulcanian(VENT, 2e5));
        List<EngineFrame> frames = new ArrayList<>();
        for (int i = 0; i < 20 * 45; i++) frames.add(engine.step());
        tephra.stopPhase();
        for (int i = 0; i < 20 * 60; i++) frames.add(engine.step());
        return new Run(frames, engine.stateHash());
    }

    @Test
    void framesAndStateDoNotDependOnThreadCount() {
        Run one = run(1);
        assertTrue(one.frames.stream().anyMatch(f -> !f.blockChanges().isEmpty()), "ash and bombs should land");
        for (int threads : new int[] {2, 3, 4}) {
            Run other = run(threads);
            assertEquals(one.hash, other.hash, "state hash with " + threads + " threads");
            assertEquals(one.frames, other.frames, "frames with " + threads + " threads");
        }
    }
}
