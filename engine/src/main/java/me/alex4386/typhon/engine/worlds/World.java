package me.alex4386.typhon.engine.worlds;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.config.ConfigException;
import me.alex4386.typhon.engine.config.ConfigNode;
import me.alex4386.typhon.engine.config.VolcanoDefinition;
import me.alex4386.typhon.engine.config.WorldDefinition;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.lava.LavaSource;
import me.alex4386.typhon.engine.output.HistoricalEvent;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.save.SaveFormat;
import me.alex4386.typhon.engine.save.SaveStore;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.subsurface.Subsurface;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.terrain.TerrainSnapshot;
import me.alex4386.typhon.engine.world.Edifice;
import me.alex4386.typhon.engine.world.WorldModel;

/**
 * A world with any number of volcanoes, assembled into one engine from a {@link WorldDefinition}
 * and {@link VolcanoDefinition}s.
 *
 * <ul>
 *   <li><b>Shared</b>: the world model / terrain (subsystem {@code terrain}), the subsurface model
 *       ({@code subsurface}: heat, groundwater, surface water — every volcano heats it) and the lava
 *       field ({@code lava}) — lava from neighbouring volcanoes meets in one field.
 *   <li><b>Per volcano</b> (ids namespaced by volcano): chamber, dikes, seismicity, alert, coupler,
 *       tephra, mass flows, geothermal, deformation. Stratigraphic units record the volcano id.
 * </ul>
 *
 * <p>Volcanoes can be added, removed or put to sleep while running ({@link #addVolcano},
 * {@link #removeVolcano}, {@link #setActive}); the engine is rebuilt between steps from an in-memory
 * save, so every other subsystem continues exactly. Such runtime changes are recorded in
 * {@code state/world.json} and leave the definition files untouched.
 *
 * <p>When a saved world is reopened its definitions are compared with the ones it was saved with
 * ({@link ConfigChanges}); hot changes apply, re-init changes follow the {@link ChangePolicy}.
 */
public final class World {
    /** What to do when definitions changed incompatibly with the saved state. */
    public enum ChangePolicy {
        /** Refuse to open (default). */
        REJECT,
        /** Keep all state and apply the new parameters. */
        ACCEPT,
        /** Restart the changed volcanoes from their definitions; keep everything else. */
        RESET_CHANGED
    }

    /** Builds the initial terrain of a fresh world (generator, DEM, a host's live world, ...). */
    @FunctionalInterface
    public interface TerrainProvider {
        TerrainSnapshot initialTerrain(WorldDefinition world, List<VolcanoDefinition> volcanoes);
    }

    static final String WORLD_STATE = "world.json";
    private static final int WORLD_STATE_FORMAT = 1;

    private final WorldDirectory directory;
    private final SaveStore stateStore;
    private final SaveStore historyStore;
    private WorldDefinition definition;
    private final TreeMap<String, VolcanoDefinition> declared = new TreeMap<>();
    private final TreeMap<String, VolcanoDefinition> added = new TreeMap<>();
    private final TreeSet<String> removed = new TreeSet<>();
    private final TreeMap<String, Boolean> activeOverrides = new TreeMap<>();
    private final List<HistoricalEvent> pendingHistory = new ArrayList<>();
    private ConfigChanges changes = ConfigChanges.NONE;

    private Engine engine;
    private TerrainModel terrain;
    private LavaFlow lava;
    private Subsurface subsurface;
    private me.alex4386.typhon.engine.expansion.WorldExpansion expansion;
    /** The host's terrain generator (not persisted; re-attached on every open). */
    private me.alex4386.typhon.engine.terrain.TerrainGenerator generator;
    /** The host's continuous initial surface (not persisted; re-attached on every open and rebuild). */
    private java.util.function.DoubleBinaryOperator relief;
    private final TreeMap<String, VolcanoSystem> systems = new TreeMap<>();
    /** Worker threads for the engine (0 = the default); results are identical for every count. */
    private int threads;

