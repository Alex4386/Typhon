package me.alex4386.typhon.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;
import me.alex4386.typhon.engine.config.ConfigNode;
import me.alex4386.typhon.engine.config.VolcanoDefinition;
import me.alex4386.typhon.engine.config.WorldDefinition;
import me.alex4386.typhon.engine.config.Yaml;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.magma.MagmaCommands;
import me.alex4386.typhon.engine.worlds.ConfigChanges;
import me.alex4386.typhon.engine.worlds.WorldDirectory;

/**
 * Parameter schema and changes for world sessions (protocol §3.6).
 *
 * <p>Parameters are not a hand-maintained list: every numeric or boolean leaf of the world's
 * definition trees ({@link WorldDefinition#toTree()}, {@link VolcanoDefinition#toTree()}) is one,
 * with id {@code world.<path>} or {@code volcano.<id>.<path>}. Whether a change is live ("hot") or
 * restarts the volcano comes from {@link ConfigChanges}, the same rule that governs editing the
 * YAML by hand. {@link #META} only adds labels, units, ranges and help to the important ones; a
 * new config field shows up automatically with a label derived from its name.
 *
 * <p>Changes are written to the world's YAML and applied by saving the session and reopening the
 * world ({@link Session#reopenWorld}). Defaults are the definitions as they were before the first
 * change ({@code tuning/baseline.json}); every change is logged in {@code tuning/audit.json}.
 */
final class Tuning {
    static final String DIR = "tuning";
    static final String BASELINE = "baseline.json";
    static final String AUDIT = "audit.json";
    static final int AUDIT_LIMIT = 200;

    private Tuning() {}

    /** Display metadata for one parameter path. */
    record Meta(String label, String unit, Double min, Double max, boolean log, String help) {}

    private static Meta m(String label, String unit, Double min, Double max, boolean log, String help) {
        return new Meta(label, unit, min, max, log, help);
    }

    /** Curated metadata by scope ({@code world} / {@code volcano}) and path. Order = display order. */
    static final Map<String, Meta> META = new LinkedHashMap<>();

