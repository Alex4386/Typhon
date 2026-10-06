package me.alex4386.typhon.engine.worlds;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Differences between the definitions a save was made with and the current ones, classified by what
 * they need:
 *
 * <ul>
 *   <li>{@link Kind#HOT}: the saved state stays valid (live and reload changes).
 *   <li>{@link Kind#REINIT}: changes the meaning of saved state; applying it needs an explicit
 *       decision (see {@link World.ChangePolicy}).
 * </ul>
 * Which is which is decided by {@link ConfigImpact}, the single source of these rules.
 */
public final class ConfigChanges {
    public enum Kind { HOT, REINIT }

    /**
     * One changed leaf.
     *
     * @param scope {@code "world"} or {@code "volcano:<id>"}
     * @param path dotted key path inside the definition ({@code "*"} for a volcano added/removed)
     * @param before previous value as JSON text, {@code null} if absent
     * @param after current value as JSON text, {@code null} if absent
     */
    public record Change(String scope, String path, Kind kind, String before, String after, ConfigImpact.Impact impact) {
        @Override
        public String toString() {
            return scope + " " + path + ": " + before + " -> " + after + " (" + kind.name().toLowerCase() + ")";
        }
    }

    /**
     * How a change to the world definition at {@code path} (dotted, as in {@link Change#path()})
     * would be classified.
     */
    public static Kind worldKind(String path) {
        return ConfigImpact.world(path).keepsState() ? Kind.HOT : Kind.REINIT;
    }

    /** How a change to a volcano definition at {@code path} would be classified. */
    public static Kind volcanoKind(String path) {
        return ConfigImpact.volcano(path).keepsState() ? Kind.HOT : Kind.REINIT;
    }

    public static final ConfigChanges NONE = new ConfigChanges(List.of());

    private final List<Change> changes;

    ConfigChanges(List<Change> changes) {
        this.changes = List.copyOf(changes);
    }

    public List<Change> all() {
        return changes;
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    public boolean requiresReinit() {
        return changes.stream().anyMatch(c -> c.kind() == Kind.REINIT);
    }

    /** Volcanoes with at least one change of any kind. */
    public Set<String> changedVolcanoes() {
        Set<String> ids = new TreeSet<>();
        for (Change c : changes) {
            if (c.scope().startsWith("volcano:")) ids.add(c.scope().substring("volcano:".length()));
        }
        return ids;
    }

    /** The most disruptive change: what applying all of them needs. */
    public ConfigImpact.Kind strongest() {
        ConfigImpact.Kind k = ConfigImpact.Kind.LIVE;
        for (Change c : changes) if (c.impact().kind().ordinal() > k.ordinal()) k = c.impact().kind();
        return k;
    }

    /**
     * Subsystem ids of {@code volcanoId} a reset clears: all of them ({@code null}) when a change resets the
     * whole volcano, otherwise only those of its partial-reset targets (tephra, hot springs, crater detail).
     */
    public Set<String> reinitSubsystems(String volcanoId) {
        Set<String> ids = new TreeSet<>();
        for (Change c : changes) {
            if (c.kind() != Kind.REINIT || !c.scope().equals("volcano:" + volcanoId)) continue;
            List<String> partial = c.impact().target().subsystemIds(volcanoId);
            if (partial.isEmpty()) return null;
            ids.addAll(partial);
        }
        return ids;
    }

    /** Volcanoes with a re-init change. */
    public Set<String> reinitVolcanoes() {
        Set<String> ids = new TreeSet<>();
        for (Change c : changes) {
            if (c.kind() == Kind.REINIT && c.scope().startsWith("volcano:")) {
                ids.add(c.scope().substring("volcano:".length()));
            }
        }
        return ids;
    }

    public boolean worldRequiresReinit() {
        return changes.stream().anyMatch(c -> c.scope().equals("world") && c.kind() == Kind.REINIT);
    }

    @Override
    public String toString() {
        if (changes.isEmpty()) return "no definition changes";
        StringBuilder sb = new StringBuilder();
        for (Change c : changes) sb.append("\n  ").append(c);
        return sb.toString();
    }

    // ── Diffing ──

    /** The changes from one set of definitions to another (volcanoes matched by id). */
    public static ConfigChanges between(me.alex4386.typhon.engine.config.WorldDefinition worldBefore,
            java.util.Collection<me.alex4386.typhon.engine.config.VolcanoDefinition> before,
            me.alex4386.typhon.engine.config.WorldDefinition worldAfter,
            java.util.Collection<me.alex4386.typhon.engine.config.VolcanoDefinition> after) {
        Map<String, JsonObject> a = new java.util.TreeMap<>();
        for (var v : before) a.put(v.id(), tree(v.toTree()));
        Map<String, JsonObject> b = new java.util.TreeMap<>();
        for (var v : after) b.put(v.id(), tree(v.toTree()));
        return compare(tree(worldBefore.toTree()), tree(worldAfter.toTree()), a, b);
    }

    private static JsonObject tree(Map<String, Object> tree) {
        return me.alex4386.typhon.engine.save.SaveFormat.gson().toJsonTree(tree).getAsJsonObject();
    }

    static ConfigChanges compare(JsonObject worldBefore, JsonObject worldAfter, Map<String, JsonObject> before,
            Map<String, JsonObject> after) {
        List<Change> out = new ArrayList<>();
        if (worldBefore != null) diff("world", "", worldBefore, worldAfter, ConfigImpact::world, out);
        Set<String> ids = new TreeSet<>(before.keySet());
        ids.addAll(after.keySet());
        for (String id : ids) {
            JsonObject a = before.get(id);
            JsonObject b = after.get(id);
            String scope = "volcano:" + id;
            if (a == null) {
                out.add(new Change(scope, "*", Kind.HOT, null, "added",
                        ConfigImpact.volcanoAdded()));
            } else if (b == null) {
                out.add(new Change(scope, "*", Kind.REINIT, "present", null,
                        ConfigImpact.volcanoRemoved()));
            } else {
                diff(scope, "", a, b, ConfigImpact::volcano, out);
            }
        }
        return new ConfigChanges(out);
    }

    /** Leaf-by-leaf differences; lists compare as a whole. */
    static void diff(String scope, String path, JsonElement before, JsonElement after,
            java.util.function.Function<String, ConfigImpact.Impact> rules, List<Change> out) {
        if (before != null && after != null && before.isJsonObject() && after.isJsonObject()) {
            Set<String> keys = new TreeSet<>(before.getAsJsonObject().keySet());
            keys.addAll(after.getAsJsonObject().keySet());
            for (String k : keys) {
                diff(scope, path.isEmpty() ? k : path + "." + k, before.getAsJsonObject().get(k),
                        after.getAsJsonObject().get(k), rules, out);
            }
            return;
        }
        if (!equal(before, after)) {
            ConfigImpact.Impact impact = rules.apply(path);
            out.add(new Change(scope, path, impact.keepsState() ? Kind.HOT : Kind.REINIT, text(before), text(after), impact));
        }
    }

    private static boolean equal(JsonElement a, JsonElement b) {
        if (a == null || b == null) return a == b;
        if (a instanceof JsonPrimitive x && b instanceof JsonPrimitive y && x.isNumber() && y.isNumber()) {
            return Double.compare(x.getAsDouble(), y.getAsDouble()) == 0;
        }
        return a.equals(b);
    }

    private static String text(JsonElement e) {
        return e == null ? null : e.toString();
    }

    /** Glob with at most one {@code *} matching any run of characters (dots included). */
    static boolean matches(String pattern, String path) {
        int star = pattern.indexOf('*');
        if (star < 0) return pattern.equals(path);
        String prefix = pattern.substring(0, star);
        String suffix = pattern.substring(star + 1);
        return path.length() >= prefix.length() + suffix.length() && path.startsWith(prefix) && path.endsWith(suffix);
    }
}
