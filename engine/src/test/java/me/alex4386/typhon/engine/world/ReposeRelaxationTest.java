package me.alex4386.typhon.engine.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Loose deposits never stand steeper than their angle of repose, however much lands on one column. */
class ReposeRelaxationTest {
    private static final int R = 40;
    private static final double L = 10;

    private static WorldModel flat(double surface) {
        WorldSpec spec = new WorldSpec(L, 8 * L, -2000, 0, List.of(new WorldSpec.GeologyLayer("granite", -500, 0.01)),
                "basalt", "basalt", L);
        WorldModel world = new WorldModel(spec);
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) world.importColumn(x, z, surface, MaterialTable.BASALT);
        world.enableReposeRelaxation();
        return world;
    }

    private static double volume(WorldModel w, double base) {
        double v = 0;
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) v += (w.surfaceZ(x, z) - base) * L * L;
        return v;
    }

    /** Largest excess of any column over a neighbour beyond its repose limit (m; ≤ 0 means all at or below repose). */
    private static double worstExcess(WorldModel w) {
        ReposeRelaxation r = w.reposeRelaxation();
        double worst = Double.NEGATIVE_INFINITY;
        for (int x = -R + 1; x < R; x++) {
            for (int z = -R + 1; z < R; z++) {
                if (r.looseTop(x, z) < ReposeRelaxation.MIN_MOVE_M) continue;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        double d = (dx != 0 && dz != 0) ? L * Math.sqrt(2) : L;
                        worst = Math.max(worst, w.surfaceZ(x, z) - w.surfaceZ(x + dx, z + dz) - r.limit(x, z, d));
                    }
                }
            }
        }
        return worst;
    }

    @Test
    void aHugeLoadOnOneColumnEndsUpAsAConeAtRepose() {
        WorldModel w = flat(0);
        w.deposit(0, 0, 300, MaterialTable.SCORIA, 1, LayerFlags.LOOSE, 0.5, 0);
        assertTrue(worstExcess(w) <= 2 * ReposeRelaxation.MIN_MOVE_M + 1e-4, "no slope above repose: " + worstExcess(w));
        assertEquals(300 * L * L, volume(w, 0), 1e-3 * 300 * L * L, "volume conserved");
        assertTrue(w.surfaceZ(0, 0) < 0.25 * 300, "no tower left: " + w.surfaceZ(0, 0));
        double expectedRim = Math.tan(Math.toRadians(34)) * L;
        assertTrue(w.surfaceZ(0, 0) - w.surfaceZ(1, 0) <= expectedRim + 0.01, "column vs neighbour ≤ L·tan φ");
    }

    @Test
    void repeatedLoadsOnTheSameColumnStayAtRepose() {
        WorldModel w = flat(0);
        for (int i = 0; i < 40; i++) {
            w.deposit(0, 0, 8, i % 2 == 0 ? MaterialTable.SCORIA : MaterialTable.ASH, 1 + i, LayerFlags.LOOSE, 0.45, 0);
            assertTrue(worstExcess(w) <= 2 * ReposeRelaxation.MIN_MOVE_M + 1e-4, "after load " + i + ": " + worstExcess(w));
        }
        assertEquals(320 * L * L, volume(w, 0), 1e-3 * 320 * L * L);
    }

    @Test
    void submergedDepositsStandGentler() {
        WorldModel w = flat(-200);
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) w.setWaterZ(x, z, 0);
        w.deposit(0, 0, 150, MaterialTable.ASH, 1, LayerFlags.LOOSE, 0.45, 0);
        assertTrue(worstExcess(w) <= 2 * ReposeRelaxation.MIN_MOVE_M + 1e-4);
        double slope = Math.toDegrees(Math.atan((w.surfaceZ(0, 0) - w.surfaceZ(1, 0)) / L));
        assertTrue(slope < 26, "under water about 20–25°: " + slope);
    }

    @Test
    void consolidatedRockIsLeftToItsStrength() {
        WorldModel w = flat(0);
        w.deposit(0, 0, 60, MaterialTable.BASALT, 1, 0, 0.1, 1);
        assertEquals(60, w.surfaceZ(0, 0), 1e-4, "a lava spine is not a sand pile");
    }

    @Test
    void dustingsOnDustingsDoNotStackNearZeroLayers() {
        WorldModel w = flat(0);
        int before = w.layerCount(5, 5);
        for (int i = 0; i < 400; i++) {
            w.deposit(5, 5, 1e-4, i % 2 == 0 ? MaterialTable.ASH : MaterialTable.BASALT, 7, LayerFlags.LOOSE, 0.4, 0);
        }
        assertTrue(w.layerCount(5, 5) - before <= 2, "one thin loose layer, not hundreds: " + (w.layerCount(5, 5) - before));
        assertEquals(0.04, w.surfaceZ(5, 5), 1e-3);
    }
}
