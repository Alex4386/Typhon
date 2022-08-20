package me.alex4386.plugin.typhon.volcano.succession;

import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.json.simple.JSONObject;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentStatus;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

public class VolcanoSuccession {
    // Implements Primary Succession
    Volcano volcano;
    boolean isEnabled = true;
    
    public VolcanoSuccession(Volcano volcano) {
        this.volcano = volcano;
    }

    public int successionScheduleId = -1;

    public void initialize() {
        this.volcano.logger.log(
            VolcanoLogClass.SUCCESSION,
            "Registering VolcanoSuccession for Primary Succession");
        this.registerTask();
    }

    public void shutdown() {
        this.volcano.logger.log(
            VolcanoLogClass.SUCCESSION,
            "Unregistering VolcanoSuccession for Primary Succession");
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
        if (this.isEnabled) {
            for (VolcanoVent vent : this.volcano.manager.getVents()) {
                runSuccessionCycle(vent);
            }
        }
    }

    public void runSuccessionCycle(VolcanoVent vent) {
        int successionCount = 0;
        if (vent.status == VolcanoVentStatus.ERUPTING) {
            successionCount = (int) (Math.random() * 2);
        } else if (Math.random() > vent.status.getScaleFactor()) {
            double count = vent.longestNormalLavaFlowLength * Math.PI * 2;
            successionCount = (int) ((1.0 - vent.status.getScaleFactor()) * Math.min(2000, count / 15) * Math.random());
        }

        for (int i = 0; i < successionCount; i++) {
            runSuccession(vent);
        }
    }

    public void runSuccession(VolcanoVent vent) {
        Block coreBlock = vent.getCoreBlock();
        double longestFlow = vent.longestNormalLavaFlowLength;

        if (Math.random() < 0.2) {
            longestFlow = Math.max(vent.longestNormalLavaFlowLength, vent.bombs.maxDistance);
        }

        double skipZone = (vent.getType() == VolcanoVentType.CRATER ? vent.craterRadius : 0);
        if (skipZone > 0) {
            if (vent.status.getScaleFactor() < 0.1) {
                if (Math.random() < 0.5) {
                    skipZone = 0;
                }
            }
        }
        
        double random = ((longestFlow - skipZone) * Math.random()) + skipZone;

        double angle = Math.random() * Math.PI * 2;
        double x = Math.sin(angle) * random;
        double z = Math.cos(angle) * random;

        Block block = coreBlock.getRelative((int) x, 0, (int) z);
        runSuccession(block);
    }

    public boolean shouldCheckHeat(Block block) {
        boolean shouldCheckHeat = false;
        VolcanoVent vent = volcano.manager.getNearestVent(block);
        if (vent != null) {
            if (vent.status.getScaleFactor() > 0.1) {
                shouldCheckHeat = true;
            }
        }

        return shouldCheckHeat;
    }