    static {
        META.put("world:climate.rainfallMmPerHour", m("Rain", "mm/h", 0.0, 200.0, false,
                "Rain over the whole map. Soaks in, fills lakes, can start mudflows on fresh ash."));
        META.put("world:climate.evaporationMmPerHour", m("Evaporation", "mm/h", 0.0, 10.0, false, null));
        META.put("world:climate.wind.speed", m("Wind speed", "m/s", 0.0, 60.0, false, "Carries ash and gas downwind."));
        META.put("world:climate.wind.bearingDeg", m("Wind blows towards", "°", 0.0, 360.0, false, "Compass bearing, 90 = east."));
        META.put("world:climate.wind.variability", m("Wind gustiness", null, 0.0, 1.0, false, null));
        META.put("world:scaling.dormantTimeCompression", m("Volcano time while quiet", "×", 1.0, 1e7, true,
                "Volcanoes recharge over years. Volcano processes run this many times faster than simulated time while quiet."));
        META.put("world:scaling.eruptiveTimeCompression", m("Volcano time while erupting", "×", 1.0, 1e4, true,
                "Usually small so lava flows and fountains move at a believable pace."));

        META.put("volcano:magma.chamber.supplyRate", m("Magma supply rate", "m³/s", 0.0, 100.0, true,
                "Magma rising into the chamber from below. Kīlauea ~0.1–0.2 m³/s."));
        META.put("volcano:magma.chamber.supplyVariability", m("Supply variability", null, 0.0, 1.0, false,
                "How much the supply pulses (0 = steady)."));
        META.put("volcano:magma.chamber.rechargeTemperatureC", m("New magma temperature", "°C", 650.0, 1350.0, false,
                "Temperature of magma supplied from now on. Basalt ~1150–1250, rhyolite ~750–900."));
        META.put("volcano:magma.chamber.rechargeSilicaWt", m("New magma silica (SiO₂)", "wt%", 42.0, 78.0, false,
                "More silica = stickier magma and more explosive eruptions. Basalt ~50, andesite ~60, rhyolite ~74."));
        META.put("volcano:magma.chamber.rechargeWaterWt", m("New magma water (H₂O)", "wt%", 0.0, 8.0, false,
                "Dissolved water drives explosions as it turns to gas. Hawaiʻi ~0.5, arcs 3–6."));
        META.put("volcano:timeCompression.dormant", m("Volcano time while quiet", "×", 1.0, 1e7, true,
                "Overrides the world setting for this volcano."));
        META.put("volcano:timeCompression.eruptive", m("Volcano time while erupting", "×", 1.0, 1e4, true,
                "Overrides the world setting for this volcano."));
        META.put("volcano:magma.chamber.volume", m("Chamber volume", "m³", 1e6, 1e13, true, "Size of the magma reservoir."));
        META.put("volcano:magma.chamber.lithostaticDepth", m("Chamber depth", "m", 200.0, 20000.0, false, "Depth of the chamber below the surface."));
        META.put("volcano:magma.chamber.tensileStrengthMPa", m("Roof strength", "MPa", 0.5, 100.0, true,
                "Overpressure needed to crack the rock above the chamber and start an eruption."));
        META.put("volcano:magma.chamber.eruptionEndOverpressureMPa", m("Eruption stops below", "MPa", 0.0, 50.0, false,
                "Overpressure at which an eruption runs out of push."));
        META.put("volcano:magma.chamber.maxEruptionRate", m("Maximum eruption rate", "m³/s", 1.0, 1e6, true, null));
        META.put("volcano:magma.chamber.initialTemperatureC", m("Starting magma temperature", "°C", 650.0, 1350.0, false, null));
        META.put("volcano:magma.chamber.initialSilicaWt", m("Starting magma silica (SiO₂)", "wt%", 42.0, 78.0, false, null));
        META.put("volcano:magma.chamber.initialWaterWt", m("Starting magma water (H₂O)", "wt%", 0.0, 8.0, false, null));
        META.put("volcano:magma.chamber.initialOverpressureMPa", m("Starting overpressure", "MPa", 0.0, 100.0, false, null));
        META.put("volcano:magma.chamber.wallTemperatureC", m("Wall-rock temperature", "°C", 0.0, 1000.0, false, null));
        META.put("volcano:magma.chamber.coolingTimescale", m("Cooling time", "s", null, null, true, "e-folding time of chamber cooling into the wall rock."));
        META.put("volcano:magma.chamber.degassingTimescale", m("Degassing time", "s", null, null, true, null));
        META.put("volcano:ballisticFraction", m("Share of erupted mass as bombs", null, 0.0, 1.0, false, null));
    }

    /**
     * The physically sensible part of a range: values outside {@code [low, high]} are accepted (the
     * hard {@link Meta} range still applies) but the schema flags them and {@code setParams} warns.
     */
    record Advice(Double low, Double high, String warning) {}

    /** Advice by scope:path, like {@link #META}. */
    static final Map<String, Advice> ADVICE = new LinkedHashMap<>();

    static {
        String fast = "Above ~100× lava, fountains and ash advance hundreds of metres per tick and the chamber drains in"
                + " a few steps; results stay bounded but look unrealistic.";
        ADVICE.put("world:scaling.eruptiveTimeCompression", new Advice(1.0, 100.0, fast));
        ADVICE.put("volcano:timeCompression.eruptive", new Advice(1.0, 100.0, fast));
        String quiet = "Above ~10⁵× a year of recharge passes in minutes; dikes and unrest are skipped over.";
        ADVICE.put("world:scaling.dormantTimeCompression", new Advice(null, 1e5, quiet));
        ADVICE.put("volcano:timeCompression.dormant", new Advice(null, 1e5, quiet));
        ADVICE.put("volcano:magma.chamber.supplyRate", new Advice(null, 5.0,
                "Long-term supply above ~5 m³/s is beyond any measured volcano (Kīlauea ~0.1–0.2, Etna ~0.8); the chamber"
                        + " stays at its rupture limit and grows."));
        ADVICE.put("volcano:magma.chamber.supplyVariability", new Advice(null, 0.6,
                "Large pulses make the supply intermittent; eruptions will start and stop abruptly."));
        ADVICE.put("volcano:magma.chamber.tensileStrengthMPa", new Advice(0.5, 20.0,
                "Measured rock tensile strengths are 0.5–9 MPa (in situ ~3); stronger roofs store implausible pressure."));
        ADVICE.put("volcano:magma.chamber.volume", new Advice(1e7, 1e12,
                "Shallow chambers are ~0.01–1000 km³ (10⁷–10¹² m³)."));
        ADVICE.put("volcano:magma.chamber.maxEruptionRate", new Advice(null, 1e5,
                "Only the largest Plinian eruptions exceed ~10⁵ m³/s (Pinatubo 1991 peaked near 10⁵–10⁶)."));
    }

