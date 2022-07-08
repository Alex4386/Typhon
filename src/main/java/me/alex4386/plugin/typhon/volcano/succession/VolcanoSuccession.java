package me.alex4386.plugin.typhon.volcano.succession;

import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentStatus;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

public class VolcanoSuccession {
    // Implements Primary Succession
    Volcano volcano;
    
    public VolcanoSuccession(Volcano volcano) {
        this.volcano = volcano;
    }

    public int successionScheduleId = -1;

    public void initialize() {
        this.registerTask();
    }

    public void shutdown() {
        this.unregisterTask();
    }

    public void registerTask() {
        if (successionScheduleId < 0) {
            successionScheduleId = Bukkit.getScheduler()
                .scheduleSyncRepeatingTask(
                    TyphonPlugin.plugin,
                    (Runnable) () -> {
                        this.runSuccessionCycle();
                    },
                    0L,
                    (long) TyphonPlugin.minecraftTicksPerSeconds);
        }
    }

    public void unregisterTask() {
        if (successionScheduleId >= 0) {
            Bukkit.getScheduler().cancelTask(successionScheduleId);
            successionScheduleId = -1;
        }
    }

    public void runSuccessionCycle() {
        for (VolcanoVent vent : this.volcano.manager.getVents()) {
            runSuccessionCycle(vent);
        }
    }

    public void runSuccessionCycle(VolcanoVent vent) {
        int successionCount = 0;
        if (vent.status == VolcanoVentStatus.ERUPTING) {
            successionCount = (int) (Math.random() * 2);
        } else if (Math.random() > vent.status.getScaleFactor()) {
            successionCount = (int) ((1.0 - vent.status.getScaleFactor()) * 200 * Math.random());
        }

        for (int i = 0; i < successionCount; i++) {
            runSuccession(vent);
        }
    }

    public void runSuccession(VolcanoVent vent) {
        Block coreBlock = vent.getCoreBlock();
        double longestFlow = vent.longestFlowLength;

        double skipZone = (vent.getType() == VolcanoVentType.CRATER ? vent.craterRadius : 0);
        double random = ((longestFlow - skipZone) * Math.random()) + skipZone;

        double x = Math.sin(Math.random() * Math.PI * 2) * random;
        double z = Math.cos(Math.random() * Math.PI * 2) * random;

        Block block = coreBlock.getRelative((int) x, 0, (int) z);
        runSuccession(block);
    }

    public void runSuccession(Block block) {
        Block targetBlock = TyphonUtils.getHighestRocklikes(block);
        double heatValue = this.volcano.manager.getHeatValue(block.getLocation());

        double heatValueThreshold = 0.8;
        if (heatValue < heatValueThreshold) {
            double probability = 1.0;
            
            if (heatValue > 0.4) {
                // since volcano is hot. probability scales down.
                probability = (probability - 0.4) / (heatValueThreshold - 0.4);

                if (Math.random() > probability) {
                    return;
                }
            }

            // stage 3. is grass?
            boolean isGrass = targetBlock.getType() == Material.GRASS_BLOCK || targetBlock.getType() == Material.DIRT;
            if (isGrass) {
                // let me run some randoms.

                if (Math.random() < 0.2) {
                    // check if creating tree is an available option
                    boolean notAvailableForTree = isObstructedByTree(targetBlock);

                    // tree can not grow if heatValue is high enough,
                    // in that case, grass should be generated instead.
                    if (heatValue > 0.6) {
                        targetBlock.applyBoneMeal(BlockFace.UP);
                        return;
                    }

                    if (!notAvailableForTree) {
                        createTree(targetBlock);
                    }    
                    
                    return;
                } 

                if (Math.random() < 0.6) {
                    spreadSoil(targetBlock);
                }

                return;
            }

            // Stage 2. check is cobbled.
            boolean isCobblestone = isTypeOfCobblestone(targetBlock.getType()) || targetBlock.getType() == Material.TUFF;
            if (isCobblestone) {
                double random = Math.random();

                if (random < 0.4) {
                    runSoilGeneration(targetBlock);
                }
                return;
            }

            // Stage 1. just randomly change into cobblestone
            if (Math.random() < 0.1) {
                if (targetBlock.getType() == Material.DEEPSLATE || targetBlock.getType().name().toLowerCase().contains("basalt")) {
                    targetBlock.setType(Material.COBBLED_DEEPSLATE);
                } else {
                    targetBlock.setType(Material.COBBLESTONE);
                }
            }
        }
    }

