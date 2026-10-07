package me.alex4386.typhon.engine.math;

/**
 * A set of {@code long}s without boxing: open addressing with linear probing and backward-shift deletion,
 * keys spread by a bijective mixer (packed coordinates hash badly as {@code Long.hashCode}). Iteration order is
 * not exposed, so it can never leak into results.
 */
public final class LongHashSet {
    private long[] slots = new long[64];
    private boolean[] used = new boolean[64];
    private int size;

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    static int hash(long v) {
        v = (v ^ (v >>> 30)) * 0xbf58476d1ce4e5b9L;
        v = (v ^ (v >>> 27)) * 0x94d049bb133111ebL;
        return (int) (v ^ (v >>> 31));
    }

    public boolean contains(long v) {
        int mask = slots.length - 1;
        for (int i = hash(v) & mask; used[i]; i = (i + 1) & mask) {
            if (slots[i] == v) return true;
        }
        return false;
    }

    /** Adds {@code v}; {@code false} if it was already present. */
    public boolean add(long v) {
        if ((size + 1) * 2 > slots.length) rehash(slots.length * 2);
        int mask = slots.length - 1;
        int i = hash(v) & mask;
        while (used[i]) {
            if (slots[i] == v) return false;
            i = (i + 1) & mask;
        }
        used[i] = true;
        slots[i] = v;
        size++;
        return true;
    }

    /** Removes {@code v}; {@code false} if it was absent. */
    public boolean remove(long v) {
        int mask = slots.length - 1;
        int i = hash(v) & mask;
        while (true) {
            if (!used[i]) return false;
            if (slots[i] == v) break;
            i = (i + 1) & mask;
        }
        // backward-shift deletion keeps every probe chain unbroken
        int gap = i;
        int j = i;
        while (true) {
            j = (j + 1) & mask;
            if (!used[j]) break;
            int home = hash(slots[j]) & mask;
            // slots[j] may move into the gap unless its home lies cyclically in (gap, j]
            boolean stays = gap <= j ? (home > gap && home <= j) : (home > gap || home <= j);
            if (!stays) {
                slots[gap] = slots[j];
                gap = j;
            }
        }
        used[gap] = false;
        size--;
        return true;
    }

    public void clear() {
        java.util.Arrays.fill(used, false);
        size = 0;
    }

    private void rehash(int capacity) {
        long[] oldSlots = slots;
        boolean[] oldUsed = used;
        slots = new long[capacity];
        used = new boolean[capacity];
        int mask = capacity - 1;
        for (int k = 0; k < oldSlots.length; k++) {
            if (!oldUsed[k]) continue;
            int i = hash(oldSlots[k]) & mask;
            while (used[i]) i = (i + 1) & mask;
            used[i] = true;
            slots[i] = oldSlots[k];
        }
    }
}
