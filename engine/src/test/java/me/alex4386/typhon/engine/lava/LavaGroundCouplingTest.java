package me.alex4386.typhon.engine.lava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.volcano.GroundCoupling;
import org.junit.jupiter.api.Test;

/** Lava ↔ ground model: base conduction heats the ground, lava in standing water boils it off. */
class LavaGroundCouplingTest {

    /** A ground model that records what lava hands it; a pond of standing water over x ≥ 16. */
    static final class RecordingGround implements GroundCoupling {
        final TreeMap<Long, Double> water = new TreeMap<>(); // m³ per column
        final List<String> calls = new ArrayList<>();
        double groundHeat;
        double removed;
        final double cellArea;

        RecordingGround(double pondDepth, double cellArea) {
            this.cellArea = cellArea;
            for (int x = 16; x < 48; x++) {
                for (int z = 0; z < 32; z++) water.put(key(x, z), pondDepth * cellArea);
            }
        }

        static long key(int x, int z) {
            return ((long) x << 32) | (z & 0xffffffffL);
        }

        @Override
        public double surfaceWaterDepthM(int x, int z) {
            return water.getOrDefault(key(x, z), 0.0) / cellArea;
        }

        @Override
        public double removeSurfaceWater(int x, int z, double volumeM3) {
            double have = water.getOrDefault(key(x, z), 0.0);
            double take = Math.min(have, volumeM3);
            if (have > 0) water.put(key(x, z), have - take);
            removed += take;
            calls.add("w" + x + "," + z + "=" + take);
            return take;
        }

        @Override
        public void addGroundHeat(int x, int z, double joules) {
            groundHeat += joules;
            calls.add("h" + x + "," + z + "=" + joules);
        }

        @Override
        public void addIntrusionHeat(double x, double z, double depthM, double areaM2, double widthM, double t) {}

        double water() {
            double sum = 0;
            for (double v : water.values()) sum += v;
            return sum;
        }
    }

    private static RecordingGround run(int threads, double seconds, LavaSource source) {
        LavaTestWorld world = new LavaTestWorld(0, 0, 2, 1, (x, z) -> 64);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults());
        RecordingGround ground = new RecordingGround(2.0, lava.metersPerBlock() * lava.metersPerBlock());
        lava.setGround(ground);
        lava.addSource(source);
        Engine engine = world.engine(lava, 7, threads);
        world.run(engine, (int) Math.round(seconds / 0.05));
        return ground;
    }

    @Test
    void coolingLavaHeatsTheGroundUnderIt() {
        RecordingGround ground = run(1, 30,
                LavaSource.at("dry", new me.alex4386.typhon.engine.math.BlockPos(4, 65, 8), 0.5, 1150, 50, 0.2));
        assertTrue(ground.groundHeat > 0, "base conduction should reach the ground");
        // k ΔT / δ over the flow area for 30 s: order 10⁵–10⁸ J for a few m³ of lava
        assertTrue(ground.groundHeat < 1e10, "heat bounded: " + ground.groundHeat);
        assertEquals(0, ground.removed, 1e-12, "no water on the dry side");
    }

    @Test
    void lavaInAPondBoilsTheWaterOff() {
        RecordingGround ground = run(1, 30,
                LavaSource.at("pond", new me.alex4386.typhon.engine.math.BlockPos(24, 65, 8), 2.0, 1150, 50, 0.2));
        assertTrue(ground.removed > 0, "lava quenched in standing water should boil some of it off");
        // energy bound: the lava's whole heat content could boil at most ~ (c ΔT + L)·ρ_l / (L_v ρ_w) per m³
        double emitted = 2.0 * 30;
        assertTrue(ground.removed < emitted * 3, "boiled " + ground.removed + " m³ from " + emitted + " m³ of lava");
    }

    @Test
    void exchangeDoesNotDependOnTheThreadCount() {
        LavaSource source = LavaSource.at("pond", new me.alex4386.typhon.engine.math.BlockPos(14, 65, 8), 3.0, 1150, 50, 0.2);
        RecordingGround one = run(1, 15, source);
        RecordingGround four = run(4, 15, source);
        assertEquals(one.calls, four.calls);
    }
}
