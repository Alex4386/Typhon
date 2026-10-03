package me.alex4386.typhon.engine.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.command.EngineCommand;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.world.BlockId;
import org.junit.jupiter.api.Test;

class EngineTest {
    /** Records the ticks it was stepped on. */
    static final class Recorder implements Subsystem {
        final String id;
        final int interval;
        final int phase;
        final List<Long> ticks = new ArrayList<>();
        final List<Double> dts = new ArrayList<>();

        Recorder(String id, int interval, int phase) {
            this.id = id;
            this.interval = interval;
            this.phase = phase;
        }

        @Override public String id() { return id; }
        @Override public int interval() { return interval; }
        @Override public int phase() { return phase; }

        @Override
        public void step(StepContext context) {
            ticks.add(context.tick());
            dts.add(context.dtSeconds());
        }
    }

    record Noise(long tick, double value) implements EngineEvent {}

    /** Emits one random event and one random block change per step. */
    static final class NoiseMaker implements Subsystem {
        @Override public String id() { return "noise"; }

        @Override
        public void step(StepContext context) {
            context.outbox().emit(new Noise(context.tick(), context.random().nextGaussian()));
            BlockPos pos = new BlockPos(context.random().nextInt(-8, 8), 64, context.random().nextInt(-8, 8));
            context.outbox().setBlock(BlockChange.set(pos, BlockId.minecraft("basalt")));
        }
    }

    record SetLevel(int level) implements EngineCommand {}

    static final class Chamber implements Subsystem {
        int level;
        final List<Integer> observed = new ArrayList<>();

        @Override public String id() { return "chamber"; }

        @Override
        public void registerCommands(CommandBus bus) {
            bus.register(SetLevel.class, command -> level = command.level());
        }

        @Override
        public void step(StepContext context) {
            observed.add(level);
        }
    }

    @Test
    void stepsSubsystemsAtTheirOwnRate() {
        Recorder everyTick = new Recorder("fast", 1, 0);
        Recorder everySecond = new Recorder("slow", 20, 0);
        Recorder staggered = new Recorder("staggered", 5, 3);
        Engine engine = Engine.builder(0).add(everyTick).add(everySecond).add(staggered).build();

        for (int i = 0; i < 41; i++) engine.tick();

        assertEquals(41, everyTick.ticks.size());
        assertEquals(List.of(0L, 20L, 40L), everySecond.ticks);
        assertEquals(List.of(3L, 8L, 13L, 18L, 23L, 28L, 33L, 38L), staggered.ticks);
        assertEquals(1.0, everySecond.dts.get(0));
        assertEquals(0.05, everyTick.dts.get(0));
    }

    @Test
    void commandsApplyAtStartOfNextTick() {
        Chamber chamber = new Chamber();
        Engine engine = Engine.builder(0).add(chamber).build();

        engine.tick();
        engine.submit(new SetLevel(5));
        engine.tick();
        engine.submit(new SetLevel(7));
        engine.submit(new SetLevel(9));
        engine.tick();

        assertEquals(List.of(0, 5, 9), chamber.observed);
    }

    @Test
    void unhandledCommandFails() {
        Engine engine = Engine.builder(0).add(new Chamber()).build();
        engine.submit(new EngineCommand() {});
        assertThrows(CommandBus.UnhandledCommandException.class, engine::tick);
    }

    @Test
    void sameSeedProducesIdenticalFrames() {
        assertEquals(run(1234, 200), run(1234, 200));
    }

    @Test
    void addingASubsystemDoesNotPerturbOthers() {
        List<EngineFrame> alone = run(99, 50);
        Engine withExtra = Engine.builder(99).add(new Recorder("extra", 1, 0)).add(new NoiseMaker()).build();
        List<EngineFrame> together = new ArrayList<>();
        for (int i = 0; i < 50; i++) together.add(withExtra.tick());
        assertEquals(alone, together);
    }

    @Test
    void rejectsInvalidRegistration() {
        assertThrows(IllegalArgumentException.class,
                () -> Engine.builder(0).add(new Recorder("a", 1, 0)).add(new Recorder("a", 1, 0)).build());
        assertThrows(IllegalArgumentException.class, () -> Engine.builder(0).add(new Recorder("a", 0, 0)).build());
        assertThrows(IllegalArgumentException.class, () -> Engine.builder(0).add(new Recorder("a", 4, 4)).build());
    }

    private static List<EngineFrame> run(long seed, int ticks) {
        Engine engine = Engine.builder(seed).add(new NoiseMaker()).build();
        List<EngineFrame> frames = new ArrayList<>();
        for (int i = 0; i < ticks; i++) frames.add(engine.tick());
        return frames;
    }
}
