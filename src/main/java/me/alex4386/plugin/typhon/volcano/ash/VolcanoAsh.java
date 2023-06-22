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

    public static int ashFallScheduleId = -1;
    public static int ashPlumeScheduleId = -1;
    public static int ashCloudScheduleId = -1;
    public static int updatesPerSeconds = 4;

    List<VolcanoPyroclasticFlow> pyroclasticFlows = new ArrayList<>();
    List<BlockDisplay> ashBlockDisplays = new ArrayList<>();

    public void registerTask() {
        if (ashPlumeScheduleId < 0) {
            ashPlumeScheduleId = Bukkit.getScheduler()
                    .scheduleSyncRepeatingTask(
                            TyphonPlugin.plugin,
                            (Runnable) () -> {
                                if (vent.erupt.isErupting()) {
                                    vent.ash.createAshPlume();
                                }
                            },
                            0L,
                            (long) 2L);
        }

        if (ashFallScheduleId < 0) {
            ashFallScheduleId = Bukkit.getScheduler()
                    .scheduleSyncRepeatingTask(
                            TyphonPlugin.plugin,
                            (Runnable) () -> {
                                if (vent.erupt.isErupting()) {
                                    vent.ash.triggerAshFall();
                                }
                            },
                            0L,
                            (long) this.vent.volcano.updateRate);
        }


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
        if (ashPlumeScheduleId >= 0) {
            Bukkit.getScheduler().cancelTask(ashPlumeScheduleId);
            ashPlumeScheduleId = -1;
        }
        if (ashFallScheduleId >= 0) {
            Bukkit.getScheduler().cancelTask(ashFallScheduleId);
            ashFallScheduleId = -1;
        }
        if (ashCloudScheduleId >= 0) {
            Bukkit.getScheduler().cancelTask(ashCloudScheduleId);
            ashCloudScheduleId = -1;
        }
    }

    public VolcanoAsh(VolcanoVent vent) {
        this.vent = vent;
    }

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

    public Location getRandomAshPlumeLocation() {
        VolcanoEruptStyle style = vent.erupt.getStyle();
        if (style == VolcanoEruptStyle.HAWAIIAN || style == VolcanoEruptStyle.STROMBOLIAN) {
            return TyphonUtils.getHighestLocation(this.vent.selectCoreBlock().getLocation())
                    .add(0, 1, 0);
        }

        double baseRadius = vent.craterRadius;
        Block targetBlock = this.vent.selectCoreBlock();

        int y = TyphonUtils.getHighestLocation(targetBlock.getLocation()).getBlockY();

        World world = vent.location.getWorld();

        int targetY = world.getSeaLevel();
        double heightPercent = Math.random() * Math.random();

        if (y < world.getSeaLevel()) {
            int diff = (vent.location.getWorld().getMaxHeight() - world.getSeaLevel());
            targetY = targetY + (int) (diff * heightPercent);
        } else {
            int diff = (vent.location.getWorld().getMaxHeight() - y);
            targetY = y + (int) (diff * heightPercent);
        }

        double multiplier = style.bombMultiplier * 0.4 + 0.8;
        double plumeRadius = baseRadius * (1 + (multiplier * heightPercent));

        double currentRadius = plumeRadius * Math.random();
        double angle = Math.random() * 2 * Math.PI;

        Location location = new Location(
                targetBlock.getWorld(),
                targetBlock.getX() + (currentRadius * Math.sin(angle)),
                targetY,
                targetBlock.getZ() + (currentRadius * Math.cos(angle))
        );

        return location;
    }

    public void createAshPlume() {
        VolcanoEruptStyle style = vent.erupt.getStyle();
        if (style.ashMultiplier >= 1) {
            Block targetBlock = TyphonUtils.getHighestRocklikes(this.vent.getCoreBlock());
            if (this.vent.getType() == VolcanoVentType.CRATER) {
                targetBlock = TyphonUtils.getHighestRocklikes(TyphonUtils.getRandomBlockInRange(targetBlock, 0, (int) (this.vent.craterRadius * 0.7)));
            }
            this.createAshPlume(targetBlock.getRelative(BlockFace.UP).getLocation());
        }
    }

    public void createAshCloud(Location loc) {
        this.vent.getVolcano().logger.debug(VolcanoLogClass.ASH, "Created Ash Cloud @ "+TyphonUtils.blockLocationTostring(loc.getBlock()));
        float size = 5;
        float sizeHalf = size / 2;

        BlockDisplay result = loc.getWorld().spawn(loc, BlockDisplay.class, (bd) -> {
            bd.setBlock(Material.TUFF.createBlockData());
            bd.setTransformation(new Transformation(new Vector3f(-sizeHalf, -sizeHalf, -sizeHalf), new AxisAngle4f(), new Vector3f(size, size, size), new AxisAngle4f()));
            bd.setInvulnerable(true);
        });
        ashBlockDisplays.add(result);
    }

    public static float ashCloudStep = 0.3f;
    public static float scalePerY = 1.1f;
    public static float life = 200;

    public void processAshCloud(BlockDisplay bd) {
        int yLimit = bd.getLocation().getWorld().getMaxHeight() + 100;
        this.vent.getVolcano().logger.debug(VolcanoLogClass.ASH, "Processing Ash Cloud @ "+TyphonUtils.blockLocationTostring(bd.getLocation().getBlock())+" (y: "+String.format("%.2f", bd.getLocation().getY())+", Lived: "+bd.getTicksLived()+")");

        if (bd.getTicksLived() > life * this.vent.erupt.getStyle().ashMultiplier) {
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

        for (BlockDisplay bd : this.ashBlockDisplays) {
            this.processAshCloud(bd);
        }

        this.clearOrphanedAshClouds(false);
    }


    public void clearOrphanedAshClouds() {
        this.clearOrphanedAshClouds(true);
    }

    public void clearOrphanedAshClouds(boolean removeAll) {
        this.vent.getVolcano().logger.debug(VolcanoLogClass.ASH, "Clearing Ash Cloud...");
        Iterator<BlockDisplay> bdI = this.ashBlockDisplays.iterator();
        while (bdI.hasNext()) {
            BlockDisplay bd = bdI.next();

            if (!removeAll) {
                if (bd.isValid()) continue;
            }

            this.vent.getVolcano().logger.debug(VolcanoLogClass.ASH, "Clearing Ash Cloud @ "+TyphonUtils.blockLocationTostring(bd.getLocation().getBlock())+", isValid: "+bd.isValid());
            bd.remove();
            bdI.remove();
        }
    }

    public void createAshPlume(Location loc) {
        VolcanoEruptStyle style = vent.erupt.getStyle();
        if (style == VolcanoEruptStyle.HAWAIIAN) {
            if (Math.random() < 0.5) {
                TyphonUtils.spawnParticleWithVelocity(Particle.WHITE_ASH, loc, 0, 5, 0, 0.25, 0);
            }
        } else if (style == VolcanoEruptStyle.STROMBOLIAN
                || style == VolcanoEruptStyle.VULCANIAN
                || style == VolcanoEruptStyle.PELEAN) {
            this.createAshCloud(loc);
        }
    }

    public void triggerPyroclasticFlow() {
        this.triggerPyroclasticFlow(this.vent.selectFlowVentBlock(Math.random() < 0.6));
    }

    public void triggerRandomAshFall() {
        if (vent.caldera.isForming()) return;

        double radiusRatio = 1 - Math.pow(Math.random(), 2);

        double coneSlopeRatio = (-1 * Math.sqrt(3 * Math.abs(radiusRatio) / 2)) + 1;
        double coneBaseSlopeRatio = -1 * (Math.abs(radiusRatio) / 3) + (1/3);

        double targetHeightRatio = Math.max(coneSlopeRatio, coneBaseSlopeRatio);
        targetHeightRatio = Math.max(0, Math.min(1, targetHeightRatio));

        double targetRadius = ((vent.longestNormalLavaFlowLength - vent.getRadius()) * radiusRatio) + vent.getRadius();

        int coneHeight = vent.getSummitBlock().getY() - vent.location.getBlockY();
        double targetConeHeight = targetHeightRatio * coneHeight;

        double targetY = vent.location.getBlockY() + targetConeHeight;

        double angle = Math.random() * 2 * Math.PI;
        Block targetBlock = vent.getCoreBlock();

        Location location = new Location(
                targetBlock.getWorld(),
                targetBlock.getX() + (targetRadius * Math.sin(angle)),
                targetBlock.getY(),
                targetBlock.getZ() + (targetRadius * Math.cos(angle))
        );

        Block surfaceBlock = TyphonUtils.getHighestRocklikes(location);

        // cone building ash fall
        if (surfaceBlock.getY() + 1 < targetY) {
            surfaceBlock.getRelative(BlockFace.UP).setType(Material.TUFF);
            //System.out.println("Ash is falling @ "+TyphonUtils.blockLocationTostring(surfaceBlock));
        }
    }

    public void triggerPyroclasticFlow(Block block) {
        this.vent.getVolcano().logger.log(VolcanoLogClass.ASH, "Triggering Pyroclastic Flows @ "+TyphonUtils.blockLocationTostring(block));
        VolcanoPyroclasticFlow flow = new VolcanoPyroclasticFlow(TyphonUtils.getHighestRocklikes(block).getLocation().add(0, 1, 0), this);
        this.pyroclasticFlows.add(flow);
        flow.initialize();
    }

    public void triggerAshFall() {
        triggerAshFall(
                TyphonUtils.getHighestLocation(this.vent.selectCoreBlock().getLocation())
                        .add(0, 1, 0));
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

    public void triggerAshFall(Location loc) {
        VolcanoEruptStyle style = vent.erupt.getStyle();

        if (style == VolcanoEruptStyle.STROMBOLIAN
                || style == VolcanoEruptStyle.VULCANIAN
                || style == VolcanoEruptStyle.PELEAN) {
            double multiplier = style.ashMultiplier;

            for (int i = 0; i < multiplier; i++) {
                VolcanoCircleOffsetXZ xz = VolcanoMath.getCenterFocusedCircleOffset(
                        loc.getBlock(),
                        this.vent.getRadius(),
                        (int) Math.round(this.vent.longestNormalLavaFlowLength * 0.5 * multiplier));
                Block target = TyphonUtils.getHighestRocklikes(loc.add(xz.x, 1, xz.z));
                Location finalLoc = target.getRelative(0,5,0).getLocation();

                TyphonUtils.spawnParticleWithVelocity(
                        Particle.CAMPFIRE_SIGNAL_SMOKE,
                        finalLoc,
                        0,
                        (int) (3 * multiplier),
                        0,
                        -0.4,
                        0);

                boolean shouldDoIt = true;
                if (this.vent.volcano.manager.isInAnyFormingCaldera(loc)) {
                    shouldDoIt = false;
                }

                if (shouldDoIt) {
                    target.getRelative(BlockFace.UP).setType(Material.TUFF);
                    vent.record.addEjectaVolume(1);
                }
            }
        }
    }
}

class VolcanoPyroclasticFlow {
    Location location;
    Vector direction;
    VolcanoAsh ash;

    int radius;
    static int maxRadius = 8;

    int life = 5;
    static int maxLife = 30;

    boolean isStarting = true;

    boolean isFinished = false;
    int scheduleID = -1;

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
        this.location = location;
        this.direction = direction;
        this.ash = ash;
        this.radius = radius;
        this.life = life;
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

        this.location = this.location.add(direction.normalize().multiply(radius));
        this.location = TyphonUtils.getHighestRocklikes(this.location).getLocation();

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
        double maxDistance = this.ash.vent.longestNormalLavaFlowLength + 20;
        if (maxDistance <= distance) {
            return true;
        }

        return false;
    }

    public int getTargetHeight(Location location) {
        double distance = ash.vent.getTwoDimensionalDistance(location);
        double height = Math.max(0, ash.vent.getSummitBlock().getY() - ash.vent.location.getY());
        if (distance > (4 * Math.sqrt(3) * height / 3)) return 0;

        return (int) ((Math.pow(((3 * (distance / height)) / 4) - Math.sqrt(3), 2) / 3) * height);
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

    public void putAsh(Block block) {
        if (!checkIfFlowed(block)) {
            if (block.getY() >= block.getWorld().getSeaLevel()) {
                int target = this.getTargetHeight(block.getLocation());
                if (block.getY() < target) {
                    int offset = target - block.getY();

                    double distance = ash.vent.getTwoDimensionalDistance(block.getLocation());
                    double craterRadius = ash.vent.craterRadius;

                    if (distance < craterRadius * 1.5) {
                        if (VolcanoComposition.isVolcanicRock(block.getType())) {
                            block.setType(Material.TUFF);
                        }
                    } else if (distance < craterRadius * 3) {
                        offset = Math.max(2, offset);
                    }

                    if (offset >= 2) {
                        for (int i = 0; i < Math.min(radius, offset); i++) {
                            block.getRelative(0, i, 0).setType(Material.TUFF);
                        }
                    } else if (offset >= 1) {
                        block.getRelative(BlockFace.UP).setType(Material.TUFF);
                    } else if (VolcanoComposition.isVolcanicRock(block.getType())) {
                        block.setType(Material.TUFF);
                    }
                } else {
                    if (block.getY() > target + 2) {
                        block.setType(Material.TUFF);
                    } else {
                        block.getRelative(BlockFace.UP).setType(Material.TUFF);
                    }
                }
            } else {
                if (block.getY() >= block.getWorld().getSeaLevel() - 3) {
                    for (int i = 0; i < block.getWorld().getSeaLevel() - block.getY(); i++) {
                        block.getRelative(0, i, 0).setType(Material.AIR);
                    }
                }
            }

            //ash.createAshPlume(block.getLocation());
            flowedBlocks.put(getFlowedBlock(block), true);
        }
    }

    public void putAsh() {
        List<Block> blocks = VolcanoMath.getCircle(this.location.getBlock(), radius);
        for (Block baseBlock : blocks) {
            if (Math.random() > (life / maxLife)) continue;
            Block block = TyphonUtils.getHighestRocklikes(baseBlock);
            this.putAsh(block);
        }

        List<Block> extBlocks = VolcanoMath.getCircle(this.location.getBlock(), radius + 3, radius);
        for (Block baseBlock : extBlocks) {
            if (Math.random() < 0.5) continue;

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
        if (flowedBlocks.get(flowedBlock) == null) return false;
        return true;
    }
}
