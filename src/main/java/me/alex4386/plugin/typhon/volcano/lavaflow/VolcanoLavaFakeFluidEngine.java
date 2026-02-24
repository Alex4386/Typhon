package me.alex4386.plugin.typhon.volcano.lavaflow;

import me.alex4386.plugin.typhon.TyphonBlocks;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.*;

public class VolcanoLavaFakeFluidEngine {

    final VolcanoLavaFlow lavaFlow;

    // active pillow lava blocks being simulated this tick
    public Map<Block, VolcanoLavaFluidState> activeMap = new HashMap<>();

    // newly registered blocks, merged into activeMap at end of tick
    public Map<Block, VolcanoLavaFluidState> cachedMap = new HashMap<>();

    public VolcanoLavaFakeFluidEngine(VolcanoLavaFlow lavaFlow) {
        this.lavaFlow = lavaFlow;
    }

    // ==========================================
    // Registration & Lookup
    // ==========================================

    public VolcanoLavaFluidState register(
            Block block, Block sourceBlock, Block fromBlock,
            int extensionCount, int ejectaRecordIdx,
            VolcanoLavaType type) {
        VolcanoLavaFluidState existing = cachedMap.get(block);
        if (existing != null) {
            return null;
        }

        lavaFlow.queueBlockUpdate(block, Material.MAGMA_BLOCK);

        VolcanoLavaFluidState data = new VolcanoLavaFluidState(
                getVent(), sourceBlock, fromBlock, extensionCount);
        data.type = type;
        data.ejectaRecordIdx = ejectaRecordIdx;

        cachedMap.put(block, data);
        lavaFlow.addFlowEndBlock(block, true);

        return data;
    }

    public void registerDirect(Block block, VolcanoLavaFluidState data) {
        cachedMap.put(block, data);
        lavaFlow.addFlowEndBlock(block, true);
    }

    public boolean isRegistered(Block block) {
        return getData(block) != null;
    }

