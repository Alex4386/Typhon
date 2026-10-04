package me.alex4386.typhon.engine.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import me.alex4386.typhon.engine.config.WorldDefinition;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import org.junit.jupiter.api.Test;

class EdificeGeologyTest {
    private static WorldSpec spec() {
        return new WorldSpec(10, 40, -3000, Double.NaN,
                List.of(new WorldSpec.GeologyLayer("granite", -1000, 0.01),
                        new WorldSpec.GeologyLayer("sediment", 0, 0.15)),
                "andesite", "soil", 2);
    }

    private static List<Integer> materials(WorldModel world, int x, int z) {
        return world.column(x, z).layers().stream().map(l -> (int) l.material()).toList();
    }

    @Test
    void edificeReplacesCountryRockAboveItsBaseInsideItsRadius() {
        WorldModel world = new WorldModel(spec());
        world.setEdifices(List.of(new Edifice("kilauea", 0.5, 0.5, 50, 200, "basalt")));
        world.importColumn(0, 0, 1200, null);   // on the cone
        world.importColumn(80, 0, 1200, null);  // same height, outside the edifice

        assertEquals(List.of((int) MaterialTable.GRANITE.id(), (int) MaterialTable.SEDIMENT.id(),
                (int) MaterialTable.ANDESITE.id(), (int) MaterialTable.BASALT.id(), (int) MaterialTable.SOIL.id()),
                materials(world, 0, 0));
        ColumnProfile cone = world.column(0, 0);
        assertEquals(200, cone.layers().get(2).top(), 1e-3, "country rock up to the edifice base");
        assertEquals(1198, cone.layers().get(3).top(), 1e-3, "edifice up to the cover");
        UnitRecord edifice = world.unit(cone.layers().get(3).unit());
        assertEquals(DepositType.EDIFICE, edifice.type());
        assertEquals("kilauea", edifice.volcanoId());
        assertEquals("kilauea", world.unit(cone.layers().get(4).unit()).volcanoId(), "cover belongs to the edifice");

        assertEquals(List.of((int) MaterialTable.GRANITE.id(), (int) MaterialTable.SEDIMENT.id(),
                (int) MaterialTable.ANDESITE.id(), (int) MaterialTable.SOIL.id()), materials(world, 80, 0));
    }

    @Test
    void edificeWithoutBaseReachesTheBasementAndOverlapsGoToTheCloserCentre() {
        WorldModel world = new WorldModel(spec());
        world.setEdifices(List.of(
                new Edifice("east", 100.5, 0.5, 120, Double.NaN, "basalt"),
                new Edifice("west", -100.5, 0.5, 120, Double.NaN, "dacite")));
        world.importColumn(60, 0, 500, null);   // 40 from east, 160 from west: east
        world.importColumn(-30, 0, 500, null);  // 130 from east (outside), 70 from west
        assertEquals(MaterialTable.BASALT.id(), world.column(60, 0).layers().get(2).material());
        assertEquals(0, world.column(60, 0).layers().get(1).top(), 1e-3, "edifice starts on the basement cake");
        assertEquals(MaterialTable.DACITE.id(), world.column(-30, 0).layers().get(2).material());
    }

    @Test
    void unboundedEdificeCoversEverythingAndUnitsSurviveSaveAndLoad() {
        List<Edifice> edifices = List.of(new Edifice("solo", 0.5, 0.5, Double.POSITIVE_INFINITY, Double.NaN, "rhyolite"));
        TerrainModel terrain = new TerrainModel(new WorldModel(spec()));
        WorldModel world = terrain.world();
        world.setEdifices(edifices);
        Engine engine = Engine.builder(1).add(terrain).build();
        world.importColumn(5000, -4000, 300, null);
        int unit = world.column(5000, -4000).layers().get(2).unit();
        assertEquals(MaterialTable.RHYOLITE.id(), world.column(5000, -4000).layers().get(2).material());

        InMemorySaveStore store = new InMemorySaveStore();
        engine.save(store);
        TerrainModel restoredTerrain = new TerrainModel(new WorldModel(spec()));
        Engine.builder(1).add(restoredTerrain).restore(store).build();
        WorldModel loaded = restoredTerrain.world();
        assertEquals(world.column(5000, -4000), loaded.column(5000, -4000));
        loaded.setEdifices(world.edifices());
        loaded.importColumn(5001, -4000, 300, null);
        assertEquals(unit, loaded.column(5001, -4000).layers().get(2).unit(), "same volcano edifice unit after reload");
    }

    @Test
    void initialConditionsFromTheDefinition() {
        WorldDefinition.Geotherm g = new WorldDefinition.Geotherm(15, 30, 6.5);
        assertEquals(15 - 6.5 + 30, g.initialTemperature(1000, 1000), 1e-9);
        assertEquals(15 + 3, g.initialTemperature(-50, 100), 1e-9, "no lapse below sea level");
        WorldDefinition.Aquifer flat = new WorldDefinition.Aquifer(20, 0.1, 0.0, Double.NaN, 0.3);
        assertEquals(0, flat.initialWaterTable(500, 0), 1e-9);
        WorldDefinition.Aquifer follows = new WorldDefinition.Aquifer(20, 0.1, 1.0, Double.NaN, 0.3);
        assertEquals(480, follows.initialWaterTable(500, 0), 1e-9);
        WorldDefinition.Aquifer subdued = new WorldDefinition.Aquifer(20, 0.1, 0.5, Double.NaN, 0.3);
        assertEquals(240, subdued.initialWaterTable(500, 0), 1e-9);
        assertEquals(-10, flat.initialWaterTable(-10, 0), 1e-9, "never above the surface (a depression below base level)");
    }
}
