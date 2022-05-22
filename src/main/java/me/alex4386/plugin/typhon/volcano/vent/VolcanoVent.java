package me.alex4386.plugin.typhon.volcano.vent;

import me.alex4386.plugin.typhon.*;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.ash.VolcanoAsh;
import me.alex4386.plugin.typhon.volcano.bomb.VolcanoBombs;
import me.alex4386.plugin.typhon.volcano.dome.VolcanoLavaDome;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoErupt;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoEruptStyle;
import me.alex4386.plugin.typhon.volcano.explosion.VolcanoExplosion;
import me.alex4386.plugin.typhon.volcano.lavaflow.VolcanoLavaFlow;
import me.alex4386.plugin.typhon.volcano.log.VolcanoVentRecord;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.json.simple.JSONObject;

import java.util.*;

public class VolcanoVent {
    public static int defaultVentRadius = 20;

    public boolean enabled = true;

    public Volcano volcano;
    public String name = null;
    public VolcanoVentStatus status = VolcanoVentStatus.DORMANT;

    private VolcanoVentType type = VolcanoVentType.CRATER;

    public Location location;
    public int craterRadius = defaultVentRadius;

    public double fissureAngle = 0.0;
    public int fissureLength = 60;

    public double longestFlowLength = 0.0;

    public List<Block> cachedVentBlocks = null;
    public List<Block> coreBlocks = null;

    public VolcanoBombs bombs = new VolcanoBombs(this);
    public VolcanoExplosion explosion = new VolcanoExplosion(this);
    public VolcanoTremor tremor = new VolcanoTremor(this);
    public VolcanoLavaFlow lavaFlow = new VolcanoLavaFlow(this);
    public VolcanoVentRecord record = new VolcanoVentRecord(this);
    public VolcanoErupt erupt = new VolcanoErupt(this);
    public VolcanoAsh ash = new VolcanoAsh(this);
    public VolcanoLavaDome lavadome = new VolcanoLavaDome(this);

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

    public void initialize() {
        volcano.logger.log(VolcanoLogClass.VENT, "Starting up vent "+name);

        // bombs don't need initialization
        ash.initialize();
        explosion.initialize();
        lavaFlow.initialize();
        tremor.initialize();
        lavadome.initialize();

        this.getVentBlocks();


        volcano.logger.log(VolcanoLogClass.VENT, "Started" +
                " up vent "+name);
    }

    public VolcanoVentType getType() {
        return this.type;
    }

    public void shutdown() {
        volcano.logger.log(VolcanoLogClass.VENT, "Shutting down vent "+name);

        // bombs don't need shutdown
        ash.shutdown();
        explosion.shutdown();
        lavaFlow.shutdown();
        tremor.shutdown();
        record.endEjectaTrack();
        bombs.shutdown();
        lavadome.shutdown();

        volcano.logger.log(VolcanoLogClass.VENT, "Shutted down vent "+name);
    }


    public String getName() {
        return this.name == null ? ChatColor.GOLD+"main"+ChatColor.RESET : this.name;
    }

