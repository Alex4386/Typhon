package me.alex4386.typhon.engine.terrain;

import java.util.HashMap;
import java.util.Map;
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.world.BlockId;

/**
 * The engine's view of the world surface: a sparse set of chunk column grids.
 *
 * <p>Hosts feed it with {@link TerrainSnapshot} commands; simulation subsystems read it and update it
 * when they change the surface (e.g. lava solidifying raises the ground). Register it before the
 * subsystems that depend on it so snapshots are applied first.
 *
 * <p>Terrain is not persisted with the engine state: after a restart the host re-sends snapshots of
 * the area around each volcano.
 */
public final class TerrainModel implements Subsystem {
    public static final String ID = "terrain";

    private final Map<Long, TerrainChunk> chunks = new HashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int interval() {
        return Integer.MAX_VALUE;
    }

    @Override
    public void registerCommands(CommandBus bus) {
        bus.register(TerrainSnapshot.class, this::apply);
    }

    @Override
    public void step(StepContext context) {}

    public void apply(TerrainSnapshot snapshot) {
        for (TerrainChunk chunk : snapshot.chunks()) {
            chunks.put(key(chunk.chunkX(), chunk.chunkZ()), chunk.copy());
        }
    }

    public boolean isKnown(int x, int z) {
        return chunks.containsKey(key(x >> 4, z >> 4));
    }

    /**
     * Read-only view of a chunk for hot loops, or {@code null} if unknown. A view stays valid until
     * a {@link TerrainSnapshot} replaces the chunk; compare instances and {@link
     * TerrainChunkView#version()} to detect changes.
     */
    public TerrainChunkView chunkView(int chunkX, int chunkZ) {
        return chunks.get(key(chunkX, chunkZ));
    }

    /** Returns the column, or {@code null} if the host has not sent that chunk. */
    public TerrainColumn column(int x, int z) {
        TerrainChunk chunk = chunks.get(key(x >> 4, z >> 4));
        return chunk == null ? null : chunk.get(x, z);
    }

    /** Ground height, or {@code fallback} if the column is unknown. */
    public int groundY(int x, int z, int fallback) {
        TerrainChunk chunk = chunks.get(key(x >> 4, z >> 4));
        return chunk == null ? fallback : chunk.groundY[TerrainChunk.index(x, z)];
    }

    /** Updates a column the engine itself changed. Creates the chunk if it is unknown. */
    public void setColumn(int x, int z, TerrainColumn column) {
        chunks.computeIfAbsent(key(x >> 4, z >> 4), k -> new TerrainChunk(x >> 4, z >> 4)).set(x, z, column);
    }

    public void setGround(int x, int z, int groundY, BlockId surface) {
        TerrainColumn current = column(x, z);
        int waterY = current == null ? TerrainColumn.NO_WATER : current.waterY();
        setColumn(x, z, new TerrainColumn(groundY, waterY, surface));
    }

    public int chunkCount() {
        return chunks.size();
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }
}
