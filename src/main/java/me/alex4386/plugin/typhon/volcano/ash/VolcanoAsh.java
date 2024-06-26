package me.alex4386.plugin.typhon.volcano.ash;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonSounds;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoEruptStyle;
import me.alex4386.plugin.typhon.volcano.intrusions.VolcanoMetamorphism;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoCircleOffsetXZ;
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

    private int queuedAshClouds = 0;

    public void registerTask() {
        if (ashCloudScheduleId < 0) {
            ashCloudScheduleId = Bukkit.getScheduler()
                    .scheduleSyncRepeatingTask(
                            TyphonPlugin.plugin,
                            (Runnable) () -> {
                                vent.ash.processQueuedAshPlume();
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
        this.queuedAshClouds = 0;
        this.unregisterTask();
        this.shutdownPyroclasticFlows();
        this.clearOrphanedAshClouds();
    }

    public void shutdownPyroclasticFlows() {
        for (VolcanoPyroclasticFlow pyroclasticFlow : this.pyroclasticFlows) {
            pyroclasticFlow.shutdown(false);
        }
    }

    public int getTargetY(Location location) {
        int baseY = Math.max(vent.location.getBlockY(), vent.location.getWorld().getSeaLevel());
        int heightY = Math.max(vent.getSummitBlock().getY() - baseY, 0);

        if (heightY == 0) return baseY;

        int tmpSummit = heightY / 2;
        double distance = TyphonUtils.getTwoDimensionalDistance(vent.getNearestCoreBlock(location).getLocation(), location);
        double deductAmount = -(distance / 10);

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
    public void createPumiceCloud(Location loc, double ashMultiplier) {
        this.createAshCloudBlockDisplay(loc, ashMultiplier, 5, Material.NETHERRACK);
    }

    public void createAshCloud(Location loc, double ashMultiplier, float size) {
        this.createAshCloudBlockDisplay(loc, ashMultiplier, size, Material.TUFF);
    }

    public void createAshCloudBlockDisplay(Location loc, double ashMultiplier, float size, Material material) {
        this.vent.getVolcano().logger.debug(VolcanoLogClass.ASH, "Created Ash Cloud @ "+TyphonUtils.blockLocationTostring(loc.getBlock()));
        float sizeHalf = size / 2;

        BlockDisplay result = loc.getWorld().spawn(loc, BlockDisplay.class, (bd) -> {
            bd.setBlock(material.createBlockData());
            bd.setTransformation(new Transformation(new Vector3f(-sizeHalf, -sizeHalf, -sizeHalf), new AxisAngle4f(), new Vector3f(size, size, size), new AxisAngle4f()));
            bd.setInvulnerable(true);
        });
        ashBlockDisplays.add(new VolcanoAshCloudData(this, result, 1));
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

        if (Math.random() < 0.002) {
            // do lightning!
            data.lightning();
        }

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

        TyphonSounds.DISTANT_EXPLOSION.play(loc, SoundCategory.BLOCKS, 10, 0.01f);
        Location tmp = loc.clone();
        float tmpSize = size;
        for (int i = 0; i < length; i++) {
            tmp.add(0, -0.5, 0);
            tmpSize /= (float) Math.sqrt(scalePerY);
            this.createAshCloud(tmp.clone(), ashMultiplier, tmpSize);
        }

    }

    public void triggerPyroclasticFlow() {
        this.triggerPyroclasticFlow(this.vent.selectFlowVentBlock(Math.random() < 0.1));
    }

    public VolcanoPyroclasticFlow triggerPyroclasticFlow(Block srcblock) {
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

class VolcanoAshCloudData {
    VolcanoAsh ash;
    public BlockDisplay bd;
    public double multiplier = 0.2;

    public int maxHeight;

    HashMap<Block, Boolean> hasAshFell = new HashMap<>();

    public VolcanoAshCloudData(VolcanoAsh ash, BlockDisplay bd, double multiplier) {
        this(ash, bd, multiplier, ash.vent.location.getWorld().getMaxHeight() + 100);
    }

    public VolcanoAshCloudData(VolcanoAsh ash, BlockDisplay bd, double multiplier, int maxHeight) {
        this.ash = ash;
        this.bd = bd;
        this.multiplier = multiplier;
        this.maxHeight = maxHeight;
    }

    public Block getAshFallTarget() {
        double range = Math.random() * 5 * this.multiplier;
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
            if (hasAshFell.get(ashTarget) != null) return;

            if (ashTarget.getY() <= ash.getTargetY(ashTarget.getLocation())) {
                this.ash.vent.lavaFlow.queueBlockUpdate(ashTarget, Material.TUFF);
            }
        }
    }

    public void lightning() {
        bd.getWorld().strikeLightning(getAshFallTarget().getLocation());
    }
}

