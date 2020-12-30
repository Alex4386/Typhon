package me.alex4386.plugin.typhon.volcano.lavaflow;

import me.alex4386.plugin.typhon.TyphonNMSUtils;
import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.crater.VolcanoCrater;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.plugin.PluginManager;
import org.json.simple.JSONObject;

import java.util.*;

public class VolcanoLavaFlow implements Listener {
    public VolcanoCrater crater = null;
    public Volcano volcano;

    public List<Chunk> lavaFlowChunks = new ArrayList<>();
    public Map<Block, VolcanoLavaCoolData> lavaCoolHashMap = new HashMap<>();

    private int lavaFlowScheduleId = -1;
    private int lavaCoolScheduleId = -1;
    public VolcanoLavaFlowSettings settings = new VolcanoLavaFlowSettings();

    public boolean registeredEvent = false;

    // core methods
    public VolcanoLavaFlow(VolcanoCrater crater) {
        this.crater = crater;
        this.volcano = crater.getVolcano();
        this.registerEvent();
    }

    public VolcanoLavaFlow(Volcano volcano) {
        this.crater = null;
        this.volcano = volcano;
        this.registerEvent();
    }

    public Volcano getVolcano() {
        if (crater == null) {
            return volcano;
        } else {
            return crater.getVolcano();
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

    // core scheduler methods
    public void registerTask() {
        if (lavaFlowScheduleId == -1) {
            lavaFlowScheduleId = TyphonPlugin.plugin.getServer().getScheduler().scheduleSyncRepeatingTask(TyphonPlugin.plugin, () -> {
                if (settings.enabled && settings.flowing) autoFlowLava();
            },0L,(long)getVolcano().updateRate);
        }
        if (lavaCoolScheduleId == -1) {
            lavaCoolScheduleId = TyphonPlugin.plugin.getServer().getScheduler().scheduleSyncRepeatingTask(TyphonPlugin.plugin, () -> {
                if (settings.enabled) runCooldownTick();
            },0L,(long)getVolcano().updateRate);
        }
    }

    public void unregisterTask() {
        if (lavaFlowScheduleId != -1) {
            TyphonPlugin.plugin.getServer().getScheduler().cancelTask(lavaFlowScheduleId);
            lavaFlowScheduleId = -1;
        }
        if (lavaCoolScheduleId != -1) {
            TyphonPlugin.plugin.getServer().getScheduler().cancelTask(lavaCoolScheduleId);
            lavaCoolScheduleId = -1;
        }
    }

    public void initialize() {
        this.registerEvent();
        this.registerTask();
    }

    public void shutdown() {
        this.unregisterEvent();
        this.unregisterTask();
    }

    public int getTickFactor() {
        int tickFactor = 20 / ((int) getVolcano().updateRate);

        return tickFactor;
    }

    @EventHandler
    public void onBlockFromTo(BlockFromToEvent event) {

        // new lava flow,
        /*
            If you set the count of the particle to 0, the "offset" should be treated as motion parameters for the particle.
            The particle system in Minecraft is sometimes a bit weird, but that's just how it works.

            (Edit: And yes, the "extra" argument will determine the speed of the particle)

            https://www.spigotmc.org/threads/spawn-a-campfire-particle.403246/
        */


        Block block = event.getBlock();
        Block toBlock = event.getToBlock();

        // this is lava. flow it.
        if (lavaCoolHashMap.get(block) != null) {

            if (this.crater != null) {
                double distance = TyphonUtils.getTwoDimensionalDistance(crater.location, block.getLocation());

                if (distance > crater.longestFlowLength) {
                    crater.longestFlowLength = distance;
                }

                crater.record.addEjectaVolume(1);

                // force load chunk.
                if (!crater.location.getChunk().isLoaded()) crater.location.getChunk().load();
            }

            if (!lavaFlowChunks.contains(toBlock.getChunk())) {
                lavaFlowChunks.add(toBlock.getLocation().getChunk());
                toBlock.getLocation().getChunk().setForceLoaded(true);
            }

            // load toBlock chunk
            Chunk toBlockChunk = toBlock.getLocation().getChunk();
            if (!toBlockChunk.isLoaded()) {
                toBlockChunk.load();
            }

            List<Block> nearByBlocks = TyphonUtils.getNearByBlocks(block);
            for (Block nearByBlock : nearByBlocks) {
                if (nearByBlock.getType().isAir()) {
                    continue;
                }

                if (Arrays.asList(VolcanoLavaFlowExplode.water).contains(nearByBlock.getType())) {
                    TyphonNMSUtils.createParticle(Particle.CLOUD, nearByBlock.getLocation(), 100);
                    //nearByBlock.getWorld().playSound(nearByBlock.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 100f, 0f);
                } else {
                    getVolcano().metamorphism.metamorphoseBlock(nearByBlock);
                }
            }

            this.registerLavaCoolData(toBlock);
        }
    }

    public void registerLavaCoolData(Block block) {
        block.setType(Material.LAVA);
        lavaCoolHashMap.put(
                block,
                new VolcanoLavaCoolData(
                        block,
                        getVolcano().composition.getExtrusiveRockMaterial(),
                        settings.flowed * this.getTickFactor()
                ));
        if (crater != null) {
            crater.record.addEjectaVolume(1);
        }
    }

    private long nextFlowTime = 0;

    public void flowLava() {
        Block whereToFlow = crater.requestFlow();
        flowLava(whereToFlow);
    }

    public void flowLava(Block whereToFlow) {
        whereToFlow.setType(Material.LAVA);
        registerLavaCoolData(whereToFlow);
    }

    public void autoFlowLava() {
        long timeNow = System.currentTimeMillis();
        if (System.currentTimeMillis() >= nextFlowTime) {
            flowLava();
            nextFlowTime = timeNow + settings.delayFlowed * (1000 * (1 / getTickFactor()));
        }
    }

    public void runCooldownTick() {
        Iterator<Map.Entry<Block, VolcanoLavaCoolData>> iterator = lavaCoolHashMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Block, VolcanoLavaCoolData> entry = iterator.next();
            VolcanoLavaCoolData coolData = entry.getValue();
            coolData.tickPass();

            if (coolData.tickPassed()) {
                if (lavaCoolHashMap.keySet().contains(coolData.block)) {
                    iterator.remove();
                }
                coolData.coolDown();
            }
        }
    }

    public void cooldownAll() {
        for (Block block : lavaCoolHashMap.keySet()) {
            block.setType(
                    getVolcano().composition.getExtrusiveRockMaterial()
            );
        }

        lavaCoolHashMap.clear();
    }

    public void importConfig(JSONObject configData) {
        this.settings.importConfig(configData);
    }

    public JSONObject exportConfig() {
        return this.settings.exportConfig();
    }
}

class VolcanoLavaFlowDefaultSettings {
    public static boolean enabled = true;

    public static int flowed = 6;
    public static int delayFlowed = 3;

    public static void importConfig(JSONObject configData) {
        VolcanoLavaFlowSettings settings = new VolcanoLavaFlowSettings();
        settings.importConfig(configData);

        enabled = settings.enabled;

        flowed = settings.flowed;
        delayFlowed = settings.delayFlowed;
    }
}


interface VolcanoLavaFlowExplode {
    public static Material[] water = { Material.WATER, Material.LEGACY_STATIONARY_WATER, Material.SNOW, Material.SNOW_BLOCK, Material.KELP, Material.KELP_PLANT };
}

