package me.alex4386.plugin.typhon.volcano.succession;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sun.source.tree.Tree;
import me.alex4386.plugin.typhon.TyphonCache;
import me.alex4386.plugin.typhon.TyphonScheduler;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.TreeType;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.json.simple.JSONObject;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentStatus;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

public class VolcanoSuccession {
    // Implements Primary Succession
    Volcano volcano;
    boolean isEnabled = true;

    double cyclesPerTick = 1;
    public int snowYAxis = 170;
    public int peakThreshold = 220;

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

    public boolean isEnabled() {
        return this.isEnabled;
    }

    public void setEnabled(boolean enabled) {
        this.isEnabled = enabled;
    }

    public void shutdown() {
        this.volcano.logger.log(
            VolcanoLogClass.SUCCESSION,
            "Unregistering VolcanoSuccession for Primary Succession");
        this.unregisterTask();
    }

    public void registerTask() {
        if (successionScheduleId < 0) {
            successionScheduleId = TyphonScheduler
                    .registerGlobalTask(
                    () -> {
                        this.runSuccessionCycle();
                    },
                    (long) (TyphonPlugin.minecraftTicksPerSeconds * cyclesPerTick));
        }
    }

    public void unregisterTask() {
        if (successionScheduleId >= 0) {
            TyphonScheduler.unregisterTask(successionScheduleId);
            successionScheduleId = -1;
        }
    }

    public void runSuccessionCycle() {
        if (this.isEnabled) {
            for (VolcanoVent vent : this.volcano.manager.getVents()) {
                if (vent.enableSuccession) runSuccessionCycle(vent);
            }
        }
    }

    public void runSuccessionCycle(VolcanoVent vent) {
        int successionCount = 0;
        if (vent.getStatus() == VolcanoVentStatus.ERUPTING || vent.getStatus() == VolcanoVentStatus.ERUPTION_IMMINENT || vent.lavaFlow.hasAnyLavaFlowing()) {
            successionCount = (int) (Math.random() * 2);
        } else if (Math.random() > vent.getStatus().getScaleFactor()) {
            double circumference = vent.longestNormalLavaFlowLength * Math.PI * 2;
            double successionScale = Math.pow((1.0 - vent.getStatus().getScaleFactor()), 2);

            double maxCount = Math.min(Math.max(0, circumference / 20), 2000 / cyclesPerTick);

            double cycleCount = successionScale * maxCount;
            successionCount = (int) cycleCount;

//            vent.getVolcano().logger.debug(VolcanoLogClass.SUCCESSION, "Running succession for "+successionCount+" times on "+vent.getCoreBlock().getLocation().toString());
        }

        for (int i = 0; i < successionCount; i++) {
            runSuccession(vent);
        }

        /*
        double random = Math.random();
        if (random < 0.0001) {
            generateAmethystGeode();
        }
        */
    }

    public boolean checkAmethystGeodeCriteria(Block block) {
        List<Block> cylinderChecker = VolcanoMath.getCylinder(block, 8, 1);
        for (Block cylinderChk: cylinderChecker) {
            int chkY = TyphonUtils.getHighestRocklikes(cylinderChk).getY();

            if (chkY <= block.getY() + 8) {
                return false;
            }
        }

        return true;
    }

    public void generateAmethystGeode(Block block) {
        if (!checkAmethystGeodeCriteria(block)) {
            return;
        }
    
        List<Block> blocks = VolcanoMath.getSphere(block, 7, 6);
        for (Block calBlock: blocks) {
            this.volcano.mainVent.lavaFlow.queueImmediateBlockUpdate(calBlock, Material.CALCITE);
        }

        blocks = VolcanoMath.getSphere(block, 6, 5);
        for (Block calBlock: blocks) {
            this.volcano.mainVent.lavaFlow.queueImmediateBlockUpdate(calBlock, Material.AMETHYST_BLOCK);
        }
    }

    private final Map<Integer, TyphonCache<Integer>> rangeCache = new HashMap<>();

    public void runSuccession(VolcanoVent vent) {
        Block coreBlock = vent.getCoreBlock();
        double longestFlow = Math.max(vent.getBasinLength(), vent.longestNormalLavaFlowLength);
        if (Math.random() < 0.2) {
            longestFlow = Math.max(longestFlow, vent.bombs.maxDistance);
        } else if (Math.random() < 0.1) {
            longestFlow = Math.max(longestFlow, vent.longestFlowLength);
        }

        double skipZone = (vent.getType() == VolcanoVentType.CRATER ? vent.craterRadius : 0);
        if (skipZone > 0) {
            if (!vent.getStatus().hasElevatedActivity()) {
                if (Math.random() < 0.5) {
                    skipZone = 0;
                }
            }
        }

        double random = ((longestFlow - skipZone) * (1 - VolcanoMath.getZeroFocusedRandom())) + skipZone;
        for (int i = 0; i < 1000; i++) {
            vent.getVolcano().logger.debug(VolcanoLogClass.SUCCESSION, "Running succession on "+coreBlock.getLocation().toString()+" with random "+random);

            double angle = Math.random() * Math.PI * 2;
            double x = Math.sin(angle) * random;
            double z = Math.cos(angle) * random;

            Block block = coreBlock.getRelative((int) x, 0, (int) z);

            if (!isValidSuccessionBlock(block)) {
                // reduce the random!
                random -= 1;

                // we need to store the random value for this angle.
                continue;
            }
            runSuccession(block);
            break;
        }
    }

