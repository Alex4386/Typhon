package me.alex4386.typhon.engine.assembly;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.random.SimRandom;
import org.junit.jupiter.api.Test;

/** Thermal feeder of a fissure: Bruce &amp; Huppert regimes and Wylie et al. localisation. */
class FissureFeederTest {
    static final double BASALT_C = 1170;
    static final double BASALT_SIO2 = 50;
    static final double DAY = 86400;

    /** A Kīlauea-like fissure: 1 m dike, 500 m long, fed from 1.5 km down, 12 segments. */
    static FissureFeeder kilauea(long seed) {
        return FissureFeeder.open(1.0, 500, 1500, 12, new SimRandom(seed));
    }

    static FissureFeeder run(FissureFeeder f, double flux, double seconds) {
        for (double t = 0; t < seconds; t += 600) f.advance(600, flux, BASALT_C, BASALT_SIO2);
        return f;
    }

    @Test
    void highFluxKeepsTheFeederOpenAndLowFluxFreezesIt() {
        FissureFeeder strong = run(kilauea(1), 50, 10 * DAY);
        assertFalse(strong.frozen(), "50 m3/s should keep a 1 m dike open");
        assertTrue(strong.widestWidth() > 1.0, "and melt its walls back: " + strong.widestWidth());

        FissureFeeder weak = run(kilauea(1), 0.1, 10 * DAY);
        assertTrue(weak.frozen(), "0.1 m3/s cannot supply the heat the wall rock takes");
    }

    @Test
    void zeroFluxFreezesWithinDaysAndStaysFrozen() {
        FissureFeeder f = kilauea(2);
        double t = 0;
        while (!f.frozen() && t < 100 * DAY) {
            f.advance(600, 0, BASALT_C, BASALT_SIO2);
            t += 600;
        }
        assertTrue(f.frozen());
        assertTrue(t > 600 && t < 30 * DAY, "a 1 m dike freezes in hours to weeks, took " + t / DAY + " d");
        run(f, 100, DAY);
        assertTrue(f.frozen(), "a frozen feeder is solid rock: flow cannot reopen it");
        assertEquals(0, f.conductance());
    }

    @Test
    void flowLocalisesToTheWidestSegments() {
        FissureFeeder f = kilauea(3);
        int total = f.width.length;
        boolean localised = false;
        int previous = total;
        for (double t = 0; t < 30 * DAY && !f.frozen(); t += 600) {
            f.advance(600, 5, BASALT_C, BASALT_SIO2);
            int open = f.openSegments();
            assertTrue(open <= previous, "a frozen segment never reopens");
            previous = open;
            if (open > 0 && open < total) localised = true;
        }
        assertTrue(localised, "narrow segments freeze first while the widest keep erupting");
    }

    @Test
    void conductanceSplitsAsWidthCubed() {
        FissureFeeder f = kilauea(4);
        double sum = 0;
        for (double w : f.width) sum += w * w * w * f.segmentLengthM / 12;
        assertEquals(sum, f.conductance(), 1e-12);
        assertEquals(f.conductance(), f.initialConductance, 1e-12);
    }

    @Test
    void saveAndLoadRoundTrip() {
        FissureFeeder f = run(kilauea(5), 5, DAY);
        FissureFeeder g = FissureFeeder.load(f.save());
        assertArrayEquals(f.width, g.width);
        assertEquals(f.age, g.age);
        assertEquals(f.initialConductance, g.initialConductance);
        run(f, 3, DAY);
        run(g, 3, DAY);
        assertArrayEquals(f.width, g.width, "a restored feeder evolves identically");
    }
}
