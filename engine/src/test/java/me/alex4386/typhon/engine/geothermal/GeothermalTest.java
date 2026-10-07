package me.alex4386.typhon.engine.geothermal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.subsurface.Subsurface;
import me.alex4386.typhon.engine.subsurface.SubsurfaceConfig;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.MagmaState;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.testing.TestGround;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.WorldModel;
import org.junit.jupiter.api.Test;
import me.alex4386.typhon.engine.testing.Saves;
import me.alex4386.typhon.engine.save.InMemorySaveStore;

class GeothermalTest {
    static final Material ANDESITE = MaterialTable.ANDESITE;
    static final Material SAND = MaterialTable.SEDIMENT;
    /**
     * Column width (m). The tests model a small hydrothermal field (an 80 m square) at 1 m resolution, with
     * the default spatial tunables scaled down tenfold ({@link #smallConfig}).
     */
    static final double L = 1;
    /** Ground elevation (m) of the flat test terrains. */
    static final double SURFACE_Z = 64;
    /**
     * Geyser ground: liquid at the 10 m reservoir about 2 m below the water table (boils at ~116 °C there)
     * but above the surface boiling point (~99.8 °C at 64 m), so it flashes as it rises.
     */
    static final double GEYSER_C = 110;
    static final Point3 CENTER = Point3.columnCentre(0, 0, SURFACE_Z, L);

    /** Test double for the magma system. */
    static final class StubMagma implements MagmaState {
        Point3 chamber = new Point3(0, -3000, 0);
        double temperature = 1100;
        double overpressure = 0;
        double eruptionRate = 0;

        StubMagma(double temperature) {
            this.temperature = temperature;
        }

        @Override public Point3 chamberCenter() { return chamber; }
        @Override public double overpressureMPa() { return overpressure; }
        @Override public double overpressureRateMPaPerSecond() { return 0; }
        @Override public double temperatureC() { return temperature; }
        @Override public double silicaWt() { return 55; }
        @Override public double waterWt() { return 3; }
        @Override public double crystalFraction() { return 0.2; }
        @Override public double eruptionRate() { return eruptionRate; }
    }

    /** Dry ground at {@link #SURFACE_Z} over columns {@code [−radius, radius)²}. */
    static TerrainModel flatTerrain(int radius, Material surface) {
        return terrain(radius, (x, z) -> SURFACE_Z, TestGround.DRY, surface);
    }

    /** A sea floor at {@code floorZ} under water standing at {@link #SURFACE_Z}. */
    static TerrainModel seafloor(int radius, double floorZ) {
        return terrain(radius, (x, z) -> floorZ, (x, z) -> SURFACE_Z, SAND);
    }

    static TerrainModel terrain(int radius, TestGround.Elevation ground, TestGround.Elevation water, Material cover) {
        TerrainModel terrain = TestGround.terrain(L);
        java.util.List<me.alex4386.typhon.engine.terrain.GroundColumn> columns = new ArrayList<>();
        for (int x = -radius; x < radius; x++) {
            for (int z = -radius; z < radius; z++) {
                columns.add(new me.alex4386.typhon.engine.terrain.GroundColumn(x, z, ground.at(x, z), water.at(x, z),
                        cover));
            }
        }
        terrain.apply(new me.alex4386.typhon.engine.terrain.GroundImport(columns));
        return terrain;
    }

    static GeothermalConfig smallConfig() {
        GeothermalConfig config = new GeothermalConfig();
        config.radiusM = 32;
        config.cellSizeM = 4;
        config.ventHaloM /= 10;
        config.fumaroleSpacingM /= 10;
        config.sulfurDepositRadiusM /= 10;
        config.geyserSpacingM /= 10;
        config.hotSpringSpacingM /= 10;
        config.mudPotSpacingM /= 10;
        config.submarineVentSpacingM /= 10;
        config.alterationGrowthRadiusM /= 10;
        config.sinterGrowthRadiusM /= 10;
        config.cinnabarSpringRadiusM /= 10;
        return config;
    }

