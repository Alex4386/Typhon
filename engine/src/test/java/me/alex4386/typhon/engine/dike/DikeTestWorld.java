package me.alex4386.typhon.engine.dike;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainChunk;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.terrain.TerrainSnapshot;
import me.alex4386.typhon.engine.world.BlockId;

/** Shared fixtures: terrains, chambers and an engine wired with chamber → dike. */
final class DikeTestWorld {
    static final BlockId STONE = BlockId.minecraft("stone");
    static final int CHUNK_RADIUS = 12;

    private DikeTestWorld() {}

    static TerrainSnapshot terrain(IntBinaryOperator height) {
        List<TerrainChunk> chunks = new ArrayList<>();
        for (int cx = -CHUNK_RADIUS; cx < CHUNK_RADIUS; cx++) {
            for (int cz = -CHUNK_RADIUS; cz < CHUNK_RADIUS; cz++) {
                TerrainChunk chunk = new TerrainChunk(cx, cz);
                for (int x = cx * 16; x < cx * 16 + 16; x++) {
                    for (int z = cz * 16; z < cz * 16 + 16; z++) {
                        chunk.set(x, z, TerrainColumn.dry(height.applyAsInt(x, z), STONE));
                    }
                }
                chunks.add(chunk);
            }
        }
        return new TerrainSnapshot(chunks);
    }

    static TerrainSnapshot flat() {
        return terrain((x, z) -> 64);
    }

    /** Cone rising from y=64 to y=184 at the axis, slope 0.6. */
    static TerrainSnapshot cone() {
        return terrain((x, z) -> (int) Math.max(64, 184 - 0.6 * Math.sqrt(x * x + z * z)));
    }

    /** Hot, dry basaltic chamber (no exsolved gas, so its compressibility is constant). */
    static MagmaChamberConfig.Builder basalt(double overpressure) {
        return MagmaChamberConfig.builder("v", new BlockPos(0, 0, 0))
                .volume(1e11)
                .initialOverpressureMPa(overpressure)
                .initialTemperatureC(1180)
                .initialSilicaWt(50)
                .initialWaterWt(0.5)
                .supplyRate(0)
                .supplyVariability(0);
    }

    static DikeConfig fastConfig() {
        DikeConfig c = DikeConfig.defaults();
        c.timeScale = 20;
        c.conduitSealing = 0; // only forced dikes unless a test opts in
        return c;
    }

    record World(Engine engine, MagmaChamber chamber, DikePropagation dikes, TerrainModel terrain) {}

    static World world(long seed, MagmaChamberConfig chamberConfig, DikeConfig config, TerrainSnapshot snapshot,
            com.google.gson.JsonObject restore) {
        TerrainModel terrain = new TerrainModel();
        MagmaChamber chamber = new MagmaChamber(chamberConfig);
        DikePropagation dikes = new DikePropagation(config, DikeMagmaSource.of(chamber), terrain);
        Engine.Builder builder = Engine.builder(seed).add(terrain).add(chamber).add(dikes);
        if (restore != null) builder.restore(restore);
        Engine engine = builder.build();
        engine.submit(snapshot);
        return new World(engine, chamber, dikes, terrain);
    }

    static List<EngineFrame> run(Engine engine, int ticks) {
        List<EngineFrame> frames = new ArrayList<>();
        for (int i = 0; i < ticks; i++) frames.add(engine.tick());
        return frames;
    }

    /** Runs until the first event of {@code type} (or {@code maxTicks}); returns its tick or −1. */
    static long runUntil(Engine engine, Class<? extends EngineEvent> type, int maxTicks) {
        for (int i = 0; i < maxTicks; i++) {
            EngineFrame frame = engine.tick();
            for (EngineEvent e : frame.events()) if (type.isInstance(e)) return frame.tick();
        }
        return -1;
    }

    static <T extends EngineEvent> List<T> events(List<EngineFrame> frames, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (EngineFrame f : frames) for (EngineEvent e : f.events()) if (type.isInstance(e)) out.add(type.cast(e));
        return out;
    }
}
