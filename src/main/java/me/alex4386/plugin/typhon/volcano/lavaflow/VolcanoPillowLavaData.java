package me.alex4386.plugin.typhon.volcano.lavaflow;

import org.bukkit.block.Block;

import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;

public class VolcanoPillowLavaData {
    int extensionCount;
    VolcanoVent vent;

    Block sourceBlock;
    Block fromBlock;

    int fluidLevel = 8;

    VolcanoPillowLavaData(VolcanoVent vent, Block sourceBlock) {
        this(vent, sourceBlock, sourceBlock);
    }

    VolcanoPillowLavaData(VolcanoVent vent, Block sourceBlock, Block fromBlock) {
        this(
                vent,
                sourceBlock,
                fromBlock,
                (int) (VolcanoLavaCoolData.calculateExtensionCount(vent.lavaFlow.settings.silicateLevel) / (8 + (Math.random() * 2)))
            );
    }

    VolcanoPillowLavaData(VolcanoVent vent, Block source, Block fromBlock2, int extensionCount) {
        this.vent = vent;
        this.sourceBlock = source;
        this.fromBlock = fromBlock2;

        this.extensionCount = extensionCount;
    }
}
