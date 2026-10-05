package me.alex4386.typhon.engine.magma.conduit;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;

/**
 * Exact JSON form of {@link ConduitInput} and {@link ConduitSolution} for saved state: every
 * component by name, {@code NaN} as {@code null}. Doubles round-trip bit for bit.
 */
public final class ConduitJson {
    private ConduitJson() {}

    public static JsonObject write(Record value) {
        JsonObject out = new JsonObject();
        try {
            for (RecordComponent c : value.getClass().getRecordComponents()) {
                Object v = c.getAccessor().invoke(value);
                if (v instanceof Double d) {
                    out.add(c.getName(), d.isNaN() ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(d));
                } else if (v instanceof Boolean b) {
                    out.addProperty(c.getName(), b);
                } else if (v instanceof Enum<?> e) {
                    out.addProperty(c.getName(), e.name());
                } else {
                    throw new IllegalArgumentException("unsupported component " + c.getName());
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    public static ConduitInput readInput(JsonObject in) {
        return read(ConduitInput.class, in);
    }

    public static ConduitSolution readSolution(JsonObject in) {
        return read(ConduitSolution.class, in);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends Record> T read(Class<T> type, JsonObject in) {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] types = new Class<?>[components.length];
        Object[] args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent c = components[i];
            types[i] = c.getType();
            JsonElement e = in.get(c.getName());
            if (c.getType() == double.class) {
                args[i] = e == null || e.isJsonNull() ? Double.NaN : e.getAsDouble();
            } else if (c.getType() == boolean.class) {
                args[i] = e != null && e.getAsBoolean();
            } else if (c.getType().isEnum()) {
                args[i] = Enum.valueOf((Class<? extends Enum>) c.getType(), e.getAsString());
            } else {
                throw new IllegalArgumentException("unsupported component " + c.getName());
            }
        }
        try {
            Constructor<T> ctor = type.getDeclaredConstructor(types);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
