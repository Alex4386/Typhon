package me.alex4386.plugin.typhon.volcano.lavaflow;

import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.*;

public class VolcanoPillowLavaFlow {

    final VolcanoLavaFlow lavaFlow;

    // active pillow lava blocks being simulated this tick
    public Map<Block, VolcanoPillowLavaData> activeMap = new HashMap<>();

    // newly registered blocks, merged into activeMap at end of tick
    public Map<Block, VolcanoPillowLavaData> cachedMap = new HashMap<>();

    public VolcanoPillowLavaFlow(VolcanoLavaFlow lavaFlow) {
        this.lavaFlow = lavaFlow;
    }

    // ==========================================
    // Registration & Lookup
    // ==========================================

    public VolcanoPillowLavaData register(
            Block block, Block sourceBlock, Block fromBlock,
            int extensionCount, int ejectaRecordIdx) {
        VolcanoPillowLavaData existing = cachedMap.get(block);
        if (existing != null) {
            return null;
        }

        lavaFlow.queueBlockUpdate(block, Material.MAGMA_BLOCK);

        VolcanoPillowLavaData data = new VolcanoPillowLavaData(
                getVent(), sourceBlock, fromBlock, extensionCount);
        data.ejectaRecordIdx = ejectaRecordIdx;

        cachedMap.put(block, data);
        lavaFlow.addFlowEndBlock(block, true);

        return data;
    }

    public void registerDirect(Block block, VolcanoPillowLavaData data) {
        cachedMap.put(block, data);
        lavaFlow.addFlowEndBlock(block, true);
    }

    public boolean isRegistered(Block block) {
        return getData(block) != null;
    }

    public VolcanoPillowLavaData getData(Block block) {
        VolcanoPillowLavaData data = activeMap.get(block);
        if (data == null) {
            data = cachedMap.get(block);
        }
        return data;
    }

    public int getActiveCount() {
        return activeMap.size();
    }

    public int getTotalCount() {
        return activeMap.size() + cachedMap.size();
    }

    // ==========================================
    // Tick Processing
    // ==========================================

    public void tick() {
        Iterator<Map.Entry<Block, VolcanoPillowLavaData>> iterator =
                activeMap.entrySet().iterator();
        List<Block> flowedBlocks = new ArrayList<>();

        try {
            while (iterator.hasNext()) {
                Map.Entry<Block, VolcanoPillowLavaData> entry = iterator.next();
                Block block = entry.getKey();
                VolcanoPillowLavaData data = entry.getValue();

                // caldera override: dissolve pillow lava in caldera range
                if (getVent().caldera.isForming()
                        && getVent().caldera.isInCalderaRange(block.getLocation())) {
                    lavaFlow.queueBlockUpdate(block, Material.WATER);
                    flowedBlocks.add(block);
                    continue;
                }

                // 20% skip — simulates slow underwater flow
                if (Math.random() < 0.2) {
                    continue;
                }

                data.runTick();
                if (!data.canCooldown() || data.hasFlowed()) {
                    continue;
                }

                flowedBlocks.add(block);
                data.markAsFlowed();

                processBlock(block, data, flowedBlocks);
            }
        } catch (ConcurrentModificationException e) {
            e.printStackTrace();
        }

        for (Block flowedBlock : flowedBlocks) {
            activeMap.remove(flowedBlock);
            lavaFlow.removeFlowEndBlock(flowedBlock);
        }

        mergeCacheToActive();
    }

    // ==========================================
    // Bulk Operations
    // ==========================================

    public void solidifyAll() {
        double silicateLevel = getSettings().silicateLevel;

        for (Map.Entry<Block, VolcanoPillowLavaData> entry : activeMap.entrySet()) {
            lavaFlow.queueBlockUpdate(
                    entry.getKey(),
                    VolcanoComposition.getExtrusiveRock(silicateLevel));
        }
        activeMap.clear();

        for (Map.Entry<Block, VolcanoPillowLavaData> entry : cachedMap.entrySet()) {
            lavaFlow.queueBlockUpdate(
                    entry.getKey(),
                    VolcanoComposition.getExtrusiveRock(silicateLevel));
        }
        cachedMap.clear();
    }

    public void clear() {
        activeMap.clear();
        cachedMap.clear();
    }

    // ==========================================
    // Internal: Tick sub-steps
    // ==========================================

    private void processBlock(Block block, VolcanoPillowLavaData data, List<Block> flowedBlocks) {
        Block fromBlock = data.fromBlock;
        Block sourceBlock = data.sourceBlock;

        double distance = TyphonUtils.getTwoDimensionalDistance(
                sourceBlock.getLocation(), block.getLocation());
        if (distance > getVent().longestFlowLength) {
            getVent().longestFlowLength = distance;
        }

        // solidify the current block
        if (fromBlock != null) {
            Material material = getSolidificationMaterial(sourceBlock, block);

            lavaFlow.queueBlockUpdate(block, material,
                    TyphonUtils.getBlockFaceUpdater(fromBlock, block));
            getVent().flushSummitCacheByLocation(block);

            BlockFace f = block.getFace(data.fromBlock);
            if (f != null) {
                TyphonUtils.getBlockFaceUpdater(f).accept(block);
            }
        }

        // handle downward flow
        if (handleDownwardFlow(block, data, flowedBlocks)) {
            return;
        }

        // handle lateral surface flow
        handleLateralFlow(block, data);
    }

