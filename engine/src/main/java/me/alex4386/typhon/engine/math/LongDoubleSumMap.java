package me.alex4386.typhon.engine.math;

import java.util.Arrays;

/**
 * Sums of {@code double}s keyed by {@code long}, without boxing: open addressing with linear probing (keys
 * spread like {@link LongHashSet}). Each key's values are summed in the order they were added, as
 * {@code HashMap.merge(key, v, Double::sum)} does; keys are only exposed sorted, so iteration order can never
 * leak into results. No removal.
 */
public final class LongDoubleSumMap {
    private static final int INITIAL = 64;
    private long[] keys = new long[INITIAL];
    private double[] values = new double[INITIAL];
    private boolean[] used = new boolean[INITIAL];
    private int size;

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Adds {@code v} to the sum at {@code key} (a new key starts from {@code v}). */
    public void add(long key, double v) {
        if ((size + 1) * 2 > keys.length) rehash(keys.length * 2);
        int mask = keys.length - 1;
        int i = LongHashSet.hash(key) & mask;
        while (used[i]) {
            if (keys[i] == key) {
                values[i] += v;
                return;
            }
            i = (i + 1) & mask;
        }
        used[i] = true;
        keys[i] = key;
        values[i] = v;
        size++;
    }

    /** The sum at {@code key}, {@code 0} if absent. */
    public double get(long key) {
        int mask = keys.length - 1;
        for (int i = LongHashSet.hash(key) & mask; used[i]; i = (i + 1) & mask) {
            if (keys[i] == key) return values[i];
        }
        return 0;
    }

    /** The keys, ascending. */
    public long[] sortedKeys() {
        long[] out = new long[size];
        int n = 0;
        for (int i = 0; i < keys.length; i++) {
            if (used[i]) out[n++] = keys[i];
        }
        Arrays.sort(out);
        return out;
    }

    private void rehash(int capacity) {
        long[] oldKeys = keys;
        double[] oldValues = values;
        boolean[] oldUsed = used;
        keys = new long[capacity];
        values = new double[capacity];
        used = new boolean[capacity];
        int mask = capacity - 1;
        for (int j = 0; j < oldKeys.length; j++) {
            if (!oldUsed[j]) continue;
            int i = LongHashSet.hash(oldKeys[j]) & mask;
            while (used[i]) i = (i + 1) & mask;
            used[i] = true;
            keys[i] = oldKeys[j];
            values[i] = oldValues[j];
        }
    }
}