    static final Map<GeothermalConfig, Double> FROZEN_WATER = new IdentityHashMap<>();

    /** Physical seconds one test step covers, per config (default: one geothermal period). */
    static final Map<GeothermalConfig, Double> STEP_TIME = new IdentityHashMap<>();

    /** Lets each test step of {@code config} cover {@code periods} geothermal periods. */
    static GeothermalConfig stepTime(GeothermalConfig config, double periods) {
        STEP_TIME.put(config, periods * config.stepSeconds);
        return config;
    }

    /**
     * Config for a pinned, uniform hydrothermal state ({@link #frozen}): reservoir saturation
     * {@code water}, two hours per test step.
     */
    static GeothermalConfig frozenConfig(double water) {
        GeothermalConfig config = smallConfig();
        GeothermalTest.stepTime(config, 3600);
        FROZEN_WATER.put(config, water);
        return config;
    }

    /** Geothermal over a {@link StubField} pinned at {@code temperatureC} and the config's saturation. */
    static Geothermal frozen(GeothermalConfig config, TerrainModel terrain, double temperatureC) {
        StubField field = new StubField(terrain, config.reservoirDepthM, temperatureC,
                FROZEN_WATER.getOrDefault(config, 0.5));
        return new Geothermal("test", config, CENTER, new StubMagma(0), terrain, List.of(), field);
    }

    static StubField stub(Geothermal geothermal) {
        return (StubField) geothermal.field();
    }

    /** Subsurface settings for live tests (they spin the system up with {@code equilibrate} first). */
    static SubsurfaceConfig liveSubsurface() {
        return new SubsurfaceConfig();
    }

    /** Geothermal heating a real {@link Subsurface} on the terrain's world model. */
    static Geothermal live(GeothermalConfig config, TerrainModel terrain, MagmaState magma, List<VentSite> vents,
            SubsurfaceConfig subsurfaceConfig) {
        // a chamber at a realistic depth, so the vents' heat pipes dominate the shallow system
        if (magma instanceof StubMagma stub) stub.chamber = new Point3(0, -3000, 0);
        Subsurface subsurface = new Subsurface(terrain.world(), subsurfaceConfig);
        Geothermal geothermal = new Geothermal("test", config, CENTER, magma, terrain, vents, subsurface);
        subsurface.setHeatSources("test", geothermal);
        return geothermal;
    }

    /** Engine with the geothermal subsystem (and its live subsurface, if any), on adaptive steps. */
    static Engine.Builder engine(Geothermal geothermal, long seed) {
        Engine.Builder builder = Engine.builder(seed).adaptive(Geothermal.MAX_STEP_SECONDS);
        if (geothermal.field() instanceof Subsurface subsurface) builder.add(subsurface);
        return builder.add(geothermal);
    }

    /** Engine with fixed base steps (no adaptive stepping): for comparisons step by step. */
    static Engine.Builder fixedEngine(Geothermal geothermal, long seed) {
        Engine.Builder builder = Engine.builder(seed);
        if (geothermal.field() instanceof Subsurface subsurface) builder.add(subsurface);
        return builder.add(geothermal);
    }

    static List<EngineFrame> run(Geothermal geothermal, long seed, int steps) {
        Engine engine = engine(geothermal, seed).build();
        return run(engine, geothermal, steps);
    }

    /** Runs {@code steps} test steps (see {@link #stepTime}) of physical time. */
    static List<EngineFrame> run(Engine engine, Geothermal geothermal, int steps) {
        List<EngineFrame> frames = new ArrayList<>();
        double perStep = STEP_TIME.getOrDefault((GeothermalConfig) geothermal.config(), geothermal.periodSeconds());
        double end = engine.time() + steps * perStep;
        while (engine.time() < end - 1e-9) frames.add(engine.step());
        return frames;
    }

    static <T extends EngineEvent> List<T> events(List<EngineFrame> frames, Class<T> type) {
        List<T> result = new ArrayList<>();
        for (EngineFrame frame : frames) {
            for (EngineEvent event : frame.events()) {
                if (type.isInstance(event)) result.add(type.cast(event));
            }
        }
        return result;
    }

