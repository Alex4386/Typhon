package me.alex4386.plugin.typhon.volcano.vent;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.plugin.PluginManager;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.util.*;

public class VolcanoAutoStart implements Listener {
    public Volcano volcano;

    public static boolean defaultCanAutoStart = true;

    public boolean canAutoStart = defaultCanAutoStart;
    public boolean pourLavaStart = true;

    public boolean createSubVent = true;

    public VolcanoAutoStartProbability probability = new VolcanoAutoStartProbability();

    public long statusCheckInterval = 72000;
    public long flankEruptionGracePeriod = 6000;

    private boolean flankTriggered = false;

    public long eruptionTimer = 12000;
    public int scheduleID = -1;

    public boolean registeredEvent = false;

    public VolcanoAutoStart(Volcano volcano) {
        this.volcano = volcano;
    }

    public void registerTask() {
        if (scheduleID >= 0) {
            return;
        }
        Bukkit.getScheduler()
                .scheduleSyncRepeatingTask(
                        TyphonPlugin.plugin,
                        () -> {
                            updateStatus();

                            if (flankTriggered) {
                                createFissure();
                            } else {
                                flankTriggered = true;
                            }
                        },
                        0L,
                        Math.max(1, (statusCheckInterval / 20) * volcano.updateRate));
    }

