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
 *   <li>{@link Kind#HOT}: applies to existing state as is (climate, time compression, magma supply,
 *       feature caps and rates, names, the active flag, new volcanoes).
 *   <li>{@link Kind#REINIT}: changes the meaning of saved state (grid, geology, chamber geometry,
 *       vents, removing a volcano). Applying it needs an explicit decision (see
 *       {@link World.ChangePolicy}).
 * </ul>
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
    public record Change(String scope, String path, Kind kind, String before, String after) {
        @Override
        public String toString() {
            return scope + " " + path + ": " + before + " -> " + after + " (" + kind.name().toLowerCase() + ")";
        }
    }

    /** Hot-reloadable world keys (exact paths, or patterns with one {@code *}). */
    static final List<String> HOT_WORLD = List.of(
            "name", "climate.*", "scaling.dormantTimeCompression", "scaling.eruptiveTimeCompression", "terrain*",
            "subsurface.timeScale", "subsurface.macroStepSeconds", "subsurface.surfaceWaterStepSeconds",
            "subsurface.hotChangeC", "subsurface.warmEvery", "subsurface.demoteAfter",
            "subsurface.groundwaterIterations", "subsurface.sorOmega", "subsurface.manningN",
            "subsurface.vadoseLagSeconds", "subsurface.threads");

    /** Hot-reloadable volcano keys. */
    static final List<String> HOT_VOLCANO = List.of(
            "name", "active", "timeCompression*", "ballisticFraction",
            "magma.chamber.supplyRate", "magma.chamber.supplyVariability",
            // properties of magma added from now on (the supply and injections); the chamber's own
            // magma is state and stays as it is
            "magma.chamber.recharge*",
            "geothermal.timeScale", "geothermal.prewarmSeconds", "geothermal.max*", "geothermal.*PerHour",
            "tephra.initialWind*", "tephra.max*", "deformation.stations*");

    /**
     * How a change to the world definition at {@code path} (dotted, as in {@link Change#path()})
     * would be classified.
     */
    public static Kind worldKind(String path) {
        return classify(path, HOT_WORLD);
    }

    /** How a change to a volcano definition at {@code path} would be classified. */
    public static Kind volcanoKind(String path) {
        return classify(path, HOT_VOLCANO);
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

    static ConfigChanges compare(JsonObject worldBefore, JsonObject worldAfter, Map<String, JsonObject> before,
            Map<String, JsonObject> after) {
        List<Change> out = new ArrayList<>();
        if (worldBefore != null) diff("world", "", worldBefore, worldAfter, HOT_WORLD, out);
        Set<String> ids = new TreeSet<>(before.keySet());
        ids.addAll(after.keySet());
        for (String id : ids) {
            JsonObject a = before.get(id);
            JsonObject b = after.get(id);
            String scope = "volcano:" + id;
            if (a == null) {
                out.add(new Change(scope, "*", Kind.HOT, null, "added"));
            } else if (b == null) {
                out.add(new Change(scope, "*", Kind.REINIT, "present", null));
            } else {
                diff(scope, "", a, b, HOT_VOLCANO, out);
            }
        }
        return new ConfigChanges(out);
    }

    /** Leaf-by-leaf differences; lists compare as a whole. */
    static void diff(String scope, String path, JsonElement before, JsonElement after, List<String> hot,
            List<Change> out) {
        if (before != null && after != null && before.isJsonObject() && after.isJsonObject()) {
            Set<String> keys = new TreeSet<>(before.getAsJsonObject().keySet());
            keys.addAll(after.getAsJsonObject().keySet());
            for (String k : keys) {
                diff(scope, path.isEmpty() ? k : path + "." + k, before.getAsJsonObject().get(k),
                        after.getAsJsonObject().get(k), hot, out);
            }
            return;
        }
        if (!equal(before, after)) {
            out.add(new Change(scope, path, classify(path, hot), text(before), text(after)));
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

    static Kind classify(String path, List<String> hot) {
        for (String pattern : hot) {
            if (matches(pattern, path)) return Kind.HOT;
        }
        return Kind.REINIT;
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
