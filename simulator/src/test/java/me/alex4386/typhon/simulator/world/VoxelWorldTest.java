package me.alex4386.typhon.simulator.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.BlockState;
import me.alex4386.typhon.simulator.terrain.ColumnGrid;
import org.junit.jupiter.api.Test;

class VoxelWorldTest {
    private static final BlockId GRASS = BlockId.minecraft("grass_block");
    private static final BlockId BASALT = BlockId.minecraft("basalt");

    private static ColumnGrid grid() {
        ColumnGrid g = new ColumnGrid(0, 0, 16);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) g.set(x, z, 64, x < 4 ? 66 : Integer.MIN_VALUE, GRASS);
        return g;
    }

    @Test
    void impliesBlocksFromColumns() {
        VoxelWorld world = new VoxelWorld(grid());
        assertEquals(VoxelWorld.STONE, world.get(8, 60, 8).id());
        assertEquals(GRASS, world.get(8, 64, 8).id());
        assertEquals(BlockId.AIR, world.get(8, 65, 8).id());
        assertEquals(VoxelWorld.WATER, world.get(1, 66, 8).id());
    }

    @Test
    void appliesCompareAndSetLikeAHost() {
        VoxelWorld world = new VoxelWorld(grid());
        assertTrue(world.apply(BlockChange.replace(new BlockPos(8, 64, 8), GRASS, BASALT)));
        assertFalse(world.apply(BlockChange.replace(new BlockPos(8, 64, 8), GRASS, BASALT)), "stale expectation");
        assertEquals(1, world.conflicts());
        assertTrue(world.apply(BlockChange.set(new BlockPos(8, 65, 8), BlockState.minecraft("lava").with("level", 0))));
        assertEquals(VoxelWorld.LAVA, world.topBlock(8, 8).id());
        assertEquals(64, world.topSolidY(8, 8), "lava is not solid");
        assertTrue(world.apply(BlockChange.set(new BlockPos(8, 65, 8), BASALT)));
        assertEquals(65, world.topSolidY(8, 8));
        assertEquals(2, world.count(BASALT));
        assertFalse(world.apply(BlockChange.set(new BlockPos(99, 65, 8), BASALT)));
        assertEquals(1, world.outsideChanges());
    }

    @Test
    void restoringTheOriginalBlockDropsTheEdit() {
        VoxelWorld world = new VoxelWorld(grid());
        world.apply(BlockChange.set(new BlockPos(2, 65, 2), BlockId.AIR));
        world.apply(BlockChange.set(new BlockPos(2, 65, 2), VoxelWorld.WATER));
        assertTrue(world.edits(2, 2).isEmpty());
        assertEquals(66, world.topY(2, 2));
    }
}