    public void runSuccession(Block block) {
        boolean isDebug = false;

        Block targetBlock = TyphonUtils.getHighestRocklikes(block);
        double heatValue = this.volcano.manager.getHeatValue(block.getLocation());

        double heatValueThreshold = 0.7;
        if (!block.getWorld().isClearWeather()) {
            heatValueThreshold = 0.8 + (0.1 * Math.random());
        }

        if (targetBlock.getY() < block.getWorld().getSeaLevel()) return;

        if (heatValue < heatValueThreshold && !this.volcano.manager.isInAnyVent(block)) {
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
                double growProbability = 0.2;
                if (!block.getWorld().isClearWeather()) growProbability += 0.3;

                if (Math.random() < growProbability) {
                    boolean treeGenerated = createTree(targetBlock);
                    if (isDebug) this.volcano.logger.log(
                        VolcanoLogClass.SUCCESSION,
                        "Creating Tree on block "+TyphonUtils.blockLocationTostring(block)+" / result: "+treeGenerated);

                    if (treeGenerated) {
                        return;
                    }

                    // tree can not grow if heatValue is high enough,
                    // in that case, grass should be generated instead.
                    if (shouldCheckHeat(block)) {
                        if (heatValue > 0.9) {

                        } else if (heatValue > 0.6) {
                            targetBlock.applyBoneMeal(BlockFace.UP);
                            spreadSoil(targetBlock);
                            return;
                        }
                    }
                } 

                if (Math.random() < 0.6) {
                    spreadSoil(targetBlock);
                }

                return;
            }

            // Stage 2. check is cobbled.
            boolean isEroded = isConsideredErodedRockType(targetBlock.getType());
            if (isEroded) {
                double random = Math.random();
                
                double soilGenerationProb = 0.3;
                if (!block.getWorld().isClearWeather()) soilGenerationProb += 0.3;

                if (random < soilGenerationProb) {
                    if (isDebug) this.volcano.logger.log(
                            VolcanoLogClass.SUCCESSION,
                            "Creating Soil on block "+TyphonUtils.blockLocationTostring(block));

                    runSoilGeneration(targetBlock);
                }
                return;
            }

                            
            double erodeProb = 0.05;
            if (!block.getWorld().isClearWeather()) erodeProb += 0.1;

            // Stage 1. just randomly change into cobblestone
            if (Math.random() < erodeProb) {
                if (targetBlock.getType() == Material.DEEPSLATE || targetBlock.getType() == Material.BLACKSTONE) {
                    targetBlock.setType(Material.COBBLED_DEEPSLATE);
                } else if (isVolcanicRock(targetBlock.getType())) {
                    targetBlock.setType(Material.COBBLESTONE);
                } else { return; }

                if (isDebug) this.volcano.logger.log(
                    VolcanoLogClass.SUCCESSION,
                    "Eroding rock on block "+TyphonUtils.blockLocationTostring(block));
            }
        }
    }

    public boolean isVolcanicRock(Material material) {
        String materialName = material.name().toLowerCase();

        return (
            material == Material.STONE ||
            material == Material.DEEPSLATE ||
            material == Material.NETHERRACK ||
            material == Material.DIORITE || 
            material == Material.ANDESITE ||
            materialName.contains("ore") ||
            material == Material.MAGMA_BLOCK ||
            material == Material.OBSIDIAN ||
            materialName.contains("basalt") ||
            material == Material.ANCIENT_DEBRIS
        );
    }

    public boolean isNaturalized(Material material) {
        String materialName = material.name().toLowerCase();

        if (
            materialName.contains("dirt") ||
            materialName.contains("gravel") ||
            materialName.contains("sand")
        ) {
            return true;
        }

        return false;
    }

    public boolean isConsideredErodedRockType(Material material) {
        switch (material) {
            case COBBLED_DEEPSLATE:
            case COBBLED_DEEPSLATE_SLAB:
            case COBBLED_DEEPSLATE_STAIRS:
            case COBBLED_DEEPSLATE_WALL:
            case COBBLESTONE:
            case COBBLESTONE_SLAB:
            case COBBLESTONE_STAIRS:
            case COBBLESTONE_WALL:
            case NETHERRACK:
            case TUFF:
            case BASALT:
            case POLISHED_BASALT:
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
            
        if (heatValue > 0.7 && shouldCheckHeat(block)) {
            // since volcano is hot. probability scales down.
            double probability = (heatValue - 0.7) / 0.2;

            if (Math.random() > probability) {
                return false;
            }
        }
        
        if (this.volcano.manager.isInAnyVent(block)) {
            VolcanoVent vent = this.volcano.manager.getNearestVent(block);

            if (vent.status.getScaleFactor() < 0.1) {
                if (Math.random() < vent.status.getScaleFactor() * 10) {
                    return false;
                }
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
        Block treeBlock = TyphonUtils.getHighestLocation(block.getLocation()).getBlock();
        Block rockBlock = TyphonUtils.getHighestRocklikes(block);

        if (treeBlock.getType().name().contains("leaves")) {
            // tree.
            return true;
        }

        if (rockBlock.getY() < block.getWorld().getSeaLevel()) {
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

        /* 
        // do not spawn big trees
        if (Math.random() < 0.05) {
            type = getBiggerEquivalent(type);
        }
        */

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

    public boolean createTree(Block block) {
        boolean isDebug = false;

        Block rockBlock = TyphonUtils.getHighestRocklikes(block);
        if (isObstructedByTree(rockBlock)) {
            if (isDebug) this.volcano.logger.log(
                VolcanoLogClass.SUCCESSION,
                "Creating Tree failed on block "+TyphonUtils.blockLocationTostring(rockBlock)+" due to obstruction.");

            return false;
        }

        spreadSoil(block);

        int radius = 6;
        if (shouldCheckHeat(block)) {
            double heatValue = this.volcano.manager.getHeatValue(block.getLocation());
            
            if (heatValue > 0.8) {
                return false;
            }

            heatValue /= 0.8;
            radius = (int) Math.max(radius, radius + (Math.pow(heatValue, 2) * (radius * 2)));    
        }

        Block scanBaseBlock = rockBlock.getRelative(BlockFace.UP);
        List<Block> treeScan = VolcanoMath.getSphere(scanBaseBlock, radius);
        for (Block tree : treeScan) {
            String materialName = tree.getType().name().toLowerCase();

            if (materialName.contains("log") || materialName.contains("leaves")) {
                if (isDebug) this.volcano.logger.log(
                    VolcanoLogClass.SUCCESSION,
                    "Creating Tree failed on block "+TyphonUtils.blockLocationTostring(rockBlock)+" due to obstruction of nearby tree.");
                return false;
            }
        }

        List<Block> requirementToGrow = VolcanoMath.getCylinder(scanBaseBlock, 1, 3);
        for (Block toRemove : requirementToGrow) {
            if (toRemove.getType() != Material.AIR) {
                if (!toRemove.isPassable() || TyphonUtils.isMaterialRocklikes(toRemove.getType()) || toRemove.getType() == Material.TALL_GRASS) {
                    toRemove.setType(Material.AIR);
                }
            }
        }

        scanBaseBlock.setType(Material.AIR);

        TreeType type = randomTreeType();
        boolean isCreated = block.getWorld().generateTree(scanBaseBlock.getLocation(), type);
        if (!isCreated) {
            if (isDebug) this.volcano.logger.log(
                VolcanoLogClass.SUCCESSION,
                "Creating "+type.name()+" Tree failed on block "+TyphonUtils.blockLocationTostring(rockBlock)+" due to bukkit internal issue.");
        }

        return isCreated;
    }


    public void importConfig(JSONObject configData) {
        this.isEnabled = (boolean) configData.get("enabled");
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        configData.put("enabled", this.isEnabled);

        return configData;
    }

}
