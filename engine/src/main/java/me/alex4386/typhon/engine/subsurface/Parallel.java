package me.alex4386.typhon.engine.subsurface;

import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

/**
 * Runs independent per-item work (chunks, tiles) on a fork-join pool. Callers must make every item
 * write only its own state (or its own result slot), so results do not depend on the number of
 * threads or on scheduling.
 */
final class Parallel {
    interface Work<T> {
        void run(int index, T item);
    }

    private final SubsurfaceConfig config;
    private ForkJoinPool pool;

    Parallel(SubsurfaceConfig config) {
        this.config = config;
    }

    int threads() {
        return config.threads > 0 ? config.threads : Runtime.getRuntime().availableProcessors();
    }

    <T> void forEach(List<T> items, Work<T> work) {
        int threads = threads();
        if (threads <= 1 || items.size() < 2) {
            for (int i = 0; i < items.size(); i++) work.run(i, items.get(i));
            return;
        }
        if (pool == null || pool.getParallelism() != threads) pool = new ForkJoinPool(threads);
        pool.submit(() -> IntStream.range(0, items.size()).parallel().forEach(i -> work.run(i, items.get(i)))).join();
    }
}
