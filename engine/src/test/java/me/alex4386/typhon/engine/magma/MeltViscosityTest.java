package me.alex4386.typhon.engine.magma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MeltViscosityTest {
    @Test
    void reproducesReferenceOrdersOfMagnitude() {
        assertEquals(2.0, MeltViscosity.meltLog10(48, 0, 1200), 0.3, "dry basalt");
        assertEquals(1.0, MeltViscosity.meltLog10(48, 2, 1200), 0.5, "hydrous basalt");
        assertEquals(11.0, MeltViscosity.meltLog10(75, 0, 800), 0.5, "dry rhyolite");
        assertEquals(7.0, MeltViscosity.meltLog10(75, 4, 800), 0.5, "hydrous rhyolite");
    }

    @Test
    void increasesWithSilica() {
        double previous = Double.NEGATIVE_INFINITY;
        for (double silica = 48; silica <= 75; silica += 3) {
            double v = MeltViscosity.meltLog10(silica, 2, 1000);
            assertTrue(v > previous, "silica " + silica);
            previous = v;
        }
    }

    @Test
    void decreasesWithTemperatureAndWater() {
        double previous = Double.POSITIVE_INFINITY;
        for (double t = 800; t <= 1300; t += 50) {
            double v = MeltViscosity.meltLog10(60, 2, t);
            assertTrue(v < previous, "temperature " + t);
            previous = v;
        }
        previous = Double.POSITIVE_INFINITY;
        for (double w = 0; w <= 6; w += 0.5) {
            double v = MeltViscosity.meltLog10(70, w, 850);
            assertTrue(v < previous, "water " + w);
            previous = v;
        }
    }

    @Test
    void crystalsFollowEinsteinRoscoeAndSaturate() {
        double melt = MeltViscosity.meltLog10(55, 2, 1100);
        assertEquals(melt, MeltViscosity.log10(55, 2, 1100, 0), 1e-12);
        assertEquals(melt + 2.5 * Math.log10(2), MeltViscosity.log10(55, 2, 1100, 0.3), 1e-9);
        assertEquals(MeltViscosity.MAX_LOG10, MeltViscosity.log10(55, 2, 1100, 0.6));
        assertTrue(MeltViscosity.log10(55, 2, 1100, 0.5) > MeltViscosity.log10(55, 2, 1100, 0.4));
    }

    @Test
    void staysWithinBoundsForExtremeInputs() {
        assertEquals(MeltViscosity.MAX_LOG10, MeltViscosity.meltLog10(75, 0, 0));
        double hot = MeltViscosity.meltLog10(40, 20, 3000);
        assertTrue(hot >= MeltViscosity.MIN_LOG10 && hot <= MeltViscosity.MAX_LOG10);
    }
}
