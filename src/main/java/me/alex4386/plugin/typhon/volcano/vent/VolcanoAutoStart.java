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
    public long subVentInterval = 72000;

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
                autoStartCreateSubVent();
            },
        0L, Math.max(1, statusCheckInterval / 20 * volcano.updateRate));
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

    public VolcanoVent createSubVent(Location location) {
        String name = "";
        boolean generated = false;

        for (int key = 1; key < 999; key++) {
            name = volcano.name+"_auto"+String.format("%03d", key);
            if (volcano.subVents.get(name) == null) {
                generated = true;
                break;
            }
        }

        if (generated) {
            VolcanoVent newVent = new VolcanoVent(volcano, location, name);
            volcano.subVents.put(name, newVent);

            volcano.trySave(true);
            return newVent;
        } else {
            return null;
        }
    }

    public VolcanoVent autoStartCreateSubVent() {
        return this.autoStartCreateSubVent(null);
    }

    public VolcanoVent autoStartCreateSubVent(Player player) {
        int availableHeight = volcano.mainVent.location.getWorld().getMaxHeight() - volcano.mainVent.location.getBlockY();
        int mainVentHeight = ((int) volcano.mainVent.averageVentHeight()) - volcano.mainVent.location.getBlockY();
        boolean isMainventGrownEnough = (volcano.mainVent.longestFlowLength > 150 && mainVentHeight >= Math.min(100, availableHeight));

        if (isMainventGrownEnough && createSubVent) {
            VolcanoVent vent = volcano.mainVent;

            Location location;
            List<Player> players = volcano.manager.getAffectedPlayers();
            Collections.shuffle(players);

            if ((Math.random() < 0.7 || players.size() == 0) && player == null) {
                location = TyphonUtils.getRandomBlockInRange(
                        vent.location.getBlock(),
                        (int) Math.max(volcano.mainVent.longestFlowLength, 100),
                        (int) Math.max(volcano.mainVent.longestFlowLength * 2, 200)
                ).getLocation();

                volcano.logger.log(VolcanoLogClass.AUTOSTART, "Volcano will now create a subvent!");
            } else {
                Player target = players.get(0);
                if (player != null) {
                    target = player;
                }
                double distance = volcano.mainVent.getTwoDimensionalDistance(target.getLocation());

                double xDiff = target.getLocation().getX() - volcano.mainVent.location.getX();
                double zDiff = target.getLocation().getZ() - volcano.mainVent.location.getZ();

                double sin = 0;
                double cos = 0;
                if (Math.abs(xDiff) >= 1) {
                    double tan = zDiff / xDiff;

                    double rad = Math.atan(tan);
                    sin = Math.sin(rad);
                    cos = Math.cos(rad);
                } else {
                    sin = 1;
                    cos = 0;
                }

                double newDistance = Math.max(volcano.mainVent.longestFlowLength + 50, distance);
                double newXDiff = newDistance * cos;
                double newZDiff = newDistance * sin;

                volcano.logger.log(VolcanoLogClass.AUTOSTART, "Volcano will now create a subvent near-by a player "+target.getName());

                location = volcano.mainVent.location.add(newXDiff, 0, newZDiff);

                location = TyphonUtils.getRandomBlockInRange(
                        location.getBlock(),
                        0,
                        (int) (volcano.mainVent.getTwoDimensionalDistance(location) - volcano.mainVent.longestFlowLength)
                ).getLocation();
                location = TyphonUtils.getHighestLocation(location);
            }

            if (!volcano.manager.isInAnyLavaFlowArea(location)) {
                int key;
                String name;
                Random random = new Random();

                do {
                    key = random.nextInt(999);
                    name = volcano.name+"_a"+String.format("%03d", key);
                } while(!volcano.subVents.containsKey(name));

                VolcanoVent newVent = new VolcanoVent(volcano, location, name);

                volcano.logger.log(VolcanoLogClass.AUTOSTART, "Volcano is now creating a new subvent "+name);

                if (newVent != null) {
                    newVent.start();
                    newVent.getVolcano().trySave(true);

                    Bukkit.getScheduler().runTaskLater(TyphonPlugin.plugin,
                            (Runnable) () -> {
                                newVent.stop();
                            },
                            eruptionTimer
                    );
                }

                return newVent;
            }

            return null;
        }

        return null;
    }

    public void updateStatus() {
        if (volcano.autoStart.canAutoStart) {
            volcano.logger.debug(VolcanoLogClass.AUTOSTART, "Volcano AutoStart interval Checking...");

            for (VolcanoVent vent : volcano.manager.getVents()) {
                VolcanoVentStatus status = vent.status;
                double a = Math.random();


                switch (vent.status) {
                    case DORMANT:
                        if (a <= probability.dormant.increase) {
                            vent.status = vent.status.increase();
                        }
                        break;
                    case MINOR_ACTIVITY:
                        if (a <= probability.minor_activity.increase) {
                            vent.status = vent.status.increase();
                        } else if (a <= probability.minor_activity.increase + probability.minor_activity.decrease) {
                            vent.status = vent.status.decrease();
                        }
                        break;
                    case MAJOR_ACTIVITY:
                        if (a <= probability.major_activity.increase) {
                            vent.volcano.logger.log(VolcanoLogClass.AUTOSTART, "volcano starting due to increment from major_activity");
                            vent.status = vent.status.increase();
                            vent.start();
                            Bukkit.getScheduler().scheduleSyncDelayedTask(TyphonPlugin.plugin, () -> {
                                vent.volcano.logger.log(VolcanoLogClass.AUTOSTART, "Volcano ended eruption session. back to MAJOR_ACTIVITY");
                                vent.stop();
                                vent.status = vent.status.decrease();
                            }, volcano.autoStart.eruptionTimer);
                        } else if (a <= probability.major_activity.increase + probability.major_activity.decrease) {
                            vent.status = vent.status.decrease();
                        }
                        break;
                    default:
                        break;
                }

                if (vent.status != status) {
                    volcano.logger.debug(VolcanoLogClass.AUTOSTART, "Volcano has changed status to" + vent.status.toString());
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
    public VolcanoAutoStartStatusProbability dormant = new VolcanoAutoStartStatusProbability(0.05, 0.001);
    public VolcanoAutoStartStatusProbability minor_activity = new VolcanoAutoStartStatusProbability(0.1, 0.05);
    public VolcanoAutoStartStatusProbability major_activity = new VolcanoAutoStartStatusProbability(0.2, 0.25);

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
