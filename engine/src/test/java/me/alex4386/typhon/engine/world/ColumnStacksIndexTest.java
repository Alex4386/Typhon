package me.alex4386.typhon.engine.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The dense tile index answers like the tile map as tiles appear anywhere, negative coordinates included. */
class ColumnStacksIndexTest {
    @Test
    void lookupsFollowTilesAddedInAnyOrder() {
        ColumnStacks stacks = new ColumnStacks(-100);
        Map<Long, Double> surfaces = new HashMap<>();
        Random random = new Random(3);
        for (int i = 0; i < 400; i++) {
            int x = random.nextInt(2000) - 1000;
            int z = random.nextInt(2000) - 1000;
            double top = random.nextInt(100);
            stacks.setLayers(x, z, List.of(new ColumnStacks.LayerSpec(top, MaterialTable.BASALT.id(), 0, 0, 0, 0)));
            surfaces.put(((long) x << 32) | (z & 0xffffffffL), top);
            // every column set so far is still found
            for (Map.Entry<Long, Double> e : surfaces.entrySet()) {
                int cx = (int) (e.getKey() >> 32);
                int cz = (int) (long) e.getKey();
                assertTrue(stacks.known(cx, cz));
                assertEquals(e.getValue(), stacks.surface(cx, cz), 1e-4);
            }
        }
        assertFalse(stacks.known(5000, 5000));
        assertFalse(stacks.known(-5000, -5000));
    }

    @Test
    void depositAtTheLayerCapLeavesNeighbouringColumnsIntact() {
        ColumnStacks stacks = new ColumnStacks(-100);
        for (int x = 0; x < 3; x++) {
            stacks.setLayers(x, 0, List.of(new ColumnStacks.LayerSpec(0, MaterialTable.BASALT.id(), 0, 0, 0, 0)));
        }
        for (int i = 0; i < 3 * ColumnStacks.MAX_LAYERS; i++) {
            // alternating units never extend the top layer, so the middle column runs into the cap
            stacks.deposit(1, 0, 0.1 + 0.01 * (i % 7), MaterialTable.BASALT.id(), i % 2 + 1, 0, 0, 0);
        }
        assertEquals(ColumnStacks.MAX_LAYERS, stacks.count(1, 0));
        assertEquals(1, stacks.count(0, 0));
        assertEquals(1, stacks.count(2, 0));
        assertEquals(0, stacks.surface(0, 0), 1e-6);
        assertEquals(0, stacks.surface(2, 0), 1e-6);
        double expected = 0;
        for (int i = 0; i < 3 * ColumnStacks.MAX_LAYERS; i++) expected += 0.1 + 0.01 * (i % 7);
        assertEquals(expected, stacks.surface(1, 0), 1e-3);
        double previous = -100;
        for (int k = 0; k < stacks.count(1, 0); k++) {
            LayerView layer = stacks.layer(1, 0, k);
            assertTrue(layer.top() > previous);
            previous = layer.top();
        }
    }
}