    private World(WorldDirectory directory, SaveStore stateStore, SaveStore historyStore, WorldDefinition definition,
            Collection<VolcanoDefinition> volcanoes) {
        this.directory = directory;
        this.stateStore = Objects.requireNonNull(stateStore);
        this.historyStore = Objects.requireNonNull(historyStore);
        this.definition = Objects.requireNonNull(definition);
        for (VolcanoDefinition v : volcanoes) {
            if (declared.put(v.id(), v) != null) throw new ConfigException("duplicate volcano id '" + v.id() + "'");
        }
    }

    // ── Creating and opening ──

    /** A fresh world kept in memory (tests, previews). Save with {@link #save()} into its in-memory stores. */
    public static World create(WorldDefinition definition, Collection<VolcanoDefinition> volcanoes, TerrainSnapshot terrain) {
        return create(definition, volcanoes, terrain, new InMemorySaveStore(), new InMemorySaveStore());
    }

    /** A fresh world saving into the given stores. */
    public static World create(WorldDefinition definition, Collection<VolcanoDefinition> volcanoes, TerrainSnapshot terrain,
            SaveStore state, SaveStore history) {
        World world = new World(null, state, history, definition, volcanoes);
        world.build(null, Map.of(), Set.of());
        world.engine.submit(terrain);
        return world;
    }

    /**
     * Opens {@code worlds/<name>}: resumes {@code state/} if present (checking definition changes
     * against {@code policy}), otherwise starts fresh on {@code terrain}'s initial terrain.
     */
    public static World open(Path root, TerrainProvider terrain, ChangePolicy policy) {
        return open(root, terrain, policy, 0);
    }

    /** {@link #open(Path, TerrainProvider, ChangePolicy)} with {@code threads} engine workers (0 = default). */
    public static World open(Path root, TerrainProvider terrain, ChangePolicy policy, int threads) {
        WorldDirectory dir = new WorldDirectory(root);
        return open(dir, dir.stateStore(), dir.historyStore(), terrain, policy, threads);
    }

    static World open(WorldDirectory dir, SaveStore state, SaveStore history, TerrainProvider terrain,
            ChangePolicy policy) {
        return open(dir, state, history, terrain, policy, 0);
    }

    static World open(WorldDirectory dir, SaveStore state, SaveStore history, TerrainProvider terrain,
            ChangePolicy policy, int threads) {
        if (threads < 0) throw new IllegalArgumentException("threads must be >= 0");
        World world = new World(dir, state, history, dir.readWorld(), dir.readVolcanoes());
        world.threads = threads;
        if (state.read(SaveFormat.META) == null) {
            world.build(null, Map.of(), Set.of());
            world.engine.submit(terrain.initialTerrain(world.definition, world.volcanoDefinitions()));
            return world;
        }
        world.resume(policy);
        return world;
    }

    /**
     * Opens a saved in-memory world again with (possibly changed) definitions — what {@link #open}
     * does for directories.
     */
    public static World reopen(WorldDefinition definition, Collection<VolcanoDefinition> volcanoes, SaveStore state,
            SaveStore history, ChangePolicy policy) {
        World world = new World(null, state, history, definition, volcanoes);
        world.resume(policy);
        return world;
    }

