package me.alex4386.typhon.engine.lava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.sim.Engine;
import org.junit.jupiter.api.Test;

/** Geometric scaling (real units on an L-metre grid) and grid isotropy of {@link LavaFlow}. */
class LavaScalingTest {
    private static final double BASALT_T = 1150;
    private static final double BASALT_SI = 50;

    @Test
    void realVolumeFillsBlocksOfLMetres() {
        // Single-column pit at L = 2 m: 16 m³ over 4 m² is 4 m of lava = 2 blocks of rock.
        LavaTestWorld world = new LavaTestWorld(-1, -1, 0, 0, (x, z) -> x == 0 && z == 0 ? 60 : 100);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withMetersPerBlock(2).withCoolingScale(500));
        Engine engine = world.engine(lava, 1);
        assertTrue(lava.addLava(0, 0, 16, BASALT_T, BASALT_SI, 0.1));
        assertEquals(4.0, lava.thickness(0, 0), 1e-12);
        assertEquals(16.0, lava.totalLavaVolume(), 1e-9);
        assertEquals(2.0, lava.toBlocks(16), 1e-12);

        for (int t = 0; t < 200_000 && lava.totalLavaVolume() > 0; t++) world.run(engine, 1);
        assertEquals(0, lava.totalLavaVolume());
        assertEquals(16.0, lava.solidifiedVolume(), 1e-9);
        assertEquals(62, world.terrain.column(0, 0).groundY(), "two 2-m blocks of rock");
    }

    @Test
    void runoutInMetresDoesNotDependOnGridScale() {
        // Same real slope (1:4) and same real eruption on 1 m and 2 m grids.
        double fine = realRunout(1);
        double coarse = realRunout(2);
        assertTrue(fine > 20, "flow should travel: " + fine);
        double ratio = coarse / fine;
        assertTrue(ratio > 0.7 && ratio < 1.4, "runout " + fine + " m at L=1 vs " + coarse + " m at L=2");
    }

    private static double realRunout(double metersPerBlock) {
        int run = 4; // one block down per four blocks along x: slope 1:4 at any L
        LavaTestWorld world = new LavaTestWorld(-1, -2, 6, 1, (x, z) -> 150 - Math.floorDiv(x, run));
        LavaConfig config = LavaConfig.defaults().withMetersPerBlock(metersPerBlock).withTimeScale(5).withCoolingScale(10);
        LavaFlow lava = new LavaFlow(world.terrain, config);
        Engine engine = world.engine(lava, 7);
        lava.addSource(LavaSource.at("vent", new BlockPos(0, 0, 0), 4, BASALT_T, BASALT_SI, 0.1));
        double farthest = 0;
        for (int i = 0; i < 1200; i++) {
            if (i == 200) lava.removeSource("vent");
            world.run(engine, 1);
            farthest = Math.max(farthest, LavaTestWorld.maxX(lava, -16, 111, -32, 31) * metersPerBlock);
        }
        return farthest;
    }

    @Test
    void pointSourceOnAConeSpreadsRadially() {
        // Cone falling one block every three blocks of radius; vent at the apex.
        LavaTestWorld world = new LavaTestWorld(-3, -3, 2, 2,
                (x, z) -> 150 - (int) Math.floor(Math.sqrt((double) x * x + (double) z * z) / 3));
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withTimeScale(5).withCoolingScale(0));
        Engine engine = world.engine(lava, 9);
        lava.addSource(LavaSource.at("vent", new BlockPos(0, 0, 0), 3, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, 600);

        double[] axis = new double[4];
        double[] diagonal = new double[4];
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                if (lava.thickness(x, z) < 0.01) continue;
                double r = Math.hypot(x, z);
                double angle = Math.toDegrees(Math.atan2(z, x));
                for (int k = 0; k < 4; k++) {
                    if (angularDistance(angle, 90 * k) <= 10) axis[k] = Math.max(axis[k], r);
                    if (angularDistance(angle, 45 + 90 * k) <= 10) diagonal[k] = Math.max(diagonal[k], r);
                }
            }
        }
        double axisMean = (axis[0] + axis[1] + axis[2] + axis[3]) / 4;
        double diagonalMean = (diagonal[0] + diagonal[1] + diagonal[2] + diagonal[3]) / 4;
        assertTrue(axisMean > 8, "flow should spread: " + axisMean);
        double ratio = diagonalMean / axisMean;
        assertTrue(ratio > 0.8 && ratio < 1.25,
                "diagonal extent " + diagonalMean + " vs axis extent " + axisMean + " (axis fingers?)");
    }

    private static double angularDistance(double a, double b) {
        double d = Math.abs(a - b) % 360;
        return d > 180 ? 360 - d : d;
    }

    @Test
    void sharedFieldRejectsAConflictingScale() {
        LavaTestWorld world = new LavaTestWorld(0, 0, 0, 0, (x, z) -> 64);
        LavaFlow lava = new LavaFlow(world.terrain);
        lava.setMetersPerBlock(4);
        assertEquals(4, lava.metersPerBlock());
        lava.setMetersPerBlock(4); // same scale from a second volcano is fine
        assertThrows(IllegalArgumentException.class, () -> lava.setMetersPerBlock(8));
    }

    @Test
    void solidificationAndOceanEntryAreAggregated() {
        LavaTestWorld world = new LavaTestWorld(-1, -1, 1, 0, (x, z) -> 80 - x,
                (x, z) -> x >= 10 ? 75 : LavaTestWorld.NO_WATER);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withTimeScale(10).withCoolingScale(5));
        Engine engine = world.engine(lava, 13);
        lava.addSource(LavaSource.at("vent", new BlockPos(0, 0, 0), 2, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, 400);

        double period = lava.config().eventPeriodSeconds();
        int interval = (int) Math.round(period * 20);
        List<LavaEvents.LavaSolidified> solid = world.events(LavaEvents.LavaSolidified.class);
        assertTrue(!solid.isEmpty() && solid.size() <= 400 / interval + 1, "solidified events: " + solid.size());
        assertTrue(solid.stream().allMatch(e -> Math.abs(e.time() / period - Math.rint(e.time() / period)) < 1e-9));

        List<LavaEvents.LavaOceanEntry> entries = world.events(LavaEvents.LavaOceanEntry.class);
        assertTrue(!entries.isEmpty(), "ocean entry should be reported");
        // at most one event per zone (chunk) per interval
        assertTrue(entries.size() <= 6 * (400 / interval + 1), "ocean entry events: " + entries.size()); // 6 chunks
        assertTrue(entries.stream().allMatch(e -> e.pos().x() >= 10 && e.powerMW() > 0));
    }
}
