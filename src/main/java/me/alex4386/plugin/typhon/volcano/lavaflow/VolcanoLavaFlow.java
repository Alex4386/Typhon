package me.alex4386.plugin.typhon.volcano.lavaflow;

import me.alex4386.plugin.typhon.TyphonBlueMapUtils;
import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonSounds;
import me.alex4386.plugin.typhon.TyphonUtils;
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
import org.bukkit.block.data.Directional;
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
import java.util.concurrent.atomic.AtomicLong;


public class VolcanoLavaFlow implements Listener {
    public VolcanoVent vent = null;

    public static List<VolcanoVent> flowingVents = new ArrayList<>();

    public List<Chunk> lavaFlowChunks = new ArrayList<>();
    public Map<Block, VolcanoLavaCoolData> lavaCoolHashMap = new HashMap<>();
    public Map<Block, VolcanoLavaCoolData> cachedLavaCoolHashMap = new HashMap<>();
    public Map<Block, VolcanoPillowLavaData> pillowLavaMap = new HashMap<>();
    public Map<Block, VolcanoPillowLavaData> cachedPillowLavaMap = new HashMap<>();

    private int lavaFlowScheduleId = -1;
    private int lavaCoolScheduleId = -1;
    private int lavaInfluxScheduleId = -1;
    private int queueScheduleId = -1;
    public VolcanoLavaFlowSettings settings = new VolcanoLavaFlowSettings();

    public boolean registeredEvent = false;

    private int highestY = Integer.MIN_VALUE;

    private double hawaiianBaseY = Double.NEGATIVE_INFINITY;
    private double thisMaxFlowLength = 0;


    public long lastQueueUpdatesPerSecond = 0;
    public long lastQueueUpdatedPerSecondAt = 0;

    public long lastQueueUpdates = 0;

    // temporary queue to store Block and Material to update
    private static Queue<Map.Entry<Block, Material>> blockUpdateQueue = new LinkedList<>();

    private int configuredLavaInflux = -1;
    private double queuedLavaInflux = 0;

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

