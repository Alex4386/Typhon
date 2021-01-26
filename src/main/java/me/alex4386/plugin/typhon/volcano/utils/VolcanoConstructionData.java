package me.alex4386.plugin.typhon.volcano.utils;

import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.Map;

public abstract class VolcanoConstructionData {
    abstract Map<Block, Block> getConstructionData();
    abstract Map<Block, Material> getConstructionMaterialUpdateData();
}

