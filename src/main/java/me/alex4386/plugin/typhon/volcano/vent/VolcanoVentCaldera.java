package me.alex4386.plugin.typhon.volcano.vent;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.bomb.VolcanoBomb;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoEruptStyle;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogger;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.*;

public class VolcanoVentCaldera {
    public VolcanoVent vent;
    public Iterator<Map.Entry<Block, Material>> work = null;

    int minBombs = 50;
    int maxBombs = 100;

    int radius = -1;
    int deep = -1;
    Block baseBlock = null;

    boolean isRunning = false;

    public long current = 0;
    public long total = 0;

    public long cycle = 0;

    int scheduleID = -1;
    int tuffLayer = 3;

    int tmpTargetY = 0;

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

    public void initialize() {
        this.registerTask();
    }

    public void shutdown() {
        this.unregisterTask();
    }

    public boolean canCreateCaldera() {
        if (vent.calderaRadius > 0) return false;
        if (vent.getType() != VolcanoVentType.CRATER) return false;
        if (vent.longestNormalLavaFlowLength < 50) return false;

        return true;
    }

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

    public HashMap<Block, Material> getMountainTops(Block coreBlock, double radius, int targetY) {
        this.vent.getVolcano().logger.log(VolcanoLogClass.CALDERA, "Fetching mountain tops from r="+radius+", y="+targetY);

        int summitY = vent.getSummitBlock().getY();
        List<Block> cylinder = VolcanoMath.getCylinder(coreBlock.getRelative(0, targetY - coreBlock.getY(), 0), (int) radius, summitY - targetY + 2);

        HashMap<Block, Material> result = new HashMap<>();
        for (Block block:cylinder) {
            result.put(block, Material.AIR);
        }

        return result;
    }

    public int getDeep(int radius) {
        return (int) (radius / Math.sqrt(3));
    }

    public HashMap<Block, Material> getCalderaArea(Block coreBlock, double radius, int targetY, int deep) {
        return this.getCalderaArea(coreBlock, radius, targetY, deep, Integer.MIN_VALUE);
    }

    public HashMap<Block, Material> getCalderaArea(Block coreBlock, double radius, int targetY, int deep, int oceanY) {
        this.vent.getVolcano().logger.log(VolcanoLogClass.CALDERA, "Fetching caldera rims from r="+radius+", y="+targetY+", depth="+deep);

        int actualDeep = Math.min(deep, targetY - (int) vent.location.getY());
        double actualRadius = (Math.pow(radius, 2)+Math.pow(deep, 2))/(2*deep);

        Block targetYCore = coreBlock.getRelative(0, targetY - coreBlock.getY(), 0);
        Location center = targetYCore.getLocation().add(0, actualRadius - deep, 0);

        List<Block> calderaSphere = VolcanoMath.getCylinder(targetYCore.getRelative(0, -actualDeep, 0), (int) radius, actualDeep);
        calderaSphere.removeIf(block -> block.getLocation().distance(center) > actualRadius);

        boolean isOceanLava = coreBlock.getWorld().getEnvironment() == World.Environment.NETHER;
        oceanY = oceanY == Integer.MIN_VALUE ? coreBlock.getWorld().getSeaLevel() : oceanY;

        HashMap<Block, Material> result = new HashMap<>();
        for (Block block : calderaSphere) {
            Material material = Material.AIR;
            if (block.getY() <= targetY - actualDeep + tuffLayer) material = Material.TUFF;
            else if (block.getY() <= targetY - actualDeep + 4 && Math.random() < 0.25) material = Material.TUFF;
            else if (block.getY() <= oceanY) {
                if (isOceanLava) material = Material.LAVA;
                else material = Material.WATER;
            }

            result.put(block, material);
        }

        return result;
    }

    public void setupWork(Block coreBlock, double radius, int deep, int oceanY) {
        int targetY = this.getTargetY(coreBlock, (int) radius);
        this.tmpTargetY = targetY;

        if (deep <= 0) deep = this.getDeep((int) radius);
        if (oceanY >= targetY) oceanY = Integer.MIN_VALUE;

        this.vent.getVolcano().logger.log(VolcanoLogClass.CALDERA, "Setting up 'work' iterator from r="+radius+", y="+targetY+", oceanY="+oceanY);

        HashMap<Block, Material> calderaArea = this.getCalderaArea(coreBlock, radius, targetY, deep, oceanY);
        List<Map.Entry<Block, Material>> calderaEntrySet = new ArrayList<>(calderaArea.entrySet());
        Collections.shuffle(calderaEntrySet);

        HashMap<Block, Material> mountainTopArea = this.getMountainTops(coreBlock, radius, targetY);
        List<Map.Entry<Block, Material>> mountainTopEntrySet = new ArrayList<>(mountainTopArea.entrySet());
        Collections.shuffle(mountainTopEntrySet);

        List<Map.Entry<Block, Material>> target = new ArrayList<>();
        target.addAll(calderaEntrySet);
        target.addAll(mountainTopEntrySet);

        total = target.size();

        Iterator<Map.Entry<Block, Material>> result = target.iterator();
        this.work = result;
        this.radius = (int) radius;
        this.deep = deep;
        this.baseBlock = coreBlock;
    }

