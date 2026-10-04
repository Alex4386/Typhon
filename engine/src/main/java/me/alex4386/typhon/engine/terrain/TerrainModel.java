package me.alex4386.typhon.engine.terrain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.save.FieldChunk;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.BlockMaterialPalette;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.UnitTable;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.engine.world.WorldSpec;

/**
 * Block-level view of the {@link WorldModel}: per column the ground block, the water surface block
 * and the visible surface block, in sparse 16×16 chunks.
 *
 * <p>The world model (stratigraphic stacks in real metres) is the source of truth for what lies
 * underground; this class is the bridge that keeps the existing block-oriented subsystems working.
 * Every surface change made through it is mirrored into the stacks: raising the ground deposits the
 * surface block's material (unattributed unless a unit is given), lowering it erodes. A host
 * {@link TerrainSnapshot} is an import path: unknown columns are built from the world spec's
 * geology, known ones are reconciled (layers are kept when the surface did not move). Blocks are
 * cubes {@link WorldSpec#metersPerColumn()} on a side, so ground block {@code y} has its top at
 * {@code (y + 1)·L} metres.
 *
 * <p>Register it before the subsystems that depend on it so snapshots are applied first. The block
 * cache (ground, water and surface ids) and the world model are both persisted.
 */
public final class TerrainModel implements Subsystem {
    public static final String ID = "terrain";
    private static final double EPS = 1e-6;

    private final Map<Long, TerrainChunk> chunks = new HashMap<>();
    private final WorldModel world;
    private final BlockMaterialPalette palette;

    /** Stand-alone terrain over a default one-metre world model (tests, simple hosts). */
    public TerrainModel() {
        this(new WorldModel(WorldSpec.defaults()));
    }

    public TerrainModel(WorldModel world) {
        this(world, BlockMaterialPalette.minecraft());
    }

