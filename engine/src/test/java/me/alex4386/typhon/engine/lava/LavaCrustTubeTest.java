package me.alex4386.typhon.engine.lava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainChunk;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.terrain.TerrainSnapshot;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.BlockState;
import org.junit.jupiter.api.Test;
import me.alex4386.typhon.engine.testing.Saves;
import me.alex4386.typhon.engine.save.InMemorySaveStore;
import me.alex4386.typhon.engine.save.SubsystemState;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.FieldChunk;

/** Crust growth, insulated (tube-fed) flow, drained lava tubes and ocean entries. */
class LavaCrustTubeTest {
    private static final double BASALT_T = 1150;
    private static final double BASALT_SI = 50;
    private static final int SEA = 72;

    // ── Crust ──

    @Test
    void pondCrustGrowsWithSquareRootOfTime() {
        // A 3 m basalt pond at physical cooling speed, 10 s per tick.
        LavaTestWorld world = new LavaTestWorld(-1, -1, 0, 0, (x, z) -> x == 0 && z == 0 ? 60 : 100);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withTimeScale(200));
        Engine engine = world.engine(lava, 1);
        lava.addLava(0, 0, 3, BASALT_T, BASALT_SI, 0.1);

        world.run(engine, 6 * 360);
        double sixHours = lava.crustThickness(0, 0);
        world.run(engine, 18 * 360);
        double oneDay = lava.crustThickness(0, 0);

