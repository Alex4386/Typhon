package me.alex4386.plugin.typhon.volcano.vent;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.json.simple.JSONObject;

import java.util.List;
import java.util.Random;

public class VolcanoErupt {
    public VolcanoVent vent;

    public boolean enabled = true;
    public boolean erupting = false;

    public VolcanoEruptionSettings settings = new VolcanoEruptionSettings();

    public int scheduleID = -1;

    public VolcanoErupt(VolcanoVent vent) {
        this.vent = vent;
    }

    public void registerTask() {
        if (this.scheduleID < 0) {
            this.vent.volcano.logger.log(VolcanoLogClass.ERUPT, "Registering VolcanoErupt for vent "+vent.getName());
            this.scheduleID = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                TyphonPlugin.plugin,
                () -> {
                    if (this.enabled && this.erupting) {
                        erupt();
                    }
                },
                this.settings.explosionDelay * vent.getVolcano().updateRate,
                this.settings.explosionDelay * vent.getVolcano().updateRate
            );
        }
    }

    public void unregisterTask() {
        if (scheduleID >= 0) {
            this.vent.volcano.logger.log(VolcanoLogClass.ERUPT, "Unregistering VolcanoErupt for vent "+vent.getName());
            Bukkit.getScheduler().cancelTask(scheduleID);
            scheduleID = -1;
        }
    }

    public void initialize() {
        this.vent.volcano.logger.log(VolcanoLogClass.ERUPT, "Intializing VolcanoErupt for vent "+vent.getName());
        this.registerTask();
    }

    public void shutdown() {
        this.vent.volcano.logger.log(VolcanoLogClass.ERUPT, "Shutting down VolcanoErupt for vent "+vent.getName());
        this.unregisterTask();
    }

    // get eruption location
    public Location getEruptionLocation() {
        int theY = vent.getSummitBlock().getY();

        Location location = vent.location;
        if (vent.getType() == VolcanoVentType.FISSURE) {
            location = vent.selectCoreBlock().getLocation();
        }

        Block launchBlock = new Location(location.getWorld(), location.getX(), theY, location.getZ()).getBlock();
        return launchBlock.getLocation();
    }

    public void erupt() {
        int bombCount = (int) (Math.random() * (settings.maxBombCount - settings.minBombCount) + settings.minBombCount);
        erupt(bombCount);
    }

    public void erupt(int bombCount) {
        erupt(bombCount, true);
    }

    public void erupt(int bombCount, boolean tremor) {
        erupt(bombCount, tremor, true);
    }

    public void erupt(int bombCount, boolean tremor, boolean smoke) {
        erupt(bombCount, tremor, smoke, true);
    }

    public void erupt(int bombCount, boolean tremor, boolean smoke, boolean summitExplode) {
        if (summitExplode) {
            vent.location.getWorld().createExplosion(this.getEruptionLocation(), settings.explosionSize, true, false);
            vent.location.getWorld().createExplosion(this.getEruptionLocation(), settings.damagingExplosionSize, false, true);
        }

        if (tremor) {
            // vent.tremor.eruptTremor();
        }

        if (smoke) {
            Random random = new Random();
            int size = 4 + random.nextInt(3);
            for (int i = 0; i < 30; i++) {
                Bukkit.getScheduler().runTaskLater(
                        TyphonPlugin.plugin,
                        (Runnable) () -> {
                            vent.generateSmoke(size);
                        },
                        5L * i
                );
            }

            //vent.generateSteam(5);
        }

        for (int i = 0; i < bombCount; i++) {
            vent.bombs.launchBomb();
        }

        // sentient mode.
        List<Player> players = vent.getPlayersInRange();
        for (Player player : players) {
            if (!player.isFlying() && !vent.isInVent(player.getLocation())) {
                int sentientBombs = (int)(bombCount * 0.1 * Math.random());

                if (sentientBombs > 0) {
                    vent.volcano.logger.log(VolcanoLogClass.ERUPT, "Striking "+sentientBombs+" volcanic bombs to player "+player.getDisplayName());

                    for (int i = 0; i < sentientBombs; i++) {
                        vent.bombs.launchBombToDestination(
                                player.getLocation()
                        );
                    }
                }
            }
        }
    }

    public void importConfig(JSONObject configData) {
        this.settings.importConfig(configData);
    }

    public JSONObject exportConfig() {
        return this.settings.exportConfig();
    }
}
