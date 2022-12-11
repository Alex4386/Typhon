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

    int minBombs = 10;
    int maxBombs = 20;

    int radius = -1;
    int deep = -1;
    Block baseBlock = null;

    long current = 0;
    long total = 0;

    long cycle = 0;

    int scheduleID = -1;
    int tuffLayer = 3;

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
        Block block = TyphonUtils.getRandomBlockInRange(coreBlock, radius, radius);
        block = TyphonUtils.getHighestRocklikes(block);

        int targetY = block.getY();
        return targetY;
    }

    public HashMap<Block, Material> getMountainTops(Block coreBlock, double radius, int targetY) {
        this.vent.getVolcano().logger.log(VolcanoLogClass.CALDERA, "Fetching mountain tops from r="+radius+", y="+targetY);

        int summitY = vent.getSummitBlock().getY();
        List<Block> cylinder = VolcanoMath.getCylinder(coreBlock.getRelative(0, targetY - coreBlock.getY(), 0), (int) radius, summitY - targetY);

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
            double bombRadius = Math.random() * 6;
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

                    if (Math.random() > 0.01) {
                        this.vent.record.addEjectaVolume((int) (Math.PI * Math.pow(radius, 2)));
                        continue;
                    }

                    int offset = 60;
                    int max = (int) Math.max(this.vent.longestNormalLavaFlowLength, this.radius * 2);
                    int min = max < this.radius + offset ? this.radius + offset : this.radius;

                    Location targetLocation = TyphonUtils.getRandomBlockInRange(this.baseBlock, min, max).getLocation();
                    if (Math.random() < 0.9) {
                        targetLocation = TyphonUtils.getRandomBlockInRange(this.baseBlock, max, max * 2).getLocation();
                    }

                    bombs.add(vent.bombs.generateBombToDestination(targetLocation, (int) Math.round(radius)));
                }
            }

            for (VolcanoBomb bomb : bombs) {
                vent.bombs.launchSpecifiedBomb(bomb);
            }

            vent.ash.createAshPlume();
            vent.ash.triggerAshFall();

            if (this.work == null || !this.work.hasNext()) {
                this.endErupt();
            }

            cycle++;

            if (cycle % 10 == 0) {
                this.vent.getVolcano().logger.log(VolcanoLogClass.CALDERA, "Current Cycle #"+cycle+" - ("+current+"/"+total+")");
            }
        }
    }

    public void endErupt() {
        this.vent.getVolcano().logger.log(VolcanoLogClass.CALDERA, "Ending caldera formation");

        this.bombardCaldera();
        this.shutdown();
        this.finalizeUpdateVentData();
    }

    public void finalizeUpdateVentData() {
        if (this.radius > 0) this.vent.calderaRadius = this.radius;
        this.work = null;

        this.vent.erupt.stop();
        this.vent.erupt.setStyle(VolcanoEruptStyle.STROMBOLIAN);
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
