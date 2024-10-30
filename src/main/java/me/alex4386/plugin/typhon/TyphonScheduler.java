package me.alex4386.plugin.typhon;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.*;

public class TyphonScheduler {
    public static Map<Integer, Object> tasks = new HashMap<>();
    private static int taskId = 1;

    private static Map<Chunk, Integer> chunkTasks = new HashMap<>();
    private static Map<Chunk, Queue<Map.Entry<Block, Material>>> blockChanges = new HashMap<>();
    private static Map<Chunk, Runnable> chunkRunOnceTasks = new HashMap<>();

    private static Queue<Runnable> globalRunOnceTasks = new LinkedList<>();
    private static int globalRunOnceTaskId = -1;

    private static void runBlockUpdateTask(Chunk chunk) {
        Queue<Map.Entry<Block, Material>> blockChangesQueue = blockChanges.get(chunk);
        if (blockChangesQueue != null) {
            while (!blockChangesQueue.isEmpty()) {
                Map.Entry<Block, Material> blockChange = blockChangesQueue.poll();
                blockChange.getKey().setType(blockChange.getValue());
            }
        }
    }

    private static void setupBlockUpdateTask(Chunk chunk) {
        if (!blockChanges.containsKey(chunk)) {
            blockChanges.put(chunk, new LinkedList<>());
        }
    }

    public static void setBlockType(Block block, Material material) {
        if (TyphonMultithreading.isPaperMultithread) {
            Chunk chunk = block.getChunk();

            setupBlockUpdateTask(chunk);
            blockChanges.get(chunk).add(new AbstractMap.SimpleEntry<>(block, material));
        } else {
            block.setType(material);
        }
    }

    private static void setupGlobalTask() {
        if (globalRunOnceTaskId > 0) return;
        globalRunOnceTaskId = registerGlobalTask(() -> {
            while (!globalRunOnceTasks.isEmpty()) {
                globalRunOnceTasks.poll().run();
            }
        }, 1L);
    }

    public static void runOnce(Runnable runnable) {
        if (TyphonMultithreading.isFolia()) {
            setupGlobalTask();
            globalRunOnceTasks.add(runnable);
        } else {
            runnable.run();
        }
    }

    private static void setupChunkTask(Chunk chunk) {
        if (!chunkTasks.containsKey(chunk)) {
            chunkTasks.put(chunk, registerTask(chunk, () -> {
                runBlockUpdateTask(chunk);
            }, 1L));
        }
    }

    public static void runOnce(Chunk chunk, Runnable runnable) {
        if (TyphonMultithreading.isFolia()) {
            setupChunkTask(chunk);
            chunkRunOnceTasks.put(chunk, runnable);
        } else {
            runnable.run();
        }
    }

    public static void runOnce(Block block, Runnable runnable) {
        if (TyphonMultithreading.isFolia()) {
            block.getWorld().getChunkAtAsync(block).thenAccept(chunk -> {
                runOnce(chunk, runnable);
            });
        } else {
            runnable.run();
        }
    }

    private static synchronized int getNewTaskId() {
        while (tasks.containsKey(taskId)) {
            taskId++;
        }
        return taskId;
    }

    public static int registerGlobalTask(Runnable task, long interval) {
        return registerTask(null, task, interval);
    }

    public static int registerTask(Chunk chunk, Runnable task, long interval) {
        int targetId;

        // check if interval is less than 1, handle it.
        if (interval < 1) {
            interval = 1;
        }

        // check if this is a paper
        if (TyphonMultithreading.isPaperMultithread) {
            ScheduledTask scheduledTask;

            if (chunk == null) {
                scheduledTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                        TyphonPlugin.plugin,
                        _a -> task.run(),
                        1L,
                        interval
                );
            } else {
                scheduledTask = Bukkit.getRegionScheduler().runAtFixedRate(
                        TyphonPlugin.plugin,
                        chunk.getWorld(),
                        chunk.getX(),
                        chunk.getZ(),
                        _a -> task.run(),
                        1L,
                        interval
                );
            }

            targetId = TyphonScheduler.getNewTaskId();
            tasks.put(targetId, scheduledTask);
        } else {
            // THIS IS NOT A DEPRECATION. THIS IS A FALLBACK FOR NON-PAPER SERVERS
            targetId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                    TyphonPlugin.plugin,
                    task,
                    0L,
                    interval
            );

            if (targetId > taskId) taskId = targetId;
            tasks.put(taskId, task);
        }

        return taskId;
    }

    public static void unregisterTask(int taskId) {
        if (tasks.containsKey(taskId)) {
            Object value = tasks.get(taskId);
            if (tasks.get(taskId) instanceof ScheduledTask) {
                ((ScheduledTask) tasks.get(taskId)).cancel();
            } else {
                Bukkit.getScheduler().cancelTask(taskId);
            }
            tasks.remove(taskId);
        }
    }


    // ========== delayed task ==============
    public static void runDelayed(Chunk chunk, Runnable task, long delay) {
        if (delay <= 0) {
            TyphonScheduler.run(chunk, task);
        }

        if (TyphonMultithreading.isPaperMultithread) {
            if (chunk == null) {
                Bukkit.getGlobalRegionScheduler().runDelayed(TyphonPlugin.plugin, _a -> task.run(), delay);
            } else {
                Bukkit.getRegionScheduler().runDelayed(TyphonPlugin.plugin, chunk.getWorld(), chunk.getX(), chunk.getZ(), _a -> task.run(), delay);
            }
        } else {
            Bukkit.getScheduler().runTaskLater(TyphonPlugin.plugin, task, delay);
        }
    }

    // =========== run task ============
    public static void run(Chunk chunk, Runnable task) {
        if (TyphonMultithreading.isPaperMultithread) {
            if (chunk == null) {
                Bukkit.getGlobalRegionScheduler().run(TyphonPlugin.plugin, _a -> task.run());
            } else {
                Bukkit.getRegionScheduler().run(TyphonPlugin.plugin, chunk.getWorld(), chunk.getX(), chunk.getZ(), _a -> task.run());
            }
        } else {
            Bukkit.getScheduler().runTask(TyphonPlugin.plugin, task);
        }
    }



}
