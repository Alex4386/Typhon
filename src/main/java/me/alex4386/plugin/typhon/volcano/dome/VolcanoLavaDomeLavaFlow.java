package me.alex4386.plugin.typhon.volcano.dome;

import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import org.bukkit.Material;
import org.bukkit.block.Block;

public class VolcanoLavaDomeLavaFlow {
    public boolean finished = false;

    public VolcanoLavaDome dome;
    public Block block;

    public Block targetBlock;
    public int flowTimer = 8;

    public void resetFlowTimer() {
        this.flowTimer = 8;
    }

    public VolcanoLavaDomeLavaFlow(VolcanoLavaDome dome, Block block, Block targetBlock) {
        this.dome = dome;
        this.block = block;
        this.targetBlock = targetBlock;
    }

    public Block getNextBlock() {
        // check if current block is floating.
        if (this.block.getRelative(0, -1, 0).getType().isAir()) {
            return this.block.getRelative(0, -1, 0);
        }

        // return the block that is the direction to the target block.
        // but neighbors to current block.

        int xDiff = this.targetBlock.getX() - this.block.getX();
        int zDiff = this.targetBlock.getZ() - this.block.getZ();

        if (xDiff == 0 && zDiff == 0) {
            return null;
        }

        if (Math.abs(xDiff) > Math.abs(zDiff)) {
            if (xDiff > 0) {
                return this.block.getRelative(1, 0, 0);
            } else {
                return this.block.getRelative(-1, 0, 0);
            }
        } else {
            if (zDiff > 0) {
                return this.block.getRelative(0, 0, 1);
            } else {
                return this.block.getRelative(0, 0, -1);
            }
        }

        // at this point,
        // the block is not floating and has no diff on x and z.
        // which means, the block == targetBlock.
    }

    public void runTick() {
        if (this.finished) return;

        if (this.flowTimer <= 0) {
            this.flowNext();
            this.resetFlowTimer();
        } else {
            this.flowTimer--;
        }
    }

    public void flowNext() {
        Block nextBlock = this.getNextBlock();

        if (nextBlock == null) {
            this.finished = true;
            this.coolDown();
        } else {
            this.block.setType(Material.AIR);
            this.block = nextBlock;
            this.block.setType(Material.MAGMA_BLOCK);
            this.dome.vent.lavaFlow.createLavaParticle(this.block);
        }
    }

    public void coolDown() {
        int y = this.block.getY();
        if (y <= this.dome.getTargetYAt(this.block.getLocation())) {
            this.block.setType(VolcanoComposition.getExtrusiveRock(
                    this.dome.vent.lavaFlow.settings.silicateLevel
            ));
        } else {
            if (this.block.getType() == Material.MAGMA_BLOCK)
                this.block.setType(Material.AIR);
        }
    }



}
