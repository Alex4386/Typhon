package me.alex4386.plugin.typhon.volcano.vent;

import me.alex4386.plugin.typhon.volcano.erupt.VolcanoEruptStyle;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Single source of truth for vent configuration nodes.
 * Used by both the in-game command handler and the web API.
 */
public class VolcanoVentConfigNodeManager {

    public enum NodeType { INT, FLOAT, DOUBLE, BOOLEAN, ENUM, STRING }

    public static class NodeInfo {
        public final String key;
        public final NodeType type;
        public final Double min;
        public final Double max;
        public final String[] options;
        private final Function<VolcanoVent, Object> getter;
        private final BiFunction<VolcanoVent, String, String> setter;

        private NodeInfo(String key, NodeType type, Double min, Double max, String[] options,
                         Function<VolcanoVent, Object> getter,
                         BiFunction<VolcanoVent, String, String> setter) {
            this.key = key;
            this.type = type;
            this.min = min;
            this.max = max;
            this.options = options;
            this.getter = getter;
            this.setter = setter;
        }

        public Object getValue(VolcanoVent vent) {
            return getter.apply(vent);
        }

        /** Returns null on success, or an error message. */
        public String setValue(VolcanoVent vent, String value) {
            return setter.apply(vent, value);
        }

        @SuppressWarnings("unchecked")
        public JSONObject toJson(VolcanoVent vent) {
            JSONObject node = new JSONObject();
            node.put("key", key);
            node.put("type", type.name().toLowerCase());
            node.put("value", getter.apply(vent));
            if (min != null) node.put("min", min);
            if (max != null) node.put("max", max);
            if (options != null) {
                JSONArray opts = new JSONArray();
                for (String opt : options) opts.add(opt);
                node.put("options", opts);
            }
            return node;
        }
    }

    // ── Builder ─────────────────────────────────────────────────────────

    private static class Builder {
        private final String key;
        private final NodeType type;
        private Double min;
        private Double max;
        private String[] options;
        private Function<VolcanoVent, Object> getter;
        private BiFunction<VolcanoVent, String, String> setter;

        Builder(String key, NodeType type) {
            this.key = key;
            this.type = type;
        }

        Builder range(double min, double max) { this.min = min; this.max = max; return this; }
        Builder options(String[] options) { this.options = options; return this; }
        Builder get(Function<VolcanoVent, Object> getter) { this.getter = getter; return this; }
        Builder set(BiFunction<VolcanoVent, String, String> setter) { this.setter = setter; return this; }

        NodeInfo build() {
            return new NodeInfo(key, type, min, max, options, getter, setter);
        }
    }

    private static Builder node(String key, NodeType type) {
        return new Builder(key, type);
    }

    // ── Setter helpers ──────────────────────────────────────────────────

    private static BiFunction<VolcanoVent, String, String> setInt(java.util.function.ObjIntConsumer<VolcanoVent> consumer) {
        return (v, s) -> { consumer.accept(v, Integer.parseInt(s)); return null; };
    }

    private static BiFunction<VolcanoVent, String, String> setFloat(java.util.function.BiConsumer<VolcanoVent, Float> consumer) {
        return (v, s) -> { consumer.accept(v, Float.parseFloat(s)); return null; };
    }

    private static BiFunction<VolcanoVent, String, String> setDouble(java.util.function.BiConsumer<VolcanoVent, Double> consumer) {
        return (v, s) -> { consumer.accept(v, Double.parseDouble(s)); return null; };
    }

    private static BiFunction<VolcanoVent, String, String> setBool(java.util.function.BiConsumer<VolcanoVent, Boolean> consumer) {
        return (v, s) -> { consumer.accept(v, Boolean.parseBoolean(s)); return null; };
    }

    // ── Node registry ───────────────────────────────────────────────────

    private static final String[] ERUPTION_STYLES;
    private static final String[] VENT_TYPES;
    static {
        VolcanoEruptStyle[] values = VolcanoEruptStyle.values();
        ERUPTION_STYLES = new String[values.length];
        for (int i = 0; i < values.length; i++) ERUPTION_STYLES[i] = values[i].toString();

        VolcanoVentType[] vtValues = VolcanoVentType.values();
        VENT_TYPES = new String[vtValues.length];
        for (int i = 0; i < vtValues.length; i++) VENT_TYPES[i] = vtValues[i].toString();
    }

