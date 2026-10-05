package me.alex4386.typhon.engine.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;
import me.alex4386.typhon.engine.world.BlockId;
import org.junit.jupiter.api.Test;

/** Stages of lane-declaring subsystems run concurrently but produce exactly the sequential output. */
class ConcurrencyLaneTest {
    record Tick(double time, String id, long value) implements EngineEvent {}

    static final Set<String> THREADS = ConcurrentHashMap.newKeySet();

    /** A lane-local random walk that reads its upstream (same lane) and writes blocks and events. */
    static final class Walker implements Subsystem {
        final String id;
        final String lane;
        final Walker upstream;
        long position;

        Walker(String id, String lane, Walker upstream) {
            this.id = id;
            this.lane = lane;
            this.upstream = upstream;
        }

        @Override public String id() { return id; }
        @Override public String concurrencyLane() { return lane; }

        @Override
        public void step(StepContext context) {
            THREADS.add(Thread.currentThread().getName());
            position += context.random().nextInt(-3, 4) + (upstream == null ? 0 : upstream.position % 3);
            context.outbox().emit(new Tick(context.time(), id, position));
            BlockPos pos = new BlockPos((int) (position % 16), 64, lane.hashCode() & 15);
            context.outbox().setBlock(BlockChange.set(pos, BlockId.minecraft(position % 2 == 0 ? "stone" : "basalt")));
        }

        @Override public void saveState(StateWriter writer) { writer.json().addProperty("p", position); }
        @Override public void loadState(StateReader reader) { position = reader.json().get("p").getAsLong(); }
    }

    /** A global (lane-less) subsystem between stages. */
    static final class Barrier implements Subsystem {
        @Override public String id() { return "barrier"; }
        @Override public void step(StepContext context) {
            context.outbox().setBlock(BlockChange.set(new BlockPos(0, 64, 0), BlockId.minecraft("sand")));
        }
    }

    private static List<EngineFrame> run(int threads) {
        Engine.Builder b = Engine.builder(3).threads(threads);
        List<Walker> heads = new ArrayList<>();
        for (int v = 0; v < 4; v++) heads.add(new Walker("a" + v, "volcano:" + v, null));
        for (Walker w : heads) b.add(w);
        for (int v = 0; v < 4; v++) b.add(new Walker("b" + v, "volcano:" + v, heads.get(v)));
        b.add(new Barrier());
        for (int v = 0; v < 4; v++) b.add(new Walker("c" + v, "volcano:" + v, heads.get(v)));
        Engine engine = b.build();
        List<EngineFrame> frames = new ArrayList<>();
        for (int i = 0; i < 400; i++) frames.add(engine.step());
        return frames;
    }

    @Test
    void lanesProduceSequentialResultsForAnyThreadCount() {
        List<EngineFrame> sequential = run(1);
        THREADS.clear();
        for (int threads : new int[] {2, 4}) assertEquals(sequential, run(threads), threads + " threads");
        assertTrue(THREADS.size() > 1, "lanes should have run on more than one thread: " + THREADS);
    }
}
