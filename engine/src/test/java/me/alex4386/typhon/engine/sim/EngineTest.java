package me.alex4386.typhon.engine.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.command.EngineCommand;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;
import me.alex4386.typhon.engine.testing.Saves;
import org.junit.jupiter.api.Test;

class EngineTest {
    /** Records the steps it ran on. */
    static final class Recorder implements Subsystem {
        final String id;
        final double period;
        final double phase;
        final List<Long> steps = new ArrayList<>();
        final List<Double> dts = new ArrayList<>();

        Recorder(String id, double period, double phase) {
            this.id = id;
            this.period = period;
            this.phase = phase;
        }

        @Override public String id() { return id; }
        @Override public double periodSeconds() { return period; }
        @Override public double phaseSeconds() { return phase; }

        @Override
        public void step(StepContext context) {
            steps.add(context.step());
            dts.add(context.dtSeconds());
        }
    }

    record Noise(double time, double value) implements EngineEvent {}

    /** Emits two random events per step. */
    static final class NoiseMaker implements Subsystem {
        @Override public String id() { return "noise"; }

        @Override
        public void step(StepContext context) {
            context.outbox().emit(new Noise(context.time(), context.random().nextGaussian()));
            context.outbox().emit(new Noise(context.time(), context.random().nextInt(-8, 8)));
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
    void stepsSubsystemsAtTheirOwnPeriod() {
        Recorder everyStep = new Recorder("fast", 0, 0);
        Recorder everySecond = new Recorder("slow", 1.0, 0);
        Recorder staggered = new Recorder("staggered", 0.25, 0.15);
        Engine engine = Engine.builder(0).add(everyStep).add(everySecond).add(staggered).build();

        for (int i = 0; i < 41; i++) engine.step();

        assertEquals(41, everyStep.steps.size());
        assertEquals(List.of(0L, 20L, 40L), everySecond.steps);
        assertEquals(List.of(3L, 8L, 13L, 18L, 23L, 28L, 33L, 38L), staggered.steps);
        assertEquals(1.0, everySecond.dts.get(0));
        assertEquals(0.05, everyStep.dts.get(0));
        assertEquals(2.05, engine.time(), 1e-12);
    }

    @Test
    void periodsAreRoundedToTheBaseStep() {
        Recorder r = new Recorder("r", 0.35, 0);
        Engine engine = Engine.builder(0).baseStepMicros(100_000).add(r).build();
        for (int i = 0; i < 10; i++) engine.step();
        // 0.35 s at a 0.1 s base step rounds to 4 steps (0.4 s).
        assertEquals(List.of(0L, 4L, 8L), r.steps);
        assertEquals(0.4, r.dts.get(0), 1e-12);
    }

    @Test
    void infinitePeriodNeverSteps() {
        Recorder r = new Recorder("idle", Double.POSITIVE_INFINITY, 0);
        Engine engine = Engine.builder(0).add(r).build();
        for (int i = 0; i < 100; i++) engine.step();
        assertTrue(r.steps.isEmpty());
    }

    @Test
    void timeIsExactOverLongRuns() {
        Engine engine = Engine.builder(0).baseStepMicros(50_000).build();
        engine.runFor(3600);
        assertEquals(72_000, engine.currentStep());
        assertEquals(3_600_000_000L, engine.timeMicros());
    }

    @Test
    void commandsApplyAtStartOfNextStep() {
        Chamber chamber = new Chamber();
        Engine engine = Engine.builder(0).add(chamber).build();

        engine.step();
        engine.submit(new SetLevel(5));
        engine.step();
        engine.submit(new SetLevel(7));
        engine.submit(new SetLevel(9));
        engine.step();

        assertEquals(List.of(0, 5, 9), chamber.observed);
    }

    @Test
    void unhandledCommandFails() {
        Engine engine = Engine.builder(0).add(new Chamber()).build();
        engine.submit(new EngineCommand() {});
        assertThrows(CommandBus.UnhandledCommandException.class, engine::step);
    }

    @Test
    void sameSeedProducesIdenticalFrames() {
        assertEquals(run(1234, 200), run(1234, 200));
    }

    @Test
    void addingASubsystemDoesNotPerturbOthers() {
        List<EngineFrame> alone = run(99, 50);
        Engine withExtra = Engine.builder(99).add(new Recorder("extra", 0, 0)).add(new NoiseMaker()).build();
        List<EngineFrame> together = new ArrayList<>();
        for (int i = 0; i < 50; i++) together.add(withExtra.step());
        assertEquals(alone, together);
    }

    /** Random walk whose position is persisted. */
    static final class Walker implements Subsystem {
        long position;
        int stepSize = 2;
        final List<Long> trace = new ArrayList<>();

        @Override public String id() { return "walker"; }
        @Override public double periodSeconds() { return 0.15; }

        record Config(int stepSize) {}

        @Override public Object config() { return new Config(stepSize); }

        @Override
        public void step(StepContext context) {
            position += context.random().nextInt(-stepSize, stepSize + 1);
            trace.add(position);
        }

        @Override public void saveState(StateWriter out) { out.json().addProperty("position", position); }
        @Override public void loadState(StateReader in) { position = in.json().get("position").getAsLong(); }
    }

    @Test
    void savedStateResumesBitForBit() {
        Walker straight = new Walker();
        Engine reference = Engine.builder(77).add(straight).add(new NoiseMaker()).build();
        List<EngineFrame> referenceFrames = new ArrayList<>();
        for (int i = 0; i < 100; i++) referenceFrames.add(reference.step());

        Walker first = new Walker();
        Engine before = Engine.builder(77).add(first).add(new NoiseMaker()).build();
        for (int i = 0; i < 40; i++) before.step();
        InMemorySaveStore saved = Saves.save(before);
        assertEquals(before.stateHash(), reference(77, 40).stateHash());

        Walker second = new Walker();
        Engine after = Engine.builder(77).add(second).add(new NoiseMaker()).restore(saved).build();
        assertEquals(40, after.currentStep());
        assertEquals(before.stateHash(), after.stateHash());
        List<EngineFrame> resumed = new ArrayList<>();
        for (int i = 0; i < 60; i++) resumed.add(after.step());

        assertEquals(referenceFrames.subList(40, 100), resumed);
        List<Long> combined = new ArrayList<>(first.trace);
        combined.addAll(second.trace);
        assertEquals(straight.trace, combined);
    }

    private static Engine reference(long seed, int steps) {
        Engine engine = Engine.builder(seed).add(new Walker()).add(new NoiseMaker()).build();
        for (int i = 0; i < steps; i++) engine.step();
        return engine;
    }

    @Test
    void stateHashTracksState() {
        Engine a = reference(5, 10);
        Engine b = reference(5, 10);
        assertEquals(a.stateHash(), b.stateHash());
        b.step();
        assertNotEquals(a.stateHash(), b.stateHash());
    }

    @Test
    void restoreRejectsDifferentSeedAndBaseStep() {
        InMemorySaveStore saved = Saves.save(Engine.builder(1).add(new Walker()).build());
        assertThrows(IllegalArgumentException.class, () -> Engine.builder(2).add(new Walker()).restore(saved).build());
        assertThrows(IllegalArgumentException.class,
                () -> Engine.builder(1).baseStepMicros(100_000).add(new Walker()).restore(saved).build());
    }

    @Test
    void restoreRejectsChangedConfigurationUnlessAllowed() {
        InMemorySaveStore saved = Saves.save(Engine.builder(1).add(new Walker()).build());
        Walker changed = new Walker();
        changed.stepSize = 5;
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> Engine.builder(1).add(changed).restore(saved).build());
        assertTrue(e.getMessage().contains("walker"), e.getMessage());

        Engine allowed = Engine.builder(1).add(changed).restore(saved).allowConfigChanges().build();
        assertEquals(0, allowed.currentStep());
    }