        // Stefan problem: crust ∝ √t, so quadrupling the time doubles the crust.
        assertEquals(2.0, oneDay / sixHours, 0.2, "6 h " + sixHours + " m, 24 h " + oneDay + " m");
        // Kīlauea Iki field fit (Hon et al. 1994): ≈0.38 m after a day.
        assertTrue(oneDay > 0.25 && oneDay < 0.55, "crust after a day " + oneDay);
        // The insulated core barely cooled while the crust grew.
        assertTrue(lava.temperatureC(0, 0) > BASALT_T - 30, "core " + lava.temperatureC(0, 0));
        assertEquals(3, lava.thickness(0, 0) + lava.crustThickness(0, 0), 1e-9);
    }

    @Test
    void crustedFlowTravelsFartherThanOpenChannel() {
        double open = runout(false);
        double crusted = runout(true);
        assertTrue(crusted > open, "crusted runout " + crusted + " vs open " + open);
    }

    private static double runout(boolean crust) {
        // Gentle slope (≈3°): slow sheet flow, below the crust disruption speed.
        LavaTestWorld world = new LavaTestWorld(-1, -2, 12, 1, (x, z) -> 120 - Math.floorDiv(x, 20));
        LavaFlow lava = new LavaFlow(world.terrain,
                LavaConfig.defaults().toBuilder().timeScale(10).coolingScale(20).crustEnabled(crust).build());
        Engine engine = world.engine(lava, 2);
        lava.addSource(LavaSource.at("vent", new BlockPos(0, 0, 0), 0.5, BASALT_T, BASALT_SI, 0.1));
        double farthest = 0;
        for (int i = 0; i < 4000; i++) {
            world.run(engine, 1);
            if (i % 50 == 0) farthest = Math.max(farthest, LavaTestWorld.maxX(lava, -16, 207, -32, 31));
        }
        if (crust) {
            assertTrue(lava.crustedCellCount() > 0, "a quiet flow should crust over");
            assertEquals(0, lava.crustThickness(0, 0), "the vent stays open while it effuses");
        }
        return farthest;
    }

    @Test
    void fastChannelKeepsItsCrustTornUp() {
        // The slow margins and the cooling flow front may crust; the fast proximal channel must stay open.
        double torn = channelCrust(LavaConfig.defaults().crustDisruptionVelocity());
        double undisturbed = channelCrust(1e9);
        assertTrue(undisturbed > 0, "without disruption the channel would crust over");
        assertTrue(torn < 0.1 * undisturbed, "torn " + torn + " vs undisturbed " + undisturbed);
    }

    /** Crust along the proximal centreline of a steep (≈27°) channel where basalt runs at metres per second. */
    private static double channelCrust(double disruptionVelocity) {
        LavaTestWorld world = new LavaTestWorld(-1, -2, 6, 1, (x, z) -> 200 - Math.floorDiv(x, 2));
        LavaConfig config = LavaConfig.defaults().toBuilder().timeScale(5).coolingScale(20)
                .crustDisruptionVelocity(disruptionVelocity).build();
        LavaFlow lava = new LavaFlow(world.terrain, config);
        Engine engine = world.engine(lava, 3);
        lava.addSource(LavaSource.at("vent", new BlockPos(0, 0, 0), 4, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, 300);
        double crust = 0;
        double melt = 0;
        // Proximal half only: with 8-neighbour flux the flow fans out, and its thin distal sheet
        // (x ≳ 16) legitimately slows below the disruption speed and crusts.
        for (int x = 1; x <= 12; x++) {
            crust += lava.crustThickness(x, 0);
            melt += lava.thickness(x, 0);
        }
        assertTrue(melt > 0, "the channel should still be flowing");
        return crust;
    }

    // ── Tubes ──

    /** Sloping trough x ∈ [0, 19], z ∈ [-1, 1], dammed at x = 20, with a deep pit beyond. */
    private static int trough(int x, int z, boolean breached) {
        if (z < -1 || z > 1 || x < 0) return 100;
        if (x < 20) return 70 - x / 4;
        if (x == 20) return breached ? 50 : 100;
        if (x < 40) return 40;
        return 100;
    }

    private record TubeRun(LavaTestWorld world, LavaFlow lava, Engine engine) {}

    /** Fills the dammed trough, lets a crust grow over the pond, then breaches the dam. */
    private static TubeRun pondThenBreach(LavaConfig config, long seed, int drainTicks) {
        LavaTestWorld world = new LavaTestWorld(-1, -1, 2, 0, (x, z) -> trough(x, z, false));
        LavaFlow lava = new LavaFlow(world.terrain, config);
        Engine engine = world.engine(lava, seed);
        lava.addSource(LavaSource.at("vent", new BlockPos(0, 0, 0), 1.0, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, 300);
        lava.removeSource("vent");
        world.run(engine, 2000);
        engine.submit(breach(world.terrain));
        world.run(engine, drainTicks);
        return new TubeRun(world, lava, engine);
    }

    /** The host re-sends the trough terrain with the dam removed (a breakout). */
    private static TerrainSnapshot breach(TerrainModel terrain) {
        List<TerrainChunk> chunks = new ArrayList<>();
        for (int cx = -1; cx <= 2; cx++) {
            for (int cz = -1; cz <= 0; cz++) {
                TerrainChunk chunk = new TerrainChunk(cx, cz);
                for (int x = cx * 16; x < cx * 16 + 16; x++) {
                    for (int z = cz * 16; z < cz * 16 + 16; z++) {
                        TerrainColumn column = terrain.column(x, z);
                        if (x == 20 && z >= -1 && z <= 1) column = TerrainColumn.dry(trough(x, z, true), column.surface());
                        chunk.set(x, z, column);
                    }
                }
                chunks.add(chunk);
            }
        }
        return new TerrainSnapshot(chunks);
    }

    private static LavaConfig tubeConfig() {
        return LavaConfig.defaults().toBuilder().timeScale(20).coolingScale(100).tubeMinRoofThickness(0.5).build();
    }

    @Test
    void drainedRoofedFlowLeavesHollowTube() {
        TubeRun run = pondThenBreach(tubeConfig(), 4, 3000);
        LavaFlow lava = run.lava();
        List<LavaTube> tubes = lava.tubes();
        assertFalse(tubes.isEmpty(), "the drained pond should leave tube voids");
        assertFalse(run.world().events(LavaEvents.LavaTubesFormed.class).isEmpty());

        Map<BlockPos, BlockState> blocks = run.world().appliedBlocks();
        for (LavaTube tube : tubes) {
            int surface = run.world().terrain.column(tube.x(), tube.z()).groundY();
            assertTrue(surface > tube.topY(), "roof above the void at " + tube);
            for (int y = tube.bottomY(); y <= tube.topY(); y++) {
                BlockState state = blocks.get(new BlockPos(tube.x(), y, tube.z()));
                assertTrue(state == null || state.equals(BlockState.AIR), "void block " + y + " is " + state);
            }
            for (int y = tube.topY() + 1; y <= surface; y++) {
                BlockState roof = blocks.get(new BlockPos(tube.x(), y, tube.z()));
                assertNotNull(roof, "roof block at " + y);
                assertEquals(BlockId.minecraft("smooth_basalt"), roof.id(), "basaltic roof at " + y);
            }
            assertEquals(0, lava.thickness(tube.x(), tube.z()));
            assertEquals(0, lava.crustThickness(tube.x(), tube.z()));
        }
        assertEquals(lava.emittedVolume(), lava.totalLavaVolume() + lava.crustVolume() + lava.solidifiedVolume(),
                1e-6 * lava.emittedVolume());
    }

    @Test
    void thinRoofCollapsesInsteadOfLeavingTube() {
        LavaConfig config = tubeConfig().toBuilder().tubeMinRoofThickness(5).build();
        TubeRun run = pondThenBreach(config, 4, 3000);
        assertTrue(run.lava().tubes().isEmpty(), "a roof thinner than the threshold must cave in");
    }

    @Test
    void crustAndTubesSurviveSaveRestoreBitForBit() {
        LavaConfig config = tubeConfig();
        TubeRun reference = pondThenBreach(config, 9, 1500);
        List<EngineFrame> referenceFrames = new ArrayList<>(reference.world().frames);
        reference.world().run(reference.engine(), 1500);

        TubeRun before = pondThenBreach(config, 9, 1500);
        assertTrue(before.lava().crustedCellCount() > 0 || !before.lava().tubes().isEmpty(), "save point has crust");
        InMemorySaveStore saved = Saves.save(before.engine());

        TerrainModel resent = before.world().copyTerrain();
        LavaFlow after = new LavaFlow(resent, config);
        Engine afterEngine = Engine.builder(9).add(resent).add(after)
                .restore(saved).build();
        List<EngineFrame> resumed = new ArrayList<>();
        for (int i = 0; i < 1500; i++) resumed.add(afterEngine.step());

        assertEquals(reference.world().frames.subList(referenceFrames.size(), referenceFrames.size() + 1500), resumed);
        assertEquals(reference.lava().tubes(), after.tubes());
        assertEquals(reference.lava().crustVolume(), after.crustVolume());
        assertFalse(after.tubes().isEmpty(), "tubes should have formed by the end");
    }

    @Test
    void lavaStateRoundTripsThroughRegionFields() {
        LavaTestWorld world = new LavaTestWorld(-1, -1, 1, 1, (x, z) -> 80 - x);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withTimeScale(10));
        Engine engine = world.engine(lava, 4);
        lava.addSource(LavaSource.at("vent", new BlockPos(0, 0, 0), 2, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, 300);

        SubsystemState saved = new SubsystemState();
        lava.saveState(saved);
        assertNotNull(saved.field("cells"), "lava cells are stored as a region field");
        assertEquals(5, saved.json().get("format").getAsInt());

        LavaFlow copy = new LavaFlow(world.terrain, LavaConfig.defaults().withTimeScale(10));
        copy.loadState(saved);
        assertEquals(lava.totalLavaVolume(), copy.totalLavaVolume());
        assertEquals(lava.activeCellCount(), copy.activeCellCount());
        SubsystemState resaved = new SubsystemState();
        copy.saveState(resaved);
        assertEquals(saved.json(), resaved.json());
        for (StateReader.Entry entry : saved.field("cells").chunks()) {
            FieldChunk again = resaved.field("cells").get(entry.chunkX(), entry.chunkZ());
            assertNotNull(again);
            assertArrayEquals(entry.data().doubles("thickness"), again.doubles("thickness"));
            assertArrayEquals(entry.data().doubles("temperature"), again.doubles("temperature"));
        }
    }

    // ── Ocean entry ──

    @Test
    void sustainedOceanEntryBuildsDeltaSeaward() {
        // Coastal slope toward +x; the sea (y = 72) starts at x = 9 and deepens to y = 50.
        LavaTestWorld world = new LavaTestWorld(-1, -1, 3, 0, (x, z) -> Math.max(50, 80 - x), (x, z) -> SEA);
        LavaFlow lava = new LavaFlow(world.terrain, LavaConfig.defaults().withTimeScale(10).withCoolingScale(5));
        Engine engine = world.engine(lava, 5);
        lava.addSource(LavaSource.at("vent", new BlockPos(0, 0, 0), 3, BASALT_T, BASALT_SI, 0.1));
        int shoreBefore = shoreline(world.terrain);
        world.run(engine, 4000);
        int shoreAfter = shoreline(world.terrain);

        assertTrue(shoreAfter > shoreBefore, "shoreline " + shoreBefore + " → " + shoreAfter);
        int hyaloclastite = 0;
        for (Map.Entry<BlockPos, BlockState> e : world.appliedBlocks().entrySet()) {
            BlockPos p = e.getKey();
            if (e.getValue().equals(LavaPalette.HYALOCLASTITE) && p.y() < SEA && p.x() > shoreBefore) hyaloclastite++;
        }
        assertTrue(hyaloclastite > 0, "quench fragments should be shed down the delta front");

        List<LavaEvents.LavaOceanEntry> entries = world.events(LavaEvents.LavaOceanEntry.class);
        assertFalse(entries.isEmpty());
        for (LavaEvents.LavaOceanEntry e : entries) {
            assertTrue(e.powerMW() > 0 && e.steamKgPerS() > 0, e.toString());
        }
    }

    /** Last x along z = 0 whose ground is at or above sea level. */
    private static int shoreline(TerrainModel terrain) {
        int shore = Integer.MIN_VALUE;
        for (int x = -16; x < 64; x++) if (terrain.column(x, 0).groundY() >= SEA) shore = x;
        return shore;
    }

    @Test
    void entryPowerScalesWithFluxAndFlagsLittoralExplosions() {
        double gentle = maxEntry(0.2, false);
        double vigorous = maxEntry(8, false);
        assertTrue(vigorous > gentle, "power " + vigorous + " vs " + gentle);
        assertTrue(maxEntry(8, true) > 0, "high-flux entries should be explosive");
    }

    /** Peak entry power (MW), or with {@code explosive}, the number of littoral explosions. */
    private static double maxEntry(double rate, boolean explosive) {
        LavaTestWorld world = new LavaTestWorld(-1, -1, 1, 0, (x, z) -> 80 - x, (x, z) -> x >= 10 ? 75 : LavaTestWorld.NO_WATER);
        LavaConfig config = LavaConfig.defaults().toBuilder().timeScale(10).coolingScale(1).littoralExplosionFluxM3s(0.5).build();
        LavaFlow lava = new LavaFlow(world.terrain, config);
        Engine engine = world.engine(lava, 6);
        lava.addSource(LavaSource.at("vent", new BlockPos(0, 0, 0), rate, BASALT_T, BASALT_SI, 0.1));
        world.run(engine, 400);
        List<LavaEvents.LavaOceanEntry> entries = world.events(LavaEvents.LavaOceanEntry.class);
        if (explosive) return entries.stream().filter(LavaEvents.LavaOceanEntry::littoralExplosion).count();
        return entries.stream().mapToDouble(LavaEvents.LavaOceanEntry::powerMW).max().orElse(0);
    }
}
