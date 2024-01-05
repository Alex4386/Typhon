package me.alex4386.plugin.typhon.volcano.ash;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoEruptStyle;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoCircleOffsetXZ;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;

import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.checkerframework.checker.units.qual.A;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;


public class VolcanoAsh {
    VolcanoVent vent;

    public static int ashCloudScheduleId = -1;
    public static int updatesPerSeconds = 4;

    List<VolcanoPyroclasticFlow> pyroclasticFlows = new ArrayList<>();
    List<VolcanoAshCloudData> ashBlockDisplays = new ArrayList<>();

    public void registerTask() {
        if (ashCloudScheduleId < 0) {
            ashCloudScheduleId = Bukkit.getScheduler()
                    .scheduleSyncRepeatingTask(
                            TyphonPlugin.plugin,
                            (Runnable) () -> {
                                vent.ash.processAshClouds();
                            },
                            0L,
                            (long) 1L);
        }
    }

    public void unregisterTask() {
        if (ashCloudScheduleId >= 0) {
            Bukkit.getScheduler().cancelTask(ashCloudScheduleId);
            ashCloudScheduleId = -1;
        }
    }

    public VolcanoAsh(VolcanoVent vent) { this.vent = vent; }

    public void initialize() {
        this.vent.volcano.logger.log(
                VolcanoLogClass.ASH, "Initializing VolcanoAsh for vent " + vent.getName());
        this.registerTask();
    }

    public void shutdown() {
        this.vent.volcano.logger.log(
                VolcanoLogClass.ASH, "Shutting down VolcanoAsh for vent " + vent.getName());
        this.unregisterTask();
        this.shutdownPyroclasticFlows();
        this.clearOrphanedAshClouds();
    }

    public void shutdownPyroclasticFlows() {
        for (VolcanoPyroclasticFlow pyroclasticFlow : this.pyroclasticFlows) {
            pyroclasticFlow.shutdown();
        }
    }

    public int getTargetY(Location location) {
        int baseY = Math.max(vent.location.getBlockY(), vent.location.getWorld().getSeaLevel());
        int heightY = Math.max(vent.getSummitBlock().getY() - baseY, 0);

        if (heightY == 0) return baseY;

        int tmpSummit = heightY / 2;
        double distance = TyphonUtils.getTwoDimensionalDistance(vent.getNearestCoreBlock(location).getLocation(), location);
        double deductAmount = -(distance / 12);

        return (int) (baseY + tmpSummit + deductAmount);
    }

    public int maxTravelDistance() {
        int baseY = Math.max(vent.location.getBlockY(), vent.location.getWorld().getSeaLevel());
        int heightY = Math.max(vent.getSummitBlock().getY() - baseY, 0);

        if (heightY == 0) return baseY;

        int tmpSummit = heightY / 2;
        return tmpSummit * 12;
    }

    public void createAshPlume() {
        VolcanoEruptStyle style = vent.erupt.getStyle();
        Block targetBlock = TyphonUtils.getHighestRocklikes(this.vent.selectCoreBlock());
        if (vent.getType() == VolcanoVentType.CRATER) {
            double angle = Math.random() * Math.PI * 2;
            int xOffset = (int) (Math.pow(Math.random(), 2) * Math.sin(angle) * 5);
            int zOffset = (int) (Math.pow(Math.random(), 2) * Math.cos(angle) * 5);
            targetBlock = TyphonUtils.getHighestRocklikes(this.vent.getCoreBlock().getRelative(xOffset, 0, zOffset));
        }

        if (style.ashMultiplier > 0) {
            this.createAshPlume(targetBlock.getRelative(BlockFace.UP).getLocation());
        }
    }

    public void createAshCloud(Location loc, double ashMultiplier) {
        createAshCloud(loc, ashMultiplier, 5);
    }

    public void createAshCloud(Location loc, double ashMultiplier, float size) {
        this.vent.getVolcano().logger.debug(VolcanoLogClass.ASH, "Created Ash Cloud @ "+TyphonUtils.blockLocationTostring(loc.getBlock()));
        float sizeHalf = size / 2;

        BlockDisplay result = loc.getWorld().spawn(loc, BlockDisplay.class, (bd) -> {
            bd.setBlock(Material.TUFF.createBlockData());
            bd.setTransformation(new Transformation(new Vector3f(-sizeHalf, -sizeHalf, -sizeHalf), new AxisAngle4f(), new Vector3f(size, size, size), new AxisAngle4f()));
            bd.setInvulnerable(true);
        });
        ashBlockDisplays.add(new VolcanoAshCloudData(this, result, ashMultiplier));
    }

    public static float ashCloudStep = 0.3f;
    public static float scalePerY = 1.1f;
    public static float life = 200;

