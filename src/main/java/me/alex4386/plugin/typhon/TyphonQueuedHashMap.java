package me.alex4386.plugin.typhon;

import org.bukkit.block.Block;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Function;

public class TyphonQueuedHashMap<K,V> {
    private final int maxSize;
    private final HashMap<K, TyphonCache<V>> map;
    private final Queue<K> queue;

    private Function<K, K> keyPreprocessor;
    private Function<Map.Entry<K, TyphonCache<V>>, Object> onRemove;

    private boolean useCache = true;

    public TyphonQueuedHashMap(int maxSize) {
        this(maxSize, null);
    }

    public TyphonQueuedHashMap(int maxSize, Function<K, K> keyPreprocessor) {
        this(maxSize, keyPreprocessor, null);
    }

    public TyphonQueuedHashMap(int maxSize, Function<K, K> keyPreprocessor, Function<Map.Entry<K, TyphonCache<V>>, Object> onRemove) {
        this(maxSize, keyPreprocessor, onRemove, true);
    }

    public TyphonQueuedHashMap(int maxSize, Function<K, K> keyPreprocessor, Function<Map.Entry<K, TyphonCache<V>>, Object> onRemove, boolean useCache) {
        this.maxSize = maxSize;
        this.map = new HashMap<>();
        this.queue = new LinkedList<>();

        this.keyPreprocessor = keyPreprocessor;
        this.onRemove = onRemove;
        this.useCache = useCache;
    }

    public static Block getTwoDimensionalBlock(Block block) {
        return block.getRelative(0, -block.getY(), 0);
    }

    public boolean isUsingCache() {
        return this.useCache;
    }

    public void setUseCache(boolean useCache) {
        this.useCache = useCache;
    }

    private void removeQueueUntil(K key) {
        K realKey = this.getRealKey(key);
        while (!queue.isEmpty()) {
            K oldestKey = queue.poll();
            if (oldestKey == null) break;
            if (oldestKey.equals(realKey)) {
                break;
            }
            map.remove(oldestKey);
        }
    }

    public K getRealKey(K key) {
        return this.getKeyPreprocessor().apply(key);
    }

    public void put(K key, V value) {
        K realKey = this.getRealKey(key);

        if (map.containsKey(realKey)) {
            queue.remove(realKey);
        } else if (map.size() >= maxSize) {
            K oldestKey = queue.poll();
            if (oldestKey != null) {
                this.removeByRealKey(oldestKey);
            }
        }

        map.put(realKey, new TyphonCache<V>(value));
        queue.offer(realKey);
    }

    public int size() {
        return map.size();
    }


    public void clear() {
        map.clear();
        queue.clear();
    }

    public Set<K> keySet() {
        return map.keySet();
    }

    public Collection<V> values() {
        Collection<V> values = new ArrayList<>();
        for (TyphonCache<V> cache : this.valueCaches()) {
            values.add(cache.getTarget());
        }
        return values;
    }

    public Collection<TyphonCache<V>> valueCaches() {
        return map.values();
    }

    private Function<K, K> getKeyPreprocessor() {
        if (this.keyPreprocessor == null) {
            return (K key) -> {
                return key;
            };
        } else {
            return this.keyPreprocessor;
        }
    }

    public TyphonCache<V> getCacheIncludingExpired(K key) {
        return map.get(this.getRealKey(key));
    }

    public TyphonCache<V> getCache(K key) {
        TyphonCache<V> cache = this.getCacheIncludingExpired(key);
        if (cache == null) {
            return null;
        }

        if (this.useCache && cache.isExpired()) {
            this.removeQueueUntil(key);
            return null;
        }

        return cache;
    }

    public V getIncludingExpired(K key) {
        TyphonCache<V> cache = this.getCacheIncludingExpired(key);
        if (cache == null) {
            return null;
        }
        return cache.getTarget();
    }

    public V get(K key) {
        TyphonCache<V> cache = this.getCache(key);
        if (cache == null) {
            return null;
        }
        return cache.getTarget();
    }

    public V remove(K key) {
        return this.removeByRealKey(this.getRealKey(key));
    }

    private V removeByRealKey(K key) {
        queue.remove(key);

        TyphonCache<V> cache = map.remove(key);
        if (cache == null) {
            return null;
        }

        Map.Entry<K, TyphonCache<V>> entry = new AbstractMap.SimpleEntry<>(key, cache);
        if (this.onRemove != null) {
            try {
                this.onRemove.apply(entry);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return cache.getTarget();
    }
}
