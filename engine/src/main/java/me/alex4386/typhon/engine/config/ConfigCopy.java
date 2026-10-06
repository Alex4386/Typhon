package me.alex4386.typhon.engine.config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Field-level helpers for the mutable configuration classes (public fields), used by live retuning:
 * a subsystem copies a new configuration into the instance it and its helpers share, so all of them
 * see the new values at once.
 */
public final class ConfigCopy {
    private ConfigCopy() {}

    /** Copies every instance field of {@code from} into {@code to} (collections as fresh copies). */
    public static <T> void into(T from, T to) {
        if (from.getClass() != to.getClass()) {
            throw new IllegalArgumentException("Cannot copy " + from.getClass().getName() + " into " + to.getClass().getName());
        }
        for (Field f : fields(from.getClass())) {
            try {
                f.set(to, copy(f.get(from)));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot copy field " + f.getName(), e);
            }
        }
    }

    /** Whether {@code a} and {@code b} agree on the named fields. */
    public static boolean same(Object a, Object b, String... names) {
        for (String name : names) {
            Field f = field(a.getClass(), name);
            try {
                if (!Objects.equals(f.get(a), f.get(b))) return false;
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot read field " + name, e);
            }
        }
        return true;
    }

    private static Object copy(Object value) {
        if (value instanceof List<?> l) return new ArrayList<>(l);
        if (value instanceof Set<?> s) return s instanceof java.util.SortedSet<?> sorted ? new java.util.TreeSet<>(sorted) : new LinkedHashSet<>(s);
        if (value instanceof Map<?, ?> m) return new LinkedHashMap<>(m);
        if (value instanceof Collection<?> c) return new ArrayList<>(c);
        return value;
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                int m = f.getModifiers();
                if (Modifier.isStatic(m) || f.isSynthetic()) continue;
                if (Modifier.isFinal(m)) {
                    throw new IllegalStateException(type.getSimpleName() + "." + f.getName() + " is final; live configuration needs it mutable");
                }
                f.setAccessible(true);
                out.add(f);
            }
        }
        return out;
    }

    private static Field field(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                // superclass
            }
        }
        throw new IllegalArgumentException(type.getSimpleName() + " has no field " + name);
    }
}
