package me.alex4386.plugin.typhon.volcano;

import me.alex4386.plugin.typhon.TyphonScheduler;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;

import org.bukkit.Chunk;

import java.util.*;

public class VolcanoChunkLoader {
    Volcano volcano;

    private int scheduleId = -1;

    // chunks queued to be force-loaded on next tick
    private final Set<Chunk> pendingAdd = new HashSet<>();

    // currently force-loaded chunks with remaining ticks
    // -1 means indefinite (until explicit release or shutdown)
    private final Map<Chunk, Integer> loadedChunks = new HashMap<>();

    private static final int TICK_INTERVAL = 20; // run consumer every second
    private static final int DEFAULT_DURATION = 20 * 60; // 60 seconds in ticks

    public VolcanoChunkLoader(Volcano volcano) {
        this.volcano = volcano;
    }

    public void add(Chunk chunk) {
        addFor(chunk, DEFAULT_DURATION);
    }

    public void addFor(Chunk chunk, int durationTicks) {
        synchronized (pendingAdd) {
            pendingAdd.add(chunk);
        }
        synchronized (loadedChunks) {
            Integer existing = loadedChunks.get(chunk);
            if (existing == null || (existing != -1 && durationTicks > existing)) {
                loadedChunks.put(chunk, durationTicks);
            }
        }
    }

    public void addIndefinite(Chunk chunk) {
        synchronized (pendingAdd) {
            pendingAdd.add(chunk);
        }
        synchronized (loadedChunks) {
            loadedChunks.put(chunk, -1);
        }
    }

    public void release(Chunk chunk) {
        synchronized (loadedChunks) {
            loadedChunks.remove(chunk);
        }
        chunk.setForceLoaded(false);
    }

    public int getLoadedChunkCount() {
        synchronized (loadedChunks) {
            return loadedChunks.size();
        }
    }

    private void tick() {
        // process pending adds
        Set<Chunk> toLoad;
        synchronized (pendingAdd) {
            if (pendingAdd.isEmpty()) {
                toLoad = Collections.emptySet();
            } else {
                toLoad = new HashSet<>(pendingAdd);
                pendingAdd.clear();
            }
        }

        for (Chunk chunk : toLoad) {
            if (!chunk.isForceLoaded()) {
                chunk.setForceLoaded(true);
            }
        }

        // tick down durations and release expired
        List<Chunk> expired = new ArrayList<>();
        synchronized (loadedChunks) {
            Iterator<Map.Entry<Chunk, Integer>> it = loadedChunks.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Chunk, Integer> entry = it.next();
                int remaining = entry.getValue();
                if (remaining == -1) continue; // indefinite

                remaining -= TICK_INTERVAL;
                if (remaining <= 0) {
                    expired.add(entry.getKey());
                    it.remove();
                } else {
                    entry.setValue(remaining);
                }
            }
        }

        for (Chunk chunk : expired) {
            chunk.setForceLoaded(false);
        }
    }

    public void initialize() {
        if (scheduleId != -1) return;

        scheduleId = TyphonScheduler.registerGlobalTask(this::tick, TICK_INTERVAL);
    }

    public void shutdown() {
        if (scheduleId != -1) {
            TyphonScheduler.unregisterTask(scheduleId);
            scheduleId = -1;
        }

        // release all force-loaded chunks
        synchronized (loadedChunks) {
            for (Chunk chunk : loadedChunks.keySet()) {
                chunk.setForceLoaded(false);
            }
            loadedChunks.clear();
        }

        synchronized (pendingAdd) {
            pendingAdd.clear();
        }
    }
}
