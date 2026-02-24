package me.alex4386.plugin.typhon.volcano.lavaflow;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;

import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;

public class VolcanoLavaFluidState implements VolcanoLavaFluidAdapter {
    VolcanoLavaType type;

    int extensionCount;
    public int ejectaRecordIdx = -1;
    VolcanoVent vent;

    Block sourceBlock;
    Block fromBlock;

    int fluidLevel = 8;

    int cooldownTick = 5;
    boolean markAsFlowed = false;

    // fields for LITE mode (propagated from VolcanoLavaCoolData during conversion)
    Material material;
    boolean isBomb = false;
    boolean skipNormalLavaFlowLengthCheck = false;
    boolean isUnderfill = false;

    VolcanoLavaFluidState(VolcanoVent vent, Block sourceBlock) {
        this(vent, sourceBlock, sourceBlock);
    }

    VolcanoLavaFluidState(VolcanoVent vent, Block sourceBlock, Block fromBlock) {
        this(vent, sourceBlock, fromBlock, 0);

        this.cooldownTick += (int) (Math.random() * 3) - 1;
    }

    VolcanoLavaFluidState(VolcanoVent vent, Block source, Block fromBlock2, int extensionCount) {
        this.type = VolcanoLavaType.PILLOW;
        this.vent = vent;
        this.sourceBlock = source;
        this.fromBlock = fromBlock2;

        this.extensionCount = extensionCount;
    }

    @Override public VolcanoLavaType getType() { return type; }

    // ==========================================
    // VolcanoLavaFluidAdapter implementation
    // ==========================================

    @Override public int getFluidLevel() { return fluidLevel; }
    @Override public Block getSourceBlock() { return sourceBlock; }
    @Override public Block getFromBlock() { return fromBlock; }
    @Override public int getExtensionCount() { return extensionCount; }
    @Override public Material getMaterial() { return material; }
    @Override public boolean isBomb() { return isBomb; }
    @Override public boolean isUnderfill() { return isUnderfill; }
    @Override public boolean skipNormalLavaFlowLengthCheck() { return skipNormalLavaFlowLengthCheck; }
    @Override public int getEjectaRecordIdx() { return ejectaRecordIdx; }
    @Override public VolcanoVent getVent() { return vent; }
    @Override public int getFlowLimit() { return -1; }

    public void runTick() {
        if (cooldownTick > 0) {
            cooldownTick--;
        }
    }

    public boolean hasFlowed() {
        return markAsFlowed;
    }

    public void markAsFlowed() {
        markAsFlowed = true;
    }

    public boolean canCooldown() {
        return cooldownTick <= 0;
    }

    /**
     * Create a LITE fluid state from a VolcanoLavaCoolData (NORMAL → LITE conversion).
     */
    public static VolcanoLavaFluidState fromCoolData(VolcanoLavaCoolData data) {
        VolcanoLavaFluidState state = new VolcanoLavaFluidState(
                data.flowedFromVent, data.source, data.fromBlock, data.runExtensionCount);
        state.type = VolcanoLavaType.LITE;
        state.ejectaRecordIdx = data.ejectaRecordIdx;
        state.material = data.material;
        state.isBomb = data.isBomb;
        state.skipNormalLavaFlowLengthCheck = data.skipNormalLavaFlowLengthCheck;
        state.isUnderfill = data.isUnderfill;

        // extract fluid level from the LAVA block's Levelled data
        BlockData bd = data.block.getBlockData();
        if (bd instanceof Levelled levelled) {
            int rawLevel = levelled.getLevel();
            state.fluidLevel = rawLevel >= 8 ? 8 : (8 - rawLevel);
        } else {
            state.fluidLevel = 8;
        }

        state.cooldownTick = 0; // already past cooldown if it was normal lava
        return state;
    }

    /**
     * Convert this LITE fluid state back to a VolcanoLavaCoolData (LITE → NORMAL conversion).
     */
    public VolcanoLavaCoolData toCoolData(Block block, int ticks) {
        VolcanoLavaCoolData coolData = new VolcanoLavaCoolData(
                this.sourceBlock,
                this.fromBlock,
                block,
                this.vent,
                this.material,
                ticks,
                this.isBomb,
                this.extensionCount);
        coolData.ejectaRecordIdx = this.ejectaRecordIdx;
        coolData.skipNormalLavaFlowLengthCheck = this.skipNormalLavaFlowLengthCheck;
        coolData.isUnderfill = this.isUnderfill;
        return coolData;
    }
}
