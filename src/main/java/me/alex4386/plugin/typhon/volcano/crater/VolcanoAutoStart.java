package me.alex4386.plugin.typhon.volcano.crater;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.VolcanoStatus;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.plugin.PluginManager;
import org.json.simple.JSONObject;

import java.io.IOException;

public class VolcanoAutoStart implements Listener {
    public Volcano volcano;

    public static boolean defaultCanAutoStart = true;

    public boolean canAutoStart = defaultCanAutoStart;
    public boolean pourLavaStart = true;

    public VolcanoAutoStartProbability probability = new VolcanoAutoStartProbability();

    public long statusCheckInterval = 144000;

    public long eruptionTimer = 12000;
    public int scheduleID = -1;

    public boolean registeredEvent = false;

    public VolcanoAutoStart(Volcano volcano) {
        this.volcano = volcano;
    }

    public void registerTask() {
        if (scheduleID >= 0) { return; }
        Bukkit.getScheduler().scheduleSyncRepeatingTask(TyphonPlugin.plugin,
            () -> {
                updateStatus();
            },
        0L, statusCheckInterval);
    }

    public void unregisterTask() {
        if (scheduleID  >= 0) {
            Bukkit.getScheduler().cancelTask(scheduleID);
            scheduleID = -1;
        }
    }

    public void registerEvent() {
        if (!registeredEvent) {
            PluginManager pm = Bukkit.getPluginManager();
            pm.registerEvents(this, TyphonPlugin.plugin);
            registeredEvent = true;
        }
    }

    public void unregisterEvent() {
        if (registeredEvent) {
            BlockFromToEvent.getHandlerList().unregisterAll(this);
            registeredEvent = false;
        }
    }

    public void initialize() {
        this.registerTask();
        this.registerEvent();
    }

    public void shutdown() {
        this.unregisterTask();
        this.unregisterEvent();
    }

    @EventHandler
    public void playerLavaFlow(PlayerBucketEmptyEvent event) {
        Material bucket = event.getBucket();
        if (bucket == Material.LAVA_BUCKET || bucket == Material.LAVA) {
            Player player = event.getPlayer();
            for (VolcanoCrater crater : volcano.manager.getCraters()) {
                if (crater.isInCrater(player.getLocation())) {
                   // start lava flow on crater.
                }
            }
        }
    }

    public void updateStatus() {
        if (volcano.autoStart.canAutoStart && volcano.status != VolcanoStatus.ERUPTING) {
            volcano.logger.debug(VolcanoLogClass.AUTOSTART, "Volcano AutoStart interval Checking...");

            double a = Math.random();

            VolcanoStatus status = volcano.status;

            switch (volcano.status) {
                case DORMANT:
                    if (a <= probability.dormant.increase) {
                        volcano.status = volcano.status.increase();
                    }
                    break;
                case MINOR_ACTIVITY:
                    if (a <= probability.minor_activity.increase) {
                        volcano.status = volcano.status.increase();
                    } else if (a <= probability.minor_activity.increase + probability.minor_activity.decrease) {
                        volcano.status = volcano.status.decrease();
                    }
                    break;
                case MAJOR_ACTIVITY:
                    if (a <= probability.major_activity.increase) {
                        volcano.logger.log(VolcanoLogClass.AUTOSTART, "volcano starting due to increment from major_activity");
                        volcano.status = volcano.status.increase();
                        volcano.start();
                        Bukkit.getScheduler().scheduleSyncDelayedTask(TyphonPlugin.plugin, () -> {
                            volcano.logger.log(VolcanoLogClass.AUTOSTART, "Volcano ended eruption session. back to MAJOR_ACTIVITY");
                            volcano.stop();
                            volcano.status = volcano.status.decrease();
                        }, volcano.autoStart.eruptionTimer);
                    } else if (a <= probability.major_activity.increase + probability.major_activity.decrease) {
                        volcano.status = volcano.status.decrease();
                    }
                    break;
                default:
                    break;
            }

            if (status != volcano.status) {
                volcano.logger.debug(VolcanoLogClass.AUTOSTART, "Volcano has changed status to" + volcano.status.toString());
            }

            try {
                volcano.save();
            } catch (IOException e) {

            }
        }
    }

    public void importConfig(JSONObject configData) {
        this.canAutoStart = (boolean) configData.get("canAutoStart");
        this.pourLavaStart = (boolean) configData.get("pourLavaStart");
        this.eruptionTimer = (long) configData.get("eruptionTimer");
        this.statusCheckInterval = (long) configData.get("statusCheckInterval");
        this.probability.importConfig((JSONObject) configData.get("probability"));
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        configData.put("canAutoStart", this.canAutoStart);
        configData.put("pourLavaStart", this.pourLavaStart);
        configData.put("eruptionTimer", this.eruptionTimer);
        configData.put("statusCheckInterval", this.statusCheckInterval);
        configData.put("probability", this.probability.exportConfig());

        return configData;
    }
}

class VolcanoAutoStartProbability {
    public VolcanoAutoStartStatusProbability dormant = new VolcanoAutoStartStatusProbability(0.05, 0.001);
    public VolcanoAutoStartStatusProbability minor_activity = new VolcanoAutoStartStatusProbability(0.2, 0.05);
    public VolcanoAutoStartStatusProbability major_activity = new VolcanoAutoStartStatusProbability(0.35, 0.25);

    public void importConfig(JSONObject configData) {
        dormant = new VolcanoAutoStartStatusProbability((JSONObject) configData.get("dormant"));
        minor_activity = new VolcanoAutoStartStatusProbability((JSONObject) configData.get("minor_activity"));
        major_activity = new VolcanoAutoStartStatusProbability((JSONObject) configData.get("major_activity"));
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();
        configData.put("dormant", dormant.exportConfig());
        configData.put("minor_activity", minor_activity.exportConfig());
        configData.put("major_activity", major_activity.exportConfig());
        return configData;
    }

}

class VolcanoAutoStartStatusProbability {
    public double increase;
    public double decrease;

    public VolcanoAutoStartStatusProbability(double increase, double decrease) {
        this.increase = increase;
        this.decrease = decrease;
    }

    public VolcanoAutoStartStatusProbability(JSONObject configData) {
        this.importConfig(configData);
    }

    public void importConfig(JSONObject configData) {
        this.increase = (double) configData.get("increase");
        this.decrease = (double) configData.get("decrease");
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();
        configData.put("increase", this.increase);
        configData.put("decrease", this.decrease);

        return configData;
    }
}
