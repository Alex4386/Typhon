package me.alex4386.typhon.engine.subsurface;

import java.util.List;

/**
 * Runs independent per-item work (chunks, tiles) on the engine's shared deterministic executor
 * ({@link me.alex4386.typhon.engine.sim.Parallel}). Callers must make every item write only its own
 * state (or its own result slot), so results do not depend on the number of threads or on
 * scheduling.
 *
 * <p>{@link Subsurface#step} hands over the engine's executor each step; {@link SubsurfaceConfig#threads}
 * {@code > 0} overrides it (tests), and outside a step (e.g. pre-warming) the default thread count is
 * used.
 */
final class Parallel {
    interface Work<T> {
        void run(int index, T item);
    }

    private final SubsurfaceConfig config;
    private me.alex4386.typhon.engine.sim.Parallel engine =
            me.alex4386.typhon.engine.sim.Parallel.of(me.alex4386.typhon.engine.sim.Parallel.defaultThreads());

    Parallel(SubsurfaceConfig config) {
        this.config = config;
    }

    /** Uses the engine's executor (called by {@link Subsurface#step}). */
    void use(me.alex4386.typhon.engine.sim.Parallel executor) {
        this.engine = executor;
    }

    me.alex4386.typhon.engine.sim.Parallel executor() {
        return config.threads > 0 ? me.alex4386.typhon.engine.sim.Parallel.of(config.threads) : engine;
    }

    int threads() {
        return executor().threads();
    }

    <T> void forEach(List<T> items, Work<T> work) {
        executor().forEach(items.size(), i -> work.run(i, items.get(i)));
    }
}