    private boolean handleDownwardFlow(Block block, VolcanoPillowLavaData data,
                                        List<Block> flowedBlocks) {
        Block underBlock = block.getRelative(BlockFace.DOWN);
        Block sourceBlock = data.sourceBlock;

        Material material = getSolidificationMaterial(sourceBlock, underBlock);

        if (underBlock.getType() == Material.MAGMA_BLOCK) {
            // another pillow block below — try to merge fluid levels
            VolcanoPillowLavaData underData = activeMap.get(underBlock);
            if (underData != null) {
                int underFluidLevel = underData.fluidLevel;
                int level = data.fluidLevel;
                int levelSum = underFluidLevel + level;
                if (levelSum > 8) {
                    flowedBlocks.add(underBlock);
                    data.fluidLevel = levelSum - 8;
                } else {
                    underData.fluidLevel += level;
                    underData.extensionCount += data.extensionCount;
                    return true; // continue to next block
                }
            } else {
                lavaFlow.queueBlockUpdate(underBlock, material);
            }
        } else if (underBlock.isEmpty() || TyphonUtils.containsLiquidWater(underBlock)) {
            if (!lavaFlow.isPillowLavaRegistered(underBlock)) {
                lavaFlow.registerLavaCoolData(
                        data.sourceBlock,
                        data.fromBlock,
                        underBlock,
                        false,
                        -1);
                if (Math.random() < 0.1) {
                    TyphonUtils.createRisingSteam(
                            data.fromBlock.getLocation().add(0, 1, 0), 1, 2);
                }
                return true; // continue to next block
            }
            lavaFlow.queueBlockUpdate(underBlock, material);
            flowedBlocks.add(underBlock);
        }

        return false;
    }

    private void handleLateralFlow(Block block, VolcanoPillowLavaData data) {
        int extension = data.extensionCount;
        int level = data.fluidLevel;

        int levelDeductionRate = (getSettings().silicateLevel < 0.63)
                ? ((Math.random() > lavaFlow.getLavaStickiness()) ? 1 : 2)
                : 2;
        level -= levelDeductionRate;

        if (!lavaFlow.extensionCapable(block.getLocation())) {
            extension = 0;
        }

        if (level <= 0) {
            int deductionCount = 1;
            if (getVent().isFlowingLava()) {
                deductionCount += (int) (Math.random() * 2);
            }

            extension -= deductionCount;

            if (extension < 0) {
                return;
            }

            level = 8;
        }

        // determine primary flow direction
        BlockFace primaryFlow = null;
        if (data.fromBlock != null) {
            primaryFlow = data.fromBlock.getFace(block);
            if (primaryFlow != BlockFace.DOWN) {
                primaryFlow = null;
            }
        }

        BlockFace[] flowableFaces = {
                BlockFace.NORTH,
                BlockFace.WEST,
                BlockFace.EAST,
                BlockFace.SOUTH,
        };

        for (BlockFace flowableFace : flowableFaces) {
            Block flowTarget = block.getRelative(flowableFace);

            boolean isPrimary = false;
            int levelT = (primaryFlow != null)
                    ? level - levelDeductionRate : level;

            if (primaryFlow != null) {
                isPrimary = primaryFlow.getDirection().equals(flowableFace.getDirection());
                levelT = isPrimary ? level : levelT;
            }
            if (level <= 0) continue;

            double ratio = Math.min(1, Math.max(0, levelT / 6.0));

            double levelProbability = Math.pow(ratio, 2);
            if (isPrimary) {
                levelProbability = Math.pow(ratio, 1.25);
            }

            if (Math.random() > levelProbability) {
                continue;
            }

            if (flowTarget.getType().isAir()
                    || TyphonUtils.containsLiquidWater(flowTarget)) {

                TyphonUtils.removeSeaGrass(flowTarget);
                if (!lavaFlow.isPillowLavaRegistered(flowTarget)) {
                    Object obj;
                    if (getVent().getType() == VolcanoVentType.CRATER
                            && getVent().getTwoDimensionalDistance(flowTarget.getLocation()) == getVent().getRadius()) {
                        // on-vent: treat as source block
                        obj = lavaFlow.registerLavaCoolData(
                                flowTarget,
                                flowTarget,
                                flowTarget,
                                false,
                                extension);
                    } else {
                        obj = lavaFlow.registerLavaCoolData(
                                data.sourceBlock,
                                data.fromBlock,
                                flowTarget,
                                false,
                                extension);
                    }

                    if (obj instanceof VolcanoLavaCoolData coolData) {
                        coolData.skipNormalLavaFlowLengthCheck = true;
                    } else if (obj instanceof VolcanoPillowLavaData pillowData) {
                        pillowData.fluidLevel = levelT;
                    }
                }
            }
        }
    }

    private void mergeCacheToActive() {
        Iterator<Map.Entry<Block, VolcanoPillowLavaData>> iterator =
                cachedMap.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Block, VolcanoPillowLavaData> entry = iterator.next();
            activeMap.put(entry.getKey(), entry.getValue());
            iterator.remove();
        }
    }

    // ==========================================
    // Internal: Helpers
    // ==========================================

    private Material getSolidificationMaterial(Block sourceBlock, Block targetBlock) {
        double distance = TyphonUtils.getTwoDimensionalDistance(
                sourceBlock.getLocation(), targetBlock.getLocation());
        Material ore = lavaFlow.getOre(distance);
        Material base = VolcanoComposition.getExtrusiveRock(getSettings().silicateLevel);
        return ore != null ? ore : base;
    }

    private VolcanoVent getVent() {
        return lavaFlow.vent;
    }

    private VolcanoLavaFlowSettings getSettings() {
        return lavaFlow.settings;
    }
}
