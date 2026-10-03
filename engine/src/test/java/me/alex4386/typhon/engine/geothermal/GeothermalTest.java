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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.EngineFrame;
import me.alex4386.typhon.engine.sim.Engine;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.MagmaState;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.BlockState;
import org.junit.jupiter.api.Test;

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

    /**
     * Config for a frozen, uniform thermal state: no diffusion, no loss, no boiling buffer, no
     * sources, water pinned at {@code water}, and two simulated hours per step.
     */
    static GeothermalConfig frozenConfig(double water) {
        GeothermalConfig config = smallConfig();
        config.diffusivity = 0;
        config.surfaceLossPerSecond = 0;
        config.boilOffPerSecond = 0;
        config.boilingBufferPerSecond = 0;
        config.lakeSaturationBonus = 0;
        config.baseSaturation = water;
        config.elevationSaturationPerBlock = 0;
        config.timeScale = 3600;
        return config;
    }

    static Geothermal frozen(GeothermalConfig config, TerrainModel terrain, BlockPalette palette, double temperatureC) {
        Geothermal geothermal = new Geothermal(
                "test", config, CENTER, new StubMagma(0), terrain, palette, List.of());
        geothermal.grid().fillExcess(temperatureC - config.ambientC);
        geothermal.grid().fillWater(config.baseSaturation);
        return geothermal;
    }

    static List<EngineFrame> run(Geothermal geothermal, long seed, int steps) {
        Engine engine = Engine.builder(seed).add(geothermal).build();
        return run(engine, geothermal, steps);
    }

    static List<EngineFrame> run(Engine engine, Geothermal geothermal, int steps) {
        List<EngineFrame> frames = new ArrayList<>();
        for (int i = 0; i < steps * geothermal.interval(); i++) frames.add(engine.tick());
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

    // ── Heat field ──

    @Test
    void activeVolcanoHeatsGroundAroundVentAndReachesBoundedSteadyState() {
        GeothermalConfig config = smallConfig();
        config.radius = 64;
        TerrainModel terrain = flatTerrain(72, ANDESITE);
        Geothermal geothermal = new Geothermal("test", config, CENTER, new StubMagma(1100), terrain,
                BlockPalette.unrestricted(), List.of(VentSite.crater("main", CENTER, 3)));

        geothermal.equilibrate(50 * 3600);
        double peak = geothermal.grid().maxExcess();
        geothermal.equilibrate(50 * 3600);
        double later = geothermal.grid().maxExcess();

        double bound = (config.ventHeatRate + config.chamberHeatRate) * geothermal.activity() / config.surfaceLossPerSecond;
        assertTrue(later > 50, "vent should get hot, was " + later);
        assertTrue(later <= bound, later + " exceeds analytic bound " + bound);
        assertEquals(peak, later, peak * 0.01, "should have converged");

        double atVent = geothermal.temperatureAt(0, 0);
        double near = geothermal.temperatureAt(24, 0);
        double far = geothermal.temperatureAt(56, 0);
        assertTrue(atVent > near && near > far, atVent + " > " + near + " > " + far);
    }

    @Test
    void coldChamberProducesNoHeat() {
        TerrainModel terrain = flatTerrain(40, ANDESITE);
        Geothermal geothermal = new Geothermal("test", smallConfig(), CENTER, new StubMagma(500), terrain,
                BlockPalette.unrestricted(), List.of(VentSite.crater("main", CENTER, 3)));
        assertEquals(0, geothermal.activity());
        geothermal.equilibrate(10 * 3600);
        assertEquals(0, geothermal.grid().maxExcess());
    }

    @Test
    void eruptionAndOverpressureRaiseActivity() {
        StubMagma magma = new StubMagma(1100);
        Geothermal geothermal = new Geothermal("test", smallConfig(), CENTER, magma, flatTerrain(40, ANDESITE),
                BlockPalette.unrestricted(), List.of());
        double quiet = geothermal.activity();
        magma.overpressure = 10;
        double pressurised = geothermal.activity();
        magma.eruptionRate = 10;
        double erupting = geothermal.activity();
        assertTrue(quiet < pressurised && pressurised < erupting);
    }

    @Test
    void lavaHeatWarmsTheCell() {
        Geothermal geothermal = new Geothermal("test", smallConfig(), CENTER, new StubMagma(0), flatTerrain(40, ANDESITE),
                BlockPalette.unrestricted(), List.of());
        double before = geothermal.temperatureAt(5, 5);
        geothermal.addLavaHeat(5, 5, 1150, 2);
        assertTrue(geothermal.temperatureAt(5, 5) > before + 5);
        assertEquals(before, geothermal.temperatureAt(-20, -20));
    }

    @Test
    void hotGroundDriesOut() {
        GeothermalConfig config = frozenConfig(0.8);
        config.boilOffPerSecond = 1.0 / 3600;
        Geothermal geothermal = frozen(config, flatTerrain(40, ANDESITE), BlockPalette.unrestricted(), 300);
        geothermal.equilibrate(20 * 3600);
        assertTrue(geothermal.waterAt(0, 0) < 0.4, "water " + geothermal.waterAt(0, 0));
    }

    @Test
    void lowGroundIsWetterThanHighGround() {
        GeothermalConfig config = smallConfig();
        TerrainModel terrain = new TerrainModel();
        for (int x = -40; x < 40; x++) {
            for (int z = -40; z < 40; z++) {
                terrain.setColumn(x, z, TerrainColumn.dry(SURFACE_Y + x / 4, ANDESITE)); // slope rising to +x
            }
        }
        Geothermal geothermal = new Geothermal("test", config, CENTER, new StubMagma(0), terrain,
                BlockPalette.unrestricted(), List.of());
        geothermal.equilibrate(20 * 3600);
        // On a uniform slope the local mean equals the cell height, except at the grid edges.
        assertTrue(geothermal.waterAt(-31, 0) > geothermal.waterAt(31, 0));
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
        config.timeScale = 600;
        TerrainModel terrain = flatTerrain(40, ANDESITE);
        Geothermal geothermal = new Geothermal("test", config, CENTER, new StubMagma(chamberC), terrain,
                BlockPalette.unrestricted(), List.of(VentSite.crater("main", CENTER, 3)));
        geothermal.equilibrate(30 * 3600);
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
        Engine engine = Engine.builder(9).add(geothermal).build();
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
        config.timeScale = 900;
        Geothermal geothermal = new Geothermal("test", config, CENTER, new StubMagma(1150), terrain,
                BlockPalette.unrestricted(), List.of(VentSite.crater("main", CENTER, 3), VentSite.fissure("rift", new BlockPos(12, 64, 0), 0.5, 20)));
        geothermal.equilibrate(20 * 3600);
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
        Engine before = Engine.builder(5).add(first).build();
        List<EngineFrame> resumed = new ArrayList<>(run(before, first, half));
        String saved = before.saveState().toString();

        GeothermalConfig config = smallConfig();
        config.timeScale = 900;
        Geothermal second = new Geothermal("test", config, CENTER, new StubMagma(1150), world,
                BlockPalette.unrestricted(), List.of(VentSite.crater("main", CENTER, 3), VentSite.fissure("rift", new BlockPos(12, 64, 0), 0.5, 20)));
        Engine after = Engine.builder(5).add(second).restore(JsonParser.parseString(saved).getAsJsonObject()).build();
        resumed.addAll(run(after, second, half));

        assertEquals(reference, resumed);
        assertFalse(straight.featuresByColumn().isEmpty());
        assertEquals(straight.featuresByColumn(), second.featuresByColumn());
        assertEquals(straight.temperatureAt(3, 3), second.temperatureAt(3, 3));
    }

    @Test
    void restoreRejectsMismatchedGrid() {
        Geothermal geothermal = activeVolcano(flatTerrain(40, ANDESITE));
        Engine engine = Engine.builder(1).add(geothermal).build();
        String saved = engine.saveState().toString();

        GeothermalConfig other = smallConfig();
        other.cellSize = 8;
        Geothermal mismatched = new Geothermal("test", other, CENTER, new StubMagma(1150), flatTerrain(40, ANDESITE),
                BlockPalette.unrestricted(), List.of());
        assertThrows(IllegalArgumentException.class, () -> Engine.builder(1).add(mismatched)
                .restore(JsonParser.parseString(saved).getAsJsonObject()).build());
    }

    @Test
    void stepsAtConfiguredInterval() {
        GeothermalConfig config = smallConfig();
        config.stepSeconds = 5;
        Geothermal geothermal = new Geothermal("v1", config, CENTER, new StubMagma(0), flatTerrain(40, ANDESITE),
                BlockPalette.unrestricted(), List.of());
        assertEquals(100, geothermal.interval());
        assertEquals("geothermal:v1", geothermal.id());
        assertTrue(BlockState.parse("minecraft:sulfur_spike[thickness=tip,vertical_direction=up]")
                .equals(Geothermal.spikeState(0, 1)));
    }
}
