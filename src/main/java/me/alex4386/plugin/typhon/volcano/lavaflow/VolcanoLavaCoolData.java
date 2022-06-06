package me.alex4386.plugin.typhon.volcano.lavaflow;

import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;

public class VolcanoLavaCoolData {
    public int ticks;
    public Block block;
    public Block fromBlock;
    public Material material;
    public Block source;
    public VolcanoVent flowedFromVent = null;
    boolean isBomb = false;
    public boolean isProcessed = false;

    public int runExtensionCount = 0;

    public VolcanoLavaCoolData(
            Block source,
            Block fromBlock,
            Block block,
            VolcanoVent flowedFromVent,
            Material material,
            int ticks) {
        if (source == null)
            this.source = block;
        else
            this.source = source;

        this.ticks = ticks;
        this.fromBlock = fromBlock;
        this.block = block;
        this.material = material;
        this.flowedFromVent = flowedFromVent;
    }

    public VolcanoLavaCoolData(
            Block source,
            Block fromBlock,
            Block block,
            VolcanoVent flowedFromVent,
            Material material,
            int ticks,
            boolean isBomb) {
        this(source, fromBlock, block, flowedFromVent, material, ticks);
        this.isBomb = isBomb;

        this.runExtensionCount = VolcanoLavaCoolData.calculateExtensionCount(
                this.flowedFromVent.lavaFlow.settings.silicateLevel);
    }

    public VolcanoLavaCoolData(
            Block source,
            Block fromBlock,
            Block block,
            VolcanoVent flowedFromVent,
            Material material,
            int ticks,
            boolean isBomb,
            int runExtensionCount) {
        this(source, fromBlock, block, flowedFromVent, material, ticks, isBomb);

        this.flowedFromVent = flowedFromVent;
        this.runExtensionCount = runExtensionCount;
    }

    public static int calculateExtensionCount(double silicateLevel) {
        // 0.48 is lower end. minimum travel distance should be 10km. but this is

        return silicateLevel < 0.68
                ? (int) Math.floor(
                        Math.min(
                                Math.max(
                                        Math.pow(
                                                Math.floor((0.68 - silicateLevel) * 100)
                                                        / 5,
                                                3),
                                        0.0),
                                64.0))
                : 0;
    }

    public void tickPass() {
        if (this.isProcessed) {
            return;
        } else if (this.tickPassed()) {
            this.coolDown();
        } else {
            this.ticks--;
        }
    }

    public boolean tickPassed() {
        return this.isProcessed || this.ticks <= 0;
    }

    public boolean extensionCapable() {
        if (this.flowedFromVent != null) {
            if (this.flowedFromVent.getType() == VolcanoVentType.CRATER) {
                if (this.flowedFromVent.isInVent(this.block.getLocation())) {
                    return false;
                }
            }
        }
        return true;
    }

    public void coolDown() {
        if (this.runExtensionCount > 0 && this.extensionCapable()) {
            BlockData bd = block.getBlockData();

            if (bd instanceof Levelled && this.flowedFromVent != null) {
                Levelled levelBd = (Levelled) bd;
                int level = levelBd.getLevel();

                Location flowVector = block.getLocation().subtract(fromBlock.getLocation());
                // System.out.println("[Lavaflow-ext debug] flowVector:
                // "+flowVector.getBlockX()+","+flowVector.getBlockY()+","+flowVector.getBlockZ()+"
                // / level:
                // "+level);

                if (fromBlock != null
                        && flowVector.getBlockY() == 0
                        && 6 <= levelBd.getLevel()
                        && levelBd.getLevel() < 8) {
                    block.setType(material);
                    // Location flowVector = block.getLocation().subtract(fromBlock.getLocation());

                    BlockFace[] flowableFaces = {
                            BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH,
                    };

                    for (BlockFace bf : flowableFaces) {
                        Block flowDirectionBlock = block.getRelative(bf);
                        if (flowDirectionBlock.getType().isAir()) {
                            double silicateLevel = this.flowedFromVent.lavaFlow.settings.silicateLevel;
                            Material material = !this.isBomb
                                    ? VolcanoComposition.getExtrusiveRock(silicateLevel)
                                    : VolcanoComposition.getBombRock(silicateLevel);
                            this.flowedFromVent.lavaFlow.cachedLavaCoolHashMap.put(
                                    block,
                                    new VolcanoLavaCoolData(
                                            source,
                                            block,
                                            flowDirectionBlock,
                                            this.flowedFromVent,
                                            material,
                                            (int) (this.flowedFromVent.lavaFlow.settings.flowed
                                                    * this.flowedFromVent.lavaFlow
                                                            .getTickFactor()),
                                            this.isBomb,
                                            this.runExtensionCount - 1));
                        }
                    }
                } else if (flowVector.getBlockY() == -1) {
                    Block flowDirectionBlock = block.getLocation().add(flowVector).getBlock();
                    Block bottomBlock = flowDirectionBlock.getRelative(BlockFace.DOWN);

                    if (flowDirectionBlock.getType() == Material.WATER) {
                        flowDirectionBlock.setType(material);

                        this.flowedFromVent.lavaFlow.cachedLavaCoolHashMap.put(
                                block,
                                new VolcanoLavaCoolData(
                                        source,
                                        block,
                                        flowDirectionBlock,
                                        this.flowedFromVent,
                                        material,
                                        (int) (this.flowedFromVent.lavaFlow.settings.flowed
                                                * this.flowedFromVent.lavaFlow
                                                        .getTickFactor()),
                                        this.isBomb));

                        return;
                    }

                    if (!bottomBlock.getType().isAir()) {
                        double silicateLevel = this.flowedFromVent.lavaFlow.settings.silicateLevel;
                        Material material = !this.isBomb
                                ? VolcanoComposition.getExtrusiveRock(silicateLevel)
                                : VolcanoComposition.getBombRock(silicateLevel);
                        flowDirectionBlock.setType(material);
                    }
                } else {

                }
            }
        }

        block.setType(material);
    }

    public void forceCoolDown() {
        this.ticks = 0;
        block.setType(material);
    }
}
