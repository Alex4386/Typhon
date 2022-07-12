package me.alex4386.plugin.typhon.volcano.lavaflow;

import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

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
                this.flowedFromVent.lavaFlow.settings.silicateLevel);
        
        if (this.isBomb) {
            this.runExtensionCount = 0;

            if (Math.random() < 0.25) {
                this.runExtensionCount = 1;
            }
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

    public static int calculateExtensionCount(double silicateLevel) {
        // 0.48 is lower end. minimum travel distance should be 10km. 
        // but this is Minecraft. 10000 blocks is way too much. scaling down

        double value = silicateLevel < 0.68
            ? (int) Math.floor(
                Math.min(
                    Math.max(
                        Math.floor((0.68 - silicateLevel) * 100) / 5,
                        0.0
                    ), 4.0
                )
            ) : 0;

        double probability = value - Math.floor(value);
        int extra = Math.random() < probability ? 1 : 0;

        return (int) Math.floor(value) + extra;
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
            return this.flowedFromVent.lavaFlow.extensionCapable(this.block.getLocation());
        }
        return true;
    }

    public void coolDown() {
        BlockData bd = block.getBlockData();
        Location flowVector = block.getLocation().subtract(fromBlock.getLocation());

        if (this.runExtensionCount > 0 && this.extensionCapable()) {
            if (bd instanceof Levelled && this.flowedFromVent != null) {
                Levelled levelBd = (Levelled) bd;
                int level = levelBd.getLevel();

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
                            VolcanoLavaCoolData coolData = this.flowedFromVent.lavaFlow.lavaCoolHashMap.get(flowDirectionBlock);
                            if (coolData == null) coolData = this.flowedFromVent.lavaFlow.cachedLavaCoolHashMap.get(flowDirectionBlock);
                            
                            if (coolData == null) {
                                this.flowedFromVent.lavaFlow.registerLavaCoolData(
                                    source,
                                    block,
                                    flowDirectionBlock,
                                    isBomb,
                                    this.runExtensionCount - 1
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
                                this.runExtensionCount - 1
                            );
                         }

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