    public void processAshCloud(VolcanoAshCloudData data) {
        BlockDisplay bd = data.bd;
        int yLimit = bd.getLocation().getWorld().getMaxHeight() + 100;
        this.vent.getVolcano().logger.debug(VolcanoLogClass.ASH, "Processing Ash Cloud @ "+TyphonUtils.blockLocationTostring(bd.getLocation().getBlock())+" (y: "+String.format("%.2f", bd.getLocation().getY())+", Lived: "+bd.getTicksLived()+")");

        if (bd.getTicksLived() > life * data.multiplier) {
            this.vent.getVolcano().logger.debug(VolcanoLogClass.ASH, "Removing Ash Cloud for living too long @ "+TyphonUtils.blockLocationTostring(bd.getLocation().getBlock())+" (y: "+String.format("%.2f", bd.getLocation().getY())+", Lived: "+bd.getTicksLived()+")");

            bd.remove();
            return;
        }

        Transformation transformation = bd.getTransformation();
        Vector3f scale = transformation.getScale().mul((float) Math.pow(scalePerY, ashCloudStep));

        float half = scale.x() / 2;
        Vector3f translation = new Vector3f(-half, -half, -half);

        double step = half * ashCloudStep;

        if (bd.getLocation().getWorld() != null) {
            if (bd.getLocation().getY() > yLimit) {
                double angle = Math.random() * 2 * Math.PI;

                bd.teleport(new Location(
                        bd.getWorld(),
                        bd.getLocation().getX() + (Math.sin(angle) * step),
                        yLimit,
                        bd.getLocation().getZ() + (Math.cos(angle) * step)
                ));
            } else {
                bd.teleport(bd.getLocation().add(0, step, 0));
            }
        }

        if (bd.getLocation().getY() >= yLimit && scale.x() >= 20) {
            scale.x = 20;
            scale.y = 20;
            scale.z = 20;
        }

        bd.setTransformation(new Transformation(
                translation,
                transformation.getLeftRotation(),
                scale,
                transformation.getRightRotation()
        ));

        processAshCloudHeat(bd);
    }

    public void processAshClouds() {
        this.vent.getVolcano().logger.debug(VolcanoLogClass.ASH, "Processing Ash Cloud...");

        for (VolcanoAshCloudData bd : this.ashBlockDisplays) {
            this.processAshCloud(bd);

            if (Math.random() < 0.1 * bd.multiplier) {
                bd.fallAsh();
            }
        }

        this.clearOrphanedAshClouds(false);
    }


    public void clearOrphanedAshClouds() {
        this.clearOrphanedAshClouds(true);
    }

    public void clearOrphanedAshClouds(boolean removeAll) {
        this.vent.getVolcano().logger.debug(VolcanoLogClass.ASH, "Clearing Ash Cloud...");
        Iterator<VolcanoAshCloudData> bdI = this.ashBlockDisplays.iterator();
        while (bdI.hasNext()) {
            VolcanoAshCloudData data = bdI.next();
            BlockDisplay bd = data.bd;

            if (!removeAll) {
                if (bd.isValid()) continue;
            }

            this.vent.getVolcano().logger.debug(VolcanoLogClass.ASH, "Clearing Ash Cloud @ "+TyphonUtils.blockLocationTostring(bd.getLocation().getBlock())+", isValid: "+bd.isValid());
            bd.remove();
            bdI.remove();
        }
    }

    public void createAshPlume(Location loc) {
        this.createAshPlume(loc, vent.erupt.getStyle().ashMultiplier);
    }

    public void createAshPlume(Location loc, double ashMultiplier) {
        float size = 2.5f;
        int length = 10;

        this.createAshCloud(loc, ashMultiplier, size);

        Location tmp = loc.clone();
        float tmpSize = size;
        for (int i = 0; i < length; i++) {
            tmp.add(0, -0.5, 0);
            tmpSize /= (float) Math.sqrt(scalePerY);
            this.createAshCloud(tmp.clone(), ashMultiplier, tmpSize);
        }

    }

    public void triggerPyroclasticFlow() {
        this.triggerPyroclasticFlow(this.vent.selectFlowVentBlock(Math.random() < 0.6));
    }

    public void triggerPyroclasticFlow(Block srcblock) {
        Location target = srcblock.getLocation();
        target.setY(srcblock.getWorld().getMaxHeight());
        Block block = TyphonUtils.getHighestRocklikes(target);

        this.vent.getVolcano().logger.log(VolcanoLogClass.ASH, "Triggering Pyroclastic Flows @ "+TyphonUtils.blockLocationTostring(block));
        VolcanoPyroclasticFlow flow = new VolcanoPyroclasticFlow(TyphonUtils.getHighestRocklikes(block).getLocation().add(0, 1, 0), this);
        this.pyroclasticFlows.add(flow);
        flow.initialize();
    }

