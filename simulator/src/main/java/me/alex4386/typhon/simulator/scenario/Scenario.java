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
import me.alex4386.typhon.engine.worlds.World;
import me.alex4386.typhon.simulator.terrain.ColumnGrid;
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
        SaveStore restore = b.options.restore();
        if (restore != null) engineBuilder.restore(restore);
        this.engine = engineBuilder.build();
        this.world = new VoxelWorld(initialTerrain);
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
        private final TerrainModel terrain = new TerrainModel();
        private final LavaFlow lava;
        private final List<VolcanoSystem> volcanoes = new ArrayList<>();
        private final List<Consumer<Scenario>> afterFirstTick = new ArrayList<>();
        private Options options = Options.DEFAULT;

        public Builder(String presetName, long seed, ColumnGrid terrainGrid, LavaConfig lavaConfig) {
            this.presetName = Objects.requireNonNull(presetName);
            this.seed = seed;
            this.terrainGrid = Objects.requireNonNull(terrainGrid);
            this.lava = new LavaFlow(terrain, lavaConfig);
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