    public boolean isTypeOfCobblestone(Material material) {
        switch (material) {
            case COBBLED_DEEPSLATE:
            case COBBLED_DEEPSLATE_SLAB:
            case COBBLED_DEEPSLATE_STAIRS:
            case COBBLED_DEEPSLATE_WALL:
            case COBBLESTONE:
            case COBBLESTONE_SLAB:
            case COBBLESTONE_STAIRS:
            case COBBLESTONE_WALL:
                return true;
        }

        return false;
    }

    public void spreadSoil(Block block) {
        int spreadRange = (int) (Math.random() * 3) + 2;
        spreadSoil(block, spreadRange, false);
    }

    public void spreadSoil(Block block, int spreadRange, boolean withExtension) {
        int extension = (withExtension ? spreadRange / 3 : 0);
        List<Block> treeRange = VolcanoMath.getCircle(block, spreadRange + extension);

        for (Block rockRange : treeRange) {
            double distance = TyphonUtils.getTwoDimensionalDistance(
                rockRange.getLocation(),
                block.getLocation()
            );
            double probability = 1.0;

            if (distance > spreadRange) {
                probability = (distance - spreadRange) / extension;
            }

            if (probability == 1.0 || Math.random() < probability) {
                runSoilGeneration(rockRange);
            }
        }

    }

    public boolean runSoilGeneration(Block block) {
        return runSoilGeneration(block, true);
    }

    public boolean runSoilGeneration(Block block, boolean isSurface) {
        Block surfaceBlock = TyphonUtils.getHighestRocklikes(block);
        Block rockBlock = isSurface ? surfaceBlock : block;

        double heatValue = this.volcano.manager.getHeatValue(block.getLocation());
            
        if (heatValue > 0.7) {
            // since volcano is hot. probability scales down.
            double probability = (heatValue - 0.7) / 0.2;

            if (Math.random() > probability) {
                return false;
            }
        }

        if (surfaceBlock.getY() - block.getY() > 3) return true;

        if (rockBlock.getType() != Material.GRASS_BLOCK && rockBlock.getType() != Material.DIRT) {
            if (surfaceBlock.getY() == block.getY()) {
                rockBlock.setType(Material.GRASS_BLOCK);
            } else {
                rockBlock.setType(Material.DIRT);
            }
        } else {
            Block underlyingSoil = rockBlock.getRelative(BlockFace.DOWN);
            runSoilGeneration(underlyingSoil, false);
            return true;
        }
        return true;
    }

    public boolean isObstructedByTree(Block block) {
        Block treeBlock = TyphonUtils.getHighestNonTreeSolid(block);
        Block rockBlock = TyphonUtils.getHighestRocklikes(block);

        if (treeBlock.getY() > rockBlock.getY()) {
            return true;
        }

        return false;
    }

    public TreeType randomTreeType() {
        TreeType[] adequateTreeTypes = {
            TreeType.ACACIA,
            TreeType.TREE,
            TreeType.BIRCH,
            TreeType.REDWOOD,
            TreeType.DARK_OAK,
            TreeType.SMALL_JUNGLE,
        };

        TreeType type = adequateTreeTypes[(int) (Math.random() * adequateTreeTypes.length)];

        if (Math.random() < 0.05) {
            type = getBiggerEquivalent(type);
        }

        return type;
    }

    public TreeType getBiggerEquivalent(TreeType type) {
        switch (type) {
            case BIRCH:
                return TreeType.TALL_BIRCH;
            case REDWOOD:
                return TreeType.TALL_REDWOOD;
            case SMALL_JUNGLE:
                return TreeType.JUNGLE;
            case TREE:
            default:
                return TreeType.BIG_TREE;
        }
    }

    public void createTree(Block block) {
        Block rockBlock = TyphonUtils.getHighestRocklikes(block);
        if (isObstructedByTree(rockBlock)) return;

        block.getWorld().generateTree(block.getLocation(), randomTreeType());
    }

}
