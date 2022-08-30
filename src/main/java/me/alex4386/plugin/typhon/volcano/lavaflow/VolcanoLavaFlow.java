package me.alex4386.plugin.typhon.volcano.lavaflow;

import me.alex4386.plugin.typhon.TyphonBlueMapUtils;
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
import org.bukkit.block.data.Directional;
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

    public Map<Block, Block> lavaTerminals = new HashMap<>();
    public Map<Block, Block> cachedLavaTerminals = new HashMap<>();

    private int lavaFlowScheduleId = -1;
    private int lavaCoolScheduleId = -1;
    public VolcanoLavaFlowSettings settings = new VolcanoLavaFlowSettings();

    public boolean registeredEvent = false;

    private int highestY = Integer.MIN_VALUE;

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

    public void createEffectOnLavaSeaEntry(Block block) {
        if (Math.random() < 0.3) {
            block
                .getWorld()
                .playSound(
                        block.getLocation(), Sound.BLOCK_LAVA_EXTINGUISH, 1f, 0f);
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
                data.forceCoolDown();
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

                if (distance > vent.longestNormalLavaFlowLength && !data.skipNormalLavaFlowLengthCheck) {
                    vent.longestNormalLavaFlowLength = distance;
                    trySave = true;
                }

                if (trySave) {
                    vent.getVolcano().trySave(false);
                }

                vent.record.addEjectaVolume(1);

                // force load chunk.
                if (!vent.location.getChunk().isLoaded()) vent.location.getChunk().load();
            } else if (data.isBomb) {
                if (this.vent != null && data.source != null) {
                    if (TyphonUtils.getTwoDimensionalDistance(data.source.getLocation(), toBlock.getLocation()) > 10) {
                        event.setCancelled(true);
                        return;
                    }
                }
            }

            Block underToBlock = toBlock.getRelative(BlockFace.DOWN);
            VolcanoLavaCoolData underData = lavaCoolHashMap.get(underToBlock);

            if (underData != null && !underData.tickPassed()) {
                if (underData.fromBlock != toBlock) {
                    underData.forceCoolDown();
                }
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

                getVolcano().metamorphism.metamorphoseBlock(nearByBlock);
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
            VolcanoLavaCoolData coolData = this.cachedLavaCoolHashMap.get(toBlock);
            
            if (coolData != null) {
                if (data.skipNormalLavaFlowLengthCheck) {
                    coolData.skipNormalLavaFlowLengthCheck = true;
                }

                if (this.vent != null && isFlowBlocked) {
                    if (coolData != null) {
                        coolData.forceCoolDown();
    
                        // rush up lava
                        Block flowUp = toBlock.getRelative(BlockFace.UP);
                        if (flowUp.getType().isAir()) {
                            this.registerLavaCoolData(data.source, toBlock, flowUp, data.isBomb);
                            if (data.skipNormalLavaFlowLengthCheck) {
                                VolcanoLavaCoolData newCoolData = this.cachedLavaCoolHashMap.get(toBlock);
                                if (newCoolData != null) {
                                    newCoolData.skipNormalLavaFlowLengthCheck = coolData.skipNormalLavaFlowLengthCheck;
                                }
                            }
                        }
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
        
        double distance = TyphonUtils.getTwoDimensionalDistance(source.getLocation(), block.getLocation());

        for (Map.Entry<Block, Block> entry : lavaTerminals.entrySet()) {
            if (entry.getValue().equals(block)) {
                lavaTerminals.remove(entry.getKey());
                break;
            }
        }

        Material ore = this.getOre(distance);
        if (ore != null) {
            Material oreified = this.oreifyMaterial(targetMaterial, ore);
            if (oreified != null) targetMaterial = oreified;
        }

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

    public void handleLavaTerminal(Block source, Block block) {
        if (!this.vent.volcano.manager.isInAnyVent(block)) {
            Block previousTerminal = lavaTerminals.get(source);
            if (previousTerminal != null) {
                if (block.getLocation().distance(source.getLocation()) <= previousTerminal.getLocation().distance(source.getLocation())) {
                    return;
                }
            }

            cachedLavaTerminals.put(source, block);
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

        // extend lava flow instead of creating new flows.
        if (this.settings.silicateLevel < 0.58) {
            double fitted = Math.max(0.41, Math.min(this.settings.silicateLevel, 0.57)) / (0.57 - 0.40);
            if (Math.random() > fitted) {
                if (this.lavaTerminals.size() > 0) {
                    this.extendLava();
                    return;
                }
            }
        }

        for (Block whereToFlow : whereToFlows) {
            VolcanoLavaCoolData coolData = lavaCoolHashMap.get(whereToFlow);
            VolcanoLavaCoolData underData = lavaCoolHashMap.get(whereToFlow.getRelative(BlockFace.DOWN));
            underData = (underData == null) ? cachedLavaCoolHashMap.get(whereToFlow.getRelative(BlockFace.DOWN)) : underData;
            
            if (underData != null) {
                if (!underData.tickPassed()) {
                    underData.coolDown();
                    this.extendLava();
                    continue;
                }
            }

            if (coolData == null || coolData.tickPassed()) {
                flowLava(whereToFlow);
                if (whereToFlow.getY() > highestY) {
                    highestY = whereToFlow.getY();
                    if (TyphonBlueMapUtils.getBlueMapAvailable()) {
                        TyphonBlueMapUtils.updateVolcanoVentMarkerHeight(this.vent);
                    }
                }
            }
        }
    }

    public void flowLava(Block whereToFlow) {
        this.flowLava(whereToFlow, whereToFlow);
    }

    public void flowLava(Block source, Block currentBlock) {
        World world = currentBlock.getWorld();

        world.spawnParticle(Particle.SMOKE_LARGE, currentBlock.getLocation(), 10);
        world.spawnParticle(Particle.LAVA, currentBlock.getLocation(), 10);
        world.playSound(currentBlock.getLocation(), Sound.BLOCK_LAVA_POP, 1f, 1f);

        registerLavaCoolData(source, currentBlock, currentBlock, false);

        if (this.vent != null && this.vent.erupt != null) {
            this.vent.erupt.updateVentConfig();
        }

    }

    public void flowLavaFromBomb(Block bomb) {
        double zeroFocused = VolcanoMath.getZeroFocusedRandom();
        this.registerLavaCoolData(bomb, true, (int) (zeroFocused * 2));
    }

    public void extendLava() {
        double stickiness = ((this.settings.silicateLevel - 0.45) / (0.53 - 0.45));

        if (this.lavaTerminals.size() > 0 && Math.random() > stickiness) {
            Map<Block, Block> blocks = new HashMap<>();

            for (Map.Entry<Block, Block> entry : this.lavaTerminals.entrySet()) {
                Block targetBlock = entry.getValue();
                double distance = TyphonUtils.getTwoDimensionalDistance(targetBlock.getLocation(), this.vent.getNearestVentBlock(targetBlock.getLocation()).getLocation());

                if (distance >= vent.longestNormalLavaFlowLength * 0.9) {
                    blocks.put(entry.getKey(), entry.getValue());
                }
            }

            if (blocks.size() > 0) {
                int idx = (int) Math.floor(blocks.size() * Math.random());

                Block target = new ArrayList<Block>(blocks.keySet()).get(idx);
                this.extendLava(target);
                
                this.lavaTerminals.remove(target);
            }
        }
    }

    public void extendLava(Block block) {
        Block fromVent = TyphonUtils.getHighestRocklikes(this.vent.getNearestVentBlock(block.getLocation()));

        flowLava(fromVent, block);
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

                // don't flow.
                if (Math.random() < 0.2) {
                    continue;
                }

                flowedBlocks.add(block);

                Block fromBlock = lavaData.fromBlock;
                Block sourceBlock = lavaData.sourceBlock;

                double distance = TyphonUtils.getTwoDimensionalDistance(sourceBlock.getLocation(), block.getLocation());

                if (fromBlock != null) {
                    Material material = 
                        this.getOre(distance);
                        
                    material = material == null ?
                        VolcanoComposition.getExtrusiveRock(this.settings.silicateLevel) :
                        material;

                    block.setType(material);

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
                    underBlock.setType(material);
                } else if (underBlock.getType().isAir() || TyphonUtils.containsLiquidWater(underBlock)) {
                    if (pillowLavaMap.get(underBlock) == null) {
                        registerLavaCoolData(
                                lavaData.sourceBlock,
                                lavaData.fromBlock,
                                underBlock,
                                false);
                        continue;
                    }
                    underBlock.setType(material);
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
                    if (extension < 0)  {
                        continue;
                    }

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

                            VolcanoLavaCoolData coolData = cachedLavaCoolHashMap.get(flowTarget);
                            if (coolData != null) {
                                coolData.skipNormalLavaFlowLengthCheck = true;
                            } else {
                                pillowData = cachedPillowLavaMap.get(flowTarget);
                                if (pillowData != null) {
                                    pillowData.fluidLevel = level;
                                }
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

        Iterator<Map.Entry<Block, Block>> iterator2 = cachedLavaTerminals.entrySet().iterator();

        while(iterator2.hasNext()) {
            Map.Entry<Block, Block> entry = iterator2.next();
            this.lavaTerminals.put(entry.getKey(), entry.getValue());
            iterator2.remove();
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