    @Test
    void pendingCommandsSurviveASave() {
        Chamber chamber = new Chamber();
        Engine engine = Engine.builder(0).add(chamber).build();
        engine.step();
        engine.submit(new SetLevel(42));
        InMemorySaveStore saved = Saves.save(engine);

        Chamber restored = new Chamber();
        Engine resumed = Engine.builder(0).add(restored).restore(saved).build();
        resumed.step();
        assertEquals(List.of(42), restored.observed);
    }

    @Test
    void rejectsInvalidRegistration() {
        assertThrows(IllegalArgumentException.class,
                () -> Engine.builder(0).add(new Recorder("a", 0, 0)).add(new Recorder("a", 0, 0)).build());
        assertThrows(IllegalArgumentException.class, () -> Engine.builder(0).add(new Recorder("a", -1, 0)).build());
        assertThrows(IllegalArgumentException.class, () -> Engine.builder(0).add(new Recorder("a", 0.2, 0.2)).build());
    }

    @Test
    void snapshotsCollectSubsystemSummaries() {
        record Gauge(int value) {}
        Subsystem gauge = new Subsystem() {
            int n;
            @Override public String id() { return "gauge"; }
            @Override public void step(StepContext context) { n++; }
            @Override public Object snapshot() { return new Gauge(n); }
        };
        Engine engine = Engine.builder(0).add(gauge).add(new Chamber()).build();
        for (int i = 0; i < 7; i++) engine.step();
        EngineSnapshot snapshot = engine.snapshot();
        assertEquals(7, snapshot.step());
        assertEquals(new Gauge(7), snapshot.get("gauge", Gauge.class));
        assertEquals(1, snapshot.subsystems().size());
    }

    private static List<EngineFrame> run(long seed, int steps) {
        Engine engine = Engine.builder(seed).add(new NoiseMaker()).build();
        List<EngineFrame> frames = new ArrayList<>();
        for (int i = 0; i < steps; i++) frames.add(engine.step());
        return frames;
    }
}
