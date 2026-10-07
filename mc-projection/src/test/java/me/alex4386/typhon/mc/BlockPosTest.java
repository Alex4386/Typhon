package me.alex4386.typhon.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BlockPosTest {
    @Test
    void packRoundTripsAcrossRange() {
        int[] xz = {0, 1, -1, 15, -16, 30_000_000, -30_000_000, BlockPos.MIN_XZ, BlockPos.MAX_XZ};
        int[] ys = {0, 1, -1, -64, 319, BlockPos.MIN_Y, BlockPos.MAX_Y};
        for (int x : xz) {
            for (int y : ys) {
                for (int z : xz) {
                    BlockPos pos = new BlockPos(x, y, z);
                    assertEquals(pos, BlockPos.unpack(pos.pack()), pos::toString);
                }
            }
        }
    }

    @Test
    void neighboursPackToDistinctKeys() {
        BlockPos origin = new BlockPos(-1, -1, -1);
        assertNotEquals(origin.pack(), origin.offset(1, 0, 0).pack());
        assertNotEquals(origin.pack(), origin.offset(0, 1, 0).pack());
        assertNotEquals(origin.pack(), origin.offset(0, 0, 1).pack());
    }

    @Test
    void rejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> BlockPos.pack(0, BlockPos.MAX_Y + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> BlockPos.pack(BlockPos.MAX_XZ + 1, 0, 0));
    }

    @Test
    void chunkCoordinatesFloorNegatives() {
        BlockPos pos = new BlockPos(-1, 0, 16);
        assertEquals(-1, pos.chunkX());
        assertEquals(1, pos.chunkZ());
    }
}