    public boolean shouldCheckHeat(Block block) {
        boolean shouldCheckHeat = false;
        VolcanoVent vent = volcano.manager.getNearestVent(block);
        if (vent != null) {
            if (vent.getStatus().getScaleFactor() > 0.1) {
                shouldCheckHeat = true;
            }
        }

        return shouldCheckHeat;
    }

    public void returnToNormalBiome(Block block) {
//        if (block.getY() > snowYAxis) {
//            block.getWorld().setBiome(block.getLocation(), Biome.GROVE);
//        } else if (block.getY() > peakThreshold) {
//            block.getWorld().setBiome(block.getLocation(), Biome.JAGGED_PEAKS);
//        } else {
//            block.getWorld().setBiome(block.getLocation(), Biome.MEADOW);
//        }
//
//        block.getWorld().refreshChunk(block.getChunk().getX(), block.getChunk().getZ());

    }

    public boolean isValidSuccessionBlock(Block block) {
        Block targetBlock = TyphonUtils.getHighestRocklikes(block);

        if (targetBlock.getY() < block.getWorld().getSeaLevel() - 1) {
            return false;
        }

        return true;
    }



    public void runSuccession(Block block) {
        boolean isDebug = true;

        Block targetBlock = TyphonUtils.getHighestRocklikes(block);
        double rawHeatValue = this.volcano.manager.getHeatValue(block.getLocation());
        double heatValue = Math.sqrt(rawHeatValue);

        double heatValueThreshold = 0.7;
        if (targetBlock.getY() < block.getWorld().getSeaLevel() - 1) {
            if (isDebug) this.volcano.logger.log(
                    VolcanoLogClass.SUCCESSION,
                    "Succession on block "+TyphonUtils.blockLocationTostring(targetBlock)+" / skipped due to sea level."
                );

            return;
        }

        if (!this.volcano.manager.isInAnyVent(block)) {
            if (rawHeatValue < 0.5) {
                if (Math.random() < 0.1) {
                    this.removeOre(targetBlock);
                    return;
                }

                double probability = 1.0;

                if (heatValue > 0.4) {
                    // since volcano is hot. probability scales down.
                    probability = (probability - 0.4) / (heatValueThreshold - 0.4);
                    probability = Math.pow(0.5, Math.max(probability * 10, 1));
                }

                // check for probability
                if (Math.random() < probability) {
                    this.removeOre(targetBlock);
                    return;
                }

                // stage 3. is grass?
                boolean isGrass = targetBlock.getType() == Material.GRASS_BLOCK || targetBlock.getType() == Material.PODZOL || targetBlock.getType() == Material.COARSE_DIRT;
                if (isGrass) {
                    // let me run some randoms.
                    double growProbability = 0.2;
                    if (!block.getWorld().isClearWeather()) growProbability += 0.3;

                    if (Math.random() < growProbability) {
                        int yAxis = targetBlock.getY();
                        if (yAxis < snowYAxis) {
                            boolean treeGenerated = createTree(targetBlock);
                            if (isDebug) this.volcano.logger.log(
                                    VolcanoLogClass.SUCCESSION,
                                    "Creating Tree on block "+TyphonUtils.blockLocationTostring(targetBlock)+" / result: "+treeGenerated);

                            if (treeGenerated) {
                                return;
                            }
                        } else if (yAxis < peakThreshold) {

                            // tree can not grow if heatValue is high enough,
                            // in that case, grass should be generated instead.
                            if (shouldCheckHeat(block)) {
                                if (heatValue > 0.6) {
                                    targetBlock.applyBoneMeal(BlockFace.UP);
                                    spreadSoil(targetBlock);
                                    return;
                                }
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
                                "Creating Soil on block "+TyphonUtils.blockLocationTostring(targetBlock));

                        runSoilGeneration(targetBlock);
                        spreadSoil(targetBlock);
                    }
                    return;
                }


                double erodeProb = 0.05;
                if (!block.getWorld().isClearWeather()) erodeProb += 0.1;

                // Stage 1. just randomly change into cobblestone
                if (Math.random() < erodeProb) {
                    erodeBlock(targetBlock);

                    if (isDebug) this.volcano.logger.log(
                            VolcanoLogClass.SUCCESSION,
                            "Eroding rock on block "+TyphonUtils.blockLocationTostring(targetBlock));
                }
            } else if (rawHeatValue < 0.65) {
                if (Math.random() < 0.1) {
                    this.removeOre(targetBlock);
                    return;
                }

                double amount = 1 - Math.min(1, Math.max(0, (rawHeatValue - 0.5) / 0.15));

                if (isDebug) this.volcano.logger.log(
                        VolcanoLogClass.SUCCESSION,
                        "Succession on block "+TyphonUtils.blockLocationTostring(block)+" / amount: "+amount);

                if (Math.random() < Math.pow(amount, 2)) {
                    targetBlock.applyBoneMeal(BlockFace.UP);
                }
                spreadSoil(targetBlock, (int)(amount * 5), false);
                return;
            } else {
                VolcanoVent vent = this.volcano.manager.getNearestVent(targetBlock);
                double distance = vent.getTwoDimensionalDistance(block.getLocation());
                boolean isInRange = distance < vent.getRadius() * 2;

                // add random for matching the probability on t
                if (!vent.isStarted() && !isInRange) {
                    if (Math.random() < 0.1) {
                        this.removeOre(vent, targetBlock);
                        return;
                    }

                    if (rawHeatValue < 0.8) {
                        double percentage = (rawHeatValue - 0.65) / 0.15;
                        percentage = Math.min(1, Math.max(0, percentage));

                        // prevent soil generation nearby the 0.8 have more grass
                        percentage = Math.pow(1 - percentage, 2);

                        // scan nearby blocks
                        List<Block> nearbyBlocks = VolcanoMath.getCircle(targetBlock, 3);

                        // check how many of them are dirt
                        int dirtCount = 0;
                        int totalCount = nearbyBlocks.size();

                        for (Block nearbyBlock: nearbyBlocks) {
                            Block highestBlock = TyphonUtils.getHighestRocklikes(nearbyBlock);
                            String type = TyphonUtils.toLowerCaseDumbEdition(highestBlock.getType().name());
                            if (type.contains("dirt") || type.contains("grass")) {
                                dirtCount++;
                            }
                        }

                        double currentDirt = (double) dirtCount / totalCount;
                        if (currentDirt < percentage) {
                            int neededDirtBlocks = (int) (percentage * totalCount) - dirtCount;

                            // get random blocks from nearby blocks
                            Collections.shuffle(nearbyBlocks);
                            for (Block nearbyBlock: nearbyBlocks) {
                                Block highestBlock = TyphonUtils.getHighestRocklikes(nearbyBlock);
                                if (!isConsideredErodedRockType(highestBlock.getType())) {
                                    vent.lavaFlow.queueBlockUpdate(highestBlock, Material.DIRT);
                                    neededDirtBlocks--;
                                }

                                if (neededDirtBlocks <= 0) {
                                    break;
                                }
                            }
                        }
                    }
                }
                
                this.removeOre(vent, targetBlock);
            }
        }
    }

    private void removeOre(Block targetBlock) {
        VolcanoVent vent = this.volcano.manager.getNearestVent(targetBlock);
        removeOre(vent, targetBlock);
    }

    public void removeOre(VolcanoVent vent, Block targetBlock) {    
        if (isTypeOfVolcanicOre(targetBlock.getType())) {
            vent.lavaFlow.queueImmediateBlockUpdate(targetBlock, VolcanoComposition.getExtrusiveRock(vent.lavaFlow.settings.silicateLevel));
        } else if (targetBlock.getType() == Material.BLACKSTONE) {
            if (Math.random() < 0.01) {
                vent.lavaFlow.queueImmediateBlockUpdate(targetBlock, Material.NETHERRACK);
            }
        } else if (targetBlock.getType() == Material.NETHERRACK) {
            if (Math.random() < 0.01 * 0.01) {
                vent.lavaFlow.queueImmediateBlockUpdate(targetBlock, Material.TUFF);
            }
        }
    }

    public void erodeBlock(Block targetBlock) {
        if (targetBlock.getType() == Material.DEEPSLATE || targetBlock.getType() == Material.BLACKSTONE) {
            this.volcano.mainVent.lavaFlow.queueImmediateBlockUpdate(targetBlock, Material.COBBLED_DEEPSLATE);
        } else if (VolcanoComposition.isVolcanicRock(targetBlock.getType())) {
            this.volcano.mainVent.lavaFlow.queueImmediateBlockUpdate(targetBlock, Material.COBBLESTONE);
        } else { return; }
    }

    public boolean isNaturalized(Material material) {
        if (material == Material.GRASS_BLOCK) return true;

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
            case BLACKSTONE:
                return true;
        }

        return false;
    }

