package me.alex4386.typhon.simulator.scenario;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.alex4386.typhon.engine.config.ConfigException;
import me.alex4386.typhon.engine.config.WorldDefinition;
import me.alex4386.typhon.engine.config.Yaml;
import me.alex4386.typhon.engine.worlds.WorldDirectory;
import me.alex4386.typhon.simulator.terrain.ColumnGrid;
import me.alex4386.typhon.simulator.terrain.RealTerrain;

/**
 * Empty worlds: terrain and sea only, no volcano. The user places magma chambers into them and whatever
 * forms (vents, cones, islands) comes out of the eruptions. Terrain comes from a template:
 * <ul>
 *   <li>{@code ocean}: a smooth, gently sloping sea floor {@code depthM} below sea level (Surtsey's
 *       shelf was ~130 m deep, Thorarinsson 1967);
 *   <li>{@code flat}: level land at {@code elevationM};
 *   <li>{@code slope}: land rising towards −x by {@code slope} (m per m) from {@code elevationM} at the
 *       centre.
 * </ul>
 * The terrain is a continuous, unbounded surface, so the world can grow over it on demand.
 */
public final class WorldTemplates {
    private WorldTemplates() {}

    /** Template names, in the order the visualizer offers them. */
    public static final List<String> NAMES = List.of("ocean", "flat", "slope");

    /**
     * Parameters of an empty world.
     *
     * @param kind {@code ocean}, {@code flat} or {@code slope}
     * @param coreExtentM width (m) of the initially simulated square
     * @param metersPerColumn column size (m)
     * @param depthM sea-floor depth below sea level at the centre ({@code ocean})
     * @param elevationM land elevation at the centre ({@code flat}, {@code slope})
     * @param slope gradient (m per m): the sea floor deepens towards +x (and shoals towards −x without
     *     ever surfacing), land rises towards −x
     * @param seaLevelZ sea level (m); {@code NaN} for no sea ({@code ocean} always has one, default 0)
     * @param roughnessM amplitude of gentle undulation (m)
     */
    public record Template(String kind, double coreExtentM, double metersPerColumn, double depthM, double elevationM,
            double slope, double seaLevelZ, double roughnessM) {
        public Template {
            if (!NAMES.contains(kind)) throw new IllegalArgumentException("Unknown template '" + kind + "'; known: " + NAMES);
            if (!(coreExtentM >= 640)) throw new IllegalArgumentException("coreExtentM must be at least 640 m");
            if (!(metersPerColumn > 0)) throw new IllegalArgumentException("metersPerColumn must be positive");
            if (kind.equals("ocean") && !(depthM > 0)) throw new IllegalArgumentException("depthM must be positive");
            if (kind.equals("ocean") && Double.isNaN(seaLevelZ)) seaLevelZ = 0;
            if (!(roughnessM >= 0)) throw new IllegalArgumentException("roughnessM must be >= 0");
        }

        /** The defaults of a template: an 8 km square of 10 m columns. */
        public static Template defaults(String kind) {
            return switch (kind) {
                case "ocean" -> new Template(kind, 8_000, 10, 130, 0, 0.01, 0, 2);
                case "flat" -> new Template(kind, 8_000, 10, 0, 100, 0, Double.NaN, 2);
                case "slope" -> new Template(kind, 8_000, 10, 0, 300, 0.05, Double.NaN, 2);
                default -> throw new IllegalArgumentException("Unknown template '" + kind + "'; known: " + NAMES);
            };
        }

        /**
         * Elevation (m) at a point (m from the centre). The same function is the context terrain and the
         * ground new tiles are generated from, so it must hold for any distance: the ocean floor
         * deepens linearly towards +x and shoals towards −x only asymptotically, d·exp(s·x/d) (the same
         * depth and gradient at the centre), so open ocean never turns into land on the horizon.
         */
        double elevation(double xm, double zm) {
            return switch (kind) {
                case "ocean" -> seaLevelZ - (xm >= 0 || depthM <= 0 ? depthM + slope * xm : depthM * Math.exp(slope * xm / depthM));
                case "flat" -> elevationM;
                default -> elevationM - slope * xm;
            };
        }

        String terrainSection(long seed) {
            return String.format(Locale.ROOT, "{source: template, template: %s, seed: %d, coreExtentM: %s, %s: %s, slope: %s,"
                    + " roughnessM: %s}", kind, seed, fmt(coreExtentM), kind.equals("ocean") ? "depthM" : "elevationM",
                    fmt(kind.equals("ocean") ? depthM : elevationM), fmt(slope), fmt(roughnessM));
        }

        static Template fromTerrain(Map<String, Object> t, double metersPerColumn, double seaLevelZ) {
            String kind = String.valueOf(t.getOrDefault("template", "ocean"));
            Template d = defaults(kind);
            return new Template(kind, num(t, "coreExtentM", d.coreExtentM), metersPerColumn, num(t, "depthM", d.depthM),
                    num(t, "elevationM", d.elevationM), num(t, "slope", d.slope), seaLevelZ, num(t, "roughnessM", d.roughnessM));
        }
    }

