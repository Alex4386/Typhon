package me.alex4386.plugin.typhon.volcano.utils;

import me.alex4386.plugin.typhon.TyphonUtils;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
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
                Block fakeBlock = TyphonUtils.createFakeBlock(replacementMaterial);

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

        return blockUpdates;
    }
}