    /** The top materials of the columns within {@code radius} of the origin. */
    static Set<Material> surfaces(TerrainModel terrain, int radius) {
        WorldModel world = terrain.world();
        Set<Material> set = new HashSet<>();
        for (int x = -radius; x < radius; x++) {
            for (int z = -radius; z < radius; z++) {
                if (world.isKnown(x, z)) set.add(world.layer(x, z, world.layerCount(x, z) - 1).materialInfo());
            }
        }
        return set;
    }

    static long formed(List<EngineFrame> frames, HydrothermalFeature kind) {
        return events(frames, HydrothermalFeatureFormed.class).stream().filter(e -> e.feature() == kind).count();
    }

    // ── Heat supplied to and sampled from the subsurface ──

    private static final double DAY = 86400;

    @Test
    void activeVolcanoHeatsGroundAroundVent() {
        GeothermalConfig config = smallConfig();
        config.radiusM = 64 * L;
        TerrainModel terrain = flatTerrain(72, ANDESITE);
        Geothermal geothermal = live(config, terrain, new StubMagma(1100), List.of(VentSite.crater("main", CENTER, 3 * L)),
                liveSubsurface());
        geothermal.equilibrate(30 * DAY);

        double atVent = geothermal.temperatureAt(0, 0);
        double near = geothermal.temperatureAt(24, 0);
        double far = geothermal.temperatureAt(56, 0);
        assertTrue(atVent > 60, "vent should get hot, was " + atVent);
        assertTrue(atVent > near && near > far, atVent + " > " + near + " > " + far);
    }

    @Test
    void coldChamberDrivesNoVentHeat() {
        Geothermal geothermal = frozen(smallConfig(), flatTerrain(40, ANDESITE), 15);
        assertEquals(0, geothermal.activity());
        assertTrue(geothermal.vents().isEmpty());
    }

    @Test
    void chamberAndVentsAreReportedAsHeatSources() {
        StubMagma magma = new StubMagma(1100);
        TerrainModel terrain = flatTerrain(40, ANDESITE);
        Geothermal geothermal = new Geothermal("test", smallConfig(), CENTER, magma, terrain,
                List.of(VentSite.crater("main", CENTER, 3 * L)),
                new StubField(terrain, 10, 15, 0.5));
        List<me.alex4386.typhon.engine.subsurface.HeatSources.Chamber> chambers = geothermal.chambers();
        assertEquals(1, chambers.size());
        var chamber = chambers.get(0);
        double depth = chamber.surfaceElevation() - chamber.centerElevation();
        assertTrue(depth > 0 && chamber.radiusM() <= depth / 2 + 1e-9);
        assertEquals(1100, chamber.temperatureC());
        assertEquals(1, geothermal.vents().size());
        assertEquals(geothermal.activity() * new GeothermalConfig().ventHeatPowerW, geothermal.vents().get(0).powerW(), 1e-6);
    }

    @Test
    void eruptionAndOverpressureRaiseActivity() {
        StubMagma magma = new StubMagma(1100);
        TerrainModel terrain = flatTerrain(40, ANDESITE);
        Geothermal geothermal = new Geothermal("test", smallConfig(), CENTER, magma, terrain,
                List.of(), new StubField(terrain, 10, 15, 0.5));
        double quiet = geothermal.activity();
        magma.overpressure = 10;
        double pressurised = geothermal.activity();
        magma.eruptionRate = 10;
        double erupting = geothermal.activity();
        assertTrue(quiet < pressurised && pressurised < erupting);
    }

    @Test
    void lavaHeatFlowsIntoTheGround() {
        GeothermalConfig config = smallConfig();
        Geothermal geothermal = frozen(config, flatTerrain(40, ANDESITE), 15);
        geothermal.addLavaHeat(5, 5, 1150, 2);
        // W/m² through 1 m (half the flow), over the column's L² m², for 1 s
        double expected = config.lavaConductivity * (1150 - 15) / 1.0 * L * L;
        assertEquals(expected, stub(geothermal).surfaceHeat, 1e-9);
    }

