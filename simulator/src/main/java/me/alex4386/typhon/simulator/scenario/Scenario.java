package me.alex4386.typhon.simulator.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.lava.LavaConfig;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.save.SaveStore;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.world.Edifice;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.engine.world.WorldSpec;
import me.alex4386.typhon.engine.worlds.World;
import me.alex4386.typhon.simulator.terrain.ColumnGrid;
import me.alex4386.typhon.simulator.terrain.ContextTerrain;
import me.alex4386.typhon.simulator.world.VoxelWorld;

/**
 * A ready-to-run simulation: engine, shared terrain and lava, the volcanoes, and the in-memory world
 * that receives the engine's block changes. A fresh scenario has the initial terrain snapshot queued;
 * a restored one (see {@link Options#restore()}) resumes the saved engine and host world instead.
 */
public final class Scenario {
    private final String presetName;
    private final long seed;
    private final ColumnGrid initialTerrain;
    private final TerrainModel terrain;
    private final LavaFlow lava;
    private final List<VolcanoSystem> volcanoes;
    private final Engine engine;
    private final VoxelWorld world;
    private final List<Consumer<Scenario>> afterFirstTick;
    private final boolean restored;
    private final World session;
    private final me.alex4386.typhon.engine.expansion.WorldExpansion expansion;

    /**
     * How the engine is built.
     *
     * @param baseStepMicros engine base step (simulation resolution, not a game tick)
     * @param restore save to resume from, or {@code null} for a fresh run
     */
    public record Options(long baseStepMicros, SaveStore restore) {
        public static final Options DEFAULT = new Options(Engine.DEFAULT_BASE_STEP_MICROS, null);

        public Options withBaseStepMicros(long micros) {
            return new Options(micros, restore);
        }

        public Options withRestore(SaveStore store) {
            return new Options(baseStepMicros, store);
        }
    }

    private Scenario(Builder b) {
        this.presetName = b.presetName;
        this.seed = b.seed;
        this.initialTerrain = b.terrainGrid;
        this.terrain = b.terrain;
        this.lava = b.lava;
        this.volcanoes = List.copyOf(b.volcanoes);
        this.afterFirstTick = List.copyOf(b.afterFirstTick);

        Engine.Builder engineBuilder = Engine.builder(seed).baseStepMicros(b.options.baseStepMicros()).add(terrain);
        for (VolcanoSystem volcano : volcanoes) volcano.addTo(engineBuilder);
        engineBuilder.add(lava);
        // grows on demand like a world (same subsystem, so a preset and its world template stay identical)
        this.expansion = new me.alex4386.typhon.engine.expansion.WorldExpansion(terrain,
                me.alex4386.typhon.engine.expansion.ExpansionConfig.DEFAULTS);
        me.alex4386.typhon.engine.subsurface.Subsurface sub = null;
        for (VolcanoSystem v : volcanoes) if (sub == null) sub = v.subsurface();
        me.alex4386.typhon.engine.expansion.ExpansionWiring.wire(expansion, lava, sub, volcanoes);
        expansion.setGenerator(initialTerrain.source());
        engineBuilder.add(expansion);
        SaveStore restore = b.options.restore();
        if (restore != null) engineBuilder.restore(restore);
        this.engine = engineBuilder.build();
        this.world = new VoxelWorld(initialTerrain);
        attachRelief(terrain, initialTerrain);
        this.restored = restore != null;
        this.session = null;
        if (restored) {
            byte[] edits = restore.read(VoxelWorld.SAVE_PATH);
            if (edits != null) world.loadEdits(edits);
        } else {
            this.engine.submit(initialTerrain.toSnapshot());
        }
    }

    private Scenario(String name, World session, ColumnGrid initialTerrain, boolean restored) {
        this.presetName = name;
        this.seed = session.definition().seed();
        this.initialTerrain = initialTerrain;
        this.terrain = session.terrain();
        this.lava = session.lava();
        this.volcanoes = List.copyOf(session.volcanoes().values());
        this.afterFirstTick = List.of();
        this.engine = session.engine();
        this.world = new VoxelWorld(initialTerrain);
        attachRelief(terrain, initialTerrain);
        if (initialTerrain.source() != null) session.setTerrainGenerator(initialTerrain.source());
        this.expansion = session.expansion();
        this.restored = restored;
        this.session = session;
        if (restored) {
            byte[] edits = session.stateStore().read(VoxelWorld.SAVE_PATH);
            if (edits != null) world.loadEdits(edits);
        }
    }

    /**
     * Runs a {@link World} (a {@code worlds/<name>} directory or an in-memory world) through the
     * simulator; {@code initialTerrain} must be the terrain the world was created on.
     */
    public static Scenario fromWorld(String name, World session, ColumnGrid initialTerrain, boolean restored) {
        if (session.volcanoes().isEmpty()) throw new IllegalStateException("A world needs at least one volcano to run");
        return new Scenario(name, session, initialTerrain, restored);
    }

    /**
     * Gives the world model the generator's continuous surface (in metres), so the fine surface
     * around vents starts from the true crater shape rather than an interpolation of columns.
     */
    private static void attachRelief(TerrainModel terrain, ColumnGrid grid) {
        ColumnGrid.Relief relief = grid.relief();
        if (relief == null) return;
        var world = terrain.world();
        world.setRelief((xm, zm) -> {
            double size = world.spec().metersPerColumn();
            return relief.topBlocks(xm / size, zm / size) * size;
        });
    }

