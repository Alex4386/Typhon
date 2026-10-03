package me.alex4386.typhon.engine.lava;

import java.util.List;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineEvent;

/** Events emitted by {@link LavaFlow}. */
public final class LavaEvents {
    private LavaEvents() {}

    /**
     * Periodic flow telemetry.
     *
     * @param front active lava column farthest from any source
     * @param lengthM horizontal distance from the nearest source to {@code front}
     */
    public record LavaFlowFront(long tick, BlockPos front, double lengthM, int activeCells, double volumeM3)
            implements EngineEvent {}

    /** Lava that turned to rock during one step. */
    public record LavaSolidified(long tick, int cells, double volumeM3, int blocks) implements EngineEvent {}

    /** Lava reached a submerged column for the first time (steam, littoral explosions). */
    public record LavaEnteredWater(long tick, BlockPos pos, double volumeM3) implements EngineEvent {}

    /** The flow reached chunks the host has not sent terrain for; they act as walls until it does. */
    public record TerrainNeeded(long tick, List<ChunkCoord> chunks) implements EngineEvent {
        public TerrainNeeded {
            chunks = List.copyOf(chunks);
        }
    }

    public record ChunkCoord(int x, int z) {}
}