    public TerrainModel(WorldModel world, BlockMaterialPalette palette) {
        this.world = Objects.requireNonNull(world);
        this.palette = Objects.requireNonNull(palette);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public double periodSeconds() {
        return Double.POSITIVE_INFINITY; // command-driven
    }

    @Override
    public Object config() {
        return world.spec();
    }

    @Override
    public void registerCommands(CommandBus bus) {
        bus.register(TerrainSnapshot.class, this::apply);
    }

    @Override
    public void step(StepContext context) {}

    /** The underlying world model. */
    public WorldModel world() {
        return world;
    }

    /**
     * Sets the block size (metres per block and per column) while the world model is still empty;
     * a no-op if it already matches. Volcano assemblies call this like {@code LavaFlow#setMetersPerBlock}.
     */
    public void setMetersPerBlock(double meters) {
        WorldSpec spec = world.spec();
        if (spec.metersPerColumn() == meters) return;
        world.setSpec(spec.withMetersPerColumn(meters));
    }

    public BlockMaterialPalette palette() {
        return palette;
    }

    public void apply(TerrainSnapshot snapshot) {
        List<WorldModel.ColumnImport> imports = new ArrayList<>();
        for (TerrainChunk chunk : snapshot.chunks()) {
            // a re-sent column whose ground block did not move keeps the world model's exact surface
            TerrainChunk previous = chunks.put(key(chunk.chunkX(), chunk.chunkZ()), chunk.copy());
            for (int lz = 0; lz < TerrainChunk.SIZE; lz++) {
                for (int lx = 0; lx < TerrainChunk.SIZE; lx++) {
                    int x = chunk.chunkX() * TerrainChunk.SIZE + lx;
                    int z = chunk.chunkZ() * TerrainChunk.SIZE + lz;
                    TerrainColumn column = chunk.get(x, z);
                    if (world.isKnown(x, z)) {
                        sync(x, z, column, UnitTable.UNATTRIBUTED, previous == null ? null : previous.get(x, z));
                    } else {
                        Material cover = palette.knows(column.surface()) && solid(column.surface())
                                ? palette.material(column.surface()) : null;
                        imports.add(new WorldModel.ColumnImport(x, z, world.spec().blockTop(column.groundY()), cover));
                    }
                }
            }
        }
        world.importColumns(imports);
        for (TerrainChunk chunk : snapshot.chunks()) {
            for (int i = 0; i < TerrainChunk.AREA; i++) {
                int x = chunk.chunkX() * TerrainChunk.SIZE + (i & 15);
                int z = chunk.chunkZ() * TerrainChunk.SIZE + (i >> 4);
                syncWater(x, z, chunk.waterY[i]);
            }
        }
    }

    private boolean solid(BlockId block) {
        return palette.material(block).solid();
    }

    /**
     * Mirrors a block-level column change into the world model. When the ground block did not move
     * ({@code previous} has the same {@code groundY}) only the surface block or water changed, so the
     * stacks keep their exact (possibly sub-block) surface: engine subsystems deposit physical
     * thicknesses straight into the world model and only use the block cache for rendering.
     */
    private void sync(int x, int z, TerrainColumn column, int unit, TerrainColumn previous) {
        WorldSpec spec = world.spec();
        double target = spec.blockTop(column.groundY());
        double current = world.surfaceZ(x, z);
        if (previous != null && previous.groundY() == column.groundY() && !Double.isNaN(current)
                && Math.abs(current - target) < spec.metersPerColumn()) {
            syncWater(x, z, column.waterY());
            return;
        }
        if (Double.isNaN(current)) {
            Material cover = palette.knows(column.surface()) && solid(column.surface())
                    ? palette.material(column.surface()) : null;
            world.importColumn(x, z, target, cover);
        } else if (target > current + EPS) {
            Material material = palette.material(column.surface());
            if (!material.solid()) material = MaterialTable.require(spec.surfaceMaterial());
            world.deposit(x, z, target - current, material, unit);
        } else if (target < current - EPS) {
            world.erode(x, z, current - target, false);
        }
        syncWater(x, z, column.waterY());
    }

    private void syncWater(int x, int z, int waterY) {
        world.setWaterZ(x, z, waterY == TerrainColumn.NO_WATER ? Double.NaN : world.spec().blockTop(waterY));
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

    /**
     * Updates a column the engine itself changed (creates the chunk if it is unknown) and mirrors the
     * change into the world model as unattributed material.
     */
    public void setColumn(int x, int z, TerrainColumn column) {
        setColumn(x, z, column, UnitTable.UNATTRIBUTED);
    }

    /** {@link #setColumn} with the stratigraphic unit a raised ground belongs to. */
    public void setColumn(int x, int z, TerrainColumn column, int unit) {
        TerrainChunk chunk = chunks.get(key(x >> 4, z >> 4));
        TerrainColumn previous = chunk == null ? null : chunk.get(x, z);
        if (chunk == null) {
            chunk = new TerrainChunk(x >> 4, z >> 4);
            chunks.put(key(x >> 4, z >> 4), chunk);
        }
        chunk.set(x, z, column);
        sync(x, z, column, unit, previous);
    }

    /**
     * Updates only the block cache of a column, leaving the world model untouched: for subsystems that
     * already wrote the physical change (deposit, erosion, cavity) into the {@link #world()} and now
     * show it as whole blocks. Keeps the water surface.
     */
    public void updateBlockCache(int x, int z, int groundY, BlockId surface) {
        TerrainChunk chunk = chunks.computeIfAbsent(key(x >> 4, z >> 4), k -> new TerrainChunk(x >> 4, z >> 4));
        TerrainColumn current = chunk.get(x, z);
        chunk.set(x, z, new TerrainColumn(groundY, current.waterY(), surface));
    }

    /**
     * The ground block that shows a world-model surface elevation {@code surfaceZ}: the highest block
     * whose top lies at or below it, counting a block once more than half of it is filled.
     */
    public int blockForSurface(double surfaceZ) {
        return (int) Math.floor(surfaceZ / world.spec().metersPerColumn() + 0.5) - 1;
    }

    public void setGround(int x, int z, int groundY, BlockId surface) {
        setGround(x, z, groundY, surface, UnitTable.UNATTRIBUTED);
    }

    public void setGround(int x, int z, int groundY, BlockId surface, int unit) {
        TerrainColumn current = column(x, z);
        int waterY = current == null ? TerrainColumn.NO_WATER : current.waterY();
        setColumn(x, z, new TerrainColumn(groundY, waterY, surface), unit);
    }

    public int chunkCount() {
        return chunks.size();
    }

    /** Read-only views of every known chunk, in no particular order. */
    public List<TerrainChunkView> chunks() {
        return new ArrayList<>(chunks.values());
    }

    // ── Persistence: block cache (one field chunk per terrain chunk, surface ids through a palette)
    //    plus the world model (stacks per 32×32 tile) ──

    private static final int SCHEMA = 1;

    @Override
    public void saveState(StateWriter out) {
        StateWriter.Field field = out.field("columns", SCHEMA);
        Map<BlockId, Integer> ids = new LinkedHashMap<>();
        List<TerrainChunk> sorted = new ArrayList<>(chunks.values());
        sorted.sort(java.util.Comparator.comparingLong(c -> pack(c.chunkX(), c.chunkZ())));
        for (TerrainChunk chunk : sorted) {
            int[] surface = new int[TerrainChunk.AREA];
            for (int i = 0; i < TerrainChunk.AREA; i++) {
                surface[i] = ids.computeIfAbsent(chunk.surface[i], id -> ids.size());
            }
            field.put(chunk.chunkX(), chunk.chunkZ(), new FieldChunk()
                    .ints("groundY", chunk.groundY.clone())
                    .ints("waterY", chunk.waterY.clone())
                    .ints("surface", surface));
        }
        JsonArray palette = new JsonArray();
        ids.keySet().forEach(id -> palette.add(id.toString()));
        out.json().add("palette", palette);
        world.save(out);
    }

    @Override
    public void loadState(StateReader in) {
        chunks.clear();
        world.load(in);
        StateReader.Field field = in.field("columns");
        if (field == null) return;
        List<BlockId> ids = new ArrayList<>();
        for (JsonElement e : in.json().getAsJsonArray("palette")) ids.add(BlockId.parse(e.getAsString()));
        for (StateReader.Entry entry : field.chunks()) {
            TerrainChunk chunk = new TerrainChunk(entry.chunkX(), entry.chunkZ());
            System.arraycopy(entry.data().ints("groundY"), 0, chunk.groundY, 0, TerrainChunk.AREA);
            System.arraycopy(entry.data().ints("waterY"), 0, chunk.waterY, 0, TerrainChunk.AREA);
            int[] surface = entry.data().ints("surface");
            for (int i = 0; i < TerrainChunk.AREA; i++) chunk.surface[i] = ids.get(surface[i]);
            chunks.put(key(entry.chunkX(), entry.chunkZ()), chunk);
        }
    }

    /** Packed chunk coordinates; the persistence order of chunks. */
    private static long pack(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xffffffffL);
    }

    /**
     * Map key of a chunk: the packed coordinates through a bijective mixer, so that {@code Long.hashCode}
     * (which folds the plain packed value to {@code chunkX ^ chunkZ}) does not collide for whole
     * diagonals of chunks on km-wide worlds.
     */
    private static long key(int chunkX, int chunkZ) {
        long z = pack(chunkX, chunkZ);
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }
}
