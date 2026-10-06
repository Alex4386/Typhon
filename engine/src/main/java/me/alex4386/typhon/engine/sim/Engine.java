package me.alex4386.typhon.engine.sim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.command.EngineCommand;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.output.HistoricalEvent;
import me.alex4386.typhon.engine.output.Outbox;
import me.alex4386.typhon.engine.random.SimRandom;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.save.SaveFormat;
import me.alex4386.typhon.engine.save.SaveStore;
import me.alex4386.typhon.engine.save.SubsystemState;

/**
 * Deterministic simulation loop on one physical clock.
 *
 * <p>Time is counted in base quanta of {@link #baseStepMicros()} (default 50 ms, a simulator setting).
 * Each {@link #step()} applies queued commands in submission order, picks the step length, steps
 * every subsystem that is due (in registration order) and returns the resulting {@link EngineFrame}.
 *
 * <p><b>Adaptive steps.</b> With {@link Builder#adaptive} a step spans a power-of-two number of quanta
 * (aligned to the current time, so steps tile the time line), as long as every subsystem allows
 * ({@link Subsystem#maxStepSeconds()}) and up to the configured maximum: hours while the volcano is
 * quiet, the base step while lava, flows or an eruption need it. The choice depends only on state,
 * never on wall time or playback speed. A subsystem is due when one of its schedule points
 * ({@code phase + n·period}) falls in the step; it then covers the time up to its next point after
 * the step, so its steps tile the time line too. Without {@code adaptive} every step is one quantum.
 * Given the same seed, base step, maximum step, subsystems and command sequence, the frames are
 * identical across runs and thread counts.
 *
 * <p>{@link #save(SaveStore)} captures the time, every subsystem's configuration hash, random state
 * and own state, still-queued commands, and appends new {@link HistoricalEvent}s to the history log,
 * so a run restored with {@link Builder#restore} continues exactly as if it had never stopped.
 *
 * <p>The engine is confined to a single thread; only {@link #submit} may be called from others.
 */
public final class Engine {
    public static final int STATE_FORMAT = 2;
    public static final String ENGINE_VERSION = "1.0.0-SNAPSHOT";
    public static final long DEFAULT_BASE_STEP_MICROS = 50_000;
    /**
     * Longest step hosts allow ({@link Builder#adaptive}): a day. Quiet volcanoes recharge for years;
     * subsystems shorten the steps as soon as anything moves.
     */
    public static final double DEFAULT_MAX_STEP_SECONDS = 86_400;

    private final long seed;
    private final long baseStepMicros;
    private final List<Registered> subsystems;
    private final CommandBus commandBus;
    private final Queue<EngineCommand> pendingCommands = new ConcurrentLinkedQueue<>();
    private final Outbox outbox = new Outbox();
    private final List<HistoricalEvent> history = new ArrayList<>();
    private final Parallel parallel;
    private final long maxStrideQuanta;
    private long currentStep;
    /** Simulation time in base quanta. */
    private long timeQuanta;
    private long lastStrideQuanta = 1;

    private Engine(long seed, long baseStepMicros, long maxStrideQuanta, long startStep, long startQuanta,
            List<Registered> subsystems, CommandBus commandBus, Parallel parallel) {
        this.parallel = parallel;
        this.seed = seed;
        this.baseStepMicros = baseStepMicros;
        this.maxStrideQuanta = maxStrideQuanta;
        this.currentStep = startStep;
        this.timeQuanta = startQuanta;
        this.subsystems = subsystems;
        this.commandBus = commandBus;
    }

    public static Builder builder(long seed) {
        return new Builder(seed);
    }

    public long seed() {
        return seed;
    }

    public long baseStepMicros() {
        return baseStepMicros;
    }

    /** The executor subsystems use for data-parallel work; results never depend on its thread count. */
    public Parallel parallel() {
        return parallel;
    }

    /** The step index that the next call to {@link #step()} will simulate. */
    public long currentStep() {
        return currentStep;
    }

    /** Simulation time at the start of the next step, in microseconds. */
    public long timeMicros() {
        return timeQuanta * baseStepMicros;
    }

    /** Length of the last step taken, in seconds (the base step before any). */
    public double lastStepSeconds() {
        return SimTime.seconds(lastStrideQuanta * baseStepMicros);
    }

