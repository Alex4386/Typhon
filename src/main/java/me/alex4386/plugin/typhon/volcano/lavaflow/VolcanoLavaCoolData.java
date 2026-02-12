package me.alex4386.plugin.typhon.volcano.lavaflow;

import me.alex4386.plugin.typhon.TyphonBlocks;
import me.alex4386.plugin.typhon.TyphonSounds;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;
import me.alex4386.plugin.typhon.volcano.lavaflow.VolcanoPillowLavaData;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.util.Vector;

import java.util.Queue;
import java.util.function.Consumer;

public class VolcanoLavaCoolData {
    public int ticks;
    public Block block;
    public Block fromBlock;
    public Material material;
    public Block source;
    public int flowLimit = 10;
    public VolcanoVent flowedFromVent = null;
    boolean isBomb = false;
    public boolean isProcessed = false;
    private boolean quickCoolKillSwitch = false;

    public int ejectaRecordIdx = -1;
    public int runExtensionCount = 0;
    public boolean skipNormalLavaFlowLengthCheck = false;

    public double plumbExtension = 1;

    public boolean isUnderfill = false;

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

        // System.out.println("LavaCoolData generated: "+TyphonUtils.blockLocationTostring(block));
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
                this.flowedFromVent.lavaFlow.settings.silicateLevel,
                TyphonUtils.getTwoDimensionalDistance(source.getLocation(), fromBlock.getLocation()),
                Math.max(5, this.flowedFromVent.getSummitBlock().getY() - this.flowedFromVent.location.getY())
        );

        if (this.isBomb) {
            this.runExtensionCount = 0;
        }
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

    public void setQuickCoolKillSwitch() {
        this.quickCoolKillSwitch = true;
    }

    public static int calculateExtensionCount(double silicateLevel, double distance, double height) {
        // 0.50 is lower end. minimum travel distance should be 10km.
        // but this is Minecraft. 10000 blocks is way too much. scaling down
        if (silicateLevel > 0.68) return 0;

        double silicateRatio = (Math.max(0.50, silicateLevel) - 0.50) / (0.68 - 0.50);
        double extensionRate = 0.1;

        //if (Math.random() > extensionRate) return 0;
        if (Math.random() < Math.pow(silicateRatio, 1.5)) return 0;

        double extBySilicateLevel = Math.max(0, Math.pow((1 - silicateRatio), 2.5) * 4);
        double extendLimit = Math.max(20, extBySilicateLevel * height);

        if (extendLimit > distance || distance < 30) {
            double probability = Math.pow(distance / extendLimit, 2);

            if (Math.random() > probability) {
                return (int) extBySilicateLevel;
            }
        }
        return 0;
    }

    public boolean shouldCooldown() {
        return this.tickPassed() && !this.isProcessed;
    }

    public boolean shouldTickPass() {
        return !this.shouldCooldown() && !this.isProcessed;
    }

    public void tickPass() {
        if (this.shouldCooldown()) {
            this.coolDown();
        } else if (!this.isProcessed) {
            this.ticks--;
        }
    }

    public boolean tickPassed() {
        return this.isProcessed || this.ticks <= 0;
    }

    public boolean extensionCapable() {
        if (this.flowedFromVent != null) {
            return this.flowedFromVent.lavaFlow.extensionCapable(this.block.getLocation()) && this.runExtensionCount > 0;
        }
        return true;
    }

    public BlockFace getExtensionTargetBlockFace() {
        BlockFace[] flowableFaces = {
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH,
        };

        Location flowVector = block.getLocation().subtract(fromBlock.getLocation());

        Location sourceLocation = source.getLocation();
        Location ventFlowVector = block.getLocation().subtract(sourceLocation);

        // heck, It's Linear Algebra time. AGAIN NOOOOOOOOOO
        Vector flowUnit = new Vector(flowVector.getX(), flowVector.getY(), flowVector.getZ()).normalize();
        Vector ventFlowUnit = new Vector(ventFlowVector.getX(), ventFlowVector.getY(), ventFlowVector.getZ());

        double pushingMultiplier = Math.min(0.5, Math.max(0.1, ventFlowVector.length() / 400));
        Vector lavaPushingDirection = flowUnit.add(ventFlowUnit.multiply(pushingMultiplier)).normalize();

        BlockFace targetFlowFace = null;

        for (BlockFace bf : flowableFaces) {
            Block flowDirectionBlock = block.getRelative(bf);
            if (flowDirectionBlock.getType().isAir()) {
                if (lavaPushingDirection.angle(new Vector(bf.getModX(), bf.getModY(), bf.getModZ())) <= Math.PI / 4) {
                    targetFlowFace = bf;
                }
            }
        }

        return targetFlowFace;
    }

    public void handleExtension() {
        BlockData bd = block.getBlockData();
        Location flowVector = block.getLocation().subtract(fromBlock.getLocation());

        // Just do it in flow vectors. lava is stiff
        Location sourceLocation = source.getLocation();
        sourceLocation.setY(block.getLocation().getY());
        int level = -1;

        if (bd instanceof Levelled) {
            Levelled levelBd = (Levelled) bd;
            level = levelBd.getLevel();
        }

        int targetExtensionValue = this.runExtensionCount - 1;
        if (bd instanceof Levelled && this.flowedFromVent != null) {
            if (fromBlock != null
                    && flowVector.getBlockY() == 0
                    && 6 <= level
                    && level < 8) {

                this.flowedFromVent.lavaFlow.queueBlockUpdate(block, material, TyphonUtils.getBlockFaceUpdater(flowVector.toVector()));

                BlockFace[] baseFaces = {
                        BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH,
                };

                // For underfill: also consider UP if under the volcano slope
                BlockFace[] flowableFaces;
                if (this.isUnderfill) {
                    Block highestRock = TyphonUtils.getHighestRocklikes(block);
                    if (highestRock.getY() > block.getY()) {
                        Block upBlock = block.getRelative(BlockFace.UP);
                        if (upBlock.getType().isAir()) {
                            flowableFaces = new BlockFace[] {
                                    BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.UP
                            };
                        } else {
                            flowableFaces = baseFaces;
                        }
                    } else {
                        flowableFaces = baseFaces;
                    }
                } else {
                    flowableFaces = baseFaces;
                }

                if (Math.random() < 0.3) {
                    for (BlockFace bf : flowableFaces) {
                        if (Math.random() < 0.2) continue;

                        Block flowDirectionBlock = block.getRelative(bf);
                        if (flowDirectionBlock.getType().isAir()) {
                            if (!this.flowedFromVent.lavaFlow.isLavaRegistered(flowDirectionBlock)) {
                                Object obj = this.flowedFromVent.lavaFlow.registerLavaCoolData(
                                        source,
                                        block,
                                        flowDirectionBlock,
                                        isBomb,
                                        targetExtensionValue
                                );

                                if (obj instanceof VolcanoLavaCoolData data) {
                                    data.plumbExtension = this.plumbExtension * (0.5 + (Math.random() * 0.25));
                                    if (this.isUnderfill) data.isUnderfill = true;
                                }
                            }
                        }
                    }
                } else {
                    BlockFace targetFlowFace = this.getExtensionTargetBlockFace();

                    if (targetFlowFace != null) {
                        Block flowDirectionBlock = block.getRelative(targetFlowFace);

                        if (!this.flowedFromVent.lavaFlow.isLavaRegistered(flowDirectionBlock)) {
                            Object obj = this.flowedFromVent.lavaFlow.registerLavaCoolData(
                                    source,
                                    block,
                                    flowDirectionBlock,
                                    isBomb,
                                    targetExtensionValue
                            );

                            if (obj instanceof VolcanoLavaCoolData data) {
                                if (this.isUnderfill) data.isUnderfill = true;
                            }
                        }
                    } else if (this.isUnderfill) {
                        // Underfill dead end: add flowable neighbors as new underfill targets
                        for (BlockFace bf : flowableFaces) {
                            Block neighbor = block.getRelative(bf);
                            if (neighbor.getType().isAir() && !this.flowedFromVent.lavaFlow.isLavaRegistered(neighbor)) {
                                this.flowedFromVent.lavaFlow.underfillTargets.add(neighbor);
                            }
                        }
                        // Also check DOWN for caves
                        Block downBlock = block.getRelative(BlockFace.DOWN);
                        if (downBlock.getType().isAir() && !this.flowedFromVent.lavaFlow.isLavaRegistered(downBlock)) {
                            this.flowedFromVent.lavaFlow.underfillTargets.add(downBlock);
                        }
                    }
                }
            } else if (flowVector.getBlockY() == -1) {
                Block flowDirectionBlock = block.getLocation().add(flowVector).getBlock();
                Block bottomBlock = flowDirectionBlock.getRelative(BlockFace.DOWN);

                if (!this.flowedFromVent.lavaFlow.isLavaRegistered(flowDirectionBlock)) {
                    this.flowedFromVent.lavaFlow.queueBlockUpdate(flowDirectionBlock, material, TyphonUtils.getBlockFaceUpdater(flowVector.toVector()));

                    Object obj = this.flowedFromVent.lavaFlow.registerLavaCoolData(
                            source,
                            block,
                            flowDirectionBlock,
                            isBomb,
                            -1
                    );

                    if (obj instanceof VolcanoLavaCoolData data) {
                        if (this.isUnderfill) data.isUnderfill = true;
                    }
                }

                if (flowDirectionBlock.getType() == Material.WATER) return;

                if (!bottomBlock.getType().isAir() && VolcanoComposition.isVolcanicRock(bottomBlock.getType())) {
                    double silicateLevel = this.flowedFromVent.lavaFlow.settings.silicateLevel;
                    Material material = !this.isBomb
                            ? VolcanoComposition.getExtrusiveRock(silicateLevel)
                            : VolcanoComposition.getBombRock(silicateLevel, this.flowedFromVent.lavaFlow.getDistanceRatio(flowDirectionBlock.getLocation()));
                    this.flowedFromVent.lavaFlow.queueBlockUpdate(flowDirectionBlock, material);
                }
            }
        }
    }

    public void coolDown() {
        this.coolDown(false);
    }

    public void coolDown(boolean isQuickCool) {
        if (Math.random() < 0.001) {
            TyphonSounds.getRandomLavaFragmenting().play(
                    block.getLocation(),
                    SoundCategory.BLOCKS,
                    0.1f,
                    (float) (0.5f + (Math.random() * 0.5))
            );
        }

        if (this.flowedFromVent != null) {
            if (this.flowedFromVent.volcano.manager.isInAnyFormingCaldera(block.getLocation())) {
                this.flowedFromVent.lavaFlow.queueBlockUpdate(block, Material.AIR);
                return;
            }

            if (!this.flowedFromVent.isFlowingLava()) {
                if (TyphonUtils.toLowerCaseDumbEdition(material.name()).contains("ore")) {
                    material = VolcanoComposition.getExtrusiveRock(this.flowedFromVent.lavaFlow.settings.silicateLevel);
                }
            }
            this.flowedFromVent.flushSummitCacheByLocation(block);
        }

        if (this.extensionCapable() && !isQuickCool) {
            if (!this.quickCoolKillSwitch) {
                this.handleExtension();
            }
        }

        if (this.flowedFromVent != null) {
            this.flowedFromVent.lavaFlow.queueBlockUpdate(block, material, TyphonUtils.getBlockFaceUpdater(
                    block.getLocation().subtract(fromBlock.getLocation()).toVector()
            ));
        } else {
            TyphonBlocks.setBlockType(block, material);
        }
    }

    public void forceCoolDown() {
        Material material = this.material;

        if (this.flowedFromVent != null) {
            if (this.flowedFromVent.volcano.manager.isInAnyFormingCaldera(block.getLocation())) {
                TyphonBlocks.setBlockType(block, Material.AIR);
                this.ticks = 0;
                return;
            }

            if (!this.flowedFromVent.isFlowingLava()) {
                if (TyphonUtils.toLowerCaseDumbEdition(material.name()).contains("ore")) {
                    material = VolcanoComposition.getExtrusiveRock(this.flowedFromVent.lavaFlow.settings.silicateLevel);
                }
            }
        }

        this.ticks = 0;

        TyphonBlocks.setBlockType(block, material);
    }
}
