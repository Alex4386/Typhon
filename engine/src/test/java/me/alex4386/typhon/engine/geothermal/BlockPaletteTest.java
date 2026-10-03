package me.alex4386.typhon.engine.geothermal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Set;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.BlockState;
import org.junit.jupiter.api.Test;

class BlockPaletteTest {
    @Test
    void unrestrictedReturnsPreferred() {
        BlockPalette palette = GeothermalBlocks.installFallbacks(BlockPalette.unrestricted());
        assertEquals(GeothermalBlocks.SULFUR, palette.resolve(GeothermalBlocks.SULFUR));
    }

    @Test
    void walksFallbackChainInOrder() {
        BlockPalette palette = GeothermalBlocks.defaultPalette(Set.of(GeothermalBlocks.SANDSTONE, GeothermalBlocks.YELLOW_TERRACOTTA));
        assertEquals(GeothermalBlocks.YELLOW_TERRACOTTA, palette.resolve(GeothermalBlocks.SULFUR));

        BlockPalette older = GeothermalBlocks.defaultPalette(Set.of(GeothermalBlocks.SANDSTONE));
        assertEquals(GeothermalBlocks.SANDSTONE, older.resolve(GeothermalBlocks.SULFUR));
    }

    @Test
    void unsupportedChainResolvesToNull() {
        BlockPalette palette = GeothermalBlocks.defaultPalette(Set.of(BlockId.minecraft("stone")));
        assertNull(palette.resolve(GeothermalBlocks.POTENT_SULFUR));
        assertNull(palette.resolve(BlockState.of(GeothermalBlocks.SULFUR)));
    }

    @Test
    void compatibleFallbackKeepsPropertiesOthersDropThem() {
        BlockPalette palette = GeothermalBlocks.defaultPalette(Set.of(GeothermalBlocks.POINTED_DRIPSTONE, GeothermalBlocks.DIORITE));
        BlockState spike = Geothermal.spikeState(0, 1);
        assertEquals(new BlockState(GeothermalBlocks.POINTED_DRIPSTONE, spike.properties()), palette.resolve(spike));

        BlockState calcite = BlockState.of(GeothermalBlocks.CALCITE).with("foo", "bar");
        assertEquals(BlockState.of(GeothermalBlocks.DIORITE), palette.resolve(calcite));
    }
}
