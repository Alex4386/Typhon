package me.alex4386.typhon.engine.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;
import me.alex4386.typhon.engine.command.EngineCommand;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;

/**
 * Runs an {@link Engine} on its own thread, decoupled from whoever consumes its output.
 *
 * <h2>Modes</h2>
 * <ul>
 *   <li>{@link Mode#REALTIME}: time advances at {@link #speed()} seconds per wall-clock second
 *       (adjustable while running, e.g. 1×–10⁷×). A step runs once the wall clock has reached its
 *       end, whatever its length (the engine picks step lengths from its state, never from the
 *       speed). When the engine cannot keep up it catches up at most {@code maxCatchUpSteps} steps
 *       at once and then lets time slip.
 *   <li>{@link Mode#UNBOUNDED}: as many steps as the CPU allows.
 *   <li>{@link Mode#PAUSED}: no steps, except those requested with {@link #step(int)}.
 * </ul>
 * The mode only changes how time maps onto wall-clock time; the simulation itself (frames, state)
 * is identical whatever the mode or speed. A {@linkplain #setFrameObserver frame observer} sees
 * every frame on the engine thread and may change the mode or speed there (playback policies that
 * slow down when an eruption starts).
 *
 * <h2>Output</h2>
 * <ul>
 *   <li><b>Frames</b> (optional, {@code frameCapacity > 0}): every non-empty {@link EngineFrame},
 *       in order, never dropped. Hosts that must apply every block change use this; when the queue
 *       is full the engine thread waits (back-pressure) instead of discarding anything.
 *   <li><b>Events</b>: a bounded ring for UIs. When a consumer falls behind, the oldest events are
 *       discarded and counted in {@link #droppedEvents()}.
 *   <li><b>Snapshot</b>: the latest {@link EngineSnapshot}, refreshed at most every
 *       {@code snapshotIntervalMillis} of wall time and whenever the runner pauses.
 * </ul>
 * Work that must happen between steps (saving, hashing, inspecting subsystems) goes through
 * {@link #onEngineThread}.
 */
public final class EngineRunner implements AutoCloseable {
    public enum Mode { REALTIME, UNBOUNDED, PAUSED }

    /**
     * @param mode initial mode
     * @param speed initial real-time multiplier
     * @param frameCapacity capacity of the lossless frame queue; 0 disables frame delivery
     * @param eventCapacity capacity of the lossy event ring
     * @param snapshotIntervalMillis minimum wall time between published snapshots
     * @param maxCatchUpSteps steps run back to back in REALTIME before letting time slip
     */
    public record Options(Mode mode, double speed, int frameCapacity, int eventCapacity, long snapshotIntervalMillis,
            int maxCatchUpSteps) {
        public Options {
            if (!(speed > 0)) throw new IllegalArgumentException("speed must be positive");
            if (frameCapacity < 0 || eventCapacity < 1) throw new IllegalArgumentException("bad capacities");
            if (maxCatchUpSteps < 1) throw new IllegalArgumentException("maxCatchUpSteps must be at least 1");
        }

        public static Options defaults() {
            return new Options(Mode.REALTIME, 1.0, 0, 4096, 33, 200);
        }

        public Options withMode(Mode mode) {
            return new Options(mode, speed, frameCapacity, eventCapacity, snapshotIntervalMillis, maxCatchUpSteps);
        }

        public Options withSpeed(double speed) {
            return new Options(mode, speed, frameCapacity, eventCapacity, snapshotIntervalMillis, maxCatchUpSteps);
        }

        public Options withFrames(int capacity) {
            return new Options(mode, speed, capacity, eventCapacity, snapshotIntervalMillis, maxCatchUpSteps);
        }

        public Options withEvents(int capacity) {
            return new Options(mode, speed, frameCapacity, capacity, snapshotIntervalMillis, maxCatchUpSteps);
        }
    }

    private final Engine engine;
    private final Options options;
    private final Consumer<Throwable> errorHandler;
    private final Thread thread;
    private final BlockingQueue<EngineFrame> frames;
    private final EngineEvent[] ring;
    private final Object ringLock = new Object();
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