    public boolean isTypeOfVolcanicOre(Material material) {
        return material == Material.ANCIENT_DEBRIS ||
            TyphonUtils.toLowerCaseDumbEdition(material.name()).contains("ore");
    }

    public void spreadSoil(Block block) {
        int spreadRange = (int) (Math.pow(Math.random(), 1.5) * 10) + 5;
        spreadSoil(block, spreadRange, false);
    }

    public void spreadSoil(Block block, int spreadRange, boolean withExtension) {
        int extension = (withExtension ? spreadRange / 3 : 0);
        List<Block> treeRange = VolcanoMath.getCircle(block, spreadRange + extension);
        returnToNormalBiome(block);

        TyphonUtils.smoothBlockHeights(block, spreadRange + extension, Material.DIRT);
        VolcanoVent vent = this.volcano.manager.getNearestVent(block);
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
                if (vent.isInVent(rockRange.getLocation())) {
                    if (vent.getStatus().hasElevatedActivity()) {
                        continue;
                    } else if (vent.getStatus().isActive()) {
                        if (Math.random() < 0.001) {
                            continue;
                        }
                    }
                }

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

        VolcanoVent vent = this.volcano.manager.getNearestVent(block);
        double scaleFactor = vent.getStatus().getScaleFactor();
        double heatValue = this.volcano.manager.getHeatValue(block.getLocation());

        if (scaleFactor >= 0.8) {
            if (heatValue > 0.9) {
                if (isTypeOfVolcanicOre(rockBlock.getType())) {
                    if (vent != null) vent.lavaFlow.queueBlockUpdate(rockBlock, VolcanoComposition.getExtrusiveRock(vent.lavaFlow.settings.silicateLevel));
                }
                return false;
            }
        }

        // remove ores first
        if (heatValue > 0.97) {
            if (isTypeOfVolcanicOre(rockBlock.getType())) {
                if (vent != null) vent.lavaFlow.queueBlockUpdate(rockBlock, VolcanoComposition.getExtrusiveRock(vent.lavaFlow.settings.silicateLevel));
            }
            return false;
        }

        // if volcano is active, prevent soil generation
        if (vent.getStatus().hasElevatedActivity()) {
            if (Math.random() < scaleFactor) {
                return false;
            }
        }

        if (surfaceBlock.getY() - block.getY() > 3) return true;

        if (!isFertilizedSoil(rockBlock.getType())) {
            if (surfaceBlock.getY() == block.getY()) {
                volcano.mainVent.lavaFlow.queueImmediateBlockUpdate(rockBlock, Material.GRASS_BLOCK);
            } else {
                volcano.mainVent.lavaFlow.queueImmediateBlockUpdate(rockBlock, Material.DIRT);
            }
        } else {
            Block underlyingSoil = rockBlock.getRelative(BlockFace.DOWN);
            runSoilGeneration(underlyingSoil, false);
            return true;
        }
        return true;
    }

