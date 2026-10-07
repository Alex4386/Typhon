package me.alex4386.typhon.engine.math;

import java.util.Arrays;

/**
 * A set of {@code long}s polled smallest first: the same order as {@code TreeSet<Long>.pollFirst()}, without
 * boxing or tree nodes. A binary min-heap holds the order; a {@link LongHashSet} keeps each value queued at
 * most once.
 */
public final class LongMinQueue {
    private long[] heap = new long[64];
    private int size;
    private final LongHashSet members = new LongHashSet();

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Queues {@code v} unless it is already queued. */
    public void add(long v) {
        if (!members.add(v)) return;
        if (size == heap.length) heap = Arrays.copyOf(heap, size * 2);
        int i = size++;
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            long p = heap[parent];
            if (p <= v) break;
            heap[i] = p;
            i = parent;
        }
        heap[i] = v;
    }

    /** Removes and returns the smallest queued value; the queue must not be empty. */
    public long pollFirst() {
        long min = heap[0];
        long last = heap[--size];
        int i = 0;
        int half = size >>> 1;
        while (i < half) {
            int child = 2 * i + 1;
            long cv = heap[child];
            int right = child + 1;
            if (right < size && heap[right] < cv) cv = heap[child = right];
            if (last <= cv) break;
            heap[i] = cv;
            i = child;
        }
        if (size > 0) heap[i] = last;
        members.remove(min);
        return min;
    }

    public void clear() {
        size = 0;
        members.clear();
    }

    /** The queued values in ascending order (the order they would be polled). */
    public long[] toSortedArray() {
        long[] values = Arrays.copyOf(heap, size);
        Arrays.sort(values);
        return values;
    }
}
