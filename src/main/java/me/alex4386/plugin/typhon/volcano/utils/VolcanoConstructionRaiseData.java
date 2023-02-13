package me.alex4386.plugin.typhon.volcano.utils;

import me.alex4386.plugin.typhon.TyphonUtils;

import org.bukkit.*;
import org.bukkit.block.*;

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
        int bedrockY =
                TyphonUtils.getHighestBedrock(this.baseBlock.getLocation()).getBlockY();
        int blockY = baseBlock.getY();

        World world = baseBlock.getWorld();
        /*

                // TODO: This should be refactored.

                if (this.raiseAmount > 0) {
                    for (int i = surfaceY; i >= blockY; i--) {
                        Block sourceBlock = world.getBlockAt(x, i, z);
                        Block destinationBlock = sourceBlock.getRelative(0, this.raiseAmount, 0);
                        Block fakeBlock = ;

                        blockUpdates.put(sourceBlock, destinationBlock);
                        blockUpdates.put(fakeBlock, sourceBlock);
                    }
                } else if (this.raiseAmount < 0) {
                    for (int i = blockY; i >= bedrockY; i--) {
                        Block sourceBlock = world.getBlockAt(x, i, z);
                        Block destinationBlock = sourceBlock.getRelative(0, this.raiseAmount, 0);
                        Block fakeBlock = TyphonUtils.createFakeBlock(replacementMaterial);

                        if (destinationBlock.getY() <= bedrockY) continue;
                        blockUpdates.put(sourceBlock, destinationBlock);
                        blockUpdates.put(fakeBlock, sourceBlock);
                    }
                } else {}
        */

        return blockUpdates;
    }

    @Override
    public Map<Block, Material> getConstructionMaterialUpdateData() {
        Map<Block, Material> blockUpdates = new LinkedHashMap<>();

        World world = this.baseBlock.getWorld();

        int x = baseBlock.getX();
        int y = baseBlock.getY();
        int z = baseBlock.getZ();

        if (this.raiseAmount > 0) {
            for (int i = 0; i < raiseAmount; i++) {
                Block sourceBlock = world.getBlockAt(x, y + i, z);
                blockUpdates.put(sourceBlock, Material.LAVA);
            }
        } else if (this.raiseAmount < 0) {
            for (int i = 0; i >= -raiseAmount; i--) {
                Block sourceBlock = world.getBlockAt(x, y + i, z);
                blockUpdates.put(sourceBlock, Material.LAVA);
            }
        } else {
        }

        return blockUpdates;
    }
}
