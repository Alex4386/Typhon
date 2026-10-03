package me.alex4386.typhon.simulator.world;

import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.BlockState;
import me.alex4386.typhon.simulator.terrain.ColumnGrid;

/**
 * A stand-in for a Minecraft world: the initial terrain as columns plus every block the engine has
 * written, applied with the same compare-and-set rule a host uses.
 *
 * <p>Unedited blocks are implied by the column: the surface block at ground level, stone below it,
 * water up to the water level, air above. Edits are stored sparsely per column.
 */
public final class VoxelWorld {
    public static final BlockId STONE = BlockId.minecraft("stone");
    public static final BlockId WATER = BlockId.minecraft("water");
    public static final BlockId LAVA = BlockId.minecraft("lava");

    private static final Set<BlockId> NON_SOLID = Set.of(
            BlockId.AIR, WATER, LAVA, BlockId.minecraft("fire"), BlockId.minecraft("short_grass"),
            BlockId.minecraft("tall_grass"), BlockId.minecraft("snow"));

    private final ColumnGrid base;
    private final Map<Long, NavigableMap<Integer, BlockState>> edits = new HashMap<>();
    private long applied;
    private long conflicts;
    private long outside;

    public VoxelWorld(ColumnGrid initial) {
        this.base = initial.copy();
    }

    public ColumnGrid base() {
        return base;
    }

    public long appliedChanges() { return applied; }
    public long conflicts() { return conflicts; }
    /** Changes outside the simulated area (ignored, as a host would ignore unloaded chunks). */
    public long outsideChanges() { return outside; }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    /** The block at a position, edited or implied by the initial terrain. */
    public BlockState get(int x, int y, int z) {
        NavigableMap<Integer, BlockState> column = edits.get(key(x, z));
        if (column != null) {
            BlockState s = column.get(y);
            if (s != null) return s;
        }
        return BlockState.of(implied(x, y, z));
    }

    private BlockId implied(int x, int y, int z) {
        if (!base.contains(x, z)) return BlockId.AIR;
        TerrainColumn c = base.column(x, z);
        if (y < c.groundY()) return STONE;
        if (y == c.groundY()) return c.surface();
        if (c.waterY() != TerrainColumn.NO_WATER && y <= c.waterY()) return WATER;
        return BlockId.AIR;
    }

    /** Applies a change; returns false on a compare-and-set conflict or outside the area. */
    public boolean apply(BlockChange change) {
        int x = change.pos().x();
        int y = change.pos().y();
        int z = change.pos().z();
        if (!base.contains(x, z)) {
            outside++;
            return false;
        }
        if (change.expected() != null && !get(x, y, z).id().equals(change.expected())) {
            conflicts++;
            return false;
        }
        NavigableMap<Integer, BlockState> column = edits.computeIfAbsent(key(x, z), k -> new TreeMap<>());
        if (BlockState.of(implied(x, y, z)).equals(change.to())) {
            column.remove(y);
        } else {
            column.put(y, change.to());
        }
        applied++;
        return true;
    }

    public static boolean isSolid(BlockId id) {
        return !NON_SOLID.contains(id);
    }

    /** Highest solid block in the column (edits included). */
    public int topSolidY(int x, int z) {
        int ground = base.ground(x, z);
        NavigableMap<Integer, BlockState> column = edits.get(key(x, z));
        if (column == null || column.isEmpty()) return ground;
        int y = Math.max(ground, column.lastKey());
        for (; y > ground - 64; y--) {
            if (isSolid(get(x, y, z).id())) return y;
        }
        return y;
    }

    /** Highest non-air block (lava and water included). */
    public int topY(int x, int z) {
        TerrainColumn c = base.column(x, z);
        int top = Math.max(c.groundY(), c.waterY() == TerrainColumn.NO_WATER ? Integer.MIN_VALUE : c.waterY());
        NavigableMap<Integer, BlockState> column = edits.get(key(x, z));
        if (column == null || column.isEmpty()) return top;
        int y = Math.max(top, column.lastKey());
        for (; y > c.groundY() - 64; y--) {
            if (!get(x, y, z).id().equals(BlockId.AIR)) return y;
        }
        return y;
    }

    public BlockState topBlock(int x, int z) {
        return get(x, topY(x, z), z);
    }

    /** Edited blocks in a column, bottom to top (read-only view). */
    public NavigableMap<Integer, BlockState> edits(int x, int z) {
        NavigableMap<Integer, BlockState> column = edits.get(key(x, z));
        return column == null ? new TreeMap<>() : java.util.Collections.unmodifiableNavigableMap(column);
    }

    /** Number of edited blocks currently holding {@code id}. */
    public long count(BlockId id) {
        long n = 0;
        for (NavigableMap<Integer, BlockState> column : edits.values()) {
            for (BlockState s : column.values()) if (s.id().equals(id)) n++;
        }
        return n;
    }

    /** Blocks per id over all edits. */
    public Map<BlockId, Long> editCounts() {
        Map<BlockId, Long> counts = new TreeMap<>(java.util.Comparator.comparing(BlockId::toString));
        for (NavigableMap<Integer, BlockState> column : edits.values()) {
            for (BlockState s : column.values()) counts.merge(s.id(), 1L, Long::sum);
        }
        return counts;
    }
}
