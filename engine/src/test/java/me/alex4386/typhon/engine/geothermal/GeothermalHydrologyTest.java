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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.alex4386.typhon.engine.geothermal.GeothermalTest.StubMagma;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.VentSite;
import org.junit.jupiter.api.Test;
import me.alex4386.typhon.engine.testing.Saves;
import me.alex4386.typhon.engine.save.InMemorySaveStore;

/** Liquid- vs vapour-dominated ground, patchy alteration, aggregated events and prewarming. */
class GeothermalHydrologyTest {
    private static final VentSite WET_VENT = VentSite.crater("wet", Point3.surfaceOf(new BlockPos(-12, SURFACE_Y, 0), 1), 2);
    private static final VentSite DRY_VENT = VentSite.crater("dry", Point3.surfaceOf(new BlockPos(16, SURFACE_Y, 0), 1), 2);

    /** Flat ground: well watered west of x = −4, poorly watered east of x = 4. */
    static TerrainModel basin() {
        return flatTerrain(40, ANDESITE);
    }

    /**
     * A pinned hydrothermal state: the wet vent's ground is liquid-dominated near boiling (140 °C,
     * saturation 0.9), the dry vent's ground vapour-dominated and hot (300 °C, saturation 0.2).
     */
    static Geothermal lakesideVolcano(GeothermalConfig config) {
        TerrainModel terrain = basin();
        StubField field = new StubField(terrain, config.reservoirDepthM, 15, 0.5);
        field.temperature = (x, z) -> x < -4 ? 140 : (x > 4 ? 300 : 15);
        field.water = (x, z) -> x < -4 ? 0.9 : 0.2;
        return new Geothermal("test", config, CENTER, new StubMagma(1150), terrain, BlockPalette.unrestricted(),
                List.of(WET_VENT, DRY_VENT), field);
    }

    @Test
    void boilingPointFollowsDepth() {
        assertEquals(100, Geothermal.boilingPointAtDepth(0), 1e-9);
        assertEquals(115, Geothermal.boilingPointAtDepth(10), 1.5);
        assertEquals(146, Geothermal.boilingPointAtDepth(50), 3);
        assertEquals(200, Geothermal.boilingPointAtDepth(150), 3);
    }

    /**
     * The liquid-/vapour-dominated dichotomy emerges from the subsurface model: with a shallow water
     * table the heated reservoir is held near its boiling point and stays wet, with a deep one the
     * same heat boils it dry and heats it far above boiling.
     */
    @Test
    void shallowWaterTableStaysNearBoilingDeepOneBecomesVapourDominated() {
        Geothermal wet = heatedGround(1.0);
        Geothermal dry = heatedGround(40.0);
        double wetT = wet.temperatureAt(0, 0);
        double dryT = dry.temperatureAt(0, 0);
        assertTrue(wetT >= 100 && wetT <= 150, "liquid-dominated ground is held near boiling, was " + wetT);
        assertFalse(wet.vapourDominatedAt(0, 0), "and stays wet: " + wet.waterAt(0, 0));
        assertTrue(dryT > 150, "poorly watered ground heats up: " + dryT);
        assertTrue(dry.vapourDominatedAt(0, 0), "and is vapour-dominated: " + dry.waterAt(0, 0));
    }

    private static Geothermal heatedGround(double waterTableDepth) {
        me.alex4386.typhon.engine.subsurface.SubsurfaceConfig sc = GeothermalTest.liveSubsurface();
        sc.initialWaterTableDepthM = waterTableDepth;
        GeothermalConfig config = smallConfig();
        config.ventHeatPowerW = 2e6; // ≈ 0.9 kg/s of steam: within what the aquifer can resupply
        config.ventPipeDepthM = 20;
        // Permeable host rock (basalt, K ≈ 10⁻⁵ m/s): boiling is limited by how fast the rock lets
        // water back in, so a liquid-dominated system needs permeable ground (tight andesite would
        // conduct the heat away instead and simply heat up).
        me.alex4386.typhon.engine.world.WorldSpec spec = new me.alex4386.typhon.engine.world.WorldSpec(1, 8, -2000,
                Double.NaN, List.of(new me.alex4386.typhon.engine.world.WorldSpec.GeologyLayer("granite", -500, 0.01)),
                "basalt", "soil", 1);
        me.alex4386.typhon.engine.terrain.TerrainModel terrain = new me.alex4386.typhon.engine.terrain.TerrainModel(
                new me.alex4386.typhon.engine.world.WorldModel(spec));
        for (int x = -40; x < 40; x++) {
            for (int z = -40; z < 40; z++) {
                terrain.setColumn(x, z, me.alex4386.typhon.engine.terrain.TerrainColumn.dry(GeothermalTest.SURFACE_Y,
                        me.alex4386.typhon.engine.world.BlockId.minecraft("basalt")));
            }
        }
        Geothermal geothermal = GeothermalTest.live(config, terrain, new StubMagma(1150),
                List.of(VentSite.crater("main", Point3.surfaceOf(CENTER, 1), 3)), sc);
        geothermal.equilibrate(60 * 86400);
        return geothermal;
    }

