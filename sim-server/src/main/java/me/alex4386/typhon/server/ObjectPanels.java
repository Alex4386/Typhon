package me.alex4386.typhon.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which object each setting belongs to, how the Inspector lays objects out, and how prominent each
 * setting is: the single source of truth for the per-object panels (protocol §4.7 {@code objectPanels}).
 *
 * <p>Every setting gets an <b>owner</b> (the object it describes: a chamber, its conduit and dikes, the
 * volcano's ash, flows, springs or ground deformation, or the world), a <b>tab</b> and a <b>tier</b>:
 * <ul>
 *   <li>{@code primary} — a physical what-if input shown as a dial;
 *   <li>{@code more} — rarely touched inputs, in a collapsed "More" section of the same tab;
 *   <li>{@code internals} — numerical, solver and reporting parameters, in a collapsed "Solver internals"
 *       section at the bottom of the object.
 * </ul>
 * Values the physics computes from other settings are not dials; they are {@code derived} fields of the
 * panel (with the setting that can pin them, where pinning makes physical sense).
 */
final class ObjectPanels {
    private ObjectPanels() {}

    /** Where a setting goes: its owner (an id prefix), tab, tier and position. */
    record Place(String owner, String tab, String tier, int order) {}

    /** A rule: settings under {@code ownerSuffix} (relative to the volcano) whose key matches go to tab/tier. */
    private record Rule(Pattern key, String tab, String tier, int order) {}

    private static Rule r(String regex, String tab, String tier, int order) {
        return new Rule(Pattern.compile(regex), tab, tier, order);
    }

    private static final String PRIMARY = "primary";
    private static final String MORE = "more";
    private static final String INTERNALS = "internals";

    /** Solver, numerical and reporting parameters, wherever they appear. */
    private static final Pattern INTERNAL = Pattern.compile(
            "(?i).*(stepPeriod|samplePeriod|PeriodSeconds|StepSeconds|gridSteps|gridCells|cellSize|cfl|maxSubsteps|substep|"
                    + "Refresh|report|Report|EventSeconds|eventChange|EventRegion|maxReported|maxRecorded|maxSteam|"
                    + "threads|iterations|sorOmega|macroStep|warmEvery|demoteAfter|hotChange|maxConcurrent|"
                    + "Jitter|hypocenter|frontEvent|minAirborne|minBlock|minBomb|minExitSpeed|minCooling|"
                    + "worldTopY|ashLoadThreshold|ashFallRateThreshold|depositUpdate|depositJitter|alterableSurfaces|"
                    + "Spacing|NucleationFactor|GrowthRadius|ChangeFraction|ZoneCells|IntervalSeconds|minHazard|"
                    + "slopeSample|maxSubstepMeters|maxRecordedDikes|bubbleRadius|coalescence|surfaceTension|"
                    + "microlites|slugLength|plug|percolation|gasViscosity|referencePermeability|wallPermeability|"
                    + "turbulentFriction|wallSlip|wallFriction|exsolutionTimescale|crystallisationTimescale|"
                    + "foamStrength|brittleStress|fragmentationPorosity|maxCrystalFraction|maxEruptionRate|"
                    + "headingNoise|headingCorrelation|deflection|edificeDepthScale|minCharacteristicHeight|"
                    + "maxSpeed|freezeSpeed|stallPressure|minOpening|maxOpening|maxStrikeLength|startOffset|"
                    + "Fissure).*");

    /** A chamber's position and depth: set by placing or moving it in Build mode. */
    static final Pattern BUILD_ONLY = Pattern.compile("magma\\.(chamber|chambers\\[[^\\]]+\\])\\.(center\\..*|lithostaticDepth)");

    /** Chamber settings (main {@code magma.chamber.*} or a further chamber's element). */
    private static final List<Rule> CHAMBER = List.of(
            r("supplyRate", "supply", PRIMARY, 0),
            r("supplyVariability", "supply", MORE, 1),
            // the magma the deep supply delivers belongs with the supply; the Magma tab edits the chamber's own
            r("rechargeTemperatureC", "supply", PRIMARY, 2),
            r("rechargeSilicaWt", "supply", PRIMARY, 3),
            r("rechargeWaterWt", "supply", PRIMARY, 4),
            r("rechargeCo2Wt", "supply", MORE, 5),
            r("rechargeCrystalFraction", "supply", MORE, 6),
            r("initial.*", "magma", MORE, 10),
            r("crystalSilicaWt", "magma", MORE, 20),
            r("volume", "walls", PRIMARY, 0),
            r("tensileStrengthMPa", "walls", PRIMARY, 1),
            r("wallTemperatureC", "walls", MORE, 2),
            r("compressibilityPerMPa", "walls", MORE, 10),
            r("coolingTimescale", "walls", MORE, 11),
            r("degassingTimescale", "magma", MORE, 21),
            r("conduitRadius", "eruption", PRIMARY, 0),
            r("eruptionEndOverpressureMPa", "eruption", MORE, 10),
            r("wallRuptureRatio", "overrides", MORE, 2),
            r("wallYieldFraction", "overrides", MORE, 3),
            r("freezeVolume", "overrides", PRIMARY, 4),
            // (position and depth are set in Build mode: see BUILD_ONLY)
            r("id|chamberId", "details", MORE, 90));

    private static final List<Rule> CONDUIT = List.of(
            r("reopenOverpressureMPa|initialOpenness", "eruption", MORE, 20));

    private static final List<Rule> DIKES = List.of(
            r("blocked", "overrides", PRIMARY, 1),
            r("shearModulusPa|poissonRatio|rockDensity|fractureToughnessMPaSqrtM|regionalSigma3AzimuthDeg", "dikes", MORE, 20),
            r("enabled", "dikes", MORE, 30));

    private static final List<Rule> VOLCANO_ROOT = List.of(
            r("active", "overview", PRIMARY, 0),
            r("name", "details", MORE, 90));

    /** Where a volcano setting at dotted {@code path} goes, or null for settings no object shows. */
    static Place volcanoPlace(String volcanoId, String path) {
        String v = "volcano." + volcanoId;
        // where a chamber sits is set in Build mode (moving it), the same for every chamber: no dial
        if (BUILD_ONLY.matcher(path).matches()) return null;
        if (path.startsWith("magma.chambers[")) {
            int close = path.indexOf(']');
            String owner = v + "." + path.substring(0, close + 1);
            return place(owner, path.substring(Math.min(path.length(), close + 2)), CHAMBER, "walls");
        }
        if (path.startsWith("magma.connections[")) {
            int close = path.indexOf(']');
            String owner = v + "." + path.substring(0, close + 1);
            String key = path.substring(Math.min(path.length(), close + 2));
            boolean geometry = key.matches("radiusM|widthM|strikeLengthM|lengthM|kind|from|to");
            return new Place(owner, geometry ? "geometry" : "flow", key.matches("radiusM|widthM|open") ? PRIMARY : MORE, 0);
        }
        if (path.startsWith("magma.chamber.")) return place(v + ".magma.chamber", path.substring("magma.chamber.".length()), CHAMBER, "walls");
        if (path.startsWith("magma.conduit.")) return place(v + ".magma.conduit", path.substring("magma.conduit.".length()), CONDUIT, "eruption");
        if (path.startsWith("dikes.")) return place(v + ".dikes", path.substring("dikes.".length()), DIKES, "dikes");
        if (path.startsWith("tephra.")) return generic(v + ".tephra", path.substring(7), "ash");
        if (path.startsWith("massFlows.")) return generic(v + ".massFlows", path.substring(10), "flows");
        if (path.startsWith("geothermal.")) return generic(v + ".geothermal", path.substring(11), "springs");
        if (path.startsWith("deformation.")) return generic(v + ".deformation", path.substring(12), "deformation");
        if (path.startsWith("detail.") || path.startsWith("edifice.")) {
            return new Place(v, "details", INTERNALS, 99);
        }
        if (!path.contains(".")) return place(v, path, VOLCANO_ROOT, "details");
        return new Place(v, "details", MORE, 99);
    }

    /** Where a world setting goes (the Settings drawer: world-level only). */
    static Place worldPlace(String path) {
        String tab;
        String tier;
        if (path.startsWith("climate.")) {
            tab = "weather";
            tier = path.matches("climate\\.(rainfallMmPerHour|wind\\.(speed|bearingDeg))") ? PRIMARY : MORE;
        } else if (path.startsWith("subsurface.") || path.startsWith("aquifer") || path.startsWith("geotherm")) {
            tab = "underground";
            tier = path.matches("aquifer\\.(waterTableDepth|rechargeFraction)|geotherm\\.gradientCPerKm") ? PRIMARY : MORE;
        } else if (path.startsWith("expansion")) {
            tab = "world";
            tier = MORE;
        } else {
            tab = "world";
            tier = MORE;
        }
        if (INTERNAL.matcher(path).matches() || path.startsWith("subsurface.")) tier = path.matches("subsurface\\.(rain|evaporation).*") ? tier : INTERNALS;
        return new Place("world", tab, tier, 0);
    }

    private static Place place(String owner, String key, List<Rule> rules, String fallbackTab) {
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            if (rule.key.matcher(key).matches()) return new Place(owner, rule.tab, rule.tier, rule.order);
        }
        if (INTERNAL.matcher(key).matches()) return new Place(owner, fallbackTab, INTERNALS, 50);
        return new Place(owner, fallbackTab, MORE, 50);
    }

    /** Ash, flows, springs, ground deformation: a few physical inputs, the rest more or internals. */
    private static Place generic(String owner, String key, String tab) {
        boolean primary = key.matches("(?i)(erosionCoefficient|frictionCoefficient|maxSedimentFraction|ventHeatPowerW|"
                + "rainFailureWaterRatio|bombMedianDiameter|airDensity|shearModulusPa|poissonRatio|lahar\\.erosionCoefficient|"
                + "pdc\\.frictionCoefficient)");
        if (INTERNAL.matcher(key).matches() || key.matches("(?i).*(initialWind).*")) return new Place(owner, tab, INTERNALS, 50);
        return new Place(owner, tab, primary ? PRIMARY : MORE, primary ? 0 : 50);
    }

    // ── Panel layouts per entity kind ──

    private static JsonObject tab(String id, String title, JsonObject... sections) {
        JsonObject t = new JsonObject();
        t.addProperty("id", id);
        t.addProperty("title", title);
        JsonArray s = new JsonArray();
        for (JsonObject x : sections) s.add(x);
        t.add("sections", s);
        return t;
    }

    private static JsonObject section(String title, JsonObject... fields) {
        JsonObject s = new JsonObject();
        if (title != null) s.addProperty("title", title);
        JsonArray f = new JsonArray();
        for (JsonObject x : fields) f.add(x);
        s.add("fields", f);
        return s;
    }

    /** A measured, read-only value: an entity property. */
    private static JsonObject measure(String prop, String label, String unit, String help) {
        JsonObject f = new JsonObject();
        f.addProperty("measure", prop);
        f.addProperty("label", label);
        if (unit != null) f.addProperty("unit", unit);
        if (help != null) f.addProperty("help", help);
        return f;
    }

    /**
     * A value the physics computes from settings (an entity property, updated live); {@code pin} names the
     * setting (relative to the object's owner) that can override it, if any.
     */
    private static JsonObject derived(String prop, String label, String unit, String help, String pin) {
        JsonObject f = measure(prop, label, unit, help);
        f.addProperty("derived", true);
        if (pin != null) f.addProperty("pin", pin);
        return f;
    }

    private static JsonObject widget(String id) {
        JsonObject f = new JsonObject();
        f.addProperty("widget", id);
        return f;
    }

    /** Tabs per entity kind; params join the tab their {@link Place} names. */
    static JsonObject layouts() {
        JsonObject kinds = new JsonObject();
        JsonArray chamber = new JsonArray();
        chamber.add(tab("overview", "Overview",
                section("Right now",
                        measure("overpressureMPa", "Overpressure", "MPa", "Pressure above the surrounding rock's weight"),
                        derived("failureOverpressureMPa", "Erupts at", "MPa", "The roof fails here (lower while the conduit is still open)", null),
                        measure("eruptionRateM3PerS", "Eruption rate", "m³/s", null),
                        measure("regime", "Regime", null, null),
                        measure("styleEstimate", "Style (estimate)", null, "Estimated from the eruption as it happens"),
                        measure("vei", "VEI", null, null)),
                section("Magma budget", widget("magmaBudget")),
                section(null, widget("landscape"))));
        chamber.add(tab("magma", "Magma",
                section("In the chamber",
                        measure("temperatureC", "Temperature", "°C", null),
                        measure("silicaWt", "Silica (SiO₂)", "wt%", null),
                        measure("waterWt", "Water (H₂O)", "wt%", null),
                        measure("crystalFraction", "Crystals", "fraction", null),
                        derived("viscosityLog10", "Viscosity (log₁₀ Pa·s)", null, "From temperature, silica, water and crystals", null)),
                section("Replace the chamber's magma", widget("setMagma"))));
        chamber.add(tab("supply", "Supply",
                section(null, derived("supplyNowM3PerS", "Supply now", "m³/s", "The deep supply in effect (with its variability)", null)),
                section("New magma from depth")));
        chamber.add(tab("walls", "Walls",
                section(null,
                        derived("chamberRadiusM", "Radius", "m", "Of a sphere of the chamber's volume", null),
                        derived("ruptureOverpressureMPa", "Walls rupture at", "MPa", "Twice the rock strength (hoop stress on a sphere)", "wallRuptureRatio"),
                        derived("wallYieldFraction", "Wall yielding", null, "Share of excess magma the hot walls absorb, from wall temperature and supply", "wallYieldFraction"))));
        chamber.add(tab("eruption", "Eruption", section(null)));
        chamber.add(tab("dikes", "Dikes", section(null, measure("dikesBlocked", "New dikes blocked", null, null))));
        chamber.add(tab("overrides", "Overrides", section("Override the physics (experiments)")));
        chamber.add(tab("details", "Details",
                section(null, measure("volumeM3", "Volume", "m³", null), measure("depthM", "Depth", "m", "Set it in Build mode"),
                        measure("transferredInM3", "Received from other chambers", "m³", null),
                        measure("transferredOutM3", "Sent to other chambers", "m³", null))));
        kinds.add("chamber", kindJson(chamber));

        // the volcano as a whole: whether it is active and its surface processes
        JsonArray volcano = new JsonArray();
        volcano.add(tab("overview", "Overview", section(null, widget("volcanoState")), section(null, widget("landscape"))));
        volcano.add(tab("ash", "Ash", section(null)));
        volcano.add(tab("flows", "Flows", section(null)));
        volcano.add(tab("springs", "Hydrothermal", section(null)));
        volcano.add(tab("deformation", "Deformation", section(null)));
        volcano.add(tab("details", "Details", section(null)));
        kinds.add("volcano", kindJson(volcano));

        JsonArray connection = new JsonArray();
        connection.add(tab("flow", "Flow", section(null,
                measure("flowM3PerS", "Flow", "m³/s", null),
                measure("drivingPressureMPa", "Driving pressure", "MPa", null),
                measure("transferredM3", "Transferred so far", "m³", null),
                measure("open", "Open", null, null),
                measure("frozen", "Frozen", null, null))));
        connection.add(tab("geometry", "Geometry", section(null,
                measure("shape", "Kind", null, null),
                measure("lengthM", "Length", "m", null))));
        kinds.add("connection", kindJson(connection));

        JsonArray dike = new JsonArray();
        dike.add(tab("overview", "Overview", section(null,
                measure("status", "Status", null, null),
                measure("tipDepthM", "Tip depth", "m", null),
                measure("speedMPerS", "Rising at", "m/s", null),
                measure("openingM", "Opening", "m", null),
                measure("volumeM3", "Volume", "m³", null))));
        dike.add(tab("properties", "Properties", section(null,
                measure("strikeLengthM", "Length along strike", "m", null),
                measure("heightM", "Height", "m", null),
                measure("fissure", "Fed fissure", null, null),
                measure("startedAt", "Started", "s", null))));
        kinds.add("dike", kindJson(dike));

        JsonArray vent = new JsonArray();
        vent.add(tab("state", "State", section(null,
                widget("ventState"),
                measure("fluxM3PerS", "Flux", "m³/s", null),
                measure("craterRadiusM", "Crater radius", "m", null),
                measure("lengthM", "Length", "m", null),
                measure("feederWidthM", "Feeder width", "m", null),
                measure("segmentsOpen", "Segments open", null, null))));
        kinds.add("vent", kindJson(vent));
        kinds.add("fissure", kindJson(vent));

        JsonArray feature = new JsonArray();
        feature.add(tab("overview", "Overview", section(null,
                measure("feature", "Type", null, null),
                measure("groundTemperatureC", "Ground temperature", "°C", null),
                measure("maxTemperatureC", "Hottest", "°C", null))));
        feature.add(tab("springs", "Hydrothermal", section(null)));
        kinds.add("feature", kindJson(feature));

        JsonArray flow = new JsonArray();
        flow.add(tab("overview", "Overview", section(null,
                measure("runoutM", "Run-out", "m", null),
                measure("speedMPerS", "Speed", "m/s", null),
                measure("sedimentFraction", "Sediment", null, null))));
        flow.add(tab("flows", "Flows", section(null)));
        kinds.add("pdc", kindJson(flow));
        kinds.add("lahar", kindJson(flow));

        JsonArray plume = new JsonArray();
        plume.add(tab("overview", "Overview", section(null,
                measure("heightM", "Column height", "m", null),
                measure("massRateKgS", "Mass eruption rate", "kg/s", null))));
        plume.add(tab("ash", "Ash", section(null)));
        kinds.add("plume", kindJson(plume));

        JsonArray station = new JsonArray();
        station.add(tab("overview", "Overview", section(null, measure("station", "Station", null, null))));
        station.add(tab("deformation", "Deformation", section(null)));
        kinds.add("station", kindJson(station));

        JsonObject world = new JsonObject();
        JsonArray worldTabs = new JsonArray();
        worldTabs.add(tab("weather", "Weather", section(null, widget("weatherNow"))));
        worldTabs.add(tab("underground", "Underground", section(null)));
        worldTabs.add(tab("world", "World", section(null)));
        world.add("tabs", worldTabs);
        kinds.add("world", world);
        return kinds;
    }

    private static JsonObject kindJson(JsonArray tabs) {
        JsonObject k = new JsonObject();
        k.add("tabs", tabs);
        return k;
    }

    /** Param owners an entity of {@code kind} shows (id prefixes), given its volcano and config path. */
    static JsonArray owners(String kind, String volcanoId, String configPath) {
        JsonArray a = new JsonArray();
        String v = "volcano." + volcanoId;
        switch (kind) {
            case "chamber" -> {
                a.add(v + "." + configPath);
                if (configPath.equals("magma.chamber")) {
                    a.add(v + ".magma.conduit");
                    a.add(v + ".dikes");
                }
            }
            case "volcano" -> {
                a.add(v);
                a.add(v + ".tephra");
                a.add(v + ".massFlows");
                a.add(v + ".geothermal");
                a.add(v + ".deformation");
            }
            case "connection" -> a.add(v + "." + configPath);
            case "world" -> a.add("world");
            case "feature" -> a.add(v + ".geothermal");
            case "pdc", "lahar" -> a.add(v + ".massFlows");
            case "plume" -> a.add(v + ".tephra");
            case "station" -> a.add(v + ".deformation");
            default -> {}
        }
        return a;
    }
}
