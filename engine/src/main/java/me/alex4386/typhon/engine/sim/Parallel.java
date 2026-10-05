package me.alex4386.typhon.engine.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntToDoubleFunction;

/**
 * Deterministic data parallelism for subsystems.
 *
 * <p>Every helper here produces results that are <b>bit-identical for any thread count</b>, provided
 * the caller follows one rule: the body for item {@code i} only <em>writes</em> state owned by item
 * {@code i} (its own chunk, its own slot in an output array) and only <em>reads</em> state that no
 * item writes during the call (e.g. the previous step's buffers). Anything order-dependent — summing
 * doubles, emitting block changes or events, drawing random numbers from a shared stream, editing the
 * world model — must be done per item into the item's own buffer and then combined sequentially in
 * item order (see {@link #sum}, {@link #map}).
 *
 * <p>The engine issues many short parallel regions per step (one per phase of each field), so the
 * backend is a low-latency worker pool: workers spin briefly between regions and park only after a
 * while without work, and the calling thread takes part in every region. Regions smaller than twice
 * the requested grain, nested regions and single-thread instances run inline, in order.
 */
public final class Parallel {
    private static final Map<Integer, Pool> POOLS = new ConcurrentHashMap<>();
    private static final Parallel SEQUENTIAL = new Parallel(1);
    private static final ThreadLocal<boolean[]> IN_REGION = ThreadLocal.withInitial(() -> new boolean[1]);

    /** Default: system property {@code typhon.threads}, else the number of available processors. */
    public static int defaultThreads() {
        Integer configured = Integer.getInteger("typhon.threads");
        if (configured != null && configured > 0) return configured;
        return Runtime.getRuntime().availableProcessors();
    }

    public static Parallel of(int threads) {
        if (threads < 1) throw new IllegalArgumentException("threads must be at least 1");
        return threads == 1 ? SEQUENTIAL : new Parallel(threads);
    }

    public static Parallel sequential() {
        return SEQUENTIAL;
    }

    private final int threads;
    private final Pool pool;

    private Parallel(int threads) {
        this.threads = threads;
        this.pool = threads == 1 ? null : POOLS.computeIfAbsent(threads, t -> new Pool(t - 1));
    }

    public int threads() {
        return threads;
    }

    public boolean isSequential() {
        return pool == null;
    }

    /** Runs {@code body(i)} for every {@code i} in {@code [0, n)}; see the class rule. */
    public void forEach(int n, IntConsumer body) {
        forEach(n, 1, body);
    }

    /**
     * Like {@link #forEach(int, IntConsumer)}, with at least {@code grain} consecutive items per task
     * (use a larger grain for cheap bodies); fewer than {@code 2 · grain} items run inline.
     */
    public void forEach(int n, int grain, IntConsumer body) {
        if (n <= 0) return;
        int g = Math.max(1, grain);
        boolean[] inRegion = IN_REGION.get();
        if (pool == null || n < 2 * g || inRegion[0]) {
            for (int i = 0; i < n; i++) body.accept(i);
            return;
        }
        pool.run(n, Math.max(g, n / (threads * 8)), body);
    }

    /** {@link #forEach(int, int, IntConsumer)} over a list. */
    public <T> void forEach(List<T> items, int grain, Consumer<? super T> body) {
        forEach(items.size(), grain, i -> body.accept(items.get(i)));
    }

    /** {@link #forEach(int, IntConsumer)} over a list. */
    public <T> void forEach(List<T> items, Consumer<? super T> body) {
        forEach(items, 1, body);
    }

    /** Maps every item in parallel; the result list is in item order. */
    public <T, R> List<R> map(List<T> items, Function<? super T, ? extends R> f) {
        int n = items.size();
        Object[] out = new Object[n];
        forEach(n, i -> out[i] = f.apply(items.get(i)));
        List<R> result = new ArrayList<>(n);
        for (Object o : out) {
            @SuppressWarnings("unchecked")
            R r = (R) o;
            result.add(r);
        }
        return result;
    }

    /**
     * Sum of {@code f(i)} over {@code [0, n)}. Terms are computed in parallel but added sequentially
     * in index order, so the result does not depend on the thread count.
     */
    public double sum(int n, IntToDoubleFunction f) {
        return sum(n, 1, f);
    }

    /** {@link #sum(int, IntToDoubleFunction)} with a minimum grain. */
    public double sum(int n, int grain, IntToDoubleFunction f) {
        double[] terms = new double[Math.max(0, n)];
        forEach(n, grain, i -> terms[i] = f.applyAsDouble(i));
        double s = 0;
        for (double t : terms) s += t;
        return s;
    }

    /** Runs independent tasks concurrently and waits for all of them. */
    public void run(List<Runnable> tasks) {
        forEach(tasks.size(), i -> tasks.get(i).run());
    }

    @Override
    public String toString() {
        return "Parallel[" + threads + "]";
    }

    /**
     * {@code workers} daemon threads plus the caller. One region runs at a time per pool (concurrent
     * callers — e.g. several engines — take turns). Items are claimed {@code grain} at a time from a
     * shared counter; completion is a countdown of the workers that saw the region.
     */
    private static final class Pool {
        /** How long an idle worker spins before parking (engine steps issue regions back to back). */
        private static final long SPIN_NANOS = 200_000;

        private final Thread[] workers;
        private final Object turn = new Object();
        private final AtomicInteger next = new AtomicInteger();
        private final AtomicInteger pending = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile long generation;
        private int n;
        private int grain;
        private IntConsumer body;

        Pool(int workers) {
            this.workers = new Thread[workers];
            for (int w = 0; w < workers; w++) {
                Thread t = new Thread(this::workerLoop, "typhon-parallel-" + (workers + 1) + "-" + w);
                t.setDaemon(true);
                this.workers[w] = t;
                t.start();
            }
        }

        void run(int n, int grain, IntConsumer body) {
            synchronized (turn) {
                this.n = n;
                this.grain = grain;
                this.body = body;
                next.set(0);
                failure.set(null);
                pending.set(workers.length);
                generation++; // publishes the fields above to workers reading the volatile
                for (Thread t : workers) LockSupport.unpark(t);
                work();
                while (pending.get() != 0) Thread.onSpinWait();
                this.body = null;
                Throwable t = failure.get();
                if (t != null) {
                    if (t instanceof RuntimeException e) throw e;
                    if (t instanceof Error e) throw e;
                    throw new IllegalStateException(t);
                }
            }
        }

        private void work() {
            boolean[] inRegion = IN_REGION.get();
            inRegion[0] = true;
            try {
                int count = n;
                int g = grain;
                IntConsumer f = body;
                while (failure.get() == null) {
                    int start = next.getAndAdd(g);
                    if (start >= count) break;
                    int end = Math.min(count, start + g);
                    for (int i = start; i < end; i++) f.accept(i);
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                inRegion[0] = false;
            }
        }

        private void workerLoop() {
            long seen = generation;
            while (true) {
                long spinStart = System.nanoTime();
                while (generation == seen) {
                    if (System.nanoTime() - spinStart < SPIN_NANOS) {
                        Thread.onSpinWait();
                    } else {
                        LockSupport.park(this);
                    }
                }
                seen = generation;
                work();
                pending.decrementAndGet();
            }
        }
    }
}
