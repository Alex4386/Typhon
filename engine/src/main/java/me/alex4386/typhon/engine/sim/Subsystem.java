package me.alex4386.typhon.engine.sim;

import me.alex4386.typhon.engine.command.CommandBus;

/**
 * A simulation component stepped by the {@link Engine} at its own rate.
 *
 * <p>Subsystems run at different resolutions in time: a magma chamber may step every few seconds
 * while lava flow steps every tick. A subsystem with {@code interval() == n} and
 * {@code phase() == p} steps on ticks {@code p, p + n, p + 2n, ...}; staggering phases spreads
 * expensive subsystems across ticks.
 */
public interface Subsystem {
    /** Stable identifier; also seeds this subsystem's random stream, so do not rename casually. */
    String id();

    /** Step interval in ticks (at least 1). */
    default int interval() {
        return 1;
    }

    /** Step offset in ticks, in {@code [0, interval())}. */
    default int phase() {
        return 0;
    }

    /** Registers handlers for the commands this subsystem accepts. Called once while building. */
    default void registerCommands(CommandBus bus) {}

    void step(StepContext context);
}
