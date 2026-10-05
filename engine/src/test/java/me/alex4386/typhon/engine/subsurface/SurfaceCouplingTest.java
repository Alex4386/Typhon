package me.alex4386.typhon.engine.subsurface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.alex4386.typhon.engine.lava.LavaConfig;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.lava.LavaSource;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainChunk;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.terrain.TerrainSnapshot;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.WorldModel;
import org.junit.jupiter.api.Test;

/** The subsurface model coupled to lava (heat, boiling standing water) and dikes (sheet heat). */
class SurfaceCouplingTest {
    private static final int N = 48;

    /** Flat basalt plain at 10 m, columns [0, N)², 4 m columns, 16 m solver cells. */
    private static WorldModel plain() {
        return SubsurfaceTestWorld.uniform("basalt", 4, 16, Double.NaN, N, N, (x, z) -> 10);
    }

    private static SubsurfaceConfig config() {
        SubsurfaceConfig c = SubsurfaceTestWorld.config();
        c.surfaceTemperatureC = 15;
        c.gradientCPerKm = 30;
        c.initialWaterTableDepthM = 30;
        c.macroStepSeconds = 2;
        return c;
    }

    record Run(Subsurface subsurface, LavaFlow lava) {}

    private static Run run(boolean withLava, boolean pond, double seconds, int threads) {
        WorldModel world = plain();
        TerrainModel terrain = new TerrainModel(world);
        terrain.setMetersPerBlock(4);
        // the block view hosts send (the lava field reads its bed through it)
        java.util.List<TerrainChunk> chunks = new java.util.ArrayList<>();
        for (int cx = 0; cx < N / 16; cx++) {
            for (int cz = 0; cz < N / 16; cz++) {
                TerrainChunk chunk = new TerrainChunk(cx, cz);
                for (int z = cz * 16; z < cz * 16 + 16; z++) {
                    for (int x = cx * 16; x < cx * 16 + 16; x++) {
                        chunk.set(x, z, TerrainColumn.dry(2, BlockId.minecraft("basalt")));
                    }
                }
                chunks.add(chunk);
            }
        }
        terrain.apply(new TerrainSnapshot(chunks));
        Subsurface s = new Subsurface(world, config());
        LavaFlow lava = new LavaFlow(terrain, LavaConfig.defaults());
        lava.setMetersPerBlock(4);
        lava.setGround(s);
        Engine engine = Engine.builder(3).threads(threads).add(terrain).add(s).add(lava).build();
        engine.step();
        if (pond) {
            for (int z = 16; z < 32; z++) {
                for (int x = 16; x < 32; x++) s.addWater(x, z, 2.0 * 16); // 2 m of water on 4 m columns
            }
            engine.runFor(5);
        }
        if (withLava) lava.addSource(LavaSource.at("vent", new BlockPos(24, 11, 24), 8, 1150, 50, 0.2));
        engine.runFor(seconds);
        return new Run(s, lava);
    }

    @Test
    void cooledLavaWarmsTheGroundBeneathIt() {
        double withLava = run(true, false, 60, 1).subsurface().temperatureC(24, 24, 0.5);
        double without = run(false, false, 60, 1).subsurface().temperatureC(24, 24, 0.5);
        // ~0.8 m of 1150 °C lava over ~140 columns conducts k·ΔT/δ ≈ 4.6 kW/m² for a minute: a few
        // 10⁸ J into a top cell of ~7·10⁸ J/K per 16 m solver column, i.e. a rise of order 0.1 K.
        assertTrue(withLava > without + 0.05, "ground under the flow: " + withLava + " °C vs " + without + " °C");
    }

    @Test
    void lavaBoilsOffAPondAndTheWaterBudgetBalances() {
        Run r = run(true, true, 60, 1);
        WaterBudget b = r.subsurface().budget();
        assertTrue(b.removed() > 0, "lava in the pond should boil water off: " + b);
        assertEquals(0, b.imbalance(), 1e-6 * Math.max(1, b.inflow()), b.toString());
        // the lava standing in the pond is quenched: it freezes faster than on dry ground
        double wet = r.lava().solidifiedVolume();
        double dry = run(true, false, 60, 1).lava().solidifiedVolume();
        assertTrue(wet > dry, "quenched " + wet + " m³ vs " + dry + " m³ on dry ground");
    }

    @Test
    void couplingDoesNotDependOnTheThreadCount() {
        Run one = run(true, true, 30, 1);
        Run four = run(true, true, 30, 4);
        assertEquals(one.subsurface().budget(), four.subsurface().budget());
        assertEquals(one.subsurface().temperatureC(24, 24, 0.5), four.subsurface().temperatureC(24, 24, 0.5));
        assertEquals(one.lava().totalLavaVolume(), four.lava().totalLavaVolume());
    }

    @Test
    void intrudedSheetHeatsTheRockAtItsDepth() {
        Subsurface s = new Subsurface(plain(), config());
        s.prepare();
        int level = s.grid().levelAtDepth(200);
        double depth = s.levelCenterDepth(level);
        double before = s.temperatureC(24, 24, depth);
        // a 1 m thick dike segment 50 m tall along a 16 m cell at 1150 °C
        s.addIntrusionHeat(24.5, 24.5, depth, 50 * 16, 1.0, 1150);
        s.macroStep(1, 1, false);
        double after = s.temperatureC(24, 24, depth);
        assertTrue(after > before + 1, "rock beside the dike: " + before + " → " + after);
    }
}
