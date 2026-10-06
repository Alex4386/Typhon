package me.alex4386.typhon.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.staticfiles.Location;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import me.alex4386.typhon.engine.worlds.World;
import me.alex4386.typhon.server.protocol.Field;
import me.alex4386.typhon.simulator.scenario.Preset;
import me.alex4386.typhon.simulator.scenario.Presets;
import me.alex4386.typhon.simulator.scenario.Scenario;

/**
 * WebSocket server speaking protocol v1 (docs/protocol.md) at {@code /ws}, subprotocol
 * {@code typhon.v1}. Hosts any number of {@link Session}s; a pump thread streams clocks, state,
 * events and tiles to the clients attached to each session.
 */
public final class SimServer implements AutoCloseable {
    public static final int PROTOCOL_VERSION = 1;
    public static final String SUBPROTOCOL = "typhon.v1";
    public static final String SERVER_NAME = "typhon-sim-server/1.0";
    private static final Logger LOG = Logger.getLogger(SimServer.class.getName());
    private static final long PUMP_MILLIS = 50;
    private static final long STATE_MILLIS = 250;
    private static final long REPLAY_INFO_MILLIS = 2000;
    /** Events are batched: every message re-renders the client's event-driven views. */
    private static final long EVENTS_MILLIS = 250;
    /** Entity registry deltas (§4.8). */
    private static final long ENTITIES_MILLIS = 1000;

    /** Server settings. */
    public record Config(String host, int port, Path worldsDir, Path uiDir, String defaultPreset, long defaultSeed,
            long baseStepMicros, double initialSpeed, int maxSessions) {
        public Config(String host, int port, Path worldsDir, Path uiDir, String defaultPreset, long defaultSeed,
                long baseStepMicros, double initialSpeed) {
            this(host, port, worldsDir, uiDir, defaultPreset, defaultSeed, baseStepMicros, initialSpeed, DEFAULT_MAX_SESSIONS);
        }
    }

    /** Sessions one server keeps loaded at once (each holds a full engine in memory). */
    public static final int DEFAULT_MAX_SESSIONS = 8;
    /** How often every client gets the session list (status, time, mode of every loaded world). */
    private static final long SESSIONS_MILLIS = 2000;
    private long lastSessionsNanos;

