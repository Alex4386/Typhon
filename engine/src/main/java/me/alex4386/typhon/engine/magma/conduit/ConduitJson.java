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
                } else if (v instanceof ConduitShape shape) {
                    com.google.gson.JsonArray radii = new com.google.gson.JsonArray();
                    for (double r : shape.radii()) radii.add(r);
                    out.add(c.getName(), radii);
                } else if (v == null && c.getType() == ConduitShape.class) {
                    out.add(c.getName(), JsonNull.INSTANCE);
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
            } else if (c.getType() == ConduitShape.class) {
                if (e == null || e.isJsonNull()) {
                    args[i] = null; // a cylinder (and every save from before conduits eroded)
                } else {
                    com.google.gson.JsonArray a = e.getAsJsonArray();
                    double[] radii = new double[a.size()];
                    for (int k = 0; k < radii.length; k++) radii[k] = a.get(k).getAsDouble();
                    args[i] = new ConduitShape(radii);
                }
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
