package me.alex4386.typhon.simulator.scenario;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.lava.LavaConfig;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.simulator.terrain.ColumnGrid;
import me.alex4386.typhon.simulator.world.VoxelWorld;

/**
 * A ready-to-run simulation: engine, shared terrain and lava, the volcanoes, and the in-memory world
 * that receives the engine's block changes. The initial terrain snapshot is already queued.
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

    private Scenario(Builder b) {
        this.presetName = b.presetName;
        this.seed = b.seed;
        this.initialTerrain = b.terrainGrid;
        this.terrain = b.terrain;
        this.lava = b.lava;
        this.volcanoes = List.copyOf(b.volcanoes);
        this.afterFirstTick = List.copyOf(b.afterFirstTick);

        Engine.Builder engineBuilder = Engine.builder(seed).add(terrain);
        for (VolcanoSystem volcano : volcanoes) volcano.addTo(engineBuilder);
        engineBuilder.add(lava);
        this.engine = engineBuilder.build();
        this.engine.submit(initialTerrain.toSnapshot());
        this.world = new VoxelWorld(initialTerrain);
    }

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