    private void resume(ChangePolicy policy) {
        JsonObject saved = readWorldState();
        JsonObject savedWorld = null;
        Map<String, JsonObject> savedVolcanoes = new TreeMap<>();
        if (saved != null) {
            JsonObject runtime = saved.getAsJsonObject("runtime");
            for (Map.Entry<String, JsonElement> e : runtime.getAsJsonObject("added").entrySet()) {
                added.put(e.getKey(), VolcanoDefinition.parse(e.getKey(),
                        ConfigNode.root(WORLD_STATE + "#added." + e.getKey(), toJava(e.getValue()))));
            }
            for (JsonElement e : runtime.getAsJsonArray("removed")) removed.add(e.getAsString());
            for (Map.Entry<String, JsonElement> e : runtime.getAsJsonObject("active").entrySet()) {
                activeOverrides.put(e.getKey(), e.getValue().getAsBoolean());
            }
            JsonObject defs = saved.getAsJsonObject("definitions");
            savedWorld = defs.getAsJsonObject("world");
            for (Map.Entry<String, JsonElement> e : defs.getAsJsonObject("volcanoes").entrySet()) {
                savedVolcanoes.put(e.getKey(), e.getValue().getAsJsonObject());
            }
            long seed = savedWorld.get("seed").getAsLong();
            double step = savedWorld.get("baseStepMs").getAsDouble();
            if (seed != definition.seed() || step != definition.baseStepMs()) {
                throw new ConfigException("world seed/baseStepMs changed since the last save (" + seed + "/" + step
                        + " ms -> " + definition.seed() + "/" + definition.baseStepMs() + " ms); the saved state cannot be"
                        + " continued with them. Restore the old values or delete state/ to start over.");
            }
        }
        Map<String, JsonObject> current = new TreeMap<>();
        for (VolcanoDefinition v : volcanoDefinitions()) current.put(v.id(), json(v.toTree()));
        changes = saved == null ? ConfigChanges.NONE
                : ConfigChanges.compare(savedWorld, json(definition.toTree()), savedVolcanoes, current);

        Map<String, Set<String>> reset = Map.of();
        if (changes.requiresReinit()) {
            switch (policy) {
                case REJECT -> throw new ConfigException("definitions changed since the last save in ways that do not fit"
                        + " the saved state:" + changes + "\nAccept them (keep state) or reset the changed volcanoes.");
                case ACCEPT -> { }
                case RESET_CHANGED -> {
                    if (changes.worldRequiresReinit()) {
                        throw new ConfigException("world-level changes cannot be reset per volcano:" + changes
                                + "\nAccept them or delete state/ to start over.");
                    }
                    Map<String, Set<String>> targets = new TreeMap<>();
                    for (String id : changes.reinitVolcanoes()) {
                        Set<String> subsystems = changes.reinitSubsystems(id);
                        targets.put(id, subsystems == null ? ALL : subsystems);
                    }
                    reset = targets;
                }
            }
        }
        Set<String> resync = new TreeSet<>();
        for (ConfigChanges.Change c : changes.all()) {
            if (c.scope().startsWith("volcano:")
                    && (c.path().startsWith("magma.chamber.supply") || c.path().startsWith("magma.chamber.recharge")
                            || c.path().equals("active"))) {
                resync.add(c.scope().substring("volcano:".length()));
            }
        }
        build(stateStore, reset, resync);
    }

    private JsonObject readWorldState() {
        byte[] bytes = stateStore.read(WORLD_STATE);
        if (bytes == null) return null;
        JsonObject o = SaveFormat.parse(bytes);
        int format = o.get("format").getAsInt();
        if (format != WORLD_STATE_FORMAT) throw new ConfigException(WORLD_STATE + ": unsupported format " + format);
        return o;
    }

    // ── Assembly ──

    /** Effective volcano definitions: declared − removed + added, with runtime activity overrides. */
    public List<VolcanoDefinition> volcanoDefinitions() {
        TreeMap<String, VolcanoDefinition> effective = new TreeMap<>(declared);
        for (String id : removed) effective.remove(id);
        effective.putAll(added);
        List<VolcanoDefinition> list = new ArrayList<>();
        for (VolcanoDefinition v : effective.values()) {
            Boolean active = activeOverrides.get(v.id());
            list.add(active == null || active == v.active() ? v : v.withActive(active));
        }
        return list;
    }

    /**
     * Assembles the engine; with {@code restore}, resumes it (volcanoes in {@code resetVolcanoes} start
     * fresh) and re-applies the definition's magma supply (rate and magma) to {@code resyncSupply} (supply is state:
     * a changed definition or activity flag must override the saved value).
     */
    /** Marks a whole-volcano reset in {@link #build}'s reset map. */
    private static final Set<String> ALL = Set.of("*");