    /** Longest step the engine may take, in seconds (the base step unless {@link Builder#adaptive}). */
    public double maxStepSeconds() {
        return SimTime.seconds(maxStrideQuanta * baseStepMicros);
    }

    /**
     * Length (seconds) of the next step from the current state: what {@link #step()} takes unless a
     * queued command changes the state first.
     */
    public double nextStepSeconds() {
        return SimTime.seconds(chooseStride() * baseStepMicros);
    }

    /** Simulation time at the start of the next step, in seconds. */
    public double time() {
        return SimTime.seconds(timeMicros());
    }

    /** Queues a command for the next step. Safe to call from any thread. */
    public void submit(EngineCommand command) {
        pendingCommands.add(command);
    }

    /**
     * Swaps the configuration of subsystem {@code id} in place, between steps on the engine thread: the
     * subsystem keeps its state and continues with {@code config} from the next step (see
     * {@link Subsystem#reconfigure}). Its schedule follows a changed period, and saves record the new
     * configuration, so a restore built with it resumes exactly.
     *
     * @throws IllegalArgumentException for an unknown subsystem or a change it cannot take in place
     */
    public void reconfigure(String id, Object config) {
        Registered r = registration(id);
        if (r == null) throw new IllegalArgumentException("No subsystem " + id);
        if (!r.subsystem.reconfigure(config)) {
            throw new IllegalArgumentException("Subsystem " + id + " cannot take this configuration change in place");
        }
        // the declared schedule, exactly as a restore with this configuration computes it
        long[] schedule = schedule(r.subsystem, baseStepMicros);
        if (schedule[1] < 0 || (schedule[0] > 0 && schedule[1] >= schedule[0])) {
            throw new IllegalStateException("Subsystem " + id + " phase must be in [0, period) after reconfiguring");
        }
        r.periodSteps = schedule[0];
        r.phaseSteps = schedule[1];
        // time already covered stays covered; the new period applies from the next schedule point
        if (r.nextQuanta < timeQuanta) r.nextQuanta = r.nextPointAtOrAfter(timeQuanta);
        r.configJson = configJson(r.subsystem);
        r.configHash = hash(r.configJson);
    }

    /** Hash of subsystem {@code id}'s current configuration (as saves record it), or {@code null} if unknown. */
    public String configHash(String id) {
        Registered r = registration(id);
        return r == null ? null : r.configHash;
    }

    /** The hash a subsystem with configuration {@code config} would be recorded with. */
    public static String configHash(Object config) {
        if (config == null) return hash(JsonNull.INSTANCE);
        return hash(SaveFormat.gson().toJsonTree(config));
    }

    /** Whether subsystem {@code id} is registered. */
    public boolean hasSubsystem(String id) {
        return registration(id) != null;
    }

    private Registered registration(String id) {
        for (Registered r : subsystems) if (r.subsystem.id().equals(id)) return r;
        return null;
    }

    /** {period, phase} in base steps for a subsystem's declared period and phase. */
    private static long[] schedule(Subsystem subsystem, long baseStepMicros) {
        double period = subsystem.periodSeconds();
        double phase = subsystem.phaseSeconds();
        long periodSteps = Double.isInfinite(period) ? 0 : Math.max(1, Math.round(SimTime.micros(period) / (double) baseStepMicros));
        long phaseSteps = Math.round(SimTime.micros(phase) / (double) baseStepMicros);
        return new long[] {periodSteps, phaseSteps};
    }

