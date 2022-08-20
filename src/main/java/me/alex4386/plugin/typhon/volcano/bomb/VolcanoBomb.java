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

        loc.getWorld().spawnParticle(Particle.FLAME, loc, 6);
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

    public void land() {
        Volcano volcano = this.vent.getVolcano();

        this.landingLocation = block.getLocation();

        // calculate even more fall.
        Block block = this.landingLocation.getBlock();
        while (!TyphonUtils.isMaterialRocklikes(block.getRelative(BlockFace.DOWN).getType())) {
            if (block.getY() < TyphonUtils.getMinimumY(block.getWorld()))
                return;

            block.setType(Material.AIR);
            block = block.getRelative(BlockFace.DOWN);
        }

        this.landingLocation = block.getLocation();

        Location loc = block.getLocation();

        String ventName = "";
        boolean isLandedOnVent = this.vent.getVolcano().manager.isInAnyVent(loc);
        boolean isLandedInCurrentlyGrowingCone = false;
        VolcanoVent nearestVent = this.vent.getVolcano().manager.getNearestVent(loc);

        if (nearestVent != null) {
            double getCurrentDistance = nearestVent.getTwoDimensionalDistance(loc);
            if (getCurrentDistance < nearestVent.longestFlowLength * 0.8) {
                isLandedInCurrentlyGrowingCone = true;
            }
        }

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

        if (isLandedOnVent) {
            this.block.remove();
            this.isLanded = true;

            this.block.getLocation().getBlock().setType(Material.AIR);
            this.vent.lavaFlow.extendLava();
            return;
        }

        if (isLandedInCurrentlyGrowingCone) {
            this.block.remove();
            this.isLanded = true;

            volcano.logger.debug(
                    VolcanoLogClass.BOMB_LAUNCHER,
                    "Volcanic Bomb from "
                            + TyphonUtils.blockLocationTostring(this.launchLocation.getBlock())
                            + " landed at currently growing cone. interrupting bomb and starting a"
                            + " new flow");

            loc.getWorld().createExplosion(loc, 1, true, false);

            this.vent.lavaFlow.flowLavaFromBomb(this.block.getLocation().getBlock());
            this.vent.record.addEjectaVolume(1);

            return;
        }

        stopTrail();

        volcano.logger.debug(
                VolcanoLogClass.BOMB_LAUNCHER,
                "Volcanic Bomb from "
                        + TyphonUtils.blockLocationTostring(this.launchLocation.getBlock())
                        + " just landed at "
                        + TyphonUtils.blockLocationTostring(this.landingLocation.getBlock())
                        + (isLandedOnVent ? "which is inside of vent: " + ventName : "")
                        + " with Power: "
                        + this.bombPower
                        + ", radius: "
                        + this.bombRadius
                        + ", lifeTime: "
                        + this.lifeTime
                        + " (= "
                        + this.getLifetimeSeconds()
                        + "s)");

        if (vent != null) {
            if (vent.bombs.maxDistance < vent.getTwoDimensionalDistance(loc)) {
                vent.bombs.maxDistance = vent.getTwoDimensionalDistance(loc);
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
                                        loc.getBlock(), this.bombRadius);

                                for (Block bombBlock : bomb) {
                                    lavaFlow.flowLavaFromBomb(bombBlock);
                                }

                                loc.getWorld().createExplosion(loc, 1, true, false);
                                totalEjecta = bomb.size();
                            } else {
                                List<Block> bomb = VolcanoMath.getSphere(
                                        loc.getBlock(), this.bombRadius);

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
