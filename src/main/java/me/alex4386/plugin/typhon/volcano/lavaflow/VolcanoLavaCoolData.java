package me.alex4386.plugin.typhon.volcano.lavaflow;

import me.alex4386.plugin.typhon.volcano.crater.VolcanoCrater;
import org.bukkit.Material;
import org.bukkit.block.Block;

public class VolcanoLavaCoolData {
    public int ticks;
    public Block block;
    public Material material;
    public VolcanoCrater flowedFromCrater = null;

    public VolcanoLavaCoolData(Block block, VolcanoCrater flowedFromCrater, Material material, int ticks) {
        this.ticks = ticks;
        this.block = block;
        this.material = material;
        this.flowedFromCrater = flowedFromCrater;
    }

    public VolcanoLavaCoolData(Block block, Material material, int ticks) {
        this.ticks = ticks;
        this.block = block;
        this.material = material;
    }

    public void tickPass() {
        if (this.tickPassed()) { this.coolDown(); }
        else { this.ticks--; }
    }

    public boolean tickPassed() {
        return this.ticks <= 0;
    }

    public void coolDown() {
        block.setType(material);
    }

    public void forceCoolDown() {
        this.ticks = 0;
        block.setType(material);
    }
}