    public EngineFrame step() {
        long step = currentStep;

        EngineCommand command;
        while ((command = pendingCommands.poll()) != null) {
            commandBus.dispatch(command);
        }

        long stride = chooseStride();
        long time = timeQuanta * baseStepMicros;
        long end = timeQuanta + stride;
        int count = subsystems.size();
        // each due subsystem covers from its next schedule point to its first point after the step
        long[] dts = new long[count];
        for (int s = 0; s < count; s++) {
            Registered r = subsystems.get(s);
            if (!r.isDue(end)) continue;
            long next = r.nextPointAtOrAfter(end);
            dts[s] = (next - r.nextQuanta) * baseStepMicros;
            r.nextQuanta = next;
        }
        for (int s = 0; s < count; s++) {
            if (dts[s] == 0) continue;
            Registered registered = subsystems.get(s);
            if (registered.lane != null && !parallel.isSequential()) {
                int stageEnd = s + 1; // the stage: following due subsystems that also declare a lane
                while (stageEnd < count && subsystems.get(stageEnd).lane != null) stageEnd++;
                List<Registered> stage = new ArrayList<>();
                List<Long> stageDts = new ArrayList<>();
                for (int k = s; k < stageEnd; k++) {
                    if (dts[k] == 0) continue;
                    stage.add(subsystems.get(k));
                    stageDts.add(dts[k]);
                }
                if (runStage(stage, stageDts, step, time)) {
                    s = stageEnd - 1;
                    continue;
                }
            }
            registered.subsystem.step(new StepContext(step, time, dts[s], registered.random, outbox, parallel));
        }

        currentStep++;
        timeQuanta = end;
        lastStrideQuanta = stride;
        EngineFrame frame = outbox.drain(step, time);
        for (EngineEvent event : frame.events()) {
            if (event instanceof HistoricalEvent historical) history.add(historical);
        }
        return frame;
    }

    /**
     * Quanta the next step spans: the largest power of two that divides the current time (steps stay
     * aligned) and fits every stepping subsystem's {@link Subsystem#maxStepSeconds()} and the maximum.
     */
    /** Each stepped subsystem's current {@link Subsystem#maxStepSeconds()} (diagnostics), in order. */
    public java.util.Map<String, Double> stepLimits() {
        java.util.Map<String, Double> out = new java.util.LinkedHashMap<>();
        for (Registered r : subsystems) if (r.periodSteps != 0) out.put(r.subsystem.id(), r.subsystem.maxStepSeconds());
        return out;
    }

    private long chooseStride() {
        if (maxStrideQuanta <= 1) return 1;
        double limit = Double.POSITIVE_INFINITY;
        for (Registered r : subsystems) {
            if (r.periodSteps == 0) continue;
            double m = r.subsystem.maxStepSeconds();
            if (m < limit) limit = m;
        }
        long q = maxStrideQuanta;
        if (limit < Double.POSITIVE_INFINITY) {
            double quanta = Math.floor(limit * SimTime.MICROS_PER_SECOND / baseStepMicros);
            q = (long) Math.max(1, Math.min(q, quanta));
        }
        long k = Long.highestOneBit(q);
        while (k > 1 && Math.floorMod(timeQuanta, k) != 0) k >>= 1;
        return k;
    }

    /**
     * Runs a stage of lane-declaring subsystems with lanes in parallel (see
     * {@link Subsystem#concurrencyLane()}); returns false (nothing run) when it has a single lane.
     */
    private boolean runStage(List<Registered> stage, List<Long> dts, long step, long time) {
        Map<String, List<Integer>> lanes = new LinkedHashMap<>();
        for (int i = 0; i < stage.size(); i++) {
            lanes.computeIfAbsent(stage.get(i).lane, k -> new ArrayList<>()).add(i);
        }
        if (lanes.size() < 2) return false;
        Outbox[] buffers = new Outbox[stage.size()];
        List<List<Integer>> laneList = new ArrayList<>(lanes.values());
        parallel.forEach(laneList.size(), l -> {
            for (int i : laneList.get(l)) {
                Registered r = stage.get(i);
                Outbox buffer = new Outbox();
                buffers[i] = buffer;
                r.subsystem.step(new StepContext(step, time, dts.get(i), r.random, buffer, parallel));
            }
        });
        for (Outbox buffer : buffers) outbox.absorb(buffer);
        return true;
    }

    /** Steps until simulation time reaches at least {@code seconds}; returns the frames produced. */
    public List<EngineFrame> runFor(double seconds) {
        long end = timeMicros() + SimTime.micros(seconds);
        List<EngineFrame> frames = new ArrayList<>();
        while (timeMicros() < end) frames.add(step());
        return frames;
    }