    /**
     * @param resets volcanoes to (partly) start fresh: volcano id → subsystem ids whose saved state is
     *     ignored, or {@link #ALL} for every subsystem of the volcano
     */
    private void build(SaveStore restore, Map<String, Set<String>> resets, Set<String> resyncSupply) {
        terrain = new TerrainModel(new WorldModel(definition.spec()));
        List<Edifice> edifices = new ArrayList<>();
        for (VolcanoDefinition v : volcanoDefinitions()) {
            Edifice e = v.edifice();
            if (e != null) edifices.add(e);
        }
        terrain.world().setEdifices(edifices);
        lava = new LavaFlow(terrain, definition.lava());
        // the field is in the world's columns from the start, also before any volcano exists
        lava.setMetersPerBlock(definition.scaling().metersPerBlock());
        subsurface = new Subsurface(terrain.world(), definition.subsurfaceConfig());
        systems.clear();
        Engine.Builder builder = Engine.builder(definition.seed()).baseStepMicros(definition.baseStepMicros())
                .add(terrain).add(subsurface);
        if (threads > 0) builder.threads(threads);
        Set<String> hidden = new HashSet<>();
        for (VolcanoDefinition v : volcanoDefinitions()) {
            VolcanoSystem system = v.assemble(terrain, lava, definition, subsurface);
            systems.put(v.id(), system);
            system.addTo(builder);
            Set<String> reset = resets.get(v.id());
            if (reset == ALL) {
                for (Subsystem s : system.subsystems()) hidden.add(s.id());
            } else if (reset != null) {
                hidden.addAll(reset);
            }
        }
        builder.add(lava);
        expansion = new me.alex4386.typhon.engine.expansion.WorldExpansion(terrain, definition.expansion());
        wireExpansion();
        builder.add(expansion);
        if (restore != null) {
            builder.restore(hidden.isEmpty() ? restore : new HidingSaveStore(restore, hidden)).allowConfigChanges();
        }
        engine = builder.build();
        for (String id : resyncSupply) {
            VolcanoSystem system = systems.get(id);
            if (system != null) system.chamber().resetSupplyFromConfig();
        }
        for (Map.Entry<String, Set<String>> e : resets.entrySet()) {
            if (e.getValue() != ALL) continue; // partial resets leave the volcano's lava alone
            for (LavaSource source : List.copyOf(lava.sources())) {
                if (source.id().startsWith(e.getKey() + "/")) lava.removeSource(source.id());
            }
        }
    }

    /**
     * Registers what drives on-demand growth: lava, mass flows, rising dikes, slope failures, thick tephra
     * and running water; tephra that fell on unsimulated ground is laid down when it materialises.
     */
    private void wireExpansion() {
        expansion.setGenerator(generator);
        if (relief != null) terrain.world().setRelief(relief);
        me.alex4386.typhon.engine.expansion.ExpansionWiring.wire(expansion, lava, subsurface, systems.values());
    }

    /**
     * Attaches the host's terrain generator: the column at any (x, z), as generated for this world's
     * definition. With it the simulated area grows on demand ({@link me.alex4386.typhon.engine.expansion.WorldExpansion});
     * without it, it stays as imported. Call before stepping, after every open.
     */
    /**
     * Attaches the host's continuous description of the initial surface ({@link
     * me.alex4386.typhon.engine.world.WorldModel#setRelief}); kept across rebuilds (adding a volcano).
     */
    public void setRelief(java.util.function.DoubleBinaryOperator relief) {
        this.relief = relief;
        if (terrain != null) terrain.world().setRelief(relief);
    }

    public void setTerrainGenerator(me.alex4386.typhon.engine.terrain.TerrainGenerator generator) {
        this.generator = generator;
        if (expansion != null) expansion.setGenerator(generator);
    }

    /** The on-demand growth of the simulated area. */
    public me.alex4386.typhon.engine.expansion.WorldExpansion expansion() {
        return expansion;
    }


    /**
     * Retunes the running world to changed definitions in place, keeping all state: every subsystem whose
     * configuration the new definitions change takes it at the next step (see {@link Engine#reconfigure}).
     * Only for changes {@link ConfigImpact} classifies as {@link ConfigImpact.Kind#LIVE}; call on the engine
     * thread between steps. Saves from now on record the new definitions, so a reopen sees no change.
     *
     * @throws IllegalArgumentException if a subsystem cannot take its change in place (nothing further
     *     is applied; the caller rebuilds instead)
     */
    public void reconfigureLive(WorldDefinition world, Collection<VolcanoDefinition> volcanoes) {
        Map<String, VolcanoDefinition> next = new TreeMap<>();
        for (VolcanoDefinition v : volcanoes) next.put(v.id(), v);
        if (!next.keySet().equals(systems.keySet())) {
            throw new IllegalArgumentException("volcanoes were added or removed; the world must be rebuilt");
        }
        reconfigureIfChanged(LavaFlow.ID, world.lava());
        reconfigureIfChanged(subsurface.id(), world.subsurfaceConfig());
        reconfigureIfChanged(expansion.id(), world.expansion());
        for (Map.Entry<String, VolcanoDefinition> e : next.entrySet()) {
            VolcanoDefinition v = e.getValue();
            Boolean active = activeOverrides.get(v.id());
            VolcanoDefinition effective = active == null || active == v.active() ? v : v.withActive(active);
            systems.get(e.getKey()).reconfigure(engine, effective.builder(terrain, lava, world, subsurface));
        }
        this.definition = world;
        for (VolcanoDefinition v : volcanoes) {
            if (declared.containsKey(v.id())) declared.put(v.id(), v);
            else if (added.containsKey(v.id())) added.put(v.id(), v);
        }
    }

