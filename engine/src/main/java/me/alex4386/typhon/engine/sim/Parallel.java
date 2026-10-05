package me.alex4386.typhon.engine.sim;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
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
 * <p>With one thread (or one item) bodies run inline on the caller's thread, in order. Pools are
 * shared per thread count and use daemon worker threads.
 */
public final class Parallel {
    private static final Map<Integer, ForkJoinPool> POOLS = new ConcurrentHashMap<>();
    private static final Parallel SEQUENTIAL = new Parallel(1);

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
    private final ForkJoinPool pool;

    private Parallel(int threads) {
        this.threads = threads;
        this.pool = threads == 1 ? null : POOLS.computeIfAbsent(threads, ForkJoinPool::new);
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
     * Like {@link #forEach(int, IntConsumer)}, but never splits work below {@code grain} items per task
     * (use a larger grain for cheap bodies).
     */
    public void forEach(int n, int grain, IntConsumer body) {
        if (n <= 0) return;
        if (pool == null || n <= Math.max(1, grain) || inWorkerOfOtherPool()) {
            for (int i = 0; i < n; i++) body.accept(i);
            return;
        }
        int minGrain = Math.max(Math.max(1, grain), n / (threads * 4));
        Range task = new Range(0, n, minGrain, body);
        if (ForkJoinTask.inForkJoinPool() && ForkJoinTask.getPool() == pool) {
            task.invoke();
        } else {
            pool.invoke(task);
        }
    }

    /** {@link #forEach(int, IntConsumer)} over a list. */
    public <T> void forEach(List<T> items, Consumer<? super T> body) {
        forEach(items.size(), i -> body.accept(items.get(i)));
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
        double[] terms = new double[Math.max(0, n)];
        forEach(n, i -> terms[i] = f.applyAsDouble(i));
        double s = 0;
        for (double t : terms) s += t;
        return s;
    }

    /** Runs independent tasks concurrently and waits for all of them. */
    public void run(List<Runnable> tasks) {
        forEach(tasks.size(), i -> tasks.get(i).run());
    }

    /** A worker of a different pool runs nested work inline instead of blocking on this pool. */
    private boolean inWorkerOfOtherPool() {
        return ForkJoinTask.inForkJoinPool() && ForkJoinTask.getPool() != pool;
    }

    private static final class Range extends RecursiveAction {
        private final int from;
        private final int to;
        private final int grain;
        private final IntConsumer body;

        Range(int from, int to, int grain, IntConsumer body) {
            this.from = from;
            this.to = to;
            this.grain = grain;
            this.body = body;
        }

        @Override
        protected void compute() {
            if (to - from <= grain) {
                for (int i = from; i < to; i++) body.accept(i);
                return;
            }
            int mid = (from + to) >>> 1;
            invokeAll(new Range(from, mid, grain, body), new Range(mid, to, grain, body));
        }
    }

    @Override
    public String toString() {
        return "Parallel[" + threads + "]";
    }
}
