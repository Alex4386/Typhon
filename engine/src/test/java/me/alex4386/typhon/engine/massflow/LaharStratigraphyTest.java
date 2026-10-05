package me.alex4386.typhon.engine.massflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.LayerView;
import me.alex4386.typhon.engine.world.Provenance;
import me.alex4386.typhon.engine.world.UnitRecord;
import me.alex4386.typhon.engine.world.WorldModel;
import org.junit.jupiter.api.Test;

/** Lahars rework the loose volcanic layers of the world model and lay down attributed deposits. */
class LaharStratigraphyTest {
    private static final BlockPos TOP = new BlockPos(4, 0, 32);

    private static MassFlowTestWorld ramp() {
        return new MassFlowTestWorld(0, 0, 15, 3, MassFlowTestWorld.rampToPlain(0.3, 100, 64));
    }

    /** Σ loose volcanic thickness × column area over the test area (m³). */
    private static double looseVolume(MassFlowTestWorld w, Lahars l) {
        double sum = 0;
        for (int x = 0; x < 256; x++) {
            for (int z = 0; z < 64; z++) sum += l.erodibleThickness(x, z);
        }
        double l2 = w.terrain.world().spec().metersPerColumn();
        return sum * l2 * l2;
    }

    @Test
    void entrainmentOfFallLayersConservesVolume() {
        MassFlowTestWorld w = ramp();
        Lahars l = new Lahars("lahar", w.terrain);
        Engine e = w.engine(l, 3);
        for (int x = 10; x < 60; x++) {
            for (int z = 20; z < 44; z++) l.addErodibleDeposit(x, z, 0.6);
        }
        double before = looseVolume(w, l);
        l.meltwater(TOP, 3, 3000);
        w.run(e, 400);

        MassFlowField.MassBudget budget = l.massBudget();
        assertTrue(budget.entrained() > 0, "fast flow entrains the fall layers");
        double after = looseVolume(w, l);
        WorldModel world = w.terrain.world();
        double area = world.spec().metersPerColumn() * world.spec().metersPerColumn();
        // Loose volume now = remaining fall + lahar deposit still in place. Everything entrained
        // (fall layers and any lahar deposit re-worked by the same flow) left the stacks:
        // entrained = before + laid down − after.
        double laidDown = w.depositVolume(l, area);
        double pending = 0; // laid down but still pooled below the 1 mm write threshold
        for (int x = 0; x < 256; x++) {
            for (int z = 0; z < 64; z++) pending += l.pendingWorldDeposit(x, z);
        }
        // tolerance: the stacks store layer tops as floats (≈8 µm near 90 m)
        assertEquals(budget.entrained(), before + laidDown - pending * area - after,
                2e-3 * budget.entrained() + 1e-2, "eroded from the stacks = entrained into the flow");
        assertTrue(Math.abs(budget.imbalance()) < 1e-6 * (budget.released() + budget.entrained()));
    }

    @Test
    void laharDepositsAreLooseAndAttributed() {
        MassFlowTestWorld w = ramp();
        Lahars l = new Lahars("lahar", w.terrain);
        WorldModel world = w.terrain.world();
        int unit = Provenance.unitFor(world, "v", 2, DepositType.LAHAR, 0, Double.NaN, 50);
        l.setUnits((type, time, temperature) -> type == DepositType.LAHAR ? unit : 0);
        Engine e = w.engine(l, 4);
        l.release(new BlockPos(110, 0, 32), 3, 2000, 15, 0.4, MassFlowEvents.Trigger.MANUAL);
        w.runUntilStill(e, l, 20 * 600);

        boolean found = false;
        for (int x = 90; x < 140 && !found; x++) {
            for (int z = 20; z < 44 && !found; z++) {
                int n = world.layerCount(x, z);
                LayerView top = world.layer(x, z, n - 1);
                if (top.unit() == unit) {
                    found = true;
                    assertTrue(top.loose(), "lahar deposits are unconsolidated");
                    UnitRecord record = world.unit(top.unit());
                    assertEquals("v", record.volcanoId());
                    assertEquals(2, record.eruptionId());
                }
            }
        }
        assertTrue(found, "lahar layer on the plain");
        assertFalse(l.erodibleThickness(110, 32) < 0);
    }
}
