package me.alex4386.typhon.engine.magma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.math.BlockPos;
import org.junit.jupiter.api.Test;

class ConduitFlowTest {
    private static final MagmaChamberConfig CONFIG = MagmaChamberConfig.builder("v", new BlockPos(0, -40, 0)).build();
    private static final MagmaChamberConfig WIDE = CONFIG.toBuilder().conduitRadius(15).lithostaticDepth(7500).build();

    @Test
    void slowAscentErupsDegassedMagma() {
        double fast = ConduitFlow.retainedWaterWt(WIDE, 5, 8000);
        double slow = ConduitFlow.retainedWaterWt(WIDE, 5, 1);
        assertTrue(fast > 4, "a Plinian-rate column has no time to outgas: " + fast);
        assertEquals(CONFIG.conduit().degassedWaterWt(), slow, 0.01, "dome-rate magma is fully outgassed");
        assertTrue(ConduitFlow.retainedWaterWt(WIDE, 5, 100) < fast);
    }

    @Test
    void viscousWetMagmaFragmentsWhenFastButNotWhenSlow() {
        // St. Helens-like dacite.
        assertTrue(ConduitFlow.fragments(WIDE, 64, 4.6, 920, 0.3, 8000));
        assertFalse(ConduitFlow.fragments(WIDE, 64, 4.6, 920, 0.3, 1), "slow extrusion degasses into a dome");
    }

    @Test
    void fluidBasaltFountainsInsteadOfFragmentingBrittlely() {
        // Even at a high, gas-rich rate basalt cannot reach the brittle stress.
        assertFalse(ConduitFlow.fragments(CONFIG, 50, 3, 1150, 0.05, 100));
        double stress = ConduitFlow.fragmentationStress(CONFIG, 50, 1150, 0.05, 100);
        assertTrue(stress < CONFIG.conduit().brittleStressPa() / 100, "basalt stress " + stress);
    }

    @Test
    void gasFractionGrowsWithWaterAndVanishesBelowSolubility() {
        ConduitConfig c = ConduitConfig.DEFAULT;
        assertEquals(0, ConduitFlow.gasFractionAtFragmentation(c, 0.3, 1000));
        double little = ConduitFlow.gasFractionAtFragmentation(c, 0.5, 1000);
        double much = ConduitFlow.gasFractionAtFragmentation(c, 3, 1000);
        assertTrue(little > 0 && much > little && much < 1);
    }

    @Test
    void gasSlugsRiseFastInFluidMagmaAndStallInViscousMagma() {
        double inertial = 0.345 * Math.sqrt(9.81 * 3);
        assertEquals(inertial, ConduitFlow.slugVelocity(3, 100), 1e-9, "inviscid Taylor bubble");
        assertTrue(ConduitFlow.slugVelocity(3, 1e7) < 1e-3, "slugs cannot rise through dacite");
        assertTrue(ConduitFlow.slugVelocity(3, 1e6) < ConduitFlow.slugVelocity(3, 1e5));
    }
}
