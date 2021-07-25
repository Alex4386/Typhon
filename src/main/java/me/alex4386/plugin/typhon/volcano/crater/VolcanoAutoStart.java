package me.alex4386.plugin.typhon.volcano.crater;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
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

    public boolean createSubCrater = true;

    public VolcanoAutoStartProbability probability = new VolcanoAutoStartProbability();

    public long statusCheckInterval = 72000;
    public long subCraterInterval = 72000;

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
                autoStartCreateSubCrater();
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

    public VolcanoCrater createSubCrater(Location location) {
        String name = "";
        boolean generated = false;

        for (int key = 1; key < 999; key++) {
            name = volcano.name+"_auto"+String.format("%03d", key);
            if (volcano.subCraters.get(name) == null) {
                generated = true;
                break;
            }
        }

        if (generated) {
            VolcanoCrater newCrater = new VolcanoCrater(volcano, location, name);
            volcano.subCraters.put(name, newCrater);

            volcano.trySave();
            return newCrater;
        } else {
            return null;
        }
    }

    public VolcanoCrater createSubCrater(VolcanoCrater crater) {
        int minRange = (int) (((Math.random() * 0.2) + 0.4) * crater.longestFlowLength) + crater.craterRadius;
        int range = (int) (Math.random() * ((crater.bombs.maxDistance <= 100 ? 100 : crater.bombs.maxDistance) - minRange));
        return createSubCrater(crater.location, minRange, minRange + range);
    }

    public VolcanoCrater createSubCrater(Location location, int minRange, int maxRange) {
        Location craterLocation;

        do {
            craterLocation = TyphonUtils.getHighestRocklikes(
                    TyphonUtils.getRandomBlockInRange(
                            location.getBlock(),
                            minRange,
                            maxRange
                    )
            ).getLocation();
        } while (volcano.manager.getCraterRadiusInRange(craterLocation, minRange).size() == 0);


        return createSubCrater(craterLocation);
    }

    public VolcanoCrater createSubCraterNearEntity(Entity entity) {
        int minRange = (int) (Math.random() * 30) + 10;
        int range = (int) (Math.random() * 100);

        return createSubCraterNearEntity(entity, minRange, range);
    }

    public VolcanoCrater createSubCraterNearEntity(Entity entity, int minRange, int maxRange) {
        Location location = entity.getLocation();
        return createSubCrater(location, minRange, maxRange);
    }

    public VolcanoCrater autoStartCreateSubCrater() {
        int availableHeight = volcano.mainCrater.location.getWorld().getMaxHeight() - volcano.mainCrater.location.getBlockY();
        int mainCraterHeight = ((int) volcano.mainCrater.averageCraterHeight()) - volcano.mainCrater.location.getBlockY();
        boolean isMaincraterGrownEnough = (volcano.mainCrater.longestFlowLength > 150 && mainCraterHeight > 100);

        if (isMaincraterGrownEnough && createSubCrater) {
            VolcanoCrater crater = volcano.mainCrater;

            Location location;
            List<Player> players = volcano.manager.getAffectedPlayers();
            Collections.shuffle(players);

            if (Math.random() < 0.7 || players.size() == 0) {
                location = TyphonUtils.getRandomBlockInRange(
                        crater.location.getBlock(),
                        (int) Math.max(volcano.mainCrater.longestFlowLength, 100),
                        (int) Math.max(volcano.mainCrater.longestFlowLength * 2, 200)
                ).getLocation();

                volcano.logger.log(VolcanoLogClass.AUTOSTART, "Volcano will now create a subcrater!");
            } else {
                Player target = players.get(0);
                double distance = volcano.mainCrater.getTwoDimensionalDistance(target.getLocation());

                double xDiff = target.getLocation().getX() - volcano.mainCrater.location.getX();
                double zDiff = target.getLocation().getZ() - volcano.mainCrater.location.getZ();

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

                double newDistance = Math.max(volcano.mainCrater.longestFlowLength + 50, distance);
                double newXDiff = newDistance * cos;
                double newZDiff = newDistance * sin;

                volcano.logger.log(VolcanoLogClass.AUTOSTART, "Volcano will now create a subcrater near-by a player "+target.getName());

                location = volcano.mainCrater.location.add(newXDiff, 0, newZDiff);

                location = TyphonUtils.getRandomBlockInRange(
                        location.getBlock(),
                        0,
                        (int) (volcano.mainCrater.getTwoDimensionalDistance(location) - volcano.mainCrater.longestFlowLength)
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
                } while(!volcano.subCraters.containsKey(name));

                VolcanoCrater newCrater = new VolcanoCrater(volcano, location, name);

                volcano.logger.log(VolcanoLogClass.AUTOSTART, "Volcano is now creating a new subcrater "+name);

                newCrater.start();
                Bukkit.getScheduler().runTaskLater(TyphonPlugin.plugin,
                        (Runnable) () -> {
                            newCrater.stop();
                        },
                        eruptionTimer
                );

                return newCrater;
            }

            return null;
        }

        return null;
    }

    public void updateStatus() {
        if (volcano.autoStart.canAutoStart) {
            volcano.logger.debug(VolcanoLogClass.AUTOSTART, "Volcano AutoStart interval Checking...");

            for (VolcanoCrater crater : volcano.manager.getCraters()) {
                VolcanoCraterStatus status = crater.status;
                double a = Math.random();


                switch (crater.status) {
                    case DORMANT:
                        if (a <= probability.dormant.increase) {
                            crater.status = crater.status.increase();
                        }
                        break;
                    case MINOR_ACTIVITY:
                        if (a <= probability.minor_activity.increase) {
                            crater.status = crater.status.increase();
                        } else if (a <= probability.minor_activity.increase + probability.minor_activity.decrease) {
                            crater.status = crater.status.decrease();
                        }
                        break;
                    case MAJOR_ACTIVITY:
                        if (a <= probability.major_activity.increase) {
                            crater.volcano.logger.log(VolcanoLogClass.AUTOSTART, "volcano starting due to increment from major_activity");
                            crater.status = crater.status.increase();
                            crater.start();
                            Bukkit.getScheduler().scheduleSyncDelayedTask(TyphonPlugin.plugin, () -> {
                                crater.volcano.logger.log(VolcanoLogClass.AUTOSTART, "Volcano ended eruption session. back to MAJOR_ACTIVITY");
                                crater.stop();
                                crater.status = crater.status.decrease();
                            }, volcano.autoStart.eruptionTimer);
                        } else if (a <= probability.major_activity.increase + probability.major_activity.decrease) {
                            crater.status = crater.status.decrease();
                        }
                        break;
                    default:
                        break;
                }

                if (crater.status != status) {
                    volcano.logger.debug(VolcanoLogClass.AUTOSTART, "Volcano has changed status to" + crater.status.toString());
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
        this.createSubCrater = (boolean) configData.get("createSubCrater");
        this.pourLavaStart = (boolean) configData.get("pourLavaStart");
        this.eruptionTimer = (long) configData.get("eruptionTimer");
        this.statusCheckInterval = (long) configData.get("statusCheckInterval");
        this.probability.importConfig((JSONObject) configData.get("probability"));
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        configData.put("canAutoStart", this.canAutoStart);
        configData.put("pourLavaStart", this.pourLavaStart);
        configData.put("createSubCrater", this.createSubCrater);
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