    public double excavateAndGetBombRadius() {
        if (this.work != null) {
            this.isRunning = true;

            double bombRadius = 1;
            if (Math.random() < 0.5) {
                bombRadius = 2;
                if (Math.random() < 0.75) {
                    bombRadius = Math.random() * 3;
                }
            }
            double volume = (4 / 3) * Math.PI * Math.pow(bombRadius, 3);

            for (int i = 0; i < volume; i++) {
                if (this.work.hasNext()) {
                    Map.Entry<Block, Material> entry = this.work.next();
                    entry.getKey().setType(entry.getValue());
                    current++;
                }
            }

            return bombRadius;
        }

        return -1;
    }

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

    public boolean isForming() {
        return this.isSettedUp() && this.work.hasNext() && this.isRunning;
    }

    public boolean isInCalderaRange(Location location) {
        if (!this.isSettedUp()) {
            return false;
        }

        boolean inCalderaRange = TyphonUtils.getTwoDimensionalDistance(this.baseBlock.getLocation(), location) < this.radius;
        return inCalderaRange;
    }

    public boolean isSettedUp() {
        return this.work != null && this.baseBlock != null && this.radius >= 0 && this.deep >= 0;
    }

    public void startErupt() {
        this.vent.getVolcano().logger.log(VolcanoLogClass.CALDERA, "Starting plinian eruption for caldera formation");
        this.vent.erupt.setStyle(VolcanoEruptStyle.PLINIAN);
        this.initialize();
    }

    public void runEruptTick() {
        if (this.work != null) {
            int bombCount = (int) (Math.random() * (maxBombs - minBombs)) + minBombs;
            List<VolcanoBomb> bombs = new ArrayList<>();

            for (int i = 0; i < bombCount; i++) {
                if (this.work != null && this.work.hasNext()) {
                    double radius = this.excavateAndGetBombRadius();

                    if (Math.random() > 0.05) {
                        this.vent.record.addEjectaVolume((int) (Math.PI * Math.pow(radius, 2)));
                        continue;
                    }

                    int offset = 60;
                    int max = (int) Math.max(this.vent.longestNormalLavaFlowLength, this.radius * 2);
                    int min = max < this.radius + offset ? this.radius + offset : this.radius;

                    Location targetLocation = TyphonUtils.getRandomBlockInRange(this.baseBlock, min, max).getLocation();
                    if (Math.random() < 0.7) {
                        targetLocation = TyphonUtils.getRandomBlockInRange(this.baseBlock, max, max * 2).getLocation();
                    }

                    bombs.add(vent.bombs.generateBombToDestination(targetLocation, (int) Math.round(radius)));
                }
            }

            for (VolcanoBomb bomb : bombs) {
                if (Math.random() < 0.1) { 
                    vent.bombs.launchSpecifiedBomb(bomb);
                } else {
                    bomb.land();
                }
            }

            this.vent.location
                .getWorld()
                .createExplosion(
                        this.vent.getCoreBlock().getLocation(), 8F, true, false);

            if (this.work == null || !this.work.hasNext()) {
                this.endErupt();
            }

            cycle++;

            if (cycle % 10 == 0) {
                if (cycle % 100 == 0) this.vent.flushCache();
                this.vent.getVolcano().logger.log(VolcanoLogClass.CALDERA, "Current Cycle #"+cycle+" - ("+current+"/"+total+") - "+String.format("%.2f", current*100/(double) total)+"%");
            }
        }
    }

    public void endErupt() {
        this.vent.getVolcano().logger.log(VolcanoLogClass.CALDERA, "Ending caldera formation");

        this.bombardCaldera();
        this.shutdown();
        this.finalizeUpdateVentData();

        this.vent.erupt.stop();
        this.vent.volcano.quickCool();
        this.vent.bombs.bombMap.clear();
        
        this.cleanupLeftovers();
    }

    public void cleanupLeftovers() {
        HashMap<Block, Material> leftovers = this.getMountainTops(this.baseBlock, this.radius, this.tmpTargetY);

        this.vent.getVolcano().logger.log(VolcanoLogClass.CALDERA, "Starting cleanup...");
        for (Block block : leftovers.keySet()) {
            if (block.getType() != Material.AIR) {
                block.setType(Material.AIR);
            }
        }

        this.vent.getVolcano().logger.log(VolcanoLogClass.CALDERA, "Cleanup Complete!");
    }

    public void finalizeUpdateVentData() {
        if (this.radius > 0) this.vent.calderaRadius = this.radius;
        this.work = null;

        this.vent.erupt.stop();
        this.vent.flushCache();
        this.vent.erupt.setStyle(VolcanoEruptStyle.STROMBOLIAN);

        this.isRunning = false;
    }

    public void bombardCaldera() {
        this.bombardCaldera(this.baseBlock, this.radius);
    }

    public void bombardCaldera(Block baseBlock, int radius) {
        this.vent.getVolcano().logger.log(VolcanoLogClass.CALDERA, "Bombarding caldera internals to look way more dynamic crater");

        for (int i = 0; i < Math.pow(radius, 1.1); i++) {
            int bombRadius = (int) Math.random() * 3;
            VolcanoBomb bomb = this.vent.bombs.generateBombToDestination(TyphonUtils.getHighestRocklikes(TyphonUtils.getRandomBlockInRange(baseBlock, radius)).getLocation(), bombRadius);

            bomb.land();
        }
    }

    public void forceShutdown() {
        this.vent.getVolcano().logger.log(VolcanoLogClass.CALDERA, "Forcefully shutting down caldera formation");
        this.shutdown();

        if (work != null) {
            if (work.hasNext()) {
                while (work.hasNext()) {
                    Map.Entry<Block, Material> next = work.next();

                    next.getKey().setType(next.getValue());
                }
            }

            this.finalizeUpdateVentData();
        }
    }
}
