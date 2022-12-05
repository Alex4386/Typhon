package me.alex4386.plugin.typhon.volcano.lavaflow;

import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;
import me.alex4386.plugin.typhon.volcano.lavaflow.VolcanoPillowLavaData;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
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
    public boolean skipNormalLavaFlowLengthCheck = false;

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

    public static int calculateExtensionCount(double silicateLevel, double distance, double height) {
        // 0.48 is lower end. minimum travel distance should be 10km. 
        // but this is Minecraft. 10000 blocks is way too much. scaling down

        double extBySilicateLevel = silicateLevel < 0.68
            ? (int) Math.floor(
                Math.min(
                    Math.max(
                        Math.floor((0.68 - silicateLevel) * 100) / 5,
                        0.0
                    ), 4.0
                )
            ) : 0;

        if (extBySilicateLevel == 0) {
            return 0;
        } else {
            double extendLimit = extBySilicateLevel * height;
            double distanceRatio = Math.min(Math.max(0, (extendLimit - distance) / extendLimit), 1);

            double silicateRatio = (Math.max(0.48, silicateLevel) - 0.48) / (0.68 - 0.48);
            double targetMultiplier = Math.pow(distanceRatio, (1.2 + (silicateRatio * 0.8)));

            double calibratedExtension = extBySilicateLevel * targetMultiplier;
            double extension = calibratedExtension * Math.random();

            return (int) Math.max(0, extension);
        }
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
            return this.flowedFromVent.lavaFlow.extensionCapable(this.block.getLocation());
        }
        return true;
    }


    public void coolDown() {
        BlockData bd = block.getBlockData();
        Location flowVector = block.getLocation().subtract(fromBlock.getLocation());
        int level = -1;

        if (bd instanceof Levelled) {
            Levelled levelBd = (Levelled) bd;
            level = levelBd.getLevel();
        }

        if (this.runExtensionCount > 0 && this.extensionCapable()) {
            int targetExtensionValue = this.runExtensionCount - 1;
            if (this.flowedFromVent != null) {
                if (!this.flowedFromVent.isFlowingLava()) {
                    targetExtensionValue -= (int) (Math.random() * 2);
                }
            }

            if (bd instanceof Levelled && this.flowedFromVent != null) {
                if (fromBlock != null
                        && flowVector.getBlockY() == 0
                        && 6 <= level
                        && level < 8) {
                    block.setType(material);
                    // Location flowVector = block.getLocation().subtract(fromBlock.getLocation());

                    BlockFace[] flowableFaces = {
                            BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH,
                    };

                    for (BlockFace bf : flowableFaces) {
                        Block flowDirectionBlock = block.getRelative(bf);
                        if (flowDirectionBlock.getType().isAir()) {
                            VolcanoLavaCoolData coolData = this.flowedFromVent.lavaFlow.lavaCoolHashMap.get(flowDirectionBlock);
                            if (coolData == null) coolData = this.flowedFromVent.lavaFlow.cachedLavaCoolHashMap.get(flowDirectionBlock);
                            
                            if (coolData == null) {
                                this.flowedFromVent.lavaFlow.registerLavaCoolData(
                                    source,
                                    block,
                                    flowDirectionBlock,
                                    isBomb,
                                    targetExtensionValue
                                );
                            }
                        }
                    }
                } else if (flowVector.getBlockY() == -1) {
                    Block flowDirectionBlock = block.getLocation().add(flowVector).getBlock();
                    Block bottomBlock = flowDirectionBlock.getRelative(BlockFace.DOWN);

                    if (flowDirectionBlock.getType() == Material.WATER) {
                        VolcanoPillowLavaData lavaData = this.flowedFromVent.lavaFlow.pillowLavaMap.get(flowDirectionBlock);
                        if (lavaData == null) lavaData = this.flowedFromVent.lavaFlow.cachedPillowLavaMap.get(flowDirectionBlock);

                        if (lavaData == null) {
                            flowDirectionBlock.setType(material);

                            this.flowedFromVent.lavaFlow.registerLavaCoolData(
                                source,
                                block,
                                flowDirectionBlock,
                                isBomb,
                                targetExtensionValue
                            );
                         }

                        return;
                    }

                    if (!bottomBlock.getType().isAir() && VolcanoComposition.isVolcanicRock(bottomBlock.getType())) {
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
        bd = block.getBlockData();
        
        if (fromBlock != null) {
            BlockFace f = block.getFace(fromBlock);
    
            if (bd instanceof Directional) {
                Directional d = (Directional) bd;
                if (f != null && f.isCartesian()) {
                    d.setFacing(f);
                }
                block.setBlockData(d);
            }
        }
    }

    public void forceCoolDown() {
        this.ticks = 0;
        block.setType(material);
    }
}
