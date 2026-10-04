package me.alex4386.typhon.engine.terrain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.save.FieldChunk;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;
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
 * <p>Terrain is persisted with the engine state (it is the simulator's source of truth). Hosts may
 * still send fresh snapshots after a restart to pick up edits made while the engine was stopped.
 */
public final class TerrainModel implements Subsystem {
    public static final String ID = "terrain";

    private final Map<Long, TerrainChunk> chunks = new HashMap<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public double periodSeconds() {
        return Double.POSITIVE_INFINITY; // command-driven
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

    /** Read-only views of every known chunk, in no particular order. */
    public List<TerrainChunkView> chunks() {
        return new ArrayList<>(chunks.values());
    }

    // ── Persistence: one field chunk per terrain chunk; surface ids through a shared palette ──

    private static final int SCHEMA = 1;

    @Override
    public void saveState(StateWriter out) {
        StateWriter.Field field = out.field("columns", SCHEMA);
        Map<BlockId, Integer> palette = new LinkedHashMap<>();
        List<Long> keys = new ArrayList<>(chunks.keySet());
        keys.sort(null);
        for (long key : keys) {
            TerrainChunk chunk = chunks.get(key);
            int[] surface = new int[TerrainChunk.AREA];
            for (int i = 0; i < TerrainChunk.AREA; i++) {
                surface[i] = palette.computeIfAbsent(chunk.surface[i], id -> palette.size());
            }
            field.put(chunk.chunkX(), chunk.chunkZ(), new FieldChunk()
                    .ints("groundY", chunk.groundY.clone())
                    .ints("waterY", chunk.waterY.clone())
                    .ints("surface", surface));
        }
        JsonArray ids = new JsonArray();
        palette.keySet().forEach(id -> ids.add(id.toString()));
        out.json().add("palette", ids);
    }

    @Override
    public void loadState(StateReader in) {
        chunks.clear();
        StateReader.Field field = in.field("columns");
        if (field == null) return;
        List<BlockId> palette = new ArrayList<>();
        for (JsonElement e : in.json().getAsJsonArray("palette")) palette.add(BlockId.parse(e.getAsString()));
        for (StateReader.Entry entry : field.chunks()) {
            TerrainChunk chunk = new TerrainChunk(entry.chunkX(), entry.chunkZ());
            System.arraycopy(entry.data().ints("groundY"), 0, chunk.groundY, 0, TerrainChunk.AREA);
            System.arraycopy(entry.data().ints("waterY"), 0, chunk.waterY, 0, TerrainChunk.AREA);
            int[] surface = entry.data().ints("surface");
            for (int i = 0; i < TerrainChunk.AREA; i++) chunk.surface[i] = palette.get(surface[i]);
            chunks.put(key(entry.chunkX(), entry.chunkZ()), chunk);
        }
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }
}
