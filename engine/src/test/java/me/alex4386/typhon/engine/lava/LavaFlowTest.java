package me.alex4386.typhon.engine.lava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.alex4386.typhon.engine.lava.LavaEvents.ChunkCoord;
import me.alex4386.typhon.engine.lava.LavaTestWorld.FixedRheology;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.random.SimRandom;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.BlockState;
import org.junit.jupiter.api.Test;
import me.alex4386.typhon.engine.testing.Saves;
import me.alex4386.typhon.engine.save.InMemorySaveStore;

class LavaFlowTest {
    private static final double BASALT_T = 1150;
    private static final double BASALT_SI = 50;

    /** Inclined plane falling toward +x by one block every {@code run} blocks. */
    private static LavaTestWorld ramp(int run) {
        return new LavaTestWorld(-1, -2, 6, 1, (x, z) -> 120 - Math.floorDiv(x, run));
    }

    @Test
    void conservesVolume() {
        LavaTestWorld world = ramp(3);
        world.coarse(5);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(200));
        Engine engine = world.engine(lava, 1);
        lava.addSource(LavaSource.at("vent", new BlockPos(0, 0, 0), 3, BASALT_T, BASALT_SI, 0.1));

        for (int i = 0; i < 400; i++) {
            if (i == 200) lava.removeSource("vent");
            world.run(engine, 1);
            // Crust is a third reservoir: melt that froze onto the roof but has not left the flow.
            double accounted = lava.totalLavaVolume() + lava.crustVolume() + lava.solidifiedVolume();
            assertEquals(lava.emittedVolume(), accounted, 1e-9 * Math.max(1, lava.emittedVolume()), "tick " + i);
        }
        assertEquals(3 * 200 * 0.05 * 5, lava.emittedVolume(), 1e-9);
        assertTrue(lava.solidifiedVolume() > 0, "some lava should have solidified");
    }

    @Test
    void flowsDownhillAndPoolsInBasin() {
        // Ramp falling toward +x into a walled basin at x ∈ [30, 39], z ∈ [-5, 5].
        LavaTestWorld world = new LavaTestWorld(-1, -1, 3, 0, (x, z) -> {
            boolean inBasin = x >= 30 && x <= 39 && z >= -5 && z <= 5;
            if (inBasin) return 60;
            if (x >= 30) return 90; // rim/walls around and beyond the basin
            return 80 - x / 2;
        });
        world.coarse(10);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(0));
        Engine engine = world.engine(lava, 2);
        lava.addSource(LavaSource.at("vent", new BlockPos(0, 0, 0), 2, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, 200);
        lava.removeSource("vent");
        world.run(engine, 2000);

        double basin = 0;
        for (int x = 30; x <= 39; x++) for (int z = -5; z <= 5; z++) basin += lava.thickness(x, z);
        double total = lava.totalLavaVolume();
        assertTrue(basin > 0.7 * total, "basin holds " + basin + " of " + total);
        double upslope = 0;
        for (int x = -16; x < 0; x++) for (int z = -16; z < 16; z++) upslope += lava.thickness(x, z);
        assertTrue(upslope < 0.01 * total, "upslope volume " + upslope);
    }

    @Test
    void yieldStrengthStopsFlowBelowCriticalSlope() {
        // tau = 1e4 Pa ⇒ h_cr = tau / (rho g sinθ) ≥ 0.39 m. A 0.45 m column next to a 1-block drop
        // (sinθ ≈ 0.82 ⇒ h_cr ≈ 0.48) must hold; next to a 4-block drop (h_cr ≈ 0.40) it must flow.
        assertFalse(flowsOverDrop(1));
        assertTrue(flowsOverDrop(4));
    }

    private static boolean flowsOverDrop(int drop) {
        LavaTestWorld world = new LavaTestWorld(-1, -1, 0, 0, (x, z) -> {
            if (x == 0 && z == 0) return 64;
            if (x == 1 && z == 0) return 64 - drop;
            return 100;
        });
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(0), new FixedRheology(100, 1e4));
        Engine engine = world.engine(lava, 3);
        lava.addLava(0, 0, 0.45, 1150, 50, 0);
        world.run(engine, 20);
        return lava.thickness(1, 0) > 0;
    }

    @Test
    void highYieldStrengthPilesUpWhileLowYieldSpreads() {
        int strong = footprint(5e4);
        int weak = footprint(0);
        assertTrue(strong < 30, "strong lava footprint " + strong);
        assertTrue(weak > 5 * strong, "weak " + weak + " vs strong " + strong);
    }

    private static int footprint(double yieldStrength) {
        LavaTestWorld world = new LavaTestWorld(-2, -2, 1, 1, (x, z) -> 64);
        LavaConfig config = new LavaConfig(0, 2600, 1150, 4e5, 0.95, 25, 15, 3000, 2, 0.5,
                0.05, 0.2, 0.02, 1, 2, 20, 32);
        LavaFlow lava = new LavaFlow(world.terrain, config, new FixedRheology(100, yieldStrength));
        Engine engine = world.engine(lava, 4);
        lava.addLava(0, 0, 20, 1150, 50, 0);
        world.run(engine, 600);
        assertEquals(20, lava.totalLavaVolume(), 1e-9);
        return lava.activeCellCount();
    }

    @Test
    void basaltTravelsFartherThanDacite() {
        double basalt = runout(1150, 50);
        double dacite = runout(950, 65);
        // Qualitative only: the exact ratio is a tuning choice, not a physical requirement.
        assertTrue(basalt > dacite, "basalt runout " + basalt + " vs dacite " + dacite);
    }

    private static double runout(double temperature, double silica) {
        LavaTestWorld world = ramp(4);
        world.coarse(10);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(50));
        Engine engine = world.engine(lava, 5);
        lava.addSource(LavaSource.at("vent", new BlockPos(0, 0, 0), 2, temperature, silica, 0.1));
        double farthest = 0;
        for (int i = 0; i < 1500; i++) {
            if (i == 100) lava.removeSource("vent");
            world.run(engine, 1);
            farthest = Math.max(farthest, LavaTestWorld.maxX(lava, -16, 111, -32, 31));
        }
        return farthest;
    }

    @Test
    void submergedLavaQuenchesMuchFaster() {
        int dry = ticksToSolidify(LavaTestWorld.NO_WATER);
        int wet = ticksToSolidify(70);
        assertTrue(wet * 5 < dry, "submerged " + wet + " ticks vs dry " + dry);
    }

    private static int ticksToSolidify(int waterY) {
        // Single-column pit so the lava cannot spread.
        LavaTestWorld world = new LavaTestWorld(-1, -1, 0, 0,
                (x, z) -> x == 0 && z == 0 ? 60 : 100, (x, z) -> waterY);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(20));
        Engine engine = world.engine(lava, 6);
        lava.addLava(0, 0, 1, BASALT_T, BASALT_SI, 0.1);
        for (int t = 1; t <= 200_000; t++) {
            world.run(engine, 1);
            if (lava.totalLavaVolume() == 0) return t;
        }
        throw new AssertionError("never solidified");
    }

    @Test
    void submergedBasaltFormsPillowRock() {
        LavaTestWorld world = new LavaTestWorld(-1, -1, 0, 0,
                (x, z) -> x == 0 && z == 0 ? 60 : 100, (x, z) -> 70);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(20));
        Engine engine = world.engine(lava, 7);
        lava.addLava(0, 0, 1, BASALT_T, BASALT_SI, 0.1);
        world.run(engine, 20_000);
        BlockState rock = world.appliedBlocks().get(new BlockPos(0, 61, 0));
        assertNotNull(rock);
        assertTrue(rock.id().equals(BlockId.minecraft("smooth_basalt")) || rock.id().equals(BlockId.minecraft("tuff")),
                "pillow rock was " + rock);
    }

    @Test
    void solidificationRaisesTerrainWithColumnarBasalt() {
        LavaTestWorld world = new LavaTestWorld(-1, -1, 0, 0, (x, z) -> x == 0 && z == 0 ? 60 : 100);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(200));
        Engine engine = world.engine(lava, 8);
        lava.addLava(0, 0, 3, BASALT_T, BASALT_SI, 0.1);

        world.run(engine, 1);
        Map<BlockPos, BlockState> early = world.appliedBlocks();
        assertEquals("minecraft:lava", early.get(new BlockPos(0, 63, 0)).id().toString(), "lava rendered while molten");

        for (int t = 0; t < 200_000 && lava.totalLavaVolume() > 0; t++) world.run(engine, 1);
        assertEquals(0, lava.totalLavaVolume());
        assertEquals(63, world.terrain.column(0, 0).groundY());
        assertEquals(0, lava.partialSolid(0, 0), 1e-6);

        Map<BlockPos, BlockState> blocks = world.appliedBlocks();
        BlockState columnar = BlockState.minecraft("basalt").with("axis", "y");
        for (int y = 61; y <= 63; y++) assertEquals(columnar, blocks.get(new BlockPos(0, y, 0)), "y=" + y);
        BlockState above = blocks.get(new BlockPos(0, 64, 0));
        assertTrue(above == null || above.id().equals(BlockId.AIR), "no lava left above: " + above);
    }

    @Test
    void paletteChoosesRockByCompositionAndCooling() {
        SimRandom random = new SimRandom(1);
        for (int i = 0; i < 50; i++) {
            assertTrue(LavaPalette.rock(72, false, true, false, random).id().path().contains("obsidian"));
            assertTrue(LavaPalette.rock(72, true, false, false, random).id().path().contains("obsidian"));
            assertFalse(LavaPalette.rock(72, false, false, false, random).id().path().contains("obsidian"));
            assertEquals(BlockState.minecraft("basalt").with("axis", "y"), LavaPalette.rock(50, false, false, true, random));
            String pillow = LavaPalette.rock(50, true, true, false, random).id().path();
            assertTrue(pillow.equals("smooth_basalt") || pillow.equals("tuff"), pillow);
            String andesite = LavaPalette.rock(60, false, false, false, random).id().path();
            assertTrue(andesite.equals("andesite") || andesite.equals("tuff"), andesite);
        }
    }

    @Test
    void sameSeedSameFrames() {
        assertEquals(scenario(42, 300), scenario(42, 300));
    }

    private static List<EngineFrame> scenario(long seed, int ticks) {
        LavaTestWorld world = ramp(3);
        world.coarse(5);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(200));
        Engine engine = world.engine(lava, seed);
        lava.addSource(LavaSource.atVent(VentSite.crater("summit", new BlockPos(0, 0, 0), 4), 2, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, ticks);
        return world.frames;
    }

    @Test
    void savedStateResumesBitForBit() {
        LavaConfig config = LavaConfig.defaults().withCoolingScale(200);
        LavaSource source = LavaSource.atVent(VentSite.fissure("rift", new BlockPos(0, 0, 0), 0.3, 9), 2, BASALT_T, BASALT_SI, 0.1);

        LavaTestWorld reference = ramp(3).coarse(5);
        LavaFlow refLava = new LavaFlow(reference.terrain, config);
        Engine refEngine = reference.engine(refLava, 99);
        refLava.addSource(source);
        reference.run(refEngine, 300);

        LavaTestWorld before = ramp(3).coarse(5);
        LavaFlow beforeLava = new LavaFlow(before.terrain, config);
        Engine beforeEngine = before.engine(beforeLava, 99);
        beforeLava.addSource(source);
        before.run(beforeEngine, 120);
        InMemorySaveStore saved = Saves.save(beforeEngine);

        TerrainModel resentTerrain = before.copyTerrain();
        LavaFlow afterLava = new LavaFlow(resentTerrain, config);
        Engine afterEngine = Engine.builder(99).baseStepMicros(before.baseStepMicros)
                .add(resentTerrain)
                .add(afterLava)
                .restore(saved)
                .build();
        List<EngineFrame> resumed = new ArrayList<>();
        for (int i = 0; i < 180; i++) resumed.add(afterEngine.step());

        assertEquals(reference.frames.subList(120, 300), resumed);
        assertEquals(refLava.emittedVolume(), afterLava.emittedVolume());
        assertEquals(refLava.solidifiedVolume(), afterLava.solidifiedVolume());
    }

    @Test
    void unknownTerrainActsAsWallAndIsRequested() {
        // Only chunk (0,0) is known; the source sits at its +x edge on a slope falling toward +x.
        LavaTestWorld world = new LavaTestWorld(0, 0, 0, 0, (x, z) -> 100 - x);
        world.coarse(5);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(0));
        Engine engine = world.engine(lava, 10);
        lava.addSource(LavaSource.at("edge", new BlockPos(15, 0, 8), 1, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, 100);

        assertEquals(0, lava.thickness(16, 8));
        List<LavaEvents.TerrainNeeded> requests = world.events(LavaEvents.TerrainNeeded.class);
        List<ChunkCoord> requested = new ArrayList<>();
        for (LavaEvents.TerrainNeeded r : requests) requested.addAll(r.chunks());
        assertEquals(requested.size(), new java.util.HashSet<>(requested).size(), "each chunk requested once");
        assertTrue(requested.contains(new ChunkCoord(1, 0)));
        assertEquals(lava.emittedVolume(), lava.totalLavaVolume() + lava.solidifiedVolume(), 1e-9);
    }

    @Test
    void lavaEnteringWaterIsReported() {
        // Ramp falling toward +x; the sea starts at x >= 10.
        LavaTestWorld world = new LavaTestWorld(-1, -1, 1, 0, (x, z) -> 80 - x, (x, z) -> x >= 10 ? 75 : LavaTestWorld.NO_WATER);
        world.coarse(10);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(0));
        Engine engine = world.engine(lava, 11);
        lava.addSource(LavaSource.at("vent", new BlockPos(0, 0, 0), 2, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, 400);
        List<LavaEvents.LavaOceanEntry> entries = world.events(LavaEvents.LavaOceanEntry.class);
        assertFalse(entries.isEmpty());
        assertTrue(entries.stream().allMatch(e -> e.pos().x() >= 10));
    }

    @Test
    void frontEventsTrackRunout() {
        LavaTestWorld world = ramp(3);
        world.coarse(5);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(0));
        Engine engine = world.engine(lava, 12);
        lava.addSource(LavaSource.at("vent", new BlockPos(0, 0, 0), 2, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, 400);
        List<LavaEvents.LavaFlowFront> fronts = world.events(LavaEvents.LavaFlowFront.class);
        assertTrue(fronts.size() >= 19);
        LavaEvents.LavaFlowFront last = fronts.get(fronts.size() - 1);
        assertTrue(last.lengthM() > fronts.get(0).lengthM());
        assertTrue(last.front().x() > 0, "front should be downslope");
    }

    @Test
    void commandsControlEffusion() {
        LavaTestWorld world = ramp(3);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(0));
        Engine engine = world.engine(lava, 13);
        engine.submit(new LavaCommands.StartEffusion(LavaSource.at("vent", new BlockPos(0, 0, 0), 1, BASALT_T, BASALT_SI, 0.1)));
        world.run(engine, 20);
        assertEquals(1.0, lava.emittedVolume(), 1e-9);
        engine.submit(new LavaCommands.SetEffusionRate("vent", 2));
        world.run(engine, 20);
        assertEquals(3.0, lava.emittedVolume(), 1e-9);
        engine.submit(new LavaCommands.StopEffusion("vent"));
        world.run(engine, 20);
        assertEquals(3.0, lava.emittedVolume(), 1e-9);
    }

    @Test
    void renderedBlocksMatchFinalState() {
        // Every lava block ever placed must be removed or replaced by rock once everything solidified.
        LavaTestWorld world = ramp(3);
        world.coarse(5);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(300));
        Engine engine = world.engine(lava, 14);
        lava.addSource(LavaSource.at("vent", new BlockPos(0, 0, 0), 2, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, 100);
        lava.removeSource("vent");
        for (int t = 0; t < 100_000 && lava.activeCellCount() > 0; t++) world.run(engine, 1);
        assertEquals(0, lava.activeCellCount());

        for (Map.Entry<BlockPos, BlockState> e : world.appliedBlocks().entrySet()) {
            String id = e.getValue().id().toString();
            assertFalse(id.equals("minecraft:lava") || id.equals("minecraft:magma_block"), "leftover " + id + " at " + e.getKey());
            BlockPos p = e.getKey();
            if (!id.equals("minecraft:air") && !id.equals("minecraft:water")) {
                assertTrue(p.y() <= world.terrain.column(p.x(), p.z()).groundY(), "rock above ground at " + p);
            }
        }
        // Thin edges render as partial lava levels at some point.
        boolean sawPartialLevel = false;
        for (EngineFrame f : world.frames) {
            for (BlockChange c : f.blockChanges()) {
                if (c.to().id().path().equals("lava") && !"0".equals(c.to().property("level"))) sawPartialLevel = true;
            }
        }
        assertTrue(sawPartialLevel);
    }
}
