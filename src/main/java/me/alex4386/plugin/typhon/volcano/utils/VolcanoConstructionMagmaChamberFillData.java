package me.alex4386.plugin.typhon.volcano.utils;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.LinkedHashMap;
import java.util.Map;

public class VolcanoConstructionMagmaChamberFillData extends VolcanoConstructionData {
    Block baseBlock;
    int magmaHeight;
    public Material magmaMaterial;

    VolcanoConstructionMagmaChamberFillData(Block baseBlock, int magmaHeight, Material magma) {
        this.baseBlock = baseBlock;
        this.magmaHeight = magmaHeight;
        this.magmaMaterial = magma;
    }

    @Override
    public Map<Block, Block> getConstructionData() {
        Map<Block, Block> blockUpdates = new LinkedHashMap<>();

        return blockUpdates;
    }

    @Override
    public Map<Block, Material> getConstructionMaterialUpdateData() {
        Map<Block, Material> blockUpdates = new LinkedHashMap<>();

        World world = this.baseBlock.getWorld();

        int x = baseBlock.getX();
        int y = baseBlock.getY();
        int z = baseBlock.getZ();

        if (this.magmaHeight > 0) {
            for (int i = 0; i < magmaHeight; i++) {
                Block sourceBlock = world.getBlockAt(x, y + i, z);
                blockUpdates.put(sourceBlock, Material.LAVA);
            }
        } else if (this.magmaHeight < 0) {
            for (int i = 0; i >= -magmaHeight; i--) {
                Block sourceBlock = world.getBlockAt(x, y + i, z);
                blockUpdates.put(sourceBlock, Material.LAVA);
            }
        } else {
        }

        return blockUpdates;
    }
}
