package me.alex4386.plugin.typhon.volcano.utils;

import me.alex4386.plugin.typhon.TyphonNMSUtils;
import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.volcano.Volcano;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class VolcanoConstruction {
    Volcano volcano;

    public static int blockUpdatesPerSecondsInNMS = -1;
    public static int blockUpdatesPerSecondsInBukkit = -1;

    public VolcanoConstruction(Volcano volcano) {
        this.volcano = volcano;
    }

    // estimate how much would it take to finish this operation
    public static double estimateBlockUpdatesInSeconds(long blockCount, boolean useNMS) {
        if (useNMS && blockUpdatesPerSecondsInNMS < 0) {
            return (double)blockCount / blockUpdatesPerSecondsInNMS;
        } else if (!useNMS && blockUpdatesPerSecondsInBukkit < 0) {
            return (double)blockCount / blockUpdatesPerSecondsInBukkit;
        } else {
            return -1;
        }
    }

    public static int estimateConstructionProcessedBlocks(VolcanoConstructionData data) {
        int processingBlocks = data.getConstructionData().size();
        if (data instanceof VolcanoConstructionRaiseData) {
            processingBlocks += ((VolcanoConstructionRaiseData) data).raiseAmount;
        }

        return processingBlocks;
    }

    public static double estimateBlockUpdatesInSeconds(VolcanoConstructionData data, boolean useNMS) {
        return estimateBlockUpdatesInSeconds(estimateConstructionProcessedBlocks(data), useNMS);
    }

    // synchronous function that build volcano construction
    public static<T extends VolcanoConstructionData> void runConstruction(T data, boolean useNMS, Runnable callback) {
        Map<Block, Block> constructionData = data.getConstructionData();

        Bukkit.getScheduler().runTask(TyphonPlugin.plugin, (Runnable) () -> {

            // time calculation
            long blockUpdateInitTime = System.currentTimeMillis();
            int processingBlocks = constructionData.size();

            int currentBlock = 0;

            for (Map.Entry<Block, Block> entry : constructionData.entrySet()) {
                Block sourceBlock = entry.getKey();
                Block destinationBlock = entry.getValue();
                boolean shouldUpdate = destinationBlock.getY() % 16 == 0 && destinationBlock.getX() % 16 == 0 && destinationBlock.getZ() % 16 == 0;

                if (useNMS) {
                    TyphonNMSUtils.moveBlock(sourceBlock, destinationBlock, false, shouldUpdate);
                } else {
                    BlockData blockData = sourceBlock.getBlockData();
                    destinationBlock.setType(sourceBlock.getType());
                    destinationBlock.setBlockData(blockData, true);
                }

                currentBlock++;
            }

            // raise data handler
            if (data instanceof VolcanoConstructionRaiseData) {
                VolcanoConstructionRaiseData raiseData = (VolcanoConstructionRaiseData)data;

                for (int i = 0; i < raiseData.raiseAmount; i = raiseData.raiseAmount < 0 ? --i : ++i) {
                    Block gapBlock = raiseData.baseBlock.getRelative(0, i, 0);

                    boolean shouldUpdate = gapBlock.getY() % 16 == 0 && gapBlock.getX() % 16 == 0 && gapBlock.getZ() % 16 == 0;

                    if (useNMS) {
                        TyphonNMSUtils.setBlockMaterial(gapBlock, raiseData.replacementMaterial, false, shouldUpdate);
                    } else {
                        gapBlock.setType(raiseData.replacementMaterial);
                    }
                }
                processingBlocks += raiseData.raiseAmount;
            }

            // time calculation
            long blockUpdateEndTime = System.currentTimeMillis();
            long processingInMilliseconds = blockUpdateEndTime - blockUpdateInitTime;

            double partOfSeconds = ( (double) processingInMilliseconds / 1000 );
            int thisBlockUpdatePerSecond;

            // has accuracy issue if it is 0.
            if (partOfSeconds != 0) {
                if (useNMS) {
                    blockUpdatesPerSecondsInNMS = (int)(processingBlocks / partOfSeconds);
                    thisBlockUpdatePerSecond = blockUpdatesPerSecondsInNMS;
                } else {
                    blockUpdatesPerSecondsInBukkit = (int)(processingBlocks / partOfSeconds);
                    thisBlockUpdatePerSecond = blockUpdatesPerSecondsInBukkit;
                }
            } else {
                thisBlockUpdatePerSecond = processingBlocks;
            }

            if (callback != null) {
                int threadWait = (processingBlocks / thisBlockUpdatePerSecond) / 2;

                Thread createNextThread = new Thread((Runnable) () -> {
                    callback.run();
                });

                ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1);

                executorService.schedule(() -> {
                    createNextThread.start();
                }, threadWait, TimeUnit.SECONDS);
            }
        });
    }

    public static void runConstruction(VolcanoConstructionData data, boolean useNMS) {
        runConstruction(data, useNMS, null);
    }

    public static void runConstruction(VolcanoConstructionData data) {
        runConstruction(data, false);
    }


    public static <T extends VolcanoConstructionData> void runConstructions(Iterator<T> iterator, boolean useNMS, Runnable callback, Runnable iterationCallback) {
        if (iterator.hasNext()) {
            VolcanoConstructionData nextData = iterator.next();
            runConstruction(nextData, useNMS, (Runnable) () -> {
                if (iterationCallback != null) {
                    iterationCallback.run();
                }
                if (nextData != null) {
                    // ah screw it
                    Thread createNextThread = new Thread((Runnable) () -> {
                        runConstructions(iterator, useNMS, callback, iterationCallback);
                    });

                    createNextThread.start();
                }
            });
        } else {
            if (callback != null) {
                callback.run();
            }
        }
    }

    public static void runConstructions(Iterable<VolcanoConstructionData> data, boolean useNMS, Runnable callback, Runnable iterationCallback) {
        Iterator<VolcanoConstructionData> iterator = data.iterator();
        runConstructions(iterator, useNMS, callback, iterationCallback);
    }

    public static void runConstructions(Iterable<VolcanoConstructionData> data, boolean useNMS, Runnable callback) {
        runConstructions(data, useNMS, callback, null);
    }

    public static void runConstructions(Iterable<VolcanoConstructionData> data, boolean useNMS) {
        runConstructions(data, useNMS, null);
    }

    public static void runConstructions(Iterable<VolcanoConstructionData> data) {
        runConstructions(data, false);
    }
}