    private int ringHead;
    private int ringSize;
    private long droppedEvents;

    private volatile Mode mode;
    private volatile double speed;
    private volatile boolean running;
    private final AtomicLong pendingSteps = new AtomicLong();
    private volatile boolean idle;
    private volatile long pauseAtStep = Long.MAX_VALUE;
    private volatile long pauseAtMicros = Long.MAX_VALUE;
    /** While paused, steps run until the time reaches this (µs; see {@link #stepFor}). */
    private volatile long stepUntilMicros = Long.MIN_VALUE;
    private volatile EngineSnapshot snapshot;
    private volatile long completedStep;
    private volatile long snapshotTimeMicros;
    private volatile long nextStepMicros;

    // REALTIME pacing anchor (written on the engine thread; read for the playback clock)
    private volatile long anchorNanos;
    private volatile long anchorMicros;
    private volatile boolean reanchor = true;
    private volatile Consumer<EngineFrame> frameObserver;
    private long lastSnapshotNanos;

    public EngineRunner(Engine engine, Options options, Consumer<Throwable> errorHandler) {
        this.engine = engine;
        this.options = options;
        this.errorHandler = errorHandler;
        this.mode = options.mode();
        this.speed = options.speed();
        this.frames = options.frameCapacity() > 0 ? new ArrayBlockingQueue<>(options.frameCapacity()) : null;
        this.ring = new EngineEvent[options.eventCapacity()];
        this.thread = new Thread(this::loop, "typhon-engine");
        this.thread.setDaemon(true);
        this.snapshot = engine.snapshot();
        this.completedStep = engine.currentStep();
        this.snapshotTimeMicros = engine.timeMicros();
    }

    public EngineRunner(Engine engine, Consumer<Throwable> errorHandler) {
        this(engine, Options.defaults(), errorHandler);
    }

    public void start() {
        running = true;
        thread.start();
    }

    public boolean isRunning() {
        return running;
    }

    // ── Control ──

    public Mode mode() {
        return mode;
    }

    public double speed() {
        return speed;
    }

    /**
     * Calls {@code observer} with every frame, on the engine thread, right after its step (before it
     * is queued). It may switch the mode or speed; it must not block.
     */
    public void setFrameObserver(Consumer<EngineFrame> observer) {
        this.frameObserver = observer;
    }

    /**
     * Playback time (µs): in {@link Mode#REALTIME} the wall-clock position between the engine's
     * time and the end of its next step (so a clock advances smoothly through long quiet steps);
     * otherwise the time of the last completed step.
     */
    public long playbackMicros() {
        long engineMicros = snapshotTimeMicros;
        if (mode != Mode.REALTIME || reanchor) return engineMicros;
        long target = anchorMicros + (long) ((System.nanoTime() - anchorNanos) / 1000.0 * speed);
        return Math.max(engineMicros, Math.min(target, engineMicros + nextStepMicros));
    }

    public void realtime(double speed) {
        if (!(speed > 0)) throw new IllegalArgumentException("speed must be positive");
        this.speed = speed;
        this.mode = Mode.REALTIME;
        this.reanchor = true;
        wake();
    }

    /** Sets the {@link Mode#REALTIME} speed without changing the mode (a paused runner stays paused). */
    public void setSpeed(double speed) {
        if (!(speed > 0)) throw new IllegalArgumentException("speed must be positive");
        this.speed = speed;
        this.reanchor = true;
        wake();
    }

    public void unbounded() {
        this.mode = Mode.UNBOUNDED;
        wake();
    }

    public void pause() {
        this.mode = Mode.PAUSED;
        wake();
    }

    /** Runs {@code n} more steps while paused (pauses first if needed). */
    public void step(int n) {
        if (n < 0) throw new IllegalArgumentException("n must be non-negative");
        this.mode = Mode.PAUSED;
        this.pendingSteps.addAndGet(n);
        wake();
    }

