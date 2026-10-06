package me.alex4386.typhon.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import me.alex4386.typhon.engine.config.ConfigNode;
import me.alex4386.typhon.engine.config.VolcanoDefinition;
import me.alex4386.typhon.engine.config.WorldDefinition;
import me.alex4386.typhon.engine.worlds.ConfigChanges;
import me.alex4386.typhon.engine.worlds.ConfigImpact;
import me.alex4386.typhon.engine.worlds.World;

/**
 * The configuration API (HTTP {@code /api/sessions/{id}/config} and WS {@code setConfig}): a client
 * sends the configuration it wants (a patch of dotted paths, or full definitions); the server validates
 * it, diffs it against what is running and decides per change, through {@link ConfigImpact} only, how to
 * apply it: in place (live), by rebuilding with state kept (reload) or by resetting the affected part
 * (reinit). See {@link Session#applyConfig}.
 */
final class ConfigApi {
    private ConfigApi() {}

    /**
     * A request.
     *
     * @param world world patch (dotted or nested keys; JSON null = back to the default/computed value) or, with
     *     {@code replace}, the full world definition; {@code null} if unchanged
     * @param volcanoes volcano id → patch (or full definition with {@code replace})
     * @param confirm the token of a plan the user confirmed ({@link Plan#token})
     */
    record Request(JsonObject world, JsonObject volcanoes, boolean replace, boolean dryRun, String confirm) {
        static Request from(JsonObject body, boolean replace, boolean dryRun, String confirm) {
            JsonObject w = body.has("world") && body.get("world").isJsonObject() ? body.getAsJsonObject("world") : null;
            JsonObject v = body.has("volcanoes") && body.get("volcanoes").isJsonObject() ? body.getAsJsonObject("volcanoes") : null;
            return new Request(w, v, replace, dryRun, confirm);
        }

        /** {@code setParams}' values (param id → value) as a patch request. */
        static Request fromParams(JsonObject values, boolean dryRun, String confirm) {
            JsonObject world = new JsonObject();
            JsonObject volcanoes = new JsonObject();
            for (Map.Entry<String, JsonElement> e : values.entrySet()) {
                String id = e.getKey();
                if (id.startsWith("world.")) {
                    world.add(id.substring("world.".length()), e.getValue());
                } else if (id.startsWith("volcano.")) {
                    String rest = id.substring("volcano.".length());
                    int dot = rest.indexOf('.');
                    if (dot < 0) throw new Rejected(List.of(new FieldError(id, "unknown setting")));
                    String vid = rest.substring(0, dot);
                    if (!volcanoes.has(vid)) volcanoes.add(vid, new JsonObject());
                    volcanoes.getAsJsonObject(vid).add(rest.substring(dot + 1), e.getValue());
                } else {
                    throw new Rejected(List.of(new FieldError(id, "unknown setting")));
                }
            }
            return new Request(world.isEmpty() ? null : world, volcanoes.isEmpty() ? null : volcanoes, false, dryRun, confirm);
        }
    }

