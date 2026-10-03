package me.alex4386.typhon.engine.sim;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import me.alex4386.typhon.engine.command.EngineCommand;
import me.alex4386.typhon.engine.output.EngineFrame;

/**
 * Runs an {@link Engine} on its own thread at a fixed tick rate, decoupled from the host's game loop.
 *
 * <p>When the runner falls behind it catches up by running several ticks back to back, bounded by
 * {@code maxCatchUpTicks} so a long stall cannot spiral; ticks beyond that bound are dropped (the
 * simulation slows down instead of freezing the thread). Non-empty frames are queued for the host,
 * which drains them on its own thread and applies them within its budget.
 */
public final class EngineRunner implements AutoCloseable {
    private final Engine engine;
    private final long tickNanos;
    private final int maxCatchUpTicks;
    private final Queue<EngineFrame> frames = new ConcurrentLinkedQueue<>();
    private final Consumer<Throwable> errorHandler;
    private final Thread thread;
    private volatile boolean running;

    public EngineRunner(Engine engine, double ticksPerSecond, int maxCatchUpTicks, Consumer<Throwable> errorHandler) {
        if (ticksPerSecond <= 0) throw new IllegalArgumentException("ticksPerSecond must be positive");
        if (maxCatchUpTicks < 1) throw new IllegalArgumentException("maxCatchUpTicks must be at least 1");
        this.engine = engine;
        this.tickNanos = (long) (TimeUnit.SECONDS.toNanos(1) / ticksPerSecond);
        this.maxCatchUpTicks = maxCatchUpTicks;
        this.errorHandler = errorHandler;
        this.thread = new Thread(this::loop, "typhon-engine");
        this.thread.setDaemon(true);
    }

    public EngineRunner(Engine engine, Consumer<Throwable> errorHandler) {
        this(engine, SimTime.TICKS_PER_SECOND, SimTime.TICKS_PER_SECOND, errorHandler);
    }

    public void start() {
        running = true;
        thread.start();
    }

    public boolean isRunning() {
        return running;
    }

    public void submit(EngineCommand command) {
        engine.submit(command);
    }

    /** Returns the oldest pending frame, or {@code null}. Called from the host thread. */
    public EngineFrame pollFrame() {
        return frames.poll();
    }

    public int pendingFrames() {
        return frames.size();
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        LockSupport.unpark(thread);
        thread.join();
    }

    private void loop() {
        long nextTickAt = System.nanoTime();
        try {
            while (running) {
                long now = System.nanoTime();
                if (now < nextTickAt) {
                    LockSupport.parkNanos(nextTickAt - now);
                    continue;
                }

                int ran = 0;
                while (running && nextTickAt <= now && ran < maxCatchUpTicks) {
                    EngineFrame frame = engine.tick();
                    if (!frame.isEmpty()) frames.add(frame);
                    nextTickAt += tickNanos;
                    ran++;
                }
                if (nextTickAt <= now) {
                    // Still behind after the catch-up budget: drop the backlog.
                    nextTickAt = now + tickNanos;
                }
            }
        } catch (Throwable t) {
            running = false;
            errorHandler.accept(t);
        }
    }
}
