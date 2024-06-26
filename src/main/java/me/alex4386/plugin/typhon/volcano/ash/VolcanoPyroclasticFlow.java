package me.alex4386.plugin.typhon.volcano.ash;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.intrusions.VolcanoMetamorphism;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class VolcanoPyroclasticFlow {
    Location location;
    Vector direction;
    VolcanoAsh ash;

    int minY = Integer.MAX_VALUE;

    int radius;
    static int maxRadius = 10;

    int life = 5;
    static int maxLife = 30;

    boolean isStarting = true;

    boolean isFinished = false;
    int scheduleID = -1;

    double maxPileup = 5;

    Map<Block, Integer> initialY = new HashMap<>();

    HashMap<Block, Boolean> hasAshFell = new HashMap<>();
    List<BlockDisplay> pyroclasticClouds = new ArrayList<>();

    public static int getMaxLife(VolcanoVent vent) {
        return getMaxLife(vent, maxLife);
    }

    public static int getMaxLife(VolcanoVent vent, int radius) {
        return (int) (vent.longestNormalLavaFlowLength / (radius));
    }

    public VolcanoPyroclasticFlow(Location location, VolcanoAsh ash) {
        this(location, ash, calculateInitialDirection(ash.vent, location));
    }

    public VolcanoPyroclasticFlow(Location location, VolcanoAsh ash, Vector direction) {
        this(location, ash, direction, 5);
    }

    public VolcanoPyroclasticFlow(Location location, VolcanoAsh ash, Vector direction, int radius) {
        this(location, ash, direction, radius, getMaxLife(ash.vent, radius));
    }

    public VolcanoPyroclasticFlow(Location location, VolcanoAsh ash, Vector direction, int radius, int life) {
        this.minY = location.getBlockY();

        this.location = location;
        this.direction = direction;
        this.ash = ash;
        this.radius = radius;
        this.life = life;
        this.maxPileup = (double) radius / (1 + Math.random());
    }

    public void setDirection(Vector direction) {
        this.direction = direction;
    }

    public void registerTask() {
        if (scheduleID < 0) {
            this.scheduleID = Bukkit.getScheduler().scheduleSyncRepeatingTask(TyphonPlugin.plugin, () -> {
                this.runTick();
                this.processAllPyroclasticClouds();
            }, 0L, 2L);
        }
    }

    public void unregisterTask() {
        if (scheduleID >= 0) {
            Bukkit.getScheduler().cancelTask(this.scheduleID);
            this.scheduleID = -1;
        }
    }

    public void initialize() {
        this.ash.vent.getVolcano().logger.log(VolcanoLogClass.ASH, "Initializing pyroclastic flow @ "+ TyphonUtils.blockLocationTostring(this.location.getBlock()));
        this.registerTask();
    }

    public void shutdown() {
        this.shutdown(true);
    }

    public void shutdown(boolean removeMe) {
        this.ash.vent.getVolcano().logger.log(VolcanoLogClass.ASH, "Shutting down pyroclastic flow @ "+TyphonUtils.blockLocationTostring(this.location.getBlock()));
        this.unregisterTask();
        this.removeAllPyroclasticClouds();

        this.ash.pyroclasticFlows.remove(this);
    }

    public void updateMinY() {
        this.minY = Math.min(this.minY, this.location.getBlockY());
    }

    public static Vector calculateInitialDirection(VolcanoVent vent, Location location) {
        Location tmpLoc = location.clone();
        return tmpLoc.subtract(vent.getNearestCoreBlock(location).getLocation()).toVector().normalize();
    }


    public void processAllPyroclasticClouds() {
        for (BlockDisplay bd : this.pyroclasticClouds) {
            ash.processAshCloudHeat(bd);
        }
    }

    public void removeAllPyroclasticClouds() {
        for (BlockDisplay bd : this.pyroclasticClouds) {
            if (bd.isValid()) {
                bd.remove();
            }
        }
    }

    public void runTick() {
        if (isFinished) return;

        this.runAsh();

        if (Math.random() < 0.2) {
            if (radius < maxRadius) radius++;
        }

        if (Math.random() < 0.01) {
            life--;
        }


        Location prevLoc = this.location;

        if (!isStarting) {
            this.calculateDirection();
        } else {
            isStarting = false;
        }

        Location tmpLocation = this.location;

        if (Math.random() < 0.1) {
            // rotate the direction vector within -6.25~6.25 degrees
            double angle = Math.random() * Math.PI / 24;
            double x = direction.getX();
            double z = direction.getZ();

            this.direction = new Vector(
                    x * Math.cos(angle) - z * Math.sin(angle),
                    0,
                    x * Math.sin(angle) + z * Math.cos(angle)
            );
        }

        this.location = this.location.add(direction.normalize().multiply(radius));
        this.location = TyphonUtils.getHighestRocklikes(this.location).getLocation();

        int climbupLimit = Math.max(1, this.radius / 2);

        if (this.location.getY() > tmpLocation.getY() + climbupLimit) {
            this.location = tmpLocation;
            boolean whichWay = Math.random() < 0.5;

            // rotate vector angle 90 degrees
            double x = direction.getX();
            double z = direction.getZ();

            this.direction = new Vector(whichWay ? z : -z, 0, whichWay ? -x: x);
            this.location = this.location.add(direction.normalize().multiply(radius));

            this.location = TyphonUtils.getHighestRocklikes(this.location).getLocation();
            if (this.location.getY() > tmpLocation.getY() + climbupLimit) {
                this.location = tmpLocation;

                this.direction = new Vector(whichWay ? -z : z, 0, whichWay ? x: -x);
                this.location = this.location.add(direction.normalize().multiply(radius));

                this.location = TyphonUtils.getHighestRocklikes(this.location).getLocation();
                if (this.location.getY() > tmpLocation.getY() + climbupLimit) {
                    this.location = tmpLocation;
                    life = 0;
                }
            }
        }

        this.updateMinY();
        if (this.location.getBlockY() > this.minY + this.radius) {
            this.location = tmpLocation;
            life = 0;
        }

        if (this.location.getY() >= prevLoc.getY()) {
            life -= (int) ((this.location.getY() - prevLoc.getY()) + 1);
        }

        if (Math.random() < 0.05) {
            if (Math.random() < 0.1) {
                this.location.getWorld().playSound(this.location.add(0, 3, 0), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER, 4F, 0.5F);
            }
        }

        if (tmpLocation != this.location) {
            // do ash interpolation
            //this.putInterpolationAsh(tmpLocation);
        }

        this.ash.vent.getVolcano().logger.debug(VolcanoLogClass.ASH, "ash trail @ "+TyphonUtils.blockLocationTostring(prevLoc.getBlock())+" -> "+TyphonUtils.blockLocationTostring(this.location.getBlock()));

        isFinished = this.finishConditionCheck();
        if (isFinished) {
            Bukkit.getScheduler().runTaskLater(TyphonPlugin.plugin, () -> {
                this.shutdown();
            }, 40L);
        }
    }


    public boolean finishConditionCheck() {
        if (isFinished) return true;
        if (life < 0) return true;

        double distance = this.ash.vent.getTwoDimensionalDistance(this.location);
        double maxDistance = Math.max(this.ash.vent.longestNormalLavaFlowLength + 20, this.ash.maxTravelDistance());

        int delta = (int) (Math.pow(Math.random(), 2) * 50);

        if (maxDistance + delta <= distance) {
            return true;
        }

        return false;
    }

    public void calculateDirection() {
        double prevDistance = this.ash.vent.getTwoDimensionalDistance(this.location);

        List<Block> blocks = VolcanoMath.getCircle(this.location.getBlock(), radius, radius - 1);
        Block lowestBlock = blocks.get(0);

        for (Block block : blocks) {
            if (block.getY() < lowestBlock.getY()) {
                if (this.ash.vent.getTwoDimensionalDistance(block.getLocation()) >= prevDistance) {
                    lowestBlock = block;
                }
            }
        }

        Vector target = lowestBlock.getLocation().subtract(this.location).toVector().normalize();
        direction.add(target.multiply(0.01));
    }

    public void runAsh() {
        this.processNearby();
        this.putAsh();
        this.playAshTrail();
    }

    public void processNearby() {
        Block baseBlock = TyphonUtils.getHighestRocklikes(this.location.getBlock());
        List<Block> blocks = VolcanoMath.getCube(baseBlock, radius);
        VolcanoMetamorphism metamorphism = this.ash.vent.volcano.metamorphism;
        for (Block block : blocks) {
            if (TyphonUtils.isMaterialTree(block.getType())) {
                if (TyphonUtils.toLowerCaseDumbEdition(block.getType().name()).contains("log")) {
                    metamorphism.removeTree(block);
                } else {
                    if (Math.random() < 0.25) {
                        metamorphism.removeTree(block);
                    } else {
                        this.ash.vent.lavaFlow.queueImmediateBlockUpdate(block, Material.AIR);
                    }
                }
            }

            if (metamorphism.isPlantlike(block.getType()) || metamorphism.isPlaceableAnimalEgg(block.getType())) {
                this.ash.vent.lavaFlow.queueImmediateBlockUpdate(block, Material.AIR);
            }
        }
    }

    public void putAsh() {
        // if it is near the summit, just do not put ash.
        if (this.ash.vent.getTwoDimensionalDistance(this.location) < 20 + this.ash.vent.getRadius()) {
            return;
        }

        List<Block> blocks = VolcanoMath.getCircle(this.location.getBlock(), radius);
        Vector srcDirection = new Vector().copy(this.direction);
        srcDirection.setY(0);

        Vector radiusVector = new Vector().copy(srcDirection).normalize().multiply(radius);
        Vector radiusVectorBack = new Vector().copy(radiusVector).multiply(-1);

        Block maxYBlock = TyphonUtils.getHighestRocklikes(this.location.add(radiusVectorBack).getBlock());
        Block minYBlock = TyphonUtils.getHighestRocklikes(this.location.add(radiusVector).getBlock());


        if (this.ash.vent.getTwoDimensionalDistance(this.location) > 130) {
            if (this.hasAshFell(maxYBlock) && !this.hasAshFell(minYBlock)) {
                maxYBlock = maxYBlock.getRelative(0, -2, 0);
            }
        }

        double slopeDistance = TyphonUtils.getTwoDimensionalDistance(minYBlock.getLocation(), maxYBlock.getLocation());
        double slope = Math.abs(maxYBlock.getY() - minYBlock.getY()) / slopeDistance;
        double maxPileup = this.maxPileup;

        /*
        this.ash.vent.getVolcano().logger.log(VolcanoLogClass.ASH,
                "Current maxY:"+maxYBlock.getY()+", minY:"+minYBlock.getY()+", distance:"+slopeDistance+" maxPileup: "+maxPileup+", slope: "+slope+", radius: "+radius+", location: "+TyphonUtils.blockLocationTostring(this.location.getBlock()));
        */

        double ashCoatStart = 0.5;
        double startAccumulate = 0.2;

        if (slope >= ashCoatStart) {
            // the slope is too steep. do not put ash.
            return;
        } else {
            if (slope >= startAccumulate) {
                double slopeMultiplier = 1 - ((slope - startAccumulate) / (ashCoatStart - startAccumulate));
                maxPileup = Math.min(1, this.maxPileup * Math.pow(slopeMultiplier, 2));
            } else {
                if (Math.random() < 0.05) {
                    maxPileup *= 0.95;
                }
            }

            if (maxPileup < 1) {
                maxPileup = 1;
            }
        }


        Block baseBlock = TyphonUtils.getHighestRocklikes(this.location.getBlock());
        for (Block block : blocks) {
            double distance = TyphonUtils.getTwoDimensionalDistance(baseBlock.getLocation(), block.getLocation());

            if (distance > radius) continue;
            double deduct = (maxPileup / (double) radius) * distance;

            int height = (int) Math.round(maxPileup - deduct);

            Block accumulateBase = TyphonUtils.getHighestRocklikes(block);
            for (int y = 1; y <= height; y++) {
                Block targetBlock = accumulateBase.getRelative(0, y, 0);
                if (targetBlock.getType().isAir() || TyphonUtils.containsWater(targetBlock)) {
                    this.ash.vent.lavaFlow.queueBlockUpdate(targetBlock, Material.TUFF);
                    this.ash.vent.record.addEjectaVolume(1);
                }
            }
        }
    }

    public void playAshTrail() {
        int ashTrailRadius = (int) (radius * 2);
        float radiusHalf = ashTrailRadius / 2.0f;


        BlockDisplay bd = location.getWorld().spawn(location, BlockDisplay.class, (_bd) -> {
            _bd.setBlock(Material.TUFF.createBlockData());
            _bd.setTransformation(new Transformation(
                    new Vector3f(-radiusHalf,-radiusHalf,-radiusHalf),
                    new AxisAngle4f(),
                    new Vector3f(ashTrailRadius, ashTrailRadius, ashTrailRadius),
                    new AxisAngle4f()
            ));
        });

        //Bukkit.getPlayer("Alex4386").teleport(location.add(0, 10, 0));

        pyroclasticClouds.add(bd);
        Bukkit.getScheduler().runTaskLater(TyphonPlugin.plugin, () -> {
            bd.remove();
        }, 60L);
    }

    public Block get2DBlock(Block block) {
        int y = ash.vent.location.getBlock().getY();
        return block.getRelative(0, y - block.getY(), 0);
    }

    public boolean hasAshFell(Block block) {
        Block flowedBlock = this.get2DBlock(block);
        if (flowedBlock == null) return false;
        return hasAshFell.get(flowedBlock) != null;
    }

    public void markAshFell(Block block) {
        Block flowedBlock = this.get2DBlock(block);
        if (flowedBlock == null) return;
        hasAshFell.put(flowedBlock, true);
    }
}