    public int activePyroclasticFlows() {
        return this.pyroclasticFlows.size();
    }

    public void processAshCloudHeat(BlockDisplay bd) {
        if (bd.isValid()) {
            float radius = bd.getTransformation().getScale().x;

            // add lava particles in pyroclastic clouds
            for (int i = 0; i < 8 * Math.random(); i++) {
                double distance = Math.random() * radius;
                double theta = Math.random() * 2 * Math.PI;
                double theta2 = Math.random() * 2 * Math.PI;

                double x = distance * Math.sin(theta);
                double twoDimBit = distance * Math.cos(theta);

                double y = twoDimBit * Math.sin(theta2);
                double z = twoDimBit * Math.cos(theta2);

                Location particleLoc = bd.getLocation().clone();
                particleLoc.add(x,y,z);

                bd.getWorld().spawnParticle(Particle.LAVA, particleLoc, 1);
            }

            Collection<Entity> entities = bd.getWorld().getNearbyEntities(bd.getLocation(), radius, radius, radius);
            for (Entity entity : entities) {
                if (entity instanceof Damageable) {
                    Damageable target = (Damageable) entity;
                    if (!target.isInvulnerable() && target.getHealth() > 0 && target.isValid()) {
                        EntityDamageEvent event = new EntityDamageEvent(entity, EntityDamageEvent.DamageCause.FIRE, target.getHealth());
                        target.setLastDamageCause(event);
                        target.setHealth(0.1);
                        target.setFireTicks(100);
                        if (!(entity instanceof Player)) entity.remove();
                    }
                }
            }
        }
    }
}

class VolcanoAshCloudData {
    VolcanoAsh ash;
    public BlockDisplay bd;
    public double multiplier = 0.2;

    public VolcanoAshCloudData(VolcanoAsh ash, BlockDisplay bd, double multiplier) {
        this.ash = ash;
        this.bd = bd;
        this.multiplier = multiplier;
    }

    public Block getAshFallTarget() {
        double range = Math.pow(Math.random(), 2) * 5 * this.multiplier;
        double angle = Math.random() * 2 * Math.PI;
        return TyphonUtils.getHighestRocklikes(bd.getLocation().add(
                Math.sin(angle) * range,
                0,
                Math.cos(angle) * range
        ));
    }

    public void fallAsh() {
        if (!ash.vent.caldera.isForming()) {
            Block ashTarget = this.getAshFallTarget();
            if (ashTarget.getY() <= ash.getTargetY(ashTarget.getLocation())) {
                ashTarget.setType(Material.TUFF);
            }
        }
    }

    public void lightning() {
        bd.getWorld().strikeLightning(getAshFallTarget().getLocation());
    }
}


class VolcanoPyroclasticFlow {
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

    Map<Block, Integer> initialY = new HashMap<>();

