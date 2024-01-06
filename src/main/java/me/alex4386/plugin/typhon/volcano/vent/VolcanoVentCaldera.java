package me.alex4386.plugin.typhon.volcano.vent;

import me.alex4386.plugin.typhon.TyphonBlueMapUtils;
import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.bomb.VolcanoBomb;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoEruptStyle;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.*;

public class VolcanoVentCaldera {
    public VolcanoVent vent;

    int minBombs = 50;
    int maxBombs = 100;

    public int radius = -1;
    int deep = -1;
    Block baseBlock = null;

    boolean isRunning = false;

    public int currentRadius = 0;
    public Block currentBase = null;

    int randomnessHeight = 5;
    int tuffLayer = 3;

    long notProcessedEjecta = 0;

    int scheduleID = -1;

    int targetY = 0;

    int oceanY = 0;

    double[][] noise = null;

    VolcanoEruptStyle backedupStyle = VolcanoEruptStyle.STROMBOLIAN;

    int currentIteration = 0;
    List<Block> currentIterationList = null;

    VolcanoVentCaldera(VolcanoVent vent) {
        this.vent = vent;
    }

    public void registerTask() {
        this.vent.getVolcano().logger.log(VolcanoLogClass.CALDERA, "Registering Eruption tick");
        if (this.scheduleID < 0) {
            this.scheduleID = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                    TyphonPlugin.plugin,
                    () -> {
                        this.runEruptTick();
                    },
                    0l,
                    4l
            );
        }
    }

    public void unregisterTask() {
        this.vent.getVolcano().logger.log(VolcanoLogClass.CALDERA, "Unregistering Eruption tick");
        if (this.scheduleID >= 0) {
            Bukkit.getScheduler().cancelTask(this.scheduleID);
            this.scheduleID = -1;
        }
    }

    /* ====== AUTOSETUP UTILS ====== */

    // calculate the target Y for the caldera
    public int getTargetY(Block coreBlock, int radius) {
        int sampleSize = 5 + (int) (Math.random() * 5);

        int totalY = 0;
        for (int i = 0; i < sampleSize; i++) {
            Block block = TyphonUtils.getRandomBlockInRange(coreBlock, radius, radius);
            block = TyphonUtils.getHighestRocklikes(block);

            totalY += block.getY();
        }

        int targetY = totalY / sampleSize;
        return targetY;
    }

    public int getDeep(int radius) {
        return (int) Math.max(10, ((radius / Math.sqrt(3)) / 4));
    }

    /* ======= AUTOSETUP ======== */
    public void autoSetup() {
        double radius = this.vent.longestNormalLavaFlowLength / (2 + Math.random());
        if (radius == 0) {
            radius = this.vent.longestFlowLength / (2 + Math.random());
        }

        radius = Math.max(50, radius);
        this.autoSetup((int) radius);
    }

    public void autoSetup(int radius) {
        int deep = this.getDeep(radius);
        this.autoSetup(radius, deep);
    }

    public void autoSetup(int radius, int deep) {
        Block block = this.vent.getCoreBlock();
        int y = this.getTargetY(block, radius);

        int oceanDepth = (int) (deep / (2 + Math.random()));

        boolean fillWater = Math.random() < 0.3;
        int oceanY = Integer.MIN_VALUE;

        if (fillWater) {
            oceanY = y - deep + tuffLayer + oceanDepth;
        }

        this.setupWork(block, radius, deep, oceanY);
    }

    public void autoSetup(int radius, int deep, int oceanY) {
        Block block = this.vent.getCoreBlock();

        this.setupWork(block, radius, deep, oceanY);
    }

    /* ===== CONFIGURATOR ===== */
    public void setupWork(Block block, int radius, int deep, int oceanY) {
        this.vent.getVolcano().logger.log(VolcanoLogClass.CALDERA, "Setting up caldera formation");

        this.radius = radius;
        this.deep = deep;

        Location loc = block.getLocation();
        loc.setY(this.vent.getSummitBlock().getY());

        this.baseBlock = loc.getBlock();

        this.targetY = this.getTargetY(this.baseBlock, this.radius);
        this.oceanY = oceanY > this.targetY ? oceanY : this.vent.getVolcano().location.getWorld().getSeaLevel();

        this.initializeNoise();

        this.currentBase = this.baseBlock.getRelative(0, this.vent.getSummitBlock().getY() - this.baseBlock.getY(), 0).getRelative(0, -this.vent.getRadius(), 0);
        this.currentRadius = this.vent.getRadius();
    }

    /* ====== REGISTER/UNREGISTER ====== */

    public void initialize() {
        this.registerTask();
    }

    public void shutdown() {
        this.unregisterTask();
    }

    /* ====== CONDITION CHECKERS ====== */

    public boolean canCreateCaldera() {
        if (vent.calderaRadius > 0) return false;
        if (vent.getType() != VolcanoVentType.CRATER) return false;
        if (vent.longestNormalLavaFlowLength < 50) return false;

        return true;
    }

    public int getCurrentTargetY(Block block) {
        double rawDistance = Math.min(TyphonUtils.getTwoDimensionalDistance(this.baseBlock.getLocation(), block.getLocation()), this.radius);
        double heightDeduct = Math.max(0, Math.min(deep, radius - rawDistance));

        int targetYFromHeightDeduct = (int) (this.targetY - heightDeduct);
        int baseBlockStairY = this.currentBase.getY() + (int) rawDistance;

        return Math.max(targetYFromHeightDeduct, baseBlockStairY) + this.getRandomnessHeight(block);
    }

    public int getRandomnessHeight(Block block) {
        int x = block.getX() - this.baseBlock.getX() + this.radius - 1;
        int z = block.getZ() - this.baseBlock.getZ() + this.radius - 1;
        if (x < 0 || x >= this.radius * 2 || z < 0 || z >= this.radius * 2) return 0;

        this.initializeNoise();
        try {
            double data = this.noise[x][z];
            return (int) (data * randomnessHeight);
        } catch(Exception e) {
            return 0;
        }
    }

    /* ====== WORK UTILS ====== */
    public void initializeNoise() {
        if (this.noise == null) this.noise = VolcanoMath.generatePerlinNoise(this.radius * 2, this.radius * 2, 4);
    }

    public int excavateUntilSpecificY(Block block, int targetY) {
        int excavated = 0;

        while (block.getY() > targetY) {
            Material material = Material.AIR;
            if (block.getY() <= this.oceanY) material = Material.WATER;

            vent.lavaFlow.queueBlockUpdate(block, material);
            block = block.getRelative(0, -1, 0);
            excavated++;
        }

        if (block != null) {
            vent.lavaFlow.queueBlockUpdate(block, Material.TUFF);
        }

        return excavated;
    }

    /* ====== WORK ====== */
    public void runSession() {
        int sessionBlocks = 400000;

        if (currentIteration == 0) {
            List<Block> blocks = VolcanoMath.getCircle(this.baseBlock, this.currentRadius);

            blocks.sort((o1, o2) -> {
                double distance1 = TyphonUtils.getTwoDimensionalDistance(this.baseBlock.getLocation(), o1.getLocation());
                double distance2 = TyphonUtils.getTwoDimensionalDistance(this.baseBlock.getLocation(), o2.getLocation());

                return Double.compare(distance1, distance2);
            });

            this.currentIterationList = blocks;
        }

        int listSize = currentIterationList.size();
        for (int i = 0; i < sessionBlocks && currentIteration < listSize; i++, currentIteration++) {
            Block block = currentIterationList.get(currentIteration);
            Block targetBlock = TyphonUtils.getHighestRocklikes(block);
            int targetY = this.getCurrentTargetY(block);
            if (targetBlock.getY() <= targetY) continue;

            int excavated = this.excavateUntilSpecificY(targetBlock, targetY);
            if (excavated > 0) {
                this.vent.record.addEjectaVolume(excavated);
                notProcessedEjecta += excavated;
            }

            if (currentIteration > sessionBlocks) {
                return;
            }
        }

        if (currentIteration >= listSize) {
            this.currentIteration = 0;
            this.currentIterationList = null;

            if (this.currentRadius < radius) {
                this.currentRadius++;
                this.currentBase = this.currentBase.getRelative(0, -1, 0);
            } else {
                this.endErupt();
            }
        }
    }

    /* ====== STATUS ====== */

    public boolean isForming() {
        return this.isSettedUp() && this.isRunning;
    }

    public boolean isInCalderaRange(Location location) {
        if (!this.isSettedUp()) {
            return false;
        }

        return TyphonUtils.getTwoDimensionalDistance(this.baseBlock.getLocation(), location) < this.radius;
    }

    private long getTotal() {
        return this.getTotal(this.radius);
    }

    private long getTotal(int to) {
        int from = this.vent.getRadius();
        long total = 0;

        for (int i = from; i < to; i++) {
            total += (long) (Math.pow(i, 2) * Math.PI);
        }

        return total;
    }

    public double getProgress() {
        if (!this.isSettedUp()) {
            return 0;
        }

        long size = (long) (Math.pow(this.currentRadius, 2) * Math.PI);
        double currentIterationPercent = (double) this.currentIteration / size;
        double currentIterationInScale = (currentIterationPercent * size) / this.getTotal();

        return ((this.getTotal(this.currentRadius) + currentIterationInScale) / this.getTotal());
    }

    public boolean isSettedUp() {
        return this.baseBlock != null && this.radius >= 0 && this.deep >= 0;
    }

    /* ====== ERUPTION ====== */

    public void startErupt() {
        this.vent.getVolcano().logger.log(VolcanoLogClass.CALDERA, "Starting plinian eruption for caldera formation");

        this.backedupStyle = this.vent.erupt.getStyle();
        this.vent.erupt.setStyle(VolcanoEruptStyle.PLINIAN);
        this.initialize();

        this.isRunning = true;
    }

    public void doEruptionPlume() {
        Block randomBlock = TyphonUtils.getRandomBlockInRange(this.baseBlock, this.currentRadius);
        this.vent.ash.createAshCloud(TyphonUtils.getHighestRocklikes(randomBlock).getLocation(), 1);
    }

    public void doEruptionPyroclasticFlows() {
        // +5 to circumvent the caldera formation detection override
        if (this.vent.ash.activePyroclasticFlows() > 200) return;

        long total = 0;
        Block lowestY;

        List<Block> listBlocks = VolcanoMath.getCircle(this.baseBlock, this.currentRadius + 5, this.currentRadius + 4);
        lowestY = TyphonUtils.getHighestRocklikes(listBlocks.get(0));
        for (Block baseBlock : listBlocks) {
            Block block = TyphonUtils.getHighestRocklikes(baseBlock);

            total += block.getY();
            if (block.getY() < lowestY.getY()) lowestY = block;
        }

        double average = (double) total / listBlocks.size();
        Block targetBlock;
        if (lowestY.getY() + 2 <= average) {
            targetBlock = TyphonUtils.getRandomBlockInRange(this.baseBlock, this.currentRadius + 2, this.currentRadius + 4);
        } else {
            targetBlock = lowestY;
        }

        this.vent.ash.triggerPyroclasticFlow(TyphonUtils.getHighestRocklikes(targetBlock));
    }

    public void runEruptTick() {
        if (this.isForming()) {
            if (this.notProcessedEjecta <= 0) {
                this.notProcessedEjecta = 0;
                this.runSession();
            } else {
                // launch bombs outside the caldera
                int random = (int) (Math.random() * 100);
                for (int i = 0; i < random; i++) {
                    if (this.notProcessedEjecta <= 0) break;

                    int bombSize = (int) ((Math.random() * 3) + 2);

                    VolcanoBomb bomb;
                    if (Math.random() < 0.5) {
                        bomb = this.vent.bombs.generateBombToDestination(
                                TyphonUtils.getHighestRocklikes(
                                        TyphonUtils.getFairRandomBlockInRange(this.baseBlock, this.currentRadius, (int) Math.max(this.radius + 100, this.vent.longestFlowLength))
                                ).getLocation(), 1);
                    } else {
                        bomb = this.vent.bombs.generateConeBuildingBomb();
                    }

                    if (bomb != null) {
                        if (Math.random() < 0.95) {
                            bomb.land();
                        } else {
                            vent.bombs.launchSpecifiedBomb(bomb);
                        }

                        double bombVolume = (4.0 / 3.0) * Math.PI * Math.pow(bombSize, 3);
                        this.notProcessedEjecta -= (long) Math.ceil(bombVolume);
                    }

                    int plumeCount = (int) (Math.pow(random / 100.0, 2) * 4) + 1;
                    for (int j = 0; j < plumeCount; j++) {
                        this.doEruptionPlume();
                    }

                    if (Math.random() < 0.2) {
                        this.doEruptionPyroclasticFlows();
                    }
                }
            }
        }
    }

    public void endErupt() {
        this.vent.getVolcano().logger.log(VolcanoLogClass.CALDERA, "Ending caldera formation");

        this.shutdown();
        this.finalizeUpdateVentData();

        this.vent.erupt.stop();
        this.vent.volcano.quickCool();
        this.vent.bombs.bombMap.clear();

        this.vent.flushSummitCache();
    }

    public void finalizeUpdateVentData() {
        if (this.radius > 0) this.vent.calderaRadius = this.radius;

        this.vent.erupt.stop();
        this.vent.flushCache();

        this.vent.bombs.resetBaseY();

        TyphonBlueMapUtils.updateVolcanoVentIcon(this.vent);
        this.vent.erupt.setStyle(backedupStyle);

        this.isRunning = false;
    }
}
