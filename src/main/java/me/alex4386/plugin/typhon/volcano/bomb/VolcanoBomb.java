package me.alex4386.plugin.typhon.volcano.bomb;

import me.alex4386.plugin.typhon.*;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.lavaflow.VolcanoLavaFlow;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.FallingBlock;
import org.bukkit.material.MaterialData;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.util.Vector;

import java.util.Collections;
import java.util.List;
import java.util.Random;

public class VolcanoBomb {
    public VolcanoVent vent;

    public Location launchLocation;
    public float bombPower;
    FallingBlock block;
    public int bombRadius;
    public int bombTrailScheduleId;

    public Location landingLocation;
    public Location prevLocation = null;
    public int bombDelay;
    public boolean isTrailOn = false;

    public int lifeTime = 0;

    public boolean isLanded = false;

    public VolcanoBomb(
            VolcanoVent vent,
            Location loc,
            FallingBlock block,
            float bombPower,
            int bombRadius,
            int bombDelay) {
        this.vent = vent;
        this.launchLocation = loc;
        this.bombPower = bombPower;
        this.bombRadius = bombRadius;
        this.bombDelay = bombDelay;
        this.block = block;
    }

    public VolcanoBomb(
            VolcanoVent vent,
            Location loc,
            float bombLaunchPowerX,
            float bombLaunchPowerY,
            float bombLaunchPowerZ,
            float bombPower,
            int bombRadius,
            int bombDelay) {
        this.vent = vent;
        this.bombPower = bombPower;
        this.bombRadius = bombRadius;
        this.bombDelay = bombDelay;

        this.launchLocation = loc;

        Vector launchVector = new Vector(bombLaunchPowerX, bombLaunchPowerY, bombLaunchPowerZ);
        this.block = loc.getWorld()
                .spawnFallingBlock(
                        loc,
                        new MaterialData(
                                VolcanoComposition.getBombRock(
                                        this.vent.lavaFlow.settings.silicateLevel)));

        this.block.setGlowing(true);
        this.block.setFireTicks(1000);
        this.block.setVelocity(launchVector);

        this.block.setGravity(true);
        this.block.setInvulnerable(true);
        this.block.setMetadata("DropItem", new FixedMetadataValue(TyphonPlugin.plugin, 0));
        this.block.setDropItem(false);

        this.vent
                .getVolcano().logger
                .debug(
                        VolcanoLogClass.BOMB_LAUNCHER,
                        "Volcanic Bomb Just launched from: "
                                + TyphonUtils.blockLocationTostring(launchLocation.getBlock()));
    }

    public double getLifetimeSeconds() {
        return (double) this.lifeTime / VolcanoBombListener.updatesPerSeconds;
    }

    public void createSmoke() {
        Location loc = block.getLocation();
        loc.getChunk().load();

        loc.getWorld().spawnParticle(Particle.LAVA, loc, 1);
    }

    public void startTrail() {
        if (!isTrailOn) {
            bombTrailScheduleId = Bukkit.getScheduler()
                    .scheduleSyncRepeatingTask(
                            TyphonPlugin.plugin,
                            (Runnable) () -> {
                                createSmoke();
                            },
                            0L,
                            1L);
            isTrailOn = true;
        }
    }

    public void stopTrail() {
        if (isTrailOn) {
            Bukkit.getScheduler().cancelTask(bombTrailScheduleId);
            isTrailOn = false;
        }
    }

    public void skipMe() {
        this.block.remove();
        this.isLanded = true;

        this.block.getLocation().getBlock().setType(Material.AIR);
    }