    private final Config config;
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Map<String, ClientConnection> clients = new ConcurrentHashMap<>();
    private final Map<String, ExecutorService> clientQueues = new ConcurrentHashMap<>();
    private final AtomicInteger sessionSeq = new AtomicInteger();
    private final ScheduledExecutorService pump = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "typhon-pump");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, long[]> pumpTimes = new ConcurrentHashMap<>();
    /** World sessions are saved this often (seconds; system property {@code typhon.autosaveSeconds}, 0 = off). */
    static final long AUTOSAVE_SECONDS = Long.getLong("typhon.autosaveSeconds", 300);
    private final ScheduledExecutorService autosave = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "typhon-autosave");
        t.setDaemon(true);
        return t;
    });
    private Javalin app;

    public SimServer(Config config) {
        this.config = config;
    }

    // ── Sessions ──

    /** Creates an in-memory session from a preset (and registers it). Nothing is written to disk. */
    public synchronized Session createPreset(String preset, long seed) {
        checkCapacity();
        String id = "s" + sessionSeq.incrementAndGet();
        Session s = Session.preset(id, preset, seed, config.baseStepMicros(), config.initialSpeed());
        sessions.put(id, s);
        LOG.info("Session " + id + ": preset " + preset + " (seed " + seed + ")");
        sessionsChanged();
        return s;
    }

    /**
     * Creates a session from a world directory (and registers it). A world that is already loaded is
     * not opened twice (two engines would write the same {@code state/}): its session is returned.
     */
    public synchronized Session createWorld(Path dir) {
        return createWorld(dir, null, null);
    }

    /** As {@link #createWorld(Path)}, first overriding the world's time compression (a hot change). */
    public synchronized Session createWorld(Path dir, Double dormantCompression, Double eruptiveCompression) {
        Session open = sessionForWorld(dir);
        if (open != null) {
            if (dormantCompression != null || eruptiveCompression != null) {
                throw new IllegalStateException("World " + dir.getFileName() + " is already running as session " + open.id
                        + "; close it before changing its time compression");
            }
            return open;
        }
        checkCapacity();
        WorldFiles.setCompression(dir, dormantCompression, eruptiveCompression);
        String id = "s" + sessionSeq.incrementAndGet();
        Session s = Session.world(id, dir, World.ChangePolicy.REJECT, config.initialSpeed());
        sessions.put(id, s);
        LOG.info("Session " + id + ": world " + dir);
        sessionsChanged();
        return s;
    }

    /**
     * Writes a new world directory {@code <worlds-dir>/<name>} from a preset (with an optional
     * time-compression override) and runs it. Unlike {@link #createPreset} the result is a world on
     * disk: it is saved, can be closed and reopened, and shows up in the catalog.
     */
    public synchronized Session createPresetWorld(String presetName, long seed, String name, Double dormantCompression,
            Double eruptiveCompression) {
        checkCapacity();
        Preset preset = Presets.get(presetName);
        if (name != null && !WorldFiles.validName(name)) {
            throw new IllegalArgumentException("World names may use letters, digits, '.', '_' and '-' (max 64)");
        }
        String dirName = name != null ? name : WorldFiles.uniqueName(config.worldsDir(), preset.name());
        Path dir = config.worldsDir().resolve(dirName);
        if (Files.exists(dir)) throw new IllegalArgumentException("A world named '" + dirName + "' already exists");
        try {
            Files.createDirectories(config.worldsDir());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        WorldFiles.writePresetWorld(preset, seed, dir, config.baseStepMicros() / 1000.0, dormantCompression,
                eruptiveCompression);
        return createWorld(dir);
    }

    /** Saves (worlds) and unloads a session; attached clients are detached. */
    public void closeSession(String id, boolean save) {
        Session s;
        synchronized (this) {
            s = sessions.remove(id);
        }
        if (s == null) throw new IllegalArgumentException("No session " + id);
        for (ClientConnection c : clientsOf(s)) {
            c.session = null;
            c.subscribe(Set.of(), null);
            JsonObject d = Json.obj("detached");
            d.addProperty("sessionId", id);
            d.addProperty("reason", "closed");
            c.send(d);
        }
        if (save && s.worldDir() != null && !s.replay()) {
            try {
                s.save(config.worldsDir(), null);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Saving session " + id + " on close failed", e);
            }
        }
        s.close();
        pumpTimes.remove(id);
        LOG.info("Session " + id + " closed");
        sessionsChanged();
    }

    /** Deletes a world directory under the worlds dir (it must not be loaded). */
    public synchronized void deleteWorld(String name) throws java.io.IOException {
        if (!WorldFiles.validName(name)) throw new IllegalArgumentException("Bad world name");
        Path dir = config.worldsDir().resolve(name).normalize();
        if (!dir.startsWith(config.worldsDir().normalize()) || !Files.isRegularFile(dir.resolve("world.yaml"))) {
            throw new IllegalArgumentException("No world '" + name + "' under " + config.worldsDir());
        }
        if (sessionForWorld(dir) != null) throw new IllegalStateException("Close the world's session before deleting it");
        WorldFiles.delete(dir);
        LOG.info("World " + dir + " deleted");
        sessionsChanged();
    }

    private Session sessionForWorld(Path dir) {
        Path want = dir.toAbsolutePath().normalize();
        for (Session s : sessions.values()) {
            Path d = s.worldDir();
            if (d != null && d.toAbsolutePath().normalize().equals(want)) return s;
        }
        return null;
    }

    private void checkCapacity() {
        if (sessions.size() >= config.maxSessions()) {
            throw new IllegalStateException("This server keeps at most " + config.maxSessions()
                    + " worlds loaded; close one first");
        }
    }

    /** Tells every client about a changed session list (next pump). */
    private void sessionsChanged() {
        lastSessionsNanos = 0;
    }

    synchronized List<Session> sessions() {
        return new ArrayList<>(sessions.values());
    }

    synchronized Session session(String id) {
        return sessions.get(id);
    }

    // ── Lifecycle ──

    public SimServer start() {
        app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.startupWatcherEnabled = false;
            if (config.uiDir() != null) cfg.staticFiles.add(config.uiDir().toAbsolutePath().toString(), Location.EXTERNAL);
            cfg.jetty.modifyWebSocketServletFactory(f -> {
                f.setMaxTextMessageSize(1 << 20);
                f.setMaxBinaryMessageSize(1 << 20);
                f.setIdleTimeout(Duration.ofMinutes(30));
            });
        });
        app.wsBeforeUpgrade("/ws", ctx -> {
            String requested = ctx.header("Sec-WebSocket-Protocol");
            boolean ok = false;
            if (requested != null) {
                for (String p : requested.split(",")) if (p.trim().equals(SUBPROTOCOL)) ok = true;
            }
            if (!ok) throw new BadRequestResponse("WebSocket subprotocol '" + SUBPROTOCOL + "' required");
        });
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                ctx.enableAutomaticPings(15, TimeUnit.SECONDS);
                ClientConnection c = new ClientConnection(ctx.sessionId(), ClientConnection.websocket(ctx));
                clients.put(c.id, c);
                clientQueues.put(c.id, Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "typhon-client-" + c.id.substring(0, Math.min(8, c.id.length())));
                    t.setDaemon(true);
                    return t;
                }));
            });
            ws.onMessage(ctx -> {
                ClientConnection c = clients.get(ctx.sessionId());
                ExecutorService q = clientQueues.get(ctx.sessionId());
                if (c == null || q == null) return;
                String text = ctx.message();
                q.execute(() -> handle(c, text));
            });
            ws.onBinaryMessage(ctx -> {
                ClientConnection c = clients.get(ctx.sessionId());
                if (c != null) c.send(Json.error("protocol", "Clients send JSON text frames only", null));
            });
            ws.onClose(ctx -> disconnect(ctx.sessionId()));
            ws.onError(ctx -> disconnect(ctx.sessionId()));
        });
        app.get("/health", ctx -> ctx.result("ok"));
        app.start(config.host(), config.port());
        pump.scheduleWithFixedDelay(this::pumpAll, PUMP_MILLIS, PUMP_MILLIS, TimeUnit.MILLISECONDS);
        if (AUTOSAVE_SECONDS > 0) {
            autosave.scheduleWithFixedDelay(this::saveWorlds, AUTOSAVE_SECONDS, AUTOSAVE_SECONDS, TimeUnit.SECONDS);
        }
        return this;
    }

    /** Port actually bound (useful with port 0 in tests). */
    public int port() {
        return app.port();
    }

    private void disconnect(String id) {
        clients.remove(id);
        ExecutorService q = clientQueues.remove(id);
        if (q != null) q.shutdown();
    }

    @Override
    public void close() {
        autosave.shutdownNow();
        pump.shutdownNow();
        if (app != null) app.stop();
        for (ExecutorService q : clientQueues.values()) q.shutdownNow();
        saveWorlds(); // a stopped server keeps its worlds' progress
        for (Session s : sessions()) s.close();
    }

    /** Saves every world session (not in-memory ones, not while replaying); failures are logged. */
    void saveWorlds() {
        for (Session s : sessions()) {
            if (s.worldDir() == null || s.replay()) continue;
            try {
                s.save(config.worldsDir(), null);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Saving session " + s.id + " failed", e);
            }
        }
    }

    // ── Message handling (§3) ──

    /** Handles one client message (on the client's serial queue). Visible for tests. */
    void handle(ClientConnection c, String text) {
        JsonObject msg;
        try {
            JsonElement e = Json.GSON.fromJson(text, JsonElement.class);
            if (e == null || !e.isJsonObject()) throw new JsonParseException("not an object");
            msg = e.getAsJsonObject();
        } catch (RuntimeException e) {
            c.send(Json.error("badRequest", "Malformed JSON message", null));
            return;
        }
        String type = Json.str(msg, "type");
        Long requestId = msg.has("requestId") ? Json.lng(msg, "requestId") : null;
        try {
            if (!c.helloDone && !"hello".equals(type)) {
                c.send(Json.error("protocol", "Send hello first", requestId));
                return;
            }
            switch (type == null ? "" : type) {
                case "hello" -> hello(c, msg);
                case "listSessions" -> c.send(sessionsMessage());
                case "listCatalog" -> c.send(catalogMessage());
                case "createSession" -> createSession(c, msg, requestId);
                case "sessionControl" -> sessionControl(c, msg, requestId);
                case "deleteWorld" -> {
                    deleteWorld(Json.str(msg, "name"));
                    ack(c, requestId, true, "Deleted " + Json.str(msg, "name"));
                    c.send(catalogMessage());
                }
                case "attach" -> {
                    Session s = session(Json.str(msg, "sessionId"));
                    if (s == null) c.send(Json.error("noSession", "No session " + Json.str(msg, "sessionId"), null));
                    else attach(c, s);
                }
                case "subscribe" -> subscribe(c, msg);
                case "flow" -> {
                    Long n = Json.lng(msg, "tilesProcessed");
                    if (n != null) c.acknowledge(n);
                    Session s = c.session;
                    if (s != null) {
                        c.pumpTiles(s.tiles(), s.tileOrder());
                        c.pumpLod(s.lod());
                    }
                }
                case "transport" -> {
                    // Optional sessionId: control a loaded world the client is not watching.
                    String target = Json.str(msg, "sessionId");
                    Session other = target == null ? null : session(target);
                    if (target != null && other == null) {
                        c.send(Json.error("noSession", "No session " + target, requestId));
                    } else if (other != null) {
                        String err = other.transport(String.valueOf(Json.str(msg, "mode")), Json.dbl(msg, "speed"));
                        afterTransport(c, other, err, requestId);
                        sessionsChanged();
                    } else {
                        withSession(c, requestId, s -> {
                            String err = s.transport(String.valueOf(Json.str(msg, "mode")), Json.dbl(msg, "speed"));
                            afterTransport(c, s, err, requestId);
                            sessionsChanged();
                        });
                    }
                }
                case "step" -> withSession(c, requestId, s -> afterTransport(c, s,
                        s.step(Json.lng(msg, "steps"), Json.dbl(msg, "seconds")), requestId));
                case "pauseAt" -> withSession(c, requestId, s -> afterTransport(c, s, s.pauseAt(Json.dbl(msg, "time")),
                        requestId));
                case "command" -> command(c, msg, requestId);
                case "section" -> section(c, msg, requestId);
                case "save" -> withSession(c, requestId, s -> {
                    String where = s.save(config.worldsDir(), Json.str(msg, "name"));
                    ack(c, requestId, true, "Saved to " + where);
                });
                case "load" -> load(c, msg, requestId);
                case "getSchema" -> withSession(c, requestId, s -> c.send(schemaOrError(s)));
                case "inspect" -> withSession(c, requestId, s -> {
                    Double x = Json.dbl(msg, "x");
                    Double y = Json.dbl(msg, "y");
                    if (x == null || y == null) {
                        c.send(Json.error("badRequest", "inspect needs x and y (metres)", requestId));
                        return;
                    }
                    JsonObject r = s.inspect(x, y);
                    if (requestId != null) r.addProperty("requestId", requestId);
                    c.send(r);
                });
                case "setParams" -> setParams(c, msg, requestId);
                case "replay" -> replay(c, msg, requestId);
                case "seek" -> seek(c, msg, requestId);
                default -> c.send(Json.error("badRequest", "Unknown message type '" + type + "'", requestId));
            }
        } catch (UnsupportedOperationException e) {
            c.send(Json.error("unsupported", e.getMessage(), requestId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            c.send(Json.error("badRequest", e.getMessage(), requestId));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Handling " + type + " failed", e);
            c.send(Json.error("internal", String.valueOf(e.getMessage()), requestId));
        }
    }

    private interface SessionAction {
        void run(Session s) throws Exception;
    }

    private void withSession(ClientConnection c, Long requestId, SessionAction action) throws Exception {
        Session s = c.session;
        if (s == null) {
            c.send(Json.error("noSession", "Attach to a session first", requestId));
            return;
        }
        action.run(s);
    }

    private void hello(ClientConnection c, JsonObject msg) {
        Long protocol = Json.lng(msg, "protocol");
        if (protocol == null || protocol != PROTOCOL_VERSION) {
            c.send(Json.error("protocol", "Server speaks protocol " + PROTOCOL_VERSION, null));
            return;
        }
        c.helloDone = true;
        JsonObject w = Json.obj("welcome");
        w.addProperty("protocol", PROTOCOL_VERSION);
        w.addProperty("server", SERVER_NAME);
        JsonArray fields = new JsonArray();
        for (Field f : Field.values()) if (FieldSampler.AVAILABLE.contains(f)) fields.add(f.id);
        w.add("fields", fields);
        c.send(w);
    }

    private JsonObject sessionsMessage() {
        JsonObject o = Json.obj("sessions");
        JsonArray arr = new JsonArray();
        for (Session s : sessions()) {
            JsonObject info = s.info();
            info.addProperty("clients", clientsOf(s).size());
            arr.add(info);
        }
        o.add("sessions", arr);
        o.add("server", serverInfo());
        return o;
    }

    private JsonObject serverInfo() {
        Runtime rt = Runtime.getRuntime();
        JsonObject server = new JsonObject();
        server.addProperty("maxSessions", config.maxSessions());
        server.addProperty("cpus", rt.availableProcessors());
        server.addProperty("heapUsedMB", (rt.totalMemory() - rt.freeMemory()) >> 20);
        server.addProperty("heapMaxMB", rt.maxMemory() >> 20);
        server.addProperty("worldsDir", config.worldsDir().toString());
        return server;
    }

    /** Presets and the world directories under {@code --worlds-dir} (§3.1 {@code listCatalog}). */
    private JsonObject catalogMessage() {
        JsonObject o = Json.obj("catalog");
        JsonArray presets = new JsonArray();
        for (Preset p : Presets.all()) {
            JsonObject j = new JsonObject();
            j.addProperty("name", p.name());
            j.addProperty("title", p.title());
            j.addProperty("description", p.description());
            j.addProperty("realScale", p.realSetting() != null);
            presets.add(j);
        }
        o.add("presets", presets);
        JsonArray worlds = new JsonArray();
        for (WorldFiles.Listing w : WorldFiles.list(config.worldsDir())) {
            JsonObject j = new JsonObject();
            j.addProperty("name", w.name());
            j.addProperty("title", w.title());
            j.addProperty("volcanoes", w.volcanoes());
            j.addProperty("hasState", w.hasState());
            if (Double.isFinite(w.dormantCompression())) {
                JsonObject tc = new JsonObject();
                tc.add("dormant", Json.num(w.dormantCompression()));
                tc.add("eruptive", Json.num(w.eruptiveCompression()));
                j.add("timeCompression", tc);
            }
            if (w.error() != null) j.addProperty("error", w.error());
            Session open;
            synchronized (this) {
                open = sessionForWorld(w.dir());
            }
            if (open != null) j.addProperty("sessionId", open.id);
            worlds.add(j);
        }
        o.add("worlds", worlds);
        o.add("server", serverInfo());
        return o;
    }

    private void createSession(ClientConnection c, JsonObject msg, Long requestId) {
        String preset = Json.str(msg, "preset");
        String world = Json.str(msg, "world");
        Long seed = Json.lng(msg, "seed");
        String name = Json.str(msg, "name");
        Double dormant = null;
        Double eruptive = null;
        if (msg.has("timeCompression") && msg.get("timeCompression").isJsonObject()) {
            JsonObject tc = msg.getAsJsonObject("timeCompression");
            dormant = Json.dbl(tc, "dormant");
            eruptive = Json.dbl(tc, "eruptive");
        }
        boolean paused = msg.has("paused") && msg.get("paused").getAsBoolean();
        boolean attach = !msg.has("attach") || msg.get("attach").getAsBoolean();
        Session s;
        if (world != null) {
            Path dir = config.worldsDir().resolve(world).normalize();
            if (!dir.startsWith(config.worldsDir().normalize()) || !Files.isDirectory(dir)) {
                c.send(Json.error("badRequest", "No world '" + world + "' under " + config.worldsDir(), requestId));
                return;
            }
            s = createWorld(dir, dormant, eruptive);
        } else {
            String p = preset == null || preset.equals("default") ? config.defaultPreset() : preset;
            boolean inMemory = msg.has("inMemory") && msg.get("inMemory").getAsBoolean();
            if (inMemory && (dormant != null || eruptive != null)) {
                throw new IllegalArgumentException("Time compression options need a world (omit inMemory)");
            }
            s = inMemory ? createPreset(p, seed == null ? config.defaultSeed() : seed)
                    : createPresetWorld(p, seed == null ? config.defaultSeed() : seed, name, dormant, eruptive);
        }
        if (paused) s.transport("PAUSED", null);
        ack(c, requestId, true, "Session " + s.id);
        if (attach) attach(c, s);
        broadcastSessions();
    }

    private void sessionControl(ClientConnection c, JsonObject msg, Long requestId) {
        String id = Json.str(msg, "sessionId");
        String action = Json.str(msg, "action");
        Session s = id == null ? null : session(id);
        if (s == null) {
            c.send(Json.error("noSession", "No session " + id, requestId));
            return;
        }
        String err = switch (action == null ? "" : action) {
            case "pause" -> s.transport("PAUSED", null);
            case "resume" -> s.transport("REALTIME", null);
            case "close" -> {
                closeSession(id, true);
                yield null;
            }
            case "closeWithoutSaving" -> {
                closeSession(id, false);
                yield null;
            }
            default -> "sessionControl.action must be pause, resume, close or closeWithoutSaving";
        };
        if (err != null) {
            c.send(Json.error("badRequest", err, requestId));
            return;
        }
        ack(c, requestId, true, null);
        if (!"close".equals(action) && !"closeWithoutSaving".equals(action)) broadcast(s, s.clock());
        broadcastSessions();
    }

    /** Sends the session list to every connected client now. */
    private void broadcastSessions() {
        JsonObject m = sessionsMessage();
        for (ClientConnection c : clients.values()) if (c.open() && c.helloDone) c.send(m);
        lastSessionsNanos = System.nanoTime();
    }

    /** Sends the attach burst (§2): attached, units, state, events backlog, replayInfo, clock. */
    void attach(ClientConnection c, Session s) {
        try {
            JsonObject attached = Json.obj("attached");
            attached.addProperty("sessionId", s.id);
            attached.add("world", s.worldInfo());
            JsonObject units = Json.obj("units");
            units.add("units", s.allUnits()); // may wait for the engine: before joining the session
            units.addProperty("replace", true);
            // Join and send the backlog between pump cycles: events the pump drained before are in the
            // backlog, later ones arrive live after it — none twice, none lost, none ahead of the backlog.
            synchronized (s.pumpLock) {
                c.session = s;
                c.subscribe(Set.of(), null);
                c.send(attached);
                c.send(units);
                c.send(s.state(new JsonArray()));
                c.send(s.backlog(s.time()));
            }
            c.send(s.entitiesFull());
            c.send(s.replayInfo());
            c.send(s.clock());
            c.send(schemaOrError(s));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Attach burst failed", e);
            c.send(Json.error("internal", "Attach failed: " + e.getMessage(), null));
        }
    }

    private void subscribe(ClientConnection c, JsonObject msg) {
        Set<Field> fields = EnumSet.noneOf(Field.class);
        if (msg.has("fields") && msg.get("fields").isJsonArray()) {
            for (JsonElement e : msg.getAsJsonArray("fields")) {
                Field f = Field.byId(e.getAsInt());
                if (f != null && FieldSampler.AVAILABLE.contains(f)) fields.add(f);
            }
        }
        int[] bounds = null;
        if (msg.has("bounds") && msg.get("bounds").isJsonObject()) {
            JsonObject b = msg.getAsJsonObject("bounds");
            bounds = new int[] {b.get("minTx").getAsInt(), b.get("minTy").getAsInt(), b.get("maxTx").getAsInt(),
                    b.get("maxTy").getAsInt()};
        }
        Set<Integer> levels = new java.util.TreeSet<>();
        if (msg.has("levels") && msg.get("levels").isJsonArray()) {
            for (JsonElement e : msg.getAsJsonArray("levels")) levels.add(e.getAsInt());
        }
        c.subscribe(fields, bounds, levels);
        Session s = c.session;
        if (s != null) {
            try {
                s.refreshTiles(fields, c.subscribedLevels());
            } catch (Exception e) {
                LOG.log(Level.FINE, "refresh on subscribe failed", e);
            }
            c.pumpTiles(s.tiles(), s.tileOrder());
            c.pumpLod(s.lod());
        }
    }

    private void afterTransport(ClientConnection c, Session s, String error, Long requestId) {
        if (error != null) {
            c.send(Json.error(s.replay() ? "unsupported" : "badRequest", error, requestId));
            return;
        }
        // Give the runner a moment to switch, then tell everyone.
        broadcast(s, s.clock());
    }

    private void command(ClientConnection c, JsonObject msg, Long requestId) throws Exception {
        Session s = c.session;
        if (s == null) {
            c.send(Json.error("noSession", "Attach to a session first", requestId));
            return;
        }
        if (!msg.has("command") || !msg.get("command").isJsonObject()) {
            c.send(Json.error("badRequest", "command object required", requestId));
            return;
        }
        Session.CommandResult r = s.command(msg.getAsJsonObject("command")).get(30, TimeUnit.SECONDS);
        if (requestId != null) ack(c, requestId, r.ok(), r.message());
        if (!r.ok()) c.send(Json.error(r.code(), r.message(), requestId));
    }

    private void section(ClientConnection c, JsonObject msg, Long requestId) throws Exception {
        Session s = c.session;
        if (s == null) {
            c.send(Json.error("noSession", "Attach to a session first", requestId));
            return;
        }
        JsonArray poly = msg.has("polyline") && msg.get("polyline").isJsonArray() ? msg.getAsJsonArray("polyline") : null;
        Double zMin = Json.dbl(msg, "zMin");
        Double zMax = Json.dbl(msg, "zMax");
        Long nu = Json.lng(msg, "nu");
        Long nz = Json.lng(msg, "nz");
        if (requestId == null || poly == null || poly.size() < 2 || zMin == null || zMax == null || nu == null
                || nz == null || nu < 8 || nu > 1024 || nz < 8 || nz > 512 || !(zMax > zMin)) {
            c.send(Json.error("badRequest", "section needs requestId, polyline (>= 2 points), zMin < zMax, nu 8-1024, nz 8-512",
                    requestId));
            return;
        }
        double[][] pts = new double[poly.size()][2];
        double length = 0;
        for (int i = 0; i < pts.length; i++) {
            JsonArray p = poly.get(i).getAsJsonArray();
            pts[i][0] = p.get(0).getAsDouble();
            pts[i][1] = p.get(1).getAsDouble();
            if (i > 0) length += Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]);
        }
        if (!(length > 0)) {
            c.send(Json.error("badRequest", "Section polyline has zero length", requestId));
            return;
        }
        String datum = msg.has("datum") && msg.get("datum").isJsonPrimitive() ? msg.get("datum").getAsString() : "absolute";
        if (!datum.equals("absolute") && !datum.equals("surface")) {
            c.send(Json.error("badRequest", "section datum must be \"absolute\" or \"surface\"", requestId));
            return;
        }
        byte[] frame = s.section(new SectionBuilder.Request(requestId, pts, zMin, zMax, nu.intValue(), nz.intValue(),
                datum.equals("surface")));
        c.sendBinary(frame);
    }

    private void load(ClientConnection c, JsonObject msg, Long requestId) throws Exception {
        Session s = c.session;
        if (s == null) {
            c.send(Json.error("noSession", "Attach to a session first", requestId));
            return;
        }
        String name = Json.str(msg, "name");
        if (name == null || name.contains("..") || name.contains("/") || name.contains("\\")) {
            c.send(Json.error("badRequest", "load needs a save name", requestId));
            return;
        }
        Path dir = config.worldsDir().resolve(name);
        Session.Source[] src = new Session.Source[1];
        Scenario scenario = Session.openSaved(dir, src);
        s.replace(name, src[0], scenario);
        if (requestId != null) ack(c, requestId, true, "Loaded " + dir);
        for (ClientConnection other : clientsOf(s)) {
            other.forgetTiles();
            attach(other, s);
        }
    }

    /** The session's parameter schema; a broken definition file yields a non-tunable schema saying why. */
    private JsonObject schemaOrError(Session s) {
        try {
            return Tuning.schema(s);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Schema of session " + s.id + " failed", e);
            JsonObject o = Json.obj("schema");
            o.addProperty("sessionId", s.id);
            o.addProperty("tunable", false);
            o.addProperty("reason", "The world's settings could not be read: " + e.getMessage());
            o.add("params", new JsonArray());
            o.add("commands", new JsonObject());
            o.add("audit", new JsonArray());
            return o;
        }
    }

    /**
     * Section 3.6: changes world/volcano definition values. Hot changes reopen the world keeping all
     * state; {@code restart} changes reset the affected volcanoes. Every attached client is
     * re-attached (fresh tiles and state) and receives the new schema.
     */
    private void setParams(ClientConnection c, JsonObject msg, Long requestId) throws Exception {
        Session s = c.session;
        if (s == null) {
            c.send(Json.error("noSession", "Attach to a session first", requestId));
            return;
        }
        Path dir = s.worldDir();
        if (dir == null) {
            c.send(Json.error("unsupported", "This world runs in memory only; start it from the Worlds page to change"
                    + " its settings", requestId));
            return;
        }
        JsonObject values = msg.has("values") && msg.get("values").isJsonObject() ? msg.getAsJsonObject("values") : null;
        boolean restart = msg.has("restart") && msg.get("restart").getAsBoolean();
        Tuning.Plan plan = Tuning.plan(dir, values, restart, s.time());
        if (plan.isEmpty()) {
            if (requestId != null) ack(c, requestId, true, "No changes");
            c.send(schemaOrError(s));
            return;
        }
        s.reopenWorld(plan.restart ? World.ChangePolicy.RESET_CHANGED : World.ChangePolicy.ACCEPT,
                new Session.DefinitionEdit() {
                    @Override
                    public void apply() throws Exception {
                        plan.write();
                    }

                    @Override
                    public void revert() throws Exception {
                        plan.revert();
                    }
                });
        plan.log();
        if (requestId != null) {
            ack(c, requestId, true, (plan.restart ? "Restarted with " : "Applied ") + plan.audit.size() + " change"
                    + (plan.audit.size() == 1 ? "" : "s")
                    + (plan.warnings.isEmpty() ? "" : ". Warning: " + String.join(" ", plan.warnings)));
        }
        for (ClientConnection other : clientsOf(s)) {
            other.forgetTiles();
            attach(other, s);
        }
        sessionsChanged();
    }

    private void replay(ClientConnection c, JsonObject msg, Long requestId) throws Exception {
        Session s = c.session;
        if (s == null) {
            c.send(Json.error("noSession", "Attach to a session first", requestId));
            return;
        }
        String action = Json.str(msg, "action");
        if ("enter".equals(action)) {
            s.enterReplay();
            broadcast(s, s.clock());
            broadcast(s, s.replayInfo());
        } else if ("exit".equals(action)) {
            double t = s.exitReplay();
            jumped(s, t);
        } else {
            c.send(Json.error("badRequest", "replay.action must be enter or exit", requestId));
        }
    }

    private void seek(ClientConnection c, JsonObject msg, Long requestId) throws Exception {
        Session s = c.session;
        if (s == null) {
            c.send(Json.error("noSession", "Attach to a session first", requestId));
            return;
        }
        if (!s.replay()) {
            c.send(Json.error("badRequest", "Enter replay mode before seeking", requestId));
            return;
        }
        Double time = Json.dbl(msg, "time");
        if (time == null) {
            c.send(Json.error("badRequest", "seek.time is required", requestId));
            return;
        }
        double landed = s.seek(time);
        jumped(s, landed);
    }

    /** After a replay jump: replayReset, state, backlog, clock, and every tile again. */
    private void jumped(Session s, double time) throws Exception {
        JsonObject reset = Json.obj("replayReset");
        reset.add("time", Json.num(time));
        JsonObject state = s.state(new JsonArray());
        s.resetEntities();
        JsonObject entities = s.entitiesFull();
        for (ClientConnection c : clientsOf(s)) {
            c.forgetTiles();
            c.send(reset);
            c.send(state);
            c.send(s.backlog(time));
            c.send(entities);
            c.send(s.clock());
        }
    }

    private void ack(ClientConnection c, Long requestId, boolean ok, String message) {
        if (requestId == null) return;
        JsonObject a = Json.obj("ack");
        a.addProperty("requestId", requestId);
        a.addProperty("ok", ok);
        if (message != null) a.addProperty("message", message);
        c.send(a);
    }

    // ── Pump ──

    private List<ClientConnection> clientsOf(Session s) {
        List<ClientConnection> out = new ArrayList<>();
        for (ClientConnection c : clients.values()) if (c.session == s && c.open()) out.add(c);
        return out;
    }

    private void broadcast(Session s, JsonObject message) {
        for (ClientConnection c : clientsOf(s)) c.send(message);
    }

    private void pumpAll() {
        for (Session s : sessions()) {
            try {
                s.refreshSummary();
                // an attaching client joins between pump cycles, so its backlog and the live events meet exactly
                synchronized (s.pumpLock) {
                    pump(s);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Pump of session " + s.id + " failed", e);
            }
        }
        if ((System.nanoTime() - lastSessionsNanos) / 1_000_000 >= SESSIONS_MILLIS) {
            try {
                broadcastSessions();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Session list broadcast failed", e);
            }
        }
    }

    /** Visible for tests: one pump cycle for one session. */
    void pump(Session s) throws Exception {
        long now = System.nanoTime();
        long[] times = pumpTimes.computeIfAbsent(s.id, k -> new long[] {0, 0, 0, 0});
        List<ClientConnection> watchers = clientsOf(s);
        JsonObject extent = s.checkExpansion();
        if (extent != null) for (ClientConnection c : watchers) c.send(extent);
        s.updateRate();
        s.maybeKeyframe();

        if ((now - times[2]) / 1_000_000 >= EVENTS_MILLIS) {
            times[2] = now;
            JsonObject events = s.pumpEvents();
            if (events != null) for (ClientConnection c : watchers) c.send(events);
        }

        if (watchers.isEmpty()) return;

        if ((now - times[0]) / 1_000_000 >= STATE_MILLIS) {
            times[0] = now;
            JsonArray newUnits = new JsonArray();
            JsonObject state = s.state(newUnits);
            JsonObject clock = s.clock();
            for (ClientConnection c : watchers) {
                if (!newUnits.isEmpty()) {
                    JsonObject u = Json.obj("units");
                    u.add("units", newUnits);
                    u.addProperty("replace", false);
                    c.send(u);
                }
                c.send(clock);
                c.send(state);
            }
        }
        if ((now - times[3]) / 1_000_000 >= ENTITIES_MILLIS) {
            times[3] = now;
            JsonObject delta = s.entitiesDelta();
            if (delta != null) for (ClientConnection c : watchers) c.send(delta);
        }
        if ((now - times[1]) / 1_000_000 >= REPLAY_INFO_MILLIS) {
            times[1] = now;
            JsonObject info = s.replayInfo();
            for (ClientConnection c : watchers) c.send(info);
        }

        Set<Field> wanted = EnumSet.noneOf(Field.class);
        for (ClientConnection c : watchers) wanted.addAll(c.subscribedFields());
        if (!wanted.isEmpty()) {
            Set<Integer> levels = new java.util.TreeSet<>();
            for (ClientConnection c : watchers) levels.addAll(c.subscribedLevels());
            s.refreshTiles(wanted, levels);
            for (ClientConnection c : watchers) {
                c.pumpTiles(s.tiles(), s.tileOrder());
                c.pumpLod(s.lod());
            }
        }
    }
}
