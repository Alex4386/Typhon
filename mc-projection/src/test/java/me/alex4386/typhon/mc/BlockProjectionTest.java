package me.alex4386.typhon.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.BlockMaterialPalette;
import me.alex4386.typhon.engine.world.BlockState;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.UnitTable;
import me.alex4386.typhon.engine.world.WorldModel;
import org.junit.jupiter.api.Test;

class BlockProjectionTest {
    static final BlockId STONE = BlockId.minecraft("stone");
    static final BlockId BASALT = BlockId.minecraft("basalt");
    static final int GROUND = 63;

    /** A flat host world of stone, ground block 63 (surface at 64 L), {@code n}×{@code n} columns. */
    static TerrainModel flat(int n) {
        TerrainModel terrain = new TerrainModel();
        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) terrain.setColumn(x, z, TerrainColumn.dry(GROUND, STONE));
        }
        return terrain;
    }

    static double l(WorldModel world) {
        return world.spec().metersPerColumn();
    }

    /** Block state per position after applying {@code changes} in order (a host's world, sparse). */
    static Map<BlockPos, BlockState> apply(Map<BlockPos, BlockState> blocks, List<BlockChange> changes) {
        for (BlockChange c : changes) blocks.put(c.pos(), c.to());
        return blocks;
    }

    static final class Lava implements BlockProjection.LavaSource {
        final Map<Long, double[]> cells = new HashMap<>();

        void set(int x, int z, double thicknessM, double temperatureC) {
            cells.put(((long) x << 32) | (z & 0xffffffffL), new double[] {thicknessM, temperatureC});
        }

        @Override public double thicknessM(int x, int z) {
            double[] c = cells.get(((long) x << 32) | (z & 0xffffffffL));
            return c == null ? 0 : c[0];
        }

        @Override public double temperatureC(int x, int z) {
            double[] c = cells.get(((long) x << 32) | (z & 0xffffffffL));
            return c == null ? 20 : c[1];
        }
    }

    @Test
    void untouchedHostGroundIsLeftAlone() {
        WorldModel world = flat(4).world();
        BlockProjection p = BlockProjection.simple(world);
        assertEquals(GROUND, p.project(0, 0).groundY());
        assertTrue(p.update(0, 0, GROUND, STONE).isEmpty(), "stone is not re-skinned as andesite");
        assertTrue(p.update(0, 0, GROUND, STONE).isEmpty());
        assertNull(p.project(10, 10), "unknown columns project nothing");
    }

    @Test
    void halfBlockQuantisationCountsABlockOnceMoreThanHalfFilled() {
        WorldModel world = flat(2).world();
        double l = l(world);
        BlockProjection p = BlockProjection.simple(world);
        world.deposit(0, 0, 0.4 * l, MaterialTable.BASALT, UnitTable.UNATTRIBUTED);
        assertEquals(GROUND, p.project(0, 0).groundY());
        world.deposit(0, 0, 0.2 * l, MaterialTable.BASALT, UnitTable.UNATTRIBUTED);
        assertEquals(GROUND + 1, p.project(0, 0).groundY());
        assertEquals(BASALT, p.project(0, 0).surface());
    }

    @Test
    void layersPolicyShowsThePartialTopInEighths() {
        WorldModel world = flat(2).world();
        double l = l(world);
        BlockState layer = BlockState.minecraft("snow");
        BlockProjection p = new BlockProjection(world, BlockMaterialPalette.minecraft(), QuantizationPolicy.LAYERS,
                (surface, eighths) -> layer.with("layers", eighths), null, 900);
        world.deposit(0, 0, 0.375 * l, MaterialTable.ASH, UnitTable.UNATTRIBUTED);
        BlockProjection.ProjectedColumn c = p.project(0, 0);
        assertEquals(GROUND, c.groundY());
        assertEquals(3, c.partialEighths());
        List<BlockChange> changes = p.update(0, 0, GROUND, STONE);
        assertEquals(List.of(BlockChange.replace(new BlockPos(0, GROUND + 1, 0), BlockId.AIR, layer.with("layers", 3))),
                changes);
    }

    @Test
    void raisedGroundIsFilledWithItsMaterialsAndLoweredGroundOpensUp() {
        WorldModel world = flat(2).world();
        double l = l(world);
        BlockProjection p = BlockProjection.simple(world);
        world.deposit(0, 0, 2 * l, MaterialTable.TUFF, UnitTable.UNATTRIBUTED);
        world.deposit(0, 0, 1 * l, MaterialTable.BASALT, UnitTable.UNATTRIBUTED);
        Map<BlockPos, BlockState> blocks = apply(new HashMap<>(), p.update(0, 0, GROUND, STONE));
        BlockId tuff = BlockId.minecraft("tuff");
        assertEquals(BlockState.of(tuff), blocks.get(new BlockPos(0, 64, 0)));
        assertEquals(BlockState.of(tuff), blocks.get(new BlockPos(0, 65, 0)));
        assertEquals(BlockState.of(BASALT), blocks.get(new BlockPos(0, 66, 0)));
        assertEquals(3, blocks.size(), "nothing below the old ground is touched");

        world.erode(0, 0, 2 * l, false);
        List<BlockChange> down = p.update(0, 0, GROUND, STONE);
        assertTrue(down.contains(BlockChange.replace(new BlockPos(0, 66, 0), BASALT, BlockState.AIR)),
                "the shown surface is compare-and-set: " + down);
        apply(blocks, down);
        assertEquals(BlockState.AIR, blocks.get(new BlockPos(0, 66, 0)));
        assertEquals(BlockState.AIR, blocks.get(new BlockPos(0, 65, 0)));
        assertEquals(BlockState.of(tuff), blocks.get(new BlockPos(0, 64, 0)));
        assertEquals(GROUND + 1, p.shown(0, 0).groundY());
        assertTrue(down.stream().allMatch(c -> c.pos().y() >= 64));
    }

    @Test
    void upliftLiftsTheProjectedGround() {
        WorldModel world = flat(2).world();
        double l = l(world);
        BlockProjection p = BlockProjection.simple(world);
        world.setUplift(0, 0, 0.7 * l);
        assertEquals(GROUND + 1, p.project(0, 0).groundY());
        world.setUplift(0, 0, 0.3 * l);
        assertEquals(GROUND, p.project(0, 0).groundY());
    }

    @Test
    void waterStandsAboveTheGround() {
        WorldModel world = flat(2).world();
        double l = l(world);
        BlockProjection p = BlockProjection.simple(world);
        world.erode(0, 0, 2 * l, false);
        world.setWaterZ(0, 0, 64 * l);
        Map<BlockPos, BlockState> blocks = apply(new HashMap<>(), p.update(0, 0, GROUND, STONE));
        assertEquals(BlockState.of(BlockProjection.WATER), blocks.get(new BlockPos(0, 63, 0)));
        assertEquals(BlockState.of(BlockProjection.WATER), blocks.get(new BlockPos(0, 62, 0)));
        assertEquals(GROUND - 2, p.shown(0, 0).groundY());
    }

    @Test
    void lavaShowsAsLevelsWithACrustWhenCoolAndLeavesNothingWhenItDrains() {
        WorldModel world = flat(2).world();
        double l = l(world);
        Lava lava = new Lava();
        BlockProjection p = new BlockProjection(world, BlockMaterialPalette.minecraft(), QuantizationPolicy.HALF_BLOCK,
                BlockProjection.PartialBlocks.NONE, lava, 900);
        Map<BlockPos, BlockState> blocks = new HashMap<>();

        lava.set(0, 0, 1.5 * l, 1150);
        apply(blocks, p.update(0, 0, GROUND, STONE));
        assertEquals(BlockState.of(BlockProjection.LAVA), blocks.get(new BlockPos(0, 64, 0)));
        BlockState top = blocks.get(new BlockPos(0, 65, 0));
        assertEquals("lava", top.id().path());
        assertTrue(!"0".equals(top.property("level")), "a half-filled top block is a partial level: " + top);

        lava.set(0, 0, 1.5 * l, 700);
        apply(blocks, p.update(0, 0, GROUND, STONE));
        assertEquals(BlockState.of(BlockProjection.MAGMA), blocks.get(new BlockPos(0, 65, 0)), "crusted top");

        lava.set(0, 0, 0, 20);
        apply(blocks, p.update(0, 0, GROUND, STONE));
        assertEquals(BlockState.AIR, blocks.get(new BlockPos(0, 64, 0)));
        assertEquals(BlockState.AIR, blocks.get(new BlockPos(0, 65, 0)));
    }

    @Test
    void surfaceHintRetintsTheSurfaceInPlace() {
        TerrainModel terrain = flat(2);
        WorldModel world = terrain.world();
        BlockProjection p = BlockProjection.simple(world).withSurfaceHint((x, z, groundY) -> {
            TerrainColumn c = terrain.column(x, z);
            return c != null && c.groundY() == groundY ? c.surface() : null;
        });
        assertTrue(p.update(0, 0, GROUND, STONE).isEmpty());
        BlockId sulfur = BlockId.minecraft("yellow_concrete");
        terrain.updateBlockCache(0, 0, GROUND, sulfur);
        assertEquals(List.of(BlockChange.replace(new BlockPos(0, GROUND, 0), STONE, BlockState.of(sulfur))),
                p.update(0, 0, GROUND, STONE));
    }

    @Test
    void onlyChangedTilesAreVisited() {
        WorldModel world = flat(40).world();
        double l = l(world);
        BlockProjection p = BlockProjection.simple(world);
        BlockProjection.HostGround host = new BlockProjection.HostGround() {
            @Override public int groundY(int x, int z) { return GROUND; }
            @Override public BlockId surface(int x, int z) { return STONE; }
        };
        assertTrue(p.updateChanged(null, host).isEmpty(), "the host's own ground needs no blocks");
        world.deposit(35, 3, 1.2 * l, MaterialTable.BASALT, UnitTable.UNATTRIBUTED);
        List<BlockChange> changes = p.updateChanged(null, host);
        assertEquals(List.of(BlockChange.replace(new BlockPos(35, 64, 3), BlockId.AIR, BlockState.of(BASALT))), changes);
        assertTrue(p.updateChanged(null, host).isEmpty(), "nothing changed since");
    }
}