    /**
     * The configuration every subsystem would have if the world were assembled from these definitions,
     * by subsystem id (world-level ones and every volcano's); nothing is built. A running world whose
     * engine records these hashes runs exactly these definitions.
     */
    public Map<String, Object> derivedConfigs(WorldDefinition world, Collection<VolcanoDefinition> volcanoes) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put(LavaFlow.ID, world.lava());
        out.put(subsurface.id(), world.subsurfaceConfig());
        out.put(expansion.id(), world.expansion());
        for (VolcanoDefinition v : volcanoes) {
            Boolean active = activeOverrides.get(v.id());
            VolcanoDefinition effective = active == null || active == v.active() ? v : v.withActive(active);
            out.putAll(effective.builder(terrain, lava, world, subsurface).subsystemConfigs());
        }
        return out;
    }

    /** Subsystems whose running configuration differs from what the definitions imply (empty: in sync). */
    public List<String> configDrift(WorldDefinition world, Collection<VolcanoDefinition> volcanoes) {
        List<String> drift = new ArrayList<>();
        for (Map.Entry<String, Object> e : derivedConfigs(world, volcanoes).entrySet()) {
            if (!Engine.configHash(e.getValue()).equals(engine.configHash(e.getKey()))) drift.add(e.getKey());
        }
        return drift;
    }

    private void reconfigureIfChanged(String id, Object config) {
        if (!Engine.configHash(config).equals(engine.configHash(id))) engine.reconfigure(id, config);
    }

    /** Rebuilds the engine with the current definitions, carrying all state over (between steps). */
    private void rebuild(Set<String> resyncSupply) {
        InMemorySaveStore snapshot = new InMemorySaveStore();
        engine.save(snapshot, pendingHistory::addAll);
        build(snapshot, Map.of(), resyncSupply);
    }

    // ── Runtime changes ──

    /** Adds a volcano while running; it starts from its definition. */
    public void addVolcano(VolcanoDefinition volcano) {
        if (systems.containsKey(volcano.id())) throw new IllegalArgumentException("volcano '" + volcano.id() + "' exists");
        added.put(volcano.id(), volcano);
        removed.remove(volcano.id());
        activeOverrides.remove(volcano.id());
        rebuild(Set.of());
    }

    /** Removes a volcano while running; its state is dropped, deposits it made stay in the world. */
    public void removeVolcano(String id) {
        if (!systems.containsKey(id)) throw new IllegalArgumentException("no volcano '" + id + "'");
        for (LavaSource source : List.copyOf(lava.sources())) {
            if (source.id().startsWith(id + "/")) lava.removeSource(source.id());
        }
        if (added.remove(id) == null) removed.add(id);
        activeOverrides.remove(id);
        rebuild(Set.of());
    }

    /** Puts a volcano to sleep (no deep magma supply) or wakes it; its state is kept. */
    public void setActive(String id, boolean active) {
        if (!systems.containsKey(id)) throw new IllegalArgumentException("no volcano '" + id + "'");
        activeOverrides.put(id, active);
        rebuild(Set.of(id));
    }

    // ── Saving ──

    /**
     * Saves engine state into the state store (incrementally), appends historical events to the
     * per-volcano history logs and records the definitions and runtime changes in
     * {@code world.json}. Call between steps.
     */
    public void save() {
        HistoryRouter router = new HistoryRouter(historyStore, systems.keySet());
        if (!pendingHistory.isEmpty()) {
            router.route(List.copyOf(pendingHistory));
            pendingHistory.clear();
        }
        engine.save(stateStore, router::route);
        stateStore.write(WORLD_STATE, SaveFormat.jsonBytes(worldState()));
        deleteStaleSubsystems();
        changes = ConfigChanges.NONE;
    }

    private JsonObject worldState() {
        JsonObject root = new JsonObject();
        root.addProperty("format", WORLD_STATE_FORMAT);
        JsonObject defs = new JsonObject();
        defs.add("world", json(definition.toTree()));
        JsonObject volcanoes = new JsonObject();
        for (VolcanoDefinition v : volcanoDefinitions()) volcanoes.add(v.id(), json(v.toTree()));
        defs.add("volcanoes", volcanoes);
        root.add("definitions", defs);
        JsonObject runtime = new JsonObject();
        JsonObject addedJson = new JsonObject();
        for (VolcanoDefinition v : added.values()) addedJson.add(v.id(), json(v.toTree()));
        runtime.add("added", addedJson);
        JsonArray removedJson = new JsonArray();
        removed.forEach(removedJson::add);
        runtime.add("removed", removedJson);
        JsonObject active = new JsonObject();
        activeOverrides.forEach(active::addProperty);
        runtime.add("active", active);
        root.add("runtime", runtime);
        return root;
    }

    /** Removes saved subsystems (and their fields) that no longer exist, e.g. of removed volcanoes. */
    private void deleteStaleSubsystems() {
        byte[] metaBytes = stateStore.read(SaveFormat.META);
        if (metaBytes == null) return;
        Set<String> live = new HashSet<>();
        for (JsonElement e : SaveFormat.parse(metaBytes).getAsJsonArray("subsystems")) {
            live.add(e.getAsJsonObject().get("id").getAsString());
        }
        Set<String> livePaths = new HashSet<>();
        Set<String> liveFieldPrefixes = new HashSet<>();
        for (String id : live) {
            livePaths.add(SaveFormat.subsystemPath(id));
            liveFieldPrefixes.add(SaveFormat.fieldsPrefix(id));
        }
        for (String path : stateStore.list("subsystems/")) {
            if (!livePaths.contains(path)) stateStore.delete(path);
        }
        for (String path : stateStore.list("fields/")) {
            boolean keep = false;
            for (String prefix : liveFieldPrefixes) {
                if (path.startsWith(prefix)) {
                    keep = true;
                    break;
                }
            }
            if (!keep) stateStore.delete(path);
        }
    }

    // ── Access ──

    public WorldDefinition definition() {
        return definition;
    }

    /** {@code null} for in-memory worlds. */
    public WorldDirectory directory() {
        return directory;
    }

    public SaveStore stateStore() {
        return stateStore;
    }

    public SaveStore historyStore() {
        return historyStore;
    }

    /** The current engine (replaced by runtime volcano changes — do not cache across them). */
    public Engine engine() {
        return engine;
    }

    public TerrainModel terrain() {
        return terrain;
    }

    public WorldModel worldModel() {
        return terrain.world();
    }

    public LavaFlow lava() {
        return lava;
    }

    /** The shared subsurface model (heat, groundwater, surface water). Replaced by engine rebuilds. */
    public Subsurface subsurface() {
        return subsurface;
    }

    /** Assembled volcanoes by id (sorted). */
    public Map<String, VolcanoSystem> volcanoes() {
        return Collections.unmodifiableMap(systems);
    }

    public VolcanoSystem volcano(String id) {
        VolcanoSystem v = systems.get(id);
        if (v == null) throw new IllegalArgumentException("no volcano '" + id + "'");
        return v;
    }

    /** Definition changes detected when this world was opened (empty for fresh worlds and after a save). */
    public ConfigChanges changes() {
        return changes;
    }

    // ── JSON helpers ──

    static JsonObject json(Map<String, Object> tree) {
        return SaveFormat.gson().toJsonTree(tree).getAsJsonObject();
    }

    private static Object toJava(JsonElement element) {
        return SaveFormat.gson().fromJson(element, new TypeToken<LinkedHashMap<String, Object>>() {}.getType());
    }
}
