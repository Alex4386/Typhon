package me.alex4386.plugin.typhon.volcano.lavaflow;

import me.alex4386.plugin.typhon.*;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.bomb.VolcanoBomb;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoEruptStyle;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.plugin.PluginManager;
import org.json.simple.JSONObject;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;


public class VolcanoLavaFlow implements Listener {
    public VolcanoVent vent = null;

    public static Set<VolcanoVent> flowingVents = new HashSet<>();


    public Map<Chunk, Map<Block, VolcanoLavaCoolData>> lavaCools = new HashMap<>();
    public Map<Chunk, Map<Block, VolcanoLavaCoolData>> cachedCools = new HashMap<>();
    public Map<Block, VolcanoPillowLavaData> pillowLavaMap = new HashMap<>();
    public Map<Block, VolcanoPillowLavaData> cachedPillowLavaMap = new HashMap<>();

    public Set<Block> terminalBlocks = new HashSet<>();
    public Set<Block> normalFlowEndBlocks = new HashSet<>();
    public Set<Block> pillowFlowEndBlocks = new HashSet<>();
    public Set<Block> underfillTargets = new HashSet<>();

    private int lavaFlowScheduleId = -1;
    private int lavaCoolScheduleId = -1;
    private int lavaInfluxScheduleId = -1;
    private int queueScheduleId = -1;
    private int underfillCleanupScheduleId = -1;
    public VolcanoLavaFlowSettings settings = new VolcanoLavaFlowSettings();

    public boolean registeredEvent = false;

    private int highestY = Integer.MIN_VALUE;

    private double hawaiianBaseY = Double.NEGATIVE_INFINITY;


    public long lastQueueUpdatesPerSecond = 0;
    public long lastQueueUpdatedPerSecondAt = 0;

    public long lastQueueUpdates = 0;

    // Lava flow rate tracking (flows per second from autoFlowLava)
    private long flowedBlocksSinceRateCalc = 0;
    private long lastFlowRateCalcTime = 0;
    private long flowedBlocksPerSecond = 0;

    // Plumbing rate tracking (lava diverted to extension per second)
    private long plumbedBlocksSinceRateCalc = 0;
    private volatile long plumbedBlocksPerSecond = 0;

    // Successful plumbing tracking (how many extendLava() calls actually flowed)
    private long successfulPlumbsSinceRateCalc = 0;
    private volatile long successfulPlumbsPerSecond = 0;

    // Column index for flow end block staleness detection (packed x,z -> block)
    private final Map<Long, Block> flowEndColumnIndex = new HashMap<>();

    // Chunk-level plumbing dedup: only one active plumbing target per chunk (chunkKey -> block)
    private final Map<Long, Block> plumbingChunkTargets = new HashMap<>();

    // Cached counts — updated on main thread, safe to read from web thread
    private volatile int cachedActiveLavaCount = 0;
    private volatile int cachedTerminalBlockCount = 0;
    private volatile int cachedUnderfillLavaCount = 0;

    // Disable rootless cone for now.
    private double rootlessConeProbability = (0.0 / 10.0);

    private static Map<Chunk, Queue<Map.Entry<Block, Material>>> immediateBlockUpdateQueues = new HashMap<>();

    // temporary queue to store Block and Material to update
    private static Map<Chunk, Queue<Map.Entry<Block, Material>>> blockUpdateQueues = new HashMap<>();
    private static Map<Block, Consumer<Block>> postUpdater = new HashMap<>();

    private int configuredLavaInflux = -1;
    private double queuedLavaInflux = 0;
    private double prevQueuedLavaInflux = 0;

    private long previousRerender = 0;
    private long rerenderInterval = 1000 * 60 * 5;
    private Set<Chunk> rerenderTargets = new HashSet<>();

    private Map<Block, Long> lavaHaventSpreadEnoughYet = new HashMap<>();
    private long spreadEnoughCacheLifeTime = 1000 * 10;

    private boolean isShuttingDown = false;
    private List<TyphonCache<Block>> rootlessCones = new ArrayList<>();

    private double getSpreadEnoughThreshold() {
        double runniness = 1 - Math.min(1, Math.max(this.getLavaStickiness(), 0));
        double multiplier = 1 + runniness;

        if (this.vent.longestFlowLength < 100) {
            return Math.max(5, Math.sqrt(this.vent.longestFlowLength / 100) * 10) * multiplier;
        }

        return Math.min(Math.max(10, this.vent.longestFlowLength / 10), 20) * multiplier;
    }

    // core methods
    public VolcanoLavaFlow(VolcanoVent vent) {
        this.vent = vent;
        this.registerEvent();
    }

    public int getCurrentLavaInflux() {
        if (configuredLavaInflux < 0) {
            int lavaFlowableBlocks = vent.getVentBlocksScaffold().size();
            if (this.shouldFlowOverLeeve()) {
                lavaFlowableBlocks = (int) (Math.PI * Math.pow(Math.min(5, Math.max(2, this.vent.craterRadius)), 2));
            }

            double absoluteMax = Math.min(100, Math.pow(vent.craterRadius, 2) * Math.PI);

            int influxRate = (int) Math.max(1, Math.min((int) (lavaFlowableBlocks * (0.5 + Math.random())), absoluteMax));
            return influxRate;
        }

        return configuredLavaInflux;
    }

    public double getCurrentLavaInfluxPerTick() {
        return (double) this.getCurrentLavaInflux() / 20.0;
    }

    public void setLavaInflux(int currentLavaInflux) {
        this.configuredLavaInflux = currentLavaInflux;
    }

    public void queueBlockUpdate(Block block, Material material) {
        this.queueBlockUpdate(block, material, null);
    }

    public void queueBlockUpdate(Block block, Material material, Consumer<Block> callback) {
        if (this.queueScheduleId == -1) {
            this.registerQueueUpdate();
        }

        Chunk chunk = block.getChunk();
        if (!blockUpdateQueues.containsKey(chunk)) {
            blockUpdateQueues.put(chunk, new LinkedList<>());
        }

        Queue<Map.Entry<Block, Material>> blockUpdateQueue = blockUpdateQueues.get(chunk);

        blockUpdateQueue.add(new AbstractMap.SimpleEntry<>(block, material));
        if (callback != null) postUpdater.put(block, callback);
    }

    public void queueImmediateBlockUpdate(Block block, Material material) {
        Chunk chunk = block.getChunk();
        if (!immediateBlockUpdateQueues.containsKey(chunk)) {
            immediateBlockUpdateQueues.put(chunk, new LinkedList<>());
        }

        Queue<Map.Entry<Block, Material>> blockUpdateQueue = immediateBlockUpdateQueues.get(chunk);
        blockUpdateQueue.add(new AbstractMap.SimpleEntry<>(block, material));
    }

    public long unprocessedQueueBlocks() {
        long count = 0;
        for (Queue<Map.Entry<Block, Material>> blockUpdateQueue : blockUpdateQueues.values()) {
            count += blockUpdateQueue.size();
        }
        return count;
    }

    public long getProcessedBlocksPerSecond() {
        if (lastQueueUpdatedPerSecondAt == 0) return 0;

        long passedMs = (System.currentTimeMillis() - lastQueueUpdatedPerSecondAt);
        if (passedMs < 50) return lastQueueUpdates * 20;

        return (long) (lastQueueUpdatesPerSecond / (passedMs / (double) 1000));
    }

    public long getFlowedBlocksPerSecond() {
        return flowedBlocksPerSecond;
    }

    public int getActiveLavaCount() {
        return cachedActiveLavaCount;
    }

    public int getTerminalBlockCount() {
        return cachedTerminalBlockCount;
    }

    public int getUnderfillLavaCount() {
        return cachedUnderfillLavaCount;
    }

    public long getPlumbedBlocksPerSecond() {
        return plumbedBlocksPerSecond;
    }

    public long getSuccessfulPlumbsPerSecond() {
        return successfulPlumbsPerSecond;
    }

    public int getNormalFlowEndBlockCount() {
        return normalFlowEndBlocks.size();
    }

    public int getPillowFlowEndBlockCount() {
        return pillowFlowEndBlocks.size();
    }

    // ── Flow end block management (with column-based staleness detection) ──

    private static long packXZ(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private void addFlowEndBlock(Block block, boolean isPillow) {
        long key = packXZ(block.getX(), block.getZ());
        Block existing = flowEndColumnIndex.get(key);
        if (existing != null && !existing.equals(block)) {
            // Remove stale block at same x,z column
            normalFlowEndBlocks.remove(existing);
            pillowFlowEndBlocks.remove(existing);
            terminalBlocks.remove(existing);
        }
        flowEndColumnIndex.put(key, block);
        terminalBlocks.add(block);
        if (isPillow) {
            pillowFlowEndBlocks.add(block);
        } else {
            normalFlowEndBlocks.add(block);
        }
    }

    private void removeFlowEndBlock(Block block) {
        terminalBlocks.remove(block);
        normalFlowEndBlocks.remove(block);
        pillowFlowEndBlocks.remove(block);
        long key = packXZ(block.getX(), block.getZ());
        Block indexed = flowEndColumnIndex.get(key);
        if (indexed != null && indexed.equals(block)) {
            flowEndColumnIndex.remove(key);
        }
    }

    private void clearFlowEndBlocks() {
        terminalBlocks.clear();
        normalFlowEndBlocks.clear();
        pillowFlowEndBlocks.clear();
        flowEndColumnIndex.clear();
    }

    private void updateCachedCounts() {
        int count = 0;
        int underfillCount = 0;
        for (Map<Block, VolcanoLavaCoolData> lavaCoolMap : lavaCools.values()) {
            count += lavaCoolMap.size();
            for (VolcanoLavaCoolData data : lavaCoolMap.values()) {
                if (data.isUnderfill) underfillCount++;
            }
        }
        count += pillowLavaMap.size();
        cachedActiveLavaCount = count;
        cachedTerminalBlockCount = terminalBlocks.size();
        cachedUnderfillLavaCount = underfillCount;
    }

    public Volcano getVolcano() {
        return vent.getVolcano();
    }

    public boolean shouldFlowOverLeeve() {
        double baseY = this.vent.averageVentHeight();
        if (this.vent.getType() == VolcanoVentType.FISSURE) {
            double leeveY = this.vent.averageLeeveHeight();
            if (leeveY > baseY) return true;
        }
        return false;
    }

    public void resetThisFlow() {
        double baseY = this.vent.averageVentHeight();
//        double flowLength = 0;
        if (this.shouldFlowOverLeeve()) {
            double leeveY = this.vent.averageLeeveHeight();
            baseY = Math.round((leeveY + baseY) / 2);
//            flowLength = this.vent.craterRadius * 2;
        }

        this.hawaiianBaseY = baseY;

        this.vent.currentNormalLavaFlowLength = 0;
        this.prevFlowTime = -1;
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
                    TyphonScheduler.registerGlobalTask(
                            () -> {
                                this.plumbLava();
                                this.autoFlowLava();
                            },
                            1L);
        }
        if (lavaCoolScheduleId == -1) {
            this.vent.volcano.logger.log(
                    VolcanoLogClass.LAVA_FLOW,
                    "Intializing lava cooldown scheduler of VolcanoLavaFlow for vent "
                            + vent.getName());
            lavaCoolScheduleId =
                    TyphonScheduler.registerGlobalTask(
                            () -> {
                                runCooldownTick();
                                runPillowLavaTick();
                            },
                            1L);
        }
        if (underfillCleanupScheduleId == -1) {
            underfillCleanupScheduleId =
                    TyphonScheduler.registerGlobalTask(
                            this::cleanupUnderfillTargets,
                            100L); // every 5 seconds (100 ticks)
        }
        this.registerQueueUpdate();
    }