    // ── Fumaroles ──

    @Test
    void hotterChamberMeansMoreFumaroleActivity() {
        long hot = fumaroleEvents(1100);
        long warm = fumaroleEvents(800);
        assertTrue(hot > 0);
        assertTrue(hot > warm, hot + " vs " + warm);
    }

    private static long fumaroleEvents(double chamberC) {
        GeothermalConfig config = smallConfig();
        GeothermalTest.stepTime(config, 600);
        config.ventHeatPowerW = 2e6; // a modest hydrothermal area, so the response is not saturated
        config.ventPipeDepthM = 20;
        config.maxFumaroles = 1000;
        TerrainModel terrain = flatTerrain(40, ANDESITE);
        Geothermal geothermal = live(config, terrain, new StubMagma(chamberC), List.of(VentSite.crater("main", CENTER, 3 * L)),
                liveSubsurface());
        geothermal.equilibrate(30 * DAY);
        List<EngineFrame> frames = run(geothermal, 42, 100);
        return events(frames, FumaroleActivity.class).size();
    }

    @Test
    void fumarolesGrowSulfurCrustsAndSpikes() {
        GeothermalConfig config = frozenConfig(0.2);
        config.sulfurDepositPerHour = 5;
        TerrainModel terrain = flatTerrain(40, ANDESITE);
        Geothermal geothermal = frozen(config, terrain, 300);
        List<EngineFrame> frames = run(geothermal, 7, 60);

        assertTrue(formed(frames, HydrothermalFeature.FUMAROLE) > 0);
        assertTrue(surfaces(terrain, 40).contains(MaterialTable.SULFUR), "sulfur crusts the ground");
        assertTrue(geothermal.features(HydrothermalFeature.SULFUR_DEPOSIT).stream().anyMatch(f -> f.level() > 0),
                "sulfur deposits build up in stages");

        List<FumaroleActivity> activity = events(frames, FumaroleActivity.class);
        assertFalse(activity.isEmpty());
        FumaroleActivity sample = activity.get(0);
        assertEquals(SURFACE_Z, sample.pos().y(), 1e-9);
        GasComposition gas = sample.gas();
        assertEquals(1.0, gas.h2o() + gas.co2() + gas.so2() + gas.h2s(), 1e-9);
    }

    @Test
    void magmaticGasIsSulfurDioxideRichHydrothermalGasIsHydrogenSulfideRich() {
        GasComposition hot = GasComposition.atTemperature(400);
        GasComposition cool = GasComposition.atTemperature(110);
        assertTrue(hot.so2() > hot.h2s());
        assertTrue(cool.h2s() > cool.so2());
        assertTrue(cool.h2o() > hot.h2o());
    }

    @Test
    void gasHazardsScaleWithTemperature() {
        GeothermalConfig config = frozenConfig(0.2);
        config.fumaroleFormationPerHour = 0;
        config.acidAlterationPerHour = 0;
        List<GasHazard> hot = events(run(frozen(config, flatTerrain(40, ANDESITE), 340), 1, 10), GasHazard.class);
        List<GasHazard> mild = events(run(frozen(config, flatTerrain(40, ANDESITE), 140), 1, 10), GasHazard.class);

        Set<GasSpecies> species = hot.stream().map(GasHazard::species).collect(Collectors.toSet());
        assertEquals(Set.of(GasSpecies.CO2, GasSpecies.SO2, GasSpecies.H2S), species);
        double hotSo2 = hot.stream().filter(h -> h.species() == GasSpecies.SO2).mapToDouble(GasHazard::concentrationPpm).max().orElse(0);
        double mildSo2 = mild.stream().filter(h -> h.species() == GasSpecies.SO2).mapToDouble(GasHazard::concentrationPpm).max().orElse(0);
        assertTrue(hotSo2 > mildSo2, hotSo2 + " vs " + mildSo2);
        // Hazards stay valid until the zone's next update (refresh, change or clear).
        assertTrue(hot.stream().allMatch(h -> h.durationSeconds() == config.hazardRefreshSeconds + config.hazardIntervalSeconds));

        List<GasHazard> cold = events(run(frozen(config, flatTerrain(40, ANDESITE), 60), 1, 10), GasHazard.class);
        assertTrue(cold.isEmpty());
    }

