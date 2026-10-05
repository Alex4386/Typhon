package me.alex4386.typhon.engine.magma.conduit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.magma.ConduitConfig;
import me.alex4386.typhon.engine.magma.conduit.ConduitModel.Branch;
import me.alex4386.typhon.engine.magma.conduit.ConduitSolution.Fragmentation;
import org.junit.jupiter.api.Test;

/** Qualitative behaviour of published steady conduit models (Wilson &amp; Head 1981; Melnik &amp; Sparks 1999). */
class ConduitModelTest {
    private static final ConduitConfig C = ConduitConfig.DEFAULT;
    private static final double CRUST = 2600;

    private static ConduitInput input(double overpressureMPa, double depth, double radius, double temperatureC,
            double silica, double water, double co2, double crystals) {
        double p = CRUST * 9.81 * depth + overpressureMPa * 1e6;
        return new ConduitInput(p, temperatureC, silica, water, co2, 0, crystals, radius, depth,
                ConduitInput.ATMOSPHERE_PA, 300);
    }

    private static ConduitInput basalt(double overpressureMPa) {
        return input(overpressureMPa, 1500, 1.5, 1165, 50, 0.4, 0.3, 0);
    }

    @Test
    void wetRhyoliteAtHighOverpressureFragmentsAndChokes() {
        ConduitSolution s = ConduitModel.solve(input(15, 6000, 20, 850, 74, 5.5, 0.05, 0.1), C, Branch.FASTEST);
        assertNotNull(s);
        assertTrue(s.fragmented(), "wet rhyolite fragments: " + s);
        assertTrue(s.fragmentationDepthM() > 100, "fragmentation lies deep in the conduit: " + s.fragmentationDepthM());
        assertTrue(s.choked(), "a Plinian jet leaves at its sound speed");
        assertTrue(s.exitPressurePa() >= ConduitInput.ATMOSPHERE_PA);
        assertTrue(s.exitVelocity() > 100 && s.exitVelocity() < 400, "exit velocity " + s.exitVelocity());
        assertTrue(s.dreRateM3PerS() > 300, "Plinian-scale discharge: " + s.dreRateM3PerS());
    }

    @Test
    void degassedDaciteAtLowOverpressureExtrudesCoherentViscousLava() {
        ConduitSolution s = ConduitModel.solve(input(3, 3000, 10, 880, 64, 1.0, 0, 0.4), C, Branch.SLOWEST);
        assertNotNull(s);
        assertFalse(s.fragmented(), "degassed dacite rises without fragmenting: " + s);
        assertTrue(s.outgassedFraction() > 0.5, "slow ascent lets the gas escape: " + s.outgassedFraction());
        assertTrue(s.exitMeltViscosityLog10() > 8, "crystal-rich, degassed lava: " + s.exitMeltViscosityLog10());
        assertTrue(s.dreRateM3PerS() < 10, "dome-scale discharge: " + s.dreRateM3PerS());
        assertEquals(ConduitInput.ATMOSPHERE_PA, s.exitPressurePa(), 0.1 * ConduitInput.ATMOSPHERE_PA);
    }

    @Test
    void basaltLeavesTheVentAtTensOfMetresPerSecondAndFragmentsInertially() {
        ConduitSolution s = ConduitModel.solve(basalt(9.5), C, Branch.FASTEST);
        assertNotNull(s);
        assertTrue(s.exitVelocity() > 10 && s.exitVelocity() < 200, "exit velocity " + s.exitVelocity());
        assertEquals(Fragmentation.INERTIAL, s.fragmentation(), "fluid basalt tears into clots, not ash");
        assertTrue(s.exitGasConstant() < 461.5, "CO₂ joins the water vapour: " + s.exitGasConstant());
    }

    @Test
    void moreOverpressureDrivesMoreFlux() {
        double low = ConduitModel.solve(basalt(1), C, Branch.FASTEST).massFluxKgPerS();
        double high = ConduitModel.solve(basalt(9.5), C, Branch.FASTEST).massFluxKgPerS();
        assertTrue(high > low, low + " → " + high);
    }

    @Test
    void underpressuredChamberCannotLiftItsColumn() {
        // The chamber pressure falls well short of the weight of a gas-free magma column.
        double depth = 3000;
        double p = 2500 * 9.81 * depth - 10e6;
        ConduitInput in = new ConduitInput(p, 1150, 50, 0, 0, 0, 0, 1.5, depth, ConduitInput.ATMOSPHERE_PA, 300);
        assertTrue(ConduitModel.admissibleFluxes(in, C).isEmpty());
        assertNull(ConduitModel.solve(in, C, Branch.SLOWEST));
    }

    @Test
    void co2AddsGasThatExsolvesDeep() {
        ConduitSolution dry = ConduitModel.solve(input(5, 1500, 1.5, 1165, 50, 0.4, 0, 0), C, Branch.FASTEST);
        ConduitSolution co2 = ConduitModel.solve(input(5, 1500, 1.5, 1165, 50, 0.4, 0.3, 0), C, Branch.FASTEST);
        assertTrue(co2.totalGasMassFraction() > dry.totalGasMassFraction() + 0.002);
    }

    @Test
    void solveNearFollowsTheCurrentBranch() {
        ConduitInput in = basalt(9.5);
        ConduitSolution s = ConduitModel.solve(in, C, Branch.FASTEST);
        ConduitSolution near = ConduitModel.solveNear(in, C, s.massFluxKgPerS() * 1.05);
        assertEquals(s.massFluxKgPerS(), near.massFluxKgPerS(), 0.02 * s.massFluxKgPerS());
    }

    @Test
    void solutionsAreDeterministic() {
        ConduitInput in = input(15, 7500, 15, 920, 64, 4.6, 0.05, 0.2);
        assertEquals(ConduitModel.solve(in, C, Branch.FASTEST), ConduitModel.solve(in, C, Branch.FASTEST));
    }
}
