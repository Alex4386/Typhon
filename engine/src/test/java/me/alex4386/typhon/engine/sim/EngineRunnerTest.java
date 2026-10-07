package me.alex4386.typhon.engine.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import org.junit.jupiter.api.Test;

class EngineRunnerTest {
    record Ping(double time, double value) implements EngineEvent {}

    /** One random event per step. */
    static Subsystem pinger() {
        return new Subsystem() {
            @Override public String id() { return "ping"; }

            @Override
            public void step(StepContext context) {
                context.outbox().emit(new Ping(context.time(), context.random().nextGaussian()));
            }
        };
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out");
            Thread.onSpinWait();
        }
    }

    @Test
    void framesAreDeliveredLosslesslyInOrder() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        EngineRunner runner = new EngineRunner(Engine.builder(0).add(pinger()).build(),
                EngineRunner.Options.defaults().withMode(EngineRunner.Mode.UNBOUNDED).withFrames(8), error::set);
        runner.pauseAtStep(500);
        runner.start();

        List<EngineFrame> frames = new ArrayList<>();
        while (frames.size() < 500) {
            EngineFrame frame = runner.pollFrame(5, TimeUnit.SECONDS);
            assertNotNull(frame, "runner stalled");
            frames.add(frame);
        }
        runner.close();

        for (int i = 0; i < frames.size(); i++) assertEquals(i, frames.get(i).step());
        assertTrue(error.get() == null);
    }

    @Test
    void eventRingDropsOldestWhenFull() throws Exception {
        EngineRunner runner = new EngineRunner(Engine.builder(0).add(pinger()).build(),
                EngineRunner.Options.defaults().withMode(EngineRunner.Mode.UNBOUNDED).withEvents(16), t -> {});
        runner.pauseAtStep(100);
        runner.start();
        assertTrue(runner.awaitPaused(20, TimeUnit.SECONDS));
        List<EngineEvent> events = new ArrayList<>();
        assertEquals(16, runner.drainEvents(events));
        assertEquals(84, runner.droppedEvents());
        assertEquals(84 * 0.05, events.get(0).time(), 1e-9); // the newest 16 survive
        runner.close();
    }

    @Test
    void pauseStepAndSnapshot() throws Exception {
        EngineRunner runner = new EngineRunner(Engine.builder(0).add(pinger()).build(),
                EngineRunner.Options.defaults().withMode(EngineRunner.Mode.PAUSED), t -> {});
        runner.start();
        runner.step(7);
        await(() -> runner.completedStep() == 7);
        assertTrue(runner.awaitPaused(20, TimeUnit.SECONDS));
        assertEquals(7, runner.snapshot().step());
        assertEquals(EngineRunner.Mode.PAUSED, runner.mode());
        runner.close();
    }

    @Test
    void realtimePacesSimulationTime() throws Exception {
        EngineRunner runner = new EngineRunner(Engine.builder(0).add(pinger()).build(), t -> {});
        runner.realtime(10); // 10 seconds per wall second
        long start = System.nanoTime();
        runner.start();
        Thread.sleep(500);
        runner.pause();
        assertTrue(runner.awaitPaused(20, TimeUnit.SECONDS));
        double wall = (System.nanoTime() - start) / 1e9;
        double simulated = runner.completedStep() * 0.05;
        runner.close();
        assertTrue(simulated <= wall * 10 + 0.5, "ran ahead of real time: " + simulated + " s in " + wall + " s");
        assertTrue(simulated >= 0.3 * 10 * 0.5, "far too slow: " + simulated + " s");
    }

    @Test
    void resultsDoNotDependOnRunnerSpeed() throws Exception {
        int steps = 200; // 10 seconds
        EngineRunner fast = new EngineRunner(Engine.builder(9).add(pinger()).build(),
                EngineRunner.Options.defaults().withMode(EngineRunner.Mode.UNBOUNDED), t -> {});
        EngineRunner slow = new EngineRunner(Engine.builder(9).add(pinger()).build(),
                EngineRunner.Options.defaults().withSpeed(50), t -> {});
        fast.pauseAtStep(steps);
        slow.pauseAtStep(steps);
        fast.start();
        slow.start();
        await(() -> fast.completedStep() == steps && slow.completedStep() == steps);
        assertTrue(fast.awaitPaused(20, TimeUnit.SECONDS));
        assertTrue(slow.awaitPaused(20, TimeUnit.SECONDS));

        String fastHash = fast.onEngineThread(Engine::stateHash).get(20, TimeUnit.SECONDS);
        String slowHash = slow.onEngineThread(Engine::stateHash).get(20, TimeUnit.SECONDS);
        fast.close();
        slow.close();
        assertEquals(fastHash, slowHash);

        Engine direct = Engine.builder(9).add(pinger()).build();
        for (int i = 0; i < steps; i++) direct.step();
        assertEquals(direct.stateHash(), fastHash);
    }

    @Test
    void savesRunOnTheEngineThreadBetweenSteps() throws Exception {
        EngineRunner runner = new EngineRunner(Engine.builder(3).add(pinger()).build(),
                EngineRunner.Options.defaults().withMode(EngineRunner.Mode.UNBOUNDED), t -> {});
        runner.start();
        InMemorySaveStore store = new InMemorySaveStore();
        long step = runner.onEngineThread(e -> {
            e.save(store);
            return e.currentStep();
        }).get(20, TimeUnit.SECONDS);
        runner.close();

        Engine restored = Engine.builder(3).add(pinger()).restore(store).build();
        assertEquals(step, restored.currentStep());
    }

    @Test
    void stopsAndReportsWhenASubsystemThrows() throws Exception {
        Subsystem broken = new Subsystem() {
            @Override public String id() { return "broken"; }
            @Override public void step(StepContext context) { throw new IllegalStateException("boom"); }
        };
        AtomicReference<Throwable> error = new AtomicReference<>();
        EngineRunner runner = new EngineRunner(Engine.builder(0).add(broken).build(),
                EngineRunner.Options.defaults().withMode(EngineRunner.Mode.UNBOUNDED), error::set);
        runner.start();
        await(() -> error.get() != null);
        runner.close();

        assertTrue(error.get() instanceof IllegalStateException);
        assertFalse(runner.isRunning());
    }
}
