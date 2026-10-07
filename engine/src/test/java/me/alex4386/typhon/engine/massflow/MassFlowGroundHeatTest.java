package me.alex4386.typhon.engine.massflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.ColumnIndex;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.Trigger;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.volcano.GroundCoupling;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialTable;
import org.junit.jupiter.api.Test;

/** Hot pyroclastic deposits hand their sensible heat to the ground model. */
class MassFlowGroundHeatTest {

    static final class HeatMeter implements GroundCoupling {
        double heat;
        @Override public double surfaceWaterDepthM(int x, int z) { return 0; }
        @Override public double removeSurfaceWater(int x, int z, double v) { return 0; }
        @Override public void addGroundHeat(int x, int z, double joules) { heat += joules; }
        @Override public void addIntrusionHeat(double x, double z, double d, double a, double w, double t) {}
    }

    private static double run(double temperatureC) {
        MassFlowTestWorld w = new MassFlowTestWorld(0, 0, 15, 3, MassFlowTestWorld.rampToPlain(0.35, 120, 40));
        PyroclasticFlows pdc = new PyroclasticFlows("pdc", w.terrain);
        HeatMeter meter = new HeatMeter();
        pdc.setGround(meter);
        Engine e = w.engine(pdc, 1);
        pdc.release(new ColumnIndex(8, 32), 3, 2000, temperatureC, 0, Trigger.MANUAL);
        w.runUntilStill(e, pdc, 6000);
        return meter.heat;
    }

    @Test
    void hotDepositsHeatTheGroundAndColdOnesDoNot() {
        double hot = run(600);
        double cold = run(15); // at ambient: nothing to give
        assertTrue(hot > 0, "a 600 °C deposit should heat the ground");
        assertTrue(cold < 1e-6 * hot, "an ambient deposit carries (almost) no heat: " + cold);
        // Bounded by the deposit's sensible heat: ρ_bulk c ΔT V with V ≤ released × deposit factor.
        Material tuff = MaterialTable.require("tuff");
        double bound = 2 * tuff.densityKgM3() * tuff.heatCapacityJkgK() * 600 * 2000;
        assertTrue(hot < bound, "heat " + hot + " should stay below " + bound);
    }
}
