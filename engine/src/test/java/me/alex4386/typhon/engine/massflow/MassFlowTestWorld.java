package me.alex4386.typhon.engine.massflow;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.GroundImport;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.testing.TestGround;
import me.alex4386.typhon.engine.testing.TestGround.Elevation;
import me.alex4386.typhon.engine.world.LayerView;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.WorldModel;

/** Synthetic terrain (1 m columns, elevations in metres) plus helpers for driving mass-flow fields in tests. */
final class MassFlowTestWorld {
    static final double NO_WATER = Double.NaN;
    static final double COLUMN_M = 1.0;

    final int minChunkX, minChunkZ, maxChunkX, maxChunkZ;
    final TerrainModel terrain = TestGround.terrain(COLUMN_M);
    final List<EngineFrame> frames = new ArrayList<>();

    MassFlowTestWorld(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, Elevation ground, Elevation water) {
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.maxChunkX = maxChunkX;
        this.maxChunkZ = maxChunkZ;
        terrain.apply(TestGround.chunks(minChunkX, minChunkZ, maxChunkX, maxChunkZ, ground, water));
    }

    MassFlowTestWorld(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, Elevation ground) {
        this(minChunkX, minChunkZ, maxChunkX, maxChunkZ, ground, TestGround.DRY);
    }

    /** Ramp descending toward +x at {@code slope} down to a flat plain at {@code plainZ} m from {@code plainX} on. */
    static Elevation rampToPlain(double slope, int plainX, double plainZ) {
        return (x, z) -> x >= plainX ? plainZ : plainZ + (plainX - x) * slope;
    }

    /** Current terrain (including engine edits), as a host would re-send after a restart. */
    GroundImport resample() {
        return TestGround.copy(terrain.world(), minChunkX * 16, minChunkZ * 16, maxChunkX * 16 + 15,
                maxChunkZ * 16 + 15);
    }

    /** Base step of engines this world builds (µs); {@link #coarse} lengthens it. */
    long baseStepMicros = Engine.DEFAULT_BASE_STEP_MICROS;

    /** Engines built from now on step {@code factor} × 50 ms per tick (flows sub-step inside). */
    MassFlowTestWorld coarse(double factor) {
        baseStepMicros = Math.round(Engine.DEFAULT_BASE_STEP_MICROS * factor);
        return this;
    }

    Engine engine(MassFlowField field, long seed) {
        return Engine.builder(seed).baseStepMicros(baseStepMicros).add(terrain).add(field).build();
    }

    Engine engine(MassFlowField field, long seed, int threads) {
        return Engine.builder(seed).baseStepMicros(baseStepMicros).threads(threads).add(terrain).add(field).build();
    }

    void run(Engine engine, int ticks) {
        for (int i = 0; i < ticks; i++) frames.add(engine.step());
    }

    /** Runs until nothing flows (or {@code maxTicks}); returns ticks run. */
    int runUntilStill(Engine engine, MassFlowField field, int maxTicks) {
        int t = 0;
        do {
            run(engine, 20);
            t += 20;
        } while (field.activeCellCount() > 0 && t < maxTicks);
        return t;
    }

    /** Every world-model layer of the columns in {@code [0, size)²}. */
    List<LayerView> layers(int size) {
        WorldModel world = terrain.world();
        List<LayerView> list = new ArrayList<>();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                if (!world.isKnown(x, z)) continue;
                for (int k = 0; k < world.layerCount(x, z); k++) list.add(world.layer(x, z, k));
            }
        }
        return list;
    }

    /** The surface material of each column over {@code [0, size)²}. */
    List<Material> surfaces(int size) {
        WorldModel world = terrain.world();
        List<Material> list = new ArrayList<>();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                if (world.isKnown(x, z)) list.add(world.layer(x, z, world.layerCount(x, z) - 1).materialInfo());
            }
        }
        return list;
    }

    <T extends EngineEvent> List<T> events(Class<T> type) {
        List<T> list = new ArrayList<>();
        for (EngineFrame frame : frames) {
            for (EngineEvent e : frame.events()) if (type.isInstance(e)) list.add(type.cast(e));
        }
        return list;
    }

    /** Largest x reached by flow or deposit within the area. */
    int reachX(MassFlowField field) {
        int best = Integer.MIN_VALUE;
        for (int x = minChunkX * 16; x < (maxChunkX + 1) * 16; x++) {
            for (int z = minChunkZ * 16; z < (maxChunkZ + 1) * 16; z++) {
                if (field.depth(x, z) > 0 || field.depositThickness(x, z) > 1e-6) best = Math.max(best, x);
            }
        }
        return best;
    }

    /** Total deposit thickness × cell area over the area. */
    double depositVolume(MassFlowField field, double cellArea) {
        double sum = 0;
        for (int x = minChunkX * 16; x < (maxChunkX + 1) * 16; x++) {
            for (int z = minChunkZ * 16; z < (maxChunkZ + 1) * 16; z++) sum += field.depositThickness(x, z);
        }
        return sum * cellArea;
    }
}
