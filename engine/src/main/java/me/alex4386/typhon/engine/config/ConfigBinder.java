package me.alex4386.typhon.engine.config;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Maps definition sections onto the engine's existing configuration objects by name, so every tunable
 * is configurable without a hand-written schema per field:
 *
 * <ul>
 *   <li>mutable classes with public fields ({@code DikeConfig}, {@code GeothermalConfig}, ...),
 *   <li>records ({@code ConduitConfig}, {@code LavaConfig}): components override a base instance and
 *       the canonical constructor validates,
 *   <li>builders ({@code MagmaChamberConfig.Builder}): one-argument methods named after the key.
 * </ul>
 *
 * Supported value types: numbers, booleans, strings, enums (by name), {@code double[]} and sets/lists
 * of strings. Unknown keys are reported with the list of valid ones.
 */
public final class ConfigBinder {
    private ConfigBinder() {}

    // ── Public fields ──

    /** Configurable public fields of {@code type}, in declaration order. */
    public static Map<String, Field> fields(Class<?> type) {
        Map<String, Field> result = new LinkedHashMap<>();
        for (Field f : type.getFields()) {
            int m = f.getModifiers();
            if (Modifier.isStatic(m) || Modifier.isFinal(m) || !supported(f.getGenericType())) continue;
            result.put(f.getName(), f);
        }
        return result;
    }

    /**
     * Sets the public fields of {@code target} named by the node's keys. Keys in {@code reserved} are
     * left to the caller; {@code derived} keys are rejected (they are set from other sections).
     */
    public static void bindFields(ConfigNode node, Object target, Set<String> reserved, Set<String> derived) {
        Map<String, Field> fields = fields(target.getClass());
        for (String key : node.keys()) {
            if (reserved.contains(key)) continue;
            if (derived.contains(key)) throw node.error(key, "is derived from another section and cannot be set here");
            Field f = fields.get(key);
            if (f == null) continue;
            Object value = convert(node, key, f.getGenericType());
            try {
                f.set(target, value);
            } catch (IllegalAccessException e) {
                throw node.error(key, "cannot be set: " + e.getMessage());
            }
        }
        node.finish(union(fields.keySet(), reserved, derived));
    }

