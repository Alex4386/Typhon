package me.alex4386.typhon.engine.massflow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainChunk;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.terrain.TerrainSnapshot;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.LayerView;
import me.alex4386.typhon.engine.world.WorldModel;

/** Synthetic terrain plus helpers for driving mass-flow fields in tests. */
final class MassFlowTestWorld {
    static final BlockId STONE = BlockId.minecraft("stone");
    static final int NO_WATER = TerrainColumn.NO_WATER;

    final int minChunkX, minChunkZ, maxChunkX, maxChunkZ;
    final TerrainModel terrain = new TerrainModel();
    final List<EngineFrame> frames = new ArrayList<>();

    MassFlowTestWorld(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, IntBinaryOperator ground,
            IntBinaryOperator waterY) {
        this.minChunkX = minChunkX;
        this.minChunkZ = minChunkZ;
        this.maxChunkX = maxChunkX;
        this.maxChunkZ = maxChunkZ;
        List<TerrainChunk> chunks = new ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                TerrainChunk chunk = new TerrainChunk(cx, cz);
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int x = cx * 16 + lx;
                        int z = cz * 16 + lz;
                        chunk.set(x, z, new TerrainColumn(ground.applyAsInt(x, z), waterY.applyAsInt(x, z), STONE));
                    }
                }
                chunks.add(chunk);
            }
        }
        terrain.apply(new TerrainSnapshot(chunks));
    }

    MassFlowTestWorld(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, IntBinaryOperator ground) {
        this(minChunkX, minChunkZ, maxChunkX, maxChunkZ, ground, (x, z) -> NO_WATER);
    }

    /** Ramp descending toward +x at {@code slope} down to a flat plain from {@code plainX} on. */
    static IntBinaryOperator rampToPlain(double slope, int plainX, int plainY) {
        return (x, z) -> x >= plainX ? plainY : plainY + (int) Math.round((plainX - x) * slope);
    }

    /** Current terrain (including engine edits), as a host would re-send after a restart. */
    TerrainSnapshot resample() {
        List<TerrainChunk> chunks = new ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                TerrainChunk chunk = new TerrainChunk(cx, cz);
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        int x = cx * 16 + lx;
                        int z = cz * 16 + lz;
                        chunk.set(x, z, terrain.column(x, z));
                    }
                }
                chunks.add(chunk);
            }
        }
        return new TerrainSnapshot(chunks);
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

    /** The surface blocks the block cache shows over {@code [0, size)²}. */
    List<BlockId> surfaces(int size) {
        List<BlockId> list = new ArrayList<>();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                TerrainColumn column = terrain.column(x, z);
                if (column != null) list.add(column.surface());
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
