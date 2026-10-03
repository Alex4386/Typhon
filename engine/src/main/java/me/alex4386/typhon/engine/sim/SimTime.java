package me.alex4386.typhon.engine.sim;

/** Engine time constants. One engine tick corresponds to one Minecraft game tick. */
public final class SimTime {
    public static final int TICKS_PER_SECOND = 20;

    private SimTime() {}

    public static double ticksToSeconds(long ticks) {
        return ticks / (double) TICKS_PER_SECOND;
    }

    public static long secondsToTicks(double seconds) {
        return Math.round(seconds * TICKS_PER_SECOND);
    }
}