    /** Warning for {@code value} at a scope:path, or null when it is in the sensible range. */
    static String advise(String metaKey, String label, double value) {
        Advice a = ADVICE.get(metaKey);
        if (a == null) return null;
        if ((a.low() != null && value < a.low()) || (a.high() != null && value > a.high())) return label + ": " + a.warning();
        return null;
    }

    /** An {@code injectMagma} batch beyond this share of the chamber volume ruptures the walls. */
    static final double INJECT_RUPTURE_SHARE = 0.1;

    /** Group headings by scope:path prefix (first match wins, in order). */
    private static final List<String[]> GROUPS = List.of(
            new String[] {"world:climate", "Weather"},
            new String[] {"world:scaling", "Time scale"},
            new String[] {"world:subsurface", "Underground heat and water (solver)"},
            new String[] {"volcano:magma.chamber.supply", "Magma supply"},
            new String[] {"volcano:magma.chamber.recharge", "Magma supply"},
            new String[] {"volcano:timeCompression", "Time scale"},
            new String[] {"volcano:magma.chamber.initial", "Magma chamber at start"},
            new String[] {"volcano:magma.chamber", "Magma chamber"},
            new String[] {"volcano:magma.conduit", "Conduit and eruption style"},
            new String[] {"volcano:ballistic", "Conduit and eruption style"},
            new String[] {"volcano:dikes", "Dikes (magma intrusions)"},
            new String[] {"volcano:geothermal", "Hot springs and fumaroles"},
            new String[] {"volcano:massFlows", "Pyroclastic flows and mudflows"},
            new String[] {"volcano:tephra", "Ash and bombs"},
            new String[] {"volcano:deformation", "Ground deformation"},
            new String[] {"volcano:", "Other"});

    /** Volcano paths never offered (identity, geometry the client cannot sensibly edit). */
    private static final List<String> VOLCANO_SKIP = List.of("id", "magma.chamber.center.", "edifice.", "vents");

    /** World paths offered: hot ones only (world-level re-init changes need a new world). */
    private static boolean worldOffered(String path) {
        return !path.equals("name") && !path.startsWith("terrain") && ConfigChanges.worldKind(path) == ConfigChanges.Kind.HOT;
    }

    // ── Injection fields ──

    /** One {@code injectMagma} field and the volcano setting its default comes from. */
    record InjectField(String id, String label, String unit, double min, double max, boolean log, String help,
            ToDoubleFunction<MagmaChamberConfig> configured, ToDoubleFunction<MagmaChamber.SupplyMagma> current) {}

    /**
     * Fields of {@code injectMagma} besides {@code volumeM3}, in {@link MagmaCommands.InjectRecharge}
     * argument order. To add one: add an entry here and pass it on in {@link #injection}; the client
     * builds its dialog from this list.
     */
    static final List<InjectField> INJECT_FIELDS = List.of(
            new InjectField("temperatureC", "Temperature", "°C", 650, 1350, false, null,
                    MagmaChamberConfig::rechargeTemperatureC, MagmaChamber.SupplyMagma::temperatureC),
            new InjectField("silicaWt", "Silica (SiO₂)", "wt%", 42, 78, false, "Sets how sticky the magma is",
                    MagmaChamberConfig::rechargeSilicaWt, MagmaChamber.SupplyMagma::silicaWt),
            new InjectField("waterWt", "Water (H₂O)", "wt%", 0, 8, false, "Dissolved gas that drives explosions",
                    MagmaChamberConfig::rechargeWaterWt, MagmaChamber.SupplyMagma::waterWt),
            new InjectField("co2Wt", "Carbon dioxide (CO₂)", "wt%", 0, 3, false,
                    "Less soluble than water: exsolves deep and drives gas-rich, explosive ascent",
                    MagmaChamberConfig::rechargeCo2Wt, MagmaChamber.SupplyMagma::co2Wt),
            new InjectField("crystalFraction", "Crystals", "fraction", 0, 0.6, false,
                    "Share of the magma already crystallised; crystals stiffen it and carry no latent heat",
                    MagmaChamberConfig::rechargeCrystalFraction, MagmaChamber.SupplyMagma::crystalFraction));

