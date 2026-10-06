package me.alex4386.typhon.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.alex4386.typhon.engine.assembly.VolcanoCoupler;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.dike.Dike;
import me.alex4386.typhon.engine.dike.DikeCommands;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.magma.MagmaCommands;
import me.alex4386.typhon.engine.massflow.MassFlowCommands;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.save.DirectorySaveStore;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.save.SaveStore;
import me.alex4386.typhon.engine.config.WorldDefinition;
import me.alex4386.typhon.engine.worlds.WorldDirectory;
import me.alex4386.typhon.engine.sim.EngineRunner;
import me.alex4386.typhon.engine.subsurface.Subsurface;
import me.alex4386.typhon.engine.tephra.TephraCommands;
import me.alex4386.typhon.engine.tephra.WindField;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.VentCommands;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.volcano.VentStatus;
import me.alex4386.typhon.engine.worlds.World;
import me.alex4386.typhon.server.protocol.Field;
import me.alex4386.typhon.simulator.scenario.Preset;
import me.alex4386.typhon.simulator.scenario.Presets;
import me.alex4386.typhon.simulator.scenario.Scenario;
import me.alex4386.typhon.simulator.scenario.WorldScenarios;
import me.alex4386.typhon.simulator.terrain.ColumnGrid;

/**
 * One simulation session: a scenario (preset or world directory) driven by an {@link EngineRunner},
 * plus everything the protocol needs around it — tile cache, translated event log, unit table,
 * replay keyframes. Several clients may watch the same session.
 *
 * <p>Everything that reads simulation state goes through {@link #call}, which runs on the engine
 * thread between steps (or, while viewing a replay keyframe, on the session's own executor).
 */