    public void registerQueueUpdate() {
        if (queueScheduleId == -1) {
            this.vent.volcano.logger.log(
                    VolcanoLogClass.LAVA_FLOW,
                    "Intializing lava cooldown scheduler of VolcanoLavaFlow for vent "
                            + vent.getName());
            queueScheduleId =
                    TyphonScheduler.registerGlobalTask(
                            this::delegateQueue,
                            1L);
        }
    }

    public void delegateQueue() {
        for (Map.Entry<Chunk, Queue<Map.Entry<Block, Material>>> entry : immediateBlockUpdateQueues.entrySet()) {
            if (entry.getValue().isEmpty()) continue;

            TyphonScheduler.run(entry.getKey(),
                    () -> {
                        runQueue(entry.getValue());
                    });
            rerenderTargets.add(entry.getKey());
        }

        for (Map.Entry<Chunk, Queue<Map.Entry<Block, Material>>> entry : blockUpdateQueues.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            TyphonScheduler.run(entry.getKey(),
                    () -> {
                        runBlockUpdateQueueWithinTick(entry.getValue());
                    });
            rerenderTargets.add(entry.getKey());
        }

        // update bluemap
        if (TyphonBlueMapUtils.isInitialized) {
            if (previousRerender == 0 || System.currentTimeMillis() - previousRerender > rerenderInterval) {
                TyphonBlueMapUtils.updateChunks(vent.location.getWorld(), rerenderTargets);
                previousRerender = System.currentTimeMillis();
                rerenderTargets.clear();
            }
        }
    }

    public long runQueue(Queue<Map.Entry<Block, Material>> blockUpdateQueue) {
        // get starting time
        long count = 1;

        while (true) {
            Map.Entry<Block, Material> entry = blockUpdateQueue.poll();
            if (entry != null) {
                Block block = entry.getKey();
                Material material = entry.getValue();
                TyphonBlocks.setBlockType(block, material);
            } else {
                break;
            }

            count++;
        }

        return count;
    }

    public long runBlockUpdateQueueWithinTick(Queue<Map.Entry<Block, Material>> blockUpdateQueue) {
        // get starting time
        long startTime = System.currentTimeMillis();
        long count = 1;

        while (true) {
            Map.Entry<Block, Material> entry = blockUpdateQueue.poll();
            if (entry != null) {
                Block block = entry.getKey();
                Material material = entry.getValue();
                TyphonBlocks.setBlockType(block, material);

                if (postUpdater.containsKey(block)) {
                    Consumer<Block> callback = postUpdater.get(block);
                    callback.accept(block);

                    postUpdater.remove(block);
                }
            } else {
                break;
            }

            if (count % 1000 == 0) {
                // if time is up, break
                if (System.currentTimeMillis() - startTime > 50) break;
            }

            count++;
        }

        return count;
    }

    public void unregisterTask() {
        if (lavaFlowScheduleId != -1) {
            this.vent.volcano.logger.log(
                    VolcanoLogClass.LAVA_FLOW,
                    "Shutting down lava flow scheduler of VolcanoLavaFlow for vent "
                            + vent.getName());
            TyphonScheduler.unregisterTask(lavaFlowScheduleId);
            lavaFlowScheduleId = -1;
        }
        if (lavaInfluxScheduleId != -1) {
            this.vent.volcano.logger.log(
                    VolcanoLogClass.LAVA_FLOW,
                    "Shutting down lava influx scheduler of VolcanoLavaFlow for vent "
                            + vent.getName());
            TyphonScheduler.unregisterTask(lavaInfluxScheduleId);
            lavaInfluxScheduleId = -1;
        }
        if (lavaCoolScheduleId != -1) {
            this.vent.volcano.logger.log(
                    VolcanoLogClass.LAVA_FLOW,
                    "Shutting down lava cooldown scheduler of VolcanoLavaFlow for vent "
                            + vent.getName());
            TyphonScheduler.unregisterTask(lavaCoolScheduleId);
            lavaCoolScheduleId = -1;
        }
        if (queueScheduleId != -1) {
            this.vent.volcano.logger.log(
                    VolcanoLogClass.LAVA_FLOW,
                    "Shutting down lava cooldown queue handler of VolcanoLavaFlow for vent "
                            + vent.getName());
            TyphonScheduler.unregisterTask(queueScheduleId);
            queueScheduleId = -1;
        }
        if (underfillCleanupScheduleId != -1) {
            TyphonScheduler.unregisterTask(underfillCleanupScheduleId);
            underfillCleanupScheduleId = -1;
        }
    }

    public void initialize() {
        this.isShuttingDown = false;
        this.vent.volcano.logger.log(
                VolcanoLogClass.LAVA_FLOW,
                "Intializing VolcanoLavaFlow for vent " + vent.getName());

        this.registerEvent();
        this.registerTask();
    }

