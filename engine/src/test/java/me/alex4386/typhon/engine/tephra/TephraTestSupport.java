package me.alex4386.typhon.engine.tephra;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.terrain.TerrainChunk;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.terrain.TerrainSnapshot;
import me.alex4386.typhon.engine.world.BlockId;

final class TephraTestSupport {
    static final BlockId GRASS = BlockId.minecraft("grass_block");

    private TephraTestSupport() {}

    /** Snapshot of chunks within ±radius chunks of the origin, heights from {@code ground(x, z)}. */
    static TerrainSnapshot terrain(int chunkRadius, IntBinaryOperator ground) {
        List<TerrainChunk> chunks = new ArrayList<>();
        for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
            for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
                TerrainChunk chunk = new TerrainChunk(cx, cz);
                for (int z = cz * 16; z < cz * 16 + 16; z++) {
                    for (int x = cx * 16; x < cx * 16 + 16; x++) {
                        chunk.set(x, z, TerrainColumn.dry(ground.applyAsInt(x, z), GRASS));
                    }
                }
                chunks.add(chunk);
            }
        }
        return new TerrainSnapshot(chunks);
    }

    static TerrainSnapshot flat(int chunkRadius, int groundY) {
        return terrain(chunkRadius, (x, z) -> groundY);
    }

    /** What a host does after a restart: re-sample the (engine-modified) world into snapshots. */
    static TerrainSnapshot resample(TerrainModel model, int chunkRadius) {
        List<TerrainChunk> chunks = new ArrayList<>();
        for (int cz = -chunkRadius; cz <= chunkRadius; cz++) {
            for (int cx = -chunkRadius; cx <= chunkRadius; cx++) {
                TerrainChunk chunk = new TerrainChunk(cx, cz);
                for (int z = cz * 16; z < cz * 16 + 16; z++) {
                    for (int x = cx * 16; x < cx * 16 + 16; x++) {
                        chunk.set(x, z, model.column(x, z));
                    }
                }
                chunks.add(chunk);
            }
        }
        return new TerrainSnapshot(chunks);
    }

    static <T extends EngineEvent> List<T> events(List<EngineFrame> frames, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (EngineFrame frame : frames) {
            for (EngineEvent event : frame.events()) {
                if (type.isInstance(event)) out.add(type.cast(event));
            }
        }
        return out;
    }
}