final class Session implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(Session.class.getName());
    static final double KEYFRAME_SECONDS = 300;
    static final int MAX_KEYFRAMES = 64;
    static final int EVENT_LOG_LIMIT = 20000;
    /** Milestones kept for attaching clients regardless of how many routine events followed them. */
    static final int MILESTONE_LIMIT = 4000;
    /**
     * Event kinds that mark a change in what a volcano is doing (eruptions, alert and style changes,
     * dikes, new vents, notable features): an attaching client always gets these in its backlog, so its
     * "key events" view is complete even after hours of quakes, bombs and plume updates.
     */
    static final Set<String> MILESTONES = Set.of("eruptionStarted", "eruptionEnded", "alertChanged", "regimeChanged",
            "styleEstimated", "dikeStarted", "dikeStalled", "fissureOpened", "message");
    static final String SESSION_FILE = "session.json";

    enum Kind { PRESET, WORLD }

    /** How to rebuild the scenario (for replay keyframes and saves). */
    record Source(Kind kind, String preset, long seed, long baseStepMicros, Path worldDir) {}

    /** A replay keyframe: an engine + host save (in memory for presets, on disk for worlds). */
    record Keyframe(double time, SaveStore store) {}

    /** Directory of a world's persisted replay keyframes ({@code <world>/replay/k-<micros>}). */
    static final String REPLAY_DIR = "replay";

    final String id;
    private final double initialSpeed;
    private final ExecutorService viewExec;

    private volatile String name;
    private volatile Source source;
    private volatile Scenario live;
    private volatile EngineRunner runner;
    private Thread drainThread;
    private final Object voxelLock = new Object();

    private volatile boolean replay;
    private volatile Scenario replayScenario;
    private volatile double replayTime;
    private EngineRunner.Mode modeBeforeReplay = EngineRunner.Mode.REALTIME;
    private double speedBeforeReplay;

    private volatile GridMapping map;
    private volatile TileStore tiles;
    private volatile LodTiles lod;
    private volatile int[] tileOrder;
    private final Map<Field, Long> lastRefreshNanos = new EnumMap<>(Field.class);
    private final Map<Field, Double> lastRefreshSim = new EnumMap<>(Field.class);
    private final Set<Field> forceRefresh = new HashSet<>();

    private volatile EventTranslator translator;
    /** Selectable things in the world (§4.8). */
    private volatile EntityTracker entities;
    private final Deque<JsonObject> eventLog = new ArrayDeque<>();
    /** Milestone events (guarded by {@link #eventLog}), plus the first ocean entry and first of each feature type. */
    private final Deque<JsonObject> milestones = new ArrayDeque<>();
    /** Kinds of "firsts" already in {@link #milestones} (guarded by {@link #eventLog}). */
    private final Set<String> firsts = new HashSet<>();
    final Map<String, List<String>> activeVents = new ConcurrentHashMap<>();
    private final JsonArray units = new JsonArray();
    private int unitsKnown;
    private long droppedSeen;

    private volatile double rainMmPerHour;
    private volatile double windSpeed;
    private volatile double windBearingDeg;

    private final List<Keyframe> keyframes = new ArrayList<>();
    private double lastKeyframeTime = Double.NEGATIVE_INFINITY;

    private double rate;
    private long rateWallNanos;
    private double rateSimTime;

    /** Cheap per-volcano status for the session list (refreshed by {@link #refreshSummary}). */
    record VolcanoSummary(String id, String alert, boolean erupting, double dormantCompression,
            double eruptiveCompression) {
        double currentCompression() {
            return erupting ? eruptiveCompression : dormantCompression;
        }
    }

    private volatile List<VolcanoSummary> summary = List.of();
    private long lastSummaryNanos;
    /**
     * Approximate physical (volcano) time elapsed per volcano since this session started:
     * simulated time × that volcano's current time compression, integrated by the pump.
     */
    private final Map<String, Double> physicalTime = new ConcurrentHashMap<>();

    private Session(String id, double initialSpeed) {
        this.id = id;
        this.initialSpeed = initialSpeed;
        this.viewExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "typhon-session-" + id);
            t.setDaemon(true);
            return t;
        });
    }

    // ── Creation ──

    static Session preset(String id, String presetName, long seed, long baseStepMicros, double speed) {
        Preset preset = Presets.get(presetName);
        Scenario scenario = preset.build(seed, preset.terrain(seed),
                Scenario.Options.DEFAULT.withBaseStepMicros(baseStepMicros));
        Session s = new Session(id, speed);
        s.start(preset.title(), new Source(Kind.PRESET, preset.name(), seed, baseStepMicros, null), scenario);
        return s;
    }

    static Session world(String id, Path dir, World.ChangePolicy policy, double speed) {
        Scenario scenario = WorldScenarios.open(dir, policy);
        Session s = new Session(id, speed);
        s.start(scenario.presetName(), new Source(Kind.WORLD, null, scenario.seed(),
                scenario.engine().baseStepMicros(), dir), scenario);
        return s;
    }

    /** Opens what {@link #save} wrote under {@code dir}: a world directory or a preset save. */
    static Scenario openSaved(Path dir, Source[] sourceOut) throws IOException {
        if (Files.exists(dir.resolve("world.yaml"))) {
            Scenario scenario = WorldScenarios.open(dir, World.ChangePolicy.REJECT);
            sourceOut[0] = new Source(Kind.WORLD, null, scenario.seed(), scenario.engine().baseStepMicros(), dir);
            return scenario;
        }
        Path file = dir.resolve(SESSION_FILE);
        if (!Files.exists(file)) throw new IOException("No saved session in " + dir);
        JsonObject meta = Json.GSON.fromJson(Files.readString(file), JsonObject.class);
        Preset preset = Presets.get(meta.get("preset").getAsString());
        long seed = meta.get("seed").getAsLong();
        long base = meta.get("baseStepMicros").getAsLong();
        Scenario scenario = preset.build(seed, preset.terrain(seed), Scenario.Options.DEFAULT.withBaseStepMicros(base)
                .withRestore(new DirectorySaveStore(dir.resolve("state"))));
        sourceOut[0] = new Source(Kind.PRESET, preset.name(), seed, base, null);
        return scenario;
    }

    /** Replaces the live scenario (load); clients must be re-attached by the caller. */
    synchronized void replace(String newName, Source newSource, Scenario scenario) {
        stopRunner();
        replay = false;
        replayScenario = null;
        start(newName, newSource, scenario);
    }

    private synchronized void start(String newName, Source newSource, Scenario scenario) {
        this.name = newName;
        this.source = newSource;
        boolean fresh = !scenario.restored() && scenario.engine().currentStep() == 0;
        if (fresh) {
            EngineFrame first = scenario.engine().step();
            for (BlockChange c : first.blockChanges()) scenario.world().apply(c);
            scenario.runAfterFirstTick();
        }
        this.live = scenario;

        ColumnGrid grid = scenario.initialTerrain();
        double cell = scenario.terrain().world().spec().metersPerColumn();
        int size = grid.size();
        int tile = size % 64 == 0 ? 64 : size % 32 == 0 ? 32 : 16; // fewer, larger tiles render cheaper in the client
        long base = tiles == null ? 0 : tiles.maxVersion();
        this.map = new GridMapping(cell, tile, grid.minX(), grid.minZ(), grid.maxX(), grid.maxZ());
        installDepthStretch(map, scenario);
        this.tiles = new TileStore(map, base);
        this.lod = new LodTiles(scenario, map, base);
        this.tileOrder = order(map, scenario);
        lastRefreshNanos.clear();
        lastRefreshSim.clear();
        forceRefresh.clear();

        this.translator = new EventTranslator(map, scenario.volcanoes(), v -> activeVents.getOrDefault(v, List.of()));
        List<String> volcanoIds = new ArrayList<>();
        for (VolcanoSystem v : scenario.volcanoes()) volcanoIds.add(v.volcanoId());
        this.entities = new EntityTracker(map, volcanoIds);
        synchronized (eventLog) {
            eventLog.clear();
            milestones.clear();
            firsts.clear();
        }
        activeVents.clear();
        synchronized (units) {
            while (!units.isEmpty()) units.remove(units.size() - 1);
            unitsKnown = 0;
        }
        keyframes.clear();
        lastKeyframeTime = Double.NEGATIVE_INFINITY;
        if (newSource.kind() == Kind.WORLD && newSource.worldDir() != null) {
            loadWorldKeyframes(newSource.worldDir(), scenario.engine().time());
        }

        // Initial weather as configured in the scenario.
        VolcanoSystem first = scenario.volcanoes().get(0);
        WindField wind = first.tephra().wind();
        windSpeed = wind.baseSpeed() / first.scaling().velocityScale();
        windBearingDeg = normalizeDeg(Math.toDegrees(wind.baseDirectionRad() + Math.PI / 2));
        rainMmPerHour = first.lahars() == null ? 0 : first.lahars().rainfall();

        EngineRunner.Options options = new EngineRunner.Options(EngineRunner.Mode.REALTIME,
                runner == null ? initialSpeed : runnerSpeedOr(initialSpeed), 512, 16384, 33, 20);
        EngineRunner r = new EngineRunner(scenario.engine(), options,
                t -> LOG.log(Level.SEVERE, "Engine of session " + id + " failed", t));
        this.runner = r;
        r.start();
        rateWallNanos = System.nanoTime();
        rateSimTime = scenario.engine().time();
        rate = 0;
        droppedSeen = 0;
        physicalTime.clear();
        summary = summarize(scenario);

        Thread drain = new Thread(() -> drainLoop(r, scenario), "typhon-frames-" + id);
        drain.setDaemon(true);
        this.drainThread = drain;
        drain.start();
    }

    private double runnerSpeedOr(double fallback) {
        EngineRunner r = runner;
        return r == null ? fallback : r.speed();
    }

    private void drainLoop(EngineRunner r, Scenario scenario) {
        try {
            while (r.isRunning() || r.pendingFrames() > 0) {
                EngineFrame f = r.pollFrame(100, TimeUnit.MILLISECONDS);
                if (f == null) continue;
                synchronized (voxelLock) {
                    for (BlockChange c : f.blockChanges()) scenario.world().apply(c);
                }
            }
        } catch (InterruptedException e) {
            // closing
        }
    }

    /** Applies block changes still queued (engine thread, before saving). */
    private void drainFramesNow(EngineRunner r, Scenario scenario) {
        synchronized (voxelLock) {
            EngineFrame f;
            while ((f = r.pollFrame()) != null) {
                for (BlockChange c : f.blockChanges()) scenario.world().apply(c);
            }
        }
    }

    /**
     * Samples a coarse ground-elevation grid and the first volcano's depth compression so that
     * subsurface points (chamber, hypocentres, dikes) are drawn at their physical depth. Runs while
     * the engine is not stepping.
     */
    static void installDepthStretch(GridMapping map, Scenario scenario) {
        var world = scenario.terrain().world();
        int step = 8;
        int w = (map.maxX - map.minX) / step + 1;
        int h = (map.maxZ - map.minZ) / step + 1;
        float[] ground = new float[w * h];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                double s = world.surfaceZ(map.minX + i * step, map.minZ + j * step);
                ground[j * w + i] = (float) (Double.isFinite(s) ? s : world.spec().datumZ());
            }
        }
        double scale = 1;
        for (VolcanoSystem v : scenario.volcanoes()) {
            double physical = v.chamber().physicalDepthM();
            var c = v.chamber().chamberCenter();
            double g = world.surfaceZ(c.x(), c.z());
            double blockDepth = g - (c.y() + 0.5) * map.cell - 2 * map.cell;
            if (Double.isFinite(physical) && Double.isFinite(g) && blockDepth > 0) {
                scale = Math.max(1, (physical - 2 * map.cell) / blockDepth);
                break;
            }
        }
        map.setDepthStretch(ground, step, w, h, scale);
    }

    private static int[] order(GridMapping map, Scenario scenario) {
        List<double[]> vents = new ArrayList<>();
        for (VolcanoSystem v : scenario.volcanoes()) {
            for (VentSite vent : v.vents()) vents.add(new double[] {map.x(vent.position().x()), map.y(vent.position().z())});
        }
        int n = map.tilesX * map.tilesY;
        Integer[] idx = new Integer[n];
        double[] dist = new double[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
            double cx = map.originX() + ((i % map.tilesX) + 0.5) * map.tileSize * map.cell;
            double cy = map.originY() + ((i / map.tilesX) + 0.5) * map.tileSize * map.cell;
            double best = Double.POSITIVE_INFINITY;
            for (double[] v : vents) best = Math.min(best, Math.hypot(cx - v[0], cy - v[1]));
            dist[i] = best;
        }
        java.util.Arrays.sort(idx, Comparator.comparingDouble(i -> dist[i]));
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = idx[i];
        return out;
    }

    // ── Access ──

    String name() { return name; }
    Source source() { return source; }
    GridMapping map() { return map; }
    TileStore tiles() { return tiles; }
    LodTiles lod() { return lod; }
    int[] tileOrder() { return tileOrder; }
    boolean replay() { return replay; }
    EngineRunner runner() { return runner; }
    Scenario live() { return live; }

    /** Simulation time currently shown (live, or the replay position). */
    double time() {
        return replay && replayScenario != null ? replayTime : live.engine().time();
    }

    /**
     * Runs {@code fn} against the scenario currently viewed: on the engine thread between steps for
     * the live run, or on the session executor for a replay keyframe.
     */
    <T> CompletableFuture<T> call(Function<Scenario, T> fn) {
        Scenario view = replayScenario;
        if (replay && view != null) return CompletableFuture.supplyAsync(() -> fn.apply(view), viewExec);
        Scenario s = live;
        return runner.onEngineThread(engine -> fn.apply(s));
    }

    // ── Clock / state / events / units ──

    JsonObject clock() {
        EngineRunner r = runner;
        JsonObject o = Json.obj("clock");
        o.add("time", Json.num(time()));
        long step = replay && replayScenario != null ? replayScenario.engine().currentStep() : r.completedStep();
        o.addProperty("step", step);
        o.add("baseStep", Json.num(live.engine().baseStepMicros() / 1e6));
        o.addProperty("mode", replay ? "PAUSED" : r.mode().name());
        o.add("speed", Json.num(replay ? speedBeforeReplay : r.speed()));
        o.add("rate", Json.num(replay ? 0 : rate));
        o.addProperty("replay", replay);
        List<VolcanoSummary> sum = summary;
        if (!sum.isEmpty()) {
            VolcanoSummary primary = sum.get(0);
            o.add("compression", Json.num(primary.currentCompression()));
            o.add("physicalTime", Json.num(physicalTime.getOrDefault(primary.id(), 0.0)));
        }
        return o;
    }

    private static List<VolcanoSummary> summarize(Scenario s) {
        List<VolcanoSummary> out = new ArrayList<>();
        for (VolcanoSystem v : s.volcanoes()) {
            var level = v.alert().level(); // null until the estimator's first sample (fresh or reset volcano)
            out.add(new VolcanoSummary(v.volcanoId(), level == null ? "DORMANT" : level.name(), v.chamber().erupting(),
                    v.scaling().dormantTimeCompression(), v.scaling().eruptiveTimeCompression()));
        }
        return List.copyOf(out);
    }

    /** Refreshes the per-volcano summary at most once a second (asynchronously, on the engine thread). */
    void refreshSummary() {
        long now = System.nanoTime();
        if (replay || (now - lastSummaryNanos) / 1_000_000 < 1000) return;
        lastSummaryNanos = now;
        Scenario s = live;
        runner.onEngineThread(e -> summarize(s)).thenAccept(list -> summary = list);
    }

    List<VolcanoSummary> summary() {
        return summary;
    }

    void updateRate() {
        long now = System.nanoTime();
        double t = live.engine().time();
        double wall = (now - rateWallNanos) / 1e9;
        if (wall < 0.5) return;
        double measured = Math.max(0, t - rateSimTime) / wall;
        rate = rate == 0 ? measured : 0.5 * rate + 0.5 * measured;
        double dt = Math.max(0, t - rateSimTime);
        for (VolcanoSummary v : summary) physicalTime.merge(v.id(), dt * v.currentCompression(), Double::sum);
        rateWallNanos = now;
        rateSimTime = t;
    }

    /** State message; also collects new units (returned in {@code newUnits}). */
    JsonObject state(JsonArray newUnits) throws Exception {
        GridMapping m = map;
        record Result(JsonObject volcanoes, JsonArray units, double time) {}
        int from;
        synchronized (units) {
            from = unitsKnown;
        }
        Result res = call(s -> new Result(Probe.volcanoStates(s, m, activeVents), Probe.units(s.terrain().world(), from),
                s.engine().time())).get(5, TimeUnit.SECONDS);
        synchronized (units) {
            if (from == unitsKnown) {
                for (int i = 0; i < res.units().size(); i++) {
                    units.add(res.units().get(i));
                    newUnits.add(res.units().get(i));
                }
                unitsKnown += res.units().size();
            }
        }
        JsonObject o = Json.obj("state");
        o.add("time", Json.num(res.time()));
        JsonObject world = new JsonObject();
        world.add("rainMmPerHour", Json.num(rainMmPerHour));
        JsonObject wind = new JsonObject();
        wind.add("speed", Json.num(windSpeed));
        wind.add("bearingDeg", Json.num(windBearingDeg));
        world.add("wind", wind);
        o.add("world", world);
        for (VolcanoSummary v : summary) {
            JsonElement e = res.volcanoes().get(v.id());
            if (e == null || !e.isJsonObject()) continue;
            JsonObject tc = new JsonObject();
            tc.add("dormant", Json.num(v.dormantCompression()));
            tc.add("eruptive", Json.num(v.eruptiveCompression()));
            tc.add("current", Json.num(v.currentCompression()));
            e.getAsJsonObject().add("timeCompression", tc);
            e.getAsJsonObject().add("physicalTime", Json.num(physicalTime.getOrDefault(v.id(), 0.0)));
        }
        o.add("volcanoes", res.volcanoes());
        return o;
    }

    /**
     * Every unit known so far. Pulls units the engine created since the last state message first, so
     * a client attaching before the first state pump still gets the pre-existing geology units.
     */
    JsonArray allUnits() {
        int from;
        synchronized (units) {
            from = unitsKnown;
        }
        JsonArray all;
        synchronized (units) {
            all = units.deepCopy();
        }
        try {
            // Not added to the shared cache: the state pump still broadcasts these as new units to
            // clients that are already attached (re-sending a known unit id is harmless).
            // Attaching is rare: wait out a slow engine step (e.g. a subsurface macro step under load).
            JsonArray fresh = call(s -> Probe.units(s.terrain().world(), from)).get(60, TimeUnit.SECONDS);
            all.addAll(fresh);
        } catch (Exception e) {
            // Fall back to what the state pump has collected; the next state message carries the rest.
        }
        return all;
    }

    /** Drains the runner's event ring; returns an {@code events} message or {@code null}. */
    JsonObject pumpEvents() {
        if (replay) return null;
        EngineRunner r = runner;
        List<EngineEvent> raw = new ArrayList<>();
        r.drainEvents(raw);
        long dropped = r.droppedEvents();
        long newDropped = dropped - droppedSeen;
        droppedSeen = dropped;
        JsonArray out = new JsonArray();
        EventTranslator t = translator;
        EntityTracker tracker = entities;
        for (EngineEvent e : raw) {
            if (tracker != null) tracker.observe(e);
            JsonObject j = t.translate(e);
            if (j == null) continue;
            out.add(j);
        }
        if (out.isEmpty() && newDropped == 0) return null;
        synchronized (eventLog) {
            for (int i = 0; i < out.size(); i++) {
                JsonObject e = out.get(i).getAsJsonObject();
                eventLog.addLast(e);
                if (isMilestone(e, firsts)) milestones.addLast(e);
            }
            while (eventLog.size() > EVENT_LOG_LIMIT) eventLog.removeFirst();
            while (milestones.size() > MILESTONE_LIMIT) milestones.removeFirst();
        }
        JsonObject msg = Json.obj("events");
        msg.add("events", out);
        msg.addProperty("dropped", newDropped);
        return msg;
    }

    /**
     * Whether an event goes into the milestone log: milestone kinds, and the first ocean entry and the
     * first geothermal feature of each type per volcano (later ones are routine; `firsts` remembers them).
     */
    static boolean isMilestone(JsonObject e, Set<String> firsts) {
        String kind = e.get("kind").getAsString();
        if (MILESTONES.contains(kind)) return true;
        String first = switch (kind) {
            case "oceanEntry" -> "oceanEntry";
            case "geothermalFeature" -> "feature:" + e.get("volcanoId").getAsString() + ":" + e.get("feature").getAsString();
            default -> null;
        };
        return first != null && firsts.add(first);
    }

    /**
     * Backlog for (re)attaching clients: every milestone, recent other events, and the latest ~600
     * seismic ones (and bombs), in time order, up to {@code until}.
     */
    JsonObject backlog(double until) {
        List<JsonObject> all;
        synchronized (eventLog) {
            all = selectBacklog(eventLog, milestones, until);
        }
        JsonArray arr = new JsonArray();
        all.forEach(arr::add);
        JsonObject msg = Json.obj("events");
        msg.add("events", arr);
        msg.addProperty("dropped", 0);
        return msg;
    }

    /** The backlog events (see {@link #backlog}) from an event log and its milestone log, in time order. */
    static List<JsonObject> selectBacklog(Iterable<JsonObject> log, Iterable<JsonObject> milestones, double until) {
        List<JsonObject> seismic = new ArrayList<>();
        List<JsonObject> other = new ArrayList<>();
        Set<JsonObject> included = Collections.newSetFromMap(new IdentityHashMap<>());
        List<JsonObject> all = new ArrayList<>();
        for (JsonObject e : milestones) {
            if (e.get("time").getAsDouble() <= until && included.add(e)) all.add(e);
        }
        for (JsonObject e : log) {
            if (e.get("time").getAsDouble() > until) continue;
            String kind = e.get("kind").getAsString();
            if (kind.equals("seismic") || kind.equals("bombLaunched")) seismic.add(e);
            else other.add(e);
        }
        for (JsonObject e : other.subList(Math.max(0, other.size() - 1500), other.size())) if (included.add(e)) all.add(e);
        all.addAll(seismic.subList(Math.max(0, seismic.size() - 600), seismic.size()));
        all.sort(Comparator.comparingDouble(e -> e.get("time").getAsDouble()));
        return all;
    }

    // ── Entities and inspection (§4.8, §3.7) ──

    private Map<String, JsonObject> entitySnapshot() throws Exception {
        GridMapping m = map;
        Map<String, List<String>> vents = Map.copyOf(activeVents);
        return call(s -> EntityTracker.snapshot(s, m, vents)).get(30, TimeUnit.SECONDS);
    }

    /** Entity changes since the last call, or {@code null} (broadcast to every watcher). */
    JsonObject entitiesDelta() throws Exception {
        EntityTracker t = entities;
        return t == null ? null : t.delta(entitySnapshot(), time(), false);
    }

    /** Every entity, for a client that is (re)attaching. */
    JsonObject entitiesFull() throws Exception {
        EntityTracker t = entities;
        if (t == null) {
            JsonObject empty = Json.obj("entities");
            empty.addProperty("replace", true);
            empty.add("upsert", new com.google.gson.JsonArray());
            empty.add("remove", new com.google.gson.JsonArray());
            return empty;
        }
        return t.full(entitySnapshot(), time());
    }

    /** Forgets what clients hold (replay jump): the next full message re-creates everything. */
    void resetEntities() {
        EntityTracker t = entities;
        if (t != null) t.reset();
    }

    /** Everything known about the column under world point (x, y) metres. */
    JsonObject inspect(double x, double y) throws Exception {
        GridMapping m = map;
        return call(s -> Inspector.inspect(s, m, x, y)).get(30, TimeUnit.SECONDS);
    }

    JsonObject worldInfo() throws Exception {
        GridMapping m = map;
        String n = name;
        LodTiles pyramid = lod;
        return call(s -> Probe.worldInfo(s, m, n, pyramid)).get(10, TimeUnit.SECONDS);
    }

    JsonObject replayInfo() {
        JsonObject o = Json.obj("replayInfo");
        JsonArray times = new JsonArray();
        double start;
        synchronized (keyframes) {
            for (Keyframe k : keyframes) times.add(Json.num(k.time()));
            start = keyframes.isEmpty() ? live.engine().time() : keyframes.get(0).time();
        }
        o.add("start", Json.num(start));
        o.add("end", Json.num(live.engine().time()));
        o.add("keyframes", times);
        return o;
    }

    // ── Tiles ──

    /**
     * Re-samples the subscribed fields whose refresh interval elapsed and updates the tile store.
     * Returns the number of tile versions that changed.
     */
    int refreshTiles(Set<Field> subscribed) throws Exception {
        return refreshTiles(subscribed, Set.of());
    }

    /** {@link #refreshTiles(Set)} also refreshing pyramid levels {@code levels} (§5.5). */
    int refreshTiles(Set<Field> subscribed, Set<Integer> levels) throws Exception {
        LodTiles pyramid = lod;
        long now = System.nanoTime();
        double simTime = time();
        Set<Field> due = new HashSet<>();
        boolean forced;
        synchronized (forceRefresh) {
            for (Field f : subscribed) {
                if (!FieldSampler.AVAILABLE.contains(f)) continue;
                Long last = lastRefreshNanos.get(f);
                Double lastSim = lastRefreshSim.get(f);
                boolean force = forceRefresh.contains(f) || !tiles.has(f);
                for (int level : levels) {
                    LodTiles.Level l = pyramid.level(level);
                    if (l != null && l.fields.contains(f) && !l.store.has(f)) force = true;
                }
                boolean intervalDue = last == null || (now - last) / 1e9 >= f.refreshSeconds;
                boolean changed = lastSim == null || lastSim != simTime;
                if (force || (intervalDue && changed)) due.add(f);
            }
            forced = !forceRefresh.isEmpty();
        }
        if (due.isEmpty()) return 0;
        GridMapping m = map;
        TileStore store = tiles;
        record Live(Map<Field, float[][]> values, LodTiles.EngineSample lod, double time) {}
        Live live = call(s -> new Live(FieldSampler.sample(s, m, due),
                levels.isEmpty() ? null : pyramid.sampleEngine(s, due, levels), s.engine().time()))
                .get(10, TimeUnit.SECONDS);
        // The pyramid's coarse levels are block means of the level-0 tiles: built off the engine thread.
        record Sampled(Map<Field, float[][]> values, Map<Integer, Map<Field, float[][]>> lod, double time) {}
        Sampled sampled = new Sampled(live.values(),
                live.lod() == null ? Map.of() : pyramid.assemble(live.values(), live.lod(), levels), live.time());
        int changed = 0;
        for (Map.Entry<Field, float[][]> e : sampled.values().entrySet()) {
            boolean force;
            synchronized (forceRefresh) {
                force = forceRefresh.remove(e.getKey());
            }
            changed += store.update(e.getKey(), e.getValue(), sampled.time(), force);
            for (Map.Entry<Integer, Map<Field, float[][]>> level : sampled.lod().entrySet()) {
                float[][] values = level.getValue().get(e.getKey());
                if (values != null) changed += pyramid.level(level.getKey()).store.update(e.getKey(), values, sampled.time(), force);
            }
            lastRefreshNanos.put(e.getKey(), now);
            lastRefreshSim.put(e.getKey(), simTime);
        }
        return changed;
    }

    /** Every field is re-sent at a new version on its next refresh (replay jump, load). */
    void forceAllTiles() {
        synchronized (forceRefresh) {
            for (Field f : Field.values()) forceRefresh.add(f);
        }
        lastRefreshNanos.clear();
        lastRefreshSim.clear();
    }

    // ── Sections ──

    byte[] section(SectionBuilder.Request req) throws Exception {
        GridMapping m = map;
        EventTranslator t = translator;
        return call(s -> {
            Set<String> active = new HashSet<>();
            for (String key : t.dikePaths().keySet()) if (t.dikeActive(key)) active.add(key);
            return SectionBuilder.build(s, m, req, t.dikePaths(), active, s.engine().time());
        }).get(20, TimeUnit.SECONDS);
    }

    // ── Transport ──

    String transport(String mode, Double speed) {
        if (replay) return "Transport is disabled in replay mode";
        EngineRunner r = runner;
        switch (mode) {
            case "REALTIME" -> r.realtime(clamp(speed == null ? r.speed() : speed));
            case "UNBOUNDED" -> {
                if (speed != null) r.realtime(clamp(speed));
                r.unbounded();
            }
            case "PAUSED" -> r.pause();
            default -> {
                return "Unknown transport mode " + mode;
            }
        }
        return null;
    }

    String step(Long steps, Double seconds) {
        if (replay) return "Stepping is disabled in replay mode";
        long n;
        if (steps != null) n = steps;
        else if (seconds != null) n = (long) Math.ceil(seconds * 1e6 / live.engine().baseStepMicros());
        else n = 1;
        runner.step((int) Math.max(1, Math.min(Integer.MAX_VALUE, n)));
        return null;
    }

    String pauseAt(Double time) {
        if (replay) return "Transport is disabled in replay mode";
        if (time == null) runner.pauseAtStep(Long.MAX_VALUE);
        else runner.pauseAtTime(time);
        return null;
    }

    private static double clamp(double speed) {
        return Math.max(0.1, Math.min(1000, speed));
    }

    // ── Commands (§3.3) ──

    /** Result of a command: {@code ok} with an optional note, or an error code and message. */
    record CommandResult(boolean ok, String code, String message) {
        static CommandResult ok(String note) { return new CommandResult(true, null, note); }
        static CommandResult error(String code, String message) { return new CommandResult(false, code, message); }
    }

    CompletableFuture<CommandResult> command(JsonObject cmd) {
        if (replay) return done(CommandResult.error("unsupported", "Commands are disabled in replay mode"));
        String kind = Json.str(cmd, "kind");
        if (kind == null) return done(CommandResult.error("badRequest", "command.kind is required"));
        EngineRunner r = runner;
        Scenario s = live;
        try {
            switch (kind) {
                case "startEruption", "stopEruption", "forceDike", "injectMagma" -> {
                    String vid = Json.str(cmd, "volcanoId");
                    VolcanoSystem v = volcano(s, vid);
                    if (v == null) return done(CommandResult.error("unknownVolcano", "Unknown volcano " + vid));
                    switch (kind) {
                        case "startEruption" -> r.submit(new MagmaCommands.StartEruption(vid));
                        case "stopEruption" -> r.submit(new MagmaCommands.StopEruption(vid));
                        case "forceDike" -> {
                            if (v.dikes() == null) {
                                return done(CommandResult.error("unsupported", "Volcano " + vid + " has dikes disabled"));
                            }
                            r.submit(new DikeCommands.ForceDike(vid));
                        }
                        default -> {
                            MagmaCommands.InjectRecharge inject;
                            try {
                                inject = Tuning.injection(vid, cmd, v.chamber().supply());
                            } catch (IllegalArgumentException e) {
                                return done(CommandResult.error("badRequest", e.getMessage()));
                            }
                            r.submit(inject);
                            return done(CommandResult.ok(Tuning.injectionWarning(inject.volume(), v.chamber())));
                        }
                    }
                    return done(CommandResult.ok(null));
                }
                case "sealVent", "unsealVent", "removeVent" -> {
                    String vid = Json.str(cmd, "volcanoId");
                    String ventId = Json.str(cmd, "ventId");
                    VolcanoSystem v = volcano(s, vid);
                    if (v == null) return done(CommandResult.error("unknownVolcano", "Unknown volcano " + vid));
                    if (ventId == null) return done(CommandResult.error("badRequest", "ventId is required"));
                    return r.onEngineThread(e -> {
                        VolcanoCoupler c = v.coupler();
                        VentStatus status = c.ventStatus(ventId);
                        if (status == null) return CommandResult.error("unknownVent", "Unknown vent " + ventId);
                        boolean crater = v.vents().stream().anyMatch(vent -> vent.id().equals(ventId));
                        switch (kind) {
                            case "sealVent" -> {
                                if (c.sealed(ventId)) return CommandResult.ok("Already sealed");
                                e.submit(new VentCommands.SealVent(vid, ventId));
                            }
                            case "unsealVent" -> {
                                if (!c.sealed(ventId)) return CommandResult.error("badRequest", "Vent " + ventId + " is not sealed");
                                e.submit(new VentCommands.UnsealVent(vid, ventId));
                                if (!Double.isNaN(c.feederWidthM(ventId)) && c.feederWidthM(ventId) <= 0) {
                                    return CommandResult.ok("Unsealed, but its feeder is frozen: it stays extinct");
                                }
                            }
                            default -> {
                                if (crater) {
                                    return CommandResult.error("unsupported", "Summit vents can be sealed, not removed");
                                }
                                e.submit(new VentCommands.RemoveVent(vid, ventId));
                            }
                        }
                        return CommandResult.ok(null);
                    });
                }
                case "arrestDike", "removeDike", "blockDikes" -> {
                    String vid = Json.str(cmd, "volcanoId");
                    VolcanoSystem v = volcano(s, vid);
                    if (v == null) return done(CommandResult.error("unknownVolcano", "Unknown volcano " + vid));
                    if (v.dikes() == null) {
                        return done(CommandResult.error("unsupported", "Volcano " + vid + " has dikes disabled"));
                    }
                    if (kind.equals("blockDikes")) {
                        if (!cmd.has("blocked") || !cmd.get("blocked").isJsonPrimitive()
                                || !cmd.get("blocked").getAsJsonPrimitive().isBoolean()) {
                            return done(CommandResult.error("badRequest", "blocked (boolean) is required"));
                        }
                        r.submit(new DikeCommands.BlockDikes(vid, cmd.get("blocked").getAsBoolean()));
                        return done(CommandResult.ok(null));
                    }
                    Double raw = Json.dbl(cmd, "dikeId");
                    if (raw == null || raw != Math.rint(raw)) {
                        return done(CommandResult.error("badRequest", "dikeId (integer) is required"));
                    }
                    int dikeId = raw.intValue();
                    return r.onEngineThread(e -> {
                        Dike dike = null;
                        for (Dike d : v.dikes().dikes()) if (d.id() == dikeId) dike = d;
                        if (dike == null || dike.removed()) {
                            return CommandResult.error("unknownDike", "Unknown dike " + dikeId);
                        }
                        if (kind.equals("arrestDike")) {
                            if (!dike.propagating()) {
                                return CommandResult.error("badRequest", "Dike " + dikeId + " is no longer propagating");
                            }
                            e.submit(new DikeCommands.ArrestDike(vid, dikeId));
                        } else {
                            e.submit(new DikeCommands.RemoveDike(vid, dikeId));
                        }
                        return CommandResult.ok(null);
                    });
                }
                case "rain" -> {
                    Double mm = Json.dbl(cmd, "mmPerHour");
                    if (mm == null || mm < 0) return done(CommandResult.error("badRequest", "mmPerHour must be >= 0"));
                    boolean any = false;
                    for (VolcanoSystem v : s.volcanoes()) {
                        if (v.lahars() == null) continue;
                        r.submit(new MassFlowCommands.SetRainfall(v.lahars().id(), mm));
                        any = true;
                    }
                    // Surface water, infiltration and the water table (shared subsurface model).
                    if (FieldSampler.subsurface(s) != null) {
                        r.submit(new Subsurface.SetRainfall(mm));
                        any = true;
                    }
                    if (!any) {
                        return done(CommandResult.error("unsupported",
                                "Neither lahars nor a subsurface model are simulated, so rain has no effect"));
                    }
                    rainMmPerHour = mm;
                    return done(CommandResult.ok(null));
                }
                case "setWind" -> {
                    Double speed = Json.dbl(cmd, "speed");
                    Double bearing = Json.dbl(cmd, "bearingDeg");
                    if (speed == null || bearing == null || speed < 0) {
                        return done(CommandResult.error("badRequest", "speed (>= 0) and bearingDeg are required"));
                    }
                    double dir = Math.toRadians(bearing) - Math.PI / 2; // engine: radians from +X towards +Z
                    for (VolcanoSystem v : s.volcanoes()) {
                        double variability = v.tephra().wind().variability();
                        r.submit(new TephraCommands.SetWind(v.tephra().id(), speed * v.scaling().velocityScale(), dir,
                                variability));
                    }
                    windSpeed = speed;
                    windBearingDeg = normalizeDeg(bearing);
                    return done(CommandResult.ok(null));
                }
                case "addWater" -> {
                    double[] at = xy(cmd);
                    Double vol = Json.dbl(cmd, "volumeM3");
                    if (at == null || vol == null || !(vol > 0)) {
                        return done(CommandResult.error("badRequest", "at and volumeM3 (> 0) are required"));
                    }
                    GridMapping m = map;
                    int cx = m.columnAtX(at[0]);
                    int cz = m.columnAtY(at[1]);
                    if (!m.inside(cx, cz)) return done(CommandResult.error("badRequest", "Point is outside the world"));
                    boolean flows = FieldSampler.subsurface(s) != null;
                    return r.onEngineThread(e -> {
                        s.terrain().world().addWater(cx, cz, vol);
                        return CommandResult.ok(flows ? null
                                : "Water stored; this scenario has no surface-water model, so it does not flow");
                    });
                }
                case "dig" -> {
                    double[] at = xy(cmd);
                    Double radius = Json.dbl(cmd, "radius");
                    Double depth = Json.dbl(cmd, "depth");
                    if (at == null || radius == null || depth == null || !(radius > 0) || !(depth > 0)) {
                        return done(CommandResult.error("badRequest", "at, radius (> 0) and depth (> 0) are required"));
                    }
                    GridMapping m = map;
                    if (!m.inside(m.columnAtX(at[0]), m.columnAtY(at[1]))) {
                        return done(CommandResult.error("badRequest", "Point is outside the world"));
                    }
                    return r.onEngineThread(e -> {
                        int n = dig(s, m, at[0], at[1], radius, depth);
                        return CommandResult.ok(n + " columns excavated");
                    });
                }
                default -> {
                    return done(CommandResult.error("badRequest", "Unknown command kind " + kind));
                }
            }
        } catch (IllegalArgumentException e) {
            return done(CommandResult.error("badRequest", e.getMessage()));
        }
    }

    /** Lowers the ground of every column within {@code radius} m by {@code depth} m (whole blocks). */
    static int dig(Scenario s, GridMapping m, double x, double y, double radius, double depth) {
        TerrainModel terrain = s.terrain();
        int blocks = Math.max(1, (int) Math.ceil(depth / m.cell));
        int r = (int) Math.ceil(radius / m.cell);
        int cx0 = m.columnAtX(x);
        int cz0 = m.columnAtY(y);
        int n = 0;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                int cx = cx0 + dx;
                int cz = cz0 + dz;
                if (!m.inside(cx, cz)) continue;
                if (Math.hypot(m.x(cx) - x, m.y(cz) - y) > radius) continue;
                TerrainColumn col = terrain.column(cx, cz);
                if (col == null) continue;
                terrain.setGround(cx, cz, col.groundY() - blocks, col.surface());
                n++;
            }
        }
        return n;
    }

    private static double[] xy(JsonObject cmd) {
        if (!cmd.has("at") || !cmd.get("at").isJsonArray()) return null;
        JsonArray a = cmd.getAsJsonArray("at");
        if (a.size() < 2) return null;
        return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble()};
    }

    private static VolcanoSystem volcano(Scenario s, String id) {
        if (id == null) return null;
        for (VolcanoSystem v : s.volcanoes()) if (v.volcanoId().equals(id)) return v;
        return null;
    }

    private static CompletableFuture<CommandResult> done(CommandResult r) {
        return CompletableFuture.completedFuture(r);
    }

    private static double normalizeDeg(double deg) {
        double d = deg % 360;
        return d < 0 ? d + 360 : d;
    }

    /** Chamber configuration of the first volcano (defaults for the injection dialog). */
    MagmaChamberConfig firstChamberConfig() {
        List<VolcanoSystem> vs = live.volcanoes();
        return vs.isEmpty() ? null : vs.get(0).chamber().config();
    }

    /** Changes the files of a world between saving and reopening it. */
    interface DefinitionEdit {
        void apply() throws Exception;

        void revert() throws Exception;
    }

    /**
     * Saves this world session, applies {@code edit} to its definition files and reopens it with
     * {@code policy}, keeping the transport mode and the volcano-time estimate. If the reopen fails
     * the edit is reverted and the saved world reopened as it was. Clients must be re-attached by
     * the caller.
     */
    synchronized void reopenWorld(World.ChangePolicy policy, DefinitionEdit edit) throws Exception {
        Source src = source;
        if (src.kind() != Kind.WORLD) throw new UnsupportedOperationException("Only worlds saved to disk can be changed");
        if (replay) throw new IllegalStateException("Leave replay mode before changing settings");
        EngineRunner.Mode mode = runner.mode();
        double speed = runner.speed();
        runner.pause();
        save(null, null);
        Map<String, Double> phys = new HashMap<>(physicalTime);
        Scenario scenario;
        edit.apply();
        try {
            scenario = WorldScenarios.open(src.worldDir(), policy);
        } catch (Exception e) {
            edit.revert();
            transport(mode.name(), speed);
            throw new IllegalArgumentException("The world could not be reopened with these settings: " + e.getMessage(), e);
        }
        replace(name, new Source(Kind.WORLD, null, scenario.seed(), scenario.engine().baseStepMicros(), src.worldDir()),
                scenario);
        physicalTime.putAll(phys);
        transport(mode.name(), speed);
    }

    // ── Saves (§3.4) ──

    /** Saves the session; returns a human-readable location. */
    String save(Path worldsDir, String saveName) throws Exception {
        if (replay) throw new IllegalStateException("Leave replay mode before saving");
        Source src = source;
        EngineRunner r = runner;
        Scenario s = live;
        if (src.kind() == Kind.WORLD) {
            r.onEngineThread(e -> {
                drainFramesNow(r, s);
                s.saveWorld();
                return null;
            }).get(60, TimeUnit.SECONDS);
            return src.worldDir().toString();
        }
        if (saveName == null || !saveName.matches("[A-Za-z0-9_.-]+") || saveName.startsWith(".")) {
            throw new IllegalArgumentException("Save names may only contain letters, digits, '.', '_' and '-'");
        }
        Path dir = worldsDir.resolve(saveName);
        Files.createDirectories(dir);
        r.onEngineThread(e -> {
            drainFramesNow(r, s);
            s.save(new DirectorySaveStore(dir.resolve("state")));
            return null;
        }).get(60, TimeUnit.SECONDS);
        JsonObject meta = new JsonObject();
        meta.addProperty("preset", src.preset());
        meta.addProperty("seed", src.seed());
        meta.addProperty("baseStepMicros", src.baseStepMicros());
        meta.addProperty("name", name);
        Files.writeString(dir.resolve(SESSION_FILE), Json.GSON.toJson(meta), StandardCharsets.UTF_8);
        return dir.toString();
    }

    // ── Replay (§7) ──

    /**
     * Records a keyframe when due. Preset sessions keep them in memory; world sessions write them to
     * {@code <world>/replay/k-<micros>} so a world's history survives server restarts.
     */
    void maybeKeyframe() {
        if (replay) return;
        double t = live.engine().time();
        if (t - lastKeyframeTime < KEYFRAME_SECONDS) return;
        lastKeyframeTime = t;
        EngineRunner r = runner;
        Scenario s = live;
        Path worldDir = source.kind() == Kind.WORLD ? source.worldDir() : null;
        r.onEngineThread(e -> {
            drainFramesNow(r, s);
            SaveStore store = worldDir == null ? new InMemorySaveStore()
                    : new DirectorySaveStore(worldDir.resolve(REPLAY_DIR).resolve(keyframeName(e.timeMicros())));
            s.save(store);
            return new Keyframe(e.time(), store);
        }).thenAccept(k -> {
            synchronized (keyframes) {
                keyframes.add(k);
                if (keyframes.size() > MAX_KEYFRAMES) {
                    // Thin out the older half so the whole run stays reachable.
                    for (int i = 1; i < keyframes.size() / 2; i++) discard(keyframes.remove(i));
                }
            }
        });
    }

    static String keyframeName(long timeMicros) {
        return String.format(java.util.Locale.ROOT, "k-%016d", timeMicros);
    }

    /** Deletes a world keyframe's directory (no-op for in-memory ones). */
    private static void discard(Keyframe k) {
        if (k.store() instanceof DirectorySaveStore d) deleteTree(d.root());
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    LOG.log(Level.FINE, "could not delete " + p, e);
                }
            });
        } catch (IOException e) {
            LOG.log(Level.WARNING, "could not delete keyframe " + dir, e);
        }
    }

    /**
     * Picks up a world's persisted keyframes. Keyframes newer than the resumed state belong to a run
     * that was never saved and are deleted, so the timeline never shows a future that did not happen.
     */
    private void loadWorldKeyframes(Path worldDir, double now) {
        Path root = worldDir.resolve(REPLAY_DIR);
        if (!Files.isDirectory(root)) return;
        List<Path> dirs = new ArrayList<>();
        try (var list = Files.list(root)) {
            list.filter(p -> p.getFileName().toString().startsWith("k-")).forEach(dirs::add);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "could not list " + root, e);
            return;
        }
        dirs.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (Path d : dirs) {
            long micros;
            try {
                micros = Long.parseLong(d.getFileName().toString().substring(2));
            } catch (NumberFormatException e) {
                continue;
            }
            double t = micros / 1e6;
            if (t > now + 1e-6) {
                deleteTree(d);
                continue;
            }
            keyframes.add(new Keyframe(t, new DirectorySaveStore(d)));
            lastKeyframeTime = Math.max(lastKeyframeTime, t);
        }
    }

    void enterReplay() {
        if (replay) return;
        EngineRunner r = runner;
        modeBeforeReplay = r.mode();
        speedBeforeReplay = r.speed();
        r.pause();
        replayScenario = null;
        replay = true;
    }

    /** Jumps to the latest keyframe at or before {@code time}; returns the time landed on. */
    double seek(double time) {
        if (!replay) throw new IllegalStateException("Enter replay mode before seeking");
        Keyframe best = null;
        synchronized (keyframes) {
            for (Keyframe k : keyframes) {
                if (k.time() <= time + 1e-9) best = k;
            }
            if (best == null && !keyframes.isEmpty()) best = keyframes.get(0);
        }
        if (best == null) throw new UnsupportedOperationException("No keyframes recorded yet");
        Scenario restored;
        if (source.kind() == Kind.WORLD) {
            // A detached world on the keyframe's state; its history goes to memory so viewing a
            // keyframe never touches the world's own history logs.
            WorldDirectory dir = new WorldDirectory(source.worldDir());
            WorldDefinition definition = dir.readWorld();
            World world = World.reopen(definition, dir.readVolcanoes(), best.store(), new InMemorySaveStore(),
                    World.ChangePolicy.ACCEPT);
            restored = Scenario.fromWorld(definition.name(), world, WorldScenarios.terrain(definition, source.worldDir()),
                    true);
        } else {
            Preset preset = Presets.get(source.preset());
            restored = preset.build(source.seed(), preset.terrain(source.seed()), Scenario.Options.DEFAULT
                    .withBaseStepMicros(source.baseStepMicros()).withRestore(best.store()));
        }
        replayScenario = restored;
        replayTime = restored.engine().time();
        forceAllTiles();
        return replayTime;
    }

    /** Leaves replay; returns the live time. */
    double exitReplay() {
        if (!replay) return live.engine().time();
        replayScenario = null;
        replay = false;
        EngineRunner r = runner;
        switch (modeBeforeReplay) {
            case REALTIME -> r.realtime(speedBeforeReplay);
            case UNBOUNDED -> r.unbounded();
            case PAUSED -> r.pause();
        }
        forceAllTiles();
        return live.engine().time();
    }

    // ── Shutdown ──

    private void stopRunner() {
        EngineRunner r = runner;
        if (r != null) {
            try {
                r.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Thread d = drainThread;
        if (d != null) d.interrupt();
    }

    @Override
    public void close() {
        stopRunner();
        viewExec.shutdownNow();
    }

    /** Directory of a world session ({@code null} for in-memory preset sessions). */
    Path worldDir() {
        Source src = source;
        return src.kind() == Kind.WORLD ? src.worldDir() : null;
    }

    JsonObject info() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        if (source.preset() != null) o.addProperty("preset", source.preset());
        Path dir = worldDir();
        if (dir != null) o.addProperty("world", dir.getFileName().toString());
        o.add("time", Json.num(live.engine().time()));
        EngineRunner r = runner;
        o.addProperty("mode", replay ? "PAUSED" : r.mode().name());
        o.add("speed", Json.num(r.speed()));
        o.add("rate", Json.num(replay ? 0 : rate));
        o.addProperty("replay", replay);
        JsonArray volcanoes = new JsonArray();
        for (VolcanoSummary v : summary) {
            JsonObject j = new JsonObject();
            j.addProperty("id", v.id());
            j.addProperty("alert", v.alert());
            j.addProperty("erupting", v.erupting());
            JsonObject tc = new JsonObject();
            tc.add("dormant", Json.num(v.dormantCompression()));
            tc.add("eruptive", Json.num(v.eruptiveCompression()));
            tc.add("current", Json.num(v.currentCompression()));
            j.add("timeCompression", tc);
            volcanoes.add(j);
        }
        o.add("volcanoes", volcanoes);
        return o;
    }
}