    public void shutdown() {
        this.isShuttingDown = true;

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
            Volcano volcano = this.getVolcano();
            VolcanoLavaCoolData data = this.getLavaCoolData(targetBlock);
            if (data != null
                    || volcano.manager.isInAnyLavaFlowArea(loc)) {

                // check if this volcano has pickup prevention
                if (data != null) {
                    VolcanoVent targetVent = data.flowedFromVent;
                    if (targetVent != null) {
                        boolean allowPickUp = targetVent.lavaFlow.settings.allowPickUp;
                        if (allowPickUp) {
                            data.isProcessed = true;
                            data.ticks = 0;
                            return;
                        }
                    }
                }

                // this is from lava flow
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

    public void createEffectOnLavaSeaEntry(Block block) {
        if (Math.random() < 0.2) {
            // check if the block can be seen by players
            if (!block.getWorld().getNearbyEntities(block.getLocation(), 16, 16, 16).stream().anyMatch(e -> e instanceof Player)) {
                return;
            }
            block
                    .getWorld()
                    .playSound(
                            block.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, SoundCategory.BLOCKS, 1f, 0f);

            // sea entry
            TyphonUtils.createRisingSteam(block.getLocation(), 1, 2);
        }
    }

    @EventHandler
    public void lavaCollisionDetector(BlockFormEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.COBBLESTONE || block.getType() == Material.STONE || block.getType() == Material.OBSIDIAN) {
            BlockFace[] faces = { BlockFace.UP, BlockFace.WEST, BlockFace.EAST, BlockFace.NORTH, BlockFace.SOUTH};
            for (BlockFace face : faces) {
                Block fromBlock = block.getRelative(face);
                if (fromBlock.getType() == Material.LAVA) {
                    VolcanoLavaCoolData data = this.getLavaCoolData(fromBlock);
                    if (data != null) {
                        createEffectOnLavaSeaEntry(block);
                        VolcanoPillowLavaData pillow = new VolcanoPillowLavaData(data.flowedFromVent, data.source, data.fromBlock, data.runExtensionCount);
                        pillow.ejectaRecordIdx = data.ejectaRecordIdx;
                        cachedPillowLavaMap.put(block, pillow);
                        this.addFlowEndBlock(block, true);
                        queueImmediateBlockUpdate(block, VolcanoComposition.getExtrusiveRock(data.flowedFromVent.lavaFlow.settings.silicateLevel));
                        break;
                    }
                }
            }
        }
    }

    public Block getRandomTerminalBlock() {
        return getRandomFromSet(this.normalFlowEndBlocks);
    }

    private static Block getRandomFromSet(Set<Block> set) {
        if (set.isEmpty()) return null;

        int index = (int) (Math.random() * set.size());
        Iterator<Block> it = set.iterator();
        for (int i = 0; i < index; i++) {
            it.next();
        }
        return it.next();
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

        VolcanoLavaCoolData data = this.getLavaCoolData(block);

        // remove from terminalBlocks
        this.removeFlowEndBlock(block);

        int level = -1;
        BlockData bd = block.getBlockData();
        if (bd instanceof Levelled) {
            level = ((Levelled) bd).getLevel();
        }

        if (toBlock.getType() == Material.LAVA) {
            if (data != null) {
                if (data.fromBlock != block) {
                    data.forceCoolDown();
                }
                return;
            }
        }

        // this is lava. flow it.
        if (data != null) {
            /*
            // for realistic lava flows
            if (face != BlockFace.DOWN) {
                if (Math.random() < 0.01 * this.vent.lavaFlow.settings.silicateLevel) {
                    event.setCancelled(true);
                    toBlock.setType(Material.AIR);
                    return;
                }
            }
            */
            // calculate flow direction
            boolean isPrimary = true;
            if (data.fromBlock != null) {
                BlockFace fromFace = data.fromBlock.getFace(block);
                if (fromFace != BlockFace.DOWN) {
                    if (fromFace != null) {
                        isPrimary = face.getDirection().equals(fromFace.getDirection());
                    }
                }
            }

            if (face == BlockFace.DOWN) {
                isPrimary = true;
            }

            int decrementAmount = block.getWorld().isUltraWarm() ? 1 : 2;
            int levelConverted = level >= 8 ? 8 : (8 - level);

            int levelT = isPrimary ? levelConverted : levelConverted - decrementAmount;
            double ratio = Math.min(1, Math.max(0, levelT / (8.0 - decrementAmount)));

            double levelProbability = Math.pow(ratio, 2);
            if (isPrimary) {
                levelProbability = Math.pow(ratio, 1.25);
            }

            if (Math.random() < this.getLavaStickiness()) {
                if (Math.random() > levelProbability) {
                    vent.volcano.logger.debug(VolcanoLogClass.LAVA_FLOW, "Lava stickiness match! levelProbability: "+levelProbability);

                    event.setCancelled(true);
                    return;
                }
            }

            if (Math.random() < rootlessConeProbability) {
                this.doPlumbingToRootlessCone();
            }

            if (this.vent != null && !data.isBomb && data.source != null) {
                double distance;
                distance =
                        TyphonUtils.getTwoDimensionalDistance(
                                data.source.getLocation(), block.getLocation());

                boolean trySave = false;
                if (distance > vent.longestFlowLength) {
                    vent.longestFlowLength = distance;
                    trySave = true;
                }

                if (distance > vent.currentFlowLength) {
                    vent.currentFlowLength = distance;
                    trySave = true;
                }

                if (!data.skipNormalLavaFlowLengthCheck) {
                    if (distance > vent.longestNormalLavaFlowLength) {
                        vent.longestNormalLavaFlowLength = distance;
                        trySave = true;
                    }

                    if (distance > vent.currentNormalLavaFlowLength) {
                        vent.currentNormalLavaFlowLength = distance;
                        trySave = true;
                    }

                    if (vent.calderaRadius < distance) {
                        vent.calderaRadius = -1;
                        trySave = true;
                    }
                }


                if (trySave) {
                    vent.getVolcano().trySave(false);
                }

                // force load chunk.
                if (!vent.location.getChunk().isLoaded()) vent.location.getChunk().load();
            } else if (data.isBomb) {
                if (this.vent != null && data.source != null) {
                    if (!this.vent.isInVent(toBlock.getLocation())) {
                        if (data.flowLimit >= 0) {
                            if (TyphonUtils.getTwoDimensionalDistance(data.source.getLocation(), toBlock.getLocation()) > data.flowLimit) {
                                if (vent != null && !vent.isInVent(toBlock.getLocation())) {
//                                  vent.volcano.logger.log(
//                                        VolcanoLogClass.LAVA_FLOW,
//                                        "flowLimit match! flowLimit: "+data.flowLimit
//                                  );
                                    event.setCancelled(true);
                                    return;
                                }
                            }
                        }
                    }
                }
            }

            Block underToBlock = toBlock.getRelative(BlockFace.DOWN);

            this.vent.getVolcano().chunkLoader.add(toBlock.getChunk());

            // load toBlock chunk
            Chunk toBlockChunk = toBlock.getLocation().getChunk();
            if (!toBlockChunk.isLoaded()) {
                toBlockChunk.load();
            }

            Block underUnderToBlock = underToBlock.getRelative(BlockFace.DOWN);
            boolean underIsAir = underToBlock.getType().isAir();

            if (!underIsAir && underToBlock.getType() != Material.LAVA) {
                getVolcano().metamorphism.metamorphoseBlock(vent, underToBlock, true);
            }

            boolean fillUnderUnder = true;
            if (data.isBomb) {
                if (data.flowLimit >= 0) {
                    fillUnderUnder = Math.random() < 0.1;
                }
            }

            if (data.flowedFromVent != null && fillUnderUnder) {
                double silicateLevel = data.flowedFromVent.lavaFlow.settings.silicateLevel;
                if (silicateLevel > 0.50) {
                    double silicaRatio = Math.min(0.57, silicateLevel) - 0.50 / 0.07;
                    if (silicaRatio > 1 - Math.pow(silicaRatio, 2)) {
                        fillUnderUnder = Math.random() > 0.5;
                    }
                }
            }

            if (underIsAir && underUnderToBlock.getType().isAir() && fillUnderUnder) {
                queueImmediateBlockUpdate(underToBlock, data.material);

                // Defer underfill to plumbing instead of immediate lava placement
                if (!data.isBomb) {
                    double runniness = 1.0 - (Math.max(0.45, this.settings.silicateLevel) - 0.45 / (0.53 - 0.45));

                    // No underfill for sticky (andesitic+) lava or when not flowing
                    if (this.vent.isFlowingLava() && Math.random() < (Math.pow(runniness, 2))
                            && data.source != null && this.vent.longestNormalLavaFlowLength > 0) {
                        double dist = TyphonUtils.getTwoDimensionalDistance(data.source.getLocation(), toBlock.getLocation());
                        double ratio = (this.vent.longestNormalLavaFlowLength - dist) / this.vent.longestNormalLavaFlowLength;
                        ratio = Math.max(0, Math.min(1, ratio));
                        if (Math.random() < Math.pow(ratio, 2)) {
                            underfillTargets.add(underUnderToBlock);
                        }
                    }
                } else {
                    this.flowLavaFromBomb(underUnderToBlock);
                }
            }

            // affect nearby blocks run metamorphism
            BlockFace[] nearByFaces = { BlockFace.UP, BlockFace.WEST, BlockFace.EAST, BlockFace.NORTH, BlockFace.SOUTH};
            for (BlockFace nearByFace : nearByFaces) {
                Block targetBlock = toBlock.getRelative(nearByFace);
                this.getVolcano().metamorphism.metamorphoseBlock(vent, targetBlock, data.isBomb);
            }

            int extensionCount = data.runExtensionCount;

            if (data.fromBlock != null) {
                Location flowVector = block.getLocation().subtract(data.fromBlock.getLocation());
                // this.flowedFromVent.volcano.logger.log(VolcanoLogClass.LAVA_FLOW,
                //      "FlowVector: " + TyphonUtils.blockLocationTostring(flowVector.getBlock()));

                if (!data.isBomb) {
                    if (flowVector.getBlockY() < 0) {
                            extensionCount = -1;
                    }
                }
            }

            Object obj = this.registerLavaCoolData(data.source, block, toBlock, data.isBomb, extensionCount, data.ejectaRecordIdx);
            if (obj instanceof VolcanoLavaCoolData coolData) {
                if (data.isUnderfill) {
                    // Underfill flow: propagate flag, route end-of-flow to underfillTargets
                    coolData.isUnderfill = true;
                    // Skip x,z column dedup — underfill needs to fill underneath
                    terminalBlocks.add(toBlock);
                } else {
                    // Normal flow: register on terminalBlocks with column dedup
                    this.addFlowEndBlock(toBlock, false);
                }

                if (data.skipNormalLavaFlowLengthCheck) {
                    coolData.skipNormalLavaFlowLengthCheck = true;
                }

                if (data.isBomb) {
                    coolData.flowLimit = data.flowLimit;
                }
            } else if (obj instanceof VolcanoPillowLavaData pillowData) {
                this.addFlowEndBlock(toBlock, true);
                Block targetBlock = TyphonUtils.getHighestRocklikes(pillowData.fromBlock);
                this.createEffectOnLavaSeaEntry(targetBlock);
            }
        }
    }

    private VolcanoLavaCoolData getLavaCoolData(Block block) {
        if (block == null) return null;

        Map<Block, VolcanoLavaCoolData> lavaCoolHashMap = this.lavaCools.get(block.getChunk());
        if (lavaCoolHashMap == null) {
            return this.getCachedLavaCoolData(block);
        }

        VolcanoLavaCoolData data = lavaCoolHashMap.get(block);
        if (data == null) {
            return this.getCachedLavaCoolData(block);
        }

        return data;
    }

    private VolcanoLavaCoolData getCachedLavaCoolData(Block block) {
        Map<Block, VolcanoLavaCoolData> lavaCoolHashMap = this.cachedCools.get(block.getChunk());
        if (lavaCoolHashMap == null) {
            return null;
        }

        return lavaCoolHashMap.get(block);
    }

    private VolcanoPillowLavaData getPillowLavaCoolData(Block block) {
        VolcanoPillowLavaData coolData = pillowLavaMap.get(block);
        if (coolData == null) {
            coolData = cachedPillowLavaMap.get(block);
        }

        return coolData;
    }

    private boolean isNormalLavaRegistered(Block block) {
        return this.getLavaCoolData(block) != null;
    }

    private boolean isPillowLavaRegistered(Block block) {
        return this.getPillowLavaCoolData(block) != null;
    }

    public boolean isLavaOKForFlowOnTop(Block block) {
        return !this.isLavaRegistered(block) && this.hasLavaSpreadEnough(block);
    }

    public boolean hasLavaSpreadEnough(Block block) {
        Long result = this.lavaHaventSpreadEnoughYet.get(block);
        if (result == null) return true;

        // check if the cache has been expired
        if (System.currentTimeMillis() - result > spreadEnoughCacheLifeTime) {
            this.lavaHaventSpreadEnoughYet.remove(block);
            return true;
        }

        return false;
    }

    public boolean isLavaRegistered(Block block) {
        return this.isNormalLavaRegistered(block) || this.isPillowLavaRegistered(block);
    }

    private int resolveEjectaRecordIdx(Block fromBlock, Block source) {
        VolcanoLavaCoolData fromData = getLavaCoolData(fromBlock);
        if (fromData != null && fromData.ejectaRecordIdx >= 0) {
            return fromData.ejectaRecordIdx;
        }
        VolcanoPillowLavaData fromPillow = getPillowLavaCoolData(fromBlock);
        if (fromPillow != null && fromPillow.ejectaRecordIdx >= 0) {
            return fromPillow.ejectaRecordIdx;
        }

        VolcanoLavaCoolData sourceData = getLavaCoolData(source);
        if (sourceData != null && sourceData.ejectaRecordIdx >= 0) {
            return sourceData.ejectaRecordIdx;
        }
        VolcanoPillowLavaData sourcePillow = getPillowLavaCoolData(source);
        if (sourcePillow != null && sourcePillow.ejectaRecordIdx >= 0) {
            return sourcePillow.ejectaRecordIdx;
        }

        return vent.record.getRecordIndex();
    }

    private Object registerLavaCoolData(Block block, boolean isBomb, int extension) {
        return this.registerLavaCoolData(block, block, block, isBomb, extension);
    }

    private Object registerLavaCoolData(Block block, boolean isBomb, int extension, int explicitRecordIdx) {
        boolean isUnderWater = block.getType() == Material.WATER;
        if (!isUnderWater) {
            BlockFace[] waterFaces = { BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.UP };
            for (BlockFace face : waterFaces) {
                if (block.getRelative(face).getType() == Material.WATER) { isUnderWater = true; break; }
            }
        }
        return this.registerLavaCoolData(block, block, block, isBomb, extension, isUnderWater, false, explicitRecordIdx);
    }

    private Object registerLavaCoolData(Block source, Block fromBlock, Block block, boolean isBomb) {
        return this.registerLavaCoolData(source, fromBlock, block, isBomb, -1);
    }

    public Object registerLavaCoolData(
            Block source, Block fromBlock, Block block, boolean isBomb, int extension) {
        return this.registerLavaCoolData(source, fromBlock, block, isBomb, extension, -1);
    }

    public Object registerLavaCoolData(
            Block source, Block fromBlock, Block block, boolean isBomb, int extension, int recordIdx) {

        boolean isUnderWater = block.getType() == Material.WATER;

        if (!isUnderWater) {
            boolean isSurroundedByWater = false;
            BlockFace[] waterFlowableFromFaces = {
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.UP
            };

            for (BlockFace face: waterFlowableFromFaces) {
                Block flowBlock = block.getRelative(face);
                if (flowBlock.getType() == Material.WATER) {
                    isSurroundedByWater = true;
                    break;
                }
            }

            if (isSurroundedByWater) isUnderWater = true;
        }

        return this.registerLavaCoolData(source, fromBlock, block, isBomb, extension, isUnderWater, false, recordIdx);
    }

    private Material getVolcanicPlugOre() {
        double random = Math.random();

        double silicateLevel = Math.min(Math.max(this.settings.silicateLevel, 0.43), 0.74);
        double silicateRange = (0.74 - 0.43);

        double ratio = 1 - ((silicateLevel - 0.43) / silicateRange);

        double iron = getProbabilityOfIron() * 3;
        double copper = iron * 1.4;

        // gold / iron = 0.55555~~ * 1/4 (iron: 8, gold: 2)
        double gold = getProbabilityOfSeperation(0.1 * 0.11) * 3;
        double emerald = getProbabilityOfEmerald() * (1 + (3 * ratio));

        // these will be calculated as same as minecraft defaults; ten times of it.
        double diamond = 0.000846 * (4 + (6 * ratio)); // 0.008
        double ancientDebris = 0.00005 * (8 + (8 * ratio)); // 0.0008

        double base = 0;

        if (random < base + iron) {
            return Material.IRON_ORE;
        }
        base += iron;

        if (random < base + copper) {
            return Material.COPPER_ORE;
        }
        base += copper;

        if (random < base + diamond) {
            return Material.DIAMOND_ORE;
        }
        base += diamond;

        if (random < base + gold) {
            return Material.GOLD_ORE;
        }
        base += gold;

        if (random < base + emerald) {
            return Material.EMERALD_ORE;
        }
        base += emerald;

        if (random < base + ancientDebris) {
            return Material.ANCIENT_DEBRIS;
        }
        base += ancientDebris;

        return null;


    }

    private Material getRegularOre() {
        double random = Math.random();

        double iron = getProbabilityOfIron();
        double copper = iron * 1.4;

        // gold / iron = 0.55555~~ * 1/4 (iron: 8, gold: 2)
        double gold = getProbabilityOfSeperation(0.1 * 0.11);

        double emerald = getProbabilityOfEmerald();

        // these will be calculated as same as minecraft defaults;
        double diamond = 0.000846;
        double ancientDebris = 0.00005;

        double base = 0;

        if (random < base + iron) {
            return Material.IRON_ORE;
        }
        base += iron;

        if (random < base + copper) {
            return Material.COPPER_ORE;
        }
        base += copper;

        if (random < base + diamond) {
            return Material.DIAMOND_ORE;
        }
        base += diamond;

        if (random < base + gold) {
            return Material.GOLD_ORE;
        }
        base += gold;

        if (random < base + emerald) {
            return Material.EMERALD_ORE;
        }
        base += emerald;

        if (random < base + ancientDebris) {
            return Material.ANCIENT_DEBRIS;
        }
        base += ancientDebris;

        return null;
    }

    private double getIronContentOfLava() {
        double silicateLevel = Math.min(Math.max(this.settings.silicateLevel, 0.43), 0.74);
        double silicateRange = (0.74 - 0.43);

        double ratio = 1 - ((silicateLevel - 0.43) / silicateRange);
        return (0.04 + (ratio * 0.06));
    }

    private double getProbabilityOfEmerald() {
        double silicateLevel = Math.min(Math.max(this.settings.silicateLevel, 0), 1);

        // Average amount of Aluminium in lava: 16%
        return getProbabilityOfSeperation(silicateLevel * 0.16 * 0.015);
    }

    private double getProbabilityOfIron() {
        return getProbabilityOfSeperation(getIronContentOfLava());
    }


    private double getProbabilityOfSeperation(double base) {
        // part of separated.
        double baseSeperated = 0.2 + (0.2 * Math.random() - 0.1);

        return base * baseSeperated;
    }

    private Material oreifyMaterial(Material material, Material source) {
        Material targetMaterial = source;

        if (source == Material.ANCIENT_DEBRIS) {
            return Material.ANCIENT_DEBRIS;
        }

        if (source == Material.GOLD_ORE && material == Material.NETHERRACK) {
            return Material.NETHER_GOLD_ORE;
        } else if (source == Material.GOLD_ORE && (material == Material.DEEPSLATE || material == Material.BLACKSTONE)) {
            return Material.GILDED_BLACKSTONE;
        }

        switch (material) {
            case TUFF:
                return Material.TUFF;
            case DEEPSLATE:
            case COBBLED_DEEPSLATE:
            case BLACKSTONE:
            case BASALT:
            case POLISHED_BASALT:
                targetMaterial = Material.getMaterial("DEEPSLATE_"+source.name());
                break;
            default:
                return source;
        }

        if (targetMaterial == null) {
            return source;
        }

        return targetMaterial;
    }

    private Material getOre(double distance) {
        double killZone = vent.getType() == VolcanoVentType.CRATER ? vent.craterRadius : 0;
        double ratio = distance / this.vent.longestFlowLength;
        if (distance < killZone) {
            return getVolcanicPlugOre();
        }

        if (ratio < 0.1) {
            return getVolcanicPlugOre();
        } else if (ratio < 0.2) {
            if (Math.random() > (ratio - 0.1) * 10) {
                return getVolcanicPlugOre();
            } else {
                return getRegularOre();
            }
        } else {
            return getRegularOre();
        }
    }

    private Object registerLavaCoolData(
            Block source,
            Block fromBlock,
            Block block,
            boolean isBomb,
            int extension,
            boolean isUnderWater
    ) {
        return registerLavaCoolData(source, fromBlock, block, isBomb, extension, isUnderWater, false);
    }

    private void registerToCacheCools(Block block, VolcanoLavaCoolData data) {
        Map<Block, VolcanoLavaCoolData> cachedLavaCoolHashMap = this.cachedCools.get(block.getChunk());
        if (cachedLavaCoolHashMap == null) {
            cachedLavaCoolHashMap = new HashMap<>();
            this.cachedCools.put(block.getChunk(), cachedLavaCoolHashMap);
        }

        cachedLavaCoolHashMap.put(block, data);
    }

    private Object registerLavaCoolData(
            Block source,
            Block fromBlock,
            Block block,
            boolean isBomb,
            int extension,
            boolean isUnderWater,
            boolean skipLava) {
        return registerLavaCoolData(source, fromBlock, block, isBomb, extension, isUnderWater, skipLava, -1);
    }

    private Object registerLavaCoolData(
            Block source,
            Block fromBlock,
            Block block,
            boolean isBomb,
            int extension,
            boolean isUnderWater,
            boolean skipLava,
            int explicitRecordIdx) {
        Object result = null;
        int recordIdx = explicitRecordIdx >= 0 ? explicitRecordIdx : resolveEjectaRecordIdx(fromBlock, source);
        Material targetMaterial =
                isBomb && !isUnderWater
                        ? VolcanoComposition.getBombRock(this.settings.silicateLevel, this.getDistanceRatio(block.getLocation()))
                        : VolcanoComposition.getExtrusiveRock(this.settings.silicateLevel);

        double distance = TyphonUtils.getTwoDimensionalDistance(source.getLocation(), block.getLocation());

        Material ore = this.getOre(distance);
        if (ore != null) {
            Material oreified = this.oreifyMaterial(targetMaterial, ore);
            if (oreified != null) targetMaterial = oreified;
        }

        // this is an initial lava block
        if (source == block && fromBlock == block) {
            this.lavaHaventSpreadEnoughYet.put(block, System.currentTimeMillis());
        } else if (this.lavaHaventSpreadEnoughYet.get(source) != null) {
            // check the distance from the source
            double distanceFromSource = TyphonUtils.getTwoDimensionalDistance(source.getLocation(), block.getLocation());
            if (distanceFromSource > this.getSpreadEnoughThreshold()) {
                this.lavaHaventSpreadEnoughYet.remove(source);
            }
        }

        if (!isUnderWater) {
            if (!skipLava) {
                TyphonBlocks.setBlockType(block, Material.LAVA);
            }

            // Force-load the chunk to ensure lava physics are applied
            this.vent.getVolcano().chunkLoader.add(block.getChunk());

            int ticks = 30;
            VolcanoLavaCoolData coolData;

            if (extension < 0) {
                 coolData = new VolcanoLavaCoolData(
                        source,
                        fromBlock,
                        block,
                        this.vent,
                        targetMaterial,
                        ticks * this.settings.flowed,
                        isBomb);
                result = coolData;
            } else {
                coolData = new VolcanoLavaCoolData(
                        source,
                        fromBlock,
                        block,
                        this.vent,
                        targetMaterial,
                        ticks * this.settings.flowed,
                        isBomb,
                        extension);
                result = coolData;
            }
            coolData.ejectaRecordIdx = recordIdx;
            registerToCacheCools(block, coolData);
        } else {
            VolcanoPillowLavaData lavaData = cachedPillowLavaMap.get(block);
            if (lavaData == null) {
                this.queueBlockUpdate(block, Material.MAGMA_BLOCK);
                VolcanoPillowLavaData coolData = new VolcanoPillowLavaData(
                        this.vent, source, fromBlock, extension);
                coolData.ejectaRecordIdx = recordIdx;

                // reset extensions since lava can not travel long underwater
                cachedPillowLavaMap.put(block, coolData);
                this.addFlowEndBlock(block, true);
                result = coolData;

                /* - backup!
                    if (extension < 0) {
                        cachedPillowLavaMap.put(block, new VolcanoPillowLavaData(this.vent, source, fromBlock));
                    } else {
                        // reset
                        cachedPillowLavaMap.put(
                                block, new VolcanoPillowLavaData(this.vent, source, fromBlock, 0));
                    }
                */
            }
        }

        if (vent != null) {
            vent.record.addEjectaVolume(1, recordIdx);
        }

        return result;
    }

    public Block getRandomLavaBlock() {
        List<Block> target = getRandomLavaBlocks(1);
        if (target.isEmpty()) return null;
        return target.get(0);
    }

    public boolean hasAnyLavaFlowing() {
        int counts = 0;
        for (Map<Block, VolcanoLavaCoolData> lavaCoolMap : lavaCools.values())
            counts += lavaCoolMap.size();

        counts += pillowLavaMap.size();

        return counts > 0;
    }

    public List<Block> getRandomLavaBlocks(int count) {
        List<Block> targetBlocks = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            Block block = null;

            // 30% chance to pick from pillow flow endpoints
            if (!pillowFlowEndBlocks.isEmpty() && Math.random() >= 0.7) {
                block = getRandomFromSet(pillowFlowEndBlocks);
            }

            // 70% chance (or fallback) to pick from normal flow endpoints
            if (block == null && !normalFlowEndBlocks.isEmpty()) {
                block = getRandomFromSet(normalFlowEndBlocks);
            }

            // Final fallback to pillow if normal is empty
            if (block == null && !pillowFlowEndBlocks.isEmpty()) {
                block = getRandomFromSet(pillowFlowEndBlocks);
            }

            if (block != null) {
                targetBlocks.add(block);
            }
        }

        return targetBlocks;
    }

