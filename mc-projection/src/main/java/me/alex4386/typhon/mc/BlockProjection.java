package me.alex4386.typhon.mc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import me.alex4386.typhon.engine.world.ColumnStacks;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.WorldModel;

/**
 * Shows the engine's continuous world model as blocks for a block host (Minecraft).
 *
 * <p>The engine holds metres: layer stacks with their materials, the uplift field, water and lava depths.
 * This class quantises a column ({@link QuantizationPolicy}), maps materials to blocks ({@link
 * BlockMaterialPalette}) and diffs against what it showed last, producing compare-and-set {@link
 * BlockChange}s. It is the only place where the volcano becomes blocks: nothing in the engine deposits,
 * erodes or reports in whole blocks.
 *
 * <p>The host's own blocks are left alone wherever the volcano did not change them: blocks below the
 * lower of the old and new ground are never rewritten, and a surface block only when its column's ground
 * moved or the surface block the projection computes for it changed.
 */
public final class BlockProjection {
    /** Lava on the surface, if the host shows it (e.g. the engine's {@code LavaFlow}). */
    public interface LavaSource {
        double thicknessM(int x, int z);

        double temperatureC(int x, int z);
    }

    /** The block a partial top of {@code eighths}/8 of a block shows as (null: none, the top stays open). */
    public interface PartialBlocks {
        BlockState partial(BlockId surface, int eighths);

        PartialBlocks NONE = (surface, eighths) -> null;
    }

    /**
     * The block a host already shows as a column's surface at {@code groundY} (null: none). Surface facies
     * the engine keeps only as tints (sulfur crusts, ash covers, veneers, hot bombs) come through here: a
     * host wires it to the engine's terrain block cache.
     */
    public interface SurfaceHint {
        BlockId surface(int x, int z, int groundY);

        SurfaceHint NONE = (x, z, groundY) -> null;
    }

    /** The host's own ground, for columns the projection has not shown yet. */
    public interface HostGround {
        int groundY(int x, int z);

        /** The host's surface block ({@code null}: unknown, left as it is unless the ground moves). */
        BlockId surface(int x, int z);
    }

    /**
     * The surface of one column as blocks.
     *
     * @param groundY the top solid block
     * @param partialEighths a partial block on top of it, in eighths (0: none)
     * @param surface the block at {@code groundY} ({@code null}: unknown, the host's own)
     * @param lavaTopY the top lava block, {@link #NONE} without lava
     * @param lavaLevel Minecraft lava level of the top lava block (0 full … 7 thinnest)
     * @param lavaCrust the top of the lava is a crust (magma block) rather than open melt
     * @param waterTopY the top water block, {@link #NONE} when dry
     */
    public record ProjectedColumn(int groundY, int partialEighths, BlockId surface, int lavaTopY, int lavaLevel,
            boolean lavaCrust, int waterTopY) {
        /** The highest block this column shows (its ground, a partial block or fluids above). */
        public int top() {
            return Math.max(groundY + (partialEighths > 0 ? 1 : 0), Math.max(lavaTopY, waterTopY));
        }
    }

    public static final int NONE = Integer.MIN_VALUE;
    public static final BlockId WATER = BlockId.minecraft("water");
    public static final BlockId LAVA = BlockId.minecraft("lava");
    public static final BlockId MAGMA = BlockId.minecraft("magma_block");

    private final WorldModel world;
    private final BlockMaterialPalette palette;
    private final QuantizationPolicy policy;
    private final PartialBlocks partials;
    private final LavaSource lava;
    /** Below this surface temperature (°C) lava shows a magma-block crust instead of open lava. */
    private final double crustBelowC;
    private SurfaceHint hint = SurfaceHint.NONE;
    private final Map<Long, ProjectedColumn> shown = new HashMap<>();
    /**
     * The surface block each column actually shows: the host's own until the projection replaces it (the
     * projected surface in {@link #shown} only detects when the projection's surface changes).
     */
    private final Map<Long, BlockId> shownSurface = new HashMap<>();
    private final Map<Long, Long> shownTileVersions = new HashMap<>();

