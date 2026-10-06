package me.alex4386.typhon.engine.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.engine.world.WorldSpec;
import org.junit.jupiter.api.Test;

/** Sea water seeping through a tephra ring into a crater cut off from the sea (Darcy flow). */
class CraterSeepageTest {
    private static final int CRATER = 2;

    /** A crater floor at −20 m inside a ring {@code width} columns wide standing 20 m above the sea, on a −50 m sea floor. */
    private static WorldModel ring(int width, Material cover) {
        WorldSpec spec = new WorldSpec(10, 80, -2000, 0, List.of(new WorldSpec.GeologyLayer("granite", -500, 0.01)),
                "basalt", "ash", 10);
        WorldModel world = new WorldModel(spec);
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                double r = Math.hypot(x, z);
                double surface = r <= CRATER ? -20 : r <= CRATER + width ? 20 : -50;
                world.importColumn(x, z, surface, cover);
            }
        }
        return world;
    }

    private static double seepage(WorldModel world, double floorZ) {
        return VolcanoCoupler.seepageIntoCrater(world, new BlockPos(0, 0, 0), CRATER, floorZ);
    }

    @Test
    void seepageGrowsWithHeadAndPermeabilityAndFallsWithRingWidth() {
        WorldModel narrow = ring(4, MaterialTable.ASH);
        double base = seepage(narrow, -20);
        assertTrue(base > 0, "sea water seeps in under 20 m of head: " + base);
        assertTrue(seepage(narrow, -40) > 1.5 * base, "more head, more seepage");
        assertTrue(seepage(ring(12, MaterialTable.ASH), -20) < 0.6 * base, "a wider ring lets less through");
        assertTrue(seepage(ring(4, MaterialTable.GRAVEL), -20) > 2 * base, "coarser tephra lets more through");
        assertEquals(0, seepage(narrow, 5), "a floor above the sea draws nothing in");
    }

    @Test
    void ashRingSeepageIsDarcyFlowThroughTheCraterWall() {
        WorldModel world = ring(4, MaterialTable.ASH);
        double k = Math.pow(10, MaterialTable.ASH.log10HydraulicConductivity());
        double head = 20;
        double width = 40; // the ring is 30–60 m wide at sea level, depending on direction
        double darcy = 1000 * k * head / width * (2 * Math.PI * CRATER * 10 * head);
        double q = seepage(world, -20);
        assertTrue(q > darcy / 2 && q < darcy * 2, "Darcy order of magnitude: " + q + " vs " + darcy);
    }
}
