package me.alex4386.plugin.typhon.volcano.lavaflow;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.plugin.PluginManager;
import org.json.simple.JSONObject;

import java.util.*;


public class VolcanoLavaFlow implements Listener {
    public VolcanoVent vent = null;

    public List<Chunk> lavaFlowChunks = new ArrayList<>();
    public Map<Block, VolcanoLavaCoolData> lavaCoolHashMap = new HashMap<>();
    public Map<Block, VolcanoLavaCoolData> cachedLavaCoolHashMap = new HashMap<>();
    public Map<Block, VolcanoPillowLavaData> pillowLavaMap = new HashMap<>();
    public Map<Block, VolcanoPillowLavaData> cachedPillowLavaMap = new HashMap<>();

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
            BlockFormEvent.getHandlerList().unregisterAll(this);
            PlayerBucketFillEvent.getHandlerList().unregisterAll(this);
            registeredEvent = false;
        }
    }

    // core scheduler methods
    public void registerTask() {
        if (lavaFlowScheduleId == -1) {
            this.vent.volcano.logger.log(
                    VolcanoLogClass.LAVA_FLOW,
                    "Intializing lava flow scheduler of VolcanoLavaFlow for vent "
                            + vent.getName());
            lavaFlowScheduleId =
                    TyphonPlugin.plugin
                            .getServer()
                            .getScheduler()
                            .scheduleSyncRepeatingTask(
                                    TyphonPlugin.plugin,
                                    () -> {
                                        if (settings.flowing) autoFlowLava();
                                    },
                                    0L,
                                    (long) getVolcano().updateRate);
        }
        if (lavaCoolScheduleId == -1) {
            this.vent.volcano.logger.log(
                    VolcanoLogClass.LAVA_FLOW,
                    "Intializing lava cooldown scheduler of VolcanoLavaFlow for vent "
                            + vent.getName());
            lavaCoolScheduleId =
                    TyphonPlugin.plugin
                            .getServer()
                            .getScheduler()
                            .scheduleSyncRepeatingTask(
                                    TyphonPlugin.plugin,
                                    () -> {
                                        runCooldownTick();
                                        runPillowLavaTick();
                                    },
                                    0L,
                                    (long) getVolcano().updateRate);
        }
    }

    public void unregisterTask() {
        if (lavaFlowScheduleId != -1) {
            this.vent.volcano.logger.log(
                    VolcanoLogClass.LAVA_FLOW,
                    "Shutting down lava flow scheduler of VolcanoLavaFlow for vent "
                            + vent.getName());
            TyphonPlugin.plugin.getServer().getScheduler().cancelTask(lavaFlowScheduleId);
            lavaFlowScheduleId = -1;
        }
        if (lavaCoolScheduleId != -1) {
            this.vent.volcano.logger.log(
                    VolcanoLogClass.LAVA_FLOW,
                    "Shutting down lava cooldown scheduler of VolcanoLavaFlow for vent "
                            + vent.getName());
            TyphonPlugin.plugin.getServer().getScheduler().cancelTask(lavaCoolScheduleId);
            lavaCoolScheduleId = -1;
        }
    }

    public void initialize() {
        this.vent.volcano.logger.log(
                VolcanoLogClass.LAVA_FLOW,
                "Intializing VolcanoLavaFlow for vent " + vent.getName());

        this.registerEvent();
        this.registerTask();
    }

    public void shutdown() {
        this.vent.volcano.logger.log(
                VolcanoLogClass.LAVA_FLOW,
                "Shutting down VolcanoLavaFlow for vent " + vent.getName());

        this.unregisterEvent();
        this.unregisterTask();
    }

    public double getTickFactor() {
        return this.getVolcano().getTickFactor();
    }

    @EventHandler
    public void lavaFlowPickupEvent(PlayerBucketFillEvent event) {
        Material bucket = event.getBucket();
        Block clickedBlock = event.getBlockClicked();
        Block targetBlock = clickedBlock.getRelative(event.getBlockFace());
        Location loc = targetBlock.getLocation();

        if (targetBlock.getType() == Material.LAVA) {
            if (lavaCoolHashMap.get(targetBlock) != null
                    || this.getVolcano().manager.isInAnyLavaFlowArea(loc)) {
                TyphonUtils.createRisingSteam(loc, 1, 5);

                event.getPlayer()
                        .sendMessage(
                                ChatColor.RED
                                        + "Volcano is erupting and you can't stop lava! Run!");

                event.setCancelled(true);
                if (event.getPlayer().getInventory().getItemInMainHand().getType() == bucket) {
                    event.getPlayer()
                            .getInventory()
                            .getItemInMainHand()
                            .setType(Material.LAVA_BUCKET);
                }
            }
        }
    }

    @EventHandler
    public void lavaCollisionDetector(BlockFormEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.COBBLESTONE || block.getType() == Material.STONE) {
            VolcanoLavaCoolData data = lavaCoolHashMap.get(block);
            if (data == null) data = cachedLavaCoolHashMap.get(block);
            if (data != null) {
                cachedPillowLavaMap.put(block, new VolcanoPillowLavaData(data.flowedFromVent, data.source, data.fromBlock, data.runExtensionCount));
                block.setType(VolcanoComposition.getExtrusiveRock(settings.silicateLevel));
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
        BlockFace face = event.getFace();

        VolcanoLavaCoolData data = lavaCoolHashMap.get(block);
        if (data == null) cachedLavaCoolHashMap.get(block);

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
            // for realistic lava flows
            if (face != BlockFace.DOWN) {
                if (Math.random() < 0.2 * this.vent.lavaFlow.settings.silicateLevel) {
                    event.setCancelled(true);
                    toBlock.setType(Material.AIR);
                    return;
                }
            }

            if (this.vent != null && !data.isBomb && data.source != null) {
                double distance;
                distance =
                        TyphonUtils.getTwoDimensionalDistance(
                                data.source.getLocation(), block.getLocation());

                if (distance > vent.longestFlowLength) {
                    vent.longestFlowLength = distance;
                    vent.getVolcano().trySave(false);
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

            boolean isFlowBlocked = !underToBlock.getType().isAir();
            List<Block> nearByBlocks = TyphonUtils.getNearByBlocks(toBlock);

            for (Block nearByBlock : nearByBlocks) {
                if (nearByBlock.getType().isAir()) {
                    isFlowBlocked = false;
                    continue;
                }

                if (nearByBlock.getY() == toBlock.getY()) {
                    if (Math.abs(nearByBlock.getX() - toBlock.getX()) == 0
                            ^ Math.abs(nearByBlock.getZ() - toBlock.getZ()) == 0) {
                        if (nearByBlock.getType().isAir()) {
                            isFlowBlocked = false;
                        }
                    }
                }

                if (TyphonUtils.containsWater(nearByBlock)) {
                    TyphonUtils.createRisingSteam(nearByBlock.getLocation(), 1, 2);
                    nearByBlock
                            .getWorld()
                            .playSound(
                                    nearByBlock.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 1f, 0f);

                    // VolcanoBombListener.lavaSplashExplosions.put(block, vent);
                    // nearByBlock.getWorld().createExplosion(block.getLocation(), 4f, false);
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

            if (this.vent != null && isFlowBlocked) {
                VolcanoLavaCoolData coolData = this.vent.lavaFlow.lavaCoolHashMap.get(toBlock);
                if (coolData != null) {
                    coolData.forceCoolDown();

                    // rush up lava
                    Block flowUp = toBlock.getRelative(BlockFace.UP);
                    if (flowUp.getType().isAir()) {
                        this.registerLavaCoolData(data.source, toBlock, flowUp, data.isBomb);
                    }
                }
            }
        }
    }

    private void registerLavaCoolData(Block block, boolean isBomb) {
        this.registerLavaCoolData(block, block, block, isBomb, -1);
    }

    private void registerLavaCoolData(Block block, boolean isBomb, int extension) {
        this.registerLavaCoolData(block, block, block, isBomb, extension);
    }

    private void registerLavaCoolData(Block source, Block fromBlock, Block block, boolean isBomb) {
        this.registerLavaCoolData(source, fromBlock, block, isBomb, -1);
    }

    public void registerLavaCoolData(
            Block source, Block fromBlock, Block block, boolean isBomb, int extension) {
        
        boolean isUnderWater = block.getType() == Material.WATER;
        
        if (!isUnderWater) {
            boolean isSurroundedByWater = false;
            BlockFace[] flowableFaces = {
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.UP
            };
    
            for (BlockFace face: flowableFaces) {
                Block flowBlock = block.getRelative(face);
                if (flowBlock.getType() == Material.WATER) {
                    isSurroundedByWater = true;
                    break;
                }
            }

            if (isSurroundedByWater) isUnderWater = true;
        }

        this.registerLavaCoolData(source, fromBlock, block, isBomb, extension, isUnderWater);
    }

    private void registerLavaCoolData(
            Block source,
            Block fromBlock,
            Block block,
            boolean isBomb,
            int extension,
            boolean isUnderWater) {
        Material targetMaterial =
                isBomb && !isUnderWater
                        ? VolcanoComposition.getBombRock(this.settings.silicateLevel)
                        : VolcanoComposition.getExtrusiveRock(this.settings.silicateLevel);

        if (!isUnderWater) {
            block.setType(Material.LAVA);

            if (extension < 0) {
                cachedLavaCoolHashMap.put(
                        block,
                        new VolcanoLavaCoolData(
                                source,
                                fromBlock,
                                block,
                                this.vent,
                                targetMaterial,
                                (int) (settings.flowed * this.getTickFactor()),
                                isBomb));
            } else {
                cachedLavaCoolHashMap.put(
                        block,
                        new VolcanoLavaCoolData(
                                source,
                                fromBlock,
                                block,
                                this.vent,
                                targetMaterial,
                                (int) (settings.flowed * this.getTickFactor()),
                                isBomb,
                                extension));
            }
        } else {
            VolcanoPillowLavaData lavaData = cachedPillowLavaMap.get(block);
            if (lavaData == null) {
                block.setType(Material.MAGMA_BLOCK);

                if (extension < 0) {
                    cachedPillowLavaMap.put(block, new VolcanoPillowLavaData(this.vent, source, fromBlock));
                } else {
                    cachedPillowLavaMap.put(
                            block, new VolcanoPillowLavaData(this.vent, source, fromBlock, extension));
                }
            }
        }

        if (vent != null) {
            vent.record.addEjectaVolume(1);
        }
    }

    private long nextFlowTime = 0;

    public void flowLava() {
        flowLava(1);
    }

    public void flowLava(int flowCount) {
        /*
        int craterBlocks = Math.max(vent.getVentBlocksScaffold().size(), 1);

        int boom = (int) (this.settings.flowed * this.vent.getVolcano().updateRate * 9);
        int flowCount = Math.max(1, craterBlocks / boom);
        */

        // this.vent.getVolcano().logger.log(VolcanoLogClass.LAVA_FLOW, "Triggering "+flowCount+"
        // blocks of lava flow at vent "+vent.getName()+"...");

        List<Block> whereToFlows = vent.requestFlows(flowCount);

        for (Block whereToFlow : whereToFlows) {
            VolcanoLavaCoolData coolData = lavaCoolHashMap.get(whereToFlow);
            if (coolData == null || coolData.tickPassed()) flowLava(whereToFlow);
        }
    }

    public void flowLava(Block whereToFlow) {
        World world = whereToFlow.getWorld();

        world.spawnParticle(Particle.SMOKE_LARGE, whereToFlow.getLocation(), 10);

        world.spawnParticle(Particle.LAVA, whereToFlow.getLocation(), 10);

        world.playSound(whereToFlow.getLocation(), Sound.BLOCK_LAVA_POP, 1f, 1f);

        registerLavaCoolData(whereToFlow, false);

        if (this.vent != null && this.vent.erupt != null) {
            this.vent.erupt.updateVentConfig();
        }
    }

    public void flowLavaFromBomb(Block bomb) {
        double zeroFocused = VolcanoMath.getZeroFocusedRandom();
        this.registerLavaCoolData(bomb, true, (int) (zeroFocused * 2));
    }


    public boolean extensionCapable(Location location) {
        if (this.vent != null) {
            if (this.vent.getType() == VolcanoVentType.CRATER) {
                if (this.vent.isInVent(location)) {
                    return false;
                }
            }
        }
        return true;
    }

    public void runPillowLavaTick() {
        Iterator<Map.Entry<Block, VolcanoPillowLavaData>> iterator =
                pillowLavaMap.entrySet().iterator();
        List<Block> flowedBlocks = new ArrayList<Block>();

        try {
            while (iterator.hasNext()) {
                Map.Entry<Block, VolcanoPillowLavaData> data = iterator.next();

                Block block = data.getKey();
                VolcanoPillowLavaData lavaData = data.getValue();

                block.setType(VolcanoComposition.getExtrusiveRock(this.settings.silicateLevel));

                flowedBlocks.add(block);
                Block underBlock = block.getRelative(BlockFace.DOWN);
                if (underBlock.getType() == Material.MAGMA_BLOCK) {
                    underBlock.setType(VolcanoComposition.getExtrusiveRock(this.settings.silicateLevel));
                } else if (underBlock.getType().isAir() || TyphonUtils.containsLiquidWater(underBlock)) {
                    if (pillowLavaMap.get(underBlock) == null) {
                        registerLavaCoolData(
                                lavaData.sourceBlock,
                                lavaData.fromBlock,
                                underBlock,
                                false);
                        continue;
                    }
                    underBlock.setType(VolcanoComposition.getExtrusiveRock(this.settings.silicateLevel));
                    flowedBlocks.add(underBlock);
                }

                // flow on surface
                int extension = lavaData.extensionCount;
                int level = lavaData.fluidLevel;
                level -= Math.min((int) (Math.random() * 4) + 1, 1);

                if (!this.extensionCapable(block.getLocation())) {
                    extension = 0;
                }

                if (level <= 0) {
                    extension--;
                    if (extension < 0) continue;

                    level = 8;
                }

                BlockFace[] flowableFaces = {
                    BlockFace.NORTH,
                    BlockFace.WEST,
                    BlockFace.EAST,
                    BlockFace.SOUTH,
                };

                for (BlockFace flowableFace : flowableFaces) {
                    Block flowTarget = block.getRelative(flowableFace);
                    if (level <= 4) {
                        if (Math.random() > 0.2 * level) {
                            continue;
                        }
                    }

                    if (flowTarget.getType().isAir()
                            || TyphonUtils.containsLiquidWater(flowTarget)) {

                        TyphonUtils.removeSeaGrass(flowTarget);
                        VolcanoPillowLavaData pillowData = pillowLavaMap.get(flowTarget);
                        if (pillowData == null) {
                            registerLavaCoolData(
                                lavaData.sourceBlock,
                                lavaData.fromBlock,
                                flowTarget,
                                false,
                                extension);

                            pillowData = cachedPillowLavaMap.get(flowTarget);
                            if (pillowData != null) {
                                pillowData.fluidLevel = level;
                            }
                        }
                    }
                }
            }
        } catch (ConcurrentModificationException e) {
            e.printStackTrace();
        }

        for (Block flowedBlock : flowedBlocks) {
            pillowLavaMap.remove(flowedBlock);
        }

        Map<Block, VolcanoPillowLavaData> cache = cachedPillowLavaMap;
        Iterator<Map.Entry<Block, VolcanoPillowLavaData>> iteratorCache = cache.entrySet().iterator();

        while (iteratorCache.hasNext()) {
            Map.Entry<Block, VolcanoPillowLavaData> entry = iteratorCache.next();
            Block block = entry.getKey();
            VolcanoPillowLavaData data = entry.getValue();

            this.pillowLavaMap.put(block, data);
            iteratorCache.remove();
        }
    }

    private void autoFlowLava() {
        long timeNow = System.currentTimeMillis();
        if (timeNow >= nextFlowTime) {
            double missedFlowTime = timeNow - nextFlowTime;
            double flowTick = settings.delayFlowed * (1000 * (1 / getTickFactor()));

            int missedFlows = (int) (missedFlowTime / flowTick) + 1;
            int requiredFlows = (int) ((float) (1 + missedFlows) * ((Math.random() * 1.5) + 1));
            int actualFlows =
                    (int) ((double) requiredFlows * this.vent.erupt.getStyle().lavaMultiplier);

            int fittedActualFlows =
                    Math.min(actualFlows, Math.max(vent.getVentBlocksScaffold().size() / 3, 10));

            flowLava(fittedActualFlows);
            nextFlowTime = timeNow + (int) (settings.delayFlowed * (1000 * (1 / getTickFactor())));
        }
    }

    private void runCooldownTick() {
        Iterator<Map.Entry<Block, VolcanoLavaCoolData>> iterator =
                lavaCoolHashMap.entrySet().iterator();
        List<Block> removeTargets = new ArrayList<Block>();

        try {
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
                                this.registerLavaCoolData(
                                        coolData.source,
                                        coolData.fromBlock,
                                        block,
                                        coolData.isBomb);
                            }
                        }
                    }
                }

                coolData.tickPass();
            }
        } catch (ConcurrentModificationException e) {
            e.printStackTrace();
        }

        for (Block block : removeTargets) {
            lavaCoolHashMap.remove(block);
        }

        Map<Block, VolcanoLavaCoolData> cache = cachedLavaCoolHashMap;
        Iterator<Map.Entry<Block, VolcanoLavaCoolData>> iteratorCache = cache.entrySet().iterator();

        while (iteratorCache.hasNext()) {
            Map.Entry<Block, VolcanoLavaCoolData> entry = iteratorCache.next();

            Block block = entry.getKey();
            VolcanoLavaCoolData data = entry.getValue();

            lavaCoolHashMap.put(block, data);
            iteratorCache.remove();
        }
    }

    public void cooldownAll() {
        for (Map.Entry<Block, VolcanoLavaCoolData> entry : lavaCoolHashMap.entrySet()) {
            Block block = entry.getKey();
            VolcanoLavaCoolData coolData = entry.getValue();

            block.setType(coolData.material);
        }
        lavaCoolHashMap.clear();

        for (Map.Entry<Block, VolcanoLavaCoolData> entry : cachedLavaCoolHashMap.entrySet()) {
            Block block = entry.getKey();
            VolcanoLavaCoolData coolData = entry.getValue();

            block.setType(coolData.material);
        }
        cachedLavaCoolHashMap.clear();

        for (Map.Entry<Block, VolcanoPillowLavaData> entry : pillowLavaMap.entrySet()) {
            Block block = entry.getKey();
            block.setType(VolcanoComposition.getExtrusiveRock(settings.silicateLevel));
        }
        pillowLavaMap.clear();

        for (Map.Entry<Block, VolcanoPillowLavaData> entry : cachedPillowLavaMap.entrySet()) {
            Block block = entry.getKey();
            block.setType(VolcanoComposition.getExtrusiveRock(settings.silicateLevel));
        }
        cachedPillowLavaMap.clear();
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

    public static int flowed = 10;
    public static int delayFlowed = 7;

    public static void importConfig(JSONObject configData) {
        VolcanoLavaFlowSettings settings = new VolcanoLavaFlowSettings();
        settings.importConfig(configData);

        flowed = settings.flowed;
        delayFlowed = settings.delayFlowed;
    }
}

class VolcanoPillowLavaData {
    int extensionCount;
    VolcanoVent vent;

    Block sourceBlock;
    Block fromBlock;

    int fluidLevel = 8;

    VolcanoPillowLavaData(VolcanoVent vent, Block sourceBlock) {
        this(vent, sourceBlock, sourceBlock);
    }

    VolcanoPillowLavaData(VolcanoVent vent, Block sourceBlock, Block fromBlock) {
        this(
                vent,
                sourceBlock,
                fromBlock,
                VolcanoLavaCoolData.calculateExtensionCount(vent.lavaFlow.settings.silicateLevel));
    }

    VolcanoPillowLavaData(VolcanoVent vent, Block sourceBlock, Block fromBlock, int extensionCount) {
        this.vent = vent;
        this.sourceBlock = sourceBlock;
        this.fromBlock = fromBlock;

        this.extensionCount = extensionCount;
    }
}
