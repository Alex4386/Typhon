package me.alex4386.typhon.simulator.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import me.alex4386.typhon.engine.terrain.TerrainChunk;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainSnapshot;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.simulator.terrain.TerrainGenerators.Cone;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerrainGeneratorsTest {
    private static final Cone CONE = new Cone(60, 150, 10, 6, 1.5, BlockId.minecraft("andesite"));

    @Test
    void coneHasCraterBelowItsRimAndSlopesToThePlain() {
        ColumnGrid g = TerrainGenerators.cone(192, 1, CONE);
        int floor = g.ground(0, 0);
        int rim = g.ground(10, 0);
        assertTrue(floor < rim - 3, "crater floor " + floor + " should be well below the rim " + rim);
        assertTrue(rim > g.ground(60, 0) && g.ground(60, 0) > g.ground(140, 0), "flanks descend");
        assertEquals(TerrainGenerators.BASE_Y, g.ground(190, 0), 3);
        assertEquals(BlockId.minecraft("andesite"), g.surface(30, 30));
    }

    @Test
    void shieldIsGentlerThanACone() {
        ColumnGrid shield = TerrainGenerators.shield(256, 1, 44, 300, 10, 6);
        ColumnGrid cone = TerrainGenerators.cone(256, 1, CONE);
        double shieldSlope = (shield.ground(40, 0) - shield.ground(100, 0)) / 60.0;
        double coneSlope = (cone.ground(40, 0) - cone.ground(100, 0)) / 60.0;
        assertTrue(shieldSlope < coneSlope, "shield " + shieldSlope + " vs cone " + coneSlope);
        assertTrue(shield.ground(0, 0) < shield.ground(14, 0), "summit pit crater");
    }

    @Test
    void islandHasSubmergedRingAroundEmergentSummit() {
        ColumnGrid g = TerrainGenerators.island(192, 3, 62, 30, 90, 140, 6);
        assertFalse(g.column(20, 0).submerged(), "summit flank is dry land");
        TerrainColumn ring = g.column(110, 0);
        assertTrue(ring.submerged(), "outer flank is under water");
        assertEquals(62, ring.waterY());
        assertTrue(g.column(180, 0).waterDepth() > 20, "sea floor is deep");
    }

    @Test
    void seamountIsFullySubmerged() {
        ColumnGrid g = TerrainGenerators.island(160, 3, 62, 30, 58, 120, 4);
        assertTrue(g.column(0, 0).submerged());
        assertTrue(g.column(15, 0).submerged());
    }

    @Test
    void calderaHasLakeOnItsFloorAndHighRim() {
        ColumnGrid g = TerrainGenerators.caldera(256, 2, 40, 160, 240, 34, 4);
        int rim = g.ground(160, 0);
        int floor = g.ground(0, 0);
        assertTrue(rim > floor + 20, "rim " + rim + " towers over floor " + floor);
        long lake = 0, dryFloor = 0;
        for (int x = -140; x <= 140; x += 4) {
            for (int z = -140; z <= 140; z += 4) {
                if (x * x + z * z > 140 * 140) continue;
                if (g.column(x, z).submerged()) lake++; else dryFloor++;
            }
        }
        assertTrue(lake > 0 && dryFloor > 0, "lake covers part of the floor: lake=" + lake + " dry=" + dryFloor);
        assertTrue(g.column(-120, 0).submerged(), "lake sits on the low western side");
    }

    @Test
    void generatorsAreDeterministicAndSeedDependent() {
        ColumnGrid a = TerrainGenerators.cone(128, 7, CONE);
        ColumnGrid b = TerrainGenerators.cone(128, 7, CONE);
        ColumnGrid c = TerrainGenerators.cone(128, 8, CONE);
        boolean differs = false;
        for (int x = a.minX(); x <= a.maxX(); x++) {
            for (int z = a.minZ(); z <= a.maxZ(); z++) {
                assertEquals(a.column(x, z), b.column(x, z));
                differs |= a.ground(x, z) != c.ground(x, z);
            }
        }
        assertTrue(differs, "a different seed changes the noise");
    }

    @Test
    void snapshotCoversWholeGrid() {
        ColumnGrid g = TerrainGenerators.plain(40, 1);
        TerrainSnapshot snapshot = g.toSnapshot();
        assertEquals((g.size() / 16) * (g.size() / 16), snapshot.chunks().size());
        TerrainChunk first = snapshot.chunks().get(0);
        assertEquals(g.column(first.chunkX() * 16 + 3, first.chunkZ() * 16 + 5), first.get(first.chunkX() * 16 + 3, first.chunkZ() * 16 + 5));
    }

    @Test
    void importsAsciiDemWithSeaLevelAndScaling(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("dem.asc");
        Files.write(file, List.of(
                "ncols 4", "nrows 4", "xllcorner 0", "yllcorner 0", "cellsize 80", "NODATA_value -9999",
                "-40 -40 -40 -40",
                "-40 400 400 -40",
                "-40 400 800 -40",
                "-40 -40 -40 -9999"));
        DemImporter.Dem dem = DemImporter.readAscii(file, 30);
        assertEquals(80, dem.cellSizeMeters());
        assertEquals(0, dem.elevation()[3][3]); // NODATA → 0 m
        ColumnGrid g = DemImporter.toGrid(dem, 8, 512);
        assertEquals(DemImporter.SEA_LEVEL_Y + 100, g.ground(0, 0), 2, "800 m at 8 m/block is 100 blocks above sea level");
        assertTrue(g.column(g.minX(), g.minZ()).submerged(), "negative elevations are flooded");
    }

    @Test
    void importsPngHeightmap(@TempDir Path dir) throws Exception {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_BYTE_GRAY);
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) img.getRaster().setSample(x, y, 0, x * 32);
        Path file = dir.resolve("h.png");
        ImageIO.write(img, "png", file.toFile());
        DemImporter.Dem dem = DemImporter.readPng(file, 0, 255 * 4, 20);
        ColumnGrid g = DemImporter.toGrid(dem, 4, 256);
        assertTrue(g.ground(g.maxX(), 0) > g.ground(g.minX(), 0), "brighter is higher");
    }
}
