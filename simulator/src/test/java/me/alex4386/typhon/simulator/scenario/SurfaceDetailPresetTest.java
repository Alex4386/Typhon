package me.alex4386.typhon.simulator.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.world.SurfaceDetail;
import me.alex4386.typhon.engine.world.WorldModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The crater-resolving fine surface every preset gets around its main vent. */
class SurfaceDetailPresetTest {
    @ParameterizedTest
    @ValueSource(strings = {"stromboli", "kilauea", "stromboli-real", "st-helens-real"})
    void craterIsResolvedAndConsistentWithTheColumns(String name) {
        Scenario s = Presets.get(name).build(1);
        for (int i = 0; i < 40; i++) s.engine().step(); // import the terrain, reconcile once
        SurfaceDetail d = s.volcano().surfaceDetail();
        assertNotNull(d, name + " has a fine surface");
        WorldModel world = s.terrain().world();
        double cell = d.cellMeters();
        assertTrue(cell >= 1 && cell <= 5, "crater-resolving cells: " + cell + " m");
        BlockPos vent = s.volcano().vents().get(0).block(s.world().spec().metersPerColumn());
        int r = d.refinement();
        // Every detailed column averages to its column surface: no volume, no seams.
        for (int z = vent.z() - 6; z <= vent.z() + 6; z++) {
            for (int x = vent.x() - 6; x <= vent.x() + 6; x++) {
                assertEquals(world.surfaceZ(x, z), d.columnMean(x, z), 1e-3, name + " column " + x + "," + z);
            }
        }
        // The vent's crater shows: the fine floor lies well below the surrounding rim.
        double floor = Double.POSITIVE_INFINITY;
        double rim = Double.NEGATIVE_INFINITY;
        int radius = Math.max(2, s.volcano().vents().get(0).craterRadius());
        for (int fz = (vent.z() - 2 * radius) * r; fz < (vent.z() + 2 * radius + 1) * r; fz++) {
            for (int fx = (vent.x() - 2 * radius) * r; fx < (vent.x() + 2 * radius + 1) * r; fx++) {
                double e = d.elevation(fx, fz);
                if (Double.isNaN(e)) continue;
                double dist = Math.hypot((fx + 0.5) / r - (vent.x() + 0.5), (fz + 0.5) / r - (vent.z() + 0.5));
                if (dist < 0.5 * radius) floor = Math.min(floor, e);
                rim = Math.max(rim, e);
            }
        }
        double size = world.spec().metersPerColumn();
        assertTrue(rim - floor > size, name + ": crater relief " + (rim - floor) + " m at " + size + " m columns");
    }

    @Test
    void detailFollowsDepositsConservatively() {
        Scenario s = Presets.get("stromboli").build(1);
        for (int i = 0; i < 40; i++) s.engine().step();
        SurfaceDetail d = s.volcano().surfaceDetail();
        BlockPos vent = s.volcano().vents().get(0).block(s.world().spec().metersPerColumn());
        WorldModel world = s.terrain().world();
        world.deposit(vent.x(), vent.z(), 3.0, me.alex4386.typhon.engine.world.MaterialTable.BASALT, 0);
        for (int i = 0; i < 40; i++) s.engine().step();
        assertEquals(world.surfaceZ(vent.x(), vent.z()), d.columnMean(vent.x(), vent.z()), 1e-3);
    }
}
