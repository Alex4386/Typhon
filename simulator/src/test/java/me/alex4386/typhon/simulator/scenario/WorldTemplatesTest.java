package me.alex4386.typhon.simulator.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Template terrain holds everywhere: it is also the context terrain and the ground of grown tiles. */
class WorldTemplatesTest {
    @Test
    void oceanStaysOceanToTheHorizon() {
        WorldTemplates.Template ocean = WorldTemplates.Template.defaults("ocean");
        for (double x = -100_000; x <= 100_000; x += 500) {
            for (double z : new double[] {-50_000, 0, 50_000}) {
                double e = ocean.elevation(x, z);
                assertTrue(e < ocean.seaLevelZ(), "sea floor at x=" + x + " is " + e);
            }
        }
        // the template's depth and gradient at the centre, and continuous through it
        assertEquals(ocean.seaLevelZ() - ocean.depthM(), ocean.elevation(0, 0), 1e-9);
        double h = 1;
        double left = (ocean.elevation(0, 0) - ocean.elevation(-h, 0)) / h;
        double right = (ocean.elevation(h, 0) - ocean.elevation(0, 0)) / h;
        assertEquals(-ocean.slope(), left, 1e-4);
        assertEquals(-ocean.slope(), right, 1e-4);
    }

    @Test
    void flatAndSlopeContinueTheirShape() {
        WorldTemplates.Template flat = WorldTemplates.Template.defaults("flat");
        assertEquals(flat.elevationM(), flat.elevation(-90_000, 40_000), 1e-9);
        WorldTemplates.Template slope = WorldTemplates.Template.defaults("slope");
        assertEquals(slope.elevationM() - slope.slope() * 30_000, slope.elevation(30_000, 0), 1e-9);
    }
}
