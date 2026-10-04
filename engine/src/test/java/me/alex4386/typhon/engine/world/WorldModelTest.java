package me.alex4386.typhon.engine.world;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.alex4386.typhon.engine.random.SimRandom;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainChunk;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.terrain.TerrainSnapshot;
import org.junit.jupiter.api.Test;

class WorldModelTest {
    private static final double TOL = 1e-3;

    private static WorldModel flatWorld(int size, double surface) {
        WorldModel world = new WorldModel(WorldSpec.blocks(2.0));
        List<WorldModel.ColumnImport> imports = new ArrayList<>();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) imports.add(new WorldModel.ColumnImport(x, z, surface, null));
        }
        world.importColumns(imports);
        return world;
    }

    /** Layers are contiguous, positive and end at the surface. */
    private static void assertInvariants(WorldModel world, int x, int z) {
        int n = world.layerCount(x, z);
        assertTrue(n > 0 && n <= ColumnStacks.MAX_LAYERS, "layer count " + n);
        double previous = world.spec().datumZ();
        for (int i = 0; i < n; i++) {
            LayerView layer = world.layer(x, z, i);
            assertEquals(previous, layer.bottom(), 1e-9, "contiguous");
            assertTrue(layer.thickness() > 0, "positive thickness at layer " + i);
            previous = layer.top();
        }
        assertEquals(previous, world.surfaceZ(x, z), 1e-9);
        assertFalse(world.layer(x, z, n - 1).material() == MaterialTable.VOID.id(), "no cavity on top");
    }

    @Test
    void importBuildsTheGeologicalCake() {
        WorldModel world = flatWorld(4, 100);
        ColumnProfile column = world.column(1, 2);
        assertEquals(100, column.surfaceZ(), TOL);
        assertEquals(MaterialTable.GRANITE.id(), column.layers().get(0).material());
        assertEquals(-500, column.layers().get(0).top(), TOL);
        assertEquals(MaterialTable.ANDESITE.id(), column.layers().get(1).material());
        LayerView cover = column.layers().get(column.layers().size() - 1);
        assertEquals(MaterialTable.SOIL.id(), cover.material());
        assertEquals(2.0, cover.thickness(), TOL);
        assertEquals(DepositType.BASEMENT, world.unit(column.layers().get(0).unit()).type());
        assertEquals(DepositType.EDIFICE, world.unit(column.layers().get(1).unit()).type());
        assertFalse(world.isKnown(10, 10));
    }

    @Test
    void batchImportMatchesColumnByColumnImport() {
        WorldModel batch = flatWorld(40, 37.5);
        WorldModel single = new WorldModel(WorldSpec.blocks(2.0));
        for (int x = 39; x >= 0; x--) {
            for (int z = 0; z < 40; z++) single.importColumn(x, z, 37.5, null);
        }
        for (int x = 0; x < 40; x++) {
            for (int z = 0; z < 40; z++) assertEquals(single.column(x, z), batch.column(x, z));
        }
    }

    @Test
    void depositErodeAndCarveConserveVolumeAndKeepInvariants() {
        WorldModel world = flatWorld(8, 50);
        SimRandom random = new SimRandom(4);
        Material[] materials = {MaterialTable.BASALT, MaterialTable.ASH, MaterialTable.TUFF, MaterialTable.SCORIA};
        for (int i = 0; i < 4000; i++) {
            int x = random.nextInt(8);
            int z = random.nextInt(8);
            double before = world.surfaceZ(x, z);
            switch (random.nextInt(4)) {
                case 0 -> {
                    double t = random.nextDouble(0.01, 3);
                    int unit = world.newUnit(UnitRecord.of(DepositType.FALL));
                    assertTrue(world.deposit(x, z, t, materials[random.nextInt(materials.length)], unit));
                    assertEquals(before + t, world.surfaceZ(x, z), TOL);
                }
                case 1 -> {
                    double t = random.nextDouble(0.01, 4);
                    ErodeResult r = world.erode(x, z, t, random.chance(0.5));
                    assertEquals(before - r.removedM(), world.surfaceZ(x, z), TOL);
                    assertTrue(r.solidRemovedM() <= t + TOL, "never removes more solid than asked");
                }
                case 2 -> {
                    double hi = before - random.nextDouble(0.5, 5);
                    double lo = hi - random.nextDouble(0.5, 4);
                    ErodeResult r = world.carve(x, z, lo, hi, world.newUnit(UnitRecord.of(DepositType.CAVITY)));
                    assertEquals(before, world.surfaceZ(x, z), TOL, "a buried cavity leaves the surface alone");
                    assertTrue(r.removedM() <= hi - lo + TOL);
                }
                default -> {
                    double hi = before + random.nextDouble(-3, 2);
                    double lo = hi - random.nextDouble(0.5, 3);
                    world.fill(x, z, lo, hi, MaterialTable.LAHAR_DEPOSIT, 0);
                    assertEquals(Math.max(before, hi), world.surfaceZ(x, z), TOL);
                }
            }
            assertInvariants(world, x, z);
        }
    }

    @Test
    void erosionAccountsRemovedMaterial() {
        WorldModel world = flatWorld(1, 10);
        int ash = world.newUnit(UnitRecord.of(DepositType.FALL));
        world.deposit(0, 0, 1.5, MaterialTable.ASH, ash);
        world.deposit(0, 0, 0.5, MaterialTable.BASALT, 0);
        ErodeResult looseOnly = world.erode(0, 0, 5, true);
        assertEquals(0, looseOnly.removedM(), TOL, "consolidated basalt caps the loose ash");
        ErodeResult all = world.erode(0, 0, 1.2, false);
        assertEquals(0.5, all.byMaterial().get(MaterialTable.BASALT.id()), TOL);
        assertEquals(0.7, all.byMaterial().get(MaterialTable.ASH.id()), TOL);
        ErodeResult loose = world.erode(0, 0, 5, true);
        assertEquals(0.8, loose.byMaterial().get(MaterialTable.ASH.id()), TOL, "the rest of the ash is loose");
        assertEquals(2, loose.byMaterial().get(MaterialTable.SOIL.id()), TOL, "so is the soil cover beneath it");
        assertEquals(8, world.surfaceZ(0, 0), TOL, "erosion stops at consolidated andesite");
    }

    @Test
    void carvedCavityIsNotSolidAndCollapsesWhenUnroofed() {
        WorldModel world = flatWorld(1, 20);
        world.carve(0, 0, 12, 15, world.newUnit(UnitRecord.of(DepositType.CAVITY)));
        assertTrue(world.isSolid(0, 0, 11.5));
        assertFalse(world.isSolid(0, 0, 13.5));
        assertEquals(MaterialTable.VOID, world.materialAt(0, 0, 14));
        assertTrue(world.isSolid(0, 0, 16));
        assertFalse(world.isSolid(0, 0, 20.5), "air above the surface");
        ErodeResult roofOff = world.erode(0, 0, 5, false);
        assertEquals(12, world.surfaceZ(0, 0), TOL, "removing the roof leaves an open pit floor");
        assertEquals(8, roofOff.removedM(), TOL);
        assertEquals(5, roofOff.solidRemovedM(), TOL);
        assertInvariants(world, 0, 0);
    }

    @Test
    void digFromTheSurfaceOpensAPit() {
        WorldModel world = flatWorld(1, 20);
        world.carve(0, 0, 15, 30, 0);
        assertEquals(15, world.surfaceZ(0, 0), TOL);
        assertInvariants(world, 0, 0);
    }

    @Test
    void columnsNeverExceedTheLayerCap() {
        WorldModel world = flatWorld(1, 0);
        for (int i = 0; i < 300; i++) {
            int unit = world.newUnit(UnitRecord.of(DepositType.FALL));
            world.deposit(0, 0, 0.1 + (i % 7) * 0.05, i % 2 == 0 ? MaterialTable.ASH : MaterialTable.SCORIA, unit);
            assertInvariants(world, 0, 0);
        }
        assertEquals(ColumnStacks.MAX_LAYERS, world.layerCount(0, 0));
        double total = 0;
        for (int i = 0; i < 300; i++) total += 0.1 + (i % 7) * 0.05;
        assertEquals(total, world.surfaceZ(0, 0), 1e-2, "merging keeps the total thickness");
    }

    @Test
    void isSolidMatchesABruteForceVoxelScan() {
        WorldModel world = flatWorld(6, 30);
        SimRandom random = new SimRandom(9);
        for (int i = 0; i < 300; i++) {
            int x = random.nextInt(6);
            int z = random.nextInt(6);
            double s = world.surfaceZ(x, z);
            switch (random.nextInt(3)) {
                case 0 -> world.deposit(x, z, random.nextDouble(0.2, 2), MaterialTable.BASALT, 0);
                case 1 -> world.carve(x, z, s - random.nextDouble(2, 6), s - random.nextDouble(0.3, 1.5), 0);
                default -> world.erode(x, z, random.nextDouble(0.1, 1), false);
            }
        }
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                List<LayerView> layers = world.column(x, z).layers();
                for (double y = 0; y < 45; y += 0.137) {
                    boolean expected = false;
                    for (LayerView layer : layers) {
                        if (y >= layer.bottom() && y < layer.top()) {
                            expected = MaterialTable.get(layer.material()).solid() && layer.voidFraction() < 0.5;
                        }
                    }
                    assertEquals(expected, world.isSolid(x, z, y), "column " + x + "," + z + " at " + y);
                }
            }
        }
    }

    @Test
    void sectionShowsSuccessiveEruptionsInOrder() {
        WorldModel world = flatWorld(20, 10);
        int first = world.newUnit(new UnitRecord("v", 1, DepositType.LAVA, 0, 1150, 50));
        int second = world.newUnit(new UnitRecord("v", 2, DepositType.FALL, 3600, Double.NaN, 60));
        for (int x = 0; x < 20; x++) {
            world.deposit(x, 5, 4, MaterialTable.BASALT, first);
            world.deposit(x, 5, 2, MaterialTable.ASH, second);
        }
        SectionRaster section = world.section(new double[] {0.5, 5.5, 19.5, 5.5}, 0, 20, 10, 40);
        assertEquals(10, section.nu());
        for (int iu = 0; iu < section.nu(); iu++) {
            assertEquals(16, section.surfaceZ()[iu], TOL);
            List<Integer> order = new ArrayList<>();
            for (int iz = section.nz() - 1; iz >= 0; iz--) {
                int unit = section.unit()[section.index(iu, iz)];
                if (unit >= 0 && (order.isEmpty() || order.get(order.size() - 1) != unit)) order.add(unit);
            }
            assertEquals(second, (int) order.get(order.size() - 1), "youngest on top");
            assertEquals(first, (int) order.get(order.size() - 2), "older below it");
            assertEquals(MaterialTable.AIR.id(), section.material()[section.index(iu, 0)], "air above");
        }
        assertEquals(19 * 2.0 * 0.95, section.uMeters()[9], 0.2 * 19, "distance along the line in metres");
    }

    @Test
    void waterIsHeldUntilASinkConnects() {
        WorldModel world = flatWorld(2, 0);
        world.addWater(1, 0, 3);
        world.addWater(0, 1, 2);
        world.addWater(1, 0, 1);
        assertEquals(6, world.pendingWater(), 1e-12);
        List<double[]> received = new ArrayList<>();
        world.setWaterSink((x, z, v) -> received.add(new double[] {x, z, v}));
        assertEquals(0, world.pendingWater(), 1e-12);
        assertEquals(2, received.size());
        world.addWater(0, 0, 5);
        assertEquals(3, received.size());
    }

    // ── Terrain bridge and persistence ──

    private static TerrainSnapshot snapshot(int chunks, int ground) {
        List<TerrainChunk> list = new ArrayList<>();
        for (int cx = 0; cx < chunks; cx++) {
            for (int cz = 0; cz < chunks; cz++) {
                TerrainChunk chunk = new TerrainChunk(cx, cz);
                for (int x = cx * 16; x < cx * 16 + 16; x++) {
                    for (int z = cz * 16; z < cz * 16 + 16; z++) {
                        chunk.set(x, z, TerrainColumn.dry(ground + (x + z) % 3, BlockId.minecraft("grass_block")));
                    }
                }
                list.add(chunk);
            }
        }
        return new TerrainSnapshot(list);
    }

    @Test
    void terrainEditsAreMirroredIntoTheStacks() {
        TerrainModel terrain = new TerrainModel(new WorldModel(WorldSpec.blocks(4.0)));
        terrain.apply(snapshot(2, 10));
        WorldModel world = terrain.world();
        assertEquals((10 + 1) * 4.0, world.surfaceZ(0, 0), TOL);
        assertEquals(MaterialTable.SOIL.id(), world.layer(0, 0, world.layerCount(0, 0) - 1).material());

        int lava = world.newUnit(new UnitRecord("v", 0, DepositType.LAVA, 0, 1150, 50));
        terrain.setGround(0, 0, 13, BlockId.minecraft("basalt"), lava);
        assertEquals(14 * 4.0, world.surfaceZ(0, 0), TOL);
        LayerView top = world.layer(0, 0, world.layerCount(0, 0) - 1);
        assertEquals(MaterialTable.BASALT.id(), top.material());
        assertEquals(lava, top.unit());
        assertEquals(12, top.thickness(), TOL);

        terrain.setGround(0, 0, 8, BlockId.minecraft("andesite"));
        assertEquals(9 * 4.0, world.surfaceZ(0, 0), TOL, "lowering the ground erodes the stack");

        // Re-sending an unchanged snapshot keeps the layer history.
        int before = world.layerCount(1, 1);
        terrain.apply(snapshot(2, 10));
        assertEquals(before, world.layerCount(1, 1));
        assertEquals((10 + 1) * 4.0, world.surfaceZ(0, 0), TOL, "the host's surface wins");
    }

    @Test
    void regionRoundTripIsByteIdentical() {
        TerrainModel terrain = new TerrainModel(new WorldModel(WorldSpec.blocks(2.0)));
        Engine engine = Engine.builder(3).add(terrain).build();
        engine.submit(snapshot(3, 20));
        engine.step();
        WorldModel world = terrain.world();
        int unit = world.newUnit(new UnitRecord("v", 4, DepositType.PDC, 12.5, 650, 63));
        for (int i = 0; i < 40; i++) world.deposit(i, i % 7, 0.3 * i, MaterialTable.TUFF, unit);
        world.carve(5, 5, 10, 30, 0);
        world.addWater(2, 2, 9);
        world.setUplift(3, 3, 0.25);

        InMemorySaveStore first = new InMemorySaveStore();
        engine.save(first);

        TerrainModel restoredTerrain = new TerrainModel(new WorldModel(WorldSpec.blocks(2.0)));
        Engine restored = Engine.builder(3).add(restoredTerrain).restore(first).build();
        InMemorySaveStore second = new InMemorySaveStore();
        restored.save(second);

        Map<String, byte[]> a = first.files();
        Map<String, byte[]> b = second.files();
        assertEquals(a.keySet(), b.keySet());
        for (String path : a.keySet()) {
            if (path.startsWith("log/")) continue;
            assertArrayEquals(a.get(path), b.get(path), path);
        }
        assertEquals(world.column(5, 5), restoredTerrain.world().column(5, 5));
        assertEquals(world.units().all(), restoredTerrain.world().units().all());
        assertEquals(9, restoredTerrain.world().pendingWater(), 1e-12);
        assertEquals(engine.stateHash(), restored.stateHash());
    }
}