    public VolcanoLavaFluidState getData(Block block) {
        VolcanoLavaFluidState data = activeMap.get(block);
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
        Iterator<Map.Entry<Block, VolcanoLavaFluidState>> iterator =
                activeMap.entrySet().iterator();
        List<Block> flowedBlocks = new ArrayList<>();

        try {
            while (iterator.hasNext()) {
                Map.Entry<Block, VolcanoLavaFluidState> entry = iterator.next();
                Block block = entry.getKey();
                VolcanoLavaFluidState data = entry.getValue();

                // caldera override: dissolve lava in caldera range
                if (getVent().caldera.isForming()
                        && getVent().caldera.isInCalderaRange(block.getLocation())) {
                    lavaFlow.queueBlockUpdate(block,
                            data.getType() == VolcanoLavaType.PILLOW ? Material.WATER : Material.AIR);
                    flowedBlocks.add(block);
                    continue;
                }

                // 20% skip — simulates slow underwater flow (PILLOW only)
                if (data.getType() == VolcanoLavaType.PILLOW && Math.random() < 0.2) {
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
        Material fallback = VolcanoComposition.getExtrusiveRock(silicateLevel);

        for (Map.Entry<Block, VolcanoLavaFluidState> entry : activeMap.entrySet()) {
            VolcanoLavaFluidState state = entry.getValue();
            Material mat = (state.material != null) ? state.material : fallback;
            lavaFlow.queueBlockUpdate(entry.getKey(), mat);
        }
        activeMap.clear();

        for (Map.Entry<Block, VolcanoLavaFluidState> entry : cachedMap.entrySet()) {
            VolcanoLavaFluidState state = entry.getValue();
            Material mat = (state.material != null) ? state.material : fallback;
            lavaFlow.queueBlockUpdate(entry.getKey(), mat);
        }
        cachedMap.clear();
    }

    public void clear() {
        activeMap.clear();
        cachedMap.clear();
    }

    /**
     * Remove a block from the engine (used during LITE→NORMAL conversion).
     * Returns the state if found, null otherwise.
     */
    public VolcanoLavaFluidState remove(Block block) {
        VolcanoLavaFluidState data = activeMap.remove(block);
        if (data == null) {
            data = cachedMap.remove(block);
        }
        if (data != null) {
            lavaFlow.removeFlowEndBlock(block);
        }
        return data;
    }

    // ==========================================
    // Internal: Tick sub-steps
    // ==========================================

    private void processBlock(Block block, VolcanoLavaFluidState data, List<Block> flowedBlocks) {
        Block fromBlock = data.fromBlock;
        Block sourceBlock = data.sourceBlock;

        double distance = TyphonUtils.getTwoDimensionalDistance(
                sourceBlock.getLocation(), block.getLocation());
        if (distance > getVent().longestFlowLength) {
            getVent().longestFlowLength = distance;
        }

        // solidify the current block
        if (fromBlock != null) {
            Material material = getSolidificationMaterial(data, sourceBlock, block);

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

    private boolean handleDownwardFlow(Block block, VolcanoLavaFluidState data,
                                        List<Block> flowedBlocks) {
        Block underBlock = block.getRelative(BlockFace.DOWN);
        Block sourceBlock = data.sourceBlock;

        Material material = getSolidificationMaterial(data, sourceBlock, underBlock);

        if (underBlock.getType() == Material.MAGMA_BLOCK) {
            // another fake-fluid block below — try to merge fluid levels
            VolcanoLavaFluidState underData = activeMap.get(underBlock);
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
                // steam effects only for underwater (PILLOW) mode
                if (data.getType() == VolcanoLavaType.PILLOW && Math.random() < 0.1) {
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

    private void handleLateralFlow(Block block, VolcanoLavaFluidState data) {
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
                    } else if (obj instanceof VolcanoLavaFluidState pillowData) {
                        pillowData.fluidLevel = levelT;
                    }
                }
            }
        }
    }

    private void mergeCacheToActive() {
        Iterator<Map.Entry<Block, VolcanoLavaFluidState>> iterator =
                cachedMap.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Block, VolcanoLavaFluidState> entry = iterator.next();
            activeMap.put(entry.getKey(), entry.getValue());
            iterator.remove();
        }
    }

    // ==========================================
    // Internal: Helpers
    // ==========================================

    private Material getSolidificationMaterial(VolcanoLavaFluidState data,
                                               Block sourceBlock, Block targetBlock) {
        // LITE blocks carry their own material (set during registration)
        if (data.material != null) {
            return data.material;
        }
        // PILLOW: compute from distance (ore chance + silicate-based rock)
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

    // ==========================================
    // NORMAL ↔ LITE Dynamic Conversion
    // ==========================================

    /**
     * Periodic scan that converts NORMAL lava to LITE (when underground & no player nearby)
     * and LITE back to NORMAL (when player approaches or sky access gained).
     */
    void runConversionScan() {
        // --- NORMAL → LITE ---
        List<Map.Entry<Block, VolcanoLavaCoolData>> normalToConvert = new ArrayList<>();

        for (Map<Block, VolcanoLavaCoolData> chunkMap : lavaFlow.lavaCools.values()) {
            for (Map.Entry<Block, VolcanoLavaCoolData> entry : chunkMap.entrySet()) {
                Block block = entry.getKey();
                if (lavaFlow.shouldUseLiteMode(block)) {
                    normalToConvert.add(entry);
                }
            }
        }

        for (Map.Entry<Block, VolcanoLavaCoolData> entry : normalToConvert) {
            convertNormalToLite(entry.getKey(), entry.getValue());
        }

        // --- LITE → NORMAL ---
        List<Map.Entry<Block, VolcanoLavaFluidState>> liteToConvert = new ArrayList<>();

        for (Map.Entry<Block, VolcanoLavaFluidState> entry : activeMap.entrySet()) {
            VolcanoLavaFluidState state = entry.getValue();
            if (state.getType() != VolcanoLavaType.LITE) continue;

            Block block = entry.getKey();
            if (!lavaFlow.shouldUseLiteMode(block)) {
                liteToConvert.add(entry);
            }
        }

        for (Map.Entry<Block, VolcanoLavaFluidState> entry : liteToConvert) {
            convertLiteToNormal(entry.getKey(), entry.getValue());
        }
    }

    private void convertNormalToLite(Block block, VolcanoLavaCoolData coolData) {
        // Remove from normal lava tracking
        Map<Block, VolcanoLavaCoolData> chunkMap = lavaFlow.lavaCools.get(block.getChunk());
        if (chunkMap != null) {
            chunkMap.remove(block);
        }
        Map<Block, VolcanoLavaCoolData> cachedChunkMap = lavaFlow.cachedCools.get(block.getChunk());
        if (cachedChunkMap != null) {
            cachedChunkMap.remove(block);
        }
        lavaFlow.removeFlowEndBlock(block);

        // Create LITE state from cool data
        VolcanoLavaFluidState state = VolcanoLavaFluidState.fromCoolData(coolData);

        // Replace LAVA with MAGMA_BLOCK
        lavaFlow.queueBlockUpdate(block, Material.MAGMA_BLOCK);

        // Register into the fake fluid engine
        registerDirect(block, state);
    }

    private void convertLiteToNormal(Block block, VolcanoLavaFluidState state) {
        // Remove from fake fluid engine
        remove(block);

        // Convert state back to cool data
        int ticks = 30 * lavaFlow.settings.flowed;
        VolcanoLavaCoolData coolData = state.toCoolData(block, ticks);

        // Replace MAGMA_BLOCK with real LAVA
        TyphonBlocks.setBlockType(block, Material.LAVA);

        // Force-load the chunk for physics
        lavaFlow.vent.getVolcano().chunkLoader.add(block.getChunk());

        // Register into normal cooldown tracking
        lavaFlow.registerToCacheCools(block, coolData);
        lavaFlow.addFlowEndBlock(block, false);
    }
}