    /** Snapshots of every subsystem that provides one (see {@link Subsystem#snapshot()}). */
    public EngineSnapshot snapshot() {
        Map<String, Object> snapshots = new LinkedHashMap<>();
        for (Registered registered : subsystems) {
            Object s = registered.subsystem.snapshot();
            if (s != null) snapshots.put(registered.subsystem.id(), s);
        }
        return new EngineSnapshot(currentStep, timeMicros(), snapshots);
    }

    // ── Persistence ──

    /**
     * Saves the complete state to {@code store} (see {@link SaveFormat} for the layout). Call it
     * between steps. Region files that did not change are not rewritten. Historical events produced
     * since the previous save are appended to the history log.
     */
    public void save(SaveStore store) {
        save(store, events -> {
            StringBuilder lines = new StringBuilder();
            for (HistoricalEvent event : events) lines.append(SaveFormat.historyLine(event)).append('\n');
            store.append(SaveFormat.HISTORY, lines.toString().getBytes(StandardCharsets.UTF_8));
        });
    }

    /**
     * Like {@link #save(SaveStore)}, but historical events produced since the previous save go to
     * {@code historySink} instead of the store's log (e.g. split per volcano). The sink is not called
     * when there are none.
     */
    public void save(SaveStore store, java.util.function.Consumer<List<HistoricalEvent>> historySink) {
        writeState(store);
        if (!history.isEmpty()) historySink.accept(List.copyOf(history));
        history.clear();
    }

    /** Historical events produced since the last {@link #save}, oldest first. */
    public List<HistoricalEvent> unsavedHistory() {
        return List.copyOf(history);
    }

    /**
     * SHA-256 over the complete saved state (excluding the history log). Two engines with equal
     * hashes will produce identical futures.
     */
    public String stateHash() {
        InMemorySaveStore store = new InMemorySaveStore();
        writeState(store);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, byte[]> file : store.files().entrySet()) {
                digest.update(file.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(file.getValue());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void writeState(SaveStore store) {
        JsonObject meta = new JsonObject();
        meta.addProperty("format", STATE_FORMAT);
        meta.addProperty("engineVersion", ENGINE_VERSION);
        meta.addProperty("seed", seed);
        meta.addProperty("step", currentStep);
        meta.addProperty("timeMicros", timeMicros());
        meta.addProperty("baseStepMicros", baseStepMicros);
        meta.addProperty("lastStrideQuanta", lastStrideQuanta);

        JsonArray list = new JsonArray();
        for (Registered registered : subsystems) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", registered.subsystem.id());
            entry.addProperty("configHash", registered.configHash);
            entry.addProperty("nextQuanta", registered.nextQuanta);
            entry.add("config", registered.configJson);
            list.add(entry);

            SubsystemState state = new SubsystemState();
            registered.subsystem.saveState(state);
            SaveFormat.writeSubsystem(store, registered.subsystem.id(), registered.random.state(), state);
        }
        meta.add("subsystems", list);

        JsonArray commands = new JsonArray();
        for (EngineCommand command : pendingCommands) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", command.getClass().getName());
            try {
                entry.add("data", SaveFormat.gson().toJsonTree(command));
            } catch (RuntimeException e) {
                throw new IllegalStateException("Queued command " + command.getClass().getName()
                        + " cannot be saved; save between steps after it has been applied", e);
            }
            commands.add(entry);
        }
        meta.add("pendingCommands", commands);
        store.write(SaveFormat.META, SaveFormat.jsonBytes(meta));
    }

    private static JsonElement configJson(Subsystem subsystem) {
        Object config = subsystem.config();
        if (config == null) return JsonNull.INSTANCE;
        try {
            return SaveFormat.gson().toJsonTree(config);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Config of subsystem " + subsystem.id() + " ("
                    + config.getClass().getName() + ") cannot be serialised: " + e.getMessage(), e);
        }
    }

