package me.alex4386.typhon.engine.terrain;

import java.util.List;
import me.alex4386.typhon.engine.command.EngineCommand;

/**
 * Host → engine: fresh surface data for a set of chunks, sampled from the live world.
 *
 * <p>The chunks are copied on receipt, so the host may reuse its arrays afterwards.
 */
public record TerrainSnapshot(List<TerrainChunk> chunks) implements EngineCommand {
    public TerrainSnapshot {
        chunks = List.copyOf(chunks);
    }
}