    public void unregisterTask() {
        if (scheduleID >= 0) {
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
        this.volcano.logger.log(VolcanoLogClass.AUTOSTART, "Intializing Volcano Autostart...");

        this.registerTask();
        this.registerEvent();
    }

    public void shutdown() {
        this.volcano.logger.log(VolcanoLogClass.AUTOSTART, "Shutting down Volcano Autostart...");

        this.unregisterTask();
        this.unregisterEvent();
    }

    @EventHandler
    public void playerLavaFlow(PlayerBucketEmptyEvent event) {
        Material bucket = event.getBucket();
        if (bucket == Material.LAVA_BUCKET || bucket == Material.LAVA) {
            Player player = event.getPlayer();
            for (VolcanoVent vent : volcano.manager.getVents()) {
                if (vent.isInVent(player.getLocation())) {
                    // start lava flow on vent.
                }
            }
        }
    }

    public void createFissure() {
        if (volcano.autoStart.canAutoStart) {
            volcano.logger.debug(
                    VolcanoLogClass.AUTOSTART, "Volcano Fissure Opening interval Checking...");

            int erupting = volcano.getEruptingVents().size();
            int eruptables = volcano.maxEruptions - erupting;

            if (eruptables > 0) {
                List<VolcanoVent> vents = volcano.manager.getVents();
                Collections.shuffle(vents);

                for (VolcanoVent vent : vents) {
                    double silicateLevel = vent.lavaFlow.settings.silicateLevel;
                    double basaltiness = Math.min(1, Math.max(0, (0.53 - silicateLevel) / (0.53 - 0.46)));

                    // flank eruption / parasitic cone should be only generated on basaltic eruptions
                    if ((vent.erupt.isErupting() || volcano.isVolcanicField()) && silicateLevel < 0.53) {
                        if (Math.random() > 0.7 - (0.3 * basaltiness)) continue;

                        boolean migrateLavaFlow = false;
                        if (Math.random() < 0.5) {
                            migrateLavaFlow = true;
                        }

                        VolcanoVent newVent = vent.erupt.openFissure();
                        if (newVent == null) continue;

                        if (migrateLavaFlow) {
                            vent.erupt.stop();
                        }

                        // emulate lava to migrating to target.
                        newVent.setStatus(VolcanoVentStatus.MAJOR_ACTIVITY);
                        Bukkit.getScheduler().runTaskLater(TyphonPlugin.plugin, () -> {
                            newVent.start();

                            Bukkit.getScheduler().runTaskLater(TyphonPlugin.plugin, () -> {
                                newVent.stop();
                            }, eruptionTimer);
                        }, (flankEruptionGracePeriod / 20) * volcano.updateRate);

                        eruptables--;
                        if (eruptables <= 0) break;
                    }
                }
            }
        }

    }

    public void updateStatus() {
        if (volcano.autoStart.canAutoStart) {
            volcano.logger.debug(
                    VolcanoLogClass.AUTOSTART, "Volcano AutoStart interval Checking...");

            for (VolcanoVent vent : volcano.manager.getVents()) {
                boolean canEscalate = vent.genesis.canEruptAgain();

                VolcanoVentStatus status = vent.getStatus();
                double a = Math.random();

                switch (vent.getStatus()) {
                    case DORMANT:
                        if (a <= probability.dormant.increase) {
                            if (canEscalate) vent.setStatus(vent.getStatus().increase());
                        } else if (a <= probability.dormant.increase + probability.dormant.decrease) {
                            if (!canEscalate) {
                                if (!vent.isMainVent()) {
                                    vent.setStatus(VolcanoVentStatus.EXTINCT);
                                }
                            }
                        }
                        break;
                    case MINOR_ACTIVITY:
                        if (a <= probability.minor_activity.increase) {
                            if (canEscalate) vent.setStatus(vent.getStatus().increase());
                        } else if (a
                                <= probability.minor_activity.increase
                                        + probability.minor_activity.decrease) {
                            vent.setStatus(vent.getStatus().decrease());
                        }
                        break;
                    case MAJOR_ACTIVITY:
                        if (a <= probability.major_activity.increase) {
                            if (!canEscalate) break;

                            vent.volcano.logger.log(
                                VolcanoLogClass.AUTOSTART,
                                "volcano starting due to increment from major_activity");
                            vent.setStatus(vent.getStatus().increase());
                            vent.start();
                            Bukkit.getScheduler()
                                    .scheduleSyncDelayedTask(
                                            TyphonPlugin.plugin,
                                            () -> {
                                                vent.volcano.logger.log(
                                                        VolcanoLogClass.AUTOSTART,
                                                        "Volcano ended eruption session. back to"
                                                            + " MAJOR_ACTIVITY");
                                                vent.stop();
                                                vent.setStatus(vent.getStatus().decrease());
                                            },
                                            volcano.autoStart.eruptionTimer);
                        } else if (a
                                <= probability.major_activity.increase
                                        + probability.major_activity.decrease) {
                            vent.setStatus(vent.getStatus().decrease());
                        }
                        break;
                    default:
                        break;
                }

                if (vent.getStatus() != status) {
                    volcano.logger.debug(
                            VolcanoLogClass.AUTOSTART,
                            "Volcano has changed status to" + vent.getStatus().toString());
                }
            }

            try {
                volcano.save();
            } catch (IOException e) {

            }
        }
    }

    public void importConfig(JSONObject configData) {
        this.canAutoStart = (boolean) configData.get("canAutoStart");
        this.createSubVent = (boolean) configData.get("createSubVent");
        this.pourLavaStart = (boolean) configData.get("pourLavaStart");
        this.eruptionTimer = (long) configData.get("eruptionTimer");
        this.statusCheckInterval = (long) configData.get("statusCheckInterval");
        this.probability.importConfig((JSONObject) configData.get("probability"));
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        configData.put("canAutoStart", this.canAutoStart);
        configData.put("pourLavaStart", this.pourLavaStart);
        configData.put("createSubVent", this.createSubVent);
        configData.put("eruptionTimer", this.eruptionTimer);
        configData.put("statusCheckInterval", this.statusCheckInterval);
        configData.put("probability", this.probability.exportConfig());

        return configData;
    }
}

class VolcanoAutoStartProbability {
    public VolcanoAutoStartStatusProbability dormant =
            new VolcanoAutoStartStatusProbability(0.05, 0.001);
    public VolcanoAutoStartStatusProbability minor_activity =
            new VolcanoAutoStartStatusProbability(0.1, 0.05);
    public VolcanoAutoStartStatusProbability major_activity =
            new VolcanoAutoStartStatusProbability(0.2, 0.25);

    public void importConfig(JSONObject configData) {
        dormant = new VolcanoAutoStartStatusProbability((JSONObject) configData.get("dormant"));
        minor_activity =
                new VolcanoAutoStartStatusProbability(
                        (JSONObject) configData.get("minor_activity"));
        major_activity =
                new VolcanoAutoStartStatusProbability(
                        (JSONObject) configData.get("major_activity"));
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