    public BlockProjection(WorldModel world, BlockMaterialPalette palette, QuantizationPolicy policy, PartialBlocks partials,
            LavaSource lava, double crustBelowC) {
        this.world = Objects.requireNonNull(world, "world");
        this.palette = Objects.requireNonNull(palette, "palette");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.partials = Objects.requireNonNull(partials, "partials");
        this.lava = lava;
        this.crustBelowC = crustBelowC;
    }

    /** Whole blocks, the Minecraft palette, no partial tops, no lava. */
    public static BlockProjection simple(WorldModel world) {
        return new BlockProjection(world, BlockMaterialPalette.minecraft(), QuantizationPolicy.HALF_BLOCK, PartialBlocks.NONE,
                null, 900);
    }

    /** Uses {@code hint} for the surface block where it names one at the projected ground. */
    public BlockProjection withSurfaceHint(SurfaceHint hint) {
        this.hint = Objects.requireNonNull(hint, "hint");
        return this;
    }

    private double blockSize() {
        return world.spec().metersPerColumn();
    }

    /** The column's surface as blocks now, or {@code null} if the world model does not know it. */
    public ProjectedColumn project(int x, int z) {
        if (!world.isKnown(x, z)) return null;
        double l = blockSize();
        double surface = world.surfaceZ(x, z);
        QuantizationPolicy.Level level = policy.quantize(surface, l);
        BlockId surfaceBlock = hint.surface(x, z, level.groundY());
        if (surfaceBlock == null) {
            // the material at the top of the ground block (a partial top above it is shown by PartialBlocks)
            double groundTop = Math.min(surface, (level.groundY() + 1) * l);
            surfaceBlock = materialBlock(x, z, groundTop - 1e-3 * l);
        }

        int lavaTop = NONE;
        int lavaLevel = 0;
        boolean crust = false;
        if (lava != null) {
            double h = lava.thicknessM(x, z);
            if (h > 0) {
                double blocks = (surface + h) / l;
                int top = (int) Math.ceil(blocks - 1e-9) - 1;
                if (top > level.groundY()) {
                    lavaTop = top;
                    double filled = blocks - top; // of the top block, (0, 1]
                    lavaLevel = (int) Math.max(0, Math.min(7, Math.round((1 - filled) * 7)));
                    crust = lava.temperatureC(x, z) < crustBelowC;
                }
            }
        }
        int waterTop = NONE;
        double water = world.waterZ(x, z);
        if (Double.isFinite(water) && water > surface) {
            int w = (int) Math.floor(water / l + 0.5) - 1;
            if (w > level.groundY()) waterTop = w;
        }
        return new ProjectedColumn(level.groundY(), level.partialEighths(), surfaceBlock, lavaTop, lavaLevel, crust, waterTop);
    }

    /** The block of the material at {@code elevation} (world-model metres, uplift included) in column (x, z). */
    private BlockId materialBlock(int x, int z, double elevation) {
        Material m = world.materialAt(x, z, elevation);
        BlockId id = m != null ? palette.block(m) : null;
        return id != null ? id : BlockId.minecraft("stone");
    }

    /** What a column shows above its ground at {@code y} (air, water, lava or the partial top). */
    private BlockState above(ProjectedColumn c, int y) {
        if (c.lavaTopY() != NONE && y <= c.lavaTopY()) {
            if (y < c.lavaTopY()) return BlockState.of(LAVA);
            return c.lavaCrust() ? BlockState.of(MAGMA) : BlockState.of(LAVA).with("level", c.lavaLevel());
        }
        if (y == c.groundY() + 1 && c.partialEighths() > 0) {
            BlockState p = partials.partial(c.surface(), c.partialEighths());
            if (p != null) return p;
        }
        if (c.waterTopY() != NONE && y <= c.waterTopY()) return BlockState.of(WATER);
        return BlockState.AIR;
    }