    public List<Block> getVentBlocks() {
        boolean isFirstLoad = false;

        if (this.cachedVentBlocks == null) {
            isFirstLoad = true;

            this.volcano.logger.log(VolcanoLogClass.VENT, "Calculating vent blocks of "+this.getName()+"...");
            if (type == VolcanoVentType.CRATER) {
                this.cachedVentBlocks = VolcanoMath.getCircle(this.location.getBlock(), craterRadius, craterRadius - 1);
            } else if (type == VolcanoVentType.FISSURE) {
                double rightAngledAngle = (Math.PI / 2) + this.fissureAngle;
                int xRadiusOffset = (int) Math.cos(rightAngledAngle) * this.craterRadius;
                int zRadiusOffset = (int) Math.sin(rightAngledAngle) * this.craterRadius;

                List<Block> ventBlocksWithCrater = new ArrayList<Block>();

                Block fromBlock = this.location.getBlock().getRelative(xRadiusOffset, 0, zRadiusOffset);
                ventBlocksWithCrater.addAll(VolcanoMath.getLine(fromBlock, this.fissureAngle, this.fissureLength));

                fromBlock = this.location.getBlock().getRelative(-xRadiusOffset, 0, -zRadiusOffset);
                ventBlocksWithCrater.addAll(VolcanoMath.getLine(fromBlock, this.fissureAngle, this.fissureLength));

                this.cachedVentBlocks = ventBlocksWithCrater;
            } else {
                // fallback to crater
                this.cachedVentBlocks = VolcanoMath.getCircle(this.location.getBlock(), craterRadius, craterRadius - 1);
            }

            this.volcano.logger.log(VolcanoLogClass.VENT, "Getting chunks to load vent blocks of "+this.getName()+"... (This may take a while)");
            List<Chunk> chunksToLoad = new ArrayList<Chunk>();
            for (Block block: this.cachedVentBlocks) {
                if (!chunksToLoad.contains(block.getChunk())) {
                    chunksToLoad.add(block.getChunk());
                }
            }

            this.volcano.logger.log(VolcanoLogClass.VENT, "Loading chunk vent blocks of "+this.getName()+"...");
            for (Chunk chunk: chunksToLoad) {
                chunk.load();
            }
        }

        if (this.coreBlocks == null) {
            List<Block> coreBlocks = new ArrayList<Block>();
            TyphonPlugin.logger.log(VolcanoLogClass.CORE, "Calculating core blocks of "+this.getName()+"...");  
            
            if (type == VolcanoVentType.FISSURE) {
                coreBlocks.addAll(VolcanoMath.getLine(this.location.getBlock(), this.fissureAngle, this.fissureLength));
            } else {
                coreBlocks.add(this.location.getBlock());
            }

            this.coreBlocks = coreBlocks;
        }

        //TyphonPlugin.logger.log(VolcanoLogClass.CORE, "Debug: vent Block size: "+this.cachedVentBlocks.size());

        if (isFirstLoad) this.volcano.logger.log(VolcanoLogClass.VENT, "Calculating highest points of vent blocksof "+this.getName()+"...");
        List<Block> newCachedVentBlocks = new ArrayList<>();
        for (Block block : this.cachedVentBlocks) {
            newCachedVentBlocks.add(TyphonUtils.getHighestNonTreeSolid(block.getLocation()));
        }

        if (isFirstLoad) this.volcano.logger.log(VolcanoLogClass.VENT, "Vent block calculation of "+this.getName()+" complete.");
        this.cachedVentBlocks = newCachedVentBlocks;

        return this.cachedVentBlocks;
    }


    public double getHeatValue( Location loc) {
        double distance = this.getTwoDimensionalDistance(loc);
        double distanceRatio = (distance / this.longestFlowLength) / 1.5;

        double heatValue = VolcanoMath.pdfMaxLimiter(distanceRatio, 1);

        return heatValue;
    }

    public int getRadius() {
        return this.craterRadius;
    }

    public void flushCache() {
        this.cachedVentBlocks = null;
        this.coreBlocks = null;
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
            totalY = block.getY();
        }

        return (double)totalY / this.cachedVentBlocks.size();
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
        List<Block> ventBlocks = getVentBlocks();
        Random random = new Random();

        List<Block> selectedBlocks = new ArrayList<>();

        if (evenFlow) {
            boolean useLowest = false;
            useLowest = Math.random() < ((this.lavaFlow.settings.silicateLevel - 0.22) * (1 + this.lavaFlow.settings.silicateLevel));

            double averageVentHeight = this.averageVentHeight();

            if (useLowest) {
                Block lowestVent = this.lowestVentBlock();
                if (Math.round(lowestVent.getY()) < averageVentHeight) {
                    ventBlocks.sort((Block block1, Block block2) -> block1.getY() - block2.getY());

                    for (Block block:ventBlocks) {
                        if (this.lavaFlow.lavaCoolHashMap.get(block) == null) {
                            if (!selectedBlocks.contains(block)) {
                                selectedBlocks.add(block);
                                if (selectedBlocks.size() == count) return selectedBlocks;
                            }
                        }
                    }
                }
            }

            Collections.shuffle(ventBlocks, random);

            int minimumTolerantHeight;
            if (this.type == VolcanoVentType.FISSURE) minimumTolerantHeight = (int) averageVentHeight - 3;
            else minimumTolerantHeight = (int) averageVentHeight - ((int) craterRadius / 7);

            for (Block block:ventBlocks) {
                int y = block.getY();

                if (y < minimumTolerantHeight && this.lavaFlow.lavaCoolHashMap.get(block) == null) {
                    if (Math.random() < 0.2f) continue; 
                    if (!selectedBlocks.contains(block)) {
                        selectedBlocks.add(block);
                        if (selectedBlocks.size() == count) return selectedBlocks;
                    }
                }
            }

        }

        for (int i = 0; i < count; i++) {
            int idx = random.nextInt(ventBlocks.size());
            Block block = ventBlocks.get(idx);

            if (!selectedBlocks.contains(block)) {
                selectedBlocks.add(block);
                if (selectedBlocks.size() == count) return selectedBlocks;
            }
        }