    // ── Geysers ──

    static GeothermalConfig geyserOnlyConfig(double water) {
        GeothermalConfig config = frozenConfig(water);
        config.geyserFormationPerHour = 5;
        config.fumaroleFormationPerHour = 0;
        config.hotSpringFormationPerHour = 0;
        config.mudPotFormationPerHour = 0;
        config.acidAlterationPerHour = 0;
        config.sinterPerHour = 0;
        config.cinnabarPerHour = 0;
        return config;
    }

    static long geysersFormed(List<EngineFrame> frames) {
        return formed(frames, HydrothermalFeature.GEYSER);
    }

    @Test
    void geysersFormWhereHotAndWetAsFloodedPits() {
        TerrainModel terrain = flatTerrain(40, ANDESITE);
        GeothermalConfig config = geyserOnlyConfig(0.9);
        Geothermal geothermal = frozen(config, terrain, GEYSER_C);
        List<EngineFrame> frames = run(geothermal, 3, 30);
        List<PlacedFeature> geysers = geothermal.features(HydrothermalFeature.GEYSER);
        assertFalse(geysers.isEmpty());
        assertEquals(geysers.size(), geysersFormed(frames));
        assertTrue(geysers.size() <= config.maxGeysers);

        WorldModel world = terrain.world();
        for (PlacedFeature geyser : geysers) {
            int depth = geyser.level();
            assertTrue(depth >= 1 && depth <= 4);
            // a pit 1–4 m deep, flooded to the old ground
            assertEquals(SURFACE_Z, geyser.elevation(), 1e-9);
            assertEquals(SURFACE_Z - depth, world.surfaceZ(geyser.x(), geyser.z()), 1e-6);
            assertEquals(SURFACE_Z, world.waterZ(geyser.x(), geyser.z()), 1e-6);
        }

        // geysers keep their spacing
        int spacing = (int) Math.round(config.geyserSpacingM / L);
        for (PlacedFeature a : geysers) {
            for (PlacedFeature b : geysers) {
                if (a == b) continue;
                int d = Math.max(Math.abs(a.x() - b.x()), Math.abs(a.z() - b.z()));
                assertTrue(d > spacing);
            }
        }
    }

    @Test
    void noFeaturesFormOnFreshLava() {
        // the same hot, wet ground that grows geysers in the test above, but freshly covered by lava
        Geothermal geothermal = frozen(geyserOnlyConfig(0.9), flatTerrain(40, ANDESITE), GEYSER_C);
        for (int x = -40; x < 40; x++) {
            for (int z = -40; z < 40; z++) geothermal.addLavaHeat(x, z, 1150, 2);
        }
        assertTrue(geothermal.lavaCovered(0, 0));
        List<EngineFrame> frames = run(geothermal, 3, 30);
        assertEquals(0, geysersFormed(frames), "no geyser on a lava flow");
        assertTrue(geothermal.featuresByColumn().isEmpty());
    }

    @Test
    void lavaBuriesFeaturesItReaches() {
        Geothermal geothermal = frozen(geyserOnlyConfig(0.9), flatTerrain(40, ANDESITE), GEYSER_C);
        Engine engine = engine(geothermal, 3).build();
        run(engine, geothermal, 30);
        List<PlacedFeature> geysers = geothermal.features(HydrothermalFeature.GEYSER);
        assertFalse(geysers.isEmpty());
        PlacedFeature first = geysers.get(0);
        int x = first.x();
        int z = first.z();
        assertNotNull(geothermal.featuresByColumn().get(PlacedFeature.key(x, z)));

        geothermal.addLavaHeat(x, z, 1150, 3);
        List<HydrothermalFeatureBuried> buried = events(run(engine, geothermal, 1), HydrothermalFeatureBuried.class);
        assertEquals(1, buried.stream().filter(b -> b.pos().columnX(L) == x && b.pos().columnZ(L) == z).count(),
                buried.toString());
        assertEquals(HydrothermalFeature.GEYSER, buried.get(0).feature());
        assertNull(geothermal.featuresByColumn().get(PlacedFeature.key(x, z)), "its entity goes with it");
    }