    private static String hash(JsonElement json) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(SaveFormat.gson().toJson(json).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A registered subsystem; its schedule and configuration change only through {@link #reconfigure}. */
    private static final class Registered {
        final Subsystem subsystem;
        final SimRandom random;
        final String lane;
        long periodSteps;
        long phaseSteps;
        /** The next schedule point (quanta) this subsystem has not yet covered. */
        long nextQuanta;
        JsonElement configJson;
        String configHash;

        Registered(Subsystem subsystem, long periodSteps, long phaseSteps, SimRandom random,
                JsonElement configJson, String configHash, String lane) {
            this.subsystem = subsystem;
            this.periodSteps = periodSteps;
            this.phaseSteps = phaseSteps;
            this.nextQuanta = phaseSteps;
            this.random = random;
            this.configJson = configJson;
            this.configHash = configHash;
            this.lane = lane;
        }

        /** Whether a schedule point not yet covered falls before {@code endQuanta}. */
        boolean isDue(long endQuanta) {
            return periodSteps > 0 && nextQuanta < endQuanta;
        }

        /** The first schedule point at or after {@code quanta}. */
        long nextPointAtOrAfter(long quanta) {
            if (quanta <= phaseSteps) return phaseSteps;
            return phaseSteps + Math.floorDiv(quanta - phaseSteps + periodSteps - 1, periodSteps) * periodSteps;
        }
    }

    public static final class Builder {
        private final long seed;
        private final SimRandom root;
        private final List<Subsystem> subsystems = new ArrayList<>();
        private long baseStepMicros = DEFAULT_BASE_STEP_MICROS;
        private double maxStepSeconds = Double.NaN;
        private SaveStore restoreFrom;
        private boolean allowConfigChanges;
        private int threads = Parallel.defaultThreads();

        private Builder(long seed) {
            this.seed = seed;
            this.root = new SimRandom(seed);
        }

        public Builder add(Subsystem subsystem) {
            subsystems.add(subsystem);
            return this;
        }

        /** Simulation base step. Subsystem periods are rounded to multiples of it. */
        public Builder baseStepMicros(long micros) {
            if (micros <= 0) throw new IllegalArgumentException("base step must be positive");
            this.baseStepMicros = micros;
            return this;
        }

        /**
         * Worker threads for data-parallel subsystem work (default {@link Parallel#defaultThreads()}).
         * Results are bit-identical for every thread count; this only changes speed.
         */
        public Builder threads(int threads) {
            if (threads < 1) throw new IllegalArgumentException("threads must be at least 1");
            this.threads = threads;
            return this;
        }

        /**
         * Adaptive stepping: steps grow (in powers of two of the base step) up to {@code maxStepSeconds}
         * while every subsystem allows it ({@link Subsystem#maxStepSeconds()}). Without it every step is
         * one base step.
         */
        public Builder adaptive(double maxStepSeconds) {
            if (!(maxStepSeconds > 0)) throw new IllegalArgumentException("maxStepSeconds must be > 0");
            this.maxStepSeconds = maxStepSeconds;
            return this;
        }

        public Builder baseStep(Duration step) {
            return baseStepMicros(step.toNanos() / 1000);
        }

        /**
         * Resumes from a save. Subsystems missing from the save start fresh; saved subsystems
         * without a matching registration are ignored. The seed and base step must match, and so
         * must each subsystem's configuration hash unless {@link #allowConfigChanges()} is set.
         */
        public Builder restore(SaveStore store) {
            this.restoreFrom = store;
            return this;
        }

        /** Accept a save whose subsystem configurations differ from the ones being registered. */
        public Builder allowConfigChanges() {
            this.allowConfigChanges = true;
            return this;
        }

        public Engine build() {
            JsonObject meta = null;
            Map<String, String> savedHashes = new LinkedHashMap<>();
            Map<String, Long> savedNext = new LinkedHashMap<>();
            if (restoreFrom != null) {
                byte[] bytes = restoreFrom.read(SaveFormat.META);
                if (bytes == null) throw new IllegalArgumentException("Save has no " + SaveFormat.META);
                meta = SaveFormat.parse(bytes);
                int format = meta.get("format").getAsInt();
                if (format != STATE_FORMAT) {
                    throw new IllegalArgumentException("Unsupported engine state format: " + format);
                }
                long savedSeed = meta.get("seed").getAsLong();
                if (savedSeed != seed) {
                    throw new IllegalArgumentException("State was saved with seed " + savedSeed + ", not " + seed);
                }
                long savedStep = meta.get("baseStepMicros").getAsLong();
                if (savedStep != baseStepMicros) {
                    throw new IllegalArgumentException("State was saved with a base step of " + savedStep
                            + " µs; build the engine with the same base step (not " + baseStepMicros + " µs)");
                }
                for (JsonElement e : meta.getAsJsonArray("subsystems")) {
                    JsonObject entry = e.getAsJsonObject();
                    savedHashes.put(entry.get("id").getAsString(), entry.get("configHash").getAsString());
                    if (entry.has("nextQuanta")) savedNext.put(entry.get("id").getAsString(), entry.get("nextQuanta").getAsLong());
                }
            }

            CommandBus bus = new CommandBus();
            Set<String> ids = new HashSet<>();
            List<Registered> registered = new ArrayList<>();

            for (Subsystem subsystem : subsystems) {
                String id = subsystem.id();
                if (!ids.add(id)) {
                    throw new IllegalArgumentException("Duplicate subsystem id: " + id);
                }
                double period = subsystem.periodSeconds();
                double phase = subsystem.phaseSeconds();
                if (!(period >= 0)) throw new IllegalArgumentException("Subsystem " + id + " has a negative period");
                long periodSteps = Double.isInfinite(period)
                        ? 0
                        : Math.max(1, Math.round(SimTime.micros(period) / (double) baseStepMicros));
                long phaseSteps = Math.round(SimTime.micros(phase) / (double) baseStepMicros);
                if (phaseSteps < 0 || (periodSteps > 0 && phaseSteps >= periodSteps)) {
                    throw new IllegalArgumentException(
                            "Subsystem " + id + " phase must be in [0, period): " + phase + " s vs " + period + " s");
                }
                subsystem.registerCommands(bus);

                JsonElement configJson = configJson(subsystem);
                String configHash = hash(configJson);
                SimRandom random = root.fork("subsystem:" + id);

                if (restoreFrom != null && savedHashes.containsKey(id)) {
                    if (!allowConfigChanges && !savedHashes.get(id).equals(configHash)) {
                        throw new IllegalStateException("Configuration of subsystem " + id
                                + " differs from the save (hash " + savedHashes.get(id).substring(0, 12) + "… vs "
                                + configHash.substring(0, 12) + "…); rebuild it with the saved configuration or call"
                                + " allowConfigChanges()");
                    }
                    SaveFormat.LoadedSubsystem loaded = SaveFormat.readSubsystem(restoreFrom, id);
                    if (loaded != null) {
                        random.restore(loaded.randomState());
                        subsystem.loadState(loaded.state());
                    }
                }

                registered.add(new Registered(subsystem, periodSteps, phaseSteps, random, configJson, configHash,
                        subsystem.concurrencyLane()));
            }

            long startStep = meta == null ? 0 : meta.get("step").getAsLong();
            // saves before adaptive stepping counted one quantum per step
            long startQuanta = meta == null ? 0
                    : meta.has("timeMicros") ? meta.get("timeMicros").getAsLong() / baseStepMicros : startStep;
            for (Registered r : registered) {
                Long next = savedNext.get(r.subsystem.id());
                r.nextQuanta = next != null ? next : r.nextPointAtOrAfter(startQuanta);
            }
            long maxStride = Double.isNaN(maxStepSeconds) ? 1
                    : Math.max(1, (long) Math.floor(maxStepSeconds * SimTime.MICROS_PER_SECOND / baseStepMicros));
            Engine engine = new Engine(seed, baseStepMicros, maxStride, startStep, startQuanta, List.copyOf(registered),
                    bus, Parallel.of(threads));
            if (meta != null && meta.has("lastStrideQuanta")) engine.lastStrideQuanta = meta.get("lastStrideQuanta").getAsLong();
            if (meta != null) {
                for (JsonElement e : meta.getAsJsonArray("pendingCommands")) {
                    JsonObject entry = e.getAsJsonObject();
                    String type = entry.get("type").getAsString();
                    try {
                        Class<?> cls = Class.forName(type, true, Thread.currentThread().getContextClassLoader());
                        engine.submit((EngineCommand) SaveFormat.gson().fromJson(entry.get("data"), cls));
                    } catch (ClassNotFoundException | RuntimeException ex) {
                        throw new IllegalStateException("Cannot restore queued command " + type, ex);
                    }
                }
            }
            return engine;
        }
    }
}