    HashMap<Block, Boolean> flowedBlocks = new HashMap<>();
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
    }

    public void updateMinY() {
        this.minY = Math.min(this.minY, this.location.getBlockY());
    }

    public static Vector calculateInitialDirection(VolcanoVent vent, Location location) {
        Location tmpLoc = location.clone();
        return tmpLoc.subtract(vent.getNearestCoreBlock(location).getLocation()).toVector().normalize();
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
        this.ash.vent.getVolcano().logger.log(VolcanoLogClass.ASH, "Initializing pyroclastic flow @ "+TyphonUtils.blockLocationTostring(this.location.getBlock()));
        this.registerTask();
    }

    public void shutdown() {
        this.ash.vent.getVolcano().logger.log(VolcanoLogClass.ASH, "Shutting down pyroclastic flow @ "+TyphonUtils.blockLocationTostring(this.location.getBlock()));
        this.unregisterTask();
        this.removeAllPyroclasticClouds();

        this.ash.pyroclasticFlows.remove(this);
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

        this.location = this.location.add(direction.normalize().multiply(radius));
        this.location = TyphonUtils.getHighestRocklikes(this.location).getLocation();

        if (this.location.getY() > tmpLocation.getY() + (this.radius)) {
            this.location = tmpLocation;
            boolean whichWay = Math.random() < 0.5;

            // rotate vector angle 90 degrees
            double x = direction.getX();
            double z = direction.getZ();

            this.direction = new Vector(whichWay ? z : -z, 0, whichWay ? -x: x);
            this.location = this.location.add(direction.normalize().multiply(radius));

            this.location = TyphonUtils.getHighestRocklikes(this.location).getLocation();
            if (this.location.getY() > tmpLocation.getY() + (this.radius)) {
                this.location = tmpLocation;

                this.direction = new Vector(whichWay ? -z : z, 0, whichWay ? x: -x);
                this.location = this.location.add(direction.normalize().multiply(radius));

                this.location = TyphonUtils.getHighestRocklikes(this.location).getLocation();
                if (this.location.getY() > tmpLocation.getY() + (this.radius)) {
                    this.location = tmpLocation;
                    life = 0;
                }
            }
        }

        this.updateMinY();

        if (this.location.getY() >= prevLoc.getY()) {
            life -= ((this.location.getY() - prevLoc.getY()) + 1);
        }

        if (Math.random() < 0.05) {
            if (Math.random() < 0.1) {
                this.location.getWorld().playSound(this.location.add(0, 3, 0), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 4F, 0.5F);
            }
        }

        this.ash.vent.getVolcano().logger.debug(VolcanoLogClass.ASH, "ash trail @ "+TyphonUtils.blockLocationTostring(prevLoc.getBlock())+" -> "+TyphonUtils.blockLocationTostring(this.location.getBlock()));

        isFinished = this.finishConditionCheck();
        if (isFinished) {
            Bukkit.getScheduler().runTaskLater(TyphonPlugin.plugin, () -> {
                this.shutdown();
            }, 100L);
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
        this.putAsh();
        this.killTrees();
        this.playAshTrail();
    }

    public void killTrees() {
        List<Block> blocks = VolcanoMath.getSphere(this.location.getBlock(), radius);
        for (Block block : blocks) {
            if (TyphonUtils.isMaterialTree(block.getType())) {
                if (TyphonUtils.toLowerCaseDumbEdition(block.getType().name()).contains("log")) {
                    block.setType(Material.COAL_BLOCK);
                } else {
                    block.setType(Material.AIR);
                }
            }
        }
    }

    public int coreTargetY() {
        return this.ash.getTargetY(this.location);
    }

    public int getTargetY(Block block) {
        return this.coreTargetY() - (int) TyphonUtils.getTwoDimensionalDistance(block.getLocation(), this.location);
    }

    public void putAsh(Block block) {
        int target = this.getTargetY(block);

        Block zeroBlock = block.getRelative(0, -block.getY(), 0);
        if (initialY.get(zeroBlock) == null) {
            initialY.put(zeroBlock, block.getY());
        }

        int maximum = initialY.get(zeroBlock) + Math.min(this.radius / 2, 1) - (int) (TyphonUtils.getTwoDimensionalDistance(block.getLocation(), this.location) / 2);
        maximum = Math.min(target, maximum);

        if (block.getY() <= maximum) {
            int offset = maximum - block.getY();

            double distance = ash.vent.getTwoDimensionalDistance(block.getLocation());
            double craterRadius = ash.vent.craterRadius;

            if (distance < craterRadius * 1.5) {
                if (VolcanoComposition.isVolcanicRock(block.getType()) && block.getType() != Material.TUFF) {
                    block.setType(Material.TUFF);
                }
            } else if (distance < craterRadius * 3) {
                offset = Math.max(2, offset);
            }

            offset = Math.min((int) (this.radius * 1.5), offset);

            if (offset >= 2) {
                for (int i = 0; i < offset; i++) {
                    block.getRelative(0, i, 0).setType(Material.TUFF);
                }
            } else if (offset >= 1) {
                block.getRelative(BlockFace.UP).setType(Material.TUFF);
            } else if (TyphonUtils.isMaterialRocklikes(block.getType()) && block.getType() != Material.TUFF) {
                block.setType(Material.TUFF);
            }

            if (offset >= 1) {
                this.ash.vent.record.addEjectaVolume(offset);
            }
        } else {
            if (block.getY() > maximum + 2) {
                block.setType(Material.TUFF);
            } else {
                block.getRelative(BlockFace.UP).setType(Material.TUFF);
            }
        }

        //ash.createAshPlume(block.getLocation());
        flowedBlocks.put(getFlowedBlock(block), true);
    }

    public void putAsh() {
        List<Block> blocks = VolcanoMath.getCircle(this.location.getBlock(), radius);
        for (Block baseBlock : blocks) {
            if (Math.random() > (life / maxLife)) continue;
            Block block = TyphonUtils.getHighestRocklikes(baseBlock);

            this.putAsh(block);
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
        }, 100L);
    }

    public Block getFlowedBlock(Block block) {
        int y = ash.vent.location.getBlock().getY();
        return block.getRelative(0, y - block.getY(), 0);
    }

    public boolean checkIfFlowed(Block block) {
        Block flowedBlock = this.getFlowedBlock(block);
        if (flowedBlock == null) return false;
        return flowedBlocks.get(flowedBlock) != null;
    }
}
