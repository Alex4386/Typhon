package me.alex4386.plugin.typhon.volcano.explosion;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

import org.bukkit.*;
import org.bukkit.block.Block;
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
                                if (this.enabled && this.running) {
                                    explode();
                                }
                            },
                            0l,
                            (long) Math.max(
                                    this.settings.explosionDelay
                                            / vent.getVolcano().getTickFactor(),
                                    1));
        }
    }

    public void unregisterTask() {
        if (scheduleID >= 0) {
            this.vent.volcano.logger.log(
                    VolcanoLogClass.EXPLOSION,
                    "Unregistering VolcanoExplosion for vent " + vent.getName());
            Bukkit.getScheduler().cancelTask(scheduleID);
            scheduleID = -1;
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
        int bombCount = (int) ((Math.random() * (settings.maxBombCount - settings.minBombCount)
                + settings.minBombCount)
                * this.vent.erupt.getStyle().bombMultiplier);
        explode(bombCount);
    }

    public void explode(int bombCount) {
        explode(bombCount, true);
    }

    public void explode(int bombCount, boolean tremor) {
        explode(bombCount, tremor, true);
    }

    public void explode(int bombCount, boolean tremor, boolean smoke) {
        explode(bombCount, tremor, smoke, true);
    }

    public void explode(int bombCount, boolean tremor, boolean smoke, boolean summitExplode) {
        if (summitExplode) {
            vent.location
                    .getWorld()
                    .createExplosion(
                            this.selectEruptionVent(), settings.explosionSize, true, false);
            vent.location
                    .getWorld()
                    .createExplosion(
                            this.selectEruptionVent(), settings.damagingExplosionSize, false, true);
        }

        if (tremor) {
            // vent.tremor.eruptTremor();
        }

        if (smoke) {
            Random random = new Random();
            int size = 4 + random.nextInt(3);
            for (int i = 0; i < 30; i++) {
                Bukkit.getScheduler()
                        .runTaskLater(
                                TyphonPlugin.plugin,
                                (Runnable) () -> {
                                    vent.ash.createAshPlume();
                                },
                                5L * i);
            }

            // vent.generateSteam(5);
        }

        for (int i = 0; i < bombCount; i++) {
            vent.bombs.launchBomb();
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