    @Test
    void noGeysersWhenDryOrCold() {
        assertEquals(0, geysersFormed(run(frozen(geyserOnlyConfig(0.3), flatTerrain(40, ANDESITE), 140), 3, 30)), "dry");
        assertEquals(0, geysersFormed(run(frozen(geyserOnlyConfig(0.9), flatTerrain(40, ANDESITE), 60), 3, 30)), "cold");
        assertEquals(0, geysersFormed(run(frozen(geyserOnlyConfig(0.9), flatTerrain(40, ANDESITE), 260), 3, 30)),
                "too hot (vapour-dominated)");
    }

    @Test
    void geysersNeedContainingWalls() {
        // One-column-wide ridges 2 m high: every ridge column has a lower neighbour, so water would spill.
        TerrainModel terrain = terrain(40, (x, z) -> SURFACE_Z + ((x & 1) == 0 ? 2 : 0), TestGround.DRY, ANDESITE);
        // Even columns are ridges (spill), odd columns are trenches (contained).
        Geothermal geothermal = frozen(geyserOnlyConfig(0.9), terrain, GEYSER_C);
        run(geothermal, 3, 30);
        List<PlacedFeature> geysers = geothermal.features(HydrothermalFeature.GEYSER);
        assertFalse(geysers.isEmpty());
        assertTrue(geysers.stream().allMatch(g -> (g.x() & 1) == 1));
    }

    // ── Springs, mud, alteration ──

    @Test
    void hotSpringsSulfurSpringsAndMudPotsFormInTheirBands() {
        GeothermalConfig springs = frozenConfig(0.8);
        springs.hotSpringFormationPerHour = 5;
        TerrainModel warmGround = flatTerrain(40, ANDESITE);
        Geothermal warmField = frozen(springs, warmGround, 55);
        List<EngineFrame> warm = run(warmField, 5, 30);
        assertTrue(formed(warm, HydrothermalFeature.HOT_SPRING) > 0);
        assertEquals(0, formed(warm, HydrothermalFeature.SULFUR_SPRING));
        var world = warmGround.world();
        double ground = SURFACE_Z;
        for (PlacedFeature pool : warmField.features(HydrothermalFeature.HOT_SPRING)) {
            // a shallow pit flooded to the old ground
            assertTrue(world.surfaceZ(pool.x(), pool.z()) < ground - 1e-6, "pool floor below the ground at " + pool);
            assertEquals(ground, world.waterZ(pool.x(), pool.z()), 1e-6, "pool water at " + pool);
        }

        TerrainModel hotGround = flatTerrain(40, ANDESITE);
        Geothermal hotField = frozen(springs, hotGround, 85);
        List<EngineFrame> hotter = run(hotField, 5, 30);
        assertTrue(formed(hotter, HydrothermalFeature.SULFUR_SPRING) > 0);
        WorldModel hotWorld = hotGround.world();
        assertTrue(hotField.features(HydrothermalFeature.SULFUR_SPRING).stream().anyMatch(p ->
                hotWorld.layer(p.x(), p.z(), hotWorld.layerCount(p.x(), p.z()) - 1).materialInfo() == MaterialTable.SULFUR
                        && Math.abs(hotWorld.surfaceZ(p.x(), p.z())
                                - (SURFACE_Z - Geothermal.POOL_DEPTH_M + Geothermal.SULFUR_STAGE_M)) < 1e-6),
                "sulfur-floored pools");

        GeothermalConfig mud = frozenConfig(0.5);
        mud.mudPotFormationPerHour = 5;
        TerrainModel mudGround = flatTerrain(40, ANDESITE);
        List<EngineFrame> muddy = run(frozen(mud, mudGround, 90), 5, 30);
        assertTrue(formed(muddy, HydrothermalFeature.MUD_POT) > 0);
        assertTrue(surfaces(mudGround, 40).contains(MaterialTable.CLAY), "clay-rich mud");
    }

