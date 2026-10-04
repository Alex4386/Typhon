package me.alex4386.typhon.engine.save;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Named primitive arrays describing one chunk (or one block) of a spatial field, e.g. the lava
 * thickness and temperature of a 16×16 chunk. Arrays are stored as-is (bit-exact) in region files.
 */
public final class FieldChunk {
    private final TreeMap<String, Object> arrays = new TreeMap<>();

    public FieldChunk doubles(String name, double[] values) { return put(name, values); }
    public FieldChunk floats(String name, float[] values) { return put(name, values); }
    public FieldChunk ints(String name, int[] values) { return put(name, values); }
    public FieldChunk longs(String name, long[] values) { return put(name, values); }
    public FieldChunk bytes(String name, byte[] values) { return put(name, values); }

    public FieldChunk booleans(String name, boolean[] values) {
        byte[] packed = new byte[values.length];
        for (int i = 0; i < values.length; i++) packed[i] = (byte) (values[i] ? 1 : 0);
        return put(name, packed);
    }

    private FieldChunk put(String name, Object array) {
        arrays.put(name, array);
        return this;
    }

    public boolean has(String name) {
        return arrays.containsKey(name);
    }

    public double[] doubles(String name) { return get(name, double[].class); }
    public float[] floats(String name) { return get(name, float[].class); }
    public int[] ints(String name) { return get(name, int[].class); }
    public long[] longs(String name) { return get(name, long[].class); }
    public byte[] bytes(String name) { return get(name, byte[].class); }

    public boolean[] booleans(String name) {
        byte[] packed = bytes(name);
        if (packed == null) return null;
        boolean[] values = new boolean[packed.length];
        for (int i = 0; i < packed.length; i++) values[i] = packed[i] != 0;
        return values;
    }

    private <T> T get(String name, Class<T> type) {
        Object array = arrays.get(name);
        if (array == null) return null;
        if (!type.isInstance(array)) {
            throw new IllegalStateException("Field array " + name + " is " + array.getClass().getSimpleName()
                    + ", not " + type.getSimpleName());
        }
        return type.cast(array);
    }

    /** All arrays, sorted by name. */
    public Map<String, Object> arrays() {
        return Collections.unmodifiableMap(arrays);
    }
}