    public boolean tryExtend() {
        return this.tryExtend(50);
    }

    public boolean tryExtend(int maxTrial) {
        for (int i = 0; i < maxTrial; i++) {
            if (this.extendLava()) return true;
        }

        return false;
    }

    public void createLavaParticle(Block currentBlock) {
        World world = currentBlock.getWorld();
        world.spawnParticle(Particle.LAVA, currentBlock.getLocation(), 10);
    }

    public void flowLava(Block whereToFlow) {
        this.flowLava(whereToFlow, whereToFlow);
    }

    public void flowLava(Block source, Block currentBlock) {
        this.createLavaParticle(currentBlock);
        registerLavaCoolData(source, currentBlock, currentBlock, false);

        if (this.vent != null && this.vent.erupt != null) {
            this.vent.erupt.updateVentConfig();
        }
    }

    public void flowVentLavaFromBomb(Block bomb) {
        this.createLavaParticle(bomb);
        this.flowLavaFromBomb(bomb, -1);
    }

    public void flowLavaFromBomb(Block bomb) {
        if (this.vent.getTwoDimensionalDistance(bomb.getLocation()) <= 10) {
            flowVentLavaFromBomb(bomb);
            return;
        }
        this.flowLavaFromBomb(bomb, 0);
    }

    public void flowLavaFromBomb(Block bomb, int flowLimit) {
        this.flowLavaFromBomb(bomb, flowLimit, -1);
    }

