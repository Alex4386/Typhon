package me.alex4386.plugin.typhon.volcano.utils;

import me.alex4386.plugin.typhon.TyphonNMSUtils;
import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.util.*;

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
        processingBlocks += data.getConstructionMaterialUpdateData().size();
        if (data instanceof VolcanoConstructionRaiseData) {
            processingBlocks += ((VolcanoConstructionRaiseData) data).raiseAmount;
        }

        return processingBlocks;
    }

    public static double estimateBlockUpdatesInSeconds(VolcanoConstructionData data, boolean useNMS) {
        return estimateBlockUpdatesInSeconds(estimateConstructionProcessedBlocks(data), useNMS);
    }

    // synchronous function that build volcano construction
    public static<T extends VolcanoConstructionData> void runConstruction(T data, boolean useNMS) {
        Map<Block, Block> constructionData = data.getConstructionData();
        Map<Block, Material> constructionMaterialData = data.getConstructionMaterialUpdateData();

        // time calculation
        long blockUpdateInitTime = System.currentTimeMillis();
        int processingBlocks = constructionData.size() + constructionMaterialData.size();

        List<Block> entrySet = new ArrayList<>(constructionData.keySet());

        for (Block key : entrySet) {
            Block sourceBlock = key;
            Block destinationBlock = constructionData.get(key);
            boolean shouldUpdate = destinationBlock.getY() % 16 == 0 && destinationBlock.getX() % 16 == 0 && destinationBlock.getZ() % 16 == 0;

            if (!TyphonUtils.isMaterialRocklikes(sourceBlock.getType())) {
                if (!sourceBlock.getType().isAir()) {
                    continue;
                }
            }

            Material replacementMaterial = Material.AIR;
            if (data instanceof VolcanoConstructionRaiseData) {
                VolcanoConstructionRaiseData raiseData = (VolcanoConstructionRaiseData) data;
                Block block = raiseData.baseBlock;
                while (block.getType() == Material.LAVA) {
                    block = block.getRelative(0,1,0);
                }
                if (sourceBlock.getY() <= raiseData.raiseAmount + block.getY()) {
                    replacementMaterial = Material.LAVA;
                }
            }

            if (useNMS) {
                TyphonNMSUtils.moveBlock(sourceBlock, destinationBlock, replacementMaterial, false, shouldUpdate);
                if (replacementMaterial == Material.LAVA) {
                    TyphonNMSUtils.setBlockMaterial(sourceBlock, Material.LAVA);
                }
            } else {
                BlockData blockData = sourceBlock.getBlockData();
                destinationBlock.setType(sourceBlock.getType());
                destinationBlock.setBlockData(blockData, true);
                sourceBlock.setType(replacementMaterial);
            }
        }

        entrySet = new ArrayList<>(constructionMaterialData.keySet());

        for (Block key : entrySet) {
            Block destinationBlock = key;
            Material material = constructionMaterialData.get(key);

            boolean shouldUpdate = destinationBlock.getY() % 16 == 0 && destinationBlock.getX() % 16 == 0 && destinationBlock.getZ() % 16 == 0;

            if (useNMS) {
                TyphonNMSUtils.setBlockMaterial(destinationBlock, material, false, shouldUpdate);
            } else {
                destinationBlock.setType(material);
            }
        }

        // raise data handler
        /*
        if (data instanceof VolcanoConstructionRaiseData) {
            VolcanoConstructionRaiseData raiseData = (VolcanoConstructionRaiseData) data;

            if (raiseData.raiseAmount > 0) {

            } else {
                for (int i = 0; i > raiseData.raiseAmount; i--) {
                    Block updateBlock = raiseData.baseBlock.getRelative(0, i, 0);
                    boolean shouldUpdate = updateBlock.getY() % 16 == 0 && updateBlock.getX() % 16 == 0 && updateBlock.getZ() % 16 == 0;

                }
            }

            for (int i = 0; i != raiseData.raiseAmount; i = raiseData.raiseAmount < 0 ? --i : ++i) {
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
        */

        // time calculation
        long blockUpdateEndTime = System.currentTimeMillis();
        long processingInMilliseconds = blockUpdateEndTime - blockUpdateInitTime;

        double partOfSeconds = ((double) processingInMilliseconds / 1000);

        // has accuracy issue if it is 0.
        if (partOfSeconds != 0) {
            if (useNMS) {
                blockUpdatesPerSecondsInNMS = (int) (processingBlocks / partOfSeconds);
            } else {
                blockUpdatesPerSecondsInBukkit = (int) (processingBlocks / partOfSeconds);
            }
        }
    }

    public static<T extends VolcanoConstructionData> void runConstructionAsync(T data, boolean useNMS, Runnable callback) {
        Bukkit.getScheduler().runTask(TyphonPlugin.plugin, (Runnable) () -> {
            runConstruction(data, useNMS);
            if (callback != null) {
                callback.run();
            }
        });
    }

    public static <T extends VolcanoConstructionData> void runConstructions(VolcanoConstructionStatus status, Iterator<T> iterator, boolean useNMS, Runnable callback, Runnable iterationCallback) {
        status.hasSubStage = false;
        if (iterator.hasNext()) {
            T nextData = iterator.next();
            runConstructionAsync(nextData, useNMS, (Runnable) () -> {
                if (iterationCallback != null) {
                    iterationCallback.run();
                }
                if (nextData != null) {
                    // ah screw it
                    Bukkit.getScheduler().runTask(TyphonPlugin.plugin, (Runnable) () -> {
                        status.stageComplete();
                        runConstructions(status, iterator, useNMS, callback, iterationCallback);
                    });
                }
            });
        } else {
            if (callback != null) {
                callback.run();
            }
        }
    }

    public static <T extends VolcanoConstructionData> void runConstructionGroups(VolcanoConstructionStatus status, Iterator<List<T>> iterator, boolean useNMS, Runnable callback, Runnable iterationCallback) {
        status.hasSubStage = true;
        if (iterator.hasNext()) {
            List<T> nextData = iterator.next();
            status.currentSubStage = 0;
            status.totalSubStage = nextData.size();

            for (T data: nextData) {
                runConstruction(data, useNMS);
                status.subStageComplete();
            }
            Bukkit.getScheduler().runTaskLater(TyphonPlugin.plugin, (Runnable) () -> {
                if (iterationCallback != null) {
                    iterationCallback.run();
                }
                status.stageComplete();
                runConstructionGroups(status, iterator, useNMS, callback, iterationCallback);
            }, 40L);
        } else {
            if (callback != null) {
                callback.run();
            }
        }
    }

    public static <T extends VolcanoConstructionData> List<List<T>> splitToGroups(Location baseLocation, List<T> data, boolean useNMS) {
        Block block = baseLocation.getBlock();
        Material material = block.getType();

        long blockUpdateStartTime = System.nanoTime();;

        if (useNMS) {
            TyphonNMSUtils.setBlockMaterial(block, Material.LAVA, true, false);
        } else {
            baseLocation.getBlock().setType(Material.LAVA);
        }

        long blockUpdateEndTime = System.nanoTime();;
        long elapsedNanoSecondPerBlockUpdate = blockUpdateEndTime - blockUpdateStartTime;

        block.setType(material);

        long blockUpdatesPerMilliSecond = 1000000 / elapsedNanoSecondPerBlockUpdate;
        long blockUpdatesPerSecond = blockUpdatesPerMilliSecond * 1000;

        TyphonPlugin.logger.log( VolcanoLogClass.CONSTRUCTION, "block update took:" + elapsedNanoSecondPerBlockUpdate + "ns.");
        TyphonPlugin.logger.log( VolcanoLogClass.CONSTRUCTION, blockUpdatesPerSecond + " block updates per second");

        List<List<T>> dataGroup = new ArrayList<>();

        int separatedBlocksPerGroup = (int) blockUpdatesPerSecond / 2;
        int separateGroupCount = (int) Math.ceil(data.size() / (double) separatedBlocksPerGroup);

        System.out.println("Separated: " + separatedBlocksPerGroup + " block updates per second, "+separateGroupCount+" groups");

        for (int i = 0; i < separateGroupCount; i++) {
            int max = Math.min(((i+1) * separatedBlocksPerGroup) - 1, data.size());
            List<T> thisData = data.subList(i * separatedBlocksPerGroup, max);

            dataGroup.add(thisData);
        }

        return dataGroup;
    }
}