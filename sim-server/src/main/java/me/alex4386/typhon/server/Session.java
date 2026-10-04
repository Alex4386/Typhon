package me.alex4386.typhon.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
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
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.dike.DikeCommands;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.magma.MagmaCommands;
import me.alex4386.typhon.engine.massflow.MassFlowCommands;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.save.DirectorySaveStore;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.sim.EngineRunner;
import me.alex4386.typhon.engine.tephra.TephraCommands;
import me.alex4386.typhon.engine.tephra.WindField;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.VentSite;
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
    static final String SESSION_FILE = "session.json";

    enum Kind { PRESET, WORLD }

    /** How to rebuild the scenario (for replay keyframes and saves). */
    record Source(Kind kind, String preset, long seed, long baseStepMicros, Path worldDir) {}

    record Keyframe(double time, InMemorySaveStore store) {}

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
    private volatile int[] tileOrder;
    private final Map<Field, Long> lastRefreshNanos = new EnumMap<>(Field.class);
    private final Map<Field, Double> lastRefreshSim = new EnumMap<>(Field.class);
    private final Set<Field> forceRefresh = new HashSet<>();

    private volatile EventTranslator translator;
    private final Deque<JsonObject> eventLog = new ArrayDeque<>();
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
        int tile = size % 32 == 0 ? 32 : 16;
        long base = tiles == null ? 0 : tiles.maxVersion();
        this.map = new GridMapping(cell, tile, grid.minX(), grid.minZ(), grid.maxX(), grid.maxZ());
        this.tiles = new TileStore(map, base);
        this.tileOrder = order(map, scenario);
        lastRefreshNanos.clear();
        lastRefreshSim.clear();
        forceRefresh.clear();

        this.translator = new EventTranslator(map, scenario.volcanoes(), v -> activeVents.getOrDefault(v, List.of()));
        synchronized (eventLog) {
            eventLog.clear();
        }
        activeVents.clear();
        synchronized (units) {
            while (!units.isEmpty()) units.remove(units.size() - 1);
            unitsKnown = 0;
        }
        keyframes.clear();
        lastKeyframeTime = Double.NEGATIVE_INFINITY;

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
        return o;
    }

    void updateRate() {
        long now = System.nanoTime();
        double t = live.engine().time();
        double wall = (now - rateWallNanos) / 1e9;
        if (wall < 0.5) return;
        double measured = Math.max(0, t - rateSimTime) / wall;
        rate = rate == 0 ? measured : 0.5 * rate + 0.5 * measured;
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
        o.add("volcanoes", res.volcanoes());
        return o;
    }

    JsonArray allUnits() {
        synchronized (units) {
            return units.deepCopy();
        }
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
        for (EngineEvent e : raw) {
            JsonObject j = t.translate(e);
            if (j == null) continue;
            out.add(j);
        }
        if (out.isEmpty() && newDropped == 0) return null;
        synchronized (eventLog) {
            for (int i = 0; i < out.size(); i++) eventLog.addLast(out.get(i).getAsJsonObject());
            while (eventLog.size() > EVENT_LOG_LIMIT) eventLog.removeFirst();
        }
        JsonObject msg = Json.obj("events");
        msg.add("events", out);
        msg.addProperty("dropped", newDropped);
        return msg;
    }

    /** Backlog for (re)attaching clients: recent non-seismic events plus the latest ~600 seismic ones. */
    JsonObject backlog(double until) {
        List<JsonObject> seismic = new ArrayList<>();
        List<JsonObject> other = new ArrayList<>();
        synchronized (eventLog) {
            for (JsonObject e : eventLog) {
                if (e.get("time").getAsDouble() > until) continue;
                String kind = e.get("kind").getAsString();
                if (kind.equals("seismic") || kind.equals("bombLaunched")) seismic.add(e);
                else other.add(e);
            }
        }
        List<JsonObject> all = new ArrayList<>(other.subList(Math.max(0, other.size() - 1500), other.size()));
        all.addAll(seismic.subList(Math.max(0, seismic.size() - 600), seismic.size()));
        all.sort(Comparator.comparingDouble(e -> e.get("time").getAsDouble()));
        JsonArray arr = new JsonArray();
        all.forEach(arr::add);
        JsonObject msg = Json.obj("events");
        msg.add("events", arr);
        msg.addProperty("dropped", 0);
        return msg;
    }

    JsonObject worldInfo() throws Exception {
        GridMapping m = map;
        String n = name;
        return call(s -> Probe.worldInfo(s, m, n)).get(10, TimeUnit.SECONDS);
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
                boolean intervalDue = last == null || (now - last) / 1e9 >= f.refreshSeconds;
                boolean changed = lastSim == null || lastSim != simTime;
                if (force || (intervalDue && changed)) due.add(f);
            }
            forced = !forceRefresh.isEmpty();
        }
        if (due.isEmpty()) return 0;
        GridMapping m = map;
        TileStore store = tiles;
        record Sampled(Map<Field, float[][]> values, double time) {}
        Sampled sampled = call(s -> new Sampled(FieldSampler.sample(s, m, due), s.engine().time()))
                .get(10, TimeUnit.SECONDS);
        int changed = 0;
        for (Map.Entry<Field, float[][]> e : sampled.values().entrySet()) {
            boolean force;
            synchronized (forceRefresh) {
                force = forceRefresh.remove(e.getKey());
            }
            changed += store.update(e.getKey(), e.getValue(), sampled.time(), force);
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
                            Double vol = Json.dbl(cmd, "volumeM3");
                            if (vol == null || !(vol > 0)) return done(CommandResult.error("badRequest", "volumeM3 must be > 0"));
                            MagmaChamberConfig c = v.chamber().config();
                            r.submit(new MagmaCommands.InjectRecharge(vid, vol, c.rechargeTemperatureC(),
                                    c.rechargeSilicaWt(), c.rechargeWaterWt()));
                        }
                    }
                    return done(CommandResult.ok(null));
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
                    if (!any) return done(CommandResult.error("unsupported", "No volcano simulates lahars, so rain has no effect"));
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
                    return r.onEngineThread(e -> {
                        s.terrain().world().addWater(cx, cz, vol);
                        return CommandResult.ok("Water stored; it flows and infiltrates once the surface-water model (M4) is in the engine");
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

    /** Records a keyframe when due (preset sessions; worlds save into their own directory instead). */
    void maybeKeyframe() {
        if (replay || source.kind() != Kind.PRESET) return;
        double t = live.engine().time();
        if (t - lastKeyframeTime < KEYFRAME_SECONDS) return;
        lastKeyframeTime = t;
        EngineRunner r = runner;
        Scenario s = live;
        r.onEngineThread(e -> {
            InMemorySaveStore store = new InMemorySaveStore();
            drainFramesNow(r, s);
            s.save(store);
            return new Keyframe(e.time(), store);
        }).thenAccept(k -> {
            synchronized (keyframes) {
                keyframes.add(k);
                if (keyframes.size() > MAX_KEYFRAMES) {
                    // Thin out the older half so the whole run stays reachable.
                    for (int i = 1; i < keyframes.size() / 2; i++) keyframes.remove(i);
                }
            }
        });
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
        if (source.kind() != Kind.PRESET) {
            throw new UnsupportedOperationException("Replay is only recorded for preset sessions so far");
        }
        Keyframe best = null;
        synchronized (keyframes) {
            for (Keyframe k : keyframes) {
                if (k.time() <= time + 1e-9) best = k;
            }
            if (best == null && !keyframes.isEmpty()) best = keyframes.get(0);
        }
        if (best == null) throw new UnsupportedOperationException("No keyframes recorded yet");
        Preset preset = Presets.get(source.preset());
        Scenario restored = preset.build(source.seed(), preset.terrain(source.seed()), Scenario.Options.DEFAULT
                .withBaseStepMicros(source.baseStepMicros()).withRestore(best.store()));
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

    JsonObject info() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        if (source.preset() != null) o.addProperty("preset", source.preset());
        o.add("time", Json.num(live.engine().time()));
        return o;
    }
}
