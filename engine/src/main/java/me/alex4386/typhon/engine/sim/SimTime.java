package me.alex4386.typhon.engine.sim;

/**
 * Simulation time. The engine counts time as an exact number of microseconds so that long runs
 * never accumulate floating-point drift and stay deterministic; subsystems see seconds as
 * {@code double}s derived from it.
 *
 * <p>The engine advances in fixed base steps ({@link Engine.Builder#baseStepMicros}); that step is a
 * simulator accuracy/performance setting and has nothing to do with any host's game tick.
 */
public final class SimTime {
    public static final long MICROS_PER_SECOND = 1_000_000L;

    private SimTime() {}

    /** Seconds → microseconds, rounded to the nearest microsecond. */
    public static long micros(double seconds) {
        return Math.round(seconds * MICROS_PER_SECOND);
    }

    /** Microseconds → seconds. */
    public static double seconds(long micros) {
        return micros / (double) MICROS_PER_SECOND;
    }

    /**
     * Whether a multiple of {@code periodSeconds} lies in the half-open window
     * {@code (timeMicros − dtMicros, timeMicros]} — i.e. whether a periodic action (sampling,
     * reporting) falls due during the step ending at {@code timeMicros}. Periods ≤ 0 never fire.
     */
    public static boolean crossed(long timeMicros, long dtMicros, double periodSeconds) {
        long period = micros(periodSeconds);
        if (period <= 0) return false;
        return Math.floorDiv(timeMicros, period) != Math.floorDiv(timeMicros - dtMicros, period);
    }
}
