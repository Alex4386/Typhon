package me.alex4386.typhon.engine.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.magma.ConduitConfig;
import me.alex4386.typhon.engine.magma.conduit.ConduitInput;
import me.alex4386.typhon.engine.magma.conduit.ConduitModel;
import me.alex4386.typhon.engine.magma.conduit.ConduitModel.Branch;
import me.alex4386.typhon.engine.magma.conduit.ConduitSolution;
import me.alex4386.typhon.engine.massflow.ColumnCollapse;
import org.junit.jupiter.api.Test;

/** The surface partition follows the flow: no shares are fixed, no style is chosen. */
class VentPartitionTest {
    private static final double ATM = ConduitInput.ATMOSPHERE_PA;

    private static ConduitSolution flow(double overpressureMPa, double depth, double radius, double temperatureC,
            double silica, double water, double co2, double crystals, Branch branch) {
        ConduitInput in = new ConduitInput(2600 * 9.81 * depth + overpressureMPa * 1e6, temperatureC, silica, water, co2,
                0, crystals, radius, depth, ATM, 200);
        return ConduitModel.solve(in, ConduitConfig.DEFAULT, branch);
    }

    static ConduitSolution basaltFountain() {
        return flow(9.5, 1500, 1.5, 1165, 50, 0.4, 0.3, 0, Branch.FASTEST);
    }

    static ConduitSolution plinian() {
        return flow(15, 6000, 20, 850, 74, 5.5, 0.05, 0.1, Branch.FASTEST);
    }

    static ConduitSolution dome() {
        return flow(3, 3000, 10, 880, 64, 1.0, 0, 0.4, Branch.SLOWEST);
    }

    private static final VentPartition.CollapseThreshold WOODS = (m, u, t) -> {
        double lo = 1e-3;
        double hi = 0.6;
        if (ColumnCollapse.analyze(m, u, hi, t).collapses()) return 1;
        if (!ColumnCollapse.analyze(m, u, lo, t).collapses()) return lo / 2;
        for (int i = 0; i < 16; i++) {
            double mid = Math.sqrt(lo * hi);
            if (ColumnCollapse.analyze(m, u, mid, t).collapses()) lo = mid;
            else hi = mid;
        }
        return Math.sqrt(lo * hi);
    };

    @Test
    void sharesAddUpToTheMagma() {
        for (ConduitSolution f : new ConduitSolution[] {basaltFountain(), plinian(), dome()}) {
            VentPartition.Result p = VentPartition.partition(f, ATM, 10, 60, VentPartition.Water.DRY, WOODS);
            double sum = p.lavaMassFlux() + p.ballisticMassFlux() + p.columnMassFlux() + p.wetFalloutMassFlux()
                    + p.jetMassFlux();
            assertEquals(p.magmaMassFlux(), sum, 1e-6 * p.magmaMassFlux(), "mass is conserved: " + p);
        }
    }

    @Test
    void basalticFountainFeedsLavaFromHotFallBack() {
        VentPartition.Result p = VentPartition.partition(basaltFountain(), ATM, 1.5, 50, VentPartition.Water.DRY, WOODS);
        assertTrue(p.fountainHeightM() > 50, "the jet throws clots tens to hundreds of metres up: " + p.fountainHeightM());
        assertTrue(p.clastogenicMassFlux() > 0.5 * p.magmaMassFlux(), "most clots land molten and flow on: " + p);
        assertTrue(p.columnMassFlux() < 0.2 * p.magmaMassFlux(), "little fine ash: " + p);
    }

    @Test
    void plinianFlowLoftsFineAsh() {
        VentPartition.Result p = VentPartition.partition(plinian(), ATM, 20, 74, VentPartition.Water.DRY, WOODS);
        assertTrue(p.columnMassFlux() > 0.8 * p.magmaMassFlux(), "fine ash is carried by the jet: " + p);
        assertTrue(p.lavaMassFlux() < 0.01 * p.magmaMassFlux(), "almost nothing lands molten: " + p);
        assertTrue(p.grainSize().fractions()[3] + p.grainSize().fractions()[2] > 0.5, "fine-grained");
    }

    @Test
    void coherentDomeFlowIsAllLava() {
        VentPartition.Result p = VentPartition.partition(dome(), ATM, 10, 64, VentPartition.Water.DRY, WOODS);
        assertEquals(p.magmaMassFlux(), p.lavaMassFlux(), 1e-9 * p.magmaMassFlux());
        assertEquals(0, p.columnMassFlux());
    }

    @Test
    void collapseRisesContinuouslyAsGasFalls() {
        double previous = -1;
        for (double gas : new double[] {0.08, 0.04, 0.02, 0.01, 0.005}) {
            double critical = WOODS.criticalGasFraction(1e7, 150, 900);
            double collapsing = 1 / (1 + Math.exp(Math.log(gas / critical) / 0.15));
            assertTrue(collapsing >= previous, "monotonic");
            previous = collapsing;
        }
    }

