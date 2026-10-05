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

    /** Server settings. */
    public record Config(String host, int port, Path worldsDir, Path uiDir, String defaultPreset, long defaultSeed,
            long baseStepMicros, double initialSpeed) {}

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
    private Javalin app;

    public SimServer(Config config) {
        this.config = config;
    }

    // ── Sessions ──

    /** Creates a session from a preset (and registers it). */
    public synchronized Session createPreset(String preset, long seed) {
        String id = "s" + sessionSeq.incrementAndGet();
        Session s = Session.preset(id, preset, seed, config.baseStepMicros(), config.initialSpeed());
        sessions.put(id, s);
        LOG.info("Session " + id + ": preset " + preset + " (seed " + seed + ")");
        return s;
    }

    /** Creates a session from a world directory (and registers it). */
    public synchronized Session createWorld(Path dir) {
        String id = "s" + sessionSeq.incrementAndGet();
        Session s = Session.world(id, dir, World.ChangePolicy.REJECT, config.initialSpeed());
        sessions.put(id, s);
        LOG.info("Session " + id + ": world " + dir);
        return s;
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
        pump.shutdownNow();
        if (app != null) app.stop();
        for (ExecutorService q : clientQueues.values()) q.shutdownNow();
        for (Session s : sessions()) s.close();
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
                case "createSession" -> createSession(c, msg);
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
                    if (s != null) c.pumpTiles(s.tiles(), s.tileOrder());
                }
                case "transport" -> withSession(c, requestId, s -> {
                    String err = s.transport(String.valueOf(Json.str(msg, "mode")), Json.dbl(msg, "speed"));
                    afterTransport(c, s, err, requestId);
                });
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
        for (Session s : sessions()) arr.add(s.info());
        o.add("sessions", arr);
        return o;
    }

    private void createSession(ClientConnection c, JsonObject msg) {
        String preset = Json.str(msg, "preset");
        String world = Json.str(msg, "world");
        Long seed = Json.lng(msg, "seed");
        Session s;
        if (world != null) {
            Path dir = config.worldsDir().resolve(world).normalize();
            if (!dir.startsWith(config.worldsDir().normalize()) || !Files.isDirectory(dir)) {
                c.send(Json.error("badRequest", "No world '" + world + "' under " + config.worldsDir(), null));
                return;
            }
            s = createWorld(dir);
        } else {
            String name = preset == null || preset.equals("default") ? config.defaultPreset() : preset;
            s = createPreset(name, seed == null ? config.defaultSeed() : seed);
        }
        attach(c, s);
    }

    /** Sends the attach burst (§2): attached, units, state, events backlog, replayInfo, clock. */
    void attach(ClientConnection c, Session s) {
        c.session = s;
        c.subscribe(Set.of(), null);
        try {
            JsonObject attached = Json.obj("attached");
            attached.addProperty("sessionId", s.id);
            attached.add("world", s.worldInfo());
            c.send(attached);
            JsonArray fresh = new JsonArray();
            JsonObject state = s.state(fresh);
            JsonObject units = Json.obj("units");
            units.add("units", s.allUnits());
            units.addProperty("replace", true);
            c.send(units);
            c.send(state);
            c.send(s.backlog(s.time()));
            c.send(s.replayInfo());
            c.send(s.clock());
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
        c.subscribe(fields, bounds);
        Session s = c.session;
        if (s != null) {
            try {
                s.refreshTiles(fields);
            } catch (Exception e) {
                LOG.log(Level.FINE, "refresh on subscribe failed", e);
            }
            c.pumpTiles(s.tiles(), s.tileOrder());
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
        for (ClientConnection c : clientsOf(s)) {
            c.forgetTiles();
            c.send(reset);
            c.send(state);
            c.send(s.backlog(time));
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
                pump(s);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Pump of session " + s.id + " failed", e);
            }
        }
    }

    /** Visible for tests: one pump cycle for one session. */
    void pump(Session s) throws Exception {
        long now = System.nanoTime();
        long[] times = pumpTimes.computeIfAbsent(s.id, k -> new long[] {0, 0, 0});
        List<ClientConnection> watchers = clientsOf(s);
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
        if ((now - times[1]) / 1_000_000 >= REPLAY_INFO_MILLIS) {
            times[1] = now;
            JsonObject info = s.replayInfo();
            for (ClientConnection c : watchers) c.send(info);
        }

        Set<Field> wanted = EnumSet.noneOf(Field.class);
        for (ClientConnection c : watchers) wanted.addAll(c.subscribedFields());
        if (!wanted.isEmpty()) {
            s.refreshTiles(wanted);
            for (ClientConnection c : watchers) c.pumpTiles(s.tiles(), s.tileOrder());
        }
    }
}