            int influxRate =  Math.max(1, Math.min((int) (lavaFlowableBlocks * Math.random()), 100));
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
        blockUpdateQueue.add(new AbstractMap.SimpleEntry<>(block, material));
    }
    public long unprocessedQueueBlocks() {
        return blockUpdateQueue.size();
    }
    public long getProcessedBlocksPerSecond() {
        if (lastQueueUpdatedPerSecondAt == 0) return 0;

        long passedMs = (System.currentTimeMillis() - lastQueueUpdatedPerSecondAt);
        if (passedMs < 50) return lastQueueUpdates * 20;

        return (long) (lastQueueUpdatesPerSecond / (passedMs / (double) 1000));
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
        double flowLength = 0;
        if (this.shouldFlowOverLeeve()) {
            double leeveY = this.vent.averageLeeveHeight();
            baseY = Math.round((leeveY + baseY) / 2);
            flowLength = this.vent.craterRadius * 2;
        }

        this.hawaiianBaseY = baseY;
        this.thisMaxFlowLength = flowLength;
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
                    TyphonPlugin.plugin
                            .getServer()
                            .getScheduler()
                            .scheduleSyncRepeatingTask(
                                    TyphonPlugin.plugin,
                                    () -> {
                                        if (settings.flowing) this.plumbLava();
                                        this.autoFlowLava();
                                    },
                                    0L,
                                    (long) 1);
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
                                    (long) 1);
        }
        if (queueScheduleId == -1) {
            this.vent.volcano.logger.log(
                    VolcanoLogClass.LAVA_FLOW,
                    "Intializing lava cooldown scheduler of VolcanoLavaFlow for vent "
                            + vent.getName());
            queueScheduleId =
                    TyphonPlugin.plugin
                            .getServer()
                            .getScheduler()
                            .scheduleSyncRepeatingTask(
                                    TyphonPlugin.plugin,
                                    this::runQueueWithinTick,
                                    0L,
                                    1L);
        }
    }

    public void runQueueWithinTick() {
        // get starting time
        long startTime = System.currentTimeMillis();
        long count = 1;

        boolean shouldResetAfter = false;
        if (startTime - lastQueueUpdatedPerSecondAt > 1000) {
            shouldResetAfter = true;
        }

        while (true) {
            Map.Entry<Block, Material> entry = blockUpdateQueue.poll();
            if (entry != null) {
                Block block = entry.getKey();
                Material material = entry.getValue();
                block.setType(material);
            } else {
                break;
            }

            if (count % 1000 == 0) {
                // if time is up, break
                if (System.currentTimeMillis() - startTime > 50) break;
            }

            count++;
        }

        lastQueueUpdates = count;
        if (shouldResetAfter) {
            lastQueueUpdatedPerSecondAt = startTime;
            lastQueueUpdatesPerSecond = count;
        } else {
            lastQueueUpdatesPerSecond += count;
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
        if (lavaInfluxScheduleId != -1) {
            this.vent.volcano.logger.log(
                    VolcanoLogClass.LAVA_FLOW,
                    "Shutting down lava influx scheduler of VolcanoLavaFlow for vent "
                            + vent.getName());
            TyphonPlugin.plugin.getServer().getScheduler().cancelTask(lavaInfluxScheduleId);
            lavaInfluxScheduleId = -1;
        }
        if (lavaCoolScheduleId != -1) {
            this.vent.volcano.logger.log(
                    VolcanoLogClass.LAVA_FLOW,
                    "Shutting down lava cooldown scheduler of VolcanoLavaFlow for vent "
                            + vent.getName());
            TyphonPlugin.plugin.getServer().getScheduler().cancelTask(lavaCoolScheduleId);
            lavaCoolScheduleId = -1;
        }
        if (queueScheduleId != -1) {
            this.vent.volcano.logger.log(
                    VolcanoLogClass.LAVA_FLOW,
                    "Shutting down lava cooldown queue handler of VolcanoLavaFlow for vent "
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
        /*
        Block block = event.getBlock();
        if (block.getType() == Material.COBBLESTONE || block.getType() == Material.STONE) {
            BlockFace[] faces = { BlockFace.UP, BlockFace.WEST, BlockFace.EAST, BlockFace.NORTH, BlockFace.SOUTH};
            for (BlockFace face : faces) {
                Block fromBlock = block.getRelative(face);
                if (fromBlock.getType() == Material.LAVA) {
                    VolcanoLavaCoolData data = lavaCoolHashMap.get(fromBlock);
                    if (data == null) data = cachedLavaCoolHashMap.get(fromBlock);
                    if (data != null) {
                        createEffectOnLavaSeaEntry(block);
                        cachedPillowLavaMap.put(block, new VolcanoPillowLavaData(data.flowedFromVent, data.source, data.fromBlock, data.runExtensionCount));
                        block.setType(VolcanoComposition.getExtrusiveRock(settings.silicateLevel));
                        break;
                    }
                } 
            }
        }
        */
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
                    event.setCancelled(true);
                    return;
                }
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

                if (distance > this.thisMaxFlowLength) {
                    this.thisMaxFlowLength = distance;
                }

                if (!data.skipNormalLavaFlowLengthCheck) {
                    if (distance > vent.longestNormalLavaFlowLength) {
                        vent.longestNormalLavaFlowLength = distance;
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

                vent.record.addEjectaVolume(1);

                // force load chunk.
                if (!vent.location.getChunk().isLoaded()) vent.location.getChunk().load();
            } else if (data.isBomb) {
                if (this.vent != null && data.source != null) {
                    if (!this.vent.isInVent(toBlock.getLocation())) {
                        if (data.flowLimit >= 0) {
                            int limit = data.flowLimit == 0 ? this.vent.craterRadius : data.flowLimit;
                            if (this.vent.getType() == VolcanoVentType.CRATER && Math.floor(this.vent.getTwoDimensionalDistance(data.source.getLocation())) >= limit) {
                                double distance = this.vent.getTwoDimensionalDistance(toBlock.getLocation());
                                int y = toBlock.getY();

                                int targetY = (int) (this.vent.getSummitBlock().getY() - (distance / this.vent.bombs.distanceHeightRatio()));

                                if (y > targetY) {
                                    event.setCancelled(true);
                                    return;
                                }
                            } else {
                                if (TyphonUtils.getTwoDimensionalDistance(data.source.getLocation(), toBlock.getLocation()) > data.flowLimit) {
                                    event.setCancelled(true);
                                    return;
                                }
                            }
                        }
                    }
                }
            }

            Block underToBlock = toBlock.getRelative(BlockFace.DOWN);

            if (!lavaFlowChunks.contains(toBlock.getChunk())) {
                lavaFlowChunks.add(toBlock.getLocation().getChunk());
                toBlock.getLocation().getChunk().setForceLoaded(true);
            }

            // load toBlock chunk
            Chunk toBlockChunk = toBlock.getLocation().getChunk();
            if (!toBlockChunk.isLoaded()) {
                toBlockChunk.load();
            }

            Block underUnderToBlock = underToBlock.getRelative(BlockFace.DOWN);
            boolean underIsAir = underToBlock.getType().isAir();

            if (!underIsAir && underToBlock.getType() != Material.LAVA) {
                getVolcano().metamorphism.metamorphoseBlock(underToBlock);
            }

            boolean fillUnderUnder = true;
            if (data.isBomb) {
                fillUnderUnder = Math.random() < 0.1;
            }

            if (underIsAir && underUnderToBlock.getType().isAir() && fillUnderUnder) {
                underToBlock.setType(data.material);

                // bifurcate lava
                registerLavaCoolData(data.source, underToBlock, underUnderToBlock, data.isBomb);
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

            Object obj = this.registerLavaCoolData(data.source, block, toBlock, data.isBomb, extensionCount);
            if (obj instanceof VolcanoLavaCoolData coolData) {
                if (data.skipNormalLavaFlowLengthCheck) {
                    coolData.skipNormalLavaFlowLengthCheck = true;
                }

                if (data.isBomb) {
                    coolData.flowLimit = data.flowLimit;
                }
            } else if (obj instanceof VolcanoPillowLavaData pillowData) {
                Block targetBlock = TyphonUtils.getHighestRocklikes(pillowData.fromBlock);
                this.createEffectOnLavaSeaEntry(targetBlock);
            }
        }
    }

    private VolcanoLavaCoolData getLavaCoolData(Block block) {
        VolcanoLavaCoolData coolData = lavaCoolHashMap.get(block);
        if (coolData == null) {
            coolData = cachedLavaCoolHashMap.get(block);
        }

        return coolData;
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

    public boolean isLavaRegistered(Block block) {
        return this.isNormalLavaRegistered(block) || this.isPillowLavaRegistered(block);
    }

    private void registerLavaCoolData(Block block, boolean isBomb, int extension) {
        this.registerLavaCoolData(block, block, block, isBomb, extension);
    }

    private void registerLavaCoolData(Block source, Block fromBlock, Block block, boolean isBomb) {
        this.registerLavaCoolData(source, fromBlock, block, isBomb, -1);
    }

    public Object registerLavaCoolData(
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

        return this.registerLavaCoolData(source, fromBlock, block, isBomb, extension, isUnderWater);
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
            boolean isUnderWater) {
        Object result = null;
        Material targetMaterial =
                isBomb && !isUnderWater
                        ? VolcanoComposition.getBombRock(this.settings.silicateLevel)
                        : VolcanoComposition.getExtrusiveRock(this.settings.silicateLevel);
        
        double distance = TyphonUtils.getTwoDimensionalDistance(source.getLocation(), block.getLocation());

        Material ore = this.getOre(distance);
        if (ore != null) {
            Material oreified = this.oreifyMaterial(targetMaterial, ore);
            if (oreified != null) targetMaterial = oreified;
        }

        if (!isUnderWater) {
            block.setType(Material.LAVA);
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

                cachedLavaCoolHashMap.put(
                        block,
                        coolData);
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

                cachedLavaCoolHashMap.put(
                        block,
                        coolData);
            }
        } else {
            VolcanoPillowLavaData lavaData = cachedPillowLavaMap.get(block);
            if (lavaData == null) {
                this.queueBlockUpdate(block, Material.MAGMA_BLOCK);
                VolcanoPillowLavaData coolData = new VolcanoPillowLavaData(
                        this.vent, source, fromBlock, extension);

                // reset extensions since lava can not travel long underwater
                cachedPillowLavaMap.put(block, coolData);
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
            vent.record.addEjectaVolume(1);
        }

        return result;
    }

    public Block getRandomLavaBlock() {
        List<Block> target = getRandomLavaBlocks(1);
        return target.get(0);
    }

    public boolean hasAnyLavaFlowing() {
        int counts = lavaCoolHashMap.size();
        counts += pillowLavaMap.size();

        return counts > 0;
    }

    public List<Block> getRandomLavaBlocks(int count) {
        List<Block> lavaBlocks = (ArrayList<Block>) new ArrayList<>(lavaCoolHashMap.keySet());
        List<Block> pillowBlocks = (ArrayList<Block>) new ArrayList<>(pillowLavaMap.keySet());

        Collections.shuffle(lavaBlocks);
        Collections.shuffle(pillowBlocks);

        List<Block> targetBlocks = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            Block block = null;
            if (pillowBlocks.size() > 0) {
                block = pillowBlocks.get(0);
            }
            
            if (Math.random() < 0.7 || block == null) {
                if (lavaBlocks.size() > 0)
                    block = lavaBlocks.get(0);                
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
        this.flowLavaFromBomb(bomb);

        VolcanoLavaCoolData data = this.getLavaCoolData(bomb);
        if (data != null) {
            // make it flow indefinitely
            data.flowLimit = -1;
            //System.out.println("New bomb lava flow from summit detected. setting flowLimit to infinite @ "+TyphonUtils.blockLocationTostring(bomb));
        }
    }

    public void flowLavaFromBomb(Block bomb) {
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

        this.registerLavaCoolData(bomb, true, 0);
    }

    public double getRootlessConeRadius(int height) {
        return (1 + Math.sqrt(3)) * height;
    }

    public boolean tryRootlessCone() {
        // check the volcano has grown big enough (normalLavaLength >= 100)
        if (this.vent.longestNormalLavaFlowLength < 100) return false;

        // check if the lava sillicalevel isn't too high
        if (this.settings.silicateLevel > 0.53) return false;

        double radius = 100 + (Math.random() * (this.vent.longestNormalLavaFlowLength - 100));
        double angle = Math.random() * Math.PI * 2;

        if (this.vent.isCaldera()) {
            if (Math.random() < 0.8) {
                radius = Math.min(radius, this.vent.calderaRadius);
            }
        }

        Location location = this.vent.location;
        Location targetLocation = location.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);

        this.addRootlessCone(targetLocation);

        return true;
    }

    public int rootlessConeHeight(int height, double offset) {
        double radius = getRootlessConeRadius(height);
        if (offset < radius) return (int) offset;

        double distance = offset - radius;
        double deduct = distance / Math.sqrt(3);

        int result = (int) (height - deduct);
        return Math.max(result, 0);
    }

    public void addRootlessCone(Location location) {
        Block baseBlock = TyphonUtils.getHighestRocklikes(location);

        int height = 1 + (int) (Math.random() * 2);
        double radiusRaw = getRootlessConeRadius(height);
        int radius = (int) Math.ceil(radiusRaw);

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int targetHeight = rootlessConeHeight(height, Math.sqrt(x * x + z * z));
                int targetY = baseBlock.getY() + targetHeight;

                Block baseBlockAt = TyphonUtils.getHighestRocklikes(baseBlock.getRelative(x, 0, z));
                if (targetY - baseBlockAt.getY() > 4) {
                    for (int y = 1; y < 4; y++) {
                        Block targetBlock = baseBlockAt.getRelative(0, y, 0);
                        if (targetBlock.getType().isAir()) {
                            Material material = VolcanoComposition.getBombRock(this.settings.silicateLevel);
                            queueBlockUpdate(targetBlock, material);
                        }
                    }
                } else {
                    int target = targetY - baseBlock.getY();
                    for (int y = 1; y <= target; y++) {
                        Block targetBlock = baseBlockAt.getRelative(0, y, 0);
                        if (targetBlock.getType().isAir()) {
                            Material material = VolcanoComposition.getBombRock(this.settings.silicateLevel);
                            queueBlockUpdate(targetBlock, material);
                        }
                    }
                }
            }
        }

        // launch bombs
        Location launchLocation = baseBlock.getRelative(0, Math.max(1, height - 1), 0).getLocation();
        int targetRadius = (int) (radiusRaw * 1.5);
        int launchCount = (int) (Math.random() * 7) + 3;

        for (int i = 0; i < launchCount; i++) {
            double randomAngle = Math.PI * 2 * Math.random();
            Location targetLocation = launchLocation.clone().add(Math.cos(randomAngle) * targetRadius, 0, Math.sin(randomAngle) * targetRadius);

            VolcanoBomb bomb = this.vent.bombs.generateBomb(targetLocation);
            bomb.launchLocation = launchLocation;
            this.vent.bombs.launchSpecifiedBomb(bomb);
        }
    }

    public boolean extendLava() {
        double stickiness = ((this.settings.silicateLevel - 0.45) / (0.53 - 0.45));
        double safeRange = Math.max(this.thisMaxFlowLength * 0.8, (this.vent.longestFlowLength * 7 / 10.0));

        if (this.vent.calderaRadius >= 0) {
            if (Math.random() < (this.vent.calderaRadius / safeRange)) {
                safeRange = this.vent.calderaRadius * 7 / 10.0;
            } else {
                return false;
            }
        }
        double minimumSafeRange = this.vent.getType() == VolcanoVentType.CRATER ? this.vent.craterRadius : 0;
        double minimumSafeOffset = 20;

        if (this.vent.getType() == VolcanoVentType.CRATER && this.vent.erupt.getStyle() == VolcanoEruptStyle.STROMBOLIAN) {
            // In this case, lava should ooze out from side, cause this is cinder cone.
            if (Math.random() > this.settings.gasContent && this.hawaiianBaseY >= this.vent.location.getWorld().getMinHeight()) {
                double ratio = this.vent.bombs.distanceHeightRatio();
                double distance = ratio * (this.vent.getSummitBlock().getY() - this.hawaiianBaseY) - minimumSafeOffset;

                minimumSafeRange = (int) (Math.max(distance, 0) + this.vent.getRadius());
                safeRange = minimumSafeOffset + minimumSafeRange + this.vent.getRadius();
            }
        }

        double calculatedMinimum = minimumSafeRange + minimumSafeOffset;
        double calculatedRangeMax = calculatedMinimum + safeRange;

        if (safeRange > 0) {
            Block coreBlock = this.vent.getCoreBlock();
            Block targetBlock, highestBlock = null;

            if (stickiness < 1) {
                targetBlock = TyphonUtils.getFairRandomBlockInRange(coreBlock, (int) calculatedMinimum, (int) calculatedRangeMax);
                highestBlock = TyphonUtils.getHighestRocklikes(targetBlock.getLocation()).getRelative(BlockFace.UP);
    
                // slope check
                double distance = TyphonUtils.getTwoDimensionalDistance(coreBlock.getLocation(), targetBlock.getLocation());
    
                int coreY = TyphonUtils.getHighestRocklikes(coreBlock).getY();
                double stepLength = (1 - stickiness) * 20;

                double targetYOffset = (distance / stepLength);
                double targetY = coreY - targetYOffset;

                int roundedTargetY = (int) Math.round(targetY);
                if (highestBlock.getY() <= roundedTargetY) {
                    this.extendLava(highestBlock);
                }
            } else {
                if (Math.random() < 0.7) {
                    if (this.vent.longestNormalLavaFlowLength > 50) {
                        targetBlock = TyphonUtils.getFairRandomBlockInRange(coreBlock, (int) calculatedMinimum, (int) this.vent.longestFlowLength);
                        highestBlock = TyphonUtils.getHighestRocklikes(targetBlock.getLocation()).getRelative(BlockFace.UP);
                        this.extendLava(highestBlock);
                    } 
                } else {
                    if (this.vent.longestFlowLength > 50) {
                        targetBlock = TyphonUtils.getFairRandomBlockInRange(coreBlock, (int) calculatedMinimum, (int) this.vent.longestFlowLength);
                        highestBlock = TyphonUtils.getHighestRocklikes(targetBlock.getLocation()).getRelative(BlockFace.UP);
                        this.extendLava(highestBlock);    
                    } 
                }
            }

            if (highestBlock != null) {
                return true;
            }
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

    public double getLavaStickiness() {
        return (this.settings.silicateLevel - 0.48 / 0.63 - 0.48);
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

                if (fromBlock != null) {
                    Material material = 
                        this.getOre(distance);
                        
                    material = material == null ?
                        VolcanoComposition.getExtrusiveRock(this.settings.silicateLevel) :
                        material;

                    this.queueBlockUpdate(block, material);
                    vent.flushSummitCacheByLocation(block);

                    BlockData bd = block.getBlockData();
                    BlockFace f = block.getFace(lavaData.fromBlock);
            
                    if (bd instanceof Directional) {
                        Directional d = (Directional) bd;
                        if (f != null && f.isCartesian()) {
                            d.setFacing(f);
                        }
                        block.setBlockData(d);
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
        if (prevFlowTime < 0) {
            prevFlowTime = System.currentTimeMillis();
            return;
        }

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

        int actualFlowCounts = (int) Math.min(flowAmount, (double) ventBlocks.size() / 3);
        List<Block> whereToFlows = vent.requestFlows(actualFlowCounts);

        int flowedBlocks = 0;
        if (this.vent.erupt.getStyle().lavaMultiplier > 0) {
            if (!ventBlocks.isEmpty()) {
                TyphonSounds.LAVA_ERUPTION.play(
                        ventBlocks.get(0).getLocation(),
                        SoundCategory.BLOCKS,
                        2f,
                        0f
                );
            }
            for (Block whereToFlow : whereToFlows) {
                Block underBlock = whereToFlow.getRelative(BlockFace.DOWN);
                if (underBlock.getType() == Material.LAVA || underBlock.getType() == Material.MAGMA_BLOCK) {
                    continue;
                }

                if (!TyphonUtils.isBlockFlowable(whereToFlow)) {
                    whereToFlow.setType(VolcanoComposition.getExtrusiveRock(this.settings.silicateLevel));

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
                                coolData.coolDown();
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
        } else if (this.vent.erupt.getStyle() == VolcanoEruptStyle.LAVA_DOME) {
            int count = whereToFlows.size();
            for (int i = 0; i < count; i++) {
                this.vent.lavadome.flowLava();
                flowedBlocks++;
            }

            if (this.vent.lavadome.isDomeLargeEnough()) {
                if (Math.random() < 0.1) {
                    // the lava dome is large enough to explode.
                    this.vent.lavadome.explode();
                } else if (Math.random() < 0.3) {
                    // the lava dome can ooze out lava.
                    this.vent.lavadome.ooze();
                }
            }
        }
        
        if (flowAmount - flowedBlocks > 0) {
            int leftOvers = flowAmount - flowedBlocks;
            leftOvers = Math.min(10, leftOvers);

            // all of them into extension
            for (int i = 0; i < leftOvers; i++) {
                if (Math.random() < 0.1) {
                    if (this.vent.surtseyan.isSurtseyan()) {
                        this.handleSurtseyan();
                    } else if (this.vent.erupt.getStyle().bombMultiplier > 0) {
                        if (Math.random() > 1.0/this.vent.erupt.getStyle().bombMultiplier) {
                            VolcanoBomb bomb = this.vent.bombs.generateConeBuildingBomb();
                            if (bomb != null) {
                                bomb.land();
                            }
                        }
                    }

                    if (Math.random() < 0.2) {
                        if (tryRootlessCone()) {
                            i += 30;
                            continue;
                        }
                    }

                    this.extendLava();
                }
            }
        }

        queuedLavaInflux = 0;
    }

    public boolean consumeLavaInflux(double amount) {
        if (queuedLavaInflux < amount) {
            return false;
        }

        queuedLavaInflux -= amount;
        return true;
    }

    public void handleSurtseyan() {
        this.handleSurtseyan(Math.min(this.vent.craterRadius / 2, 10));
    }

    public void handleSurtseyan(int count) {
        if (this.vent.surtseyan.isSurtseyan()) {
            this.vent.surtseyan.eruptSurtseyan(count);
        }
    }

    private void runCooldownTick() {
        if (lavaCoolHashMap.isEmpty()) {
            if (flowingVents.contains(this.vent)) {
                flowingVents.remove(this.vent);
            }
        }

        if (!flowingVents.contains(this.vent)) {
            flowingVents.add(this.vent);
        }

        Iterator<Map.Entry<Block, VolcanoLavaCoolData>> iterator =
                lavaCoolHashMap.entrySet().iterator();
        List<Block> removeTargets = new ArrayList<Block>();

        try {
            while (iterator.hasNext()) {
                Map.Entry<Block, VolcanoLavaCoolData> entry = iterator.next();
                VolcanoLavaCoolData coolData = entry.getValue();
                
                if (coolData.tickPassed()) {
                    removeTargets.add(entry.getKey());
                }
                coolData.tickPass();

            }
        } catch (ConcurrentModificationException e) {
            e.printStackTrace();
        }

        //this.getVolcano().logger.log(VolcanoLogClass.LAVA_FLOW, "processed "+limits+" lava flows");

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

    public static int flowed = 5;
    public static int delayFlowed = 7;

    public static void importConfig(JSONObject configData) {
        VolcanoLavaFlowSettings settings = new VolcanoLavaFlowSettings();
        settings.importConfig(configData);

        flowed = settings.flowed;
        delayFlowed = settings.delayFlowed;
    }
}

