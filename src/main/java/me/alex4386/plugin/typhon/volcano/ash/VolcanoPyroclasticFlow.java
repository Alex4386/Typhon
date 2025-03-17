package me.alex4386.plugin.typhon.volcano.ash;

import me.alex4386.plugin.typhon.*;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoEruptStyle;
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

import java.util.*;

public class VolcanoPyroclasticFlow {
    Location initLocation;
    Location location;
    Vector direction;
    VolcanoAsh ash;

    int minY = Integer.MAX_VALUE;

    int radius;
    int initRadius;
    static int maxRadius = 10;

    int life = 5;
    int maxDistance = -1;

    boolean isStarting = true;

    boolean isFinished = false;
    int scheduleID = -1;

    double maxPileup = 5;
    double awayCount = 0;

    Map<Block, Integer> initialY = new HashMap<>();

    HashMap<Block, Boolean> hasAshFell = new HashMap<>();
    List<BlockDisplay> pyroclasticClouds = new ArrayList<>();
    TyphonQueuedHashMap<Block, Block> initialBase = new TyphonQueuedHashMap<>(Integer.MAX_VALUE, TyphonQueuedHashMap::getTwoDimensionalBlock, null, false);

    public void setFull(boolean isFull) {
        if (isFull) {
            this.maxDistance = -1;
        } else {
            this.maxDistance = getMaxDistance(this.ash.vent);
        }
    }

    private Block getBase(Block block) {
        Block currentBase = initialBase.get(block);
        if (currentBase != null) {
            return currentBase;
        }

        Block lowestBlock = TyphonUtils.getHighestRocklikes(block);
        initialBase.put(block, lowestBlock);
        return lowestBlock;
    }

    public static int getMaxDistance(VolcanoVent vent) {
        if (vent.erupt.getStyle() == VolcanoEruptStyle.PLINIAN) {
            return -1;
        }

        if (Math.random() < vent.fullPyroclasticFlowProbability) {
            return -1;
        }

        double basinCalc = vent.longestNormalLavaFlowLength * 0.5;
        double base = Math.min(vent.bombs.getBaseY() * Math.sqrt(3), basinCalc);
        base = Math.max(200, base);

        double range = base * 0.2 * ((Math.pow(Math.random(), 4) * 6) + 1);
        double fullRange = base * 0.2 + range;

        return (int) ((base * 0.8) + (fullRange * Math.random()));
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
        this(location, ash, direction, radius, life, getMaxDistance(ash.vent));
    }

    public VolcanoPyroclasticFlow(Location location, VolcanoAsh ash, Vector direction, int radius, int life, int maxDistance) {
        this.minY = location.getBlockY();

        this.initLocation = location;
        this.location = location;
        this.direction = direction;
        this.ash = ash;
        this.radius = radius;
        this.initRadius = radius;
        this.life = life;
        this.maxDistance = maxDistance;
    }

    public void setDirection(Vector direction) {
        this.direction = direction;
    }

    public void registerTask() {
        if (scheduleID < 0) {
            this.scheduleID = TyphonScheduler.registerGlobalTask(() -> {
                this.runTick();
                this.processAllPyroclasticClouds();
            }, 2L);
        }
    }

    public void unregisterTask() {
        if (scheduleID >= 0) {
            TyphonScheduler.unregisterTask(this.scheduleID);
            this.scheduleID = -1;
        }
    }

    public void initialize() {
        this.ash.vent.getVolcano().logger.debug(VolcanoLogClass.ASH, "Initializing pyroclastic flow @ "+ TyphonUtils.blockLocationTostring(this.location.getBlock()));
        this.registerTask();
    }

    public void shutdown() {
        this.shutdown(true);
    }

    public void shutdown(boolean removeMe) {
        this.ash.vent.getVolcano().logger.debug(VolcanoLogClass.ASH, "Shutting down pyroclastic flow @ "+TyphonUtils.blockLocationTostring(this.location.getBlock()));
        this.unregisterTask();
        this.removeAllPyroclasticClouds();

        this.ash.pyroclasticFlows.remove(this);
    }

