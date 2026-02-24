package me.alex4386.plugin.typhon.volcano.lavaflow;

import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import org.bukkit.Material;
import org.bukkit.block.Block;

/**
 * Uniform data access interface for all lava flow modes.
 * NORMAL reads from Minecraft block data; LITE/PILLOW reads from in-memory state.
 */
public interface VolcanoLavaFluidAdapter {
    VolcanoLavaType getType();

    int getFluidLevel();
    Block getSourceBlock();
    Block getFromBlock();
    int getExtensionCount();
    Material getMaterial();
    boolean isBomb();
    boolean isUnderfill();
    boolean skipNormalLavaFlowLengthCheck();
    int getEjectaRecordIdx();
    VolcanoVent getVent();

    int getFlowLimit();
}