    /**
     * Updates one column, returning the block changes from what it showed last to what it shows now. The
     * first time, the host's own ground ({@code hostGroundY}, surface {@code hostSurface}, null if unknown)
     * stands for what was shown.
     */
    public List<BlockChange> update(int x, int z, int hostGroundY, BlockId hostSurface) {
        List<BlockChange> out = new ArrayList<>();
        ProjectedColumn now = project(x, z);
        if (now == null) return out;
        long key = columnKey(x, z);
        ProjectedColumn previous = shown.get(key);
        ProjectedColumn before = previous != null ? previous
                : new ProjectedColumn(hostGroundY, 0, hostSurface, NONE, 0, false, NONE);
        double l = blockSize();
        int bg = before.groundY();
        int ng = now.groundY();
        int top = Math.max(before.top(), now.top());
        BlockId surfaceShown = previous != null ? shownSurface.getOrDefault(key, before.surface()) : hostSurface;
        BlockId surfaceAfter = surfaceShown;
        for (int y = Math.min(bg, ng); y <= top; y++) {
            BlockId expected = y < bg ? null : y == bg ? surfaceShown : above(before, y).id();
            BlockState next;
            if (y < ng) {
                if (y <= bg) continue; // buried ground stays as the host shows it
                next = BlockState.of(materialBlock(x, z, (y + 0.5) * l));
            } else if (y == ng) {
                boolean moved = bg != ng;
                // the host's own surface block is kept until the projection's own surface changes
                boolean retinted = previous != null && !Objects.equals(previous.surface(), now.surface());
                if (!moved && !retinted) continue;
                next = BlockState.of(now.surface());
                surfaceAfter = now.surface();
            } else {
                next = above(now, y);
                if (y > bg && next.equals(above(before, y))) continue;
            }
            out.add(BlockChange.replace(new BlockPos(x, y, z), expected, next));
        }
        shown.put(key, now);
        if (surfaceAfter != null) shownSurface.put(key, surfaceAfter);
        return out;
    }

    /**
     * Updates every known column of every world-model tile whose version changed since the last call, plus
     * {@code alsoTiles} (packed like {@link ColumnStacks#tileKeys()}: e.g. tiles with moving lava, which the
     * world model's versions do not see). {@code host} gives the ground of columns shown for the first time.
     */
    public List<BlockChange> updateChanged(Iterable<Long> alsoTiles, HostGround host) {
        Set<Long> tiles = new TreeSet<>();
        for (long tile : world.stacks().tileKeys()) {
            long v = world.stacks().tileVersion(ColumnStacks.keyTileX(tile), ColumnStacks.keyTileZ(tile));
            Long seen = shownTileVersions.put(tile, v);
            if (seen == null || seen != v) tiles.add(tile);
        }
        if (alsoTiles != null) for (long t : alsoTiles) tiles.add(t);
        List<BlockChange> out = new ArrayList<>();
        int size = ColumnStacks.TILE;
        for (long tile : tiles) {
            int x0 = ColumnStacks.keyTileX(tile) * size;
            int z0 = ColumnStacks.keyTileZ(tile) * size;
            for (int dz = 0; dz < size; dz++) {
                for (int dx = 0; dx < size; dx++) {
                    int x = x0 + dx;
                    int z = z0 + dz;
                    if (!world.isKnown(x, z)) continue;
                    out.addAll(update(x, z, host.groundY(x, z), host.surface(x, z)));
                }
            }
        }
        return out;
    }

    /** Packs tile coordinates like {@link ColumnStacks#tileKeys()}. */
    public static long tileKey(int tileX, int tileZ) {
        return ((long) tileX << 32) | (tileZ & 0xffffffffL);
    }

    /** What was last shown for a column (null if never). */
    public ProjectedColumn shown(int x, int z) {
        return shown.get(columnKey(x, z));
    }

    /** Forgets what was shown (e.g. after the host reloaded its chunks): the next update starts over. */
    public void reset() {
        shown.clear();
        shownSurface.clear();
        shownTileVersions.clear();
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}
