package me.alex4386.plugin.typhon.volcano.vent;

import me.alex4386.plugin.typhon.*;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.ash.VolcanoAsh;
import me.alex4386.plugin.typhon.volcano.bomb.VolcanoBomb;
import me.alex4386.plugin.typhon.volcano.bomb.VolcanoBombs;
import me.alex4386.plugin.typhon.volcano.dome.VolcanoLavaDome;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoErupt;
import me.alex4386.plugin.typhon.volcano.explosion.VolcanoExplosion;
import me.alex4386.plugin.typhon.volcano.landslide.VolcanoLandslide;
import me.alex4386.plugin.typhon.volcano.lavaflow.VolcanoLavaFlow;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.log.VolcanoVentRecord;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoCircleOffsetXZ;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.json.simple.JSONObject;

import de.bluecolored.bluemap.api.markers.POIMarker;

import java.io.IOException;
import java.util.*;

public class VolcanoVent {
    public static int defaultVentRadius = 20;

    public boolean enabled = true;

    public Volcano volcano;
    public String name = null;
    private VolcanoVentStatus status = VolcanoVentStatus.DORMANT;

    private VolcanoVentType type = VolcanoVentType.CRATER;
    public VolcanoVentGenesis genesis = VolcanoVentGenesis.POLYGENETIC;

    public Location location;
    public int craterRadius = defaultVentRadius;

    public double fissureAngle = Math.random() * Math.PI * 2;
    public int fissureLength = 10;
    public int maxFissureLength = 200;

    public double longestFlowLength = 0.0;
    public double longestNormalLavaFlowLength = 0.0;

    public double currentFlowLength = 0.0;
    public double currentNormalLavaFlowLength = 0.0;

    public double longestAshNormalFlowLength = 0.0;
    public double currentAshNormalFlowLength = 0.0;

    public double calderaRadius = 0.0;
    private Block cachedSummitBlock = null;
    private long cachedSummitBlockLastSync = 0;

    private List<Block> cachedVentBlocks = null;
    private long cachedVentBlocksLastSync = 0;

    public List<Block> coreBlocks = null;
    private List<Block> leeveBlocks = null;

    public VolcanoBombs bombs = new VolcanoBombs(this);
    public VolcanoExplosion explosion = new VolcanoExplosion(this);
    public VolcanoTremor tremor = new VolcanoTremor(this);
    public VolcanoLavaFlow lavaFlow = new VolcanoLavaFlow(this);
    public VolcanoVentRecord record = new VolcanoVentRecord(this);
    public VolcanoErupt erupt = new VolcanoErupt(this);
    public VolcanoAsh ash = new VolcanoAsh(this);
    public VolcanoLavaDome lavadome = new VolcanoLavaDome(this);
    public VolcanoVentCaldera caldera = new VolcanoVentCaldera(this);
    public VolcanoLandslide landslide = new VolcanoLandslide(this);

    public VolcanoVentSurtseyan surtseyan = new VolcanoVentSurtseyan(this);
    public VolcanoVentBuilder builder = new VolcanoVentBuilder(this);

    // get update via VolcanoAutoStart?
    public boolean autoStyleUpdate = false;
    public boolean enableSuccession = true;
    public double successionProbability = 0.05;
    public double successionTreeProbability = 0.05;
    public double fullPyroclasticFlowProbability = 0.0001;

    public boolean enableKillSwitch = false;
    public long killAt = 0;

    long lastVentShuffle = 0;

    public VolcanoVent(Volcano volcano) {
        this.volcano = volcano;
        this.location = volcano.location;
        this.name = "main";
    }

    public VolcanoVent(Volcano volcano, Location location, String name) {
        this.volcano = volcano;
        this.location = location;
        this.name = name;
    }

    public VolcanoVent(Volcano volcano, JSONObject configData) {
        this.volcano = volcano;
        this.importConfig(configData);
    }

    public Volcano getVolcano() {
        return this.volcano;
    }

    public VolcanoVentStatus getStatus() {
        return this.status;
    }

    public Block getLowestCoreBlock() {
        List<Block> coreBlocks = this.getCoreBlocks();
        Block lowestCoreBlock = coreBlocks.get(0);

        for (Block coreBlock : coreBlocks) {
            if (coreBlock.getY() < lowestCoreBlock.getY()) {
                lowestCoreBlock = coreBlock;
            }
        }

        return lowestCoreBlock;
    }

