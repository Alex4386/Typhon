package me.alex4386.typhon.engine.geothermal;

import static me.alex4386.typhon.engine.geothermal.GeothermalTest.ANDESITE;
import static me.alex4386.typhon.engine.geothermal.GeothermalTest.CENTER;
import static me.alex4386.typhon.engine.geothermal.GeothermalTest.SURFACE_Y;
import static me.alex4386.typhon.engine.geothermal.GeothermalTest.events;
import static me.alex4386.typhon.engine.geothermal.GeothermalTest.flatTerrain;
import static me.alex4386.typhon.engine.geothermal.GeothermalTest.frozen;
import static me.alex4386.typhon.engine.geothermal.GeothermalTest.frozenConfig;
import static me.alex4386.typhon.engine.geothermal.GeothermalTest.run;
import static me.alex4386.typhon.engine.geothermal.GeothermalTest.smallConfig;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.alex4386.typhon.engine.geothermal.GeothermalTest.StubMagma;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.BlockId;
import org.junit.jupiter.api.Test;
import me.alex4386.typhon.engine.testing.Saves;
import me.alex4386.typhon.engine.save.InMemorySaveStore;

/** Liquid- vs vapour-dominated ground, patchy alteration, aggregated events and prewarming. */
class GeothermalHydrologyTest {
    private static final VentSite WET_VENT = VentSite.crater("wet", new BlockPos(-12, SURFACE_Y, 0), 2);
    private static final VentSite DRY_VENT = VentSite.crater("dry", new BlockPos(16, SURFACE_Y, 0), 2);

    /** Flat arid ground with a lake along the west edge (x < -20). */
    static TerrainModel lakeside() {
        TerrainModel terrain = new TerrainModel();
        for (int x = -40; x < 40; x++) {
            for (int z = -40; z < 40; z++) {
                terrain.setColumn(x, z, x < -20
                        ? new TerrainColumn(SURFACE_Y - 2, SURFACE_Y, BlockId.minecraft("sand"))
                        : TerrainColumn.dry(SURFACE_Y, ANDESITE));
            }
        }
        return terrain;
    }

    static Geothermal lakesideVolcano(GeothermalConfig config) {
        config.baseSaturation = 0.25;  // arid ground away from the lake
        config.lakeSaturationBonus = 1.0;
        config.lakeInfluenceBlocks = 16;
        return new Geothermal("test", config, CENTER, new StubMagma(1150), lakeside(),
                BlockPalette.unrestricted(), List.of(WET_VENT, DRY_VENT));
    }

    @Test
    void boilingPointFollowsDepth() {
        assertEquals(100, Geothermal.boilingPointAtDepth(0), 1e-9);
        assertEquals(115, Geothermal.boilingPointAtDepth(10), 1.5);
        assertEquals(146, Geothermal.boilingPointAtDepth(50), 3);
        assertEquals(200, Geothermal.boilingPointAtDepth(150), 3);
    }

    @Test
    void wetGroundStaysNearBoilingWhileDryGroundBecomesVapourDominated() {
        Geothermal geothermal = lakesideVolcano(smallConfig());
        geothermal.equilibrate(30 * 3600);

        double wetT = geothermal.temperatureAt(-12, 0);
        double dryT = geothermal.temperatureAt(16, 0);
        assertTrue(wetT >= 100 && wetT <= 150, "liquid-dominated ground is held near boiling, was " + wetT);
        assertTrue(geothermal.waterAt(-12, 0) >= 0.6, "and stays wet: " + geothermal.waterAt(-12, 0));
        assertFalse(geothermal.vapourDominatedAt(-12, 0));
        assertTrue(dryT > 150, "poorly watered ground heats up: " + dryT);
        assertTrue(geothermal.vapourDominatedAt(16, 0), "and boils dry: " + geothermal.waterAt(16, 0));
    }

    @Test
    void geysersGrowOnTheWetSideFumarolesOnTheDrySide() {
        GeothermalConfig config = smallConfig();
        config.timeScale = 600;
        config.geyserFormationPerHour = 2;
        config.hotSpringFormationPerHour = 0;
        config.mudPotFormationPerHour = 0;
        Geothermal geothermal = lakesideVolcano(config);
        geothermal.equilibrate(30 * 3600);
        List<EngineFrame> frames = run(geothermal, 5, 300);

        List<GeyserFormed> geysers = events(frames, GeyserFormed.class);
        assertFalse(geysers.isEmpty(), "a hot, well-watered basin grows geysers");
        assertTrue(geysers.stream().allMatch(g -> g.potentSulfur().x() < 0), "only on the lakeside");
        assertTrue(geothermal.features(HydrothermalFeature.FUMAROLE).stream().anyMatch(f -> f.x() > 4),
                "the dry vent is fumarolic");
    }

    // ── Alteration ──