    public void flowLavaFromBomb(Block bomb, int flowLimit, int recordIdx) {
        // if it is underwater, randomly give it an offset.
        if (TyphonUtils.containsLiquidWater(bomb)) {
            double multiplier = 1 - (Math.pow(Math.random(), 2));
            double distance = 10 * multiplier;
            double angle = Math.random() * Math.PI * 2;

            double offsetX = Math.cos(angle) * distance;
            double offsetZ = Math.sin(angle) * distance;

            Block targetBlock = bomb.getRelative((int) offsetX, 0, (int) offsetZ);
            bomb = TyphonUtils.getHighestRocklikes(targetBlock).getRelative(BlockFace.UP);
        }

        Object data = recordIdx >= 0
                ? this.registerLavaCoolData(bomb, true, 0, recordIdx)
                : this.registerLavaCoolData(bomb, true, 0);
        if (data instanceof VolcanoLavaCoolData coolData) {
            if (flowLimit > 0) {
                coolData.flowLimit = flowLimit;
            } else {
                coolData.flowLimit = -1;
            }
        }
    }

    public boolean doPlumbingToRootlessCone() {
        // check expiration
        this.rootlessCones.removeIf(TyphonCache::isExpired);
        int limiter = (int) Math.pow((Math.max(0, this.vent.longestNormalLavaFlowLength - 100) / 90), 2);

        if (this.rootlessCones.isEmpty() || Math.random() < 0.25 && this.rootlessCones.size() < limiter) {
            // create one
            return this.tryRootlessCone();
        }

        // get random one
        TyphonCache<Block> randomRootlessCone = this.rootlessCones.get((int) (Math.random() * this.rootlessCones.size()));
        if (randomRootlessCone == null) {
            return false;
        }

        Block targetBlock = randomRootlessCone.getTarget();
        return doPlumbingToRootlessCone(targetBlock);
    }

    public boolean tryRootlessCone() {
        // check if the lava sillicalevel isn't too high
        if (this.settings.silicateLevel > 0.53) return false;

        if (this.vent.erupt.getStyle() != VolcanoEruptStyle.HAWAIIAN)
            return false;

        int threshold = Math.max(70, this.vent.getRadius() + 30);
        double max = Math.max(this.vent.getBasinLength() + 10, this.vent.longestNormalLavaFlowLength);

        max *= (0.2 * Math.random()) + 0.8;
        if (max < threshold) return false;

        double distanceRatio = 1.0 - Math.pow(Math.random(), 2);
        double radius = threshold + (distanceRatio * (max - threshold));
        double angle = Math.random() * Math.PI * 2;

        if (this.vent.isCaldera()) {
            if (Math.random() < 0.8) {
                radius = Math.min(radius, this.vent.calderaRadius);
            }
        }

        Location location = this.vent.location;
        Location targetLocation = location.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);

        Block block = TyphonUtils.getHighestRocklikes(targetLocation);

        // is near any vent
        VolcanoVent nearVent = this.getVolcano().manager.getNearestVent(block.getLocation());
        if (nearVent != null) {
            double volcanoZone = Math.max(70, Math.min(100, nearVent.longestNormalLavaFlowLength)) + nearVent.getRadius();
            double volcanoZoneGrace = volcanoZone + 100;

            if (volcanoZoneGrace > nearVent.longestNormalLavaFlowLength) {
                volcanoZoneGrace = nearVent.longestNormalLavaFlowLength + nearVent.getRadius();
            }

            double distance = nearVent.getTwoDimensionalDistance(block.getLocation());
            if (distance < volcanoZone) {
                return false;
            } else if (distance < volcanoZoneGrace) {
                double probability = (distance - volcanoZone) / (volcanoZoneGrace - volcanoZone);
                if (Math.random() < Math.pow(probability, 2)) {
                    return false;
                }
            }
        }

        if (this.vent.erupt.isErupting()) {
            if (VolcanoComposition.isVolcanicRock(block.getType())) {
                this.addRootlessCone(targetLocation);
                return true;
            }
        } else if (this.vent.lavaFlow.hasAnyLavaFlowing()){
            // this is during the post-eruption lava flow,
            // which is cooling of already erupted lava

            Block topOfIt = block.getRelative(BlockFace.UP);
            if (topOfIt.getType() == Material.LAVA && this.getLavaCoolData(topOfIt) != null) {
                this.addRootlessCone(targetLocation);
                return true;
            }
        }

