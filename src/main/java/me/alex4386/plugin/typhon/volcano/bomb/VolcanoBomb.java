package me.alex4386.plugin.typhon.volcano.bomb;

import me.alex4386.plugin.typhon.*;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.lavaflow.VolcanoLavaFlow;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentStatus;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.FallingBlock;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;
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
    public int heatTimer = 15;

    public int lifeTime = 0;
    public static Material defaultBombMaterial = Material.MAGMA_BLOCK;

    private int lifeTimeRed = 20;

    Material targetMaterial = null;

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

        double randomMultiplier = 1.0 + (Math.random() * 0.25);

        this.heatTimer = (int)(15 * randomMultiplier);
        this.lifeTimeRed = (int)(20 * randomMultiplier);
    }



    public double getDistanceRatio() {
        return this.getDistanceRatio(this.landingLocation);
    }

    public double getDistanceRatio(Location location) {
        if (this.vent == null) return 1;
        return this.vent.lavaFlow.getDistanceRatio(location);
    }

    public Team getAdequateTeam() {
        if (this.heatTimer > 0) {
            return VolcanoBombs.bombGlowYellow;
        } else if (this.lifeTime < this.lifeTimeRed) {
            return VolcanoBombs.bombGlowGold;
        }

        return VolcanoBombs.bombGlowRed;
    }

    public void removeFromOthers() {
        if (this.block == null) return;

        if (VolcanoBombs.bombGlowYellow != null) {
            VolcanoBombs.bombGlowYellow.removeEntity(this.block);
        }

        if (VolcanoBombs.bombGlowGold != null) {
            VolcanoBombs.bombGlowGold.removeEntity(this.block);
        }

        if (VolcanoBombs.bombGlowRed != null) {
            VolcanoBombs.bombGlowRed.removeEntity(this.block);
        }
    }

    public void updateTeam() {
        if (this.block == null) return;

        try {
            // remove from other teams
            this.removeFromOthers();

            this.getAdequateTeam().addEntity(block);
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    public void customLaunch(Vector launchVector) {
        try {
            this.block = this.launchLocation.getWorld().spawn(
                    this.launchLocation,
                    FallingBlock.class,
                    entity -> {
                        FallingBlock block = (FallingBlock) entity;
                        block.setGlowing(true);
                        block.setInvulnerable(true);
                        block.setMetadata("DropItem", new FixedMetadataValue(TyphonPlugin.plugin, 0));
                        block.setDropItem(false);
                        block.setGravity(true);
                        block.setVelocity(launchVector);

                        BlockState state = block.getBlockState();
                        state.setType(defaultBombMaterial);

                        block.setBlockState(state);
                        block.setFireTicks(1000);
                    }
            );

            this.updateTeam();
        } catch (Exception e) {
            if (this.block != null) this.block.remove();

            this.land();
            return;
        }

        this.vent
                .getVolcano().logger
                .debug(
                        VolcanoLogClass.BOMB_LAUNCHER,
                        "Volcanic Bomb Just launched from: "
                                + TyphonUtils.blockLocationTostring(launchLocation.getBlock()));

    }

    public void launch() {
        int maxY = vent.getSummitBlock().getY();
        int yToLaunch = maxY - this.launchLocation.getWorld().getHighestBlockYAt(launchLocation);
        double explosionScale = Math.pow(this.vent.erupt.getStyle().bombMultiplier / 5.0, 0.8);

        // not launching up? it doesn't make sense.
        if (yToLaunch < 2) yToLaunch = 2;

        this.launchWithCustomHeight(yToLaunch + 3 + (int) ((0.5 + Math.random()) * 9 * explosionScale));
    }

    public void launchWithCustomHeight(int launchHeight) {
        Vector launchVector = TyphonUtils.calculateVelocity(
                new Vector(0, 0, 0),
                targetLocation.toVector().subtract(launchLocation.toVector()),
                launchHeight);

        this.customLaunch(launchVector);
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

    public void handleHeat() {
//        System.out.println("[HandleHeat] HeatTimer: "+this.heatTimer);
        if (this.heatTimer > 0) {
            this.heatTimer--;
        }

        if (this.heatTimer == 0) {
            // cooldown material
            this.coolDownFallingBlock();
        }
    }

    public void startTrail() {
        if (!isTrailOn && this.block != null) {
            bombTrailScheduleId = TyphonScheduler.registerTask(
                            this.block.getChunk(),
                            (Runnable) () -> {
                                createSmoke();
                            },
                            1L);
            isTrailOn = true;
        }
    }

    public void stopTrail() {
        if (isTrailOn && this.block != null) {
            TyphonScheduler.unregisterTask(bombTrailScheduleId);
            isTrailOn = false;
        }
    }

    public void coolDownFallingBlock() {
//        System.out.println("[HandleHeat] Cooling down");

        this.heatTimer = 0;
        if (this.block != null) {
            BlockState state = this.block.getBlockState();
//            System.out.println("[HandleHeat] Cooling down. current type: "+state.getType().name());
            if (state.getType() == defaultBombMaterial) {
                state.setType(VolcanoComposition.getBombRock(vent.lavaFlow.settings.silicateLevel, this.getDistanceRatio()));
                this.block.setBlockState(state);
            }
        }
    }

    public void skipMe() {
        if (this.block != null) {
            this.coolDownFallingBlock();
            this.block.remove();
            this.isLanded = true;

            TyphonBlocks.setBlockType(this.block.getLocation().getBlock(), Material.AIR);
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
                this.coolDownFallingBlock();
                this.block.remove();
            }
            return;
        }

        Material targetMaterial = VolcanoComposition.getBombRock(vent.lavaFlow.settings.silicateLevel, this.getDistanceRatio());
        if (this.block != null) {
            BlockState state = this.block.getBlockState();
            if (state.getType() == defaultBombMaterial) {
                targetMaterial = VolcanoComposition.getBombRock(vent.lavaFlow.settings.silicateLevel, this.getDistanceRatio());
                state.setType(targetMaterial);

                this.block.setBlockState(state);
            } else {
                targetMaterial = state.getType();
            }

            this.block.remove();
        }

        this.targetMaterial = targetMaterial;

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

            VolcanoLavaFlow flow;
            if (this.vent != null) {
                flow = this.vent.lavaFlow;
                flow.queueImmediateBlockUpdate(block, Material.AIR);
            } else {
                TyphonBlocks.setBlockType(block, Material.AIR);
            }
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

                Block targetVentBlock = nearestVent.requestFlow();
                if (targetVentBlock.getType() != Material.LAVA) {
                    targetVentBlock = targetVentBlock.getRelative(BlockFace.UP);
                    if (targetVentBlock.getType() == Material.LAVA) {
                        return;
                    }

                    nearestVent.lavaFlow.flowVentLavaFromBomb(targetVentBlock);
                    nearestVent.flushSummitCache();
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
                    vent.lavaFlow.queueImmediateBlockUpdate(
                            this.block.getLocation().getBlock(),
                            Material.AIR
                    );
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

        int baseFlowLimit = Math.max(this.bombRadius / 2, 2);
        if (bombRadius <= 1) {
            List<Block> bomb = VolcanoMath.getSphere(
                    finalBlock, this.bombRadius);

            if (flowLava) {
                for (Block bombBlock : bomb) {
                    lavaFlow.flowLavaFromBomb(bombBlock, baseFlowLimit);
                }

                finalBlock.getWorld().createExplosion(finalBlock.getLocation(), 1, true, false);
            } else {
                for (Block bombBlock : bomb) {
                    Material material = VolcanoComposition.getBombRock(lavaFlow.settings.silicateLevel, this.getDistanceRatio(finalBlock.getLocation(bombBlock.getLocation())));
                    if (this.targetMaterial != null) material = this.targetMaterial;

                    if (vent == null)
                        TyphonBlocks.setBlockType(bombBlock, material);
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
                Material material = VolcanoComposition.getBombRock(lavaFlow.settings.silicateLevel, this.getDistanceRatio());
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
                        TyphonBlocks.setBlockType(bombBlock, material);
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