    static final double INJECT_DEFAULT_M3 = 5e6;

    /** {@code injectMagma} fields with defaults from the volcano's configured supply magma. */
    static JsonArray injectSchema(MagmaChamberConfig c) {
        JsonArray out = new JsonArray();
        double sensible = INJECT_RUPTURE_SHARE * c.volume();
        JsonObject vol = spec("volumeM3", "Volume", "m³", "Batch", 1e3, 1e10, true,
                "How much magma to add. A large eruption is 10⁷–10⁹ m³.");
        vol.add("default", Json.num(Math.min(INJECT_DEFAULT_M3, sensible)));
        JsonObject r = new JsonObject();
        r.add("max", Json.num(sensible));
        vol.add("recommended", r);
        vol.addProperty("warning", "Batches above ~" + (int) (INJECT_RUPTURE_SHARE * 100) + "% of the chamber volume crack"
                + " its walls: the pressure stops at the rupture limit and the excess grows the chamber (and the ground).");
        out.add(vol);
        for (InjectField f : INJECT_FIELDS) {
            JsonObject j = spec(f.id(), f.label(), f.unit(), "Magma", f.min(), f.max(), f.log(), f.help());
            j.add("default", Json.num(f.configured().applyAsDouble(c)));
            out.add(j);
        }
        return out;
    }

    /** Builds the engine command, validating ranges; missing fields use the magma the supply delivers now. */
    static MagmaCommands.InjectRecharge injection(String volcanoId, JsonObject cmd, MagmaChamber.SupplyMagma supply) {
        Double vol = Json.dbl(cmd, "volumeM3");
        if (vol == null || !(vol > 0) || vol > 1e12) throw new IllegalArgumentException("volumeM3 must be in (0, 1e12]");
        double[] v = new double[INJECT_FIELDS.size()];
        for (int i = 0; i < v.length; i++) {
            InjectField f = INJECT_FIELDS.get(i);
            Double x = Json.dbl(cmd, f.id());
            if (x == null) {
                v[i] = f.current().applyAsDouble(supply);
            } else if (!(x >= f.min() && x <= f.max())) {
                throw new IllegalArgumentException(f.id() + " must be in [" + f.min() + ", " + f.max() + "] " + f.unit());
            } else {
                v[i] = x;
            }
        }
        return new MagmaCommands.InjectRecharge(volcanoId, vol, v[0], v[1], v[2], v[3], v[4]);
    }

    /** The note acknowledging an injection, or null when the batch is small enough for the walls. */
    static String injectionWarning(double volumeM3, MagmaChamber chamber) {
        if (volumeM3 <= INJECT_RUPTURE_SHARE * chamber.volumeM3()) return null;
        return String.format(java.util.Locale.ROOT, "%.3g m³ is %.0f%% of the chamber: its walls rupture at %.1f MPa and"
                + " the rest grows the chamber", volumeM3, 100 * volumeM3 / chamber.volumeM3(), chamber.ruptureOverpressureMPa());
    }

    // ── Schema ──

    private static JsonObject spec(String id, String label, String unit, String group, Double min, Double max,
            boolean log, String help) {
        JsonObject j = new JsonObject();
        j.addProperty("id", id);
        j.addProperty("label", label);
        if (unit != null) j.addProperty("unit", unit);
        if (help != null) j.addProperty("help", help);
        j.addProperty("group", group);
        j.addProperty("type", "number");
        if (min != null) j.add("min", Json.num(min));
        if (max != null) j.add("max", Json.num(max));
        if (log) j.addProperty("log", true);
        j.addProperty("apply", "hot");
        return j;
    }

