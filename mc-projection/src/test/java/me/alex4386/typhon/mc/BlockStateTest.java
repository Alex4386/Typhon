package me.alex4386.typhon.mc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BlockStateTest {
    @Test
    void roundTripsThroughText() {
        BlockState state = BlockState.minecraft("basalt").with("axis", "x");
        assertEquals("minecraft:basalt[axis=x]", state.toString());
        assertEquals(state, BlockState.parse("minecraft:basalt[axis=x]"));
        assertEquals(BlockState.minecraft("stone"), BlockState.parse("stone"));
    }

    @Test
    void propertiesAreSorted() {
        BlockState a = BlockState.minecraft("lava").with("level", 3).with("b", "1");
        BlockState b = BlockState.parse("minecraft:lava[level=3,b=1]");
        assertEquals(a, b);
        assertEquals("minecraft:lava[b=1,level=3]", a.toString());
    }
}
