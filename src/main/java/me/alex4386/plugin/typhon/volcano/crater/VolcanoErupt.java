package me.alex4386.plugin.typhon.volcano.crater;

import me.alex4386.plugin.typhon.TyphonPlugin;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.json.simple.JSONObject;

import java.util.Random;

public class VolcanoErupt {
    public VolcanoCrater crater;

    public boolean enabled = true;
    public boolean erupting = false;

    public VolcanoEruptionSettings settings = new VolcanoEruptionSettings();

    public int scheduleID = -1;

    public VolcanoErupt(VolcanoCrater crater) {
        this.crater = crater;
    }

    public void registerTask() {
        if (this.scheduleID < 0) {
            System.out.println("Register VolcanoErupt");
            this.scheduleID = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                TyphonPlugin.plugin,
                () -> {
                    if (this.enabled && this.erupting) {
                        erupt();
                    }
                },
                this.settings.explosionDelay * crater.getVolcano().updateRate,
                this.settings.explosionDelay * crater.getVolcano().updateRate
            );
        }
    }

    public void unregisterTask() {
        if (scheduleID >= 0) {
            Bukkit.getScheduler().cancelTask(scheduleID);
            scheduleID = -1;
        }
    }

    public void initialize() {
        this.registerTask();
    }

    public void shutdown() {
        this.unregisterTask();
    }

    // get eruption location
    public Location getEruptionLocation() {
        int theY = crater.getSummitBlock().getY();

        Block launchBlock = new Location(crater.location.getWorld(), crater.location.getX(), theY, crater.location.getZ()).getBlock();
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
            crater.location.getWorld().createExplosion(this.getEruptionLocation(), settings.explosionSize, true, false);
            crater.location.getWorld().createExplosion(this.getEruptionLocation(), settings.damagingExplosionSize, false, true);
        }

        if (tremor) {
            crater.tremor.eruptTremor();
        }

        if (smoke) {
            Random random = new Random();
            int size = 4 + random.nextInt(3);
            for (int i = 0; i < 30; i++) {
                Bukkit.getScheduler().runTaskLater(
                        TyphonPlugin.plugin,
                        (Runnable) () -> {
                            crater.generateSmoke(size);
                        },
                        5L * i
                );
            }

            //crater.generateSteam(5);
        }

        for (int i = 0; i < bombCount; i++) {
            crater.bombs.launchBomb();
        }
    }

    public void importConfig(JSONObject configData) {
        this.settings.importConfig(configData);
    }

    public JSONObject exportConfig() {
        return this.settings.exportConfig();
    }
}