    public void updateMinY() {
        this.minY = Math.min(this.minY, this.location.getBlockY());
    }

    public static Vector calculateInitialDirection(VolcanoVent vent, Location location) {
        Block coreBlock = vent.getNearestCoreBlock(location);
        Location tmpCoreLoc = coreBlock.getRelative(0, -coreBlock.getY(), 0).getLocation();

        Block srcBlock = location.getBlock();
        Block srcZeroBlock = srcBlock.getRelative(0, -srcBlock.getY(), 0);
        Vector direction = srcZeroBlock.getLocation().toVector().subtract(tmpCoreLoc.toVector());

        Vector baseDirection = direction.normalize();
        double randomizer = Math.random() * 2 - 1;

        Vector rightAngleDirection = new Vector(baseDirection.getZ(), 0, -baseDirection.getX()).normalize().multiply(randomizer * 0.1);
        Vector targetDirection = baseDirection.add(rightAngleDirection).normalize();

        return targetDirection.multiply(1.5 + Math.random());
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

        double distance = TyphonUtils.getTwoDimensionalDistance(initLocation, this.location);
        if (distance - awayCount > 50) {
            if (Math.random() < 0.2) {
                if (radius < maxRadius) {
                    radius++;

                    if (maxDistance > 0) {
                        if (maxPileup > 1) {
                            maxPileup -= 1;
                        }
                    }

                    awayCount = distance;
                }
            }
        }

        if (maxDistance >= 0 && distance > maxDistance) {
            life = 0;
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

        double forward = Math.max(1, radius / (2.5 + (Math.random() * 1.5)));

        Vector copiedDirection = new Vector().copy(this.direction);
        copiedDirection.multiply(forward);

        this.location = this.location.add(copiedDirection);
        this.location = this.getBase(this.location.getBlock()).getLocation();

        int climbupLimit = Math.max(1, (int) (this.radius * 1.5));

        if (this.location.getY() > tmpLocation.getY() + climbupLimit) {
            this.location = tmpLocation;
            boolean whichWay = Math.random() < 0.5;

            // rotate vector angle 90 degrees
            double x = direction.getX();
            double z = direction.getZ();

            this.direction = new Vector(whichWay ? z : -z, 0, whichWay ? -x: x);
            this.location = this.location.add(copiedDirection);

            this.location = this.getBase(this.location.getBlock()).getLocation();
            if (this.location.getY() > tmpLocation.getY() + climbupLimit) {
                this.location = tmpLocation;

                this.direction = new Vector(whichWay ? -z : z, 0, whichWay ? x: -x);
                this.location = this.location.add(copiedDirection);

                this.location = this.getBase(this.location.getBlock()).getLocation();
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

        if (this.getPercentage() > 0.95) {
            if (Math.random() < 0.1) {
                this.radius--;
            }
        }

        isFinished = this.finishConditionCheck();
        if (isFinished) {
            TyphonScheduler.runDelayed(this.location.getChunk(), () -> {
                this.shutdown();
            }, 40L);
        }
    }

    public double getMaxDistance() {
        return Math.max(this.ash.vent.getBasinLength() + 20, this.ash.maxTravelDistance());
    }

    public double getPercentage() {
        double distance = this.ash.vent.getTwoDimensionalDistance(this.location);
        double maxDistance = this.getMaxDistance();

        return distance / maxDistance;
    }

    public boolean finishConditionCheck() {
        if (isFinished) return true;
        if (life < 0) return true;

        double distance = this.ash.vent.getTwoDimensionalDistance(this.location);
        double maxDistance = this.getMaxDistance();

        int delta = (int) (Math.pow(Math.random(), 2) * 50);

        if (maxDistance + delta <= distance) {
            return true;
        }

        return false;
    }

    public void calculateDirection() {
        double prevDistance = this.ash.vent.getTwoDimensionalDistance(this.location);

        List<Block> blocks = VolcanoMath.getHollowCircle(this.location.getBlock(), radius);
        Block currentBlock = TyphonUtils.getHighestRocklikes(this.location);

        Location directionTarget =
                this.location.add(direction.clone().normalize().multiply(radius));
        Block directionBlock = TyphonUtils.getHighestRocklikes(directionTarget);
        if (directionBlock.getY() <= currentBlock.getY()) {
            // no problem. continue this way.
            return;
        }

        Block lowestBlock = TyphonUtils.getHighestRocklikes(blocks.get(0));
        for (Block block : blocks) {
            Block thisBlock = TyphonUtils.getHighestRocklikes(block);
            if (thisBlock.getY() < lowestBlock.getY()) {
                if (this.ash.vent.getTwoDimensionalDistance(block.getLocation()) >= prevDistance) {
                    lowestBlock = block;
                }
            }
        }


        Vector target = lowestBlock.getLocation().subtract(this.location).toVector().setY(0).normalize();
        direction.add(target.multiply(0.05));
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
                metamorphism.removeTree(block);
            }

            if (metamorphism.isPlantlike(block.getType()) || metamorphism.isPlaceableAnimalEgg(block.getType())) {
                this.ash.vent.lavaFlow.queueImmediateBlockUpdate(block, Material.AIR);
            }
        }
    }

    public void putAsh() {
        // if it is near the summit, just do not put ash.
        if (this.ash.vent.getTwoDimensionalDistance(this.location) < this.ash.vent.getRadius()) {
            return;
        }

        Block baseBlock = this.getBase(this.location.getBlock());
        double safeDistance = TyphonUtils.getTwoDimensionalDistance(initLocation, this.location);

        // Calculate the maximum height based on summit and slope
        double summitY = Math.max(initLocation.getY(), this.ash.vent.getSummitBlock().getY());
        double baseY = Math.max(this.ash.vent.location.getY(), this.ash.vent.location.getWorld().getSeaLevel());

        double basin = Math.max(this.ash.vent.getBasinLength(), this.ash.vent.longestNormalLavaFlowLength);
        double scale = (summitY - baseY) / basin;

        summitY -= initRadius * scale;  // Scale down based on initial radius
        double maxHeight = summitY - (safeDistance * scale);

        // Get normalized direction vector
        Vector srcDirection = new Vector().copy(this.direction).normalize();
        srcDirection.setY(0);

        // Create a set to avoid duplicate block updates
        Set<Block> processedBlocks = new HashSet<>();

        // Step through blocks in the radius
        for (double x = -this.radius; x <= this.radius; x++) {
            for (double z = -this.radius; z <= this.radius; z++) {
                // Rotate coordinates to align with flow direction
                double rotatedX = x * srcDirection.getX() + z * srcDirection.getZ();
                double rotatedZ = x * srcDirection.getZ() - z * srcDirection.getX();

                Block targetBase = baseBlock.getRelative((int)rotatedX, 0, (int)rotatedZ);
                if (processedBlocks.contains(targetBase)) continue;
                processedBlocks.add(targetBase);

                // Calculate ash height based on distance from center line
                double distanceFromCenterLine = Math.abs(x);
                double heightFactor = 1.0 - (distanceFromCenterLine / this.radius);
                heightFactor = Math.max(0, heightFactor);
                int ashHeight = (int)(this.initRadius / 2.0 * heightFactor);

                // Check if this would exceed the slope max height
                Block baseBlockHere = this.getBase(targetBase);
                if (baseBlockHere.getY() > maxHeight) {
                    continue;
                }

                // Place ash blocks vertically
                for (int y = 1; y <= ashHeight; y++) {
                    Block targetBlock = baseBlockHere.getRelative(0, y, 0);
                    if (targetBlock.getY() > Math.min(baseBlock.getY() + ashHeight, maxHeight)) {
                        continue;
                    }
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
        TyphonScheduler.runDelayed(bd.getChunk(), () -> {
            bd.remove();
        }, 60L);
    }

    public Block get2DBlock(Block block) {
        int y = ash.vent.location.getBlock().getY();
        return block.getRelative(0, y - block.getY(), 0);
    }
}