    @Test
    void alterationAccumulatesOverTime() {
        GeothermalConfig config = frozenConfig(0.3);
        Geothermal geothermal = frozen(config, flatTerrain(40, ANDESITE), 200);
        Engine engine = engine(geothermal, 9).build();
        run(engine, geothermal, 5);
        int early = geothermal.count(HydrothermalFeature.ACID_ALTERATION);
        run(engine, geothermal, 30);
        int late = geothermal.count(HydrothermalFeature.ACID_ALTERATION);
        assertTrue(early < late, early + " < " + late);
    }

    @Test
    void cinnabarOnlyInEpithermalBand() {
        assertEquals(0, cinnabar(50));
        assertTrue(cinnabar(120) > 0);
        assertEquals(0, cinnabar(250));
    }

    private static int cinnabar(double temperatureC) {
        GeothermalConfig config = frozenConfig(0.5);
        config.cinnabarPerHour = 5;
        config.cinnabarSpringRadiusM = 0; // isolate the temperature band from the spring requirement
        TerrainModel terrain = flatTerrain(40, ANDESITE);
        Geothermal geothermal = frozen(config, terrain, temperatureC);
        run(geothermal, 11, 30);
        for (PlacedFeature f : geothermal.features(HydrothermalFeature.CINNABAR)) {
            assertEquals(terrain.world().surfaceZ(f.x(), f.z()), f.elevation(), 1e-6, "cinnabar on the ground at " + f);
        }
        return geothermal.count(HydrothermalFeature.CINNABAR);
    }

    @Test
    void submarineVentsFormOnHotSeafloor() {
        GeothermalConfig config = frozenConfig(0.5);
        config.submarineVentFormationPerHour = 5;
        TerrainModel terrain = seafloor(40, 50);
        Geothermal geothermal = frozen(config, terrain, 200);
        List<EngineFrame> frames = run(geothermal, 13, 20);
        assertTrue(formed(frames, HydrothermalFeature.SUBMARINE_VENT) > 0);
        for (PlacedFeature vent : geothermal.features(HydrothermalFeature.SUBMARINE_VENT)) {
            assertEquals(50, vent.elevation(), 1e-9);
            assertEquals(50, terrain.world().surfaceZ(vent.x(), vent.z()), 1e-6, "vents open on the sea floor");
        }
        // nothing dry-land forms underwater
        assertEquals(0, formed(frames, HydrothermalFeature.FUMAROLE));
        assertEquals(0, formed(frames, HydrothermalFeature.ACID_ALTERATION));
    }

    // ── Placement ──

    @Test
    void surfaceFeaturesSitOnTheGround() {
        GeothermalConfig config = frozenConfig(0.6);
        config.sulfurDepositPerHour = 5;
        config.geyserFormationPerHour = 2;
        config.hotSpringFormationPerHour = 2;
        config.mudPotFormationPerHour = 2;
        TerrainModel terrain = flatTerrain(40, ANDESITE);
        Geothermal geothermal = frozen(config, terrain, 105);
        run(geothermal, 21, 40);

        Set<HydrothermalFeature> onTheGround = Set.of(HydrothermalFeature.FUMAROLE, HydrothermalFeature.SULFUR_DEPOSIT,
                HydrothermalFeature.SINTER, HydrothermalFeature.MUD_POT, HydrothermalFeature.ACID_ALTERATION,
                HydrothermalFeature.CINNABAR);
        int checked = 0;
        for (PlacedFeature f : geothermal.featuresByColumn().values()) {
            if (!onTheGround.contains(f.kind())) continue;
            // at most the sulfur stages laid since it formed above its recorded elevation
            double above = terrain.world().surfaceZ(f.x(), f.z()) - f.elevation();
            assertTrue(above >= -1e-6 && above <= (config.maxSulfurStages + 1) * Geothermal.SULFUR_STAGE_M + 1e-6,
                    f + " sits " + above + " m below the ground");
            checked++;
        }
        assertTrue(checked > 0);
    }

