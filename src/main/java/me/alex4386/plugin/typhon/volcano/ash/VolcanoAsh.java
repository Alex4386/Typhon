package me.alex4386.plugin.typhon.volcano.ash;

import me.alex4386.plugin.typhon.TyphonCache;
import me.alex4386.plugin.typhon.TyphonScheduler;
import me.alex4386.plugin.typhon.TyphonSounds;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoEruptStyle;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;

import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;


public class VolcanoAsh {
    VolcanoVent vent;

    public static int ashCloudScheduleId = -1;
    public static int updatesPerSeconds = 4;

    List<VolcanoPyroclasticFlow> pyroclasticFlows = new ArrayList<>();
    List<VolcanoAshCloudData> ashBlockDisplays = new ArrayList<>();

    private int queuedAshClouds = 0;
    private boolean shuttingDown = false;

    public void registerTask() {
        if (ashCloudScheduleId < 0) {
            ashCloudScheduleId = TyphonScheduler.registerGlobalTask(
                            (Runnable) () -> {
                                vent.ash.processQueuedAshPlume();
                                vent.ash.processAshClouds();
                            },
                            (long) 1L);
        }
    }

    public void unregisterTask() {
        if (ashCloudScheduleId >= 0) {
            TyphonScheduler.unregisterTask(ashCloudScheduleId);
            ashCloudScheduleId = -1;
        }
    }

    public VolcanoAsh(VolcanoVent vent) { this.vent = vent; }

    public void initialize() {
        shuttingDown = false;
        this.vent.volcano.logger.log(
                VolcanoLogClass.ASH, "Initializing VolcanoAsh for vent " + vent.getName());
        this.registerTask();
    }

    public void shutdown() {
        this.vent.volcano.logger.log(
                VolcanoLogClass.ASH, "Shutting down VolcanoAsh for vent " + vent.getName());
        this.queuedAshClouds = 0;
        this.unregisterTask();
        this.shutdownPyroclasticFlows();
        this.clearOrphanedAshClouds();
    }

    public void shutdownPyroclasticFlows() {
        shuttingDown = true;
        for (VolcanoPyroclasticFlow pyroclasticFlow : this.pyroclasticFlows) {
            pyroclasticFlow.shutdown(false);
        }
    }

    public int getTargetY(Location location) {
        Block coreBlock = vent.getNearestCoreBlock(location);
        int baseY = Math.max(coreBlock.getY(), vent.location.getWorld().getSeaLevel());
        int summitY = vent.getSummitBlock().getY();
        if (vent.caldera.isSettedUp()) {
            summitY = Math.max(summitY, TyphonUtils.getHighestRocklikes(coreBlock.getRelative(0, vent.caldera.radius, 0)).getY());
        }

        int heightY = Math.max(summitY - baseY, 0);
        if (heightY == 0) return baseY;

        int tmpSummit = heightY / 2;
        double distance = TyphonUtils.getTwoDimensionalDistance(coreBlock.getLocation(), location);
        double deductAmount = -(distance / 8);

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

    public VolcanoAshCloudData createAshCloud(Location loc, double ashMultiplier) {
        return createAshCloud(loc, ashMultiplier, 5);
    }
    public VolcanoAshCloudData createPumiceCloud(Location loc, double ashMultiplier) {
        return this.createAshCloudBlockDisplay(loc, ashMultiplier, 5, Material.NETHERRACK);
    }

    public VolcanoAshCloudData createAshCloud(Location loc, double ashMultiplier, float size) {
        return this.createAshCloudBlockDisplay(loc, ashMultiplier, size, Material.TUFF);
    }

    public VolcanoAshCloudData createAshCloudBlockDisplay(Location loc, double ashMultiplier, float size, Material material) {
        this.vent.getVolcano().logger.debug(VolcanoLogClass.ASH, "Created Ash Cloud @ "+TyphonUtils.blockLocationTostring(loc.getBlock()));
        float sizeHalf = size / 2;

        BlockDisplay result = loc.getWorld().spawn(loc, BlockDisplay.class, (bd) -> {
            bd.setBlock(material.createBlockData());
            bd.setTransformation(new Transformation(new Vector3f(-sizeHalf, -sizeHalf, -sizeHalf), new AxisAngle4f(), new Vector3f(size, size, size), new AxisAngle4f()));
            bd.setInvulnerable(true);
        });
        VolcanoAshCloudData data = new VolcanoAshCloudData(this, result, 1);
        ashBlockDisplays.add(data);

        return data;
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
        }

        if (this.ashBlockDisplays.size() > 0) {
            int count = 2 + (int) (Math.random() * 3);
            for (int i = 0; i < count * Math.floor(this.vent.erupt.getStyle().ashMultiplier); i++) {
                this.fallAsh();
            }
        }

        this.clearOrphanedAshClouds(false);
    }


