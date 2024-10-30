package me.alex4386.plugin.typhon.volcano.utils;

import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.List;

public class VolcanoCircleOffsetXZ {
    public double x;
    public double z;

    public List<Block> blocks = new ArrayList<>();

    public VolcanoCircleOffsetXZ(double x, double z) {
        this.x = x;
        this.z = z;
    }

    public void addBlock(Block block) {
        blocks.add(block);
    }

    public List<Block> getBlocks() {
        return blocks;
    }
}
