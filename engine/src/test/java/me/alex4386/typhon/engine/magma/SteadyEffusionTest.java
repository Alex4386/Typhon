package me.alex4386.typhon.engine.magma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.sim.Engine;
import org.junit.jupiter.api.Test;

/**
 * An open system in balance: when outflow through the conduit can match the deep supply, the chamber
 * settles at a constant, still-pressurised state and erupts for as long as the supply lasts
 * (Puʻu ʻŌʻō 1983–2018, Kīlauea's summit lava lake 2008–2018, Stromboli). When the balance lies beyond
 * the rupture limit and no dike can carry magma away, the pressure is pinned at the limit and the walls
 * take up the rest.
 */
class SteadyEffusionTest {
    private static final BlockPos CENTER = new BlockPos(0, 0, 0);

    private static MagmaChamberConfig.Builder chamber(double supply) {
        return MagmaChamberConfig.builder("v", Point3.ofBlock(CENTER, 1)).volume(5e7).compressibilityPerMPa(2e-4).lithostaticDepth(3000)
                .conduitRadius(0.8).tensileStrengthMPa(8).eruptionEndOverpressureMPa(0.5).supplyRate(supply)
                .supplyVariability(0).initialOverpressureMPa(7.9);
    }

    /** Runs {@code hours} after the eruption starts (adaptive steps); returns the chamber. */
    private static MagmaChamber erupt(MagmaChamberConfig config, double hours) {
        MagmaChamber c = new MagmaChamber(config);
        Engine engine = Engine.builder(0).adaptive(3600).add(c).build();
        double since = Double.NaN;
        while (!(since >= hours * 3600)) {
            engine.step();
            if (c.erupting()) since = Double.isNaN(since) ? 0 : since + engine.lastStepSeconds();
            else if (!Double.isNaN(since)) break; // it stopped
        }
        return c;
    }

    @Test
    void outflowSettlesOnTheSupplyAtAConstantPressure() {
        // This chamber balances between ~2 m³/s (below: the eruption drains and ends, as in
        // episodic activity) and ~10 m³/s (above: the balance lies beyond the walls' limit).
        // The approach takes several stiffness/conductance times (~10 h here).
        double supply = 5;
        MagmaChamber c = erupt(chamber(supply).build(), 72);
        assertTrue(c.erupting(), "a balanced open system keeps erupting");
        assertEquals(supply, c.eruptionRate(), 0.01 * supply, "outflow equals inflow");
        double balance = c.balanceOverpressureMPa();
        assertTrue(balance > c.config().eruptionEndOverpressureMPa() && balance < c.ruptureOverpressureMPa(),
                "the balance lies in the open, pressurised range: " + balance);
        assertEquals(balance, c.overpressureMPa(), 0.01 * balance, "the chamber sits at its balance pressure");
        assertTrue(c.overpressureMPa() > 0, "still pressurised");
        assertEquals(0, c.wallGrowthM3(), 1e-6, "nothing beyond the walls' limit");

        // a day later it is the same state
        MagmaChamber later = erupt(chamber(supply).build(), 96);
        assertEquals(c.overpressureMPa(), later.overpressureMPa(), 0.01 * c.overpressureMPa());
        assertEquals(c.eruptionRate(), later.eruptionRate(), 0.01 * supply);
    }

    @Test
    void beyondTheRuptureLimitTheWallsTakeTheRestWhenNoDikeCan() {
        // No dike model here (as with dikes switched off): rupture magma stays as chamber growth.
        double supply = 200;
        MagmaChamber c = erupt(chamber(supply).build(), 6);
        assertTrue(c.erupting());
        assertTrue(c.balanceOverpressureMPa() > c.ruptureOverpressureMPa(),
                "this supply would need more pressure than the walls hold: " + c.balanceOverpressureMPa());
        assertEquals(c.ruptureOverpressureMPa(), c.overpressureMPa(), 1e-6, "pinned at the rupture limit");
        assertTrue(c.eruptionRate() < supply, "the conduit cannot carry the whole supply");
        double hours = 6;
        double grown = c.wallGrowthM3();
        double expected = (supply - c.eruptionRate()) * hours * 3600;
        assertTrue(grown > 0.5 * expected && grown < 1.5 * expected,
                "growth ≈ supply − outflow over the eruption: " + grown + " vs " + expected);
    }
}
