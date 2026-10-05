package me.alex4386.typhon.simulator.scenario;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.config.ConfigException;
import me.alex4386.typhon.engine.config.VolcanoDefinition;
import me.alex4386.typhon.engine.config.WorldDefinition;
import me.alex4386.typhon.engine.subsurface.Subsurface;
import me.alex4386.typhon.engine.subsurface.SubsurfaceConfig;
import me.alex4386.typhon.engine.config.Yaml;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.Edifice;
import me.alex4386.typhon.engine.worlds.World;
import me.alex4386.typhon.engine.worlds.WorldDirectory;
import me.alex4386.typhon.simulator.terrain.ColumnGrid;
import me.alex4386.typhon.simulator.terrain.DemImporter;
import me.alex4386.typhon.simulator.terrain.DemTerrain;
import me.alex4386.typhon.simulator.terrain.TerrainGenerators;

/**
 * Runs world directories ({@code worlds/<name>}) in the simulator and writes world templates.
 *
 * <p>The {@code terrain} section of {@code world.yaml} is interpreted here (the engine treats it as
 * free-form host data):
 * <pre>
 * terrain: {source: preset, preset: kilauea, seed: 1}              # a preset's synthetic terrain
 * terrain: {source: dem, path: dem.tif, centerLat: 19.4069, centerLon: -155.2834, halfExtent: 256}
 *                                       # real scale: GeoTIFF / SRTM .hgt (or any DEM with mapping: real),
 *                                       # grid.metersPerColumn per column, sea level from world.yaml
 * terrain: {source: dem, path: dem.asc, cell: 30, maxMeters: 3000} # compact: ESRI ASCII grid or PNG heightmap
 * terrain: {source: twin-cones, separation: 160, height: 60, radius: 140, craterRadius: 5}
 * </pre>
 */
public final class WorldScenarios {
    static final String HEADER = "# Typhon world definition (see engine/README.md, \"World definitions\").\n"
            + "# Edit freely; the saved state in state/ records what it was run with.\n";

    private WorldScenarios() {}

    /** Opens (or starts) a world directory as a simulator scenario. */
    public static Scenario open(Path dir, World.ChangePolicy policy) {
        WorldDirectory layout = new WorldDirectory(dir);
        boolean restored = layout.hasState();
        WorldDefinition definition = layout.readWorld();
        ColumnGrid grid = terrain(definition, dir);
        World world = World.open(dir, (w, volcanoes) -> grid.toSnapshot(), policy);
        return Scenario.fromWorld(definition.name(), world, grid, restored);
    }

