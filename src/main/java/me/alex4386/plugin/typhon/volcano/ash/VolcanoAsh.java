package me.alex4386.plugin.typhon.volcano.ash;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoEruptStyle;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoCircleOffsetXZ;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.util.*;

public class VolcanoAsh {
    VolcanoVent vent;

    public static int ashFallScheduleId = -1;
    public static int updatesPerSeconds = 4;

    List<VolcanoPyroclasticFlow> pyroclasticFlows = new ArrayList<>();

    public void registerTask() {
        if (ashFallScheduleId < 0) {
            ashFallScheduleId = Bukkit.getScheduler()
                    .scheduleSyncRepeatingTask(
                            TyphonPlugin.plugin,
                            (Runnable) () -> {
                                if (vent.erupt.isErupting()) {
                                    vent.ash.createAshPlume();
                                    vent.ash.triggerAshFall();
                                }
                            },
                            0L,
                            (long) TyphonPlugin.minecraftTicksPerSeconds
                                    / updatesPerSeconds);
        }
    }

    public void unregisterTask() {
        if (ashFallScheduleId >= 0) {
            Bukkit.getScheduler().cancelTask(ashFallScheduleId);
            ashFallScheduleId = -1;
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
    }

    public void shutdownPyroclasticFlows() {
        for (VolcanoPyroclasticFlow pyroclasticFlow : this.pyroclasticFlows) {
            pyroclasticFlow.shutdown();
        }
    }

    public void createAshPlume() {
        createAshPlume(
                TyphonUtils.getHighestLocation(this.vent.selectCoreBlock().getLocation())
                        .add(0, 1, 0));
    }

    public void createAshPlume(Location loc) {
        VolcanoEruptStyle style = vent.erupt.getStyle();
        if (style == VolcanoEruptStyle.HAWAIIAN) {
            TyphonUtils.spawnParticleWithVelocity(Particle.WHITE_ASH, loc, 0, 5, 0, 0.25, 0);
        } else if (style == VolcanoEruptStyle.STROMBOLIAN
                || style == VolcanoEruptStyle.VULCANIAN
                || style == VolcanoEruptStyle.PELEAN) {
            double multiplier = style.ashMultiplier;

            TyphonUtils.spawnParticleWithVelocity(
                    Particle.CAMPFIRE_COSY_SMOKE,
                    loc,
                    0,
                    (int) (3 * multiplier),
                    0,
                    0.4,
                    0);
        }
    }

    public void triggerPyroclasticFlow() {
        this.triggerPyroclasticFlow(this.vent.selectFlowVentBlock(Math.random() < 0.6));
    }

    public void triggerPyroclasticFlow(Block block) {
        VolcanoPyroclasticFlow flow = new VolcanoPyroclasticFlow(block.getLocation().add(0, 1, 0), this);
        this.pyroclasticFlows.add(flow);
    }

    public void triggerAshFall() {
        triggerAshFall(
                TyphonUtils.getHighestLocation(this.vent.selectCoreBlock().getLocation())
                        .add(0, 1, 0));
    }

    public void triggerAshFall(Location loc) {
        VolcanoEruptStyle style = vent.erupt.getStyle();

        if (style == VolcanoEruptStyle.STROMBOLIAN
                || style == VolcanoEruptStyle.VULCANIAN
                || style == VolcanoEruptStyle.PELEAN) {
            int count = this.vent.explosion.settings.minBombCount
                    + ((int) Math.random() * this.vent.explosion.settings.maxBombCount);

            double multiplier = style.ashMultiplier;

            for (int i = 0; i < count * multiplier; i++) {
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
                if (this.vent.caldera.isForming()) {
                    if (this.vent.caldera.isInCalderaRange(target.getLocation())) shouldDoIt = false;
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
    static int maxLife = 10;

    boolean isFinished = false;
    int scheduleID = -1;

    HashMap<Block, Boolean> flowedBlocks = new HashMap<>();

    public VolcanoPyroclasticFlow(Location location, VolcanoAsh ash) {
        this(location, ash, calculateInitialDirection(ash.vent, location));
    }

    public VolcanoPyroclasticFlow(Location location, VolcanoAsh ash, Vector direction) {
        this(location, ash, direction, 5);
    }

    public VolcanoPyroclasticFlow(Location location, VolcanoAsh ash, Vector direction, int radius) {
        this(location, ash, direction, radius, maxLife);
    }

    public VolcanoPyroclasticFlow(Location location, VolcanoAsh ash, Vector direction, int radius, int life) {
        this.location = location;
        this.direction = direction;
        this.ash = ash;
        this.radius = radius;
        this.life = life;
    }

    public static Vector calculateInitialDirection(VolcanoVent vent, Location location) {
        return location.subtract(vent.getNearestCoreBlock(location).getLocation()).toVector().normalize();
    }

    public void registerTask() {
        if (scheduleID < 0) {
            this.scheduleID = Bukkit.getScheduler().scheduleSyncRepeatingTask(TyphonPlugin.plugin, () -> {
                this.runTick();
            }, 0L, 5L);
        }
    }

    public void unregisterTask() {
        if (scheduleID >= 0) {
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

    public void runTick() {
        if (isFinished) return;

        this.runAsh();
        this.killInRange();

        if (Math.random() < 0.2) {
            if (radius < maxRadius) radius++;
        }

        if (Math.random() < 0.01) {
            life--;
        }

        this.calculateDirection();
        Location prevLoc = this.location;

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

        if (Math.random() < 0.2) {
            this.location.getWorld().playSound(this.location, Sound.ENTITY_BLAZE_DEATH, 3F, 0.5F);
        }

        isFinished = this.finishConditionCheck();
        if (isFinished) {
            this.shutdown();
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
        List<Block> blocks = VolcanoMath.getCircle(this.location.getBlock(), radius, radius - 1);
        Block lowestBlock = blocks.get(0);

        for (Block block : blocks) {
            if (block.getY() < lowestBlock.getY()) {
                lowestBlock = block;
            }
        }

        Location target = lowestBlock.getLocation().subtract(this.location);
        direction.add(target.multiply(0.1).toVector());
    }

    public void runAsh() {
        this.putAsh();
        this.playAshTrail();
    }

    public void killInRange() {
        Collection<Entity> entities = this.location.getWorld().getNearbyEntities(this.location, radius, radius, radius);
        for (Entity entity : entities) {
            if (entity instanceof Damageable) {
                Damageable target = (Damageable) entity;
                target.setHealth(1);
                entity.setFireTicks(10000);
            }
        }
    }

    public void putAsh(Block block) {
        if (!checkIfFlowed(block)) {
            if (block.getY() >= block.getWorld().getSeaLevel()) {
                int target = this.getTargetHeight(block.getLocation());
                if (block.getY() < target) {
                    int offset = target - block.getY();
                    if (offset >= 2) {
                        block.getRelative(BlockFace.UP).setType(Material.TUFF);
                        block.getRelative(0, 2, 0).setType(Material.TUFF);
                    }
                } else {
                    if (block.getY() > target + 2) {
                        block.setType(Material.TUFF);
                    } else {
                        block.getRelative(BlockFace.UP).setType(Material.TUFF);
                    }
                }
            }

            ash.createAshPlume(block.getLocation());
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
            if (Math.random() > (life / maxLife)) continue;
            if (Math.random() < 0.5) continue;

            Block block = TyphonUtils.getHighestRocklikes(baseBlock);
            this.putAsh(block);
        }
    }

    public void playAshTrail() {
        List<Block> flowed = flowedBlocks.keySet().stream().toList();
        Collections.shuffle(flowed);

        double distance = ash.vent.getTwoDimensionalDistance(location);

        for (int i = 0; i < distance * 5; i++) {
            Block block = flowed.get(i);
            double blockDistance = ash.vent.getThreeDimensionalDistance(block.getLocation());

            if (distance < 10 || Math.random() < ((distance - blockDistance) / distance)) {
                ash.createAshPlume(flowed.get(i).getLocation());
            }
        }
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
