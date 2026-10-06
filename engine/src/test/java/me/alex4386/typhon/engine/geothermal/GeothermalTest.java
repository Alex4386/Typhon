package me.alex4386.typhon.engine.geothermal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.subsurface.Subsurface;
import me.alex4386.typhon.engine.subsurface.SubsurfaceConfig;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.MagmaState;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.BlockState;
import org.junit.jupiter.api.Test;
import me.alex4386.typhon.engine.testing.Saves;
import me.alex4386.typhon.engine.save.InMemorySaveStore;

class GeothermalTest {
    static final BlockId ANDESITE = BlockId.minecraft("andesite");
    static final BlockId SAND = BlockId.minecraft("sand");
    static final int SURFACE_Y = 64;
    static final BlockPos CENTER = new BlockPos(0, SURFACE_Y, 0);

    /** Test double for the magma system. */
    static final class StubMagma implements MagmaState {
        BlockPos chamber = new BlockPos(0, 0, 0);
        double temperature = 1100;
        double overpressure = 0;
        double eruptionRate = 0;

        StubMagma(double temperature) {
            this.temperature = temperature;
        }

        @Override public BlockPos chamberCenter() { return chamber; }
        @Override public double overpressureMPa() { return overpressure; }
        @Override public double overpressureRateMPaPerSecond() { return 0; }
        @Override public double temperatureC() { return temperature; }
        @Override public double silicaWt() { return 55; }
        @Override public double waterWt() { return 3; }
        @Override public double crystalFraction() { return 0.2; }
        @Override public double eruptionRate() { return eruptionRate; }
    }

    static TerrainModel flatTerrain(int radius, BlockId surface) {
        TerrainModel terrain = new TerrainModel();
        for (int x = -radius; x < radius; x++) {
            for (int z = -radius; z < radius; z++) {
                terrain.setColumn(x, z, TerrainColumn.dry(SURFACE_Y, surface));
            }
        }
        return terrain;
    }

    static TerrainModel seafloor(int radius, int floorY) {
        TerrainModel terrain = new TerrainModel();
        for (int x = -radius; x < radius; x++) {
            for (int z = -radius; z < radius; z++) {
                terrain.setColumn(x, z, new TerrainColumn(floorY, SURFACE_Y, SAND));
            }
        }
        return terrain;
    }

