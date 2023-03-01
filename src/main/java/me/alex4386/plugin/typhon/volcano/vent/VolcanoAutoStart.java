package me.alex4386.plugin.typhon.volcano.vent;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoEruptStyle;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;

import org.bukkit.Bukkit;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
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

    // 72000 / 20 = 1h
    public long statusCheckInterval = getMinutes(60);
    public long flankEruptionGracePeriod = getMinutes(5);

    public long eruptionTimer = 20*60*30;
    public int scheduleID = -1;
    public int styleChangeId = -1;

    public boolean registeredEvent = false;

    public static long getSeconds(long seconds) {
        return seconds * 20;
    }

    public static long getMinutes(long minutes) {
        return getSeconds(minutes * 60);
    }

    public VolcanoAutoStart(Volcano volcano) {
        this.volcano = volcano;
    }

    public void registerTask() {
        if (scheduleID >= 0 || styleChangeId >= 0) {
            return;
        }

        scheduleID = Bukkit.getScheduler()
                .scheduleSyncRepeatingTask(
                        TyphonPlugin.plugin,
                        () -> {
                            updateStatus();
                        },
                        0L,
                        Math.max(1, (statusCheckInterval / 20) * volcano.updateRate));

        styleChangeId = Bukkit.getScheduler()
                .scheduleSyncRepeatingTask(
                        TyphonPlugin.plugin,
                        () -> {
                            updateStyles();
                        },
                        0L,
                        Math.max(1, (statusCheckInterval / 20) * volcano.updateRate));

    }

    public void unregisterTask() {
        if (scheduleID >= 0) {
            Bukkit.getScheduler().cancelTask(scheduleID);
            scheduleID = -1;
        }
        if (styleChangeId >= 0) {
            Bukkit.getScheduler().cancelTask(styleChangeId);
            styleChangeId = -1;
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

    public void updateStyles() {
        for (VolcanoVent vent : volcano.manager.getVents()) {
            if (vent.getType() == VolcanoVentType.FISSURE) {
                if (vent.record.getTotalEjecta() >= 100000) {
                    if (Math.random() < 0.7 || vent.lavaFlow.settings.silicateLevel > 0.5) {
                        vent.setType(VolcanoVentType.CRATER);
                    }
                }
            } else {
                // try to do magma evolutions
                double random = Math.random();
                if (random < 0.002) {
                    vent.lavaFlow.settings.silicateLevel += 0.001;
                } else if (random < 0.003) {
                    vent.lavaFlow.settings.silicateLevel -= 0.001;
                }

                if (vent.lavaFlow.settings.silicateLevel < 0.2) {
                    vent.lavaFlow.settings.silicateLevel = 0.2;
                }

                if (vent.lavaFlow.settings.silicateLevel > 0.75) {
                    vent.erupt.setStyle(VolcanoEruptStyle.PLINIAN);
                    vent.lavaFlow.settings.silicateLevel = 0.63;
                } else if (vent.lavaFlow.settings.silicateLevel > 0.63) {
                    if (Math.random() < 0.125) {
                        if (vent.erupt.getStyle() == VolcanoEruptStyle.VULCANIAN) {
                            vent.erupt.setStyle(VolcanoEruptStyle.PELEAN);
                        } else {
                            vent.erupt.setStyle(VolcanoEruptStyle.VULCANIAN);
                        }
                    }
                } else {
                    if (Math.random() < 0.25) {
                        if (vent.erupt.getStyle() == VolcanoEruptStyle.HAWAIIAN) {
                            vent.erupt.setStyle(VolcanoEruptStyle.STROMBOLIAN);
                        }
                    }
                }
            }
        }
    }

    public boolean canDoFlankEruption() {
        double silicateLevel = volcano.mainVent.lavaFlow.settings.silicateLevel;
        double basaltiness = (0.53 - silicateLevel) / (0.53 - 0.46);

        return basaltiness >= 0;
    }

    public void startVentWithGracePeriod(VolcanoVent vent) {
        this.startVentWithGracePeriod(vent, null);
    }

    public void startVentWithGracePeriod(VolcanoVent vent, Runnable callback) {
        // emulate lava to migrating to target.
        vent.setStatus(VolcanoVentStatus.MAJOR_ACTIVITY);
        Bukkit.getScheduler().runTaskLater(TyphonPlugin.plugin, () -> {
            vent.start();

            if (callback != null) callback.run();
        }, (flankEruptionGracePeriod / 20) * volcano.updateRate);
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
                            boolean canTryFlank = (vent.getSummitBlock().getY() - vent.location.getY() > 60);
                            boolean reachedWorldHeight = vent.getSummitBlock().getY() >= vent.location.getWorld().getMaxHeight() - 5;

                            if (reachedWorldHeight || canTryFlank && Math.random() < 0.1) {
                                if (vent.erupt.getStyle().flowsLava() && !vent.erupt.getStyle().canFormCaldera) {
                                    if (this.canDoFlankEruption()) {
                                        if (reachedWorldHeight) {
                                            if (Math.random() < 0.1) {
                                                vent.volcano.logger.log(
                                                        VolcanoLogClass.AUTOSTART,
                                                        "volcano starting caldera forming eruption since it has reached the world height");

                                                vent.caldera.autoSetup();
                                                vent.caldera.startErupt();
                                            }
                                            break;
                                        }

                                        vent.volcano.logger.log(
                                                VolcanoLogClass.AUTOSTART,
                                                "volcano starting flank eruption due to increment from major_activity");

                                        VolcanoVent flankVent = vent.erupt.openFissure();
                                        if (flankVent != null) this.startVentWithGracePeriod(flankVent, () -> {
                                            Bukkit.getScheduler().runTaskLater(TyphonPlugin.plugin,
                                                    () -> {
                                                        flankVent.stop();
                                                        flankVent.setStatus(flankVent.getStatus().decrease());
                                                    },
                                                    volcano.autoStart.eruptionTimer);
                                        });
                                    }
                                }
                            }

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