    /** The initial terrain described by {@code world.yaml}'s {@code terrain} section. */
    public static ColumnGrid terrain(WorldDefinition definition, Path worldDir) {
        Map<String, Object> t = definition.terrain();
        String source = string(t, "source", "preset");
        switch (source) {
            case "preset" -> {
                String name = string(t, "preset", definition.name());
                return Presets.get(name).terrain(number(t, "seed", definition.seed()).longValue());
            }
            case "dem" -> {
                String file = string(t, "path", null);
                if (file == null) throw new ConfigException("world.yaml: terrain.path: is required for source dem");
                Path path = worldDir.resolve(file);
                double cell = number(t, "cell", 30).doubleValue();
                String lower = file.toLowerCase(Locale.ROOT);
                boolean real = "real".equals(string(t, "mapping", null)) || t.containsKey("centerLat")
                        || lower.endsWith(".tif") || lower.endsWith(".tiff") || lower.endsWith(".hgt");
                try {
                    if (real) {
                        return DemTerrain.load(path, definition.spec().metersPerColumn(),
                                number(t, "halfExtent", 256).intValue(), definition.spec().seaLevelZ(),
                                number(t, "centerLat", Double.NaN).doubleValue(),
                                number(t, "centerLon", Double.NaN).doubleValue());
                    }
                    DemImporter.Dem dem = file.toLowerCase(Locale.ROOT).endsWith(".png")
                            ? DemImporter.readPng(path, 0, number(t, "maxMeters", 3000).doubleValue(), cell)
                            : DemImporter.readAscii(path, cell);
                    return DemImporter.toGrid(dem, definition.spec().metersPerColumn(),
                            number(t, "maxHalfExtent", 384).intValue());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            case "twin-cones" -> {
                return twinCones(number(t, "halfExtent", 192).intValue(), number(t, "separation", 160).intValue(),
                        number(t, "height", 60).intValue(), number(t, "radius", 140).intValue(),
                        number(t, "craterRadius", 5).intValue());
            }
            default -> throw new ConfigException("world.yaml: terrain.source: unknown source '" + source
                    + "'; expected preset, dem or twin-cones");
        }
    }

    /** Two cones on a plain, at x = ±separation/2, each with a small summit crater. */
    public static ColumnGrid twinCones(int halfExtent, int separation, int height, int radius, int craterRadius) {
        ColumnGrid grid = ColumnGrid.centered(halfExtent);
        int[] centres = {-separation / 2, separation / 2};
        for (int x = grid.minX(); x <= grid.maxX(); x++) {
            for (int z = grid.minZ(); z <= grid.maxZ(); z++) {
                double h = 0;
                for (int cx : centres) {
                    double d = Math.hypot(x - cx, z);
                    double cone = height * Math.max(0, 1 - d / radius);
                    if (d < craterRadius) cone = height - 3;
                    h = Math.max(h, cone);
                }
                grid.set(x, z, TerrainGenerators.BASE_Y + (int) Math.round(h), TerrainColumn.NO_WATER,
                        BlockId.minecraft(h > 2 ? "basalt" : "grass_block"));
            }
        }
        return grid;
    }

    // ── Templates ──

    /** Writes a world directory that reproduces a built-in preset. */
    public static void writeFromPreset(Preset preset, long seed, Path out) {
        writeFromPreset(preset, seed, out, null);
    }

    /**
     * Writes a world directory for a built-in preset; with {@code dem} (real-scale presets only) the
     * terrain comes from that DEM file, centred on the preset's coordinates, and vents are anchored
     * to it.
     */
    public static void writeFromPreset(Preset preset, long seed, Path out, Path dem) {
        RealSetting real = preset.realSetting();
        Map<String, Object> terrain = new LinkedHashMap<>();
        Scenario scenario;
        if (dem != null) {
            if (real == null) throw new IllegalArgumentException(preset.name() + " is not a real-scale preset");
            ColumnGrid grid;
            try {
                grid = DemTerrain.load(dem, real.metersPerColumn(), real.halfExtentColumns(), real.spec().seaLevelZ(),
                        real.dem().lat(), real.dem().lon());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            scenario = preset.build(seed, grid, Scenario.Options.DEFAULT);
            terrain.put("source", "dem");
            terrain.put("path", dem.toAbsolutePath().toString());
            terrain.put("centerLat", real.dem().lat());
            terrain.put("centerLon", real.dem().lon());
            terrain.put("halfExtent", real.halfExtentColumns());
        } else {
            scenario = preset.build(seed);
            terrain.put("source", "preset");
            terrain.put("preset", preset.name());
            terrain.put("seed", seed);
        }
        VolcanoScaling scaling = scenario.volcano().scaling();
        // The preset's subsurface parameters (its own model, when a volcano created one) become the
        // world's climate, geotherm, aquifer and subsurface sections. Real-scale presets keep their
        // literature-only extras (lapse rate, water-table shape), which the subsurface does not read yet.
        Subsurface subsurface = scenario.volcano().subsurface();
        SubsurfaceConfig sc = subsurface != null ? subsurface.configuration().copy()
                : VolcanoSystem.defaultSubsurfaceConfig(scaling);
        WorldDefinition.Geotherm geotherm = real != null
                ? new WorldDefinition.Geotherm(sc.surfaceTemperatureC, sc.gradientCPerKm, real.geotherm().lapseRateCPerKm())
                : new WorldDefinition.Geotherm(sc.surfaceTemperatureC, sc.gradientCPerKm);
        WorldDefinition.Aquifer aquifer = real != null
                ? new WorldDefinition.Aquifer(sc.initialWaterTableDepthM, sc.specificYield,
                        real.aquifer().topographyFactor(), real.aquifer().baseLevel(), real.aquifer().rechargeFraction())
                : new WorldDefinition.Aquifer(sc.initialWaterTableDepthM, sc.specificYield);
        WorldDefinition world = new WorldDefinition(preset.name(), seed, 50, scenario.terrain().world().spec(), scaling,
                new WorldDefinition.Climate(sc.rainfallMmPerHour, sc.evaporationMmPerHour, Double.NaN, 0, 0.3),
                geotherm, aquifer, terrain, scenario.lava().config(), sc);
        List<VolcanoDefinition> volcanoes = new ArrayList<>();
        for (VolcanoSystem v : scenario.volcanoes()) {
            VolcanoDefinition definition = VolcanoDefinition.fromSystem(v, scaling);
            for (Edifice e : scenario.terrain().world().edifices()) {
                if (e.volcanoId().equals(v.volcanoId())) {
                    definition = definition.withEdifice(e.material(), e.radiusColumns(), e.baseZ());
                }
            }
            volcanoes.add(definition);
        }
        new WorldDirectory(out).writeDefinitions(world, volcanoes, HEADER + "# Generated from preset '" + preset.name()
                + "' (" + preset.title() + ").\n");
    }

    /** Names of the example worlds {@link #writeExample} knows. */
    public static List<String> examples() {
        return List.of("twin");
    }

    /**
     * Writes an example world: {@code twin} has two cones 160 blocks apart — a basaltic one close to
     * failure (east) and a quieter andesitic one (west) — sharing terrain, lava field and history.
     */
    public static void writeExample(String name, Path out) {
        if (!name.equals("twin")) throw new IllegalArgumentException("Unknown example '" + name + "'; known: " + examples());
        WorldDefinition world = WorldDefinition.parse(Yaml.parse("world.yaml", """
                name: twin
                seed: 1
                baseStepMs: 50
                grid: {metersPerColumn: 4}
                scaling: {plumeMetersPerBlock: 100, dormantTimeCompression: 5000, eruptiveTimeCompression: 20}
                climate:
                  wind: {speed: 6, bearingDeg: 60, variability: 0.3}
                geology:
                  basement: [{material: granite, top: -600, porosity: 0.01}]
                  edificeMaterial: basalt
                terrain: {source: twin-cones, separation: 160, height: 60, radius: 140, craterRadius: 5}
                """));
        VolcanoDefinition east = VolcanoDefinition.parse("east", Yaml.parse("east.yaml", """
                name: East cone (basaltic, near failure)
                vents: [{id: summit, kind: crater, x: 80, y: 121, z: 0, radius: 5}]
                magma:
                  chamber: {center: {x: 80, y: 20, z: 0}, volume: 2.0e9, lithostaticDepth: 2000, supplyRate: 1.0,
                            initialOverpressureMPa: 14.0, initialSilicaWt: 50, rechargeSilicaWt: 50,
                            initialWaterWt: 0.5, rechargeWaterWt: 0.5, maxEruptionRate: 80}
                geothermal: {radius: 64}
                """));
        VolcanoDefinition west = VolcanoDefinition.parse("west", Yaml.parse("west.yaml", """
                name: West cone (andesitic, quiet)
                vents: [{id: summit, kind: crater, x: -80, y: 121, z: 0, radius: 5}]
                magma:
                  chamber: {center: {x: -80, y: 10, z: 0}, volume: 5.0e9, supplyRate: 0.2, initialOverpressureMPa: 4.0,
                            initialSilicaWt: 60, rechargeSilicaWt: 58, initialWaterWt: 3.5, rechargeWaterWt: 3.5,
                            initialTemperatureC: 1000}
                geothermal: {radius: 64, maxGeysers: 4}
                edifice: {material: andesite, radius: 140}
                """));
        new WorldDirectory(out).writeDefinitions(world, List.of(east, west), HEADER + "# Example world 'twin'.\n");
    }

    /** Report metadata for a world run (stands in for a preset). */
    public static Preset describe(Scenario scenario) {
        World world = scenario.session();
        List<String> lines = new ArrayList<>();
        for (VolcanoDefinition v : world.volcanoDefinitions()) {
            lines.add(v.id() + ": " + v.name() + (v.active() ? "" : " (dormant)") + ", chamber "
                    + String.format(Locale.ROOT, "%.3g m3", v.chamber().volume()) + ", SiO2 "
                    + v.chamber().initialSilicaWt() + " wt%");
        }
        String name = world.definition().name();
        return new Preset() {
            @Override public String name() { return name; }
            @Override public String title() { return "World " + name; }
            @Override public String description() {
                return "World directory run with " + world.volcanoes().size() + " volcano(es): "
                        + String.join(", ", world.volcanoes().keySet()) + ".";
            }
            @Override public List<String> references() { return lines; }
            @Override public double defaultHours() { return 1; }
            @Override public ColumnGrid terrain(long seed) { return scenario.initialTerrain(); }
            @Override public Scenario build(long seed, ColumnGrid terrain, Scenario.Options options) {
                throw new UnsupportedOperationException("world runs are opened with WorldScenarios.open");
            }
        };
    }

    private static String string(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : v.toString();
    }

    private static Number number(Map<String, Object> m, String key, Number fallback) {
        Object v = m.get(key);
        if (v == null) return fallback;
        if (v instanceof Number n) return n;
        throw new ConfigException("world.yaml: terrain." + key + ": expected a number, got " + v);
    }
}
