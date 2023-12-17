package me.alex4386.plugin.typhon.volcano.bomb;

import me.alex4386.plugin.typhon.*;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.lavaflow.VolcanoLavaFlow;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentStatus;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.material.MaterialData;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
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
    public Location targetLocation;

    public Location prevLocation = null;
    public int bombDelay;
    public boolean isTrailOn = false;

    public int explodeTimer = -1;

    public int lifeTime = 0;

    public boolean isLanded = false;
    public VolcanoBomb(
            VolcanoVent vent,
            Location loc,
            Location targetLocation,
            float bombPower,
            int bombRadius,
            int bombDelay) {
        this.vent = vent;
        this.bombPower = bombPower;
        this.bombRadius = bombRadius;
        this.bombDelay = bombDelay;

        this.launchLocation = loc;
        this.targetLocation = targetLocation;
    }

    public void launch() {
        int maxY = vent.getSummitBlock().getY();
        int yToLaunch = maxY - this.launchLocation.getWorld().getHighestBlockYAt(launchLocation);
        if (yToLaunch < 0) yToLaunch = 0;

        Vector launchVector = TyphonUtils.calculateVelocity(
                new Vector(0, 0, 0),
                targetLocation.toVector().subtract(launchLocation.toVector()),
                yToLaunch + 3 + (int) (Math.random() * 9));

        this.block = this.launchLocation.getWorld()
                .spawnFallingBlock(
                        this.launchLocation,
                        new MaterialData(
                                VolcanoComposition.getBombRock(
                                        this.vent.lavaFlow.settings.silicateLevel)));

        this.block.setGlowing(true);

        this.block.setFireTicks(1000);
        try {
            this.block.setVelocity(launchVector);
        } catch (Exception e) {
            this.block.remove();
            this.block = null;

            this.land();
            return;
        }

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
        if (this.block != null) {
            Location loc = block.getLocation();
            loc.getWorld().spawnParticle(Particle.LAVA, loc, 1);
        }
    }

    public void startTrail() {
        if (!isTrailOn && this.block != null) {
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
        if (isTrailOn && this.block != null) {
            Bukkit.getScheduler().cancelTask(bombTrailScheduleId);
            isTrailOn = false;
        }
    }

    public void skipMe() {
        if (this.block != null) {
            this.block.remove();
            this.isLanded = true;
    
            this.block.getLocation().getBlock().setType(Material.AIR);
        }
    }

    public void emergencyLand() {
        this.isLanded = true;
        if (this.block != null) this.block.remove();
        this.block = null;
        this.land();
    }

    public void land() {
        Volcano volcano = this.vent.getVolcano();
        if (this.targetLocation != null && this.landingLocation == null) this.landingLocation = this.targetLocation;

        if (this.block != null) {
            this.landingLocation = block.getLocation();            
        } else if (this.landingLocation == null) {
            this.isLanded = true;
            if (this.block != null) {
                this.block.remove();
            }
            return;
        }
        if (this.block != null) this.block.remove();
        if (!this.isLanded) this.isLanded = true;

        if (this.targetLocation != null) {
            double error = TyphonUtils.getTwoDimensionalDistance(this.landingLocation, this.targetLocation);
            if (error > 7) {
                this.vent.getVolcano().logger.debug(VolcanoLogClass.BOMB, "Volcano Bomb targetted for "+TyphonUtils.blockLocationTostring(this.targetLocation.getBlock())+" is wrongly landed at "+TyphonUtils.blockLocationTostring(this.block.getLocation().getBlock())+". Relocating.");
                this.landingLocation = this.targetLocation.getWorld().getHighestBlockAt(this.targetLocation).getLocation().add(0, 1, 0);
            }
        }

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

        VolcanoVent nearestVent = this.vent.getVolcano().manager.getNearestVent(loc);
        double distance = TyphonUtils.getTwoDimensionalDistance(nearestVent.getNearestCoreBlock(loc).getLocation(), loc);

        if (!VolcanoBombListener.groundChecker(loc, bombRadius)) {
            volcano.logger.debug(
                    VolcanoLogClass.BOMB_LAUNCHER,
                    "Volcanic Bomb from "
                            + TyphonUtils.blockLocationTostring(this.launchLocation.getBlock())
                            + " did not landed properly at "
                            + TyphonUtils.blockLocationTostring(block));
            isLanded = true;
            return;
        }

        if (nearestVent.getStatus() == VolcanoVentStatus.ERUPTING) {
            if (distance < nearestVent.craterRadius) {
                if (!TyphonPlugin.isShuttingdown) {
                    VolcanoBomb bomb = this.vent.bombs.generateBomb();
                    bomb.land();
                }
            }

            int summitRange = nearestVent.craterRadius + (int) Math.max(Math.min(nearestVent.craterRadius * 0.5, 20), 10);
            if (distance < summitRange) {
                // build up the cone.
                if (this.block != null) {
                    this.block.remove();
                }

                if (TyphonPlugin.isShuttingdown) return;

                Block targetBlock = TyphonUtils.getHighestRocklikes(nearestVent.getNearestVentBlock(this.landingLocation));
                boolean isAllowedToGrowUp = nearestVent.averageVentHeight() == nearestVent.getSummitBlock().getY();

                if (!isAllowedToGrowUp) {
                    isAllowedToGrowUp = Math.random() < 0.1;
                }

                int targetHeight = nearestVent.getSummitBlock().getY();
                if (isAllowedToGrowUp) targetHeight += 1;

                if (targetBlock.getY() <= targetHeight) {
                    targetBlock = TyphonUtils.getHighestRocklikes(nearestVent.selectFlowVentBlock(true));
                }

                // if targetBlock's Y is lower than the vent's summitY or equal, then flow the lava.
                if (targetBlock.getY() <= targetHeight) {
                    if (targetBlock.getType() != Material.LAVA) {
                        nearestVent.lavaFlow.flowVentLavaFromBomb(targetBlock.getRelative(BlockFace.UP));
                    }

                    if (isAllowedToGrowUp) {
                        nearestVent.flushSummitCache();
                    }
                }
                return;
            }
        }

        if (this.vent.volcano.manager.isInAnyFormingCaldera(loc)) {
            return;
        }

        stopTrail();

        if (vent != null) {
            if (vent.bombs.maxDistance < vent.getTwoDimensionalDistance(targetLocation)) {
                vent.bombs.maxDistance = vent.getTwoDimensionalDistance(targetLocation);
            }

            if (this.block != null) {
                if (vent.isInVent(this.block.getLocation())) {
                    this.block.remove();
                    this.block.getLocation().getBlock().setType(Material.AIR);
                }
            }
        }


        /*
        // TODO: Shit code, fix later.

        double heightOfCone = Math.max(0, nearestVent.getSummitBlock().getY() - nearestVent.location.getBlockY());
        double coneBase = Math.max(0, heightOfCone * Math.sqrt(3));

        double currentBlockHeight = Math.max(0, block.getY() - nearestVent.location.getBlockY());

        if (distance < coneBase) {
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
         */

        if (TyphonPlugin.isShuttingdown) {
            this.buildBomb(false);
            return;
        }

        if (this.landingLocation.getBlock().getChunk().getInhabitedTime() > 0) {
            this.buildBomb();
            this.explodeTimer = TyphonPlugin.minecraftTicksPerSeconds * this.bombDelay;

        } else {
            this.buildBomb(false);
        }
    }

    public void buildBomb() {
        this.buildBomb(true);
    }

    public void buildBomb(boolean flowLava) {
        int totalEjecta = 0;

        VolcanoLavaFlow lavaFlow = vent.lavaFlow;

        if (flowLava) {
            lavaFlow.registerEvent();
            lavaFlow.registerTask();
        }

        Block finalBlock = this.landingLocation.getBlock();
        if (!finalBlock.getRelative(BlockFace.DOWN).getType().isBlock()) {
            Block temp = TyphonUtils.getHighestRocklikes(finalBlock).getRelative(BlockFace.UP);
            if (temp.getY() < finalBlock.getY()) finalBlock = temp;
        }

        if (bombRadius <= 1) {
            List<Block> bomb = VolcanoMath.getSphere(
                    finalBlock, this.bombRadius);

            if (flowLava) {
                for (Block bombBlock : bomb) {
                    lavaFlow.flowLavaFromBomb(bombBlock);
                }

                finalBlock.getWorld().createExplosion(finalBlock.getLocation(), 1, true, false);
            } else {
                for (Block bombBlock : bomb) {
                    Material material = VolcanoComposition.getBombRock(lavaFlow.settings.silicateLevel);
                    if (vent == null)
                        bombBlock.setType(material);
                    else
                        vent.lavaFlow.queueBlockUpdate(bombBlock, material);
                }
            }

            totalEjecta = bomb.size();
        } else {
            List<Block> bomb = VolcanoMath.getSphere(
                    finalBlock, this.bombRadius);

            for (Block bombBlock : bomb) {
                Random random = new Random();
                Material material = VolcanoComposition.getBombRock(lavaFlow.settings.silicateLevel);
                switch (random.nextInt(3)) {
                    case 0:
                        material = null;
                        break;
                    case 1:
                        break;
                    case 2:
                        if (flowLava) {
                            lavaFlow.flowLavaFromBomb(bombBlock);
                            material = null;
                        }
                        break;
                }

                if (material != null) {
                    if (vent == null)
                        bombBlock.setType(material);
                    else
                        vent.lavaFlow.queueBlockUpdate(bombBlock, material);
                }
            }

            totalEjecta = bomb.size();
        }

        vent.record.addEjectaVolume(totalEjecta);
    }

    public void explode() {
        Volcano volcano = this.vent.getVolcano();

        if (bombRadius >= 1) {
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

            if (this.vent != null) {
                boolean shouldExplode = true;

                if (this.vent.erupt.getStyle().lavaMultiplier == 0) {
                    int height = Math.max(0, this.vent.getSummitBlock().getY() - this.vent.location.getBlockY());
                    if (this.vent.getTwoDimensionalDistance(bombCenter.getLocation()) <= (height * Math.sqrt(3))) {
                        shouldExplode = false;
                    }
                }

                if (shouldExplode) {
                    VolcanoBombListener.lavaSplashExplosions.put(
                        bombCenter.getLocation().getBlock(), this.vent);
    
                    landingLocation
                            .getWorld()
                            .createExplosion(
                                    bombCenter.getLocation(),
                                    bombPower,
                                    true,
                                    !volcano.manager.isInAnyVent(landingLocation));
                }
            }
            

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