    /** Current definitions of a world directory. */
    record Definitions(Map<String, Object> world, Map<String, Map<String, Object>> volcanoes, Map<String, String> names) {
        static Definitions read(Path dir) {
            WorldDirectory wd = new WorldDirectory(dir);
            Map<String, Map<String, Object>> vs = new TreeMap<>();
            Map<String, String> names = new TreeMap<>();
            for (VolcanoDefinition v : wd.readVolcanoes()) {
                vs.put(v.id(), v.toTree());
                names.put(v.id(), v.name());
            }
            return new Definitions(wd.readWorld().toTree(), vs, names);
        }
    }

    /** A parameter found in the definitions. */
    record Leaf(String id, String scope, String volcanoId, String path, Object value) {
        boolean hot() {
            return (volcanoId == null ? ConfigChanges.worldKind(path) : ConfigChanges.volcanoKind(path)) == ConfigChanges.Kind.HOT;
        }

        String metaKey() {
            return (volcanoId == null ? "world:" : "volcano:") + path;
        }
    }

    static List<Leaf> leaves(Definitions d) {
        List<Leaf> out = new ArrayList<>();
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten("", d.world(), flat);
        for (Map.Entry<String, Object> e : flat.entrySet()) {
            if (worldOffered(e.getKey())) out.add(new Leaf("world." + e.getKey(), "world", null, e.getKey(), e.getValue()));
        }
        for (Map.Entry<String, Map<String, Object>> v : d.volcanoes().entrySet()) {
            flat.clear();
            flatten("", v.getValue(), flat);
            for (Map.Entry<String, Object> e : flat.entrySet()) {
                String p = e.getKey();
                if (VOLCANO_SKIP.stream().anyMatch(s -> s.endsWith(".") ? p.startsWith(s) : p.equals(s))) continue;
                out.add(new Leaf("volcano." + v.getKey() + "." + p, "volcano:" + v.getKey(), v.getKey(), p, e.getValue()));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> tree, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : tree.entrySet()) {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m) flatten(path, (Map<String, Object>) m, out);
            else if (v instanceof Number || v instanceof Boolean) out.put(path, v);
        }
    }

    private static String group(Leaf l, Map<String, String> names) {
        String key = l.metaKey();
        String heading = "Other";
        for (String[] g : GROUPS) {
            if (key.startsWith(g[0])) {
                heading = g[1];
                break;
            }
        }
        return (l.volcanoId() == null ? "World" : names.getOrDefault(l.volcanoId(), l.volcanoId())) + " · " + heading;
    }

    /** Position of a group in the panel (curated groups first, in {@link #GROUPS} order). */
    private static int groupRank(Leaf l) {
        String key = l.metaKey();
        for (int i = 0; i < GROUPS.size(); i++) if (key.startsWith(GROUPS.get(i)[0])) return i;
        return GROUPS.size();
    }

    /** Name suffix → unit, and how many trailing characters to drop from the label. */
    private static final Object[][] SUFFIXES = {
        {"MmPerHour", "mm/h", 9}, {"PerHour", "/h", 0}, {"TemperatureC", "°C", 1}, {"CPerKm", "°C/km", 6},
        {"PerMPa", "1/MPa", 6}, {"MPa", "MPa", 3}, {"Wt", "wt%", 2}, {"Seconds", "s", 7}, {"Timescale", "s", 0},
        {"Deg", "°", 3}, {"KgS", "kg/s", 3}, {"Kg", "kg", 2}, {"M3", "m³", 2}, {"DepthM", "m", 1}, {"Pa", "Pa", 2}};

