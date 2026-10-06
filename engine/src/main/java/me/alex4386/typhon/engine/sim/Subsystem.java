package me.alex4386.typhon.engine.sim;

import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;

/**
 * A simulation component stepped by the {@link Engine} at its own rate, in physical seconds.
 *
 * <p>Subsystems run at different resolutions in time: a magma chamber may step every second while
 * lava flow steps every base step. The engine rounds {@link #periodSeconds()} and {@link
 * #phaseSeconds()} to whole base steps (minimum one step), so a subsystem whose physics needs a
 * finer step than the engine's base step must sub-step internally. Staggering phases spreads
 * expensive subsystems across steps. With adaptive stepping an engine step may span several
 * periods; the subsystem then steps once with {@link StepContext#dtSeconds()} covering them all
 * (see {@link #maxStepSeconds()} for limiting that).
 */
public interface Subsystem {
    /** Stable identifier; also seeds this subsystem's random stream, so do not rename casually. */
    String id();

    /**
     * Step period in seconds. {@code 0} (the default) steps every base step;
     * {@link Double#POSITIVE_INFINITY} never steps (command-driven subsystems).
     */
    default double periodSeconds() {
        return 0;
    }

    /** Offset of the first step in seconds, in {@code [0, periodSeconds())}. */
    default double phaseSeconds() {
        return 0;
    }

    /**
     * Longest step (seconds) this subsystem can take accurately from its current state, for
     * adaptive time stepping ({@link Engine.Builder#adaptive}): e.g. a CFL limit while lava or a flow
     * moves, a fine step while erupting, {@link Double#POSITIVE_INFINITY} (the default) when nothing in
     * it constrains the step. Must depend on state only (never on wall time or playback), so runs stay
     * deterministic. The engine never steps below its base step, so a subsystem that needs less must
     * sub-step internally.
     */
    default double maxStepSeconds() {
        return Double.POSITIVE_INFINITY;
    }

    /** Registers handlers for the commands this subsystem accepts. Called once while building. */
    default void registerCommands(CommandBus bus) {}

    void step(StepContext context);

    /**
     * Concurrency lane, or {@code null} (the default) for "runs alone".
     *
     * <p>Consecutive due subsystems that all declare a lane form a stage; the stage's lanes run
     * concurrently, the subsystems of one lane in registration order. Declare a lane only if, during
     * {@link #step}, the subsystem touches nothing but its own state and that of subsystems in the same
     * lane (e.g. one volcano's 0D magma/seismic/alert chain). Outputs are buffered per subsystem and
     * merged in registration order, so results equal sequential execution for any thread count.
     */
    default String concurrencyLane() {
        return null;
    }

    /**
     * The configuration this subsystem was built with. It is stored in save files and hashed; a
     * restore with a different configuration is rejected unless explicitly allowed. Return a record
     * or plain data object Gson can serialise, or {@code null} if there is nothing to check.
     */
    default Object config() {
        return null;
    }

    /**
     * Takes a new configuration in place, keeping all state (live tuning). Called on the engine thread
     * between steps via {@link Engine#reconfigure}. Return {@code false}, changing nothing, when the
     * configuration is of another type or changes something the state depends on (grid sizes, layout,
     * geometry); such changes need the subsystem rebuilt. Afterwards {@link #config()} must return the
     * new configuration, and derived values must follow it.
     */
    default boolean reconfigure(Object config) {
        return false;
    }

    /**
     * Writes this subsystem's persistent state: small values to {@link StateWriter#json()}, spatial
     * arrays to {@link StateWriter#field}. Together with the engine time and random states this must
     * be enough to resume the simulation bit-for-bit after {@link #loadState}.
     */
    default void saveState(StateWriter out) {}

    /** Restores state written by {@link #saveState}. Called once while building, before any step. */
    default void loadState(StateReader in) {}

    /**
     * Cheap immutable summary for UIs (gauges, dashboards), or {@code null}. Called on the engine
     * thread between steps; must not expose mutable internals.
     */
    default Object snapshot() {
        return null;
    }
}
