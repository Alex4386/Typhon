package me.alex4386.typhon.engine.expansion;

/**
 * Something physical happening that may need ground beyond the simulated area: a subsystem reports the
 * columns where it is active (molten lava, a moving flow, a rising dike tip, thick ash, running water)
 * or where it is blocked by unknown terrain. Reports are deduplicated per expansion tile, so the order
 * and multiplicity of calls do not matter.
 */
@FunctionalInterface
public interface ExpansionActivity {
    void report(Sink sink);

    /** Receives active columns. */
    @FunctionalInterface
    interface Sink {
        void active(int x, int z);
    }

    /**
     * Notified after tiles were materialised (between the expansion step and the next step): columns
     * {@code [x0, x0 + size) × [z0, z0 + size)} are now known. Used to hand over state kept for
     * unsimulated ground (e.g. tephra already fallen there).
     */
    @FunctionalInterface
    interface Listener {
        void materialized(double time, int x0, int z0, int size);
    }
}
