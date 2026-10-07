package me.alex4386.typhon.engine.lava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.lava.LavaEvents.ChunkCoord;
import me.alex4386.typhon.engine.lava.LavaTestWorld.FixedRheology;
import me.alex4386.typhon.engine.math.ColumnIndex;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.WorldModel;
import org.junit.jupiter.api.Test;
import me.alex4386.typhon.engine.testing.Saves;
import me.alex4386.typhon.engine.save.InMemorySaveStore;

class LavaFlowTest {
    private static final double BASALT_T = 1150;
    private static final double BASALT_SI = 50;

    /** Inclined plane falling toward +x by 1 m every {@code run} metres. */
    private static LavaTestWorld ramp(int run) {
        return new LavaTestWorld(-1, -2, 6, 1, (x, z) -> 120 - (double) x / run);
    }

    /** Material of the topmost layer of a column. */
    private static Material top(LavaTestWorld world, int x, int z) {
        WorldModel model = world.terrain.world();
        return model.layer(x, z, model.layerCount(x, z) - 1).materialInfo();
    }

    @Test
    void conservesVolume() {
        LavaTestWorld world = ramp(3);
        world.coarse(5);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(200));
        Engine engine = world.engine(lava, 1);
        lava.addSource(LavaSource.at("vent", new ColumnIndex(0, 0), 3, BASALT_T, BASALT_SI, 0.1));

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
            return 80 - x / 2.0;
        });
        world.coarse(10);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(0));
        Engine engine = world.engine(lava, 2);
        lava.addSource(LavaSource.at("vent", new ColumnIndex(0, 0), 2, BASALT_T, BASALT_SI, 0.1));
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
        // tau = 1e4 Pa ⇒ h_cr = tau / (rho g sinθ) ≥ 0.39 m. A 0.45 m column next to a 1 m drop
        // (sinθ ≈ 0.82 ⇒ h_cr ≈ 0.48) must hold; next to a 4 m drop (h_cr ≈ 0.40) it must flow.
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
        LavaConfig config = new LavaConfig(0, 2600, 1150, 4e5, 0.95, 25, 15, 3000, 2, 2.4e6,
                0.05, 0.2, 1, 20, 32);
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
        lava.addSource(LavaSource.at("vent", new ColumnIndex(0, 0), 2, temperature, silica, 0.1));
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

    private static int ticksToSolidify(double waterZ) {
        // Single-column pit so the lava cannot spread.
        LavaTestWorld world = new LavaTestWorld(-1, -1, 0, 0,
                (x, z) -> x == 0 && z == 0 ? 60 : 100, (x, z) -> waterZ);
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
        double surface = world.terrain.world().surfaceZ(0, 0);
        assertTrue(surface > 60.5, "pillows raise the ground: " + surface);
        assertEquals(MaterialTable.BASALT, top(world, 0, 0), "pillow basalt");
    }

    @Test
    void solidificationRaisesTerrainWithColumnarBasalt() {
        LavaTestWorld world = new LavaTestWorld(-1, -1, 0, 0, (x, z) -> x == 0 && z == 0 ? 60 : 100);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(200));
        Engine engine = world.engine(lava, 8);
        lava.addLava(0, 0, 3, BASALT_T, BASALT_SI, 0.1);

        world.run(engine, 1);
        assertTrue(lava.thickness(0, 0) > 2, "molten lava stands in the pit");

        for (int t = 0; t < 200_000 && lava.totalLavaVolume() > 0; t++) world.run(engine, 1);
        assertEquals(0, lava.totalLavaVolume());
        assertEquals(MaterialTable.BASALT, top(world, 0, 0), "basalt on top");
        // the pit is filled continuously: the surface is the top of the frozen 3 m³ sheet over a 1 m² column
        assertEquals(63, world.terrain.world().surfaceZ(0, 0), 0.15);
    }

    @Test
    void rockFollowsCompositionAndCooling() {
        assertEquals(MaterialTable.OBSIDIAN, LavaRocks.rockMaterial(72, false, true));
        assertEquals(MaterialTable.OBSIDIAN, LavaRocks.rockMaterial(72, true, false));
        assertEquals(MaterialTable.RHYOLITE, LavaRocks.rockMaterial(72, false, false));
        assertEquals(MaterialTable.BASALT, LavaRocks.rockMaterial(50, true, true));
        assertEquals(MaterialTable.ANDESITE, LavaRocks.rockMaterial(60, false, false));
        assertEquals(MaterialTable.DACITE, LavaRocks.rockMaterial(66, false, false));
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
        lava.addSource(LavaSource.atVent(VentSite.crater("summit", new Point3(0.5, 120, 0.5), 4), LavaTestWorld.COLUMN_M, 2, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, ticks);
        return world.frames;
    }

    @Test
    void savedStateResumesBitForBit() {
        LavaConfig config = LavaConfig.defaults().withCoolingScale(200);
        LavaSource source = LavaSource.atVent(VentSite.fissure("rift", new Point3(0.5, 120, 0.5), 0.3, 9, 0.5), LavaTestWorld.COLUMN_M, 2, BASALT_T, BASALT_SI, 0.1);

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
        lava.addSource(LavaSource.at("edge", new ColumnIndex(15, 8), 1, BASALT_T, BASALT_SI, 0.1));
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
        lava.addSource(LavaSource.at("vent", new ColumnIndex(0, 0), 2, BASALT_T, BASALT_SI, 0.1));
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
        lava.addSource(LavaSource.at("vent", new ColumnIndex(0, 0), 2, BASALT_T, BASALT_SI, 0.1));
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
        engine.submit(new LavaCommands.StartEffusion(LavaSource.at("vent", new ColumnIndex(0, 0), 1, BASALT_T, BASALT_SI, 0.1)));
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
    void everythingSolidifiesToRock() {
        // Once everything solidified no melt is left and every lava deposit is rock.
        LavaTestWorld world = ramp(3);
        world.coarse(5);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withCoolingScale(300));
        Engine engine = world.engine(lava, 14);
        lava.addSource(LavaSource.at("vent", new ColumnIndex(0, 0), 2, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, 100);
        lava.removeSource("vent");
        for (int t = 0; t < 100_000 && lava.activeCellCount() > 0; t++) world.run(engine, 1);
        assertEquals(0, lava.activeCellCount());

        WorldModel model = world.terrain.world();
        for (int x = -32; x < 48; x++) {
            for (int z = -32; z < 32; z++) {
                assertEquals(0, lava.thickness(x, z), "melt left at " + x + "," + z);
                if (!model.isKnown(x, z)) continue;
                assertTrue(top(world, x, z).solid(), "solid surface at " + x + "," + z);
            }
        }
    }
}