    @Test
    void geysersGrowOnTheWetSideFumarolesOnTheDrySide() {
        GeothermalConfig config = smallConfig();
        GeothermalTest.stepTime(config, 600);
        config.geyserFormationPerHour = 2;
        config.hotSpringFormationPerHour = 0;
        config.mudPotFormationPerHour = 0;
        Geothermal geothermal = lakesideVolcano(config);
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
        GeothermalTest.stepTime(config, 1); // nothing forms: test steps of one geothermal period
        Geothermal geothermal = frozen(config, flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 300);
        Engine engine = GeothermalTest.engine(geothermal, 1).build();

        List<GasHazard> active = events(run(engine, geothermal, 100), GasHazard.class); // 200 s, 20 evaluations
        int zonesPerSide = (geothermal.grid().sizeX() + config.hazardZoneCells - 1) / config.hazardZoneCells;
        int zoneSpecies = zonesPerSide * zonesPerSide * GasSpecies.values().length;
        assertEquals(zoneSpecies, active.size(), "steady hazards are announced once per zone and species");

        GeothermalTest.stub(geothermal).setTemperature(15);
        List<GasHazard> cleared = events(run(engine, geothermal, 10), GasHazard.class);
        assertEquals(zoneSpecies, cleared.size());
        assertTrue(cleared.stream().allMatch(h -> h.concentrationPpm() == 0));
        Set<Point3> activeCentres = active.stream().map(GasHazard::center).collect(Collectors.toSet());
        assertTrue(cleared.stream().allMatch(h -> activeCentres.contains(h.center())), "zones keep their centre");
    }

    @Test
    void fumaroleActivityIsReportedOnChangeOrRefresh() {
        GeothermalConfig config = frozenConfig(0.2);
        config.sulfurDepositPerHour = 0;
        config.acidAlterationPerHour = 0;
        Geothermal geothermal = frozen(config, flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 300);
        Engine engine = GeothermalTest.engine(geothermal, 7).build();
        run(engine, geothermal, 90); // fumaroles form over days
        GeothermalTest.stepTime(config, 1);
        List<EngineFrame> frames = run(engine, geothermal, 90); // then 180 s of steady fumaroles

        List<FumaroleActivity> activity = events(frames, FumaroleActivity.class);
        assertFalse(activity.isEmpty());
        Map<Point3, Integer> perFumarole = new HashMap<>();
        for (FumaroleActivity a : activity) perFumarole.merge(a.pos(), 1, Integer::sum);
        long maxReports = 1 + Math.round(180 / config.fumaroleRefreshSeconds);
        assertTrue(perFumarole.values().stream().allMatch(n -> n <= maxReports), "per fumarole: " + perFumarole);
    }

    // ── Prewarming ──

    @Test
    void prewarmMatchesManualEquilibrationAndHappensOnce() {
        GeothermalConfig prewarmConfig = smallConfig();
        prewarmConfig.prewarmSeconds = 30 * 86400;
        Geothermal prewarmed = liveVolcano(prewarmConfig);
        Engine engine = GeothermalTest.fixedEngine(prewarmed, 3).build();
        engine.runFor(prewarmConfig.stepSeconds);

        Geothermal manual = liveVolcano(smallConfig());
        manual.equilibrate(30 * 86400);
        Engine manualEngine = GeothermalTest.fixedEngine(manual, 3).build();
        manualEngine.runFor(prewarmConfig.stepSeconds);
        assertArrayEquals(manual.grid().excess, prewarmed.grid().excess);
        assertTrue(prewarmed.grid().maxExcess() > 0);

        // A restored engine must not prewarm again.
        InMemorySaveStore saved = Saves.save(engine);
        List<EngineFrame> reference = run(engine, prewarmed, 20);
        GeothermalConfig again = smallConfig();
        again.prewarmSeconds = 30 * 86400;
        Geothermal restored = liveVolcano(again);
        Engine restoredEngine = GeothermalTest.fixedEngine(restored, 3).restore(saved).build();
        assertEquals(reference, run(restoredEngine, restored, 20));
        assertArrayEquals(prewarmed.grid().excess, restored.grid().excess);
    }

    private static Geothermal liveVolcano(GeothermalConfig config) {
        return GeothermalTest.live(config, flatTerrain(40, ANDESITE), new StubMagma(1150),
                List.of(WET_VENT, DRY_VENT), GeothermalTest.liveSubsurface());
    }
}