    public static Map<String, Object> exportFields(Object target, Set<String> skip) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Field f : fields(target.getClass()).values()) {
            if (skip.contains(f.getName())) continue;
            try {
                result.put(f.getName(), export(f.get(target)));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        return result;
    }

    // ── Records ──

    /** {@code base} with the components named by the node's keys replaced. */
    @SuppressWarnings("unchecked")
    public static <R extends Record> R bindRecord(ConfigNode node, R base, Set<String> reserved, Set<String> derived) {
        RecordComponent[] components = base.getClass().getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < components.length; i++) {
            RecordComponent c = components[i];
            types[i] = c.getType();
            try {
                args[i] = c.getAccessor().invoke(base);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(e);
            }
            if (!supported(c.getGenericType()) || reserved.contains(c.getName())) continue;
            names.add(c.getName());
            if (node.has(c.getName())) {
                if (derived.contains(c.getName())) {
                    throw node.error(c.getName(), "is derived from another section and cannot be set here");
                }
                args[i] = convert(node, c.getName(), c.getGenericType());
            }
        }
        node.finish(union(names, reserved, derived));
        try {
            Constructor<?> ctor = base.getClass().getDeclaredConstructor(types);
            ctor.setAccessible(true);
            return (R) ctor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw node.error("invalid values: " + e.getCause().getMessage());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, Object> exportRecord(Record record, Set<String> skip) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (RecordComponent c : record.getClass().getRecordComponents()) {
            if (skip.contains(c.getName()) || !supported(c.getGenericType())) continue;
            try {
                result.put(c.getName(), export(c.getAccessor().invoke(record)));
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException(e);
            }
        }
        return result;
    }

    // ── Builders ──

    /** Calls the builder's one-argument setter named by each key. */
    public static void bindBuilder(ConfigNode node, Object builder, Set<String> reserved, Set<String> derived) {
        Map<String, Method> setters = new LinkedHashMap<>();
        for (Method m : builder.getClass().getMethods()) {
            if (m.getParameterCount() != 1 || m.getReturnType() != builder.getClass()) continue;
            if (!supported(m.getGenericParameterTypes()[0])) continue;
            setters.put(m.getName(), m);
        }
        for (String key : node.keys()) {
            if (reserved.contains(key)) continue;
            if (derived.contains(key)) throw node.error(key, "is derived from another section and cannot be set here");
            Method m = setters.get(key);
            if (m == null) continue;
            try {
                m.invoke(builder, convert(node, key, m.getGenericParameterTypes()[0]));
            } catch (InvocationTargetException e) {
                throw node.error(key, e.getCause().getMessage());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        Set<String> valid = new TreeSet<>(setters.keySet());
        valid.removeAll(derived);
        node.finish(union(valid, reserved, Set.of()));
    }

    // ── Values ──

    static boolean supported(Type type) {
        if (type instanceof Class<?> c) {
            return c == double.class || c == Double.class || c == int.class || c == Integer.class || c == long.class
                    || c == Long.class || c == boolean.class || c == Boolean.class || c == String.class || c.isEnum()
                    || c == double[].class;
        }
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw
                && Collection.class.isAssignableFrom(raw) && p.getActualTypeArguments().length == 1) {
            Type arg = p.getActualTypeArguments()[0];
            return arg == String.class;
        }
        return false;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object convert(ConfigNode node, String key, Type type) {
        Object raw = node.raw(key);
        if (type instanceof Class<?> c) {
            if (c == double.class || c == Double.class) return node.toDouble(key, raw);
            if (c == int.class || c == Integer.class) return node.toInt(key, raw);
            if (c == long.class || c == Long.class) {
                if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) return n.longValue();
                throw node.error(key, "expected an integer, got " + ConfigNode.describe(raw));
            }
            if (c == boolean.class || c == Boolean.class) {
                if (raw instanceof Boolean b) return b;
                throw node.error(key, "expected true or false, got " + ConfigNode.describe(raw));
            }
            if (c == String.class) {
                if (raw instanceof String s) return s;
                throw node.error(key, "expected a string, got " + ConfigNode.describe(raw));
            }
            if (c.isEnum()) {
                if (raw instanceof String s) {
                    for (Object constant : c.getEnumConstants()) {
                        if (((Enum<?>) constant).name().equalsIgnoreCase(s)) return constant;
                    }
                }
                List<String> names = new ArrayList<>();
                for (Object constant : c.getEnumConstants()) names.add(((Enum<?>) constant).name().toLowerCase());
                throw node.error(key, "expected one of " + names + ", got " + ConfigNode.describe(raw));
            }
            if (c == double[].class) {
                if (!(raw instanceof List<?> list)) throw node.error(key, "expected a list of numbers");
                double[] values = new double[list.size()];
                for (int i = 0; i < values.length; i++) values[i] = node.toDouble(key + "[" + i + "]", list.get(i));
                return values;
            }
        }
        if (type instanceof ParameterizedType p) {
            if (!(raw instanceof List<?> list)) throw node.error(key, "expected a list");
            Class<?> rawType = (Class<?>) p.getRawType();
            // Sorted sets: configuration objects are hashed through their serialised form.
            Collection result = Set.class.isAssignableFrom(rawType) ? new TreeSet<>() : new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof String s)) throw node.error(key, "expected a list of strings");
                try {
                    result.add(s);
                } catch (IllegalArgumentException e) {
                    throw node.error(key, e.getMessage());
                }
            }
            return result;
        }
        throw node.error(key, "is not configurable");
    }

    /** A YAML-friendly representation of a configuration value. */
    static Object export(Object value) {
        if (value instanceof Double d) {
            if (d.isNaN()) return ".nan";
            if (d.isInfinite()) return d > 0 ? ".inf" : "-.inf";
            return d;
        }
        if (value instanceof Enum<?> e) return e.name().toLowerCase();
        if (value instanceof double[] array) {
            List<Object> list = new ArrayList<>();
            for (double v : array) list.add(export(v));
            return list;
        }
        if (value instanceof Collection<?> collection) {
            List<String> list = new ArrayList<>();
            for (Object item : collection) list.add(item.toString());
            list.sort(null);
            return list;
        }
        return value;
    }

    private static Set<String> union(Collection<String> a, Collection<String> b, Collection<String> c) {
        Set<String> s = new TreeSet<>(a);
        s.addAll(b);
        s.addAll(c);
        return s;
    }
}