    /** A rejected field: {@code path} is {@code world.<path>} or {@code volcano.<id>.<path>} (or a file for whole-file errors). */
    record FieldError(String path, String message) {
        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("path", path);
            o.addProperty("message", message);
            return o;
        }
    }

    /** Validation failed; nothing was applied. */
    static final class Rejected extends RuntimeException {
        final List<FieldError> errors;

        Rejected(List<FieldError> errors) {
            super(errors.isEmpty() ? "rejected" : errors.get(0).path() + ": " + errors.get(0).message());
            this.errors = List.copyOf(errors);
        }

        JsonArray json() {
            JsonArray a = new JsonArray();
            for (FieldError e : errors) a.add(e.json());
            return a;
        }
    }

    /** A validated request: the new definitions and every change with what it needs. */
    static final class Plan {
        WorldDefinition world;
        List<VolcanoDefinition> volcanoes;
        Map<String, Object> worldTree;
        final Map<String, Map<String, Object>> volcanoTrees = new TreeMap<>();
        boolean worldChanged;
        final List<String> changedVolcanoes = new ArrayList<>();
        ConfigChanges changes = ConfigChanges.NONE;
        final Map<String, String> names = new TreeMap<>();
        final List<String> warnings = new ArrayList<>();

        boolean isEmpty() {
            return changes.isEmpty();
        }

        ConfigImpact.Kind strongest() {
            return changes.strongest();
        }

        /** Identifies this exact set of changes; a confirmation must quote it. */
        String token() {
            try {
                MessageDigest d = MessageDigest.getInstance("SHA-256");
                for (ConfigChanges.Change c : changes.all()) {
                    d.update((c.scope() + "|" + c.path() + "|" + c.after() + "\n").getBytes(StandardCharsets.UTF_8));
                }
                return HexFormat.of().formatHex(d.digest()).substring(0, 16);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Whether applying needs the user's confirmation first: anything that resets state. */
        boolean needsConfirmation() {
            return strongest() == ConfigImpact.Kind.REINIT;
        }

        String nameOf(ConfigChanges.Change c) {
            return c.scope().startsWith("volcano:") ? names.getOrDefault(c.scope().substring(8), c.scope().substring(8)) : null;
        }

        /** Per-change report: path, values, kind and the consequence in words. */
        JsonArray describe(ConfigImpact.Kind actual) {
            JsonArray out = new JsonArray();
            for (ConfigChanges.Change c : changes.all()) {
                JsonObject o = new JsonObject();
                String vid = c.scope().startsWith("volcano:") ? c.scope().substring(8) : null;
                o.addProperty("id", (vid == null ? "world." : "volcano." + vid + ".") + c.path());
                o.addProperty("scope", c.scope());
                if (vid != null) o.addProperty("volcanoId", vid);
                o.addProperty("path", c.path());
                o.add("from", value(c.before()));
                o.add("to", value(c.after()));
                o.add("impact", impactJson(c.impact(), nameOf(c)));
                if (actual != null) o.addProperty("applied", kindName(actual.ordinal() > c.impact().kind().ordinal() ? actual : c.impact().kind()));
                out.add(o);
            }
            return out;
        }

        /** Distinct consequences, most disruptive first (what a confirmation dialog shows). */
        JsonArray consequences() {
            Map<String, JsonObject> seen = new LinkedHashMap<>();
            List<ConfigChanges.Change> sorted = new ArrayList<>(changes.all());
            sorted.sort((a, b) -> b.impact().kind().ordinal() - a.impact().kind().ordinal());
            for (ConfigChanges.Change c : sorted) {
                JsonObject o = impactJson(c.impact(), nameOf(c));
                seen.putIfAbsent(o.get("message").getAsString(), o);
            }
            JsonArray a = new JsonArray();
            seen.values().forEach(a::add);
            return a;
        }
    }

    static String kindName(ConfigImpact.Kind k) {
        return k.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** {@code {kind, target, message, reason?}} of an impact for a volcano called {@code name} ({@code null} = world). */
    static JsonObject impactJson(ConfigImpact.Impact impact, String name) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kindName(impact.kind()));
        o.addProperty("target", impact.target().name().toLowerCase(java.util.Locale.ROOT));
        o.addProperty("message", impact.message(name));
        if (impact.reason() != null) o.addProperty("reason", impact.reason());
        return o;
    }

    private static JsonElement value(String json) {
        if (json == null) return JsonNull.INSTANCE;
        try {
            return JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return new JsonPrimitive(json);
        }
    }

    // ── Planning ──

    /** The current definitions of a running world, as editable trees. */
    static JsonObject current(World world) {
        JsonObject o = new JsonObject();
        o.add("world", Json.GSON.toJsonTree(world.definition().toTree()));
        JsonObject vs = new JsonObject();
        for (VolcanoDefinition v : world.volcanoDefinitions()) vs.add(v.id(), Json.GSON.toJsonTree(v.toTree()));
        o.add("volcanoes", vs);
        return o;
    }

    /**
     * Validates a request against the running world's definitions and classifies every change. Throws
     * {@link Rejected} with per-field errors, applying nothing.
     */
    static Plan plan(World world, Path dir, Request r, double simTime) {
        List<FieldError> errors = new ArrayList<>();
        Plan p = new Plan();
        Map<String, VolcanoDefinition> running = new TreeMap<>();
        for (VolcanoDefinition v : world.volcanoDefinitions()) {
            running.put(v.id(), v);
            p.names.put(v.id(), v.name());
        }
        Tuning.Definitions now = new Tuning.Definitions(world.definition().toTree(), trees(running), p.names);
        Map<String, Object> defaults = dir != null ? Tuning.baselineValues(dir, now) : Map.of();

        // world
        p.worldTree = now.world();
        if (r.world() != null) {
            if (r.replace()) p.worldTree = Tuning.toJava(r.world());
            else applyPatch(p.worldTree, "world.", null, r.world(), defaults, errors, p.warnings);
        }
        try {
            p.world = WorldDefinition.parse(ConfigNode.root("world.yaml", p.worldTree));
        } catch (RuntimeException e) {
            errors.add(new FieldError("world", e.getMessage()));
        }

        // volcanoes
        if (r.volcanoes() != null) {
            for (Map.Entry<String, JsonElement> e : r.volcanoes().entrySet()) {
                if (!running.containsKey(e.getKey())) {
                    errors.add(new FieldError("volcano." + e.getKey(), "no such volcano in this world"));
                } else if (!e.getValue().isJsonObject()) {
                    errors.add(new FieldError("volcano." + e.getKey(), "expected an object"));
                }
            }
        }
        List<VolcanoDefinition> next = new ArrayList<>();
        for (Map.Entry<String, VolcanoDefinition> e : running.entrySet()) {
            String id = e.getKey();
            Map<String, Object> tree = now.volcanoes().get(id);
            JsonElement patch = r.volcanoes() != null ? r.volcanoes().get(id) : null;
            if (patch != null && patch.isJsonObject()) {
                if (r.replace()) tree = Tuning.toJava(patch.getAsJsonObject());
                else applyPatch(tree, "volcano." + id + ".", id, patch.getAsJsonObject(), defaults, errors, p.warnings);
            }
            p.volcanoTrees.put(id, tree);
            try {
                VolcanoDefinition v = VolcanoDefinition.parse(id, ConfigNode.root("volcanoes/" + id + ".yaml", tree));
                next.add(v);
            } catch (RuntimeException ex) {
                errors.add(new FieldError("volcano." + id, ex.getMessage()));
            }
        }
        if (!errors.isEmpty()) throw new Rejected(errors);
        p.volcanoes = next;

        p.changes = ConfigChanges.between(world.definition(), running.values(), p.world, next);
        for (ConfigChanges.Change c : p.changes.all()) {
            boolean worldScope = c.scope().equals("world");
            if (worldScope) p.worldChanged = true;
            else {
                String vid = c.scope().substring("volcano:".length());
                if (!p.changedVolcanoes.contains(vid)) p.changedVolcanoes.add(vid);
            }
            ConfigImpact.Impact impact = c.impact();
            if (impact.kind() == ConfigImpact.Kind.REINIT && impact.target() == ConfigImpact.Target.WORLD) {
                errors.add(new FieldError("world." + c.path(), impact.message(null)));
            }
            if (!worldScope && c.path().equals("id")) errors.add(new FieldError(c.scope() + ".id", "a volcano's id cannot change"));
        }
        if (!errors.isEmpty()) throw new Rejected(errors);
        return p;
    }

    private static Map<String, Map<String, Object>> trees(Map<String, VolcanoDefinition> vs) {
        Map<String, Map<String, Object>> out = new TreeMap<>();
        for (Map.Entry<String, VolcanoDefinition> e : vs.entrySet()) out.put(e.getKey(), e.getValue().toTree());
        return out;
    }

    /** Dotted leaves of a (possibly nested) patch object. */
    private static void patchLeaves(String prefix, JsonObject patch, Map<String, JsonElement> out) {
        for (Map.Entry<String, JsonElement> e : patch.entrySet()) {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (e.getValue().isJsonObject()) patchLeaves(path, e.getValue().getAsJsonObject(), out);
            else out.put(path, e.getValue());
        }
    }

    /** Applies a patch to a definition tree, checking every path, type and range. */
    @SuppressWarnings("unchecked")
    private static void applyPatch(Map<String, Object> tree, String idPrefix, String volcanoId, JsonObject patch,
            Map<String, Object> defaults, List<FieldError> errors, List<String> warnings) {
        Map<String, JsonElement> leaves = new LinkedHashMap<>();
        patchLeaves("", patch, leaves);
        for (Map.Entry<String, JsonElement> e : leaves.entrySet()) {
            String path = e.getKey();
            String id = idPrefix + path;
            String metaKey = (volcanoId == null ? "world:" : "volcano:") + path;
            String[] parts = path.split("\\.");
            Map<String, Object> node = tree;
            boolean found = true;
            for (int i = 0; i < parts.length - 1 && found; i++) {
                Object child = node.get(parts[i]);
                if (child instanceof Map<?, ?> m) node = (Map<String, Object>) m;
                else found = false;
            }
            String key = parts[parts.length - 1];
            if (!found || !node.containsKey(key)) {
                errors.add(new FieldError(id, "unknown setting"));
                continue;
            }
            Object old = node.get(key);
            JsonElement v = e.getValue();
            Object value;
            try {
                if (v.isJsonNull()) {
                    value = Tuning.AUTO.contains(metaKey) ? Double.NaN : defaults.get(id);
                    if (value == null) throw new IllegalArgumentException("has no default to go back to");
                } else {
                    value = coerce(old, v);
                }
            } catch (IllegalArgumentException ex) {
                errors.add(new FieldError(id, ex.getMessage()));
                continue;
            }
            Tuning.Meta meta = Tuning.META.get(metaKey);
            if (meta != null && value instanceof Number n && !Double.isNaN(n.doubleValue())) {
                double d = n.doubleValue();
                if ((meta.min() != null && d < meta.min()) || (meta.max() != null && d > meta.max())) {
                    errors.add(new FieldError(id, meta.label() + " must be between " + meta.min() + " and " + meta.max()));
                    continue;
                }
                String w = Tuning.advise(metaKey, meta.label(), d);
                if (w != null) warnings.add(w);
            } else if (value instanceof Number n && !Double.isNaN(n.doubleValue())) {
                String w = Tuning.advise(metaKey, Tuning.humanize(path)[0], n.doubleValue());
                if (w != null) warnings.add(w);
            }
            node.put(key, value);
        }
    }

    /** A JSON value as the type the definition holds at that place. */
    private static Object coerce(Object old, JsonElement v) {
        if (old instanceof Boolean) {
            if (!(v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean())) throw new IllegalArgumentException("expected true or false");
            return v.getAsBoolean();
        }
        if (old instanceof Number || (old instanceof String s && s.equalsIgnoreCase(".nan"))) {
            if (!(v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber())) throw new IllegalArgumentException("expected a number");
            double d = v.getAsDouble();
            if (!Double.isFinite(d)) throw new IllegalArgumentException("expected a finite number");
            if (old instanceof Integer) {
                if (d != Math.rint(d)) throw new IllegalArgumentException("expected a whole number");
                return (int) d;
            }
            if (old instanceof Long) {
                if (d != Math.rint(d)) throw new IllegalArgumentException("expected a whole number");
                return (long) d;
            }
            return d;
        }
        if (old instanceof String) {
            if (!(v.isJsonPrimitive() && v.getAsJsonPrimitive().isString())) throw new IllegalArgumentException("expected text");
            return v.getAsString();
        }
        if (old instanceof List<?>) {
            if (!v.isJsonArray()) throw new IllegalArgumentException("expected a list");
            return Tuning.toJavaValue(v);
        }
        throw new IllegalArgumentException("cannot be set this way");
    }

    // ── Responses ──

    /** The response to a request: the plan (dry run, confirmation) or what was done. */
    static JsonObject result(Plan p, ConfigImpact.Kind applied, boolean dryRun, long micros, String note) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("dryRun", dryRun);
        o.addProperty("plan", kindName(p.strongest()));
        if (applied != null) o.addProperty("applied", kindName(applied));
        o.add("changes", p.describe(dryRun ? null : applied));
        o.add("consequences", p.consequences());
        if (!p.warnings.isEmpty()) {
            JsonArray w = new JsonArray();
            p.warnings.forEach(w::add);
            o.add("warnings", w);
        }
        if (note != null) o.addProperty("note", note);
        o.add("ms", Json.num(micros / 1000.0));
        return o;
    }

    static JsonObject needsConfirmation(Plan p) {
        JsonObject o = result(p, null, true, 0, null);
        o.addProperty("ok", false);
        o.addProperty("needsConfirmation", true);
        o.addProperty("token", p.token());
        return o;
    }

    static JsonObject rejected(Rejected r) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.add("errors", r.json());
        return o;
    }

    /** History entries for applied changes (oldest first), as the Settings page lists them. */
    static List<JsonObject> auditEntries(Plan p, ConfigImpact.Kind applied, double simTime) {
        List<JsonObject> out = new ArrayList<>();
        for (ConfigChanges.Change c : p.changes.all()) {
            String vid = c.scope().startsWith("volcano:") ? c.scope().substring(8) : null;
            Tuning.Meta meta = Tuning.META.get((vid == null ? "world:" : "volcano:") + c.path());
            JsonObject a = new JsonObject();
            a.addProperty("at", System.currentTimeMillis());
            a.add("simTime", Json.num(simTime));
            a.addProperty("id", (vid == null ? "world." : "volcano." + vid + ".") + c.path());
            a.addProperty("label", (vid == null ? "" : p.names.getOrDefault(vid, vid) + ": ")
                    + (meta != null ? meta.label() : Tuning.humanize(c.path())[0]));
            a.add("from", auditValue(value(c.before())));
            a.add("to", auditValue(value(c.after())));
            ConfigImpact.Kind kind = applied.ordinal() > c.impact().kind().ordinal() ? applied : c.impact().kind();
            a.addProperty("apply", kindName(kind));
            a.addProperty("message", c.impact().message(p.nameOf(c)));
            out.add(a);
        }
        return out;
    }

    /** A computed (NaN, ".nan") value reads "auto" in the history. */
    private static JsonElement auditValue(JsonElement v) {
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() && v.getAsString().equalsIgnoreCase(".nan")) {
            return new JsonPrimitive("auto");
        }
        if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && Double.isNaN(v.getAsDouble())) return new JsonPrimitive("auto");
        return v;
    }
}