    /** "rechargeSilicaWt" → label "Recharge silica", unit "wt%". */
    static String[] humanize(String path) {
        String key = path.substring(path.lastIndexOf('.') + 1);
        String unit = null;
        String base = key;
        for (Object[] s : SUFFIXES) {
            String suffix = (String) s[0];
            if (key.endsWith(suffix) && key.length() > suffix.length()) {
                unit = (String) s[1];
                base = key.substring(0, key.length() - (int) s[2]);
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char ch = base.charAt(i);
            if (i == 0) sb.append(Character.toUpperCase(ch));
            else if (Character.isUpperCase(ch) && !Character.isUpperCase(base.charAt(i - 1))) sb.append(' ').append(Character.toLowerCase(ch));
            else sb.append(ch);
        }
        return new String[] {sb.toString(), unit};
    }

    /** The {@code schema} message of a session (§3.6). */
    static JsonObject schema(Session s) {
        JsonObject o = Json.obj("schema");
        o.addProperty("sessionId", s.id);
        JsonObject commands = new JsonObject();
        o.add("commands", commands);
        JsonArray params = new JsonArray();
        o.add("params", params);
        Path dir = s.worldDir();
        MagmaChamberConfig c = s.firstChamberConfig();
        if (c != null) commands.add("injectMagma", injectSchema(c));
        if (dir == null) {
            o.addProperty("tunable", false);
            o.addProperty("reason", "This world runs in memory only, so its settings cannot be changed. Start it from the"
                    + " Worlds page (it is then saved to disk) to tune it.");
            o.add("audit", new JsonArray());
            return o;
        }
        o.addProperty("tunable", true);
        Definitions now = Definitions.read(dir);
        Map<String, Object> defaults = baselineValues(dir, now);
        List<Leaf> leaves = new ArrayList<>(leaves(now));
        List<String> metaOrder = new ArrayList<>(META.keySet());
        leaves.sort((a, b) -> {
            int g = Integer.compare(groupRank(a), groupRank(b));
            if (g != 0) return g;
            int va = a.volcanoId() == null ? -1 : 0;
            int vb = b.volcanoId() == null ? -1 : 0;
            if (va != vb) return Integer.compare(va, vb);
            int c1 = a.volcanoId() == null || b.volcanoId() == null ? 0 : a.volcanoId().compareTo(b.volcanoId());
            if (c1 != 0) return c1;
            int ia = metaOrder.indexOf(a.metaKey());
            int ib = metaOrder.indexOf(b.metaKey());
            if (ia < 0) ia = Integer.MAX_VALUE;
            if (ib < 0) ib = Integer.MAX_VALUE;
            return ia != ib ? Integer.compare(ia, ib) : a.path().compareTo(b.path());
        });
        for (Leaf l : leaves) params.add(paramSpec(l, now.names(), defaults.get(l.id())));
        o.add("audit", readAudit(dir));
        return o;
    }

    private static JsonObject paramSpec(Leaf l, Map<String, String> names, Object def) {
        Meta meta = META.get(l.metaKey());
        String[] h = humanize(l.path());
        JsonObject j = spec(l.id(), meta != null ? meta.label() : h[0], meta != null ? meta.unit() : h[1], group(l, names),
                meta == null ? null : meta.min(), meta == null ? null : meta.max(), meta != null && meta.log(),
                meta == null ? null : meta.help());
        if (l.value() instanceof Boolean b) {
            j.addProperty("type", "boolean");
            j.addProperty("value", b);
            if (def instanceof Boolean d) j.addProperty("default", d);
        } else {
            Number n = (Number) l.value();
            j.add("value", Json.num(n.doubleValue()));
            if (def instanceof Number d) j.add("default", Json.num(d.doubleValue()));
            if (n instanceof Integer || n instanceof Long) j.add("step", Json.num(1));
        }
        j.addProperty("apply", l.hot() ? "hot" : "restart");
        if (l.volcanoId() != null) j.addProperty("volcanoId", l.volcanoId());
        Advice a = ADVICE.get(l.metaKey());
        if (a != null) {
            JsonObject r = new JsonObject();
            if (a.low() != null) r.add("min", Json.num(a.low()));
            if (a.high() != null) r.add("max", Json.num(a.high()));
            j.add("recommended", r);
            j.addProperty("warning", a.warning());
            if (l.value() instanceof Number n) {
                String w = advise(l.metaKey(), meta != null ? meta.label() : h[0], n.doubleValue());
                if (w != null) j.addProperty("outOfRange", true);
            }
        }
        return j;
    }

    // ── Baseline and audit ──

    /** Leaf values of the definitions before the first change (written on first use). */
    static Map<String, Object> baselineValues(Path dir, Definitions now) {
        Path file = dir.resolve(DIR).resolve(BASELINE);
        Definitions base = now;
        try {
            if (Files.isRegularFile(file)) {
                JsonObject j = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                Map<String, Map<String, Object>> vs = new TreeMap<>();
                for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("volcanoes").entrySet()) {
                    vs.put(e.getKey(), toJava(e.getValue().getAsJsonObject()));
                }
                base = new Definitions(toJava(j.getAsJsonObject("world")), vs, now.names());
            } else {
                Files.createDirectories(file.getParent());
                JsonObject j = new JsonObject();
                j.add("world", Json.GSON.toJsonTree(now.world()));
                j.add("volcanoes", Json.GSON.toJsonTree(now.volcanoes()));
                Files.writeString(file, Json.GSON.toJson(j), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> out = new TreeMap<>();
        for (Leaf l : leaves(base)) out.put(l.id(), l.value());
        return out;
    }

    private static Map<String, Object> toJava(JsonObject o) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) m.put(e.getKey(), toJava(e.getValue()));
        return m;
    }

    private static Object toJava(JsonElement e) {
        if (e.isJsonObject()) return toJava(e.getAsJsonObject());
        if (e.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement x : e.getAsJsonArray()) list.add(toJava(x));
            return list;
        }
        if (e.isJsonNull()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) return p.getAsDouble();
        return p.getAsString();
    }