        return selectedBlocks;
    }


    public Block selectFlowVentBlock(boolean evenFlow) {
        return this.selectFlowVentBlocks(evenFlow, 1).get(0);
    }

    public Block requestFlow() {
        return requestFlow(Material.LAVA);
    }

    public Block requestFlow(Material material) {
        Block ventBlock = this.selectFlowVentBlock().getRelative(BlockFace.UP);
        return ventBlock;
    }

    public List<Block> requestFlows(int count) {
        return this.requestFlows(Material.LAVA, count);
    }

    public List<Block> requestFlows(Material material, int count) {
        List<Block> ventBlocks = this.selectFlowVentBlocks(count);
        List<Block> lavaFlowBlocks = new ArrayList<>();

        for (Block ventBlock:ventBlocks) lavaFlowBlocks.add(ventBlock.getRelative(BlockFace.UP));
        return lavaFlowBlocks;
    }

    public Block getSummitBlock() {
        List<Block> cachedVentBlocks = this.getVentBlocks();

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

        return highestBlock;
    }

    public void cool() {
        List<Block> cachedVentBlocks = this.getVentBlocks();

        Iterator<Block> iterator = cachedVentBlocks.iterator();

        while (iterator.hasNext()) {
            Block block = iterator.next();
            block.setType(VolcanoComposition.getExtrusiveRock(this.lavaFlow.settings.silicateLevel));
        }
    }

    public boolean isInVent(Location loc) {
        return this.getTwoDimensionalDistance(loc) <= this.craterRadius;
    }

    public boolean isInLavaFlow(Location loc) {
        return this.getTwoDimensionalDistance(loc) <= this.longestFlowLength;
    }

    public boolean isBombAffected(Location loc) { return this.getTwoDimensionalDistance(loc) <= this.bombs.maxDistance; }

    public Block getNearestCoreBlock(Location loc) {
        if (this.coreBlocks == null) {
            this.getVentBlocks();
        }

        double lowest = Double.POSITIVE_INFINITY;
        Iterator<Block> iterator = this.coreBlocks.iterator();

        Block nearestBlock = null;

        while (iterator.hasNext()) {
            Block block = iterator.next();
            double currentDistance = block.getLocation().distance(loc);

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
        return this.getNearestCoreBlock(loc).getLocation().distance(loc);
    }

    public void explode() {
        explosion.explode();
    }

    public void explode(int bombCount) {
        explosion.explode(bombCount);
    }

    public void teleport(Entity entity) { teleport(entity, true); }

    public void teleport(Entity entity, boolean unstuck) {
        Location location = this.location;
        if (unstuck) location = TyphonUtils.getHighestLocation(location).add(0,2,0);
        entity.teleport(location);
    }

    public void generateSteam(int count) {
        Random random = new Random();

        Block randomBlock = TyphonUtils.getRandomBlockInRange(location.getBlock(), craterRadius);

        int steamRadius = (int) (random.nextDouble() * craterRadius / 2);

        TyphonUtils.createRisingSteam(randomBlock.getLocation(), steamRadius, count);
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
        return this.name == null;
    }

    public String getVentConfigFilename() {
        return this.name+".json";
    }

    public void importConfig(JSONObject configData) {
        this.enabled = (boolean) configData.get("enabled");
        this.type = VolcanoVentType.fromString((String) configData.get("type"));
        this.status = VolcanoVentStatus.getStatus((String) configData.get("status"));
        this.location = TyphonUtils.deserializeLocationForJSON((JSONObject) configData.get("location"));
        this.craterRadius = (int) (long) configData.get("craterRadius");
        this.fissureAngle = (double) configData.get("fissureAngle");
        this.fissureLength = (int) (long) configData.get("fissureLength");
        this.bombs.importConfig((JSONObject) configData.get("bombs"));
        this.explosion.importConfig((JSONObject) configData.get("explosion"));
        this.lavaFlow.importConfig((JSONObject) configData.get("lavaFlow"));
        this.record.importConfig((JSONObject) configData.get("record"));
        this.erupt.importConfig((JSONObject) configData.get("erupt"));
        this.lavadome.importConfig((JSONObject) configData.get("lavaDome"));
        this.longestFlowLength = (double) configData.get("longestFlowLength");
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        configData.put("enabled", this.enabled);
        configData.put("type", this.type.toString());

        configData.put("location", TyphonUtils.serializeLocationForJSON(this.location));
        configData.put("status", this.status.toString());
        configData.put("longestFlowLength", this.longestFlowLength);

        configData.put("craterRadius", this.craterRadius);

        configData.put("fissureAngle", this.fissureAngle);
        configData.put("fissureLength", this.fissureLength);

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


        return configData;
    }
}
