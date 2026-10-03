package me.alex4386.typhon.engine.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.world.BlockId;
import org.junit.jupiter.api.Test;

class TerrainModelTest {
    private static final BlockId STONE = BlockId.minecraft("stone");

    @Test
    void snapshotsArriveViaCommandsAndAreCopied() {
        TerrainModel terrain = new TerrainModel();
        Engine engine = Engine.builder(0).add(terrain).build();

        TerrainChunk chunk = new TerrainChunk(-1, 0);
        chunk.set(-3, 5, new TerrainColumn(70, 72, BlockId.minecraft("sand")));
        engine.submit(new TerrainSnapshot(List.of(chunk)));
        engine.tick();
        chunk.set(-3, 5, TerrainColumn.dry(10, STONE)); // host reuses its buffer

        TerrainColumn column = terrain.column(-3, 5);
        assertEquals(70, column.groundY());
        assertTrue(column.submerged());
        assertEquals(2, column.waterDepth());
        assertTrue(terrain.isKnown(-16, 15));
        assertFalse(terrain.isKnown(0, 0));
        assertNull(terrain.column(0, 0));
        assertEquals(-99, terrain.groundY(0, 0, -99));
    }

    @Test
    void engineEditsKeepWater() {
        TerrainModel terrain = new TerrainModel();
        terrain.setColumn(1, 1, new TerrainColumn(60, 64, STONE));
        terrain.setGround(1, 1, 62, BlockId.minecraft("basalt"));
        TerrainColumn column = terrain.column(1, 1);
        assertEquals(62, column.groundY());
        assertEquals(64, column.waterY());
        assertEquals(BlockId.minecraft("basalt"), column.surface());
    }
}
