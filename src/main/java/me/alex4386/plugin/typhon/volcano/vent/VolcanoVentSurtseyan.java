package me.alex4386.plugin.typhon.volcano.vent;

import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.bomb.VolcanoBomb;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

public class VolcanoVentSurtseyan {
    VolcanoVent vent;

    int surtseyanRange = 5;

    public VolcanoVentSurtseyan(VolcanoVent vent) {
        this.vent = vent;
    }

    public boolean isBlockInSurtseyanRange(Block block) {
        if (block.getY() <= block.getWorld().getSeaLevel() && block.getY() >= block.getWorld().getSeaLevel() - surtseyanRange) {
            if (TyphonUtils.containsLiquidWater(block.getRelative(BlockFace.UP))) {
                return true;
            }
        }

        return false;
    }


    public boolean isCraterFilledWithSeaWater() {
        Block summitBlock = this.vent.getSummitBlock();
        Block lowestCoreOceanFloor = TyphonUtils.getHighestOceanFloor(this.vent.getLowestCoreBlock().getLocation()).getBlock();

        boolean summitIsIsland = summitBlock.getY() >= summitBlock.getWorld().getSeaLevel();
        boolean craterUnderWater = lowestCoreOceanFloor.getY() <= summitBlock.getWorld().getSeaLevel();

        boolean hasSeaWaterAbove = TyphonUtils.containsLiquidWater(lowestCoreOceanFloor.getRelative(BlockFace.UP));
        return summitIsIsland && craterUnderWater && hasSeaWaterAbove;
    }

    public boolean isSurtseyan() {
        Block summitBlock = this.vent.getSummitBlock();

        if (this.isBlockInSurtseyanRange(summitBlock) || this.isCraterFilledWithSeaWater()) {
            return true;
        }

        return false;
    }

    // ???
    public boolean shouldRunSurseyan() {
        for (Block block : this.vent.getCoreBlocks()) {
            Block highest = block.getWorld().getHighestBlockAt(block.getLocation());
            Block highestRock = TyphonUtils.getHighestRocklikes(block.getLocation());

            if (highestRock.getY() >= highest.getY() - 10) {
                if (block.getType() == Material.WATER && highest.getY() <= block.getWorld().getSeaLevel()) {
                    return true;
                }
            }
        }
        return false;
    }

    public void eruptSurtseyan(int count) {
        for (int i = 0; i < count; i++) {
            VolcanoBomb bomb = this.vent.bombs.generateConeBuildingBomb();
            if (bomb == null) bomb = this.vent.bombs.generateRandomBomb(this.vent.bombs.getLaunchLocation());

            this.vent.bombs.launchSpecifiedBomb(bomb);
        }
    }
}
