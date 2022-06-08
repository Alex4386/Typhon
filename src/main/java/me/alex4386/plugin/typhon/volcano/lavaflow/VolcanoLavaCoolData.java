package me.alex4386.plugin.typhon.volcano.lavaflow;

import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;

public class VolcanoLavaCoolData {
    public int ticks;
    public Block block;
    public Block fromBlock;
    public Material material;
    public Block source;
    public VolcanoVent flowedFromVent = null;
    boolean isBomb = false;

    public int runExtensionCount = 0;
    public int fluidLevel = 8;

    public VolcanoLavaCoolData(
            Block source,
            Block fromBlock,
            Block block,
            VolcanoVent flowedFromVent,
            Material material) {
        if (source == null)
            this.source = block;
        else
            this.source = source;

        this.fromBlock = fromBlock;
        this.block = block;
        this.material = material;
        this.flowedFromVent = flowedFromVent;
    }

    public VolcanoLavaCoolData(
            Block source,
            Block fromBlock,
            Block block,
            VolcanoVent flowedFromVent,
            Material material,
            boolean isBomb) {
        this(source, fromBlock, block, flowedFromVent, material);
        this.isBomb = isBomb;

        this.runExtensionCount = VolcanoLavaCoolData.calculateExtensionCount(
                this.flowedFromVent.lavaFlow.settings.silicateLevel);
        
        if (this.isBomb) {
            this.runExtensionCount = 0;

            if (Math.random() < 0.25) {
                this.runExtensionCount = 1;
            }
        }
    }

    public VolcanoLavaCoolData(
            Block source,
            Block fromBlock,
            Block block,
            VolcanoVent flowedFromVent,
            Material material,
            boolean isBomb,
            int runExtensionCount) {
        this(source, fromBlock, block, flowedFromVent, material, isBomb);

        this.flowedFromVent = flowedFromVent;
        this.runExtensionCount = runExtensionCount;
    }

    public static int calculateExtensionCount(double silicateLevel) {
        // 0.48 is lower end. minimum travel distance should be 10km. 
        // but this is Minecraft. 10000 blocks is way too much. scaling down

        double value = silicateLevel < 0.68
            ? (int) Math.floor(
                Math.min(
                    Math.max(
                        Math.floor((0.68 - silicateLevel) * 100) / 5,
                        0.0
                    ), 4.0
                )
            ) : 0;

        double probability = value - Math.floor(value);
        int extra = Math.random() < probability ? 1 : 0;

        return (int) Math.floor(value) + extra;
    }
}