    /** Writes {@code out} as an empty world (no volcanoes) from {@code template}. */
    public static void writeEmpty(Template template, String name, long seed, Path out) {
        double l = template.metersPerColumn();
        boolean sea = !Double.isNaN(template.seaLevelZ());
        String surface = template.kind().equals("ocean") ? "sediment" : "basalt";
        String yaml = String.format(Locale.ROOT, """
                name: %s
                seed: %d
                baseStepMs: 50
                grid: {metersPerColumn: %s, solverSpacing: %s}
                scaling: {plumeMetersPerBlock: %s}
                seaLevel: %s
                climate:
                  rainfallMmPerHour: 0.0
                  evaporationMmPerHour: 0.1
                  wind: {speed: 8, bearingDeg: 60, variability: 0.6}
                geology:
                  datum: -4000
                  basement:
                  - {material: basalt, top: %s, porosity: 0.05}
                  edificeMaterial: basalt
                  surfaceMaterial: %s
                  surfaceThickness: %s
                geotherm: {surfaceTemperatureC: %s, gradientCPerKm: 60, lapseRateCPerKm: 6.5}
                aquifer: {waterTableDepth: %s, specificYield: 0.2, topographyFactor: 0.0, baseLevel: %s, rechargeFraction: 0.5}
                terrain: %s
                """, name, seed, fmt(l), fmt(4 * l), fmt(l),
                sea ? fmt(template.seaLevelZ()) : "'.nan'", fmt(Math.min(template.elevation(0, 0), 0) - 400), surface,
                fmt(2 * l), sea ? "5" : "15", sea ? "0" : "30", sea ? fmt(template.seaLevelZ()) : "'.nan'",
                template.terrainSection(seed));
        WorldDefinition world = WorldDefinition.parse(Yaml.parse("world.yaml", yaml));
        new WorldDirectory(out).writeDefinitions(world, List.of(), WorldScenarios.HEADER + "# Empty world from the '"
                + template.kind() + "' template: no volcano yet; place a magma chamber to start one.\n");
    }

    /** The terrain of a {@code source: template} world: continuous and unbounded (it can grow). */
    static ColumnGrid terrain(WorldDefinition definition) {
        Map<String, Object> t = definition.terrain();
        double l = definition.spec().metersPerColumn();
        Template template = Template.fromTerrain(t, l, definition.spec().seaLevelZ());
        long seed = ((Number) t.getOrDefault("seed", definition.seed())).longValue();
        int half = WorldScenarios.halfColumns(template.coreExtentM(), l);
        boolean ocean = template.kind().equals("ocean");
        return RealTerrain.build(l, half, seed, template::elevation, template.roughnessM(), 300,
                definition.spec().seaLevelZ(), RealPresets.rock("basalt", ocean ? -1e9 : 1e9));
    }

    private static double num(Map<String, Object> t, String key, double fallback) {
        Object v = t.get(key);
        if (v == null) return fallback;
        if (v instanceof Number n) return n.doubleValue();
        throw new ConfigException("world.yaml: terrain." + key + ": expected a number");
    }

    private static String fmt(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e15 ? String.format(Locale.ROOT, "%.1f", v) : Double.toString(v);
    }
}
