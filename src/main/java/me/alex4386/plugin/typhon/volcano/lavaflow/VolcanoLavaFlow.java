package me.alex4386.plugin.typhon.volcano.lavaflow;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.bomb.VolcanoBombListener;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.plugin.PluginManager;
import org.json.simple.JSONObject;
import org.bukkit.util.Vector;

import java.util.*;

public class VolcanoLavaFlow implements Listener {
    public VolcanoVent vent = null;

    public List<Chunk> lavaFlowChunks = new ArrayList<>();
    public Map<Block, VolcanoLavaCoolData> lavaCoolHashMap = new HashMap<>();
    public Map<Block, VolcanoLavaCoolData> cachedLavaCoolHashMap = new HashMap<>();

    private int lavaFlowScheduleId = -1;
    private int lavaCoolScheduleId = -1;
    public VolcanoLavaFlowSettings settings = new VolcanoLavaFlowSettings();

    public boolean registeredEvent = false;

    // core methods
    public VolcanoLavaFlow(VolcanoVent vent) {
        this.vent = vent;
        this.registerEvent();
    }

    public Volcano getVolcano() {
        return vent.getVolcano();
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
            PlayerBucketFillEvent.getHandlerList().unregisterAll(this);
            registeredEvent = false;
        }
    }

    // core scheduler methods
    public void registerTask() {
        if (lavaFlowScheduleId == -1) {
            this.vent.volcano.logger.log(VolcanoLogClass.LAVA_FLOW, "Intializing lava flow scheduler of VolcanoLavaFlow for vent "+vent.getName());
            lavaFlowScheduleId = TyphonPlugin.plugin.getServer().getScheduler().scheduleSyncRepeatingTask(TyphonPlugin.plugin, () -> {
                if (settings.flowing) autoFlowLava();
            },0L,(long)getVolcano().updateRate);
        }
        if (lavaCoolScheduleId == -1) {
            this.vent.volcano.logger.log(VolcanoLogClass.LAVA_FLOW, "Intializing lava cooldown scheduler of VolcanoLavaFlow for vent "+vent.getName());
            lavaCoolScheduleId = TyphonPlugin.plugin.getServer().getScheduler().scheduleSyncRepeatingTask(TyphonPlugin.plugin, () -> {
                runCooldownTick();
            },0L,(long)getVolcano().updateRate);
        }
    }

    public void unregisterTask() {
        if (lavaFlowScheduleId != -1) {
            this.vent.volcano.logger.log(VolcanoLogClass.LAVA_FLOW, "Shutting down lava flow scheduler of VolcanoLavaFlow for vent "+vent.getName());
            TyphonPlugin.plugin.getServer().getScheduler().cancelTask(lavaFlowScheduleId);
            lavaFlowScheduleId = -1;
        }
        if (lavaCoolScheduleId != -1) {
            this.vent.volcano.logger.log(VolcanoLogClass.LAVA_FLOW, "Shutting down lava cooldown scheduler of VolcanoLavaFlow for vent "+vent.getName());
            TyphonPlugin.plugin.getServer().getScheduler().cancelTask(lavaCoolScheduleId);
            lavaCoolScheduleId = -1;
        }
    }

    public void initialize() {
        this.vent.volcano.logger.log(VolcanoLogClass.LAVA_FLOW, "Intializing VolcanoLavaFlow for vent "+vent.getName());

        this.registerEvent();
        this.registerTask();
    }

    public void shutdown() {
        this.vent.volcano.logger.log(VolcanoLogClass.LAVA_FLOW, "Shutting down VolcanoLavaFlow for vent "+vent.getName());

        this.unregisterEvent();
        this.unregisterTask();
    }

    public int getTickFactor() {
        int tickFactor = 20 / ((int) getVolcano().updateRate);

        return tickFactor;
    }

    @EventHandler
    public void lavaFlowPickupEvent(PlayerBucketFillEvent event) {
        Material bucket = event.getBucket();
        Block clickedBlock = event.getBlockClicked();
        Block targetBlock = clickedBlock.getRelative(event.getBlockFace());
        Location loc = targetBlock.getLocation();

        if (targetBlock.getType() == Material.LAVA) {
            if (lavaCoolHashMap.get(targetBlock) != null || this.getVolcano().manager.isInAnyLavaFlowArea(loc)) {
                TyphonUtils.createRisingSteam(loc, 1, 5);

                event.getPlayer().sendMessage(ChatColor.RED+"Volcano is erupting and you can't stop lava! Run!");

                event.setCancelled(true);
                if (event.getPlayer().getInventory().getItemInMainHand().getType() == bucket) {
                    event.getPlayer().getInventory().getItemInMainHand().setType(Material.LAVA_BUCKET);
                }
            }
        }
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

        VolcanoLavaCoolData data = lavaCoolHashMap.get(block);

        int level = -1;
        BlockData bd = block.getBlockData();
        if (bd instanceof Levelled) {
            level = ((Levelled) bd).getLevel();
        }

        if (toBlock.getType() == Material.LAVA) {
            if (data != null) {
                data.forceCoolDown();
                return;
            }
        }

        // this is lava. flow it.
        if (data != null) {
            if (this.vent != null && !data.isBomb && data.source != null) {
                double distance;
                distance = TyphonUtils.getTwoDimensionalDistance(data.source.getLocation(), block.getLocation());

                if (distance > vent.longestFlowLength) {
                    vent.longestFlowLength = distance;
                    vent.getVolcano().trySave();
                }

                vent.record.addEjectaVolume(1);

                // force load chunk.
                if (!vent.location.getChunk().isLoaded()) vent.location.getChunk().load();
            }

            Block underToBlock = toBlock.getRelative(BlockFace.DOWN);
            VolcanoLavaCoolData underData = lavaCoolHashMap.get(underToBlock);
            if (underData != null) {
                underData.forceCoolDown();
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
                    TyphonUtils.createRisingSteam(nearByBlock.getLocation(), 1, 2);
                    nearByBlock.getWorld().playSound(nearByBlock.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 1f, 0f);

                    VolcanoBombListener.lavaSplashExplosions.put(block, vent);
                    nearByBlock.getWorld().createExplosion(block.getLocation(), 4f, false);
                } else {
                    getVolcano().metamorphism.metamorphoseBlock(nearByBlock);
                }
            }

            int extensionCount = data.runExtensionCount;

            if (data.fromBlock != null) {
                Location flowVector = block.getLocation().subtract(data.fromBlock.getLocation());
                // this.flowedFromVent.volcano.logger.log(VolcanoLogClass.LAVA_FLOW,
                //      "FlowVector: " + TyphonUtils.blockLocationTostring(flowVector.getBlock()));
    
                if (flowVector.getBlockY() < 0) {
                    extensionCount = -1;
                }    
            }

            this.registerLavaCoolData(data.source, block, toBlock, data.isBomb, extensionCount);
        }
    }

    public void registerLavaCoolData(Block block, boolean isBomb) {
        this.registerLavaCoolData(block, block, block, isBomb, -1);
    }

    public void registerLavaCoolData(Block source, Block fromBlock, Block block) {
        registerLavaCoolData(source, fromBlock, block, false);
    }

    public void registerLavaCoolData(Block source, Block fromBlock, Block block, boolean isBomb) {
        this.registerLavaCoolData(source, fromBlock, block, isBomb, -1);
    }

    public void registerLavaCoolData(Block source, Block fromBlock, Block block, boolean isBomb, int extension) {
        block.setType(Material.LAVA);

        Material targetMaterial = isBomb ?
            VolcanoComposition.getBombRock(this.settings.silicateLevel) : 
            VolcanoComposition.getExtrusiveRock(this.settings.silicateLevel);
        
        if (extension < 0) {
            lavaCoolHashMap.put(
                block,
                new VolcanoLavaCoolData(
                        source,
                        fromBlock,
                        block,
                        this.vent,
                        targetMaterial,
                        settings.flowed * this.getTickFactor(),
                        isBomb
                ));
        } else {
            lavaCoolHashMap.put(
                block,
                new VolcanoLavaCoolData(
                        source,
                        fromBlock,
                        block,
                        this.vent,
                        targetMaterial,
                        settings.flowed * this.getTickFactor(),
                        isBomb,
                        extension
                ));
        }


        if (vent != null) {
            vent.record.addEjectaVolume(1);
        }
    }

    private long nextFlowTime = 0;

    public void flowLava() {
        int craterBlocks = Math.max(vent.cachedVentBlocks.size(), 1);
        
        int boom = (this.settings.flowed + 20) / this.getTickFactor();
        int flowCount = Math.max(1, craterBlocks / boom);

        for (int i = 0; i < flowCount; i++) {
            Block whereToFlow = vent.requestFlow();

            if (whereToFlow != null) {
                flowLava(whereToFlow);
            }
        }
    }

    public void flowLava(Block whereToFlow) {
        World world = whereToFlow.getWorld();

        world.spawnParticle(
                Particle.SMOKE_LARGE,
                whereToFlow.getLocation(),
                10
        );
        
        world.spawnParticle(
                Particle.LAVA,
                whereToFlow.getLocation(),
                10
        );

        world.playSound(
                whereToFlow.getLocation(),
                Sound.BLOCK_LAVA_POP,
                1f,
                1f
        );

        whereToFlow.setType(Material.LAVA);
        registerLavaCoolData(whereToFlow, false);

        if (this.vent != null && this.vent.erupt != null) {
            this.vent.erupt.updateVentConfig();
        }
    }

    public void autoFlowLava() {
        long timeNow = System.currentTimeMillis();
        if (timeNow >= nextFlowTime) {
            double missedFlows = timeNow - nextFlowTime;

            for (int i = 0; i < missedFlows; i++) flowLava();
            nextFlowTime = timeNow + settings.delayFlowed * (1000 * (1 / getTickFactor()));
        }
    }

    public void runCooldownTick() {
        Iterator<Map.Entry<Block, VolcanoLavaCoolData>> iterator = lavaCoolHashMap.entrySet().iterator();
        List<Block> removeTargets = new ArrayList<Block>();

        while (iterator.hasNext()) {
            Map.Entry<Block, VolcanoLavaCoolData> entry = iterator.next();
            VolcanoLavaCoolData coolData = entry.getValue();

            if (coolData.tickPassed()) {
                if (lavaCoolHashMap.keySet().contains(coolData.block)) {
                    removeTargets.add(entry.getKey());
                }

                if (!coolData.isBomb) {
                    Random random = new Random();
                    if (random.nextDouble() < 0.2f) {
                        int i = random.nextInt(5) + 1;

                        int x = i % 2 == 0 && i < 5 ? 1 : -1;
                        int z = i / 2 > 0 ? 1 : -1;

                        int y = i == 5 ? -1 : 0;

                        Block block = coolData.block.getRelative(x, y, z);
                        block.setType(coolData.material);

                        if (block.getType().isAir()) {
                            this.registerLavaCoolData(coolData.source, coolData.fromBlock, block, coolData.isBomb);
                        }
                    }
                }
            }

            coolData.tickPass();
        }

        for (Block block : removeTargets) {
            lavaCoolHashMap.remove(block);
        }

        Map<Block, VolcanoLavaCoolData> cache = cachedLavaCoolHashMap;
        Iterator<Map.Entry<Block,VolcanoLavaCoolData>> iteratorCache = cache.entrySet().iterator();

        while (iteratorCache.hasNext()) {
            Map.Entry<Block, VolcanoLavaCoolData> entry = iteratorCache.next();
            VolcanoLavaCoolData data = entry.getValue();

            this.registerLavaCoolData(data.source, data.fromBlock, data.block, false);
            iteratorCache.remove();
        }
    }

    public void cooldownAll() {
        for (Block block : lavaCoolHashMap.keySet()) {
            block.setType(
                VolcanoComposition.getExtrusiveRock(this.settings.silicateLevel)
            );
        }

        lavaCoolHashMap.clear();

        for (Block block : cachedLavaCoolHashMap.keySet()) {
            block.setType(
                VolcanoComposition.getExtrusiveRock(this.settings.silicateLevel)
            );
        }
        cachedLavaCoolHashMap.clear();

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

    public static int flowed = 3;
    public static int delayFlowed = 5;

    public static void importConfig(JSONObject configData) {
        VolcanoLavaFlowSettings settings = new VolcanoLavaFlowSettings();
        settings.importConfig(configData);

        flowed = settings.flowed;
        delayFlowed = settings.delayFlowed;
    }
}


interface VolcanoLavaFlowExplode {
    public static Material[] water = { Material.WATER, Material.LEGACY_STATIONARY_WATER, Material.SNOW, Material.SNOW_BLOCK, Material.POWDER_SNOW, Material.KELP, Material.KELP_PLANT };
}