    @Test
    void saturatedGroundAltersOnlyAroundFumaroles() {
        GeothermalConfig config = frozenConfig(0.6);
        config.acidAlterationPerHour = 5;
        config.fumaroleFormationPerHour = 0;
        Geothermal noFumaroles = frozen(config, flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 200);
        run(noFumaroles, 3, 30);
        assertEquals(0, noFumaroles.count(HydrothermalFeature.ACID_ALTERATION), "no isolated nucleation when saturated");

        GeothermalConfig unsaturated = frozenConfig(0.3);
        unsaturated.acidAlterationPerHour = 5;
        unsaturated.fumaroleFormationPerHour = 0;
        Geothermal dry = frozen(unsaturated, flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 200);
        run(dry, 3, 30);
        assertTrue(dry.count(HydrothermalFeature.ACID_ALTERATION) > 0, "steam-heated ground nucleates");
    }

    @Test
    void alterationSpreadsFromFumarolesInPatches() {
        GeothermalConfig config = frozenConfig(0.6);
        config.acidAlterationPerHour = 5;
        config.fumaroleFormationPerHour = 0.5;
        config.sulfurDepositPerHour = 0;
        Geothermal geothermal = frozen(config, flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 200);
        run(geothermal, 4, 40);

        List<PlacedFeature> altered = geothermal.features(HydrothermalFeature.ACID_ALTERATION);
        assertFalse(altered.isEmpty());
        int r = config.alterationGrowthRadius;
        Map<Long, PlacedFeature> all = geothermal.featuresByColumn();
        for (PlacedFeature a : altered) {
            boolean anchored = all.values().stream().anyMatch(f -> f != a
                    && (f.kind() == HydrothermalFeature.FUMAROLE || f.kind() == HydrothermalFeature.ACID_ALTERATION)
                    && Math.abs(f.x() - a.x()) <= r && Math.abs(f.z() - a.z()) <= r);
            assertTrue(anchored, "altered column " + a + " grew from a fumarole or other altered ground");
        }
        assertTrue(altered.size() < 80 * 80 / 4, "patchy, not blanket: " + altered.size());
    }

    // ── Events ──

    @Test
    void gasHazardsAreAggregatedPerZoneAndCleared() {
        GeothermalConfig config = frozenConfig(0.2);
        config.fumaroleFormationPerHour = 0;
        config.acidAlterationPerHour = 0;
        Geothermal geothermal = frozen(config, flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 300);
        Engine engine = Engine.builder(1).add(geothermal).build();

        List<GasHazard> active = events(run(engine, geothermal, 100), GasHazard.class); // 200 s, 20 evaluations
        int zonesPerSide = (geothermal.grid().sizeX() + config.hazardZoneCells - 1) / config.hazardZoneCells;
        int zoneSpecies = zonesPerSide * zonesPerSide * GasSpecies.values().length;
        assertEquals(zoneSpecies, active.size(), "steady hazards are announced once per zone and species");

        geothermal.grid().fillExcess(0);
        List<GasHazard> cleared = events(run(engine, geothermal, 10), GasHazard.class);
        assertEquals(zoneSpecies, cleared.size());
        assertTrue(cleared.stream().allMatch(h -> h.concentrationPpm() == 0));
        Set<BlockPos> activeCentres = active.stream().map(GasHazard::center).collect(Collectors.toSet());
        assertTrue(cleared.stream().allMatch(h -> activeCentres.contains(h.center())), "zones keep their centre");
    }

    @Test
    void fumaroleActivityIsReportedOnChangeOrRefresh() {
        GeothermalConfig config = frozenConfig(0.2);
        config.sulfurDepositPerHour = 0;
        config.acidAlterationPerHour = 0;
        Geothermal geothermal = frozen(config, flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 300);
        List<EngineFrame> frames = run(geothermal, 7, 90); // 180 s of steady fumaroles

        List<FumaroleActivity> activity = events(frames, FumaroleActivity.class);
        assertFalse(activity.isEmpty());
        Map<BlockPos, Integer> perFumarole = new HashMap<>();
        for (FumaroleActivity a : activity) perFumarole.merge(a.pos(), 1, Integer::sum);
        long maxReports = 1 + Math.round(180 / config.fumaroleRefreshSeconds);
        assertTrue(perFumarole.values().stream().allMatch(n -> n <= maxReports), "per fumarole: " + perFumarole);
    }

    // ── Prewarming ──

    @Test
    void prewarmMatchesManualEquilibrationAndHappensOnce() {
        GeothermalConfig prewarmConfig = smallConfig();
        prewarmConfig.prewarmSeconds = 10 * 3600;
        Geothermal prewarmed = lakesideVolcano(prewarmConfig);
        Engine engine = Engine.builder(3).add(prewarmed).build();
        engine.step();

        Geothermal manual = lakesideVolcano(smallConfig());
        manual.equilibrate(10 * 3600);
        Engine manualEngine = Engine.builder(3).add(manual).build();
        manualEngine.step();
        assertArrayEquals(manual.grid().excess, prewarmed.grid().excess);

        // A restored engine must not prewarm again.
        InMemorySaveStore saved = Saves.save(engine);
        List<EngineFrame> reference = run(engine, prewarmed, 20);
        GeothermalConfig again = smallConfig();
        again.prewarmSeconds = 10 * 3600;
        Geothermal restored = lakesideVolcano(again);
        Engine restoredEngine = Engine.builder(3).add(restored)
                .restore(saved).build();
        assertEquals(reference, run(restoredEngine, restored, 20));
        assertArrayEquals(prewarmed.grid().excess, restored.grid().excess);
    }
}
