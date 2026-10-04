package me.alex4386.typhon.engine.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.alex4386.typhon.engine.lava.LavaConfig;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;
import me.alex4386.typhon.engine.world.WorldSpec;

/**
 * The parsed {@code world.yaml}: everything about a world that is not one volcano. A definition holds
 * parameters and initial conditions only; simulated state lives in the save.
 *
 * <pre>{@code
 * name: kilauea
 * seed: 42
 * baseStepMs: 50
 * grid:
 *   metersPerColumn: 8        # dxS: surface resolution = Minecraft block size (m)
 *   solverSpacing: 32         # dxG: heat/groundwater solver resolution (m)
 * scaling:
 *   plumeMetersPerBlock: 100
 *   dormantTimeCompression: 5000
 *   eruptiveTimeCompression: 20
 * seaLevel: .nan              # metres; .nan = no sea
 * climate:
 *   rainfallMmPerHour: 0
 *   evaporationMmPerHour: 0.1
 *   wind: {speed: 8, bearingDeg: 45, variability: 0.3}   # optional, real m/s
 * geology:
 *   datum: -2000
 *   basement:
 *     - {material: granite, top: -500, porosity: 0.01}
 *   edificeMaterial: basalt
 *   surfaceMaterial: soil
 *   surfaceThickness: 8
 * geotherm: {surfaceTemperatureC: 15, gradientCPerKm: 30}
 * aquifer: {waterTableDepth: 20, specificYield: 0.1}
 * terrain: {source: preset, preset: kilauea}   # free-form, interpreted by the host
 * lava: {coolingScale: 1}                       # any LavaConfig component
 * }</pre>
 *
 * @param baseStepMs engine base step (simulation resolution)
 * @param spec world-model grid and geology
 * @param scaling Froude scaling (its {@code metersPerBlock} is {@code grid.metersPerColumn})
 * @param terrain free-form initial-terrain description for the host (generator, DEM, ...)
 */
