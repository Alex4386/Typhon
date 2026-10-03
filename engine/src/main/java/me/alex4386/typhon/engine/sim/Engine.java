package me.alex4386.typhon.engine.sim;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.command.EngineCommand;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.output.Outbox;
import me.alex4386.typhon.engine.random.SimRandom;

/**
 * Deterministic, fixed-step simulation loop.
 *
 * <p>Each {@link #tick()} applies queued commands in submission order, steps every subsystem that is
 * due (in registration order) and returns the resulting {@link EngineFrame}. Given the same seed,
 * subsystems and command sequence, the frames are identical across runs.
 *
 * <p>{@link #saveState()} captures the tick, every subsystem's random state and its own state, so a
 * run restored with {@link Builder#restore} continues exactly as if it had never stopped. Commands
 * still queued at save time are not persisted.
 *
 * <p>The engine is confined to a single thread; only {@link #submit} may be called from others.
 */
public final class Engine {
    public static final int STATE_FORMAT = 1;

    private final long seed;
    private final List<Registered> subsystems;
    private final CommandBus commandBus;
    private final Queue<EngineCommand> pendingCommands = new ConcurrentLinkedQueue<>();
    private final Outbox outbox = new Outbox();
    private long currentTick;

    private Engine(long seed, long startTick, List<Registered> subsystems, CommandBus commandBus) {
        this.seed = seed;
        this.currentTick = startTick;
        this.subsystems = subsystems;
        this.commandBus = commandBus;
    }

    public static Builder builder(long seed) {
        return new Builder(seed);
    }

    public long seed() {
        return seed;
    }

    /** The tick that the next call to {@link #tick()} will simulate. */
    public long currentTick() {
        return currentTick;
    }

    /** Queues a command for the next tick. Safe to call from any thread. */
    public void submit(EngineCommand command) {
        pendingCommands.add(command);
    }

    public EngineFrame tick() {
        long tick = currentTick;

        EngineCommand command;
        while ((command = pendingCommands.poll()) != null) {
            commandBus.dispatch(command);
        }

        for (Registered registered : subsystems) {
            if (registered.isDue(tick)) {
                registered.subsystem.step(new StepContext(tick, registered.dtSeconds, registered.random, outbox));
            }
        }

        currentTick++;
        return outbox.drain(tick);
    }

    public JsonObject saveState() {
        JsonObject root = new JsonObject();
        root.addProperty("format", STATE_FORMAT);
        root.addProperty("seed", seed);
        root.addProperty("tick", currentTick);

        JsonObject subsystemStates = new JsonObject();
        for (Registered registered : subsystems) {
            JsonObject entry = new JsonObject();
            entry.addProperty("random", registered.random.state());
            JsonObject data = new JsonObject();
            registered.subsystem.saveState(data);
            entry.add("data", data);
            subsystemStates.add(registered.subsystem.id(), entry);
        }
        root.add("subsystems", subsystemStates);
        return root;
    }

    private record Registered(Subsystem subsystem, int interval, int phase, double dtSeconds, SimRandom random) {
        boolean isDue(long tick) {
            return tick >= phase && (tick - phase) % interval == 0;
        }
    }

    public static final class Builder {
        private final long seed;
        private final SimRandom root;
        private final List<Subsystem> subsystems = new ArrayList<>();
        private JsonObject restoreFrom;

        private Builder(long seed) {
            this.seed = seed;
            this.root = new SimRandom(seed);
        }

        public Builder add(Subsystem subsystem) {
            subsystems.add(subsystem);
            return this;
        }

        /**
         * Resumes from a {@link Engine#saveState()} snapshot. Subsystems missing from the snapshot
         * start fresh; snapshot entries without a matching subsystem are ignored.
         */
        public Builder restore(JsonObject state) {
            int format = state.get("format").getAsInt();
            if (format != STATE_FORMAT) {
                throw new IllegalArgumentException("Unsupported engine state format: " + format);
            }
            long savedSeed = state.get("seed").getAsLong();
            if (savedSeed != seed) {
                throw new IllegalArgumentException("State was saved with seed " + savedSeed + ", not " + seed);
            }
            this.restoreFrom = state;
            return this;
        }

        public Engine build() {
            CommandBus bus = new CommandBus();
            Set<String> ids = new HashSet<>();
            List<Registered> registered = new ArrayList<>();
            JsonObject savedSubsystems = restoreFrom == null ? null : restoreFrom.getAsJsonObject("subsystems");

            for (Subsystem subsystem : subsystems) {
                String id = subsystem.id();
                if (!ids.add(id)) {
                    throw new IllegalArgumentException("Duplicate subsystem id: " + id);
                }
                int interval = subsystem.interval();
                int phase = subsystem.phase();
                if (interval < 1) {
                    throw new IllegalArgumentException("Subsystem " + id + " has interval < 1: " + interval);
                }
                if (phase < 0 || phase >= interval) {
                    throw new IllegalArgumentException(
                            "Subsystem " + id + " phase must be in [0, " + interval + "): " + phase);
                }
                subsystem.registerCommands(bus);

                SimRandom random = root.fork("subsystem:" + id);
                if (savedSubsystems != null && savedSubsystems.has(id)) {
                    JsonObject entry = savedSubsystems.getAsJsonObject(id);
                    random.restore(entry.get("random").getAsLong());
                    subsystem.loadState(entry.getAsJsonObject("data"));
                }

                registered.add(new Registered(subsystem, interval, phase, SimTime.ticksToSeconds(interval), random));
            }

            long startTick = restoreFrom == null ? 0 : restoreFrom.get("tick").getAsLong();
            return new Engine(seed, startTick, List.copyOf(registered), bus);
        }
    }
}