    static JsonArray readAudit(Path dir) {
        Path file = dir.resolve(DIR).resolve(AUDIT);
        try {
            if (!Files.isRegularFile(file)) return new JsonArray();
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
        } catch (IOException | RuntimeException e) {
            return new JsonArray();
        }
    }

    private static void appendAudit(Path dir, List<JsonObject> entries) {
        JsonArray all = readAudit(dir);
        for (JsonObject e : entries) all.add(e);
        while (all.size() > AUDIT_LIMIT) all.remove(0);
        try {
            Files.createDirectories(dir.resolve(DIR));
            Files.writeString(dir.resolve(DIR).resolve(AUDIT), Json.GSON.toJson(all), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── Changes ──

    /**
     * A validated set of changes: new definition files ready to write, and whether a volcano must
     * be restarted. Nothing touches the disk until {@link #write}.
     */
    static final class Plan {
        final Path dir;
        final boolean restart;
        final Map<Path, Map<String, Object>> files = new LinkedHashMap<>();
        final Map<Path, byte[]> previous = new LinkedHashMap<>();
        final List<JsonObject> audit = new ArrayList<>();
        /** Values outside their physically sensible range ({@link #ADVICE}), readable sentences. */
        final List<String> warnings = new ArrayList<>();

        Plan(Path dir, boolean restart) {
            this.dir = dir;
            this.restart = restart;
        }

        boolean isEmpty() {
            return files.isEmpty();
        }

        void write() throws IOException {
            for (Map.Entry<Path, Map<String, Object>> f : files.entrySet()) {
                previous.put(f.getKey(), Files.readAllBytes(f.getKey()));
                Yaml.write(f.getKey(), WorldFiles.header(f.getKey()), f.getValue());
            }
        }

        /** Puts the previous files back (the reopen failed). */
        void revert() throws IOException {
            for (Map.Entry<Path, byte[]> f : previous.entrySet()) Files.write(f.getKey(), f.getValue());
        }

        void log() {
            appendAudit(dir, audit);
        }
    }

    /**
     * Validates {@code values} (id → new value, JSON null = default) against the world's
     * definitions. Throws {@link IllegalArgumentException} with a readable message on unknown ids,
     * bad values, or re-init changes without {@code restart}.
     */
    static Plan plan(Path dir, JsonObject values, boolean restart, double simTime) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("setParams needs values");
        Definitions now = Definitions.read(dir);
        Map<String, Object> defaults = baselineValues(dir, now);
        Map<String, Leaf> byId = new LinkedHashMap<>();
        for (Leaf l : leaves(now)) byId.put(l.id(), l);
        Map<String, Object> world = now.world();
        Map<String, Map<String, Object>> volcanoes = new TreeMap<>(now.volcanoes());
        boolean worldChanged = false;
        Map<String, Boolean> volcanoChanged = new TreeMap<>();
        List<JsonObject> audit = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean anyRestart = false;
        for (Map.Entry<String, JsonElement> e : values.entrySet()) {
            Leaf l = byId.get(e.getKey());
            if (l == null) throw new IllegalArgumentException("Unknown parameter '" + e.getKey() + "'");
            Object v = e.getValue().isJsonNull() ? defaults.get(l.id()) : coerce(l, e.getValue());
            if (v == null) throw new IllegalArgumentException(l.id() + " has no default");
            if (equal(v, l.value())) continue;
            Meta meta = META.get(l.metaKey());
            if (meta != null && v instanceof Number n) {
                double d = n.doubleValue();
                if ((meta.min() != null && d < meta.min()) || (meta.max() != null && d > meta.max())) {
                    throw new IllegalArgumentException(meta.label() + " must be between " + meta.min() + " and " + meta.max());
                }
            }
            if (v instanceof Number n) {
                String w = advise(l.metaKey(), meta != null ? meta.label() : humanize(l.path())[0], n.doubleValue());
                if (w != null) warnings.add(w);
            }
            if (!l.hot()) {
                if (!restart) {
                    throw new IllegalArgumentException("Changing " + l.id() + " restarts the volcano; send restart: true");
                }
                anyRestart = true;
            }
            Map<String, Object> tree = l.volcanoId() == null ? world : volcanoes.get(l.volcanoId());
            put(tree, l.path(), v);
            if (l.volcanoId() == null) worldChanged = true;
            else volcanoChanged.put(l.volcanoId(), true);
            JsonObject a = new JsonObject();
            a.addProperty("at", System.currentTimeMillis());
            a.add("simTime", Json.num(simTime));
            a.addProperty("id", l.id());
            Meta m = META.get(l.metaKey());
            a.addProperty("label", (l.volcanoId() == null ? "" : now.names().getOrDefault(l.volcanoId(), l.volcanoId()) + ": ")
                    + (m != null ? m.label() : humanize(l.path())[0]));
            a.add("from", Json.GSON.toJsonTree(l.value()));
            a.add("to", e.getValue().isJsonNull() ? null : Json.GSON.toJsonTree(v));
            a.addProperty("apply", l.hot() ? "hot" : "restart");
            audit.add(a);
        }
        Plan plan = new Plan(dir, anyRestart);
        plan.audit.addAll(audit);
        plan.warnings.addAll(warnings);
        WorldDirectory wd = new WorldDirectory(dir);
        if (worldChanged) {
            WorldDefinition.parse(ConfigNode.root("world.yaml", world)); // validates
            plan.files.put(wd.worldFile(), world);
        }
        for (String id : volcanoChanged.keySet()) {
            Map<String, Object> tree = volcanoes.get(id);
            VolcanoDefinition.parse(id, ConfigNode.root("volcanoes/" + id + ".yaml", tree)); // validates
            plan.files.put(wd.volcanoFile(id), tree);
        }
        return plan;
    }

    private static Object coerce(Leaf l, JsonElement e) {
        if (!e.isJsonPrimitive()) throw new IllegalArgumentException(l.id() + ": expected a value");
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (l.value() instanceof Boolean) {
            if (!p.isBoolean()) throw new IllegalArgumentException(l.id() + ": expected true or false");
            return p.getAsBoolean();
        }
        if (!p.isNumber()) throw new IllegalArgumentException(l.id() + ": expected a number");
        double d = p.getAsDouble();
        if (!Double.isFinite(d)) throw new IllegalArgumentException(l.id() + ": expected a finite number");
        if (l.value() instanceof Integer) return (int) Math.round(d);
        if (l.value() instanceof Long) return Math.round(d);
        return d;
    }

    private static boolean equal(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        return a.equals(b);
    }

    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> tree, String path, Object value) {
        String[] parts = path.split("\\.");
        Map<String, Object> m = tree;
        for (int i = 0; i < parts.length - 1; i++) m = (Map<String, Object>) m.get(parts[i]);
        m.put(parts[parts.length - 1], value);
    }
}
