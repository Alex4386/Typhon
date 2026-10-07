package me.alex4386.typhon.engine.math;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Random;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** {@link LongMinQueue} and {@link LongHashSet} behave like their boxed counterparts. {@link LongMinQueue} polls in exactly the order of {@code TreeSet<Long>.pollFirst()}, duplicates included. */
class LongMinQueueTest {
    @Test
    void matchesTreeSetOrder() {
        Random random = new Random(7);
        LongMinQueue queue = new LongMinQueue();
        TreeSet<Long> reference = new TreeSet<>();
        for (int round = 0; round < 200_000; round++) {
            if (reference.isEmpty() || random.nextInt(3) > 0) {
                // packed column coordinates around the origin, with many repeats
                int x = random.nextInt(81) - 40;
                int z = random.nextInt(81) - 40;
                long k = ((long) x << 32) ^ (z & 0xffffffffL);
                queue.add(k);
                reference.add(k);
            } else {
                assertEquals(reference.pollFirst(), queue.pollFirst());
            }
            assertEquals(reference.size(), queue.size());
        }
        while (!reference.isEmpty()) assertEquals(reference.pollFirst(), queue.pollFirst());
        assertEquals(0, queue.size());
    }

    @Test
    void sortedArrayAndClear() {
        LongMinQueue queue = new LongMinQueue();
        for (long v : new long[] {5, -3, 9, 5, 0, -3}) queue.add(v);
        assertArrayEquals(new long[] {-3, 0, 5, 9}, queue.toSortedArray());
        queue.clear();
        assertEquals(0, queue.size());
        queue.add(2);
        assertEquals(2, queue.pollFirst());
    }

    @Test
    void hashSetMatchesHashSet() {
        Random random = new Random(11);
        LongHashSet set = new LongHashSet();
        HashSet<Long> reference = new HashSet<>();
        for (int round = 0; round < 300_000; round++) {
            long k = ((long) (random.nextInt(61) - 30) << 32) ^ ((random.nextInt(61) - 30) & 0xffffffffL);
            switch (random.nextInt(3)) {
                case 0 -> assertEquals(reference.add(k), set.add(k));
                case 1 -> assertEquals(reference.remove(k), set.remove(k));
                default -> assertEquals(reference.contains(k), set.contains(k));
            }
            assertEquals(reference.size(), set.size());
        }
    }
}