    public boolean isFertilizedSoil(Material material) {
        if (material == Material.GRASS_BLOCK) return true;
        if (material == Material.COARSE_DIRT) return false;

        String materialName = TyphonUtils.toLowerCaseDumbEdition(material.name());
        if (materialName.contains("dirt")) return true;

        return !(isConsideredErodedRockType(material) || isTypeOfVolcanicOre(material) || VolcanoComposition.isVolcanicRock(material));
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
        double random = Math.random();

        boolean isSecondary = Math.random() < 0.1;
        if (isSecondary) {
            if (random < 0.5) {
                return TreeType.CHERRY;
            } else if (random < 0.7) {
                return TreeType.REDWOOD;
            } else {
                return Math.random() < 0.5 ? TreeType.TREE : TreeType.DARK_OAK;
            }
        }

        // get adequate tree type for current biome
        if (random < 0.7) {
            return TreeType.BIRCH;
        } else {
            return TreeType.ACACIA;
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
            VolcanoVentStatus status = this.volcano.manager.getNearestVent(block).getStatus();
            
            if (status.getScaleFactor() >= 0.8) {
                if (heatValue > 0.6) {
                    return false;
                }
            } else if (status.hasElevatedActivity()) {
                if (heatValue > 0.8) {
                    return false;
                }
            } else if (status.isActive()) {
                if (heatValue > 0.9) {
                    return Math.random() < 0.001;
                }
            }

            radius = (int) (Math.pow(3, heatValue) * 6);
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
                    this.volcano.mainVent.lavaFlow.queueImmediateBlockUpdate(toRemove, Material.AIR);
                }
            }
        }


        // get adequate tree type for current biome
        Biome biome = scanBaseBlock.getBiome();

        // get adequate tree type for current biome
        TreeType type = TyphonUtils.getAdequateTreeTypeForBiome(biome);
        if (type == null) {
            type = randomTreeType();
        }

        if (Math.random() < 0.05) {
            type = TreeType.CHERRY;
        }

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
