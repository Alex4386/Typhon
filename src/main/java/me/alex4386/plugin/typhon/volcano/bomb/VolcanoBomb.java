package me.alex4386.plugin.typhon.volcano.bomb;

import me.alex4386.plugin.typhon.*;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.crater.VolcanoCrater;
import me.alex4386.plugin.typhon.volcano.intrusions.VolcanoMetamorphism;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
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
    public VolcanoCrater crater;

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

    public VolcanoBomb(VolcanoCrater crater, Location loc, float bombLaunchPowerX, float bombLaunchPowerZ, float bombPower, int bombRadius, int bombDelay) {
        this.crater = crater;
        this.bombPower = bombPower;
        this.bombRadius = bombRadius;
        this.bombDelay = bombDelay;

        Random random = new Random();
        float bombLaunchPowerY = (random.nextFloat() * 2) + 4f;

        this.launchLocation = loc;

        Vector launchVector = new Vector(bombLaunchPowerX, bombLaunchPowerY, bombLaunchPowerZ);
        this.block = loc.getWorld().spawnFallingBlock(loc, new MaterialData(Material.GRAVEL));

        this.block.setVelocity(launchVector);

        this.block.setGravity(true);
        this.block.setInvulnerable(true);
        this.block.setMetadata("DropItem", new FixedMetadataValue(TyphonPlugin.plugin, 0));
        this.block.setDropItem(false);

        this.crater.getVolcano().logger.debug(VolcanoLogClass.BOMB_LAUNCHER, "Volcanic Bomb Just launched from: "+ TyphonUtils.blockLocationTostring(launchLocation.getBlock()));
    }

    VolcanoBomb(VolcanoCrater crater, float bombLaunchPowerX, float bombLaunchPowerZ, float bombPower, int bombRadius, int bombDelay) {
        this(crater, crater.erupt.getEruptionLocation(), bombLaunchPowerX, bombLaunchPowerZ, bombPower, bombRadius, bombDelay);
    }

    public double getLifetimeSeconds() {
        return (double) this.lifeTime / VolcanoBombListener.updatesPerSeconds;
    }

    public void createSmoke() {
        Location loc = block.getLocation();
        loc.getChunk().load();

        TyphonNMSUtils.createParticle(
                Particle.CLOUD,
                loc,
                1
        );
    }

    public void startTrail() {
        if (!isTrailOn) {
            bombTrailScheduleId = Bukkit.getScheduler().scheduleSyncRepeatingTask(TyphonPlugin.plugin, (Runnable) () -> {
                createSmoke();
            }, 0L, 1L);
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
        Volcano volcano = this.crater.getVolcano();

        this.landingLocation = block.getLocation();

        // calculate even more fall.
        Block block = this.landingLocation.getBlock();
        while (!TyphonUtils.isMaterialRocklikes(block.getRelative(BlockFace.DOWN).getType())) {
            if (block.getY() < TyphonUtils.getMinimumY(block.getWorld())) return;
            for (int i = -1; i <= 1; i++) {
                for (int j = -1; j <= 1; j++) {
                    Block burnBlock = block.getRelative(i, 0, j);
                    if (i == 0 && j == 0) {
                        burnBlock.setType(Material.AIR);
                        continue;
                    }
                    if (burnBlock.getType().isBurnable()) {
                        Block topOfBurnBlock = burnBlock.getRelative(0,1,0);
                        if (topOfBurnBlock.getType().isAir()) {
                            topOfBurnBlock.setType(Material.FIRE);
                        }
                    } else {
                        this.crater.volcano.metamorphism.metamorphoseBlock(burnBlock);
                    }
                }
            }
            block = block.getRelative(BlockFace.DOWN);
        }

        this.landingLocation = block.getLocation();

        Location loc = block.getLocation();

        String craterName = "";
        boolean isLandedOnCrater = this.crater.getVolcano().manager.isInAnyCrater(loc);
        if (isLandedOnCrater) {
            craterName = this.crater.getVolcano().manager.getNearestCrater(loc).name;
            this.bombRadius = 1;
        }

        if (!VolcanoBombListener.groundChecker(loc, bombRadius)) {
            volcano.logger.debug(VolcanoLogClass.BOMB_LAUNCHER,
                    "Volcanic Bomb from "+ TyphonUtils.blockLocationTostring(this.launchLocation.getBlock())+
                          " did not landed properly at "+ TyphonUtils.blockLocationTostring(this.landingLocation.getBlock()));
            isLanded = true;
            return;
        }

        stopTrail();

        volcano.logger.debug(
                VolcanoLogClass.BOMB_LAUNCHER,
                "Volcanic Bomb from "+ TyphonUtils.blockLocationTostring(this.launchLocation.getBlock())+
                    " just landed at "+ TyphonUtils.blockLocationTostring(this.landingLocation.getBlock())+
                    (isLandedOnCrater ? "which is inside of crater: "+craterName : "")+
                    " with Power: "+this.bombPower+", radius: "+this.bombRadius+", lifeTime: "+this.lifeTime+" (= "+
                    this.getLifetimeSeconds()+"s)");

        final Block finalBlock = block;
        Bukkit.getScheduler().scheduleSyncDelayedTask(TyphonPlugin.plugin, (Runnable) () -> {
            int totalEjecta = 0;
            
            volcano.bombLavaFlow.registerEvent();
            volcano.bombLavaFlow.registerTask();

            if (bombRadius <= 1) {
                List<Block> bomb = VolcanoMath.getSphere(loc.getBlock(), this.bombRadius);

                for (Block bombBlock:bomb) {
                    volcano.bombLavaFlow.registerLavaCoolData(bombBlock);
                    bombBlock.setType(Material.LAVA);
                }

                totalEjecta = bomb.size();
            } else {
                List<Block> bomb = VolcanoMath.getSphere(loc.getBlock(), this.bombRadius);

                for (Block bombBlock:bomb) {
                    Random random = new Random();
                    switch(random.nextInt(3)) {
                        case 0:
                        case 1:
                            bombBlock.setType(volcano.composition.getExtrusiveRockMaterial());
                        case 2:
                            volcano.bombLavaFlow.registerLavaCoolData(bombBlock);
                            bombBlock.setType(Material.LAVA);
                    }
                }

                totalEjecta = bomb.size();
            }


            crater.record.addEjectaVolume(totalEjecta);

            TyphonNMSUtils.updateChunk(finalBlock.getLocation());

            Bukkit.getScheduler().scheduleSyncDelayedTask(TyphonPlugin.plugin, (Runnable) () -> {
                this.explode();
            }, TyphonPlugin.minecraftTicksPerSeconds * this.bombDelay);

        }, 1L);

    }

    public void explode() {
        Volcano volcano = this.crater.getVolcano();

        if (bombRadius >= 2) {
            Block bombCenter = landingLocation.add(0,bombRadius,0).getBlock();

            volcano.logger.debug(
                    VolcanoLogClass.BOMB,
                    "Volcanic Bomb from "+ TyphonUtils.blockLocationTostring(this.launchLocation.getBlock())
                    +" just exploded at "+ TyphonUtils.blockLocationTostring(this.landingLocation.getBlock())
                    +" with Power: "+this.bombPower+", radius: "+this.bombRadius+", lifeTime: "+this.lifeTime+" (= "+
                    this.getLifetimeSeconds()+"s)");

            landingLocation.getWorld().createExplosion(
                    bombCenter.getLocation(),
                    bombPower,
                    true,
                    !volcano.manager.isInAnyCrater(landingLocation)
            );

            if (bombRadius > 4) {
                List<Block> circle = VolcanoMath.getCircle(bombCenter, bombRadius * 2, bombRadius + 1);

                Random random = new Random();
                int lavaSpread = random.nextInt(bombRadius * 2);

                Collections.shuffle(circle);

                for (int i = 0; i < lavaSpread; i++) {
                    Block block = TyphonUtils.getHighestOceanFloor(circle.get(i).getLocation()).getBlock();
                    block.setType(Material.LAVA);

                    volcano.bombLavaFlow.registerLavaCoolData(block);
                }
            }
        }

        TyphonNMSUtils.updateChunk(block.getLocation());
        isLanded = true;
    }
}
