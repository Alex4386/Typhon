package me.alex4386.typhon.engine.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.world.BlockId;
import org.junit.jupiter.api.Test;

class OutboxTest {
    private static final BlockId STONE = BlockId.minecraft("stone");
    private static final BlockId LAVA = BlockId.minecraft("lava");
    private static final BlockId BASALT = BlockId.minecraft("basalt");

    @Test
    void coalescesChangesToSamePosition() {
        Outbox outbox = new Outbox();
        BlockPos pos = new BlockPos(1, 64, 1);
        outbox.setBlock(BlockChange.replace(pos, STONE, LAVA));
        outbox.setBlock(BlockChange.replace(pos, LAVA, BASALT));

        List<BlockChange> changes = outbox.drain(0).blockChanges();
        assertEquals(List.of(BlockChange.replace(pos, STONE, BASALT)), changes);
    }

    @Test
    void preservesFirstWriteOrderAndClearsOnDrain() {
        Outbox outbox = new Outbox();
        BlockPos a = new BlockPos(0, 0, 0);
        BlockPos b = new BlockPos(1, 0, 0);
        outbox.setBlock(BlockChange.set(a, LAVA));
        outbox.setBlock(BlockChange.set(b, LAVA));
        outbox.setBlock(BlockChange.set(a, BASALT));

        EngineFrame frame = outbox.drain(3);
        assertEquals(3, frame.tick());
        assertEquals(List.of(BlockChange.set(a, BASALT), BlockChange.set(b, LAVA)), frame.blockChanges());
        assertTrue(outbox.drain(4).isEmpty());
    }
}
