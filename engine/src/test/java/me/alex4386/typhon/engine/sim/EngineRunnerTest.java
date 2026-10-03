package me.alex4386.typhon.engine.sim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import org.junit.jupiter.api.Test;

class EngineRunnerTest {
    record Ping(long tick) implements EngineEvent {}

    @Test
    void producesFramesOffThreadUntilClosed() throws Exception {
        Subsystem pinger = new Subsystem() {
            @Override public String id() { return "ping"; }
            @Override public void step(StepContext context) { context.outbox().emit(new Ping(context.tick())); }
        };
        AtomicReference<Throwable> error = new AtomicReference<>();
        EngineRunner runner = new EngineRunner(Engine.builder(0).add(pinger).build(), 1000, 50, error::set);
        runner.start();

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (runner.pendingFrames() < 10 && System.nanoTime() < deadline) Thread.onSpinWait();
        runner.close();

        assertTrue(runner.pendingFrames() >= 10);
        EngineFrame first = runner.pollFrame();
        assertNotNull(first);
        assertTrue(first.events().get(0) instanceof Ping);
        assertFalse(runner.isRunning());
        assertTrue(error.get() == null);
    }

    @Test
    void stopsAndReportsWhenASubsystemThrows() throws Exception {
        Subsystem broken = new Subsystem() {
            @Override public String id() { return "broken"; }
            @Override public void step(StepContext context) { throw new IllegalStateException("boom"); }
        };
        AtomicReference<Throwable> error = new AtomicReference<>();
        EngineRunner runner = new EngineRunner(Engine.builder(0).add(broken).build(), 1000, 1, error::set);
        runner.start();

        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (error.get() == null && System.nanoTime() < deadline) Thread.onSpinWait();
        runner.close();

        assertTrue(error.get() instanceof IllegalStateException);
        assertFalse(runner.isRunning());
    }
}
