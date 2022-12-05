package me.alex4386.plugin.typhon.volcano.lavaflow;

import me.alex4386.plugin.typhon.TyphonUtils;
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
        this(vent, sourceBlock, fromBlock, 1);

        int extCount = VolcanoLavaCoolData.calculateExtensionCount(
                vent.lavaFlow.settings.silicateLevel,
                TyphonUtils.getTwoDimensionalDistance(sourceBlock.getLocation(), fromBlock.getLocation()),
                Math.max(5, vent.getSummitBlock().getY() - vent.location.getY())
        );
        extCount = pillowifyExtensionCount(extCount);

        this.extensionCount = extCount;
    }

    VolcanoPillowLavaData(VolcanoVent vent, Block source, Block fromBlock2, int extensionCount) {
        this.vent = vent;
        this.sourceBlock = source;
        this.fromBlock = fromBlock2;

        this.extensionCount = extensionCount;
    }

    private static int pillowifyExtensionCount(int normalExtCount) {
        return normalExtCount * 0;
    }
}