    public double getBasinLength() {
        double longestFlow = Math.max(this.getVolcanicRadius(), (this.getSummitBlock().getY() - this.location.getWorld().getSeaLevel()) * Math.sqrt(3));
        return Math.max(longestFlow, this.craterRadius);
    }

    public void resetCurrentMetrics() {
        this.currentFlowLength = 0;
        this.currentNormalLavaFlowLength = 0;
        this.currentAshNormalFlowLength = 0;
    }

    public void setStatus(VolcanoVentStatus status) {
        this.status = status;

        if (TyphonBlueMapUtils.getBlueMapAvailable()) {
            TyphonBlueMapUtils.updateVolcanoVentIcon(this);
        }
    }

    public List<Player> getPlayersInRange() {
        List<Player> players = new ArrayList<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld().getUID().equals(this.location.getWorld().getUID())) {
                Location loc = player.getLocation();
                if (this.isBombAffected(loc) || this.isInLavaFlow(loc) || this.isInVent(loc)) {
                    players.add(player);
                }
            }
        }

        return players;
    }

    public void reset() {
        this.maxFissureLength = 0;
        this.longestFlowLength = 0;
        this.longestNormalLavaFlowLength = 0;
        this.bombs.maxDistance = 0;
        this.getVolcano().trySave(true);
    }

    public void kill() {
        volcano.logger.log(VolcanoLogClass.VENT, "Killing vent " + name+" due to kill switch.");
        this.shutdown();
        this.quickCool();
        this.delete();
    }

    public void quickCool() {
        this.bombs.shutdown();
        this.lavaFlow.cooldownAll();
    }

    public void initialize() {
        if (this.isKillSwitchActive()) {
            this.kill();
            return;
        }

        volcano.logger.log(VolcanoLogClass.VENT, "Starting up vent " + name);
        this.getVentBlocks();

        // bombs don't need initialization
        ash.initialize();
        explosion.initialize();
        lavaFlow.initialize();
        tremor.initialize();
        lavadome.initialize();
        bombs.initialize();
        landslide.initialize();
        builder.initialize();

        volcano.logger.log(VolcanoLogClass.VENT, "Started" + " up vent " + name);
    }

    public VolcanoVentType getType() {
        return this.type;
    }

    public void shutdown() {
        volcano.logger.log(VolcanoLogClass.VENT, "Shutting down vent " + name);

        // bombs don't need shutdown
        ash.shutdown();
        explosion.shutdown();
        lavaFlow.shutdown();
        tremor.shutdown();

        record.endEjectaTrack();
        volcano.trySave(true);

        bombs.shutdown();
        lavadome.shutdown();
        landslide.shutdown();
        builder.shutdown();

        volcano.logger.log(VolcanoLogClass.VENT, "Shutted down vent " + name);
    }

    public void delete() {
        this.stop();
        this.shutdown();

        if (volcano != null) {
            // delete subvent file
            if (this.isMainVent()) {
                try {
                    volcano.delete();
                } catch (IOException e) {}
            } else {
                volcano.subVents.remove(name);
                volcano.dataLoader.deleteSubVentConfig(this.getName());

                // transfer eruption ejecta data to main vent
                if (volcano.mainVent != null) {
                    volcano.mainVent.record.ejectaVolumeList.addAll(this.record.ejectaVolumeList);
                }

                TyphonBlueMapUtils.removeVolcanoVentMarker(this);
            }
        }
    }

    public boolean isCaldera() {
        return this.calderaRadius > 0;
    }

    public String getName() {
        return this.name == null ? ChatColor.GOLD + "main" + ChatColor.RESET : this.name;
    }

    public boolean isCacheInitialized() {
        return this.cachedVentBlocks != null;
    }

    public void removeInvalidVentBlocks() {
        double averageY = this.averageVentHeight();

        for (Block block : this.getVentBlocks()) {
            if (block.getY() > averageY + 5) {
                Block highest = TyphonUtils.getHighestRocklikes(block);
                Block underHighest = highest.getRelative(BlockFace.DOWN);
                if (underHighest.getType().isAir()) {
                    TyphonBlocks.setBlockType(highest, Material.AIR);
                    averageY = this.averageVentHeight();
                }
            }
        }
    }

    public double getVolcanicRadius() {
        return Math.max(this.longestNormalLavaFlowLength, this.longestAshNormalFlowLength);
    }

    public List<Block> getVentBlocksScaffold() {
        List<Block> scaffoldBlocks = new ArrayList<>();

        if (type == VolcanoVentType.CRATER) {
            scaffoldBlocks =
                    VolcanoMath.getHollowCircle(this.location.getBlock(), craterRadius);
        } else if (type == VolcanoVentType.FISSURE) {
            /*
            double rightAngledAngle = (Math.PI / 2) + this.fissureAngle;
            int xRadiusOffset = (int) Math.cos(rightAngledAngle) * this.craterRadius;
            int zRadiusOffset = (int) Math.sin(rightAngledAngle) * this.craterRadius;

            List<Block> ventBlocksWithCrater = new ArrayList<Block>();

            Block fromBlock = this.location.getBlock().getRelative(xRadiusOffset, 0, zRadiusOffset);
            ventBlocksWithCrater.addAll(
                    VolcanoMath.getLine(fromBlock, this.fissureAngle, this.fissureLength));

            fromBlock = this.location.getBlock().getRelative(-xRadiusOffset, 0, -zRadiusOffset);
            ventBlocksWithCrater.addAll(
                    VolcanoMath.getLine(fromBlock, this.fissureAngle, this.fissureLength));
            */

            List<Block> ventBlocksWithCrater = new ArrayList<Block>();

            Block fromBlock = this.location.getBlock();
            ventBlocksWithCrater.addAll(
                    VolcanoMath.getLine(fromBlock, this.fissureAngle, this.fissureLength));

            scaffoldBlocks = ventBlocksWithCrater;
        } else {
            // fallback to crater
            scaffoldBlocks =
                    VolcanoMath.getHollowCircle(this.location.getBlock(), craterRadius);
        }

        return scaffoldBlocks;
    }

    public List<Block> getVentBlocks() {
        boolean isFirstLoad = false;

        // Debug
        // TyphonUtils.stackTraceMe();

        if (!this.isCacheInitialized()) {
            isFirstLoad = true;
            this.volcano.logger.log(
                    VolcanoLogClass.VENT, "Calculating vent blocks of " + this.getName() + "...");

            this.cachedVentBlocks = this.getVentBlocksScaffold();
            this.volcano.logger.log(
                    VolcanoLogClass.VENT,
                    "Calculated vent blocks for vent "
                            + this.getName()
                            + ". "
                            + this.cachedVentBlocks.size()
                            + " blocks found.");

            World world = null;

            // estimate chunk count
            List<VolcanoCircleOffsetXZ> chunks = new ArrayList<>();
            for (Block block : this.cachedVentBlocks) {
                world = block.getWorld();
                int chunkX = block.getLocation().getBlockX() >> 4;
                int chunkZ = block.getLocation().getBlockZ() >> 4;

                boolean chunkFound = false;
                for (VolcanoCircleOffsetXZ chunk : chunks) {
                    if (chunk.x == chunkX && chunk.z == chunkZ) {
                        chunkFound = true;
                        break;
                    }
                }

                if (!chunkFound) {
                    chunks.add(new VolcanoCircleOffsetXZ(chunkX, chunkZ));
                }
            }
            this.volcano.logger.log(
                    VolcanoLogClass.VENT,
                    chunks.size()
                            + " chunks are required to load vent of "
                            + this.getName()
                            + "...");

            if (world != null) {
                int i = 1;
                for (VolcanoCircleOffsetXZ chunkData : chunks) {
                    this.volcano.logger.log(
                            VolcanoLogClass.VENT,
                            "Loading vent chunk of "
                                    + this.getName()
                                    + "... ("
                                    + i
                                    + "/"
                                    + chunks.size()
                                    + ")");
                    Chunk chunk = world.getChunkAt((int) chunkData.x, (int) chunkData.z);
                    if (!chunk.isLoaded()) chunk.load();
                    i++;
                }
            }
        }

        if (this.coreBlocks == null) {
            List<Block> coreBlocks = new ArrayList<Block>();
            TyphonPlugin.logger.log(
                    VolcanoLogClass.CORE, "Calculating core blocks of " + this.getName() + "...");

            if (type == VolcanoVentType.FISSURE) {
                coreBlocks.addAll(
                        VolcanoMath.getLine(
                                this.location.getBlock(), this.fissureAngle, this.fissureLength));
            } else {
                coreBlocks.add(this.location.getBlock());
            }

            this.coreBlocks = coreBlocks;
        }

        // TyphonPlugin.logger.log(VolcanoLogClass.CORE, "Debug: vent Block size:
        // "+this.cachedVentBlocks.size());

        if (isFirstLoad)
            this.volcano.logger.log(
                    VolcanoLogClass.VENT,
                    "Calculating highest points of vent blocks of " + this.getName() + "...");
        
        
        if (this.cachedVentBlocksLastSync != 0) {
            // check if summit has updated from since:
            boolean isCacheExpired = false;

            if (this.cachedSummitBlock == null) {
                isCacheExpired = true;
            } else {
                if (this.cachedSummitBlockLastSync > this.cachedVentBlocksLastSync) isCacheExpired = true;
                if (this.cachedVentBlocksLastSync < System.currentTimeMillis() - 500) { 
                    isCacheExpired = true;
                }
            }

            // if not expired, update it.
            if (!isCacheExpired) return this.cachedVentBlocks;
        }
            
        List<Block> newCachedVentBlocks = new ArrayList<>();
        for (Block block : this.cachedVentBlocks) {
            if (block.getType() == Material.LAVA) {
                newCachedVentBlocks.add(block);
                continue;
            }

            newCachedVentBlocks.add(TyphonUtils.getHighestRocklikes(block.getLocation()));
            // newCachedVentBlocks.add(TyphonUtils.getHighestLocation(block.getLocation()).getBlock());
        }

        if (isFirstLoad)
            this.volcano.logger.log(
                    VolcanoLogClass.VENT,
                    "Vent block calculation of " + this.getName() + " complete.");

        this.cachedVentBlocks = newCachedVentBlocks;
        this.cachedSummitBlockLastSync = System.currentTimeMillis();

        // Everyday I'm shuffl'in
        Collections.shuffle(this.cachedVentBlocks);

        return this.cachedVentBlocks;
    }

    public double getHeatValue(Location loc) {
        double distance = this.getTwoDimensionalDistance(loc);
        double killZone = this.getRadius();
        double pillowRatio = 0.2;

        double multiplier = this.status.getScaleFactor() <= VolcanoVentStatus.DORMANT.getScaleFactor() ? Math.sqrt(this.status.getScaleFactor()) : 1;
        if (distance <= killZone) {
            return multiplier;
        }

        double basinLength = this.getBasinLength();
        double deltaFromNormalLava = (this.longestFlowLength - basinLength);

        double correctedLavaLength = basinLength + (deltaFromNormalLava * pillowRatio);
        double correctedDistance = distance;

        if (correctedDistance > basinLength) {
            double delta = correctedDistance - basinLength;
            correctedDistance = basinLength + (pillowRatio * delta);
        }

        double converted = (correctedDistance - killZone) / (correctedLavaLength - killZone);

        if (converted >= 1) return 0;
        double reversed = Math.max(1 - converted, 0); 
        
        return Math.pow(reversed, 1.5) * multiplier;
    }

    public int getRadius() {
        return this.craterRadius;
    }

    public void flushCache() {
        this.cachedVentBlocks = null;
        this.coreBlocks = null;
        this.leeveBlocks = null;
        this.flushSummitCache();
    }

    public void setRadius(int ventRadius) {
        this.craterRadius = ventRadius;
        this.flushCache();
    }

    public void setType(VolcanoVentType type) {
        this.type = type;
        this.flushCache();
    }

    public double averageVentHeight() {
        int totalY = 0;
        for (Block block : this.cachedVentBlocks) {
            Location loc = block.getLocation();
            loc.setY(block.getWorld().getMaxHeight());

            int highestY = TyphonUtils.getHighestRocklikes(loc.getBlock()).getY();
            totalY += highestY;
        }

        return (double) totalY / this.cachedVentBlocks.size();
    }

    public List<Block> getLeeveBlockScffolds() {
        double rightAngledAngle = (Math.PI / 2) + this.fissureAngle;
        int xRadiusOffset = (int) Math.cos(rightAngledAngle) * this.craterRadius;
        int zRadiusOffset = (int) Math.sin(rightAngledAngle) * this.craterRadius;

        List<Block> ventBlocksWithCrater = new ArrayList<Block>();

        Block fromBlock = this.location.getBlock().getRelative(xRadiusOffset, 0, zRadiusOffset);
        ventBlocksWithCrater.addAll(
                VolcanoMath.getLine(fromBlock, this.fissureAngle, this.fissureLength));

        fromBlock = this.location.getBlock().getRelative(-xRadiusOffset, 0, -zRadiusOffset);
        ventBlocksWithCrater.addAll(
                VolcanoMath.getLine(fromBlock, this.fissureAngle, this.fissureLength));

        return ventBlocksWithCrater;            
    }

    public List<Block> getLeeveBlocks() {
        List<Block> target = this.leeveBlocks;

        if (target == null) {
            target = this.getLeeveBlockScffolds();
        }

        for (int i = 0; i < target.size(); i++) {
            Block block = TyphonUtils.getHighestRocklikes(target.get(i));
            target.set(i, block);
        }

        this.leeveBlocks = target;
        return target;
    }

    public double averageLeeveHeight() {
        if (this.type == VolcanoVentType.FISSURE) {
            double total = 0;
            List<Block> leeveBlocks = this.getLeeveBlocks();
            for (Block block : leeveBlocks) {
                total += block.getY();
            }            

            return total / leeveBlocks.size();
        } else {
            return this.averageVentHeight();
        }
    }

    public Block lowestVentBlock() {
        int lowest = Integer.MAX_VALUE;
        Block lowestBlock = this.cachedVentBlocks.get(0);

        for (Block block : this.cachedVentBlocks) {
            int y = block.getY();
            if (y < lowest) {
                lowestBlock = block;
            }
        }

        return lowestBlock;
    }

    public int lowestVentHeight() {
        return this.lowestVentBlock().getY();
    }

    public Block selectFlowVentBlock() {
        return selectFlowVentBlock(true);
    }

    public List<Block> selectFlowVentBlocks(int count) {
        return selectFlowVentBlocks(true, count);
    }

    public Block selectCoreBlock() {
        Collections.shuffle(this.coreBlocks);

        Block block = this.coreBlocks.get(0);
        return block;
    }

    public Block selectCraterBlock() {
        double direction = Math.PI * Math.random() * 2;
        double length = Math.random() * this.craterRadius;

        int xOffset = (int) (Math.sin(direction) * length);
        int zOffset = (int) (Math.cos(direction) * length);

        Block block = this.selectCoreBlock();
        Block target = block.getRelative(xOffset, 0, zOffset);

        return TyphonUtils.getHighestRocklikes(target);
    }

    public List<Block> selectFlowVentBlocks(boolean evenFlow, int count) {
        if (count <= 0) return new ArrayList<Block>();

        Random random = new Random();
        List<Block> selectedBlocks = new ArrayList<>();

        List<Block> ventBlocks = this.getVentBlocks();
        Collections.shuffle(ventBlocks);

        if (evenFlow) {
            boolean useLowest = false;
            useLowest =
                    Math.random()
                            < ((this.lavaFlow.settings.silicateLevel - 0.22)
                                    * (1 + this.lavaFlow.settings.silicateLevel));

            double averageVentHeight = this.averageVentHeight();

            if (useLowest) {
                Block lowestVent = this.lowestVentBlock();
                if (Math.round(lowestVent.getY()) < averageVentHeight) {
                    ventBlocks.sort(Comparator.comparingInt(Block::getY));

                    for (Block block : ventBlocks) {
                        if (this.lavaFlow.isLavaOKForFlow(block)) {
                            if (!selectedBlocks.contains(block)) {
                                Block blockTop = TyphonUtils.getHighestRocklikes(block);
                                selectedBlocks.add(blockTop);
                                if (selectedBlocks.size() == count) return selectedBlocks;
                            }
                        }
                    }
                }
            }

            Collections.shuffle(ventBlocks);

            int minimumTolerantHeight;
            if (this.type == VolcanoVentType.FISSURE)
                minimumTolerantHeight = (int) averageVentHeight - 3;
            else minimumTolerantHeight = (int) averageVentHeight - ((int) craterRadius / 7);

            for (Block block : ventBlocks) {
                int y = block.getY();

                if (y < minimumTolerantHeight && !this.lavaFlow.isLavaOKForFlow(block)) {
                    if (Math.random() < 0.2f) continue;
                    if (!selectedBlocks.contains(block)) {
                        Block blockTop = TyphonUtils.getHighestRocklikes(block);
                        selectedBlocks.add(blockTop);
                        if (selectedBlocks.size() == count) return selectedBlocks;
                    }
                }
            }
        }

        for (int i = 0; i < count; i++) {
            Block block = ventBlocks.get(random.nextInt(ventBlocks.size()));
            if (!selectedBlocks.contains(block) && this.lavaFlow.isLavaOKForFlow(block)) {
                Block blockTop = TyphonUtils.getHighestRocklikes(block);
                selectedBlocks.add(blockTop);
            }
        }

        return selectedBlocks;
    }

    public Block selectFlowVentBlock(boolean evenFlow) {
        List<Block> targetBlocks = this.selectFlowVentBlocks(evenFlow, 1);
        if (targetBlocks.isEmpty()) {
            List<Block> ventBlocks = this.getVentBlocks();
            if (ventBlocks.isEmpty()) {
                return TyphonUtils.getHighestRocklikes(this.location.getBlock());
            }

            int randomIdx = (int) (Math.random() * ventBlocks.size());
            return TyphonUtils.getHighestRocklikes(ventBlocks.get(randomIdx));
        }

        return targetBlocks.get((int) (Math.random() * targetBlocks.size()));
    }

    public Block requestFlow() {
        Block ventBlock = this.selectFlowVentBlock().getRelative(BlockFace.UP);
        return ventBlock;
    }

    public List<Block> requestFlows(int count) {
        List<Block> ventBlocks = this.selectFlowVentBlocks(count);
        List<Block> lavaFlowBlocks = new ArrayList<>();

        for (Block ventBlock : ventBlocks) {
            lavaFlowBlocks.add(ventBlock.getRelative(BlockFace.UP));
        }
        return lavaFlowBlocks;
    }

    public boolean isBlockPotentialSummitUpdate(Block block) {
        Block coreBlock = this.getNearestCoreBlock(block.getLocation());
        return TyphonUtils.getTwoDimensionalDistance(coreBlock.getLocation(), block.getLocation()) < (this.craterRadius * 1.3);
    }

    public void flushSummitCache() {
        this.cachedSummitBlock = null;
    }

    public void flushSummitCacheByLocation(Block block) {
        if (this.isBlockPotentialSummitUpdate(block)) {
            this.flushSummitCache();
        }
    }

    public Block getSummitBlock() {
        if (this.cachedSummitBlock != null) {
            if (this.cachedSummitBlockLastSync > System.currentTimeMillis() - 500) {
                return this.cachedSummitBlock;                
            }
        }

        List<Block> cachedVentBlocks = this.getVentBlocks();
        if (this.type == VolcanoVentType.FISSURE) {
            cachedVentBlocks.addAll(this.getLeeveBlocks());
        }

        int highestY = 0;
        Block highestBlock = null;

        for (Block block : cachedVentBlocks) {
            if (block.getY() > highestY || highestY == 0) {
                highestY = block.getY();
                highestBlock = block;
            }
        }

        if (highestBlock == null) {
            highestBlock = cachedVentBlocks.get(0);
        }

        // update base, since it is now an island.
        int seaLevel = highestBlock.getWorld().getSeaLevel();
        if (bombs.getBaseY() < seaLevel) {
            if (highestBlock.getY() > seaLevel + 2) {
                bombs.baseY = seaLevel;
                volcano.trySave();
            }
        }

        this.cachedSummitBlock = highestBlock;
        this.cachedSummitBlockLastSync = System.currentTimeMillis();
        return highestBlock;
    }

    public void cool() {
        List<Block> cachedVentBlocks = this.getVentBlocks();
        Iterator<Block> iterator = cachedVentBlocks.iterator();

        while (iterator.hasNext()) {
            Block block = iterator.next();

            if (this.erupt.getStyle().lavaMultiplier < 1) {
                TyphonBlocks.setBlockType(
                        block,
                        VolcanoComposition.getBombRock(this.lavaFlow.settings.silicateLevel, 0));
            } else {
                TyphonBlocks.setBlockType(
                        block,
                        VolcanoComposition.getExtrusiveRock(this.lavaFlow.settings.silicateLevel)
                );
            }
        }
    }

    public boolean isInVent(Location loc) {
        return this.getTwoDimensionalDistance(loc) <= this.craterRadius;
    }

    public boolean isInLavaFlow(Location loc) {
        return this.getTwoDimensionalDistance(loc) <= this.longestFlowLength;
    }

    public boolean isBombAffected(Location loc) {
        return this.getTwoDimensionalDistance(loc) <= this.bombs.maxDistance;
    }

    public List<Block> getCoreBlocks() {
        if (this.coreBlocks == null) {
            this.getVentBlocks();
        }

        return this.coreBlocks;
    }

    public Block getCoreBlock() {
        List<Block> coreBlocks = this.getCoreBlocks();
        int idx = (int) (coreBlocks.size() * Math.random());

        return coreBlocks.get(idx);
    }

    public Block getNearestCoreBlock(Location loc) {
        if (this.coreBlocks == null) {
            this.getVentBlocks();
        }

        double lowest = Double.POSITIVE_INFINITY;
        Iterator<Block> iterator = this.coreBlocks.iterator();

        Block nearestBlock = null;

        while (iterator.hasNext()) {
            Block block = iterator.next();
            double currentDistance = TyphonUtils.getTwoDimensionalDistance(block.getLocation(), loc);

            if (currentDistance < lowest) {
                lowest = currentDistance;
                nearestBlock = block;
            }
        }

        return nearestBlock;
    }

    public Block getNearestVentBlock(Location loc) {
        if (this.cachedVentBlocks == null) {
            this.getVentBlocks();
        }

        double lowest = Double.POSITIVE_INFINITY;
        Iterator<Block> iterator = this.cachedVentBlocks.iterator();

        Block nearestBlock = null;

        while (iterator.hasNext()) {
            Block block = iterator.next();
            double currentDistance = TyphonUtils.getTwoDimensionalDistance(block.getLocation(), loc);

            if (currentDistance < lowest) {
                lowest = currentDistance;
                nearestBlock = block;
            }
        }

        return nearestBlock;
    }

    public double getTwoDimensionalDistance(Location loc) {
        Block block = this.getNearestCoreBlock(loc);

        return TyphonUtils.getTwoDimensionalDistance(loc, block.getLocation());
    }

    public double getThreeDimensionalDistance(Location loc) {
        if (loc.getWorld() != this.location.getWorld()) return Double.MAX_VALUE;

        Block coreBlock = this.getNearestCoreBlock(loc);
        return Math.sqrt(Math.pow(coreBlock.getX() - loc.getX(), 2) + Math.pow(coreBlock.getZ() - loc.getZ(), 2) + Math.pow(coreBlock.getY() - loc.getY(), 2));
    }

    public void explode() {
        explosion.explode();
    }

    public void explode(int bombCount) {
        explosion.explode(bombCount);
    }

    public void teleport(Entity entity) {
        teleport(entity, true);
    }

    public void teleport(Entity entity, boolean unstuck) {
        Location location = this.location;
        if (unstuck) location = TyphonUtils.getHighestLocation(location).add(0, 2, 0);
        entity.teleport(location);
    }

    public boolean isStarted() {
        return this.isFlowingLava() || this.isExploding();
    }

    public boolean isFlowingLava() {
        return lavaFlow.settings.flowing;
    }

    public boolean isExploding() {
        return explosion.running;
    }

    public void start() {
        this.erupt.start();
        this.getVolcano().trySave(true);
    }

    public void stop() {
        this.erupt.stop();
        this.getVolcano().trySave(true);
    }

    public boolean isMainVent() {
        return this.name == null || this.name.equals("main");
    }

    public String getVentConfigFilename() {
        return this.name + ".json";
    }

    public boolean isKillSwitchActive() {
        return this.enableKillSwitch && this.killAt > 0 && this.killAt < System.currentTimeMillis();
    }

    public void importConfig(JSONObject configData) {
        this.enabled = (boolean) configData.get("enabled");
        this.type = VolcanoVentType.fromString((String) configData.get("type"));
        this.status = VolcanoVentStatus.getStatus((String) configData.get("status"));
        this.location =
                TyphonUtils.deserializeLocationForJSON((JSONObject) configData.get("location"));
        this.craterRadius = (int) (long) configData.get("craterRadius");
        this.fissureAngle = (double) configData.get("fissureAngle");
        this.fissureLength = (int) (long) configData.get("fissureLength");
        this.maxFissureLength = (int) (long) configData.get("maxFissureLength");
        this.bombs.importConfig((JSONObject) configData.get("bombs"));
        this.explosion.importConfig((JSONObject) configData.get("explosion"));
        this.lavaFlow.importConfig((JSONObject) configData.get("lavaFlow"));
        this.record.importConfig((JSONObject) configData.get("record"));
        this.erupt.importConfig((JSONObject) configData.get("erupt"));
        this.lavadome.importConfig((JSONObject) configData.get("lavaDome"));
        this.longestFlowLength = (double) configData.get("longestFlowLength");
        this.longestNormalLavaFlowLength = (double) configData.get("longestNormalLavaFlowLength");
        this.longestAshNormalFlowLength = (double) configData.getOrDefault("longestAshNormalFlowLength", 0.0);
        this.genesis = VolcanoVentGenesis.getGenesisType((String) configData.get("genesis"));
        this.calderaRadius = (double) configData.getOrDefault("calderaRadius" , -1.0);
        this.autoStyleUpdate = (boolean) configData.getOrDefault("autoStyleUpdate", true);
        this.enableSuccession = (boolean) configData.getOrDefault("enableSuccession", true);
        this.successionProbability = (double) configData.getOrDefault("successionProbability", 0.05);
        this.successionTreeProbability = (double) configData.getOrDefault("successionTreeProbability", 0.05);
        this.fullPyroclasticFlowProbability = (double) configData.getOrDefault("fullPyroclasticFlowProbability", 0.0001);

        JSONObject killSwitchConfig = (JSONObject) configData.getOrDefault("killSwitch", new JSONObject());
        this.enableKillSwitch = (boolean) killSwitchConfig.getOrDefault("enable", false);
        this.killAt = (long) killSwitchConfig.getOrDefault("killAt", 0);

        this.postProcessImport();
    }

    public void postProcessImport() {
        if (this.isFlowingLava() || this.isExploding()) {
            this.erupt.start();
        }
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        configData.put("enabled", this.enabled);
        configData.put("type", this.type.toString());

        configData.put("location", TyphonUtils.serializeLocationForJSON(this.location));
        configData.put("status", this.status.toString());
        configData.put("longestFlowLength", this.longestFlowLength);
        configData.put("longestNormalLavaFlowLength", this.longestNormalLavaFlowLength);
        configData.put("longestAshNormalFlowLength", this.longestAshNormalFlowLength);

        configData.put("craterRadius", this.craterRadius);

        configData.put("fissureAngle", this.fissureAngle);
        configData.put("fissureLength", this.fissureLength);
        configData.put("maxFissureLength", this.maxFissureLength);

        configData.put("autoStyleUpdate", this.autoStyleUpdate);
        configData.put("enableSuccession", this.enableSuccession);
        configData.put("successionProbability", this.successionProbability);
        configData.put("successionTreeProbability", this.successionTreeProbability);
        configData.put("fullPyroclasticFlowProbability", this.fullPyroclasticFlowProbability);

        configData.put("genesis", this.genesis.getName());

        JSONObject bombConfig = this.bombs.exportConfig();
        configData.put("bombs", bombConfig);

        JSONObject explosionConfig = this.explosion.exportConfig();
        configData.put("explosion", explosionConfig);

        JSONObject eruptConfig = this.erupt.exportConfig();
        configData.put("erupt", eruptConfig);

        JSONObject lavaFlowConfig = this.lavaFlow.exportConfig();
        configData.put("lavaFlow", lavaFlowConfig);

        JSONObject lavadomeConfig = this.lavadome.exportConfig();
        configData.put("lavaDome", lavadomeConfig);

        JSONObject recordConfig = this.record.exportConfig();
        configData.put("record", recordConfig);

        configData.put("calderaRadius", calderaRadius);

        JSONObject killSwitchConfig = new JSONObject();
        killSwitchConfig.put("enable", this.enableKillSwitch);
        killSwitchConfig.put("killAt", this.killAt);
        configData.put("killSwitch", killSwitchConfig);

        return configData;
    }
}
