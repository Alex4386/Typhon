package me.alex4386.typhon.engine.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * One mapping of a parsed definition file, with typed getters and strict validation: every key read
 * is recorded, and {@link #finish} rejects the keys nobody read (typos) with the list of valid keys.
 * Errors name the file and the dotted key path, e.g. {@code volcanoes/fuji.yaml: magma.chamber.volume}.
 */
public final class ConfigNode {
    private final String file;
    private final String path;
    private final Map<String, Object> values;
    private final Set<String> used = new LinkedHashSet<>();

    private ConfigNode(String file, String path, Map<String, Object> values) {
        this.file = file;
        this.path = path;
        this.values = values;
    }

    /** Root node of a parsed document ({@code null} documents are empty). */
    public static ConfigNode root(String file, Object document) {
        if (document == null) return new ConfigNode(file, "", new LinkedHashMap<>());
        if (!(document instanceof Map<?, ?> map)) throw new ConfigException(file + ": expected a mapping at the top level");
        return new ConfigNode(file, "", stringKeys(file, "", map));
    }

    private static Map<String, Object> stringKeys(String file, String path, Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                throw new ConfigException(file + ": " + (path.isEmpty() ? "" : path + ": ") + "keys must be strings, got "
                        + e.getKey());
            }
            result.put(key, e.getValue());
        }
        return result;
    }

    public String file() {
        return file;
    }

    public String path() {
        return path;
    }

    public String pathOf(String key) {
        return path.isEmpty() ? key : path + "." + key;
    }

    public ConfigException error(String key, String message) {
        return new ConfigException(file + ": " + pathOf(key) + ": " + message);
    }

    public ConfigException error(String message) {
        return new ConfigException(file + ": " + (path.isEmpty() ? "" : path + ": ") + message);
    }

    public boolean has(String key) {
        return values.containsKey(key) && values.get(key) != null;
    }

    public Set<String> keys() {
        return values.keySet();
    }

    /** Raw value (and marks the key as read). */
    public Object raw(String key) {
        used.add(key);
        return values.get(key);
    }

    public void markUsed(String key) {
        used.add(key);
    }

    public String string(String key, String fallback) {
        Object v = raw(key);
        if (v == null) return fallback;
        if (v instanceof String s) return s;
        if (v instanceof Number || v instanceof Boolean) return v.toString();
        throw error(key, "expected a string, got " + describe(v));
    }

    public String requireString(String key) {
        String s = string(key, null);
        if (s == null) throw error(key, "is required");
        return s;
    }

    public double number(String key, double fallback) {
        Object v = raw(key);
        if (v == null) return fallback;
        return toDouble(key, v);
    }

    public double requireNumber(String key) {
        if (!has(key)) {
            used.add(key);
            throw error(key, "is required");
        }
        return number(key, Double.NaN);
    }

    double toDouble(String key, Object v) {
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            switch (s.toLowerCase()) {
                case ".nan", "nan" -> { return Double.NaN; }
                case ".inf", "+.inf", "inf", "infinity" -> { return Double.POSITIVE_INFINITY; }
                case "-.inf", "-inf", "-infinity" -> { return Double.NEGATIVE_INFINITY; }
                default -> { }
            }
        }
        throw error(key, "expected a number, got " + describe(v));
    }

    public int integer(String key, int fallback) {
        Object v = raw(key);
        if (v == null) return fallback;
        return toInt(key, v);
    }

    int toInt(String key, Object v) {
        if (v instanceof Integer || v instanceof Long || v instanceof java.math.BigInteger) {
            long l = ((Number) v).longValue();
            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) throw error(key, "out of range for an integer: " + v);
            return (int) l;
        }
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) return (int) n.doubleValue();
        throw error(key, "expected an integer, got " + describe(v));
    }

    public long longValue(String key, long fallback) {
        Object v = raw(key);
        if (v == null) return fallback;
        if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) return n.longValue();
        throw error(key, "expected an integer, got " + describe(v));
    }

    public boolean bool(String key, boolean fallback) {
        Object v = raw(key);
        if (v == null) return fallback;
        if (v instanceof Boolean b) return b;
        throw error(key, "expected true or false, got " + describe(v));
    }

    /** Child mapping; an empty node if the key is absent. */
    public ConfigNode child(String key) {
        Object v = raw(key);
        if (v == null) return new ConfigNode(file, pathOf(key), new LinkedHashMap<>());
        if (!(v instanceof Map<?, ?> map)) throw error(key, "expected a mapping, got " + describe(v));
        return new ConfigNode(file, pathOf(key), stringKeys(file, pathOf(key), map));
    }

    /** List of mappings; empty if the key is absent. */
    public List<ConfigNode> children(String key) {
        Object v = raw(key);
        List<ConfigNode> list = new ArrayList<>();
        if (v == null) return list;
        if (!(v instanceof List<?> items)) throw error(key, "expected a list, got " + describe(v));
        for (int i = 0; i < items.size(); i++) {
            String p = pathOf(key) + "[" + i + "]";
            if (!(items.get(i) instanceof Map<?, ?> map)) {
                throw new ConfigException(file + ": " + p + ": expected a mapping, got " + describe(items.get(i)));
            }
            list.add(new ConfigNode(file, p, stringKeys(file, p, map)));
        }
        return list;
    }

    /** Values of a mapping as plain Java objects (for free-form sections), marking them all read. */
    public Map<String, Object> asMap() {
        used.addAll(values.keySet());
        return new LinkedHashMap<>(values);
    }

    /**
     * Keys of the retired time compression (there is one physical clock; how fast time passes on screen
     * is the playback speed). Definitions written before still load: these are ignored with a warning.
     */
    static final Set<String> RETIRED = Set.of("timeCompression", "dormantTimeCompression", "eruptiveTimeCompression",
            "dormantTimeScale", "eruptiveTimeScale", "timeScale");

    /**
     * Keys merged into others or now derived from the physics (key → why); ignored with a warning so old
     * definitions still load.
     */
    static final java.util.Map<String, String> MERGED = java.util.Map.of(
            "conduitSealing", "dike likelihood now follows the live conduit openness",
            "ruptureNucleation", "merged into dikes.blocked (wall rupture opens a dike unless dikes are blocked)",
            "nucleateDuringEruption", "derived: an open, erupting conduit already makes spontaneous dikes unlikely");

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(ConfigNode.class.getName());

    /** Rejects keys that were never read; {@code valid} lists additional accepted keys for the message. */
    public void finish(Collection<String> valid) {
        List<String> unknown = new ArrayList<>();
        for (String key : values.keySet()) {
            if (used.contains(key)) continue;
            if (MERGED.containsKey(key)) {
                used.add(key);
                LOG.warning(file + ": " + pathOf(key) + " is ignored: " + MERGED.get(key));
                continue;
            }
            if (RETIRED.contains(key)) {
                used.add(key);
                LOG.warning(file + ": " + pathOf(key) + " is ignored: time compression was removed (one physical"
                        + " clock; set the playback speed instead)");
                continue;
            }
            unknown.add(key);
        }
        if (unknown.isEmpty()) return;
        Set<String> accepted = new TreeSet<>(used);
        accepted.addAll(valid);
        String key = unknown.get(0);
        throw error(key, "unknown key" + (unknown.size() > 1 ? " (also unknown: " + unknown.subList(1, unknown.size()) + ")" : "")
                + "; valid keys here: " + accepted);
    }

    public void finish() {
        finish(List.of());
    }

    static String describe(Object v) {
        if (v instanceof Map) return "a mapping";
        if (v instanceof List) return "a list";
        if (v instanceof String s) return "'" + s + "'";
        return String.valueOf(v);
    }
}
