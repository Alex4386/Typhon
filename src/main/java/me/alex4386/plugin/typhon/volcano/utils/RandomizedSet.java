package me.alex4386.plugin.typhon.volcano.utils;

import org.jspecify.annotations.NonNull;

import java.util.*;

/**
 * A Set implementation that supports:
 * - add: O(1) average
 * - remove: O(1) average
 * - contains: O(1) average
 * - pollRandom: O(1) average
 *
 * Backed by an ArrayList + HashMap (item -> index).
 */
public class RandomizedSet<T> extends AbstractSet<T> {
    private final List<T> items = new ArrayList<>();
    private final Map<T, Integer> indexByItem = new HashMap<>();
    private final Random random;

    public RandomizedSet() {
        this(new Random());
    }

    public RandomizedSet(Random random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public boolean add(T item) {
        if (indexByItem.containsKey(item)) return false;

        indexByItem.put(item, items.size());
        items.add(item);
        return true;
    }

    @Override
    public boolean remove(Object obj) {
        Integer index = indexByItem.get(obj);
        if (index == null) return false;

        removeAt(index);
        return true;
    }

    @Override
    public boolean contains(Object obj) {
        return indexByItem.containsKey(obj);
    }

    @Override
    public int size() {
        return items.size();
    }

    @Override
    public void clear() {
        items.clear();
        indexByItem.clear();
    }

    /**
     * Removes and returns a uniformly random element, or null if empty.
     */
    public T pollRandom() {
        if (items.isEmpty()) return null;
        int randomIndex = random.nextInt(items.size());
        return removeAt(randomIndex);
    }

    private T removeAt(int index) {
        int lastIndex = items.size() - 1;

        T removed = items.get(index);
        T last = items.get(lastIndex);

        // Move last into removed slot (if not already last), then pop last.
        items.set(index, last);
        items.remove(lastIndex);

        // Remove mapping for removed item.
        indexByItem.remove(removed);

        // Fix moved item's index if it actually moved.
        if (index != lastIndex) {
            indexByItem.put(last, index);
        }

        return removed;
    }

    @Override
    public @NonNull Iterator<T> iterator() {
        return new Iterator<>() {
            int cursor = 0;
            int lastReturnedIndex = -1;

            @Override
            public boolean hasNext() {
                return cursor < items.size();
            }

            @Override
            public T next() {
                if (!hasNext()) throw new NoSuchElementException();
                lastReturnedIndex = cursor++;
                return items.get(lastReturnedIndex);
            }

            @Override
            public void remove() {
                if (lastReturnedIndex < 0) throw new IllegalStateException();

                // Remove the last returned element in O(1) average.
                RandomizedSet.this.removeAt(lastReturnedIndex);

                // Cursor should point to the element that shifted into lastReturnedIndex.
                cursor = lastReturnedIndex;
                lastReturnedIndex = -1;
            }
        };
    }
}