    /**
     * Runs steps while paused until the time has advanced by at least {@code seconds} (pauses first
     * if needed). Steps keep the lengths the engine picks.
     */
    public void stepFor(double seconds) {
        if (!(seconds >= 0)) throw new IllegalArgumentException("seconds must be non-negative");
        this.mode = Mode.PAUSED;
        this.stepUntilMicros = snapshotTimeMicros + SimTime.micros(seconds);
        wake();
    }

    /** Pauses automatically before simulating step {@code step} (i.e. once {@code step} steps ran). */
    public void pauseAtStep(long step) {
        this.pauseAtStep = step;
        this.pauseAtMicros = Long.MAX_VALUE;
        wake();
    }

    /** Pauses automatically once the time reaches {@code seconds} (at the end of the step crossing it). */
    public void pauseAtTime(double seconds) {
        this.pauseAtStep = Long.MAX_VALUE;
        this.pauseAtMicros = SimTime.micros(seconds);
        wake();
    }

    private boolean pauseDue() {
        return engine.currentStep() >= pauseAtStep || engine.timeMicros() >= pauseAtMicros;
    }

    /** Number of steps completed so far. */
    public long completedStep() {
        return completedStep;
    }

    /** Waits until the runner is paused with no requested steps outstanding. */
    public boolean awaitPaused(long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!(mode == Mode.PAUSED && pendingSteps.get() == 0 && stepUntilMicros <= snapshotTimeMicros && idle)) {
            if (!running || System.nanoTime() > deadline) return false;
            Thread.sleep(1);
        }
        return true;
    }

    public void submit(EngineCommand command) {
        engine.submit(command);
        wake();
    }

    /**
     * Runs {@code task} on the engine thread between steps (e.g. {@code Engine::save},
     * {@code Engine::stateHash}) and completes the future with its result.
     */
    public <T> CompletableFuture<T> onEngineThread(Function<Engine, T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        tasks.add(() -> {
            try {
                future.complete(task.apply(engine));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        wake();
        return future;
    }

    // ── Output ──

    /** Next frame in order, or {@code null} if none is waiting (frame delivery must be enabled). */
    public EngineFrame pollFrame() {
        requireFrames();
        return frames.poll();
    }

    /** Waits up to {@code timeout} for the next frame. */
    public EngineFrame pollFrame(long timeout, TimeUnit unit) throws InterruptedException {
        requireFrames();
        return frames.poll(timeout, unit);
    }

    public int pendingFrames() {
        return frames == null ? 0 : frames.size();
    }

    private void requireFrames() {
        if (frames == null) throw new IllegalStateException("Frame delivery is disabled (frameCapacity = 0)");
    }

    /** Moves all buffered events (oldest first) into {@code out}; returns how many were moved. */
    public int drainEvents(List<EngineEvent> out) {
        synchronized (ringLock) {
            int n = ringSize;
            for (int i = 0; i < n; i++) {
                int idx = (ringHead + i) % ring.length;
                out.add(ring[idx]);
                ring[idx] = null;
            }
            ringHead = 0;
            ringSize = 0;
            return n;
        }
    }

    /** Events discarded because the ring was full. */
    public long droppedEvents() {
        synchronized (ringLock) {
            return droppedEvents;
        }
    }

    /** The latest published snapshot. */
    public EngineSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        wake();
        thread.interrupt();
        thread.join();
    }

    // ── Engine thread ──

    private void wake() {
        LockSupport.unpark(thread);
    }

    private void loop() {
        try {
            while (running) {
                runTasks();
                if (pauseDue() && mode != Mode.PAUSED) {
                    mode = Mode.PAUSED;
                    pendingSteps.set(0);
                    stepUntilMicros = Long.MIN_VALUE;
                }
                switch (mode) {
                    case PAUSED -> {
                        if (pendingSteps.get() > 0 && !pauseDue()) {
                            idle = false;
                            doStep();
                            pendingSteps.decrementAndGet();
                        } else if (engine.timeMicros() < stepUntilMicros && !pauseDue()) {
                            idle = false;
                            doStep();
                        } else {
                            pendingSteps.set(0);
                            stepUntilMicros = Long.MIN_VALUE;
                            publishSnapshot(true);
                            idle = true;
                            reanchor = true;
                            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                        }
                    }
                    case UNBOUNDED -> {
                        idle = false;
                        doStep();
                        reanchor = true;
                    }
                    case REALTIME -> {
                        idle = false;
                        realtimeSlice();
                    }
                }
                publishSnapshot(false);
            }
        } catch (InterruptedException e) {
            // closing
        } catch (Throwable t) {
            running = false;
            errorHandler.accept(t);
        } finally {
            idle = true;
        }
    }

    private void realtimeSlice() throws InterruptedException {
        long now = System.nanoTime();
        if (reanchor) {
            anchorNanos = now;
            anchorMicros = engine.timeMicros();
            reanchor = false;
        }
        double elapsedMicros = (now - anchorNanos) / 1000.0 * speed;
        long targetMicros = anchorMicros + (long) elapsedMicros;
        double startSpeed = speed;

        int ran = 0;
        while (running && mode == Mode.REALTIME && speed == startSpeed && !reanchor
                && engine.timeMicros() + nextStep() <= targetMicros
                && ran < options.maxCatchUpSteps() && !pauseDue()) {
            doStep();
            ran++;
        }
        if (mode != Mode.REALTIME || speed != startSpeed || reanchor) return; // switched mid-slice
        if (ran >= options.maxCatchUpSteps() && engine.timeMicros() + nextStep() <= targetMicros) {
            reanchor = true; // fell behind: let time slip instead of spiralling
            return;
        }
        if (ran == 0) {
            long nextMicros = engine.timeMicros() + nextStep() - anchorMicros;
            long wakeAt = anchorNanos + (long) (nextMicros * 1000.0 / speed);
            long sleep = Math.min(wakeAt - System.nanoTime(), TimeUnit.MILLISECONDS.toNanos(10));
            if (sleep > 0) LockSupport.parkNanos(sleep);
        }
    }

    /** Length of the engine's next step (µs), from its state. */
    private long nextStep() {
        long micros = Math.max(engine.baseStepMicros(), Math.round(engine.nextStepSeconds() * 1e6));
        nextStepMicros = micros;
        return micros;
    }

    private void doStep() throws InterruptedException {
        EngineFrame frame = engine.step();
        completedStep = engine.currentStep();
        snapshotTimeMicros = engine.timeMicros();
        Consumer<EngineFrame> observer = frameObserver;
        if (observer != null) observer.accept(frame);
        if (frame.isEmpty()) return;
        if (!frame.events().isEmpty()) {
            synchronized (ringLock) {
                for (EngineEvent event : frame.events()) {
                    if (ringSize == ring.length) {
                        ring[ringHead] = null;
                        ringHead = (ringHead + 1) % ring.length;
                        ringSize--;
                        droppedEvents++;
                    }
                    ring[(ringHead + ringSize) % ring.length] = event;
                    ringSize++;
                }
            }
        }
        if (frames != null) {
            while (running && !frames.offer(frame, 10, TimeUnit.MILLISECONDS)) {
                runTasks(); // keep serving saves/inspection while the consumer catches up
            }
        }
    }

    private void runTasks() {
        Runnable task;
        while ((task = tasks.poll()) != null) task.run();
    }

    private void publishSnapshot(boolean force) {
        long now = System.nanoTime();
        if (!force && now - lastSnapshotNanos < TimeUnit.MILLISECONDS.toNanos(options.snapshotIntervalMillis())) return;
        if (force && snapshot != null && snapshot.step() == engine.currentStep()) return;
        snapshot = engine.snapshot();
        lastSnapshotNanos = now;
    }

    /** Copies the buffered events without removing them (for diagnostics). */
    List<EngineEvent> peekEvents() {
        synchronized (ringLock) {
            List<EngineEvent> out = new ArrayList<>(ringSize);
            for (int i = 0; i < ringSize; i++) out.add(ring[(ringHead + i) % ring.length]);
            return out;
        }
    }
}
