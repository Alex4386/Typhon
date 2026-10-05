package me.alex4386.typhon.engine.massflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.Trigger;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import org.junit.jupiter.api.Test;

/** PDC and lahar transport must give bit-identical results for every thread count. */
class MassFlowThreadInvarianceTest {
    private record Run(List<EngineFrame> frames, String hash) {}

    private static Run pdc(int threads) {
        MassFlowTestWorld w = new MassFlowTestWorld(0, 0, 15, 3, MassFlowTestWorld.rampToPlain(0.25, 160, 64));
        PyroclasticFlows flow = new PyroclasticFlows("pdc", w.terrain);
        Engine engine = w.engine(flow, 7, threads);
        flow.release(new BlockPos(8, 0, 32), 3, 2500, 650, 0, Trigger.MANUAL);
        flow.addSource(FlowSource.at("feed", new BlockPos(5, 0, 30), 15, 600, 0), Trigger.COLUMN_COLLAPSE);
        w.run(engine, 500);
        return new Run(w.frames, engine.stateHash());
    }

    private static Run lahar(int threads) {
        MassFlowTestWorld w = new MassFlowTestWorld(0, 0, 15, 3, MassFlowTestWorld.rampToPlain(0.25, 160, 64));
        Lahars flow = new Lahars("lahar", w.terrain);
        Engine engine = w.engine(flow, 42, threads);
        for (int x = 0; x < 60; x++) flow.addErodibleDeposit(x, 32, 0.3);
        flow.release(new BlockPos(8, 0, 32), 3, 1500, 15, 0.3, Trigger.MANUAL);
        w.run(engine, 500);
        return new Run(w.frames, engine.stateHash());
    }

    @Test
    void pyroclasticFlowsDoNotDependOnThreadCount() {
        Run one = pdc(1);
        assertTrue(one.frames.stream().anyMatch(f -> !f.blockChanges().isEmpty()), "the PDC should deposit");
        for (int threads : new int[] {2, 3, 4}) {
            Run other = pdc(threads);
            assertEquals(one.hash, other.hash, "state hash with " + threads + " threads");
            assertEquals(one.frames, other.frames, "frames with " + threads + " threads");
        }
    }

    @Test
    void laharsDoNotDependOnThreadCount() {
        Run one = lahar(1);
        for (int threads : new int[] {2, 4}) {
            Run other = lahar(threads);
            assertEquals(one.hash, other.hash, "state hash with " + threads + " threads");
            assertEquals(one.frames, other.frames, "frames with " + threads + " threads");
        }
    }
}
