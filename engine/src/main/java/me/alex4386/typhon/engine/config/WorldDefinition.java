package me.alex4386.typhon.engine.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.alex4386.typhon.engine.lava.LavaConfig;
import me.alex4386.typhon.engine.subsurface.SubsurfaceConfig;
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
 *   solverSpacing: 64         # dxG: heat/groundwater solver resolution (m)
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
 * geotherm: {surfaceTemperatureC: 15, gradientCPerKm: 30, lapseRateCPerKm: 6.5}
 * aquifer: {waterTableDepth: 20, specificYield: 0.1, topographyFactor: 0.6, baseLevel: .nan, rechargeFraction: 0.3}
 * terrain: {source: preset, preset: kilauea}   # free-form, interpreted by the host
 * lava: {coolingScale: 1}                       # any LavaConfig component
 * subsurface: {timeScale: 5000, macroStepSeconds: 60}   # any SubsurfaceConfig field except those
 *                                                     # set from climate/geotherm/aquifer
 * }</pre>
 *
 * @param baseStepMs engine base step (simulation resolution)
 * @param spec world-model grid and geology
 * @param scaling Froude scaling (its {@code metersPerBlock} is {@code grid.metersPerColumn})
 * @param terrain free-form initial-terrain description for the host (generator, DEM, ...)
 * @param subsurface heat/groundwater/surface-water parameters, with the climate, geotherm and aquifer
 *     values filled in; its {@code timeScale} defaults to the dormant time compression
 */