    public void land() {
        Volcano volcano = this.vent.getVolcano();

        this.landingLocation = block.getLocation();

        // calculate even more fall.
        Block block = this.landingLocation.getBlock();
        while (!TyphonUtils.isMaterialRocklikes(block.getRelative(BlockFace.DOWN).getType())) {
            if (block.getY() < TyphonUtils.getMinimumY(block.getWorld()))
                return;
            
            if (TyphonUtils.isMaterialTree(block.getType())) {
                // burn it!
                vent.volcano.metamorphism.removeTree(block);
                TyphonUtils.getHighestNonTreeSolid(block).getLocation();
            }

            block.setType(Material.AIR);
            block = block.getRelative(BlockFace.DOWN);
        }

        this.landingLocation = block.getLocation();

        Location loc = block.getLocation();

        String ventName = "";
        VolcanoVent nearestVent = this.vent.getVolcano().manager.getNearestVent(loc);
        double distance = TyphonUtils.getTwoDimensionalDistance(nearestVent.location, block.getLocation());

        if (!VolcanoBombListener.groundChecker(loc, bombRadius)) {
            volcano.logger.debug(
                    VolcanoLogClass.BOMB_LAUNCHER,
                    "Volcanic Bomb from "
                            + TyphonUtils.blockLocationTostring(this.launchLocation.getBlock())
                            + " did not landed properly at "
                            + TyphonUtils.blockLocationTostring(this.landingLocation.getBlock()));
            isLanded = true;
            return;
        }

        if (distance < nearestVent.craterRadius * 0.7) {
            this.skipMe();
            this.vent.lavaFlow.extendLava();
            return;
        }

        stopTrail();

        if (vent != null) {
            if (vent.bombs.maxDistance < vent.getTwoDimensionalDistance(loc)) {
                vent.bombs.maxDistance = vent.getTwoDimensionalDistance(loc);
            }
        }

        double heightOfCone = Math.max(0, nearestVent.getSummitBlock().getY() - nearestVent.location.getBlockY());
        double coneBase = Math.max(0, heightOfCone * Math.sqrt(3));

        double currentBlockHeight = Math.max(0, block.getY() - nearestVent.location.getBlockY());

        if (distance < coneBase && heightOfCone > 5) {
            double fromEndOfCone = coneBase - distance;

            double adequateHeight = fromEndOfCone / Math.sqrt(3);
            int targetHeight = (int) Math.round(adequateHeight);

            if (adequateHeight > 5 && currentBlockHeight > 5) {
                double probability = 1;

                if (currentBlockHeight > targetHeight) {
                    probability = Math.pow(0.1, currentBlockHeight - targetHeight);
                }

                if (probability < 1) {
                    if (Math.random() >= probability) {
                        this.skipMe();
                        return;
                    }
                }
            }
        } else if (distance > coneBase) {
            double probability = 1;

            if (currentBlockHeight > 3) {
                probability = Math.pow(0.1, currentBlockHeight - 3);
            }

            if (probability < 1) {
                if (Math.random() >= probability) {
                    this.skipMe();
                    return;
                }
            }
        }

        final Block finalBlock = block;
        Bukkit.getScheduler()
                .scheduleSyncDelayedTask(
                        TyphonPlugin.plugin,
                        (Runnable) () -> {
                            int totalEjecta = 0;

                            VolcanoLavaFlow lavaFlow = vent.lavaFlow;

                            lavaFlow.registerEvent();
                            lavaFlow.registerTask();

                            if (bombRadius <= 1) {
                                List<Block> bomb = VolcanoMath.getSphere(
                                        finalBlock, this.bombRadius);

                                for (Block bombBlock : bomb) {
                                    lavaFlow.flowLavaFromBomb(bombBlock);
                                }

                                finalBlock.getWorld().createExplosion(finalBlock.getLocation(), 1, true, false);
                                totalEjecta = bomb.size();
                            } else {
                                List<Block> bomb = VolcanoMath.getSphere(
                                        finalBlock, this.bombRadius);

                                for (Block bombBlock : bomb) {
                                    Random random = new Random();
                                    switch (random.nextInt(3)) {
                                        case 0:
                                        case 1:
                                            bombBlock.setType(
                                                    VolcanoComposition.getBombRock(
                                                            vent.lavaFlow.settings.silicateLevel));
                                            break;
                                        case 2:
                                            lavaFlow.flowLavaFromBomb(bombBlock);
                                            break;
                                    }
                                }

                                totalEjecta = bomb.size();
                            }

                            vent.record.addEjectaVolume(totalEjecta);

                            Bukkit.getScheduler()
                                    .scheduleSyncDelayedTask(
                                            TyphonPlugin.plugin,
                                            (Runnable) () -> {
                                                this.explode();
                                            },
                                            TyphonPlugin.minecraftTicksPerSeconds
                                                    * this.bombDelay);
                        },
                        1L);
    }

    public void explode() {
        Volcano volcano = this.vent.getVolcano();

        if (bombRadius >= 2) {
            Block bombCenter = landingLocation.add(0, bombRadius, 0).getBlock();

            volcano.logger.debug(
                    VolcanoLogClass.BOMB,
                    "Volcanic Bomb from "
                            + TyphonUtils.blockLocationTostring(this.launchLocation.getBlock())
                            + " just exploded at "
                            + TyphonUtils.blockLocationTostring(this.landingLocation.getBlock())
                            + " with Power: "
                            + this.bombPower
                            + ", radius: "
                            + this.bombRadius
                            + ", lifeTime: "
                            + this.lifeTime
                            + " (= "
                            + this.getLifetimeSeconds()
                            + "s)");

            VolcanoBombListener.lavaSplashExplosions.put(
                    bombCenter.getLocation().getBlock(), this.vent);

            landingLocation
                    .getWorld()
                    .createExplosion(
                            bombCenter.getLocation(),
                            bombPower,
                            true,
                            !volcano.manager.isInAnyVent(landingLocation));

            if (bombRadius > 4) {
                List<Block> circle = VolcanoMath.getCircle(bombCenter, bombRadius * 2, bombRadius + 1);

                Random random = new Random();
                int lavaSpread = random.nextInt(bombRadius * 2);

                Collections.shuffle(circle);

                for (int i = 0; i < lavaSpread; i++) {
                    Block block = TyphonUtils.getHighestOceanFloor(circle.get(i).getLocation())
                            .getBlock();

                    this.vent.lavaFlow.flowLavaFromBomb(block);
                }
            }
        }

        isLanded = true;
    }
}
