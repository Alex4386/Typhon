package me.alex4386.plugin.typhon.volcano.dome;

import me.alex4386.plugin.typhon.TyphonBlocks;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;

public class VolcanoLavaDomeLavaFlow {
    public boolean finished = false;

    public VolcanoLavaDome dome;

    public Block block;
    public Block targetBlock;

    public Vector direction;

    public Location currentLocation;
    public int flowTimer = 8;

    public int flatFlowLife = 8;

    public void resetFlowTimer() {
        this.flowTimer = 8;
    }

    public VolcanoLavaDomeLavaFlow(VolcanoLavaDome dome, Block block, Block targetBlock) {
        this.dome = dome;
        this.block = block;

        this.currentLocation = block.getLocation();
        this.targetBlock = targetBlock;

        this.direction = this.targetBlock.getLocation().toVector().subtract(this.currentLocation.toVector()).setY(0).normalize();
    }

    private void resetFlatFlowLife() {
        this.flatFlowLife = 8;
    }

    public Location getNext() {
        Block block = this.currentLocation.getBlock();

        // check if current block is floating.
        if (block.getRelative(0, -1, 0).getType().isAir()) {
            this.resetFlatFlowLife();
            return this.currentLocation.add(0, -1 ,0);
        }

        // return the block that is the direction to the target block.
        // but neighbors to current block.
        if (Math.random() < 0.01) {
            Vector targetDir = this.targetBlock.getLocation().toVector().subtract(this.currentLocation.toVector()).setY(0).normalize();
            direction.add(targetDir.multiply(0.01));
            direction.setY(0);
        }
        Location targetDirection = this.currentLocation.add(this.direction.clone().multiply(1.1));

        int xDiff = this.targetBlock.getX() - block.getX();
        int zDiff = this.targetBlock.getZ() - block.getZ();

        // at this point,
        // the block is not floating and has no diff on x and z.
        // which means, the block == targetBlock.
        if (xDiff == 0 && zDiff == 0) {
            return null;
        }

        this.flatFlowLife--;
        return targetDirection;
    }

    public Block getNextBlock() {
        Location next = this.getNext();
        if (next == null) return null;

        Block targetBlock = next.getBlock();
        if (!targetBlock.getType().isAir()) {
            // check if it is not MAGMA_BLOCK
            if (targetBlock.getType() != Material.MAGMA_BLOCK) {
                // we can't flow to here! stop!
                return null;
            }
        }

        return next.getBlock();
    }

    public void runTick() {
        if (this.flatFlowLife <= 0) {
            this.finished = true;
            this.coolDown();
        }

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
            // it might be flowing at there, but if it is nearby the effusion, don't stop the lava flow
            if (this.dome.baseLocation != null && TyphonUtils.getTwoDimensionalDistance(this.dome.baseLocation, this.currentLocation) > 10) {
                if (nextBlock.getType() == Material.MAGMA_BLOCK) {
                    // STOP!!!! - we should divert!!!
                    this.dome.vent.lavaFlow.queueBlockUpdate(
                            this.block,
                            Material.AIR
                    );
                    this.finished = true;
                    return;
                }
            }
            int y = this.block.getY();
            if (y > this.dome.getTargetYAt(this.block.getLocation())) {
                double distance = TyphonUtils.getTwoDimensionalDistance(this.dome.baseLocation, this.currentLocation);
                if (distance > this.dome.getTargetBasin() + Math.min(100, this.dome.getTargetBasin())) {
                    this.finished = true;
                    this.coolDown();
                    return;
                }

                this.dome.vent.lavaFlow.queueBlockUpdate(
                        this.block,
                        Material.AIR
                );
            } else {
                this.dome.plumbedLava++;
                this.dome.vent.lavaFlow.queueBlockUpdate(
                        this.block, VolcanoComposition.getExtrusiveRock(
                                this.dome.vent.lavaFlow.settings.silicateLevel
                        ));
            }
            this.block = nextBlock;
            this.dome.vent.lavaFlow.queueBlockUpdate(
                    this.block,
                    Material.MAGMA_BLOCK
            );
            this.dome.vent.lavaFlow.createLavaParticle(this.block);
        }
    }

    public void coolDown() {
        int y = this.block.getY();
        double targetY = this.dome.getTargetYAt(this.block.getLocation());

        Material material = null;

        if (y <= targetY) {
            if (this.block.getType() == Material.MAGMA_BLOCK) {
                this.dome.plumbedLava++;
            }

            material = VolcanoComposition.getExtrusiveRock(
                    this.dome.vent.lavaFlow.settings.silicateLevel
            );
        } else {
            if (this.block.getType() == Material.MAGMA_BLOCK) {
                material = Material.AIR;
            }
        }

        if (material != null) {
            TyphonBlocks.setBlockType(this.block, material);
            this.dome.vent.lavaFlow.queueBlockUpdate(this.block, material);
        }
    }

    public void forceCoolDown() {
        this.finished = true;
        this.coolDown();
    }



}