    // ── Determinism & persistence ──

    static Geothermal activeVolcano(TerrainModel terrain) {
        GeothermalConfig config = smallConfig();
        GeothermalTest.stepTime(config, 900);
        Geothermal geothermal = live(config, terrain, new StubMagma(1150),
                List.of(VentSite.crater("main", CENTER, 3 * L), VentSite.fissure("rift", Point3.columnCentre(12, 0, SURFACE_Z, L), 0.5, 20 * L, 2)),
                liveSubsurface());
        geothermal.equilibrate(30 * DAY);
        return geothermal;
    }

    @Test
    void sameSeedSameFrames() {
        List<EngineFrame> a = run(activeVolcano(flatTerrain(40, ANDESITE)), 99, 60);
        List<EngineFrame> b = run(activeVolcano(flatTerrain(40, ANDESITE)), 99, 60);
        assertEquals(a, b);
        assertFalse(a.stream().allMatch(EngineFrame::isEmpty));
    }

    @Test
    void saveAndRestoreResumesBitForBit() {
        int half = 40;
        Geothermal straight = activeVolcano(flatTerrain(40, ANDESITE));
        List<EngineFrame> reference = run(straight, 5, 2 * half);

        // The terrain stands in for the live world; the host re-sends it after a restart.
        TerrainModel world = flatTerrain(40, ANDESITE);
        Geothermal first = activeVolcano(world);
        Engine before = engine(first, 5).build();
        List<EngineFrame> resumed = new ArrayList<>(run(before, first, half));
        InMemorySaveStore saved = Saves.save(before);

        GeothermalConfig config = smallConfig();
        GeothermalTest.stepTime(config, 900);
        Geothermal second = live(config, world, new StubMagma(1150),
                List.of(VentSite.crater("main", CENTER, 3 * L), VentSite.fissure("rift", Point3.columnCentre(12, 0, SURFACE_Z, L), 0.5, 20 * L, 2)),
                liveSubsurface());
        Engine after = engine(second, 5).restore(saved).build();
        // to the same absolute end as the reference (the first half may end past its mark)
        resumed.addAll(me.alex4386.typhon.engine.testing.Runs.until(after, 2 * half * STEP_TIME.get(config)));

        assertEquals(reference, resumed);
        assertFalse(straight.featuresByColumn().isEmpty());
        assertEquals(straight.featuresByColumn(), second.featuresByColumn());
        assertEquals(straight.temperatureAt(3, 3), second.temperatureAt(3, 3));
    }

    @Test
    void restoreRejectsChangedConfig() {
        Geothermal geothermal = activeVolcano(flatTerrain(40, ANDESITE));
        Engine engine = engine(geothermal, 1).build();
        InMemorySaveStore saved = Saves.save(engine);

        GeothermalConfig other = smallConfig();
        other.cellSizeM = 8 * L;
        Geothermal mismatched = live(other, flatTerrain(40, ANDESITE), new StubMagma(1150), List.of(), liveSubsurface());
        assertThrows(IllegalStateException.class, () -> engine(mismatched, 1).restore(saved).build());
    }

    @Test
    void stepsAtConfiguredInterval() {
        GeothermalConfig config = smallConfig();
        config.stepSeconds = 5;
        TerrainModel terrain = flatTerrain(40, ANDESITE);
        Geothermal geothermal = new Geothermal("v1", config, CENTER, new StubMagma(0), terrain,
                List.of(), new StubField(terrain, 10, 15, 0.5));
        assertEquals(5.0, geothermal.periodSeconds());
        assertEquals("geothermal:v1", geothermal.id());
    }
}
