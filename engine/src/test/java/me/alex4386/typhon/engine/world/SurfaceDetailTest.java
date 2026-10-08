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
        WorldModel world = new WorldModel(WorldSpec.withColumns(L));
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
    void depositsOnASlopeDrapeItInsteadOfTerracingIt() {
        // a uniform 31° flank (tan = 0.6) rising to the east, no relief: the detail starts as the bilinear slope
        double tan = 0.6;
        WorldModel world = new WorldModel(WorldSpec.withColumns(L));
        for (int z = 0; z < 9; z++) {
            for (int x = 0; x < 9; x++) world.importColumn(x, z, 100 + tan * L * x, MaterialTable.BASALT);
        }
        SurfaceDetail d = new SurfaceDetail(world, 0, 0, 9, R, null);
        d.reconcileAll();
        double before = total(d);
        for (int k = 0; k < 20; k++) {
            for (int z = 0; z < 9; z++) {
                for (int x = 0; x < 9; x++) world.deposit(x, z, 1.0, MaterialTable.ASH, 0, LayerFlags.LOOSE, 0.4, 0);
            }
            d.reconcileAll();
        }
        assertEquals(before + 20.0 * 81 * R * R, total(d), 1e-1, "volume conserved");
        // every interior column still rises to the east by tan·L/R per cell: no flat steps between columns
        for (int x = 1; x < 8; x++) {
            assertEquals(world.surfaceZ(x, 4), d.columnMean(x, 4), 1e-3);
            for (int i = 1; i < R; i++) {
                double rise = d.elevation(x * R + i, 4 * R + 1) - d.elevation(x * R + i - 1, 4 * R + 1);
                assertEquals(tan * L / R, rise, 0.05, "column " + x + " cell " + i + " keeps the slope");
            }
        }
    }

    @Test
    void uniformDeformationMovesTheDetailAsAWhole() {
        WorldModel world = world();
        SurfaceDetail d = new SurfaceDetail(world, 0, 0, 9, R, SurfaceDetailTest::bowl);
        d.reconcileAll();
        double[] before = new double[81 * R * R];
        for (int k = 0; k < before.length; k++) before[k] = d.elevation(k % (9 * R), k / (9 * R));
        for (int z = 0; z < 9; z++) for (int x = 0; x < 9; x++) world.setUplift(x, z, 1.5);
        d.reconcileAll();
        for (int k = 0; k < before.length; k++) {
            assertEquals(before[k] + 1.5, d.elevation(k % (9 * R), k / (9 * R)), 1e-4,
                    "the crater keeps its shape: deformation is not a deposit filling its hollows");
        }
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
    void erosionCutsWhatStandsAboveTheSmoothSurfaceFirst() {
        WorldModel world = new WorldModel(WorldSpec.withColumns(L));
        for (int z = 0; z < 9; z++) {
            for (int x = 0; x < 9; x++) world.importColumn(x, z, 100, MaterialTable.BASALT);
        }
        SurfaceDetail d = new SurfaceDetail(world, 0, 0, 9, R, null);
        d.reconcileAll();
        // a 1.6 m mound on one cell of column (4, 4); the column rises by 1.6 / R² = 0.1 m
        assertTrue(d.raise(4 * R + 1, 4 * R + 1, 1.6, MaterialTable.SCORIA, 0));
        double mound = d.elevation(4 * R + 1, 4 * R + 1);
        double flat = d.elevation(4 * R + 3, 4 * R + 3);
        world.erode(4, 4, 0.05, false); // half the mound's volume
        d.reconcileAll();
        // (within the base's own small change under the lowered column)
        assertEquals(mound - 0.8, d.elevation(4 * R + 1, 4 * R + 1), 0.02, "the mound is cut first");
        assertEquals(flat, d.elevation(4 * R + 3, 4 * R + 3), 0.02, "the flat ground is (nearly) untouched");
        assertEquals(world.surfaceZ(4, 4), d.columnMean(4, 4), 1e-4);
    }

    @Test
    void stateRoundTrips() {
        WorldModel world = world();
        SurfaceDetail d = new SurfaceDetail(world, 0, 0, 9, R, SurfaceDetailTest::bowl);
        d.reconcileAll();
        d.lower(5, 5, 1.0);
        SurfaceDetail e = new SurfaceDetail(world, 0, 0, 9, R, null);
        e.restore(d.residuals().clone(), d.seenSurfaces().clone(), d.seenVersions().clone());
        for (int fz = 0; fz < 9 * R; fz++) {
            for (int fx = 0; fx < 9 * R; fx++) assertEquals(d.elevation(fx, fz), e.elevation(fx, fz));
        }
    }
}
