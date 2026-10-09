package me.alex4386.typhon.engine.magma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The crust's weight from its layers. */
class CrustColumnTest {
    /** 200 m of scoria (2000 kg/m³ saturated) over 800 m of lavas (2400) over dense crust (2800). */
    static final CrustColumn EDIFICE = new CrustColumn(new double[] {200, 1000, Double.POSITIVE_INFINITY},
            new double[] {2000, 2400, 2800});

    @Test
    void weightAddsUpLayerByLayer() {
        double g = 9.81;
        assertEquals(2000 * g * 100 / 1e6, EDIFICE.pressureMPa(100), 1e-12);
        assertEquals((2000 * 200 + 2400 * 800 + 2800 * 2000) * g / 1e6, EDIFICE.pressureMPa(3000), 1e-9);
        for (double z : new double[] {0, 50, 200, 700, 1000, 4000}) {
            assertEquals(z, EDIFICE.depthAtPressure(EDIFICE.pressureMPa(z)), 1e-6, "inverse at " + z);
        }
        assertEquals(2400, EDIFICE.densityAt(500));
        assertEquals(2800, EDIFICE.densityAt(1e5));
    }

    @Test
    void aPorousEdificeWeighsLessThanUniformCrust() {
        double uniform = CrustColumn.uniform(2600).pressureMPa(3000);
        assertTrue(EDIFICE.pressureMPa(3000) > uniform - 2 && EDIFICE.meanDensity(1000) < 2600,
                "light near the surface, dense at depth");
        assertEquals(2600 * 9.81 * 3000 / 1e6, uniform, 1e-9);
    }
}