public record WorldDefinition(String name, long seed, double baseStepMs, WorldSpec spec, VolcanoScaling scaling,
        Climate climate, Geotherm geotherm, Aquifer aquifer, Map<String, Object> terrain, LavaConfig lava) {

    /** @param windSpeed real wind speed (m/s), {@code NaN} to leave each volcano's own wind */
    public record Climate(double rainfallMmPerHour, double evaporationMmPerHour, double windSpeed, double windBearingDeg,
            double windVariability) {
        public boolean hasWind() {
            return !Double.isNaN(windSpeed);
        }
    }

    public record Geotherm(double surfaceTemperatureC, double gradientCPerKm) {}

    /** @param waterTableDepth initial depth of the water table below the surface (m) */
    public record Aquifer(double waterTableDepth, double specificYield) {}

    public WorldDefinition {
        if (name == null || name.isBlank()) throw new ConfigException("world name is required");
        if (!(baseStepMs > 0)) throw new ConfigException("baseStepMs must be > 0");
        terrain = new LinkedHashMap<>(terrain);
    }

    public long baseStepMicros() {
        return Math.round(baseStepMs * 1000);
    }

    // ── Parsing ──

    public static WorldDefinition parse(ConfigNode root) {
        String name = root.requireString("name");
        long seed = root.longValue("seed", 0);
        double baseStep = root.number("baseStepMs", 50);

        ConfigNode grid = root.child("grid");
        double dxS = grid.number("metersPerColumn", VolcanoScaling.DEFAULT.metersPerBlock());
        double dxG = grid.number("solverSpacing", 4 * dxS);
        grid.finish();

        ConfigNode scalingNode = root.child("scaling");
        VolcanoScaling base = VolcanoScaling.DEFAULT;
        VolcanoScaling scaling;
        try {
            scaling = new VolcanoScaling(dxS,
                    scalingNode.number("plumeMetersPerBlock", base.plumeMetersPerBlock()),
                    scalingNode.number("dormantTimeCompression", base.dormantTimeCompression()),
                    scalingNode.number("eruptiveTimeCompression", base.eruptiveTimeCompression()));
        } catch (IllegalArgumentException e) {
            throw scalingNode.error(e.getMessage());
        }
        if (scalingNode.has("metersPerBlock")) {
            throw scalingNode.error("metersPerBlock", "is grid.metersPerColumn (one block is one surface column)");
        }
        scalingNode.finish();

        double seaLevel = root.number("seaLevel", Double.NaN);

        ConfigNode climateNode = root.child("climate");
        double rain = climateNode.number("rainfallMmPerHour", 0);
        double evaporation = climateNode.number("evaporationMmPerHour", 0.1);
        double windSpeed = Double.NaN;
        double windBearing = 0;
        double windVariability = 0.3;
        if (climateNode.has("wind")) {
            ConfigNode wind = climateNode.child("wind");
            windSpeed = wind.requireNumber("speed");
            windBearing = wind.number("bearingDeg", 0);
            windVariability = wind.number("variability", 0.3);
            wind.finish();
        }
        climateNode.finish();
        if (rain < 0 || evaporation < 0) throw climateNode.error("rates must be >= 0");

        ConfigNode geology = root.child("geology");
        double datum = geology.number("datum", -2000);
        List<WorldSpec.GeologyLayer> basement = new ArrayList<>();
        if (geology.has("basement")) {
            for (ConfigNode layer : geology.children("basement")) {
                try {
                    basement.add(new WorldSpec.GeologyLayer(layer.requireString("material"), layer.requireNumber("top"),
                            layer.number("porosity", 0.01)));
                } catch (IllegalArgumentException e) {
                    throw layer.error(e.getMessage());
                }
                layer.finish();
            }
        } else {
            basement.add(new WorldSpec.GeologyLayer("granite", -500, 0.01));
        }
        String edifice = geology.string("edificeMaterial", "andesite");
        String surface = geology.string("surfaceMaterial", "soil");
        double surfaceThickness = geology.number("surfaceThickness", dxS);
        geology.finish();
        WorldSpec spec;
        try {
            spec = new WorldSpec(dxS, dxG, datum, seaLevel, basement, edifice, surface, surfaceThickness);
        } catch (IllegalArgumentException e) {
            throw geology.error(e.getMessage());
        }

        ConfigNode geothermNode = root.child("geotherm");
        Geotherm geotherm = new Geotherm(geothermNode.number("surfaceTemperatureC", 15),
                geothermNode.number("gradientCPerKm", 30));
        geothermNode.finish();

        ConfigNode aquiferNode = root.child("aquifer");
        Aquifer aquifer = new Aquifer(aquiferNode.number("waterTableDepth", 20), aquiferNode.number("specificYield", 0.1));
        aquiferNode.finish();

        Map<String, Object> terrain = root.has("terrain") ? root.child("terrain").asMap() : new LinkedHashMap<>();
        if (!root.has("terrain")) root.markUsed("terrain");

        LavaConfig lava = ConfigBinder.bindRecord(root.child("lava"), LavaConfig.defaults(), Set.of(),
                Set.of("metersPerBlock"));

        root.finish();
        return new WorldDefinition(name, seed, baseStep, spec, scaling,
                new Climate(rain, evaporation, windSpeed, windBearing, windVariability), geotherm, aquifer, terrain, lava);
    }

    // ── Export ──

    /** The effective definition as a YAML tree (defaults included); also the basis of its hash. */
    public Map<String, Object> toTree() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", name);
        root.put("seed", seed);
        root.put("baseStepMs", baseStepMs);
        root.put("grid", map("metersPerColumn", spec.metersPerColumn(), "solverSpacing", spec.solverSpacing()));
        root.put("scaling", map("plumeMetersPerBlock", scaling.plumeMetersPerBlock(),
                "dormantTimeCompression", scaling.dormantTimeCompression(),
                "eruptiveTimeCompression", scaling.eruptiveTimeCompression()));
        root.put("seaLevel", ConfigBinder.export(spec.seaLevelZ()));
        Map<String, Object> climateTree = map("rainfallMmPerHour", climate.rainfallMmPerHour(),
                "evaporationMmPerHour", climate.evaporationMmPerHour());
        if (climate.hasWind()) {
            climateTree.put("wind", map("speed", climate.windSpeed(), "bearingDeg", climate.windBearingDeg(),
                    "variability", climate.windVariability()));
        }
        root.put("climate", climateTree);
        List<Object> basement = new ArrayList<>();
        for (WorldSpec.GeologyLayer layer : spec.basement()) {
            basement.add(map("material", layer.material(), "top", layer.topZ(), "porosity", layer.porosity()));
        }
        Map<String, Object> geology = map("datum", spec.datumZ());
        geology.put("basement", basement);
        geology.put("edificeMaterial", spec.edificeMaterial());
        geology.put("surfaceMaterial", spec.surfaceMaterial());
        geology.put("surfaceThickness", spec.surfaceThickness());
        root.put("geology", geology);
        root.put("geotherm", map("surfaceTemperatureC", geotherm.surfaceTemperatureC(),
                "gradientCPerKm", geotherm.gradientCPerKm()));
        root.put("aquifer", map("waterTableDepth", aquifer.waterTableDepth(), "specificYield", aquifer.specificYield()));
        root.put("terrain", new LinkedHashMap<>(terrain));
        root.put("lava", ConfigBinder.exportRecord(lava, Set.of("metersPerBlock")));
        return root;
    }

    static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) m.put((String) keyValues[i], ConfigBinder.export(keyValues[i + 1]));
        return m;
    }
}
