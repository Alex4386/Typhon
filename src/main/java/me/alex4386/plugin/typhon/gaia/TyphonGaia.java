package me.alex4386.plugin.typhon.gaia;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.VolcanoManager;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoEruptStyle;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoNamer;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentStatus;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.json.simple.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TyphonGaia {

    //  if we were to imagine a hypothetical scenario in which all active volcanoes were evenly spaced,
    //  the average distance between them would be approximately 40 to 50 kilometers (25 to 31 miles) apart.
    public static int bubbleRadius = 4000;
    public static List<World> enabledWorlds = new ArrayList<>();


    public static int scheduleId = -1;
    public static long interval = 20 * 60 * 60;

    public static void initialize() {
        registerTask();
    }

    public static void shutdown() {
        unregisterTask();
    }

    public static void registerTask() {
        if (scheduleId == -1) {
            scheduleId =
                    TyphonPlugin.plugin
                            .getServer()
                            .getScheduler()
                            .scheduleSyncRepeatingTask(
                                    TyphonPlugin.plugin,
                                    () -> {
                                        runVolcanoSpawn();
                                    },
                                    0L,
                                    interval
                            );
        }
    }

    public static void unregisterTask() {
        if (scheduleId >= 0) {
            TyphonPlugin.plugin.getServer().getScheduler().cancelTask(scheduleId);
            scheduleId = -1;
        }
    }


    public static void enableWorld(World world) {
        if (!enabledWorlds.contains(world)) {
            enabledWorlds.add(world);
        }

        saveConfig();
    }

    public static void disableWorld(World world) {
        if (enabledWorlds.contains(world)) {
            enabledWorlds.remove(world);
        }
        
        saveConfig();
    }

    public static void loadConfig(FileConfiguration config) {
        TyphonPlugin.logger.log(VolcanoLogClass.GAIA, "Loading gaia config....");
        List<String> worldRawStrings = config.getStringList("gaia.worlds");

        enabledWorlds.clear();
        for (String worldString : worldRawStrings) {
            World world = Bukkit.getWorld(worldString);
            if (world != null) {
                enabledWorlds.add(world);
            }
        }

        TyphonPlugin.logger.log(VolcanoLogClass.GAIA, "Loaded Gaia enabled worlds! "+enabledWorlds.size()+" worlds loaded.");
    }

    public static void saveConfig() {
        TyphonPlugin.logger.log(VolcanoLogClass.GAIA, "Saving gaia config....");

        List<String> worldRawStrings = new ArrayList<>();
        for (World world : enabledWorlds) {
            worldRawStrings.add(world.getName());
        }

        TyphonPlugin.plugin.getConfig().set("gaia.worlds", worldRawStrings);
        TyphonPlugin.plugin.saveConfig();
    }

    public static long getAdequateVolcanoCount(World world) {
        long bubbleRectangle = bubbleRadius * bubbleRadius;
        long area = TyphonUtils.getWorldArea(world);

        return (area / bubbleRectangle) + 1;
    }

    public static boolean isObstructingOtherVolcanosBubble(Location location) {
        for (Volcano volcano : TyphonPlugin.listVolcanoes.values()) {
            if (volcano.location.getWorld().equals(location.getWorld())) {
                VolcanoVent nearestVent = volcano.manager.getNearestVent(location);
                if (nearestVent != null) {
                    if (TyphonUtils.getTwoDimensionalDistance(nearestVent.getNearestCoreBlock(location).getLocation(), location) < bubbleRadius) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    public static int getRandomViewableDistance() {
        // utilize minecraft scaling - realworld / 10
        // 1.5km ~ 4.5km
        int baseRadius = 150;
        baseRadius += (int) (Math.random() * 150);

        // chunk * 7
        int viewableDistance = 16 * (4 + (int) (Math.random() * 3));

        return baseRadius + viewableDistance;
    }

    public static GaiaChunkCoordIntPair parseRegionFilename(String filename) {
        String extension = filename.substring(filename.lastIndexOf(".") + 1);

        if (extension.equalsIgnoreCase("mca")) {
            String name = filename.substring(0, filename.lastIndexOf("."));

            String[] parts = name.split("\\.");
            if (parts.length != 3) return null;
            if (!parts[0].equals("r")) return null;

            String xCoordRaw = parts[1];
            String zCoordRaw = parts[2];

            int xCoord = Integer.parseInt(xCoordRaw);
            int zCoord = Integer.parseInt(zCoordRaw);

            return new GaiaChunkCoordIntPair(xCoord, zCoord);
        }

        return null;
    }

    public static void runVolcanoSpawn() {
        for (World world : enabledWorlds) {
            int count = VolcanoManager.getActiveVolcanoesOnWorld(world).size();
            long adeqCount = getAdequateVolcanoCount(world);
            
            int diff = (int) (adeqCount - count);
            if (diff <= 0) continue;
            
            for (int i = 0; i < diff; i++) {
                Volcano volcano = spawnRandomVolcano(world);
                if (volcano == null) continue;
                
                volcano.autoStart.startVentWithGracePeriod(volcano.mainVent);
            }
        }
    }

    public static Volcano spawnRandomVolcano(World world) {
        TyphonPlugin.logger.log(VolcanoLogClass.GAIA, "Gaia is starting to create a new volcano in world "+world.getName()+"...");
        Volcano volcano = null;

        for (int i = 0; i < 999; i++) {
            volcano = trySpawnRandomVolcano(world);
            if (volcano != null) break;
        }

        if (volcano == null) return null;

        TyphonPlugin.logger.log(VolcanoLogClass.GAIA, "Gaia has created a new volcano: "+volcano.name);
        return volcano;
    }

    public static Volcano spawnVolcano(Location target) {
        Block baseBlock = TyphonUtils.getHighestRocklikes(target);

        String name = VolcanoNamer.generate();

        // TODO: Streamline this to static Volcano.create()
        File volcanoDir = new File(TyphonPlugin.volcanoDir, name);
        try {
            Volcano volcano = new Volcano(volcanoDir.toPath(), baseBlock.getLocation());

            // vent type
            VolcanoVentType type = VolcanoVentType.CRATER;
            volcano.mainVent.fissureAngle = Math.random() * 2 * Math.PI;

            VolcanoEruptStyle eruptStyle = VolcanoEruptStyle.HAWAIIAN;

            // volcano lava silicate content
            double silicateContent = (Math.random() * 0.25) + 0.35;

            if (silicateContent >= 0.55) {
                eruptStyle = VolcanoEruptStyle.VULCANIAN;
            } else {
                if (Math.random() < 0.2) eruptStyle = VolcanoEruptStyle.STROMBOLIAN;
            }

            volcano.mainVent.setType(type);
            volcano.mainVent.erupt.setStyle(eruptStyle);
            volcano.mainVent.erupt.autoConfig();

            volcano.mainVent.setStatus(VolcanoVentStatus.MAJOR_ACTIVITY);

            volcano.trySave(true);
            TyphonPlugin.listVolcanoes.put(name, volcano);

            return volcano;
        } catch (Exception e) { return null; }
    }

    private static Volcano trySpawnRandomVolcano(World world) {
        List<File> regionFiles = TyphonUtils.getAllChunkFiles(world);
        if (regionFiles.size() == 0) return null;

        Collections.shuffle(regionFiles);
        GaiaChunkCoordIntPair region = parseRegionFilename(regionFiles.get(0).getName());
        if (region == null) return null;

        Chunk chunk = world.getChunkAt(region.x, region.z);
        int distance = getRandomViewableDistance();

        Block chunkCenter = chunk.getBlock(8, 0, 8);
        Block target = TyphonUtils.getFairRandomBlockInRange(chunkCenter, distance, distance);

        if (isObstructingOtherVolcanosBubble(target.getLocation())) return null;
        return spawnVolcano(target.getLocation());
    }
}


class GaiaChunkCoordIntPair {
    public int x;
    public int z;

    public GaiaChunkCoordIntPair(int x, int z) {
        this.x = x;
        this.z = z;
    }
}

