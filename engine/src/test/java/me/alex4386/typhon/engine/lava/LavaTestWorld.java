package me.alex4386.typhon.engine.lava;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainChunk;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.terrain.TerrainSnapshot;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.BlockState;

/** Synthetic terrain plus helpers for driving a {@link LavaFlow} in tests. */
final class LavaTestWorld {
    static final BlockId STONE = BlockId.minecraft("stone");
    static final int NO_WATER = TerrainColumn.NO_WATER;

    final int minChunkX, minChunkZ, maxChunkX, maxChunkZ;
    final TerrainModel terrain = new TerrainModel();
    final List<EngineFrame> frames = new ArrayList<>();

    /** Terrain covering chunks [minChunk, maxChunk] inclusive. */
    LavaTestWorld(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, IntBinaryOperator ground,
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

    LavaTestWorld(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, IntBinaryOperator ground) {
        this(minChunkX, minChunkZ, maxChunkX, maxChunkZ, ground, (x, z) -> NO_WATER);
    }

    /** Copy of the current terrain (including engine edits), as a host would re-send after a restart. */
    TerrainModel copyTerrain() {
        TerrainModel copy = new TerrainModel();
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
        copy.apply(new TerrainSnapshot(chunks));
        return copy;
    }

    /** Base step of engines this world builds (µs); {@link #coarse} lengthens it. */
    long baseStepMicros = Engine.DEFAULT_BASE_STEP_MICROS;

    /** Engines built from now on step {@code factor} × 50 ms per tick (lava sub-steps inside). */
    LavaTestWorld coarse(double factor) {
        baseStepMicros = Math.round(Engine.DEFAULT_BASE_STEP_MICROS * factor);
        return this;
    }

    Engine engine(LavaFlow lava, long seed) {
        return Engine.builder(seed).baseStepMicros(baseStepMicros).add(terrain).add(lava).build();
    }

    Engine engine(LavaFlow lava, long seed, int threads) {
        return Engine.builder(seed).baseStepMicros(baseStepMicros).threads(threads).add(terrain).add(lava).build();
    }

    void run(Engine engine, int ticks) {
        for (int i = 0; i < ticks; i++) frames.add(engine.step());
    }

    /** Final block state per position after applying all frames in order. */
    Map<BlockPos, BlockState> appliedBlocks() {
        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (EngineFrame frame : frames) {
            for (BlockChange change : frame.blockChanges()) blocks.put(change.pos(), change.to());
        }
        return blocks;
    }

    <T extends EngineEvent> List<T> events(Class<T> type) {
        List<T> list = new ArrayList<>();
        for (EngineFrame frame : frames) {
            for (EngineEvent e : frame.events()) if (type.isInstance(e)) list.add(type.cast(e));
        }
        return list;
    }

    static double maxX(LavaFlow lava, int minX, int maxX, int minZ, int maxZ) {
        double best = Double.NEGATIVE_INFINITY;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (lava.thickness(x, z) > 0) best = Math.max(best, x);
            }
        }
        return best;
    }

    /** Rheology with fixed viscosity and yield strength, for isolating the flow law. */
    record FixedRheology(double viscosity, double yieldStrength) implements LavaRheology {
        @Override public double viscosityPaS(double temperatureC, double silicaWt, double waterWt) { return viscosity; }
        @Override public double yieldStrengthPa(double temperatureC, double silicaWt) { return yieldStrength; }
        @Override public double liquidusC(double silicaWt) { return 1200; }
        @Override public double solidusC(double silicaWt) { return 1000; }
    }
}