    static GeothermalConfig smallConfig() {
        GeothermalConfig config = new GeothermalConfig();
        config.radius = 32;
        config.cellSize = 4;
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
    static Geothermal frozen(GeothermalConfig config, TerrainModel terrain, BlockPalette palette, double temperatureC) {
        StubField field = new StubField(terrain, config.reservoirDepthM, temperatureC,
                FROZEN_WATER.getOrDefault(config, 0.5));
        return new Geothermal("test", config, CENTER, new StubMagma(0), terrain, palette, List.of(), field);
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
        // A chamber 64 blocks (m) below a 1 m-block surface would bake the whole test area; put it at
        // a realistic depth so the vents' heat pipes dominate the shallow system.
        if (magma instanceof StubMagma stub) stub.chamber = new BlockPos(0, -3000, 0);
        Subsurface subsurface = new Subsurface(terrain.world(), subsurfaceConfig);
        Geothermal geothermal = new Geothermal("test", config, CENTER, magma, terrain, BlockPalette.unrestricted(),
                vents, subsurface);
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

    static List<BlockChange> changes(List<EngineFrame> frames) {
        return frames.stream().flatMap(f -> f.blockChanges().stream()).collect(Collectors.toList());
    }

    static long formed(List<EngineFrame> frames, HydrothermalFeature kind) {
        return events(frames, HydrothermalFeatureFormed.class).stream().filter(e -> e.feature() == kind).count();
    }

    // ── Heat supplied to and sampled from the subsurface ──

    private static final double DAY = 86400;

    @Test
    void activeVolcanoHeatsGroundAroundVent() {
        GeothermalConfig config = smallConfig();
        config.radius = 64;
        TerrainModel terrain = flatTerrain(72, ANDESITE);
        Geothermal geothermal = live(config, terrain, new StubMagma(1100), List.of(VentSite.crater("main", CENTER, 3)),
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
        Geothermal geothermal = frozen(smallConfig(), flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 15);
        assertEquals(0, geothermal.activity());
        assertTrue(geothermal.vents().isEmpty());
    }

    @Test
    void chamberAndVentsAreReportedAsHeatSources() {
        StubMagma magma = new StubMagma(1100);
        TerrainModel terrain = flatTerrain(40, ANDESITE);
        Geothermal geothermal = new Geothermal("test", smallConfig(), CENTER, magma, terrain,
                BlockPalette.unrestricted(), List.of(VentSite.crater("main", CENTER, 3)),
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
                BlockPalette.unrestricted(), List.of(), new StubField(terrain, 10, 15, 0.5));
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
        Geothermal geothermal = frozen(config, flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 15);
        geothermal.addLavaHeat(5, 5, 1150, 2);
        double expected = config.lavaConductivity * (1150 - 15) / 1.0; // W/m² through 1 m (half the flow), 1 m², 1 s
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
        Geothermal geothermal = live(config, terrain, new StubMagma(chamberC), List.of(VentSite.crater("main", CENTER, 3)),
                liveSubsurface());
        geothermal.equilibrate(30 * DAY);
        List<EngineFrame> frames = run(geothermal, 42, 100);
        return events(frames, FumaroleActivity.class).size();
    }

    @Test
    void fumarolesGrowSulfurCrustsAndSpikes() {
        GeothermalConfig config = frozenConfig(0.2);
        config.sulfurDepositPerHour = 5;
        List<EngineFrame> frames = run(frozen(config, flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 300), 7, 60);

        List<BlockChange> changes = changes(frames);
        assertTrue(formed(frames, HydrothermalFeature.FUMAROLE) > 0);
        assertTrue(changes.stream().anyMatch(c -> c.to().id().equals(GeothermalBlocks.SULFUR)));
        assertTrue(changes.stream().anyMatch(c -> c.to().id().equals(GeothermalBlocks.SULFUR_SPIKE)
                && "tip".equals(c.to().property("thickness"))
                && "up".equals(c.to().property("vertical_direction"))));

        List<FumaroleActivity> activity = events(frames, FumaroleActivity.class);
        assertFalse(activity.isEmpty());
        FumaroleActivity sample = activity.get(0);
        assertEquals(SURFACE_Y + 1, sample.pos().y());
        GasComposition gas = sample.gas();
        assertEquals(1.0, gas.h2o() + gas.co2() + gas.so2() + gas.h2s(), 1e-9);
    }

    @Test
    void spikeThicknessFollowsDripstoneSequence() {
        assertEquals(List.of("tip"), thicknesses(1));
        assertEquals(List.of("frustum", "tip"), thicknesses(2));
        assertEquals(List.of("base", "frustum", "tip"), thicknesses(3));
        assertEquals(List.of("base", "middle", "frustum", "tip"), thicknesses(4));
    }

    private static List<String> thicknesses(int height) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < height; i++) result.add(Geothermal.spikeState(i, height).property("thickness"));
        return result;
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
        List<GasHazard> hot = events(run(frozen(config, flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 340), 1, 10), GasHazard.class);
        List<GasHazard> mild = events(run(frozen(config, flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 140), 1, 10), GasHazard.class);

        Set<GasSpecies> species = hot.stream().map(GasHazard::species).collect(Collectors.toSet());
        assertEquals(Set.of(GasSpecies.CO2, GasSpecies.SO2, GasSpecies.H2S), species);
        double hotSo2 = hot.stream().filter(h -> h.species() == GasSpecies.SO2).mapToDouble(GasHazard::concentrationPpm).max().orElse(0);
        double mildSo2 = mild.stream().filter(h -> h.species() == GasSpecies.SO2).mapToDouble(GasHazard::concentrationPpm).max().orElse(0);
        assertTrue(hotSo2 > mildSo2, hotSo2 + " vs " + mildSo2);
        // Hazards stay valid until the zone's next update (refresh, change or clear).
        assertTrue(hot.stream().allMatch(h -> h.durationSeconds() == config.hazardRefreshSeconds + config.hazardIntervalSeconds));

        List<GasHazard> cold = events(run(frozen(config, flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 60), 1, 10), GasHazard.class);
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

    @Test
    void geysersFormWhereHotAndWetWithVanillaStructure() {
        List<EngineFrame> frames = run(frozen(geyserOnlyConfig(0.9), flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 140), 3, 30);
        List<GeyserFormed> geysers = events(frames, GeyserFormed.class);
        assertFalse(geysers.isEmpty());
        assertTrue(geysers.size() <= new GeothermalConfig().maxGeysers);

        Map<BlockPos, BlockChange> byPos = changes(frames).stream()
                .collect(Collectors.toMap(BlockChange::pos, c -> c, (a, b) -> b));
        for (GeyserFormed geyser : geysers) {
            int k = geyser.waterBlocks();
            assertTrue(k >= 1 && k <= 4);
            BlockPos ps = geyser.potentSulfur();
            assertEquals(SURFACE_Y - k, ps.y());
            assertEquals(GeothermalBlocks.MAGMA_BLOCK, byPos.get(ps.offset(0, -1, 0)).to().id());
            assertEquals(GeothermalBlocks.POTENT_SULFUR, byPos.get(ps).to().id());
            for (int i = 1; i <= k; i++) {
                BlockChange water = byPos.get(ps.offset(0, i, 0));
                assertEquals(GeothermalBlocks.WATER, water.to().id());
                // only the original surface block is known, so only it is compare-and-set
                assertEquals(i == k ? ANDESITE : null, water.expected());
            }
            assertNull(byPos.get(ps.offset(0, k + 1, 0)), "nothing above the pool");
        }

        // geysers keep their spacing
        for (GeyserFormed a : geysers) {
            for (GeyserFormed b : geysers) {
                if (a == b) continue;
                int d = Math.max(Math.abs(a.potentSulfur().x() - b.potentSulfur().x()),
                        Math.abs(a.potentSulfur().z() - b.potentSulfur().z()));
                assertTrue(d > new GeothermalConfig().geyserSpacing);
            }
        }
    }

    @Test
    void noFeaturesFormOnFreshLava() {
        // the same hot, wet ground that grows geysers in the test above, but freshly covered by lava
        Geothermal geothermal = frozen(geyserOnlyConfig(0.9), flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 140);
        for (int x = -40; x < 40; x++) {
            for (int z = -40; z < 40; z++) geothermal.addLavaHeat(x, z, 1150, 2);
        }
        assertTrue(geothermal.lavaCovered(0, 0));
        List<EngineFrame> frames = run(geothermal, 3, 30);
        assertTrue(events(frames, GeyserFormed.class).isEmpty(), "no geyser on a lava flow");
        assertTrue(geothermal.featuresByColumn().isEmpty());
    }

    @Test
    void lavaBuriesFeaturesItReaches() {
        Geothermal geothermal = frozen(geyserOnlyConfig(0.9), flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 140);
        Engine engine = engine(geothermal, 3).build();
        List<GeyserFormed> geysers = events(run(engine, geothermal, 30), GeyserFormed.class);
        assertFalse(geysers.isEmpty());
        GeyserFormed first = geysers.get(0);
        int x = first.potentSulfur().x();
        int z = first.potentSulfur().z();
        assertNotNull(geothermal.featuresByColumn().get(PlacedFeature.key(x, z)));

        geothermal.addLavaHeat(x, z, 1150, 3);
        List<HydrothermalFeatureBuried> buried = events(run(engine, geothermal, 1), HydrothermalFeatureBuried.class);
        assertEquals(1, buried.stream().filter(b -> b.pos().x() == x && b.pos().z() == z).count(), buried.toString());
        assertEquals(HydrothermalFeature.GEYSER, buried.get(0).feature());
        assertNull(geothermal.featuresByColumn().get(PlacedFeature.key(x, z)), "its entity goes with it");
    }

    @Test
    void noGeysersWhenDryColdOrUnsupported() {
        assertTrue(events(run(frozen(geyserOnlyConfig(0.3), flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 140), 3, 30),
                GeyserFormed.class).isEmpty(), "dry");
        assertTrue(events(run(frozen(geyserOnlyConfig(0.9), flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 60), 3, 30),
                GeyserFormed.class).isEmpty(), "cold");
        assertTrue(events(run(frozen(geyserOnlyConfig(0.9), flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 260), 3, 30),
                GeyserFormed.class).isEmpty(), "too hot (vapour-dominated)");

        BlockPalette legacy = GeothermalBlocks.defaultPalette(Set.of(
                ANDESITE, GeothermalBlocks.WATER, GeothermalBlocks.MAGMA_BLOCK, GeothermalBlocks.CALCITE));
        assertTrue(events(run(frozen(geyserOnlyConfig(0.9), flatTerrain(40, ANDESITE), legacy, 140), 3, 30),
                GeyserFormed.class).isEmpty(), "no potent sulfur");
    }

    @Test
    void geysersNeedContainingWalls() {
        // Single-block-wide ridges: every column has a lower neighbour, so water would spill.
        TerrainModel terrain = new TerrainModel();
        for (int x = -40; x < 40; x++) {
            for (int z = -40; z < 40; z++) {
                terrain.setColumn(x, z, TerrainColumn.dry(SURFACE_Y + ((x & 1) == 0 ? 2 : 0), ANDESITE));
            }
        }
        // Even columns are ridges (spill), odd columns are trenches (contained).
        List<GeyserFormed> geysers = events(run(frozen(geyserOnlyConfig(0.9), terrain, BlockPalette.unrestricted(), 140), 3, 30),
                GeyserFormed.class);
        assertFalse(geysers.isEmpty());
        assertTrue(geysers.stream().allMatch(g -> (g.potentSulfur().x() & 1) == 1));
    }

    // ── Springs, mud, alteration ──

    @Test
    void hotSpringsSulfurSpringsAndMudPotsFormInTheirBands() {
        GeothermalConfig springs = frozenConfig(0.8);
        springs.hotSpringFormationPerHour = 5;
        List<EngineFrame> warm = run(frozen(springs, flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 55), 5, 30);
        assertTrue(formed(warm, HydrothermalFeature.HOT_SPRING) > 0);
        assertEquals(0, formed(warm, HydrothermalFeature.SULFUR_SPRING));
        assertTrue(changes(warm).stream().anyMatch(c -> c.to().id().equals(GeothermalBlocks.WATER)
                && c.pos().y() == SURFACE_Y && ANDESITE.equals(c.expected())));

        List<EngineFrame> hotter = run(frozen(springs, flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 85), 5, 30);
        assertTrue(formed(hotter, HydrothermalFeature.SULFUR_SPRING) > 0);
        assertTrue(changes(hotter).stream().anyMatch(c -> c.to().id().equals(GeothermalBlocks.POTENT_SULFUR)
                && c.pos().y() == SURFACE_Y - 1));

        GeothermalConfig mud = frozenConfig(0.5);
        mud.mudPotFormationPerHour = 5;
        List<EngineFrame> muddy = run(frozen(mud, flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 90), 5, 30);
        assertTrue(formed(muddy, HydrothermalFeature.MUD_POT) > 0);
        assertTrue(changes(muddy).stream().anyMatch(c -> c.to().id().equals(GeothermalBlocks.MUD)));
    }

    @Test
    void alterationAccumulatesOverTime() {
        GeothermalConfig config = frozenConfig(0.3);
        Geothermal geothermal = frozen(config, flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 200);
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
        config.cinnabarSpringRadius = 0; // isolate the temperature band from the spring requirement
        Geothermal geothermal = frozen(config, flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), temperatureC);
        List<EngineFrame> frames = run(geothermal, 11, 30);
        long placed = changes(frames).stream().filter(c -> c.to().id().equals(GeothermalBlocks.CINNABAR)).count();
        assertEquals(geothermal.count(HydrothermalFeature.CINNABAR), placed);
        return geothermal.count(HydrothermalFeature.CINNABAR);
    }

    @Test
    void submarineVentsFormOnHotSeafloor() {
        GeothermalConfig config = frozenConfig(0.5);
        config.submarineVentFormationPerHour = 5;
        Geothermal geothermal = frozen(config, seafloor(40, 50), BlockPalette.unrestricted(), 200);
        List<EngineFrame> frames = run(geothermal, 13, 20);
        assertTrue(formed(frames, HydrothermalFeature.SUBMARINE_VENT) > 0);
        assertTrue(changes(frames).stream().anyMatch(c -> c.to().id().equals(GeothermalBlocks.MAGMA_BLOCK)
                && c.pos().y() == 50 && SAND.equals(c.expected())));
        // nothing dry-land forms underwater
        assertEquals(0, formed(frames, HydrothermalFeature.FUMAROLE));
        assertEquals(0, formed(frames, HydrothermalFeature.ACID_ALTERATION));
    }

    // ── Palette & CAS ──

    @Test
    void fallbackPaletteReplacesMissingBlocks() {
        BlockPalette legacy = GeothermalBlocks.defaultPalette(Set.of(
                ANDESITE, GeothermalBlocks.YELLOW_TERRACOTTA, GeothermalBlocks.POINTED_DRIPSTONE,
                GeothermalBlocks.TERRACOTTA, GeothermalBlocks.CLAY, GeothermalBlocks.RED_TERRACOTTA,
                GeothermalBlocks.WHITE_TERRACOTTA, GeothermalBlocks.ORANGE_TERRACOTTA, GeothermalBlocks.WATER));
        GeothermalConfig config = frozenConfig(0.2);
        config.sulfurDepositPerHour = 5;
        List<BlockChange> changes = changes(run(frozen(config, flatTerrain(40, ANDESITE), legacy, 300), 7, 60));

        Set<BlockId> placed = new HashSet<>();
        changes.forEach(c -> placed.add(c.to().id()));
        assertFalse(placed.contains(GeothermalBlocks.SULFUR));
        assertFalse(placed.contains(GeothermalBlocks.SULFUR_SPIKE));
        assertTrue(placed.contains(GeothermalBlocks.YELLOW_TERRACOTTA));
        assertTrue(placed.contains(GeothermalBlocks.POINTED_DRIPSTONE));
        assertTrue(changes.stream()
                .filter(c -> c.to().id().equals(GeothermalBlocks.POINTED_DRIPSTONE))
                .allMatch(c -> c.to().property("thickness") != null));
    }

    @Test
    void surfaceEditsAreCompareAndSet() {
        GeothermalConfig config = frozenConfig(0.6);
        config.sulfurDepositPerHour = 5;
        config.geyserFormationPerHour = 2;
        config.hotSpringFormationPerHour = 2;
        config.mudPotFormationPerHour = 2;
        TerrainModel terrain = flatTerrain(40, ANDESITE);
        List<EngineFrame> frames = run(frozen(config, terrain, BlockPalette.unrestricted(), 105), 21, 40);

        List<BlockChange> all = changes(frames);
        assertFalse(all.isEmpty());
        for (BlockChange change : all) {
            if (change.pos().y() >= SURFACE_Y) {
                assertNotNull(change.expected(), "surface/above-surface edit must be CAS: " + change);
            }
        }
        // terrain model mirrors surface edits
        for (BlockChange change : all) {
            if (change.pos().y() == SURFACE_Y && !change.to().id().equals(GeothermalBlocks.WATER)) {
                TerrainColumn column = terrain.column(change.pos().x(), change.pos().z());
                assertEquals(change.pos().y(), column.groundY());
            }
        }
    }

    // ── Determinism & persistence ──

    static Geothermal activeVolcano(TerrainModel terrain) {
        GeothermalConfig config = smallConfig();
        GeothermalTest.stepTime(config, 900);
        Geothermal geothermal = live(config, terrain, new StubMagma(1150),
                List.of(VentSite.crater("main", CENTER, 3), VentSite.fissure("rift", new BlockPos(12, 64, 0), 0.5, 20)),
                liveSubsurface());
        geothermal.equilibrate(30 * DAY);
        return geothermal;
    }

    @Test
    void sameSeedSameFrames() {
        List<EngineFrame> a = run(activeVolcano(flatTerrain(40, ANDESITE)), 99, 60);
        List<EngineFrame> b = run(activeVolcano(flatTerrain(40, ANDESITE)), 99, 60);
        assertEquals(a, b);
        assertFalse(changes(a).isEmpty());
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
                List.of(VentSite.crater("main", CENTER, 3), VentSite.fissure("rift", new BlockPos(12, 64, 0), 0.5, 20)),
                liveSubsurface());
        Engine after = engine(second, 5).restore(saved).build();
        resumed.addAll(run(after, second, half));

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
        other.cellSize = 8;
        Geothermal mismatched = live(other, flatTerrain(40, ANDESITE), new StubMagma(1150), List.of(), liveSubsurface());
        assertThrows(IllegalStateException.class, () -> engine(mismatched, 1).restore(saved).build());
    }

    @Test
    void stepsAtConfiguredInterval() {
        GeothermalConfig config = smallConfig();
        config.stepSeconds = 5;
        TerrainModel terrain = flatTerrain(40, ANDESITE);
        Geothermal geothermal = new Geothermal("v1", config, CENTER, new StubMagma(0), terrain,
                BlockPalette.unrestricted(), List.of(), new StubField(terrain, 10, 15, 0.5));
        assertEquals(5.0, geothermal.periodSeconds());
        assertEquals("geothermal:v1", geothermal.id());
        assertTrue(BlockState.parse("minecraft:sulfur_spike[thickness=tip,vertical_direction=up]")
                .equals(Geothermal.spikeState(0, 1)));
    }
}
