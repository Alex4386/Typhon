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

    /** Largest excess of any column over a neighbour beyond its repose limit and tolerance (m; ≤ 0: all at repose). */
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
                        worst = Math.max(worst, w.surfaceZ(x, z) - w.surfaceZ(x + dx, z + dz) - r.limit(x, z, d)
                                - ReposeRelaxation.toleranceM(d));
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
        assertTrue(worstExcess(w) <= 1e-4, "no slope above repose: " + worstExcess(w));
        assertEquals(300 * L * L, volume(w, 0), 1e-3 * 300 * L * L, "volume conserved");
        assertTrue(w.surfaceZ(0, 0) < 0.25 * 300, "no tower left: " + w.surfaceZ(0, 0));
        double expectedRim = Math.tan(Math.toRadians(34)) * L;
        assertTrue(w.surfaceZ(0, 0) - w.surfaceZ(1, 0) <= expectedRim + ReposeRelaxation.toleranceM(L) + 1e-4,
                "column vs neighbour ≤ L·(tan φ + tolerance)");
    }

    @Test
    void repeatedLoadsOnTheSameColumnStayAtRepose() {
        WorldModel w = flat(0);
        for (int i = 0; i < 40; i++) {
            w.deposit(0, 0, 8, i % 2 == 0 ? MaterialTable.SCORIA : MaterialTable.ASH, 1 + i, LayerFlags.LOOSE, 0.45, 0);
            assertTrue(worstExcess(w) <= 1e-4, "after load " + i + ": " + worstExcess(w));
        }
        assertEquals(320 * L * L, volume(w, 0), 1e-3 * 320 * L * L);
    }

    @Test
    void deferredLoadsRelaxOnceTheSweepEnds() {
        WorldModel w = flat(0);
        w.withRelaxationDeferred(() -> {
            for (int i = 0; i < 40; i++) {
                w.deposit(i % 3, 0, 8, MaterialTable.ASH, 1 + i, LayerFlags.LOOSE, 0.45, 0);
            }
            // nothing cascades inside the sweep
            assertTrue(w.surfaceZ(0, 0) >= 14 * 8 - 1e-6, "still a tower: " + w.surfaceZ(0, 0));
            assertEquals(0, w.surfaceZ(5, 0), 1e-9);
        });
        assertTrue(worstExcess(w) <= 1e-4, "at repose after the sweep: " + worstExcess(w));
        assertEquals(320 * L * L, volume(w, 0), 1e-3 * 320 * L * L, "volume conserved");
        assertEquals(0, w.reposeRelaxation().pending());
    }

    @Test
    void groundTiltedByDeformationSlumpsBackToRepose() {
        WorldModel w = flat(0);
        w.deposit(0, 0, 150, MaterialTable.SCORIA, 1, LayerFlags.LOOSE, 0.5, 0);
        assertTrue(worstExcess(w) <= 1e-4);
        double before = volume(w, 0);
        // inflation centred east of the cone: its west flank steepens by a fifth of the column spacing per column
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) w.setUplift(x, z, 0.2 * L * Math.max(0, x + 10));
        }
        assertTrue(worstExcess(w) <= 1e-4, "tilted slopes back at repose: " + worstExcess(w));
        double moved = 0;
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) moved += w.uplift(x, z) * L * L;
        assertEquals(before, volume(w, 0) - moved, 1e-3 * before, "deformation adds no material");
    }

    private static WorldModel sea(double floor) {
        WorldModel w = flat(floor);
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) w.setWaterZ(x, z, 0);
        return w;
    }

    /** Mean flank slope (°) of a cone dropped on a floor at {@code floor}: its height over its base radius. */
    private static double coneSlopeDeg(WorldModel w, double floor) {
        w.deposit(0, 0, 150, MaterialTable.ASH, 1, LayerFlags.LOOSE, 0.45, 0);
        assertTrue(worstExcess(w) <= 1e-4);
        double top = Double.NEGATIVE_INFINITY;
        double base = 0;
        for (int x = -R; x <= R; x++) {
            for (int z = -R; z <= R; z++) {
                top = Math.max(top, w.surfaceZ(x, z));
                if (w.surfaceZ(x, z) > floor + 0.5) base = Math.max(base, Math.hypot(x, z) * L);
            }
        }
        return Math.toDegrees(Math.atan((top - floor) / base));
    }

    /** Repose angle (°) of a loose top at column (0, 0). */
    private static double reposeDeg(WorldModel w) {
        w.deposit(0, 0, 0.5, MaterialTable.ASH, 1, LayerFlags.LOOSE, 0.45, 0);
        return Math.toDegrees(Math.atan(w.reposeRelaxation().limit(0, 0, L) / L));
    }

    @Test
    void belowTheWaveBaseLooseTephraStandsAtAboutItsDryAngle() {
        // Grains avalanching under water stand at about their dry angle (Courrech du Pont et al. 2003;
        // Cassar et al. 2005): submergence alone does not flatten a pile.
        double dry = reposeDeg(flat(0));
        assertEquals(dry, reposeDeg(sea(-100)), 1e-9, "deep: the dry angle");
        double submerged = coneSlopeDeg(sea(-200), -200);
        double inAir = coneSlopeDeg(flat(-200), -200);
        assertEquals(inAir, submerged, 1.0, "a deep submarine cone is as steep as one in air");
    }

    @Test
    void wavesReworkShallowSlopesGentler() {
        double dry = reposeDeg(flat(0));
        double shallow = reposeDeg(sea(-2));
        double mid = reposeDeg(sea(-15));
        assertTrue(shallow < mid && mid < dry, "gentler the nearer the waves: " + shallow + " < " + mid + " < " + dry);
        assertTrue(shallow > 20 && shallow < 27, "a wave-worked apron, ~20–25° (Moore 1985): " + shallow);
        WorldModel off = sea(-2);
        off.reposeRelaxation().setWaveBaseM(0);
        assertEquals(dry, reposeDeg(off), 1e-9, "no wave base: the dry angle everywhere");
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