public record WorldDefinition(String name, long seed, double baseStepMs, WorldSpec spec, VolcanoScaling scaling,
        Climate climate, Geotherm geotherm, Aquifer aquifer, Map<String, Object> terrain, LavaConfig lava,
        SubsurfaceConfig subsurface) {

    /** @param windSpeed real wind speed (m/s), {@code NaN} to leave each volcano's own wind */
    public record Climate(double rainfallMmPerHour, double evaporationMmPerHour, double windSpeed, double windBearingDeg,
            double windVariability) {
        public boolean hasWind() {
            return !Double.isNaN(windSpeed);
        }
    }

    /**
     * Initial temperature field (consumed by the subsurface heat solver).
     *
     * <p>Initial ground temperature at a point {@code d} metres below a surface at elevation {@code zs}:
     * {@code T = surfaceTemperatureC − lapseRateCPerKm·zs/1000 + gradientCPerKm·d/1000}. Volcanic heat
     * (chamber halo, intrusions) is added on top by the solvers.
     *
     * @param surfaceTemperatureC mean annual ground-surface temperature at sea level (°C)
     * @param gradientCPerKm background geothermal gradient (°C/km); ~25–30 continental, 40–80 in arcs
     * @param lapseRateCPerKm decrease of surface temperature with elevation (°C/km; 6.5 = standard atmosphere)
     */
    public record Geotherm(double surfaceTemperatureC, double gradientCPerKm, double lapseRateCPerKm) {
        public Geotherm(double surfaceTemperatureC, double gradientCPerKm) {
            this(surfaceTemperatureC, gradientCPerKm, 6.5);
        }

        /** Initial temperature (°C) {@code depth} metres below a surface at {@code surfaceZ}. */
        public double initialTemperature(double surfaceZ, double depth) {
            return surfaceTemperatureC - lapseRateCPerKm * Math.max(0, surfaceZ) / 1000 + gradientCPerKm * depth / 1000;
        }
    }

    /**
     * Initial water table and aquifer properties (consumed by the groundwater solver).
     *
     * <p>Initial water-table elevation under a surface at {@code zs}:
     * {@code hw = base + topographyFactor·(zs − waterTableDepth − base)}, never above the surface, where
     * {@code base} is {@code baseLevel} or, when that is {@code NaN}, the sea level (or the lowest
     * surface of the domain without a sea). {@code topographyFactor = 1} keeps the table a constant
     * {@code waterTableDepth} below ground; {@code 0} makes it flat at the base level (very permeable
     * ground). Real water tables are subdued replicas of topography (≈0.3–0.8).
     *
     * @param waterTableDepth depth of the water table below the surface where it follows topography (m)
     * @param specificYield drainable porosity of the aquifer (–)
     * @param topographyFactor how closely the water table follows the topography (0–1)
     * @param baseLevel regional base level (m), {@code NaN} = sea level / lowest surface
     * @param rechargeFraction fraction of rainfall that reaches the water table (–)
     */
    public record Aquifer(double waterTableDepth, double specificYield, double topographyFactor, double baseLevel,
            double rechargeFraction) {
        public Aquifer {
            if (!(waterTableDepth >= 0)) throw new ConfigException("aquifer.waterTableDepth must be >= 0");
            if (!(specificYield > 0 && specificYield <= 1)) throw new ConfigException("aquifer.specificYield must be in (0, 1]");
            if (!(topographyFactor >= 0 && topographyFactor <= 1)) {
                throw new ConfigException("aquifer.topographyFactor must be in [0, 1]");
            }
            if (!(rechargeFraction >= 0 && rechargeFraction <= 1)) {
                throw new ConfigException("aquifer.rechargeFraction must be in [0, 1]");
            }
        }

        public Aquifer(double waterTableDepth, double specificYield) {
            this(waterTableDepth, specificYield, 1.0, Double.NaN, 0.3);
        }

        /** Initial water-table elevation (m) under a surface at {@code surfaceZ}, given the resolved base level. */
        public double initialWaterTable(double surfaceZ, double resolvedBase) {
            double hw = resolvedBase + topographyFactor * (surfaceZ - waterTableDepth - resolvedBase);
            return Math.min(surfaceZ, hw);
        }
    }

    public WorldDefinition {
        if (name == null || name.isBlank()) throw new ConfigException("world name is required");
        if (!(baseStepMs > 0)) throw new ConfigException("baseStepMs must be > 0");
        terrain = new LinkedHashMap<>(terrain);
        if (subsurface == null) subsurface = defaultSubsurface(scaling);
        subsurface = withDerived(subsurface, climate, geotherm, aquifer);
        try {
            subsurface.validate();
        } catch (IllegalArgumentException e) {
            throw new ConfigException("subsurface: " + e.getMessage());
        }
    }

    /** Definition with default subsurface parameters (derived from climate, geotherm and aquifer). */
    public WorldDefinition(String name, long seed, double baseStepMs, WorldSpec spec, VolcanoScaling scaling,
            Climate climate, Geotherm geotherm, Aquifer aquifer, Map<String, Object> terrain, LavaConfig lava) {
        this(name, seed, baseStepMs, spec, scaling, climate, geotherm, aquifer, terrain, lava, null);
    }

    /** {@link SubsurfaceConfig} fields set from the climate, geotherm and aquifer sections. */
    static final Set<String> SUBSURFACE_DERIVED = Set.of("rainfallMmPerHour", "evaporationMmPerHour",
            "surfaceTemperatureC", "gradientCPerKm", "initialWaterTableDepthM", "specificYield",
            "waterTableTopographyFactor", "waterTableBaseLevelM", "rechargeFraction");

    static SubsurfaceConfig defaultSubsurface(VolcanoScaling scaling) {
        SubsurfaceConfig c = new SubsurfaceConfig();
        c.timeScale = scaling.dormantTimeCompression();
        return c;
    }

    private static SubsurfaceConfig withDerived(SubsurfaceConfig base, Climate climate, Geotherm geotherm,
            Aquifer aquifer) {
        SubsurfaceConfig c = base.copy();
        c.rainfallMmPerHour = climate.rainfallMmPerHour();
        c.evaporationMmPerHour = climate.evaporationMmPerHour();
        return applyGeothermAquifer(c, geotherm, aquifer);
    }

    /**
     * Sets the subsurface fields that come from a geotherm and an aquifer section (in place; returns
     * {@code c}). Scenarios that build a subsurface outside a world use this to stay consistent with
     * the world they would write.
     */
    public static SubsurfaceConfig applyGeothermAquifer(SubsurfaceConfig c, Geotherm geotherm, Aquifer aquifer) {
        c.surfaceTemperatureC = geotherm.surfaceTemperatureC();
        c.gradientCPerKm = geotherm.gradientCPerKm();
        c.initialWaterTableDepthM = aquifer.waterTableDepth();
        c.specificYield = aquifer.specificYield();
        c.waterTableTopographyFactor = aquifer.topographyFactor();
        c.waterTableBaseLevelM = aquifer.baseLevel();
        c.rechargeFraction = aquifer.rechargeFraction();
        return c;
    }

    /** A copy of the subsurface parameters (safe to hand to a new {@code Subsurface}). */
    public SubsurfaceConfig subsurfaceConfig() {
        return subsurface.copy();
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
        double dxG = grid.number("solverSpacing", 8 * dxS);
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
                geothermNode.number("gradientCPerKm", 30), geothermNode.number("lapseRateCPerKm", 6.5));
        geothermNode.finish();

        ConfigNode aquiferNode = root.child("aquifer");
        Aquifer aquifer;
        try {
            aquifer = new Aquifer(aquiferNode.number("waterTableDepth", 20), aquiferNode.number("specificYield", 0.1),
                    aquiferNode.number("topographyFactor", 1.0), aquiferNode.number("baseLevel", Double.NaN),
                    aquiferNode.number("rechargeFraction", 0.3));
        } catch (ConfigException e) {
            throw aquiferNode.error(e.getMessage());
        }
        aquiferNode.finish();

        Map<String, Object> terrain = root.has("terrain") ? root.child("terrain").asMap() : new LinkedHashMap<>();
        if (!root.has("terrain")) root.markUsed("terrain");

        LavaConfig lava = ConfigBinder.bindRecord(root.child("lava"), LavaConfig.defaults(), Set.of(),
                Set.of("metersPerBlock"));

        SubsurfaceConfig subsurface = defaultSubsurface(scaling);
        ConfigBinder.bindFields(root.child("subsurface"), subsurface, Set.of(), SUBSURFACE_DERIVED);

        root.finish();
        return new WorldDefinition(name, seed, baseStep, spec, scaling,
                new Climate(rain, evaporation, windSpeed, windBearing, windVariability), geotherm, aquifer, terrain, lava,
                subsurface);
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
                "gradientCPerKm", geotherm.gradientCPerKm(), "lapseRateCPerKm", geotherm.lapseRateCPerKm()));
        root.put("aquifer", map("waterTableDepth", aquifer.waterTableDepth(), "specificYield", aquifer.specificYield(),
                "topographyFactor", aquifer.topographyFactor(), "baseLevel", aquifer.baseLevel(),
                "rechargeFraction", aquifer.rechargeFraction()));
        root.put("terrain", new LinkedHashMap<>(terrain));
        root.put("lava", ConfigBinder.exportRecord(lava, Set.of("metersPerBlock")));
        root.put("subsurface", ConfigBinder.exportFields(subsurface, SUBSURFACE_DERIVED));
        return root;
    }

    static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) m.put((String) keyValues[i], ConfigBinder.export(keyValues[i + 1]));
        return m;
    }
}