        return false;
    }

    public int rootlessConeHeight(int height, double offset) {
        if (offset < height) return (int) offset;

        double distance = offset - height;
        double deduct = distance / Math.sqrt(3);

        return (int) Math.round(height - deduct);
    }

    public void addRootlessCone(Location location) {
        this.addRootlessCone(location, (int) (7 + (Math.pow(Math.random(), 2) * 3)));
    }

    public void addRootlessCone(Location location, int height) {
        Block baseBlock = TyphonUtils.getHighestRocklikes(location);
        boolean allowLavaFlow = !this.isShuttingDown;
        int summitThreshold = this.vent.getSummitBlock().getY() - 10;

        double rootlessConeRange = 70;

        if (!allowLavaFlow) return;
        if (rootlessCones != null && !rootlessCones.isEmpty()) {
            for (TyphonCache<Block> cache : rootlessCones) {
                if (TyphonUtils.getTwoDimensionalDistance(location, cache.getTarget().getLocation()) < rootlessConeRange) {
                    return;
                }
            }
        }

        // get circle around the base block
        List<Block> craterBlock = VolcanoMath.getHollowCircle(baseBlock, height);
        double heightSum = 0;
        int max = Integer.MIN_VALUE, min = Integer.MAX_VALUE;
        for (Block block : craterBlock) {
            Block highestBlock = TyphonUtils.getHighestRocklikes(block);
            if (highestBlock.getY() + height > summitThreshold) {

                this.getVolcano().logger.log(
                        VolcanoLogClass.LAVA_FLOW,
                        "Rootless cone rejected due to passing summitThreshold " + TyphonUtils.blockLocationTostring(baseBlock)
                );
                return;
            }

            heightSum += highestBlock.getY();
            max = Math.max(max, highestBlock.getY());
            min = Math.min(min, highestBlock.getY());
        }

        TyphonCache<Block> cache = new TyphonCache<Block>(baseBlock);
        cache.cacheValidity = (long) (1000 * 60 * (1 + (Math.random() * 3)));

        this.getVolcano().logger.log(
            VolcanoLogClass.LAVA_FLOW,
            "Rootless cone created at " + TyphonUtils.blockLocationTostring(baseBlock)
        );
        rootlessCones.add(cache);
    }

    private int getRootlessConeRadius() {
        return (int) Math.min(20, Math.max(10, this.vent.getRadius() / 2));
    }

    private boolean doPlumbingToRootlessCone(Block baseBlock) {
        Location target = TyphonUtils.getHighestRocklikes(baseBlock).getLocation().add(0, 1, 0);

        VolcanoBomb bomb = this.vent.bombs.generateBomb(target);
        bomb.launchLocation = target.add(0, 5, 0);

        // effuse lava
        boolean allowLavaFlow = !this.isShuttingDown;
        if (!allowLavaFlow) return true;

        int radius = this.getRootlessConeRadius();

        // even flow
        List<Block> craterBlock = VolcanoMath.getHollowCircle(baseBlock, radius);
        double averageY = 0;
        int lowestY = Integer.MAX_VALUE;

        for (Block block : craterBlock) {
            Block highestBlock = TyphonUtils.getHighestRocklikes(block);
            averageY += highestBlock.getY();
            lowestY = Math.min(lowestY, highestBlock.getY());
        }

        averageY /= craterBlock.size();
        int blocks = (int) (Math.random() * 5);

        // get nearest vent
        VolcanoVent nearestVent = this.getVolcano().manager.getNearestVent(baseBlock.getLocation());

        int deduction = (int) Math.max(10, Math.max(0, nearestVent.getTwoDimensionalDistance(baseBlock.getLocation()) - nearestVent.getRadius()) / 6);
        int summitY = this.vent.getSummitBlock().getY();
        int limitY = summitY - deduction + 5;

        craterBlock.removeIf((block) -> {
            int y = TyphonUtils.getHighestRocklikes(block).getY();
            return y > limitY - 2;
        });
        if (craterBlock.isEmpty()) return false;

        boolean doEvenFlow = Math.random() < 0.5 && lowestY < averageY - 5;
        if (doEvenFlow) {
            double finalAverageY = averageY;


            craterBlock.removeIf((block) -> {
                int y = TyphonUtils.getHighestRocklikes(block).getY();
                return y > finalAverageY - 3;
            });
        }

        // the flows are even. we can flow lava on everything.
        if (craterBlock.isEmpty()) {
            craterBlock = VolcanoMath.getHollowCircle(baseBlock, radius);
        }

        int randomIndex = (int) (Math.random() * craterBlock.size());
        Block targetBlock = craterBlock.get(randomIndex);

        Block targetRandomBlock = TyphonUtils.getHighestRocklikes(targetBlock).getRelative(BlockFace.UP);
        if (Math.random() < 0.8) {
            if (targetRandomBlock.getType().isAir()) {
                this.flowVentLavaFromBomb(targetRandomBlock);
                VolcanoLavaCoolData data = this.vent.lavaFlow.getLavaCoolData(targetRandomBlock);

            } else if (targetRandomBlock.getY() < averageY - 3) {
                // cooldown lower blocks
                VolcanoLavaCoolData data = this.vent.lavaFlow.getLavaCoolData(targetRandomBlock);
                if (data != null) {
                    data.coolDown(true);
                }

                this.flowVentLavaFromBomb(targetRandomBlock.getRelative(BlockFace.UP));
            }
        } else {
            // ooze out from the bottom
            Location diff = targetBlock.getLocation().subtract(baseBlock.getLocation());
            diff.setY(0);
            org.bukkit.util.Vector direction = diff.toVector().normalize().multiply((0.2 + Math.random() * 0.8) * radius);

            Block oozeOutTarget = TyphonUtils.getHighestRocklikes(targetBlock.getRelative(direction.getBlockX(), 0, direction.getBlockZ())).getRelative(BlockFace.UP);
            if (oozeOutTarget.getType().isAir()) {
                if (Math.random() < 0.1) {
                    this.flowLava(targetRandomBlock, oozeOutTarget);
                } else {
                    this.flowVentLavaFromBomb(oozeOutTarget);
                }
            }
        }

//        double distance = radius + (Math.random() * 15);
//        double angle = Math.random() * Math.PI * 2;
//        bomb.targetLocation = bomb.launchLocation.add(Math.cos(angle) * distance, 0, Math.sin(angle) * distance);
//
//        this.vent.bombs.customLaunchSpecifiedBomb(bomb, (b) -> {
//            b.launchWithCustomHeight(2 + (int) Math.round(Math.random() * 2));
//            b.bombRadius = 0;
//        });

        return true;
    }

    public VolcanoVent generateFlankVent(Location location, String prefix, Consumer<VolcanoVent> setup) {
        // small percentage of generating a vent
        String name = prefix.isEmpty() ? "parasitic_" : prefix + "_";

        int i = 1;
        while (this.getVolcano().subVents.containsKey(String.format(name + "%03d", i))) {
            i++;
        }

        VolcanoVent vent = new VolcanoVent(
                this.getVolcano(),
                location,
                String.format(name + "%03d", i)
        );
        this.getVolcano().subVents.put(vent.getName(), vent);

        vent.enableKillSwitch = true;
        vent.killAt = System.currentTimeMillis() + (long) (1000 * 60 * (2 + (Math.random() * 3)));
        vent.lavaFlow.settings.silicateLevel = this.settings.silicateLevel;
        vent.erupt.setStyle(VolcanoEruptStyle.STROMBOLIAN);

        if (setup != null) {
            setup.accept(vent);
        }

        vent.start();
        return vent;
    }

    public double getDistanceRatio(Location dest) {
        if (dest == null || this.vent == null) return 1;
        double distance = TyphonUtils.getTwoDimensionalDistance(vent.location, dest);
        int radius = 0;
        if (vent != null) {
            radius = vent.getRadius();
        }

        assert this.vent != null;

        double coneHeight = Math.max(1, this.vent.getSummitBlock().getY() - this.vent.bombs.baseY);

        double distanceFromVent = Math.max(0, Math.min(distance - radius, coneHeight));
        double scaledDistance = distanceFromVent / coneHeight;

        //System.out.println("coneHeight: "+coneHeight+", distance: "+distance+", radius: "+radius+", distanceFromVent: "+distanceFromVent+", scaledDistance: "+scaledDistance);

        return Math.min(scaledDistance, 1);
    }

    public boolean extendLava() {
        // get the terminal blocks (at the end of the flow)
        Block targetBlock = this.getRandomTerminalBlock();
        if (targetBlock == null) return false;

        VolcanoLavaCoolData data = this.getLavaCoolData(targetBlock);
        if (data == null) {
            return false;
        }

        // Chunk-level plumbing dedup (skip for underfill — needs to fill underneath)
        if (!data.isUnderfill) {
            long chunkKey = packXZ(targetBlock.getX() >> 4, targetBlock.getZ() >> 4);
            Block existingTarget = plumbingChunkTargets.get(chunkKey);
            if (existingTarget != null) {
                VolcanoLavaCoolData existingData = this.getLavaCoolData(existingTarget);
                if (existingData != null) {
                    return false;
                }
                plumbingChunkTargets.remove(chunkKey);
            }
        }

        BlockFace target = data.getExtensionTargetBlockFace();
        List<BlockFace> flowableFaces = new ArrayList<>();
        BlockFace[] flowableFacesArray;

        if (data.isUnderfill) {
            // Underfill: include UP if under the volcano slope
            Block highestRock = TyphonUtils.getHighestRocklikes(targetBlock);
            if (highestRock.getY() > targetBlock.getY() && targetBlock.getRelative(BlockFace.UP).getType().isAir()) {
                flowableFacesArray = new BlockFace[] {
                        BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.DOWN, BlockFace.UP
                };
            } else {
                flowableFacesArray = new BlockFace[] {
                        BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.DOWN
                };
            }
        } else {
            flowableFacesArray = new BlockFace[] {
                    BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.NORTH, BlockFace.DOWN
            };
        }

        for (BlockFace flowableFace : flowableFacesArray) {
            if (flowableFace.equals(target)) continue;
            flowableFaces.add(flowableFace);
        }

        for (BlockFace face: flowableFaces) {
            Block flowBlock = targetBlock.getRelative(face);
            Block flowUnderBlock = flowBlock.getRelative(BlockFace.DOWN);

            boolean flowableConditionCheck = flowBlock.isEmpty() || TyphonUtils.containsLiquidWater(flowUnderBlock);
            if (flowableConditionCheck && this.isLavaOKForFlowOnTop(flowUnderBlock)) {
                this.extendLava(flowBlock);
                // Register chunk target (only for non-underfill)
                if (!data.isUnderfill) {
                    long chunkKey = packXZ(targetBlock.getX() >> 4, targetBlock.getZ() >> 4);
                    plumbingChunkTargets.put(chunkKey, flowBlock);
                }
                return true;
            }
        }

        // No flowable direction found — dead end
        if (data.isUnderfill) {
            // Underfill dead end: add flowable neighbors as new underfill targets
            terminalBlocks.remove(targetBlock);
            for (BlockFace face : flowableFacesArray) {
                Block neighbor = targetBlock.getRelative(face);
                if (neighbor.getType().isAir() && !isLavaRegistered(neighbor)) {
                    underfillTargets.add(neighbor);
                }
            }
        } else {
            removeFlowEndBlock(targetBlock);
        }
        return false;
    }

    public void extendLava(Block block) {
        if (this.vent.caldera.isForming()) {
            if (this.vent.caldera.isInCalderaRange(block.getLocation())) {
                return;
            }
        }

        Block fromVent = TyphonUtils.getHighestRocklikes(this.vent.getNearestVentBlock(block.getLocation()));
        if (fromVent.getType().isAir()) {
            flowLava(fromVent, block);
        } else if (TyphonUtils.containsLiquidWater(fromVent)) {

        }
    }

    /**
     * Attempt to flow lava into an underfill target (cave/underground void).
     * Picks from underfillTargets, checks flowability, removes non-flowable targets.
     * @return true if lava was successfully placed at an underfill target
     */
    public boolean flowUnderfillLava() {
        int maxAttempts = Math.min(5, underfillTargets.size());
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            Block target = getRandomFromSet(underfillTargets);
            if (target == null) return false;

            // Check if target is still flowable (air or water)
            if (!target.getType().isAir() && !TyphonUtils.containsLiquidWater(target)) {
                underfillTargets.remove(target);
                continue;
            }

            // Check if already registered
            if (isLavaRegistered(target)) {
                underfillTargets.remove(target);
                continue;
            }

            underfillTargets.remove(target);

            // Use block above as fromBlock (lava flowing downward)
            Block fromBlock = target.getRelative(BlockFace.UP);
            Block source = TyphonUtils.getHighestRocklikes(this.vent.getNearestVentBlock(target.getLocation()));

            Object obj = registerLavaCoolData(source, fromBlock, target, false, -1);
            if (obj instanceof VolcanoLavaCoolData coolData) {
                coolData.isUnderfill = true;
            }
            return true;
        }
        return false;
    }

    /**
     * Periodic cleanup of underfill targets that are no longer valid.
     * Processes a batch per tick to avoid TPS impact.
     */
    public void cleanupUnderfillTargets() {
        if (underfillTargets.isEmpty()) return;

        int batchSize = Math.min(2000, underfillTargets.size());
        Iterator<Block> it = underfillTargets.iterator();
        int removed = 0;
        for (int i = 0; i < batchSize && it.hasNext(); i++) {
            Block target = it.next();
            if (!target.getType().isAir() && !TyphonUtils.containsLiquidWater(target)) {
                it.remove();
                removed++;
            } else if (isLavaRegistered(target)) {
                it.remove();
                removed++;
            }
        }
    }

    public double getLavaStickiness() {
        return (this.settings.silicateLevel - 0.50 / 0.63 - 0.50);
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

        boolean ranTick = false;
        try {
            while (iterator.hasNext()) {
                Map.Entry<Block, VolcanoPillowLavaData> data = iterator.next();
                ranTick = true;

                Block block = data.getKey();
                VolcanoPillowLavaData lavaData = data.getValue();

                if (this.vent.caldera.isForming() && this.vent.caldera.isInCalderaRange(block.getLocation())) {
                    this.queueBlockUpdate(block, Material.WATER);
                    flowedBlocks.add(block);
                    continue;
                }

                // don't flow.
                if (Math.random() < 0.2) {
                    continue;
                }

                lavaData.runTick();
                if (!lavaData.canCooldown() || lavaData.hasFlowed()) {
                    continue;
                }

                // escape flowed
                flowedBlocks.add(block);

                lavaData.markAsFlowed();
                // PILLOW STUFF END

                Block fromBlock = lavaData.fromBlock;
                Block sourceBlock = lavaData.sourceBlock;

                double distance = TyphonUtils.getTwoDimensionalDistance(sourceBlock.getLocation(), block.getLocation());
                if (distance > this.vent.longestFlowLength) {
                    this.vent.longestFlowLength = distance;
                }

                if (fromBlock != null) {
                    Material material =
                        this.getOre(distance);

                    material = material == null ?
                        VolcanoComposition.getExtrusiveRock(this.settings.silicateLevel) :
                        material;

                    this.queueBlockUpdate(block, material, TyphonUtils.getBlockFaceUpdater(fromBlock, block));
                    vent.flushSummitCacheByLocation(block);

                    BlockFace f = block.getFace(lavaData.fromBlock);
                    if (f != null) {
                        TyphonUtils.getBlockFaceUpdater(f).accept(block);
                    }
                }


                Block underBlock = block.getRelative(BlockFace.DOWN);
                distance = TyphonUtils.getTwoDimensionalDistance(sourceBlock.getLocation(), underBlock.getLocation());
                Material material =
                    this.getOre(distance);

                material = material == null ?
                    VolcanoComposition.getExtrusiveRock(this.settings.silicateLevel) :
                    material;


                if (underBlock.getType() == Material.MAGMA_BLOCK) {
                    VolcanoPillowLavaData underLavaData = pillowLavaMap.get(underBlock);
                    if (underLavaData != null) {
                        int underFluidlevel = underLavaData.fluidLevel;
                        int level = lavaData.fluidLevel;
                        int levelSum = underFluidlevel + level;
                        if (levelSum > 8) {
                            flowedBlocks.add(underBlock);
                            lavaData.fluidLevel = levelSum - 8;
                        } else {
                            underLavaData.fluidLevel += level;
                            underLavaData.extensionCount += lavaData.extensionCount;
                            continue;
                        }
                    } else {
                        this.queueBlockUpdate(underBlock, material);
                    }
                } else if (underBlock.isEmpty() || TyphonUtils.containsLiquidWater(underBlock)) {
                    if (!isPillowLavaRegistered(underBlock)) {
                        registerLavaCoolData(
                                lavaData.sourceBlock,
                                lavaData.fromBlock,
                                underBlock,
                                false);
                        if (Math.random() < 0.1) {
                            TyphonUtils.createRisingSteam(lavaData.fromBlock.getLocation().add(0,1,0), 1,2);
                        }
                        continue;
                    }
                    this.queueBlockUpdate(underBlock, material);
                    flowedBlocks.add(underBlock);
                }

                // flow on surface
                int extension = lavaData.extensionCount;
                int level = lavaData.fluidLevel;

                int levelDeductionRate = (this.settings.silicateLevel < 0.63) ? (
                    (Math.random() > this.getLavaStickiness()) ? 1 : 2
                ) : 2;
                level -= levelDeductionRate;

                if (!this.extensionCapable(block.getLocation())) {
                    extension = 0;
                }

                if (level <= 0) {
                    int deductionCount = 1;
                    if (this.vent.isFlowingLava()) {
                        deductionCount += (int) (Math.random() * 2);
                    }

                    extension -= deductionCount;

                    if (extension < 0)  {
                        continue;
                    }

                    level = 8;
                }

                BlockFace primaryFlow = null;
                if (lavaData.fromBlock != null) {
                    primaryFlow = lavaData.fromBlock.getFace(block);
                    if (primaryFlow != BlockFace.DOWN) {
                        primaryFlow = null;
                    }
                }

                BlockFace[] flowableFaces = {
                    BlockFace.NORTH,
                    BlockFace.WEST,
                    BlockFace.EAST,
                    BlockFace.SOUTH,
                };

                for (BlockFace flowableFace : flowableFaces) {
                    Block flowTarget = block.getRelative(flowableFace);

                    boolean isPrimary = false;
                    int levelT = (primaryFlow != null) ?
                            level - levelDeductionRate : level;

                    if (primaryFlow != null) {
                        isPrimary = primaryFlow.getDirection().equals(flowableFace.getDirection());
                        levelT = isPrimary ? level : levelT;
                    }
                    if (level <= 0) continue;

                    double ratio = Math.min(1, Math.max(0, levelT / 6.0));

                    double levelProbability = Math.pow(ratio, 2);
                    if (isPrimary) {
                        levelProbability = Math.pow(ratio, 1.25);
                    }

                    if (Math.random() > levelProbability) {
                        continue;
                    }

                    if (flowTarget.getType().isAir()
                            || TyphonUtils.containsLiquidWater(flowTarget)) {

                        TyphonUtils.removeSeaGrass(flowTarget);
                        if (!isPillowLavaRegistered(flowTarget)) {
                            Object obj;
                            if (vent.getType() == VolcanoVentType.CRATER && vent.getTwoDimensionalDistance(flowTarget.getLocation()) == vent.getRadius()) {
                                // It's on a vent - marking it as "source" block
                                obj = registerLavaCoolData(
                                    flowTarget,
                                    flowTarget,
                                    flowTarget,
                                    false,
                                    extension
                                );
                            } else {
                                obj = registerLavaCoolData(
                                        lavaData.sourceBlock,
                                        lavaData.fromBlock,
                                        flowTarget,
                                        false,
                                        extension);
                            }

                            if (obj instanceof VolcanoLavaCoolData coolData) {
                                coolData.skipNormalLavaFlowLengthCheck = true;
                            } else if (obj instanceof VolcanoPillowLavaData pillowData) {
                                pillowData.fluidLevel = levelT;
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
            removeFlowEndBlock(flowedBlock);
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

    private float getCurrentTPS() {
        try {
            // Prepare for 1.20.4 tick commands
            return Bukkit.getServer().getServerTickManager().getTickRate();
        } catch(Exception e) {
            // fallback for non-tick-command ready versions
            return 20;
        }
    }

    private long prevFlowTime = -1;

    private void plumbLava() {
        if (this.vent != null) {
            if (this.vent.isKillSwitchActive()) {
                this.vent.kill();
                return;
            }
        }

        if (prevFlowTime < 0) {
            prevFlowTime = System.currentTimeMillis();
            return;
        }

        if (!this.settings.flowing) return;

        double msPerTick = 1000 / getCurrentTPS();
        long deltaTime = System.currentTimeMillis() - prevFlowTime;
        double passedTicks = deltaTime / msPerTick;
        double plumbingAmount = this.getCurrentLavaInfluxPerTick() * passedTicks;

        this.queuedLavaInflux += plumbingAmount;
        this.prevFlowTime = System.currentTimeMillis();
    }

    private void autoFlowLava() {
        if (this.vent.caldera.isForming()) return;

        List<Block> ventBlocks = this.vent.getVentBlocks();

        int flowAmount = (int) Math.min(Math.floor(queuedLavaInflux), ventBlocks.size());
        if (flowAmount <= 0) return;

        int actualFlowMax = (int) Math.min(flowAmount, (double) ventBlocks.size() / 3);
        int flowRequests = (int) Math.min(flowAmount, ventBlocks.size());

        List<Block> whereToFlows = vent.requestFlows(flowRequests);
        int flowedBlocks = 0;

        if (this.vent.erupt.getStyle() == VolcanoEruptStyle.LAVA_DOME) {
            int count = whereToFlows.size();
            for (int i = 0; i < count; i++) {
                this.vent.lavadome.flowLava();
                flowedBlocks++;
            }

            if (this.vent.lavadome.isDomeLargeEnough()) {
                if (Math.random() < 0.0001) {
                    // check if queued influx is larger than it can handle
                    int queuedAmount = (flowAmount - flowedBlocks) + (int) prevQueuedLavaInflux;
                    if (queuedAmount > 100) {
                        // the plumbing amount is not clearing out enough
                        // and the lava dome is large enough to explode.
                        this.vent.lavadome.explode();
                    }
                } else if (Math.random() < 0.01) {
                    // the lava dome can ooze out lava.
                    this.vent.lavadome.ooze();
                }
            }
        } else if (this.vent.erupt.getStyle().lavaMultiplier > 0) {
            int flowableCounts = 0;
            for (Block whereToFlow : whereToFlows) {
                if (TyphonUtils.isBlockFlowable(whereToFlow)) {
                    if (whereToFlow.getType() == Material.LAVA) continue;
                    flowableCounts++;
                }
            }

            int actaualFlowable = Math.min(actualFlowMax, flowableCounts);
            for (Block whereToFlow : whereToFlows) {
                if (flowedBlocks >= actaualFlowable) break;

                Block underBlock = whereToFlow.getRelative(BlockFace.DOWN);
                if (underBlock.getType() == Material.LAVA || underBlock.getType() == Material.MAGMA_BLOCK) {
                    continue;
                }

                if (!TyphonUtils.isBlockFlowable(whereToFlow)) {
                    TyphonBlocks.setBlockType(
                            whereToFlow,
                            VolcanoComposition.getExtrusiveRock(this.settings.silicateLevel)
                    );

                    // check if underBlock is registered lava
                    if (isPillowLavaRegistered(underBlock)) {
                        // get pillow lava
                        VolcanoPillowLavaData pillowData = getPillowLavaCoolData(underBlock);
                        if (pillowData != null) {
                            if (!pillowData.canCooldown()) {
                                // ignore.
                                continue;
                            }
                        }
                    } else if (isNormalLavaRegistered(underBlock)) {
                        // check if underBlock lava can not flow anywhere
                        boolean canFlow = TyphonUtils.isBlockFlowable(underBlock);

                        if (!canFlow) {
                            // cooldown underBlock.
                            VolcanoLavaCoolData coolData = getLavaCoolData(underBlock);
                            if (coolData != null) {
                                if (!coolData.tickPassed()) {
                                    this.extendLava();
                                    continue;
                                }
                                coolData.coolDown(true);
                            }
                        }

                        continue;
                    }

                    if (Math.random() > 0.01) {
                        if (this.vent.erupt.getStyle().flowsLava()) {
                            this.extendLava();
                        }
                        continue;
                    }

                    // whereToFlow is blocked. use upper one
                    whereToFlow = whereToFlow.getRelative(BlockFace.UP);
                }


                flowedBlocks++;

                // fallback routine
                flowLava(whereToFlow);

                if (whereToFlow.getY() > highestY) {
                    highestY = whereToFlow.getY();
                    if (TyphonBlueMapUtils.getBlueMapAvailable()) {
                        TyphonBlueMapUtils.updateVolcanoVentMarkerHeight(this.vent);
                    }
                }
            }


            if (flowedBlocks > 0) {
                TyphonSounds.getRandomLavaThroat().play(
                        ventBlocks.get(0).getLocation(),
                        SoundCategory.BLOCKS,
                        2f,
                        1f
                );
            }
        }

        if (flowAmount - flowedBlocks > 0) {
            int leftOvers = flowAmount - flowedBlocks;
            plumbedBlocksSinceRateCalc += leftOvers;
            leftOvers = Math.min(10, leftOvers);

            // all of them into extension
            for (int i = 0; i < leftOvers; i++) {
                if (Math.random() < 0.1) {
                    if (this.vent.surtseyan.isSurtseyan()) {
                        this.handleSurtseyan();
                    } else if (this.vent.erupt.getStyle().bombMultiplier > 0) {
                        if (Math.random() > 1.0/this.vent.erupt.getStyle().bombMultiplier) {
                            VolcanoBomb bomb = this.vent.bombs.tryConeBuildingBomb();
                            if (bomb != null) {
                                bomb.land();
                            }
                        }
                    }

                    if (Math.random() < rootlessConeProbability) {
                        if (doPlumbingToRootlessCone()) {
                            continue;
                        }
                    } else {
                        // 70% chance to try underfill first
                        if (!underfillTargets.isEmpty() && Math.random() < 0.7) {
                            if (this.flowUnderfillLava()) { successfulPlumbsSinceRateCalc++; continue; }
                        }
                        if (this.extendLava()) successfulPlumbsSinceRateCalc++;
                    }
                } else {
                    // 70% chance to try underfill first
                    if (!underfillTargets.isEmpty() && Math.random() < 0.7) {
                        if (this.flowUnderfillLava()) { successfulPlumbsSinceRateCalc++; continue; }
                    }
                    if (this.extendLava()) successfulPlumbsSinceRateCalc++;
                }
            }
        }

        prevQueuedLavaInflux = queuedLavaInflux;
        queuedLavaInflux = 0;

        // Update flow rate tracking
        flowedBlocksSinceRateCalc += flowedBlocks;
        long now = System.currentTimeMillis();
        if (lastFlowRateCalcTime == 0) {
            lastFlowRateCalcTime = now;
        } else if (now - lastFlowRateCalcTime >= 1000) {
            flowedBlocksPerSecond = flowedBlocksSinceRateCalc;
            flowedBlocksSinceRateCalc = 0;
            plumbedBlocksPerSecond = plumbedBlocksSinceRateCalc;
            plumbedBlocksSinceRateCalc = 0;
            successfulPlumbsPerSecond = successfulPlumbsSinceRateCalc;
            successfulPlumbsSinceRateCalc = 0;
            lastFlowRateCalcTime = now;
        }

        // Update cached counts (safe for web thread reads)
        updateCachedCounts();
    }

    public void handleSurtseyan() {
        this.handleSurtseyan(Math.min(this.vent.craterRadius / 2, 10));
    }

    public void handleSurtseyan(int count) {
        if (this.vent.surtseyan.isSurtseyan()) {
            this.vent.surtseyan.eruptSurtseyan(count);
        }
    }

    private void runChunkCooldownTick(Map<Block, VolcanoLavaCoolData> lavaCoolMap) {
        List<Block> removeTargets = new ArrayList<Block>();

        for (Map.Entry<Block, VolcanoLavaCoolData> entry : lavaCoolMap.entrySet()) {
            Block block = entry.getKey();
            VolcanoLavaCoolData coolData = entry.getValue();

            if (coolData.tickPassed()) {
                coolData.coolDown();
                removeTargets.add(block);
                continue;
            }
            coolData.tickPass();
        }

        for (Block removeTarget : removeTargets) {
            lavaCoolMap.remove(removeTarget);
            removeFlowEndBlock(removeTarget);
        }
    }

    private Map<Block, VolcanoLavaCoolData> addCacheToLavaCoolMap(Chunk chunk) {
        Map<Block, VolcanoLavaCoolData> lavaCoolMap = lavaCools.get(chunk);
        if (lavaCoolMap == null) {
            lavaCoolMap = new HashMap<>();
            lavaCools.put(chunk, lavaCoolMap);
        }

        Map<Block, VolcanoLavaCoolData> cache = cachedCools.get(chunk);
        if (cache == null) return lavaCoolMap;

        for (Map.Entry<Block, VolcanoLavaCoolData> entry : cache.entrySet()) {
            Block block = entry.getKey();
            VolcanoLavaCoolData coolData = entry.getValue();

            lavaCoolMap.put(block, coolData);
        }

        cache.clear();
        return lavaCoolMap;
    }


    private void runCooldownTick() {
        for (Map.Entry<Chunk, Map<Block, VolcanoLavaCoolData>> entry : cachedCools.entrySet()) {
            addCacheToLavaCoolMap(entry.getKey());
        }

        boolean isEmpty = true;
        for (Map.Entry<Chunk, Map<Block, VolcanoLavaCoolData>> entry : lavaCools.entrySet()) {
            if (!entry.getValue().isEmpty()) isEmpty = false;
            TyphonScheduler.run(entry.getKey(), () -> {
                runChunkCooldownTick(entry.getValue());
            });
        }

        if (isEmpty) {
            flowingVents.remove(this.vent);
        } else {
            if (!flowingVents.contains(this.vent)) {
                flowingVents.add(this.vent);
            }
        }
    }

    public void cooldownAll() {
        for (Map.Entry<Chunk, Map<Block, VolcanoLavaCoolData>> coolEntry : lavaCools.entrySet()) {
            for (Map.Entry<Block, VolcanoLavaCoolData> entry : coolEntry.getValue().entrySet()) {
                Block block = entry.getKey();
                VolcanoLavaCoolData coolData = entry.getValue();
                coolData.setQuickCoolKillSwitch();

                TyphonBlocks.setBlockType(
                        block,
                        coolData.material
                );
            }
            coolEntry.getValue().clear();
        }
        lavaCools.clear();

        for (Map.Entry<Chunk, Map<Block, VolcanoLavaCoolData>> coolEntry : cachedCools.entrySet()) {
            for (Map.Entry<Block, VolcanoLavaCoolData> entry : coolEntry.getValue().entrySet()) {
                Block block = entry.getKey();
                VolcanoLavaCoolData coolData = entry.getValue();
                coolData.setQuickCoolKillSwitch();

                TyphonBlocks.setBlockType(
                        block,
                        coolData.material
                );
            }
            coolEntry.getValue().clear();
        }
        cachedCools.clear();
        clearFlowEndBlocks();

        for (Map.Entry<Block, VolcanoPillowLavaData> entry : pillowLavaMap.entrySet()) {
            Block block = entry.getKey();
            TyphonBlocks.setBlockType(
                    block,
                    VolcanoComposition.getExtrusiveRock(settings.silicateLevel)
            );
        }
        pillowLavaMap.clear();

        for (Map.Entry<Block, VolcanoPillowLavaData> entry : cachedPillowLavaMap.entrySet()) {
            Block block = entry.getKey();
            TyphonBlocks.setBlockType(
                    block,
                    VolcanoComposition.getExtrusiveRock(settings.silicateLevel)
            );
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

    public static int flowed = 5;
    public static int delayFlowed = 7;

    public static void importConfig(JSONObject configData) {
        VolcanoLavaFlowSettings settings = new VolcanoLavaFlowSettings();
        settings.importConfig(configData);

        flowed = settings.flowed;
        delayFlowed = settings.delayFlowed;
    }
}

