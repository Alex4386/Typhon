package me.alex4386.typhon.engine.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A localised vent's throat: cylinder heat balance and competition between vents. */
class VentThroatTest {
    static final double BASALT_C = 1170;
    static final double BASALT_SIO2 = 50;
    static final double DAY = 86400;

    static VentThroat throat(double radius) {
        // a 1.5 km feeder on the average continental geotherm, a day after its fissure opened
        return new VentThroat(radius, 1500, 37.5, DAY);
    }

    @Test
    void cylinderFluxFollowsItsAsymptotes() {
        for (double tau : new double[] {1e-4, 1e-3}) {
            assertEquals(1 / Math.sqrt(Math.PI * tau) + 0.5, VentThroat.cylinderFlux(tau), 0.02 * VentThroat.cylinderFlux(tau));
        }
        for (double tau : new double[] {1e4, 1e6}) {
            double d = Math.log(4 * tau) - 2 * 0.5772156649;
            assertEquals(2 / d, VentThroat.cylinderFlux(tau), 0.1 * VentThroat.cylinderFlux(tau));
        }
        double previous = Double.POSITIVE_INFINITY;
        for (double lg = -4; lg <= 6; lg += 0.05) {
            double f = VentThroat.cylinderFlux(Math.pow(10, lg));
            assertTrue(f < previous, "the flux decays monotonically");
            previous = f;
        }
    }

    @Test
    void aNarrowThroatWithoutFlowFreezesWithinHours() {
        VentThroat t = throat(0.1);
        double s = 0;
        while (!t.frozen() && s < 30 * DAY) {
            t.advance(600, 0, BASALT_C, BASALT_SIO2);
            s += 600;
        }
        assertTrue(t.frozen());
        assertTrue(s < DAY, "a 20 cm pipe of stagnant basalt freezes within a day, took " + s / 3600 + " h");
    }

    @Test
    void aWellFedThroatHoldsOpen() {
        VentThroat t = throat(1.0);
        for (double s = 0; s < 30 * DAY; s += 600) t.advance(600, 5, BASALT_C, BASALT_SIO2);
        assertFalse(t.frozen());
        assertTrue(t.radius > 1.0, "5 m³/s melts the throat's walls back: " + t.radius);
    }

    @Test
    void ventsSharingAFlowCoalesceIntoTheWidest() {
        double[] radii = {0.8, 1.0, 0.7, 0.9, 0.5};
        VentThroat[] vents = new VentThroat[radii.length];
        for (int i = 0; i < radii.length; i++) vents[i] = throat(radii[i]);
        double flux = 5;
        for (double s = 0; s < 60 * DAY; s += 600) {
            double sum = 0;
            for (VentThroat v : vents) sum += v.conductance();
            for (VentThroat v : vents) v.advance(600, sum > 0 ? flux * v.conductance() / sum : 0, BASALT_C, BASALT_SIO2);
        }
        int open = 0;
        for (VentThroat v : vents) if (!v.frozen()) open++;
        assertEquals(1, open, "the flow focuses into one vent");
        assertFalse(vents[1].frozen(), "the widest takes the flow");
    }
}