    /** Real-scale worlds (columns ≥ 10 m) show this much terrain around the core by default (m). */
    public static final double REAL_CONTEXT_M = 30_000;
    /** Compact worlds show at least this much (m), and at least four core widths. */
    public static final double COMPACT_CONTEXT_M = 6_000;

    private double contextExtentM = Double.NaN;
    private ContextTerrain context;

    /**
     * Sets the context extent (full width, m) before {@link #context()} is first used, e.g. from
     * {@code world.yaml}'s {@code terrain.contextExtentM}; {@code NaN} = default.
     */
    public void setContextExtent(double meters) {
        this.contextExtentM = meters;
        this.context = null;
    }

    /**
     * The coarse terrain around the simulated core ({@link ContextTerrain}), by default 30 km wide
     * for real-scale worlds and four core widths (at least 6 km) for compact ones.
     */
    public synchronized ContextTerrain context() {
        if (context == null) {
            double size = terrain.world().spec().metersPerColumn();
            double core = initialTerrain.size() * size;
            double extent = !Double.isNaN(contextExtentM) ? contextExtentM
                    : size >= 10 ? Math.max(REAL_CONTEXT_M, core) : Math.max(COMPACT_CONTEXT_M, 4 * core);
            context = new ContextTerrain(initialTerrain, size, extent);
        }
        return context;
    }

    /** The world this scenario runs, or {@code null} for preset scenarios. */
    public World session() {
        return session;
    }

    /** Saves the engine and the simulator's host world (between steps). */
    public void save(SaveStore store) {
        engine.save(store);
        store.write(VoxelWorld.SAVE_PATH, world.saveEdits());
    }

    /** Saves a world scenario into its own world directory (state, history, host world). */
    public void saveWorld() {
        if (session == null) throw new IllegalStateException("not a world scenario");
        session.save();
        session.stateStore().write(VoxelWorld.SAVE_PATH, world.saveEdits());
    }

    /** Whether this scenario resumed from a save. */
    public boolean restored() { return restored; }

    public String presetName() { return presetName; }
    public long seed() { return seed; }
    public ColumnGrid initialTerrain() { return initialTerrain; }
    public TerrainModel terrain() { return terrain; }
    public LavaFlow lava() { return lava; }
    public List<VolcanoSystem> volcanoes() { return volcanoes; }
    /** The first (primary) volcano; presets put the main subject first. */
    public VolcanoSystem volcano() { return volcanoes.get(0); }
    public Engine engine() { return engine; }
    public VoxelWorld world() { return world; }
    /** On-demand growth of the simulated area. */
    public me.alex4386.typhon.engine.expansion.WorldExpansion expansion() { return expansion; }

    /** Runs set-up hooks that need the terrain snapshot applied (called once after the first tick). */
    public void runAfterFirstTick() {
        for (Consumer<Scenario> hook : afterFirstTick) hook.accept(this);
    }

    /**
     * Assembles a scenario. The terrain model and shared lava field are created first so the preset can
     * build its volcanoes against them.
     */
    public static final class Builder {
        private final String presetName;
        private final long seed;
        private final ColumnGrid terrainGrid;
        private final TerrainModel terrain;
        private final LavaFlow lava;
        private final List<VolcanoSystem> volcanoes = new ArrayList<>();
        private final List<Consumer<Scenario>> afterFirstTick = new ArrayList<>();
        private Options options = Options.DEFAULT;

        public Builder(String presetName, long seed, ColumnGrid terrainGrid, LavaConfig lavaConfig) {
            this(presetName, seed, terrainGrid, lavaConfig, new TerrainModel());
        }

        /**
         * A scenario on a world model with explicit geology ({@code spec}, its metres per column must
         * match the volcanoes' scaling) and volcano edifices applied to imported columns.
         */
        public Builder(String presetName, long seed, ColumnGrid terrainGrid, WorldSpec spec, List<Edifice> edifices) {
            this(presetName, seed, terrainGrid, LavaConfig.defaults(), worldTerrain(spec, edifices));
        }

        private Builder(String presetName, long seed, ColumnGrid terrainGrid, LavaConfig lavaConfig,
                TerrainModel terrain) {
            this.presetName = Objects.requireNonNull(presetName);
            this.seed = seed;
            this.terrainGrid = Objects.requireNonNull(terrainGrid);
            this.terrain = terrain;
            this.lava = new LavaFlow(terrain, lavaConfig);
        }

        private static TerrainModel worldTerrain(WorldSpec spec, List<Edifice> edifices) {
            WorldModel world = new WorldModel(spec);
            world.setEdifices(edifices);
            return new TerrainModel(world);
        }

        public Builder(String presetName, long seed, ColumnGrid terrainGrid) {
            this(presetName, seed, terrainGrid, LavaConfig.defaults());
        }

        public long seed() { return seed; }
        public ColumnGrid terrainGrid() { return terrainGrid; }
        public TerrainModel terrain() { return terrain; }
        public LavaFlow lava() { return lava; }

        public Builder volcano(VolcanoSystem volcano) {
            volcanoes.add(volcano);
            return this;
        }

        public Builder options(Options options) {
            this.options = Objects.requireNonNull(options);
            return this;
        }

        public Builder afterFirstTick(Consumer<Scenario> hook) {
            afterFirstTick.add(hook);
            return this;
        }

        public Scenario build() {
            if (volcanoes.isEmpty()) throw new IllegalStateException("A scenario needs at least one volcano");
            return new Scenario(this);
        }
    }
}
