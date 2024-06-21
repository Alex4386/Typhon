package me.alex4386.plugin.typhon;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import java.util.HashMap;
import java.util.Map;

public class TyphonScheduler {
    public static Map<Integer, Object> tasks = new HashMap<>();
    private static int taskId = 1;

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
            targetId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                    TyphonPlugin.plugin,
                    task,
                    0L,
                    interval
            );

            if (targetId > taskId) taskId = targetId;
        }

        tasks.put(taskId, task);
        return taskId;
    }

    public static void unregisterTask(int taskId) {
        if (tasks.containsKey(taskId)) {
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