    public Block getAshFallTarget() {
        double deadZone = this.vent.getRadius() + ((this.vent.getBasinLength() / 2) - this.vent.getRadius());
        if (vent.caldera.isForming()) {
            deadZone = this.vent.caldera.radius + 7;
        }

        double max = Math.max(this.vent.getBasinLength(), this.vent.longestNormalLavaFlowLength + 20);
        max = Math.max(max, this.vent.bombs.maxDistance);

        if (Math.random() < 0.3) {
            max = Math.max(max, this.vent.longestFlowLength);
        }

        double angle = Math.random() * 2 * Math.PI;
        double distance = (1 - VolcanoMath.getZeroFocusedRandom()) * (max - deadZone) + deadZone;
        double x = Math.cos(angle) * distance;
        double z = Math.sin(angle) * distance;

        return TyphonUtils.getHighestRocklikes(this.vent.getCoreBlock().getLocation().add(x, 0, z));
    }

    public void fallAsh() {
        Block target = this.getAshFallTarget();
        this.vent.volcano.logger.debug(VolcanoLogClass.ASH, "Falling Ash @ "+TyphonUtils.blockLocationTostring(target));

        int height = (int) Math.round(1 + (Math.random() * 2));
        int radius = height * (2 + (int) (Math.random() * 2));
        List<Block> circleBlock = VolcanoMath.getCircle(target, radius);

        if (target.getY() > this.getTargetY(target.getLocation())) {
            return;
        }

        TyphonUtils.smoothBlockHeights(target, radius, Material.TUFF);

        for (Block circle : circleBlock) {
            Block circleTarget = TyphonUtils.getHighestRocklikes(circle);
            Block highest = TyphonUtils.getHighestLocation(circleTarget.getLocation()).getBlock();
            if (highest.getY() > circleTarget.getY()) {
                for (Block block = highest; block.getY() > circleTarget.getY(); block = block.getRelative(0, -1 ,0)) {
                    if (!TyphonUtils.isMaterialRocklikes(block.getType())) {
                        if (!block.isEmpty() && !block.isLiquid()) {
                            this.vent.lavaFlow.queueBlockUpdate(block, Material.AIR);
                        }
                    }
                }
            }

            if (circleTarget.getY() + 1 > this.getTargetY(circleTarget.getLocation())) {
                continue;
            }

            double distance = TyphonUtils.getTwoDimensionalDistance(circle.getLocation(), target.getLocation());
            int thisHeight = (int) Math.round((1 - (distance / radius)) * height);

            for (int i = 0; i < thisHeight; i++) {
                Block pileUp = circleTarget.getRelative(0, i, 0);
                this.vent.lavaFlow.queueBlockUpdate(pileUp, Material.TUFF);
                this.vent.record.addEjectaVolume(1);
            }
        }
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

    public void processQueuedAshPlume() {
        if (queuedAshClouds > 0) {
            this.createAshPlume();
            queuedAshClouds--;
        }
    }

    public void createAshPlume(Location loc) {
        this.createAshPlume(loc, vent.erupt.getStyle().ashMultiplier);
    }

    public void createAshPlume(Location loc, double ashMultiplier) {
        float size = 2.5f;
        int length = 10;

        this.createAshCloud(loc, ashMultiplier, size);

        TyphonSounds.ASH_PLUME.play(loc, SoundCategory.BLOCKS, 10, 0.01f);
        Location tmp = loc.clone();
        float tmpSize = size;
        for (int i = 0; i < length; i++) {
            tmp.add(0, -0.5, 0);
            tmpSize /= (float) Math.sqrt(scalePerY);
            this.createAshCloud(tmp.clone(), ashMultiplier, tmpSize);
        }
    }

    Map<Block, TyphonCache<Block>> lowestCaches = new HashMap<>();

    public Block getPyroclasticFlowTargetBlock(boolean evenFlow) {
        Block coreBlock = this.vent.getCoreBlock();
        Block target = null;

        double sum = 0;
        int count = 0;

        if (evenFlow) {
            TyphonCache<Block> lowestCache = lowestCaches.get(coreBlock);
            if (lowestCache != null && !lowestCache.isExpired()) {
                target = lowestCache.getTarget();
            } else {
                List<Block> blocks = VolcanoMath.getAccurateHollowCircle(coreBlock, this.vent.getRadius());
                for (Block block : blocks) {
                    Block highest = TyphonUtils.getHighestRocklikes(block);

                    Location direction = block.getLocation().subtract(coreBlock.getLocation()).multiply(1.0 / this.vent.getRadius());
                    Block extended = TyphonUtils.getHighestRocklikes(coreBlock.getLocation().add(direction.clone().multiply(this.vent.getRadius() * 1.5)));
                    Block extendedHighest = TyphonUtils.getHighestRocklikes(extended);

                    double y = Math.max(highest.getY(), extendedHighest.getY());

                    if (target == null || target.getY() > y) {
                        target = highest;
                    }

                    sum += y;
                    count++;
                }

                if (target != null) {
                    double average = sum / count;
                    if (target.getY() < average - 5) {
                        target = null;
                    }

                    if (target != null) {
                        lowestCaches.put(coreBlock, new TyphonCache<>(target, 20));
                    }
                }
            }
        }

        if (target == null) {
            double angle = Math.random() * Math.PI * 2;
            double x = this.vent.getRadius() * Math.cos(angle);
            double z = this.vent.getRadius() * Math.sin(angle);

            target = TyphonUtils.getHighestRocklikes(coreBlock.getRelative((int) x, 0, (int) z));
        }

        return target;
    }

    public VolcanoPyroclasticFlow triggerPyroclasticFlow() {
        return this.triggerPyroclasticFlow(this.getPyroclasticFlowTargetBlock(Math.random() < 0.3));
    }

    public VolcanoPyroclasticFlow triggerPyroclasticFlow(Block srcblock) {
        if (shuttingDown) return null;

        Location target = srcblock.getLocation();
        target.setY(srcblock.getWorld().getMaxHeight());
        Block block = TyphonUtils.getHighestRocklikes(target);

        this.vent.getVolcano().logger.log(VolcanoLogClass.ASH, "Triggering Pyroclastic Flows @ "+TyphonUtils.blockLocationTostring(block));
        VolcanoPyroclasticFlow flow = new VolcanoPyroclasticFlow(TyphonUtils.getHighestRocklikes(block).getLocation().add(0, 1, 0), this);

        this.pyroclasticFlows.add(flow);
        flow.initialize();

        return flow;
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
                        EntityDamageEvent event = new EntityDamageEvent(entity, EntityDamageEvent.DamageCause.FIRE, DamageSource.builder(DamageType.IN_FIRE).build(), target.getHealth());
                        //target.setLastDamageCause(event);
                        target.setHealth(0.1);
                        target.setFireTicks(100);
                        if (!(entity instanceof Player)) entity.remove();
                    }
                }
            }
        }
    }
}