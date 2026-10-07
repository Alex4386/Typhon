package me.alex4386.typhon.engine.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.WorldModel;
import org.junit.jupiter.api.Test;

class TerrainModelTest {
    @Test
    void groundArrivesViaCommandsAndIsCopied() {
        TerrainModel terrain = new TerrainModel();
        Engine engine = Engine.builder(0).add(terrain).build();

        List<GroundColumn> columns = new ArrayList<>();
        columns.add(new GroundColumn(-3, 5, 70.4, 72.9, MaterialTable.SEDIMENT));
        engine.submit(new GroundImport(columns));
        engine.step();
        columns.set(0, GroundColumn.dry(-3, 5, 10, MaterialTable.ANDESITE)); // host reuses its buffer

        WorldModel world = terrain.world();
        assertEquals(70.4, world.surfaceZ(-3, 5), 1e-4); // stored in single precision
        assertEquals(72.9, world.waterZ(-3, 5), 1e-4);
        assertEquals(MaterialTable.SEDIMENT, world.layer(-3, 5, world.layerCount(-3, 5) - 1).materialInfo());
        assertTrue(world.isKnown(-3, 5));
        assertFalse(world.isKnown(0, 0));
    }

    @Test
    void reImportDepositsOrErodesToTheSurfaceAndSetsWater() {
        TerrainModel terrain = new TerrainModel();
        WorldModel world = terrain.world();
        terrain.apply(new GroundImport(List.of(new GroundColumn(1, 1, 60, 64, MaterialTable.ANDESITE))));
        assertEquals(60, world.surfaceZ(1, 1), 1e-9);
        assertEquals(64, world.waterZ(1, 1), 1e-9);

        terrain.apply(new GroundImport(List.of(new GroundColumn(1, 1, 62.5, 64, MaterialTable.BASALT))));
        assertEquals(62.5, world.surfaceZ(1, 1), 1e-9);
        assertEquals(64, world.waterZ(1, 1), 1e-9);
        assertEquals(MaterialTable.BASALT, world.layer(1, 1, world.layerCount(1, 1) - 1).materialInfo());

        terrain.apply(new GroundImport(List.of(GroundColumn.dry(1, 1, 58.25, null))));
        assertEquals(58.25, world.surfaceZ(1, 1), 1e-9);
        assertTrue(Double.isNaN(world.waterZ(1, 1)));
    }
}
