package me.alex4386.typhon.engine.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SurfaceDetailTest {
    private static final double L = 8;
    private static final int R = 4;

    /** A 9×9-column world (L = 8 m) holding a 20 m deep, 24 m radius bowl centred on column (4, 4). */
    private static double bowl(double xm, double zm) {
        double d = Math.hypot(xm - 4.5 * L, zm - 4.5 * L);
        return 100 - (d < 24 ? 20 * (1 - (d / 24) * (d / 24)) : 0);
    }

    private static WorldModel world() {
        WorldModel world = new WorldModel(WorldSpec.blocks(L));
        for (int z = 0; z < 9; z++) {
            for (int x = 0; x < 9; x++) {
                double mean = 0;
                for (int j = 0; j < 16; j++) {
                    for (int i = 0; i < 16; i++) mean += bowl((x + (i + 0.5) / 16) * L, (z + (j + 0.5) / 16) * L);
                }
                world.importColumn(x, z, mean / 256, MaterialTable.BASALT);
            }
        }
        return world;
    }

    private static double total(SurfaceDetail d) {
        double sum = 0;
        for (int fz = 0; fz < 9 * R; fz++) {
            for (int fx = 0; fx < 9 * R; fx++) sum += d.elevation(fx, fz);
        }
        return sum;
    }

    @Test
    void detailAveragesToTheColumnsAndShowsTheCrater() {
        WorldModel world = world();
        SurfaceDetail d = new SurfaceDetail(world, 0, 0, 9, R, SurfaceDetailTest::bowl);
        d.reconcileAll();
        for (int z = 0; z < 9; z++) {
            for (int x = 0; x < 9; x++) assertEquals(world.surfaceZ(x, z), d.columnMean(x, z), 1e-3);
        }
        // The crater floor is resolved below the coarse column: the centre column averages its slope.
        double floor = d.elevation(4 * R + R / 2, 4 * R + R / 2);
        assertTrue(floor < world.surfaceZ(4, 4) - 0.1, "detail resolves the crater floor below the column mean");
        assertEquals(80, floor, 1.0);
        assertEquals(100, d.elevation(0, 0), 1e-3, "outside the crater the detail is flat");
    }

    @Test
    void depositsFillLowCellsFirstAndConserveVolume() {
        WorldModel world = world();
        SurfaceDetail d = new SurfaceDetail(world, 0, 0, 9, R, SurfaceDetailTest::bowl);
        d.reconcileAll();
        double before = total(d);
        world.deposit(4, 4, 2.0, MaterialTable.BASALT, 0);
        d.reconcileAll();
        assertEquals(before + 2.0 * R * R, total(d), 1e-2, "the cells gain exactly the column's deposit");
        assertEquals(world.surfaceZ(4, 4), d.columnMean(4, 4), 1e-4);
        // a lava pond is flat: the lowest cells rose to a common level
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        for (int j = 1; j < R - 1; j++) {
            for (int i = 1; i < R - 1; i++) {
                double e = d.elevation(4 * R + i, 4 * R + j);
                lo = Math.min(lo, e);
                hi = Math.max(hi, e);
            }
        }
        assertEquals(lo, hi, 1e-3, "the centre of the crater ponds flat");
    }

    @Test
    void reconcilingIsPathIndependent() {
        WorldModel a = world();
        WorldModel b = world();
        SurfaceDetail da = new SurfaceDetail(a, 0, 0, 9, R, SurfaceDetailTest::bowl);
        SurfaceDetail db = new SurfaceDetail(b, 0, 0, 9, R, SurfaceDetailTest::bowl);
        da.reconcileAll();
        db.reconcileAll();
        for (int k = 0; k < 10; k++) {
            a.deposit(4, 4, 0.3, MaterialTable.BASALT, 0);
            da.reconcileAll();
        }
        b.deposit(4, 4, 3.0, MaterialTable.BASALT, 0);
        db.reconcileAll();
        for (int j = 0; j < R; j++) {
            for (int i = 0; i < R; i++) {
                assertEquals(db.elevation(4 * R + i, 4 * R + j), da.elevation(4 * R + i, 4 * R + j), 1e-3);
            }
        }
    }

    @Test
    void subColumnEditsMoveTheColumnByVolumeOverArea() {
        WorldModel world = world();
        SurfaceDetail d = new SurfaceDetail(world, 0, 0, 9, R, SurfaceDetailTest::bowl);
        d.reconcileAll();
        double column = world.surfaceZ(2, 2);
        double cell = d.elevation(2 * R + 1, 2 * R + 1);
        ErodeResult dug = d.lower(2 * R + 1, 2 * R + 1, 3.2);
        assertEquals(3.2 / (R * R), dug.removedM(), 1e-6);
        assertEquals(column - 3.2 / (R * R), world.surfaceZ(2, 2), 1e-4);
        assertEquals(cell - 3.2, d.elevation(2 * R + 1, 2 * R + 1), 1e-3, "only the dug cell went down");
        assertEquals(world.surfaceZ(2, 2), d.columnMean(2, 2), 1e-4);

        assertTrue(d.raise(2 * R + 2, 2 * R + 1, 3.2, MaterialTable.SCORIA, 0));
        assertEquals(column, world.surfaceZ(2, 2), 1e-4, "moving material within a column keeps its surface");
        assertEquals(column, d.columnMean(2, 2), 1e-4);
        // a later reconcile has nothing left to redistribute
        assertEquals(0, d.reconcileAll());
    }

    @Test
    void erosionCutsTheHighestCells() {
        WorldModel world = world();
        SurfaceDetail d = new SurfaceDetail(world, 0, 0, 9, R, SurfaceDetailTest::bowl);
        d.reconcileAll();
        double low = d.elevation(4 * R + 1, 4 * R + 1);
        world.erode(4, 4, 0.05, false);
        d.reconcileAll();
        assertEquals(low, d.elevation(4 * R + 1, 4 * R + 1), 1e-3, "the crater floor is untouched by a little erosion");
        assertEquals(world.surfaceZ(4, 4), d.columnMean(4, 4), 1e-4);
    }

    @Test
    void stateRoundTrips() {
        WorldModel world = world();
        SurfaceDetail d = new SurfaceDetail(world, 0, 0, 9, R, SurfaceDetailTest::bowl);
        d.reconcileAll();
        d.lower(5, 5, 1.0);
        SurfaceDetail e = new SurfaceDetail(world, 0, 0, 9, R, null);
        e.restore(d.cells().clone(), d.seenSurfaces().clone(), d.seenVersions().clone());
        for (int fz = 0; fz < 9 * R; fz++) {
            for (int fx = 0; fx < 9 * R; fx++) assertEquals(d.elevation(fx, fz), e.elevation(fx, fz));
        }
    }
}
