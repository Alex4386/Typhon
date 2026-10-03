package me.alex4386.typhon.engine.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BlockIdTest {
    @Test
    void parsesNamespacedAndBareIds() {
        assertEquals(BlockId.minecraft("basalt"), BlockId.parse("basalt"));
        assertEquals(new BlockId("typhon", "ash_layer"), BlockId.parse("typhon:ash_layer"));
        assertEquals("minecraft:potent_sulfur", BlockId.minecraft("potent_sulfur").toString());
    }

    @Test
    void rejectsInvalidIds() {
        assertThrows(IllegalArgumentException.class, () -> BlockId.parse("Minecraft:Stone"));
        assertThrows(IllegalArgumentException.class, () -> BlockId.parse("minecraft:"));
    }
}
