package me.alex4386.typhon.engine.save;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** {@link SaveStore} kept in memory, for tests, state hashing and quick snapshots. */
public final class InMemorySaveStore implements SaveStore {
    private final TreeMap<String, byte[]> files = new TreeMap<>();
    private long writes;

    @Override
    public byte[] read(String path) {
        byte[] data = files.get(path);
        return data == null ? null : data.clone();
    }

    @Override
    public boolean write(String path, byte[] data) {
        byte[] existing = files.get(path);
        if (existing != null && Arrays.equals(existing, data)) return false;
        files.put(path, data.clone());
        writes++;
        return true;
    }

    @Override
    public void append(String path, byte[] data) {
        byte[] existing = files.getOrDefault(path, new byte[0]);
        byte[] joined = Arrays.copyOf(existing, existing.length + data.length);
        System.arraycopy(data, 0, joined, existing.length, data.length);
        files.put(path, joined);
    }

    @Override
    public List<String> list(String prefix) {
        List<String> out = new ArrayList<>();
        for (String path : files.tailMap(prefix, true).keySet()) {
            if (!path.startsWith(prefix)) break;
            out.add(path);
        }
        return out;
    }

    @Override
    public void delete(String path) {
        files.remove(path);
    }

    /** Number of writes that actually changed content (for incremental-save checks). */
    public long writeCount() {
        return writes;
    }

    /** Read-only view of every file, sorted by path. */
    public Map<String, byte[]> files() {
        return java.util.Collections.unmodifiableMap(files);
    }
}
