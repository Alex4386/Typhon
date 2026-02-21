package me.alex4386.plugin.typhon;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class TyphonBlockQueue {

    private final Map<Chunk, Queue<Map.Entry<Block, Material>>> queues = new HashMap<>();
    private final Map<Chunk, Queue<Map.Entry<Block, Material>>> immediateQueues = new HashMap<>();
    private final Map<Block, Consumer<Block>> postUpdater = new HashMap<>();

    private int scheduleId = -1;

    private long timeBudgetMs = 50;
    private int checkInterval = 1000;

    // Rate tracking (processedCount written from region threads, read globally)
    private final AtomicLong processedCount = new AtomicLong(0);
    private long lastCalcTime = 0;
    private volatile double processedPerSecond = 0;

    private Consumer<Set<Chunk>> dirtyChunkListener = null;

    public TyphonBlockQueue() {}

    public TyphonBlockQueue(long timeBudgetMs) {
        this.timeBudgetMs = timeBudgetMs;
    }

    // ── Configuration ──

    public void setTimeBudgetMs(long timeBudgetMs) {
        this.timeBudgetMs = timeBudgetMs;
    }

    public long getTimeBudgetMs() {
        return timeBudgetMs;
    }

    public void setDirtyChunkListener(Consumer<Set<Chunk>> listener) {
        this.dirtyChunkListener = listener;
    }

    // ── Enqueue ──

    public void add(Block block, Material material) {
        this.add(block, material, null);
    }

    public void add(Block block, Material material, Consumer<Block> callback) {
        ensureStarted();
        Chunk chunk = block.getChunk();
        queues.computeIfAbsent(chunk, k -> new LinkedList<>())
                .add(new AbstractMap.SimpleEntry<>(block, material));
        if (callback != null) postUpdater.put(block, callback);
    }

    public void addImmediate(Block block, Material material) {
        ensureStarted();
        Chunk chunk = block.getChunk();
        immediateQueues.computeIfAbsent(chunk, k -> new LinkedList<>())
                .add(new AbstractMap.SimpleEntry<>(block, material));
    }

    // ── Query ──

    public long unprocessedCount() {
        long count = 0;
        for (Queue<Map.Entry<Block, Material>> queue : queues.values()) {
            count += queue.size();
        }
        return count;
    }

    public double getProcessedPerSecond() {
        return processedPerSecond;
    }

    // ── Lifecycle ──

    private void ensureStarted() {
        if (scheduleId == -1) {
            scheduleId = TyphonScheduler.registerGlobalTask(this::tick, 1L);
        }
    }

    public void shutdown() {
        if (scheduleId != -1) {
            TyphonScheduler.unregisterTask(scheduleId);
            scheduleId = -1;
        }
        queues.clear();
        immediateQueues.clear();
        postUpdater.clear();
    }

    // ── Tick ──

    private void tick() {
        Set<Chunk> dirtyChunks = new HashSet<>();

        for (Map.Entry<Chunk, Queue<Map.Entry<Block, Material>>> entry : immediateQueues.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            Queue<Map.Entry<Block, Material>> queue = entry.getValue();
            TyphonScheduler.run(entry.getKey(), () -> processAll(queue));
            dirtyChunks.add(entry.getKey());
        }

        for (Map.Entry<Chunk, Queue<Map.Entry<Block, Material>>> entry : queues.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            Queue<Map.Entry<Block, Material>> queue = entry.getValue();
            TyphonScheduler.run(entry.getKey(), () -> processWithinBudget(queue));
            dirtyChunks.add(entry.getKey());
        }

        // run post-update callbacks
        if (!postUpdater.isEmpty()) {
            Iterator<Map.Entry<Block, Consumer<Block>>> it = postUpdater.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Block, Consumer<Block>> cbEntry = it.next();
                cbEntry.getValue().accept(cbEntry.getKey());
                it.remove();
            }
        }

        updateRate();

        if (dirtyChunkListener != null && !dirtyChunks.isEmpty()) {
            dirtyChunkListener.accept(dirtyChunks);
        }
    }

    // ── Internal processing ──

    private void processAll(Queue<Map.Entry<Block, Material>> queue) {
        long count = 0;

        Map.Entry<Block, Material> entry;
        while ((entry = queue.poll()) != null) {
            TyphonBlocks.setBlockType(entry.getKey(), entry.getValue());
            count++;
        }

        processedCount.addAndGet(count);
    }

    private void processWithinBudget(Queue<Map.Entry<Block, Material>> queue) {
        long startTime = System.currentTimeMillis();
        long count = 0;

        Map.Entry<Block, Material> entry;
        while ((entry = queue.poll()) != null) {
            TyphonBlocks.setBlockType(entry.getKey(), entry.getValue());

            count++;
            if (count % checkInterval == 0) {
                if (System.currentTimeMillis() - startTime >= timeBudgetMs) break;
            }
        }

        processedCount.addAndGet(count);
    }

    // ── Rate tracking ──

    private void updateRate() {
        long now = System.currentTimeMillis();

        if (lastCalcTime == 0) {
            lastCalcTime = now;
            return;
        }

        long elapsedMs = now - lastCalcTime;
        if (elapsedMs >= 1000) {
            long counted = processedCount.getAndSet(0);
            processedPerSecond = counted / (elapsedMs / 1000.0);
            lastCalcTime = now;
        }
    }
}