    @Test
    void waterFragmentsMagmaMostEfficientlyNearOptimalRatio() {
        ConduitSolution f = flow(11.8, 3000, 2, 1170, 46.5, 0.7, 0.2, 0, Branch.FASTEST);
        double best = 0;
        double bestDepth = -1;
        for (double depth : new double[] {1, 5, 20, 60, 200}) {
            VentPartition.Result p = VentPartition.partition(f, VentPartition.ambientPressurePa(depth), 2, 46.5,
                    new VentPartition.Water(depth, 1, 0), WOODS);
            double share = p.waterFragmentedMassFlux() / p.magmaMassFlux();
            if (share > best) {
                best = share;
                bestDepth = depth;
            }
        }
        assertTrue(best > 0.3, "shallow sea water fragments much of the magma: " + best);
        assertTrue(bestDepth < 200, "deep water suppresses steam expansion");
        VentPartition.Result deep = VentPartition.partition(f, VentPartition.ambientPressurePa(200), 2, 46.5,
                new VentPartition.Water(200, 1, 0), WOODS);
        assertTrue(deep.waterFragmentedMassFlux() < 0.1 * deep.magmaMassFlux(), "pillow depth: " + deep);
        VentPartition.Result dry = VentPartition.partition(f, ATM, 2, 46.5, VentPartition.Water.DRY, WOODS);
        assertEquals(0, dry.waterFragmentedMassFlux());
    }

    @Test
    void magmaRisingIntoAWaterSaturatedVentFillMixesWithItsPoreWater() {
        // Kokelaar (1983): a vent full of wet tephra slurry gives R ≈ φ ρ_w / ρ_m, near the efficiency peak
        VentPartition.Water slurry = new VentPartition.Water(5, 1, 0, 1, 0.45, Double.NaN);
        double r = VentPartition.slurryRatio(slurry, 2, 3e4);
        assertEquals(0.45 * 1000 / 2500, r, 1e-12);
        // a crater cut off from the sea: only what seeps back in can mix
        VentPartition.Water sealed = new VentPartition.Water(5, 0, 1000, 1, 0.45, 300);
        assertEquals(300 / 3e4, VentPartition.slurryRatio(sealed, 2, 3e4), 1e-12);
        assertTrue(VentPartition.interactionEfficiency(r) > 0.6, "near the optimum: " + VentPartition.interactionEfficiency(r));
        assertTrue(VentPartition.interactionEfficiency(300 / 3e4) < 0.15, "starved of water: mostly dry");
        assertTrue(VentPartition.interactionEfficiency(20) < 1e-20, "flooded: water quenches without exploding");
    }

    @Test
    void shallowWetVentFillIsSurtseyanDeepOrSealedIsNot() {
        ConduitSolution f = flow(11.8, 3000, 2, 1170, 46.5, 0.7, 0.2, 0, Branch.FASTEST);
        double shallow = wetShare(f, new VentPartition.Water(5, 1, 0, 1, 0.45, Double.NaN));
        double deep = wetShare(f, new VentPartition.Water(140, 1, 0, 1, 0.45, Double.NaN));
        double sealed = wetShare(f, new VentPartition.Water(0, 0, 50, 1, 0.45, 50));
        assertTrue(shallow > 0.4, "shallow slurry-filled vent: Surtseyan jets " + shallow);
        assertTrue(deep < 0.1, "deep water suppresses steam expansion: " + deep);
        assertTrue(sealed < 0.15, "a sealed crater dries out: " + sealed);
    }

    @Test
    void floodedVentMeetsWaterThroughoutAndJetsAtObservedSpeeds() {
        // Magma rising through 20 m of open sea all meets water (contact is set by water supply, not by the
        // MFCI efficiency); the explosive part drives dense cock's-tail jets at ~60–160 m/s (jets 200–500 m high,
        // the largest bombs to ~1 km; Thorarinsson 1967; Moore 1985), with coarse tephra and aggregated wet
        // ash, not a fine-ash plume.
        ConduitSolution f = flow(11.8, 3000, 2, 1170, 46.5, 0.7, 0.2, 0, Branch.FASTEST);
        for (double depth : new double[] {5, 20, 40}) {
            VentPartition.Result p = VentPartition.partition(f, VentPartition.ambientPressurePa(depth), 2, 46.5,
                    new VentPartition.Water(depth, 1, 0, 1, 0.45, Double.NaN), WOODS);
            assertTrue(p.waterFragmentedMassFlux() > 0.8 * p.magmaMassFlux(), depth + " m: magma meets water: " + p);
            assertTrue(p.jetSpeed() > 50 && p.jetSpeed() < 160, depth + " m: jet speed " + p.jetSpeed());
            assertTrue(p.jetMassFlux() > p.columnMassFlux(), depth + " m: wet tephra flies in jets, little is lofted: " + p);
        }
    }

    private static double wetShare(ConduitSolution f, VentPartition.Water water) {
        VentPartition.Result p = VentPartition.partition(f, VentPartition.ambientPressurePa(water.surfaceDepthM()), 2, 46.5,
                water, WOODS);
        return p.waterFragmentedMassFlux() / p.magmaMassFlux();
    }

    @Test
    void groundwaterMattersOnlyForSmallMagmaFluxes() {
        VentPartition.Water aquifer = new VentPartition.Water(0, 0, 10);
        assertTrue(VentPartition.waterMagmaRatio(aquifer, 2, 1e3) > 0.05, "a trickle of magma meets a wet aquifer");
        assertTrue(VentPartition.waterMagmaRatio(aquifer, 2, 1e7) < 1e-3, "a Plinian flux overwhelms it");
        assertEquals(0, VentPartition.waterMagmaRatio(new VentPartition.Water(0, 0, 1000), 2, 1e3));
    }
}
