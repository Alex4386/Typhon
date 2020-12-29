package me.alex4386.plugin.typhon.volcano.utils;

import me.alex4386.plugin.typhon.TyphonUtils;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.LinkedHashMap;
import java.util.Map;

public class VolcanoConstructionRaiseData extends VolcanoConstructionData {
    Block baseBlock;
    int raiseAmount;
    public Material replacementMaterial;

    VolcanoConstructionRaiseData(Block baseBlock, int raiseAmount, Material replacement) {
        this.baseBlock = baseBlock;
        this.raiseAmount = raiseAmount;
        this.replacementMaterial = replacement;
    }

    @Override
    public Map<Block, Block> getConstructionData() {
        Map<Block, Block> blockUpdates = new LinkedHashMap<>();

        int x = baseBlock.getX();
        int z = baseBlock.getZ();

        int surfaceY = TyphonUtils.getHighestOceanFloor(this.baseBlock.getLocation()).getBlockY();
        int bedrockY = TyphonUtils.getLowestBedrockCeiling(this.baseBlock.getLocation()).getBlockY();
        int blockY = baseBlock.getY();

        World world = baseBlock.getWorld();

        if (this.raiseAmount > 0) {
            for (int i = surfaceY; i >= blockY; i--) {
                Block sourceBlock = world.getBlockAt(x, i, z);
                Block destinationBlock = sourceBlock.getRelative(0, this.raiseAmount, 0);

                blockUpdates.put(sourceBlock, destinationBlock);
            }
        } else if (this.raiseAmount < 0) {
            for (int i = blockY - this.raiseAmount; i <= blockY; i--) {
                Block sourceBlock = world.getBlockAt(x, i, z);
                Block destinationBlock = sourceBlock.getRelative(0, this.raiseAmount, 0);

                if (destinationBlock.getY() <= bedrockY) continue;
                blockUpdates.put(sourceBlock, destinationBlock);
            }
        } else {}

        return blockUpdates;
    }
}

