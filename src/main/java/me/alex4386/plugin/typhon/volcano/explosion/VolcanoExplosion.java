package me.alex4386.plugin.typhon.volcano.explosion;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoEruptStyle;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.json.simple.JSONObject;

import java.util.List;
import java.util.Random;

public class VolcanoExplosion {
    public VolcanoVent vent;

    public boolean enabled = true;
    public boolean running = false;

    public VolcanoExplosionSettings settings = new VolcanoExplosionSettings();

    public int scheduleID = -1;
    public int queueScheduleID = -1;

    public int queuedBombs = 0;

    public VolcanoExplosion(VolcanoVent vent) {
        this.vent = vent;
    }

    public void registerTask() {
        if (this.scheduleID < 0) {
            this.vent.volcano.logger.log(
                    VolcanoLogClass.EXPLOSION,
                    "Registering VolcanoExplosion for vent " + vent.getName());
            this.scheduleID = Bukkit.getScheduler()
                    .scheduleSyncRepeatingTask(
                            TyphonPlugin.plugin,
                            () -> {
                                if (this.enabled) {
                                    explodeQueued();
                                }
                            },
                            0l,
                            1l);
        }

        if (this.queueScheduleID < 0) {
            this.queueScheduleID = Bukkit.getScheduler()
                    .scheduleSyncRepeatingTask(
                            TyphonPlugin.plugin,
                            () -> {
                                if (this.enabled && this.running) {
                                    if (!this.vent.erupt.getStyle().canFormCaldera) explode();
                                }
                            },
                            0l,
                            5l);
        }
    }

    public void unregisterTask() {
        if (scheduleID >= 0) {
            this.vent.volcano.logger.log(
                    VolcanoLogClass.EXPLOSION,
                    "Unregistering VolcanoExplosion for vent " + vent.getName());
            Bukkit.getScheduler().cancelTask(scheduleID);
            Bukkit.getScheduler().cancelTask(queueScheduleID);
            scheduleID = -1;
            queueScheduleID = -1;
        }
    }

    public void initialize() {
        this.vent.volcano.logger.log(
                VolcanoLogClass.EXPLOSION,
                "Intializing VolcanoExplosion for vent " + vent.getName());
        this.registerTask();
    }

    public void shutdown() {
        this.vent.volcano.logger.log(
                VolcanoLogClass.EXPLOSION,
                "Shutting down VolcanoExplosion for vent " + vent.getName());
        this.unregisterTask();
    }

    // get eruption location
    public Location selectEruptionVent() {
        int theY = vent.getSummitBlock().getY();

        Location location = vent.location;
        if (vent.getType() == VolcanoVentType.FISSURE) {
            location = vent.selectCoreBlock().getLocation();
        }

        Block launchBlock = new Location(location.getWorld(), location.getX(), theY, location.getZ())
                .getBlock();
        return launchBlock.getLocation();
    }

    public void explode() {
        VolcanoEruptStyle style = this.vent.erupt.getStyle();
        double bombMultiplier = style.bombMultiplier;

        int fissureLengthMultiplier = 1;
        if (this.vent.getType() == VolcanoVentType.FISSURE) {
            fissureLengthMultiplier = this.vent.fissureLength / this.vent.craterRadius;
        }

        int bombCount = (int) (
            (
                Math.random() * 
                (settings.maxBombCount - settings.minBombCount)
                + settings.minBombCount
            ) * bombMultiplier * this.vent.lavaFlow.settings.gasContent * fissureLengthMultiplier
        );
        
        explode(bombCount);
    }

    public void explode(int bombCount) {
        queuedBombs += bombCount;
    }

    public void explodeQueued() {
        int bombCount = Math.min(settings.queueSize, queuedBombs);
        if (queuedBombs == 0) return;
        
        queuedBombs -= bombCount;

        boolean queueComplete = queuedBombs <= 0;

        Location targetVent = this.selectEruptionVent();

        vent.location.getWorld().playSound(targetVent, Sound.AMBIENT_BASALT_DELTAS_MOOD, SoundCategory.HOSTILE, 10F, 0F);
        vent.location.getWorld().playSound(targetVent, Sound.ENTITY_BREEZE_WIND_BURST, 2F, 0F);

        vent.location
                .getWorld()
                .createExplosion(
                        targetVent, settings.explosionSize, true, false);
        vent.location
                .getWorld()
                .createExplosion(
                        targetVent, settings.damagingExplosionSize, false, true);

        for (int i = 0; i < bombCount; i++) {
            vent.bombs.requestBombLaunch();
        }

        // sentient mode.
        List<Player> players = vent.getPlayersInRange();
        for (Player player : players) {
            if (!player.isFlying() && !vent.isInVent(player.getLocation())) {
                int sentientBombs = (int) (bombCount * 0.1 * Math.random());

                if (sentientBombs > 0) {
                    vent.volcano.logger.log(
                            VolcanoLogClass.EXPLOSION,
                            "Striking "
                                    + sentientBombs
                                    + " volcanic bombs to player "
                                    + player.getDisplayName());

                    for (int i = 0; i < sentientBombs; i++) {
                        vent.bombs.launchBombToDestination(player.getLocation());
                    }
                }
            }
        }

        if (queueComplete) {
            vent.volcano.logger.debug(
                    VolcanoLogClass.EXPLOSION,
                    "bomb throwing Queue completed"
            );
            this.runQueueComplete(targetVent);
        } else {
            vent.volcano.logger.debug(
                    VolcanoLogClass.EXPLOSION,
                    bombCount+" bombs thrown. Currently Queued: "+queuedBombs
            );
            this.vent.ash.createAshPlume();
        }
    }

    public void runQueueComplete(Location targetVent) {
        if (this.vent != null) {
            double pyroclast = this.vent.erupt.getStyle().getPyroclasticFlowMultiplier();
            if (pyroclast > 0) {
                for (int i = 0; i < pyroclast; i++) {
                    if (Math.random() < 0.4) {
                        this.vent.ash.createAshPlume(targetVent);
                        this.vent.ash.triggerPyroclasticFlow();
                    }
                }
            }
        }
    }

    public void importConfig(JSONObject configData) {
        this.settings.importConfig(configData);
        this.enabled = (boolean) configData.get("enabled");
        this.running = (boolean) configData.get("running");
    }

    public JSONObject exportConfig() {
        JSONObject config = this.settings.exportConfig();
        config.put("enabled", this.enabled);
        config.put("running", this.running);

        return config;
    }
}
