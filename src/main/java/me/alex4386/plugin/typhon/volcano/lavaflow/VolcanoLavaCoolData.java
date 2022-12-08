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
import org.bukkit.util.Vector;

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
        if (silicateLevel > 0.68) return 0;

        double silicateRatio = (Math.max(0.48, silicateLevel) - 0.48) / (0.68 - 0.48);
        double extensionRate = 0.1;

        if (distance < 30) {
            return Math.random() < 0.5 ? 1 : 0;
        }

        //if (Math.random() > extensionRate) return 0;
        if (Math.random() < Math.pow(silicateRatio, 1.5)) return 0;

        double extBySilicateLevel = Math.max(Math.sqrt(3), (1 - Math.pow(silicateRatio, 2)) * 4);
        double extendLimit = Math.max(20, extBySilicateLevel * height);

        if (extendLimit > distance) {
            double probability = Math.pow(distance / extendLimit, 2);
            if (Math.random() < probability) {
                return 1;
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
            return this.flowedFromVent.lavaFlow.extensionCapable(this.block.getLocation());
        }
        return true;
    }

    public void handleExtension() {
        BlockData bd = block.getBlockData();
        Location flowVector = block.getLocation().subtract(fromBlock.getLocation());

        // Just do it in flow vectors. lava is stiff
        Location sourceLocation = source.getLocation();
        sourceLocation.setY(block.getLocation().getY());

        Location ventFlowVector = block.getLocation().subtract(sourceLocation);
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
                block.setType(material);

                BlockFace[] flowableFaces = {
                        BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH,
                };

                if (Math.random() < 0.3) {
                    for (BlockFace bf : flowableFaces) {
                        if (Math.random() < 0.2) continue;

                        Block flowDirectionBlock = block.getRelative(bf);
                        if (flowDirectionBlock.getType().isAir()) {
                            if (!this.flowedFromVent.lavaFlow.isLavaRegistered(flowDirectionBlock)) {
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
                } else {
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

                    if (targetFlowFace != null) {
                        Block flowDirectionBlock = block.getRelative(targetFlowFace);

                        if (!this.flowedFromVent.lavaFlow.isLavaRegistered(flowDirectionBlock)) {
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

                if (!this.flowedFromVent.lavaFlow.isLavaRegistered(flowDirectionBlock)) {
                    flowDirectionBlock.setType(material);

                    this.flowedFromVent.lavaFlow.registerLavaCoolData(
                            source,
                            block,
                            flowDirectionBlock,
                            isBomb,
                            targetExtensionValue
                    );
                }

                if (flowDirectionBlock.getType() == Material.WATER) return;

                if (!bottomBlock.getType().isAir() && VolcanoComposition.isVolcanicRock(bottomBlock.getType())) {
                    double silicateLevel = this.flowedFromVent.lavaFlow.settings.silicateLevel;
                    Material material = !this.isBomb
                            ? VolcanoComposition.getExtrusiveRock(silicateLevel)
                            : VolcanoComposition.getBombRock(silicateLevel);
                    flowDirectionBlock.setType(material);
                }
            }
        }
    }


    public void coolDown() {
        block.setType(material);
        BlockData bd = block.getBlockData();
        
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

        if (this.runExtensionCount > 0 && this.extensionCapable()) this.handleExtension();
    }

    public void forceCoolDown() {
        this.ticks = 0;
        block.setType(material);
    }
}