    private static final Map<String, NodeInfo> NODES;
    static {
        Map<String, NodeInfo> m = new LinkedHashMap<>();

        // ── erupt ──
        m.put("erupt:style", node("erupt:style", NodeType.ENUM).options(ERUPTION_STYLES)
                .get(v -> v.erupt != null && v.erupt.getStyle() != null ? v.erupt.getStyle().toString() : "UNKNOWN")
                .set((v, s) -> {
                    VolcanoEruptStyle style = VolcanoEruptStyle.getVolcanoEruptStyle(s);
                    if (style == null) return "Unknown eruption style: " + s;
                    v.erupt.setStyle(style);
                    v.erupt.autoConfig();
                    return null;
                }).build());

        // ── lavaflow ──
        m.put("lavaflow:delay", node("lavaflow:delay", NodeType.INT)
                .get(v -> v.lavaFlow != null ? v.lavaFlow.settings.delayFlowed : 0)
                .set(setInt((v, i) -> v.lavaFlow.settings.delayFlowed = i)).build());

        m.put("lavaflow:flowed", node("lavaflow:flowed", NodeType.INT)
                .get(v -> v.lavaFlow != null ? v.lavaFlow.settings.flowed : 0)
                .set(setInt((v, i) -> v.lavaFlow.settings.flowed = i)).build());

        m.put("lavaflow:silicateLevel", node("lavaflow:silicateLevel", NodeType.DOUBLE).range(0.3, 0.9)
                .get(v -> v.lavaFlow != null ? v.lavaFlow.settings.silicateLevel : 0.0)
                .set(setDouble((v, d) -> v.lavaFlow.settings.silicateLevel = Math.min(0.9, Math.max(0.3, d)))).build());

        m.put("lavaflow:gasContent", node("lavaflow:gasContent", NodeType.DOUBLE).range(0.0, 1.0)
                .get(v -> v.lavaFlow != null ? v.lavaFlow.settings.gasContent : 0.0)
                .set(setDouble((v, d) -> v.lavaFlow.settings.gasContent = Math.min(1.0, Math.max(0.0, d)))).build());

        m.put("lavaflow:usePouredLava", node("lavaflow:usePouredLava", NodeType.BOOLEAN)
                .get(v -> v.lavaFlow != null && v.lavaFlow.settings.usePouredLava)
                .set(setBool((v, b) -> v.lavaFlow.settings.usePouredLava = b)).build());

        m.put("lavaflow:allowPickUp", node("lavaflow:allowPickUp", NodeType.BOOLEAN)
                .get(v -> v.lavaFlow != null && v.lavaFlow.settings.allowPickUp)
                .set(setBool((v, b) -> v.lavaFlow.settings.allowPickUp = b)).build());

        // ── bombs ──
        m.put("bombs:explosionPower:min", node("bombs:explosionPower:min", NodeType.FLOAT)
                .get(v -> v.bombs != null ? v.bombs.minBombPower : 0f)
                .set(setFloat((v, f) -> v.bombs.minBombPower = f)).build());

        m.put("bombs:explosionPower:max", node("bombs:explosionPower:max", NodeType.FLOAT)
                .get(v -> v.bombs != null ? v.bombs.maxBombPower : 0f)
                .set(setFloat((v, f) -> v.bombs.maxBombPower = f)).build());

        m.put("bombs:radius:min", node("bombs:radius:min", NodeType.INT)
                .get(v -> v.bombs != null ? v.bombs.minBombRadius : 0)
                .set(setInt((v, i) -> v.bombs.minBombRadius = i)).build());

        m.put("bombs:radius:max", node("bombs:radius:max", NodeType.INT)
                .get(v -> v.bombs != null ? v.bombs.maxBombRadius : 0)
                .set(setInt((v, i) -> v.bombs.maxBombRadius = i)).build());

        m.put("bombs:delay", node("bombs:delay", NodeType.INT)
                .get(v -> v.bombs != null ? v.bombs.bombDelay : 0)
                .set(setInt((v, i) -> v.bombs.bombDelay = i)).build());

        m.put("bombs:baseY", node("bombs:baseY", NodeType.INT)
                .get(v -> v.bombs != null ? v.bombs.baseY : 0)
                .set((v, s) -> {
                    if ("reset".equalsIgnoreCase(s)) v.bombs.resetBaseY();
                    else v.bombs.baseY = Integer.parseInt(s);
                    return null;
                }).build());

        // ── explosion ──
        m.put("explosion:bombs:min", node("explosion:bombs:min", NodeType.INT)
                .get(v -> v.explosion != null ? v.explosion.settings.minBombCount : 0)
                .set(setInt((v, i) -> v.explosion.settings.minBombCount = i)).build());

        m.put("explosion:bombs:max", node("explosion:bombs:max", NodeType.INT)
                .get(v -> v.explosion != null ? v.explosion.settings.maxBombCount : 0)
                .set(setInt((v, i) -> v.explosion.settings.maxBombCount = i)).build());

        m.put("explosion:scheduler:size", node("explosion:scheduler:size", NodeType.INT)
                .get(v -> v.explosion != null ? v.explosion.settings.explosionSize : 0)
                .set(setInt((v, i) -> v.explosion.settings.explosionSize = i)).build());

        m.put("explosion:scheduler:damagingSize", node("explosion:scheduler:damagingSize", NodeType.INT)
                .get(v -> v.explosion != null ? v.explosion.settings.damagingExplosionSize : 0)
                .set(setInt((v, i) -> v.explosion.settings.damagingExplosionSize = i)).build());

        // ── vent ──
        m.put("vent:craterRadius", node("vent:craterRadius", NodeType.INT)
                .get(v -> v.craterRadius)
                .set((v, s) -> { v.setRadius(Integer.parseInt(s)); v.flushCache(); return null; }).build());

        m.put("vent:type", node("vent:type", NodeType.ENUM).options(VENT_TYPES)
                .get(v -> v.getType() != null ? v.getType().toString() : "UNKNOWN")
                .set((v, s) -> { v.setType(VolcanoVentType.fromString(s)); v.flushCache(); return null; }).build());

        m.put("vent:fissureLength", node("vent:fissureLength", NodeType.INT)
                .get(v -> v.fissureLength)
                .set((v, s) -> { v.fissureLength = Integer.parseInt(s); v.flushCache(); return null; }).build());

        m.put("vent:fissureAngle", node("vent:fissureAngle", NodeType.DOUBLE)
                .get(v -> v.fissureAngle)
                .set((v, s) -> { v.fissureAngle = Double.parseDouble(s); v.flushCache(); return null; }).build());

        // ── succession ──
        m.put("succession:enable", node("succession:enable", NodeType.BOOLEAN)
                .get(v -> v.enableSuccession)
                .set(setBool((v, b) -> v.enableSuccession = b)).build());

        m.put("succession:probability", node("succession:probability", NodeType.DOUBLE)
                .get(v -> v.successionProbability)
                .set(setDouble((v, d) -> v.successionProbability = d)).build());

        m.put("succession:treeProbability", node("succession:treeProbability", NodeType.DOUBLE)
                .get(v -> v.successionTreeProbability)
                .set(setDouble((v, d) -> v.successionTreeProbability = d)).build());

        // ── ash ──i
        m.put("ash:fullPyroclasticFlowProbability", node("ash:fullPyroclasticFlowProbability", NodeType.DOUBLE)
                .get(v -> v.fullPyroclasticFlowProbability)
                .set(setDouble((v, d) -> v.fullPyroclasticFlowProbability = d)).build());

        NODES = Collections.unmodifiableMap(m);
    }

    // ── Public API ──────────────────────────────────────────────────────

    public static Map<String, NodeInfo> getAll() {
        return NODES;
    }

    public static List<String> getKeys() {
        return List.copyOf(NODES.keySet());
    }

    public static NodeInfo getInfo(String key) {
        return NODES.get(key);
    }

    public static Object getValue(VolcanoVent vent, String key) {
        NodeInfo info = NODES.get(key);
        return info != null ? info.getValue(vent) : null;
    }

    public static String setValue(VolcanoVent vent, String key, String value) {
        NodeInfo info = NODES.get(key);
        if (info == null) return "Unknown config node: " + key;
        return info.setValue(vent, value);
    }

    public static JSONObject toJson(VolcanoVent vent, String key) {
        NodeInfo info = NODES.get(key);
        if (info == null) return null;
        return info.toJson(vent);
    }

    @SuppressWarnings("unchecked")
    public static JSONArray allToJson(VolcanoVent vent) {
        JSONArray arr = new JSONArray();
        for (NodeInfo info : NODES.values()) {
            try {
                arr.add(info.toJson(vent));
            } catch (Exception ignored) {}
        }
        return arr;
    }
}
