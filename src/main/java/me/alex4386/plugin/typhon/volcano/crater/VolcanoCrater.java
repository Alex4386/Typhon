package me.alex4386.plugin.typhon.volcano.crater;

import me.alex4386.plugin.typhon.*;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.bomb.VolcanoBombs;
import me.alex4386.plugin.typhon.volcano.lavaflow.VolcanoLavaFlow;
import me.alex4386.plugin.typhon.volcano.log.VolcanoCraterRecord;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.json.simple.JSONObject;

import java.util.*;

public class VolcanoCrater {
    public static int defaultCraterRadius = 20;

    public boolean enabled = true;

    public Volcano volcano;
    public String name = null;
    public VolcanoCraterStatus status = VolcanoCraterStatus.DORMANT;

    public Location location;
    public int craterRadius = defaultCraterRadius;

    public double longestFlowLength = 0.0;

    public List<Block> cachedCraterBlocks = null;

    public VolcanoBombs bombs = new VolcanoBombs(this);
    public VolcanoErupt erupt = new VolcanoErupt(this);
    public VolcanoTremor tremor = new VolcanoTremor(this);
    public VolcanoLavaFlow lavaFlow = new VolcanoLavaFlow(this);
    public VolcanoCraterRecord record = new VolcanoCraterRecord(this);

    public VolcanoCrater(Volcano volcano) {
        this.volcano = volcano;
        this.location = volcano.location;
        this.name = "main";
    }

    public VolcanoCrater(Volcano volcano, Location location, String name) {
        this.volcano = volcano;
        this.location = location;
        this.name = name;
    }

    public VolcanoCrater(Volcano volcano, JSONObject configData) {
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
                if (this.isBombAffected(loc) || this.isInLavaFlow(loc) || this.isInCrater(loc)) {
                    players.add(player);
                }
            }
        }

        return players;
    }

    public void initialize() {
        volcano.logger.log(VolcanoLogClass.CRATER, "Starting up crater "+name);

        // bombs don't need initialization
        erupt.initialize();
        lavaFlow.initialize();
        tremor.initialize();

        this.getCraterBlocks();


        volcano.logger.log(VolcanoLogClass.CRATER, "Started" +
                " up crater "+name);
    }

    public void shutdown() {
        volcano.logger.log(VolcanoLogClass.CRATER, "Shutting down crater "+name);

        // bombs don't need shutdown
        erupt.shutdown();
        lavaFlow.shutdown();
        tremor.shutdown();
        record.endEjectaTrack();

        volcano.logger.log(VolcanoLogClass.CRATER, "Shutted down crater "+name);
    }


    public String getName() {
        return this.name == null ? ChatColor.GOLD+"main"+ChatColor.RESET : this.name;
    }

    public List<Block> getCraterBlocks() {
        if (this.cachedCraterBlocks == null) {
            this.cachedCraterBlocks = VolcanoMath.getCircle(this.location.getBlock(), craterRadius, craterRadius - 1);
        }

        //TyphonPlugin.logger.log(VolcanoLogClass.CORE, "Debug: crater Block size: "+this.cachedCraterBlocks.size());

        List<Block> newCachedCraterBlocks = new ArrayList<>();
        for (Block block : this.cachedCraterBlocks) {
            newCachedCraterBlocks.add(TyphonUtils.getHighestNonTreeSolid(block.getLocation()));
        }

        this.cachedCraterBlocks = newCachedCraterBlocks;

        return this.cachedCraterBlocks;
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

    public void setRadius(int craterRadius) {
        this.craterRadius = craterRadius;
        this.cachedCraterBlocks = null;
    }

    public double averageCraterHeight() {
        int totalY = 0;
        for (Block block : this.cachedCraterBlocks) {
            totalY = block.getY();
        }

        return (double)totalY / this.cachedCraterBlocks.size();
    }

    public Block selectCraterBlock() {
        return selectCraterBlock(true);
    }

    public Block selectCraterBlock(boolean evenFlow) {
        List<Block> craterBlocks = getCraterBlocks();
        Collections.shuffle(craterBlocks);

        Random random = new Random();

        if (evenFlow && random.nextDouble() < 0.7f) {
            int minimumTolerantHeight = (int) this.averageCraterHeight() - 1;

            for (Block block:craterBlocks) {
                int y = block.getY();

                if (y < minimumTolerantHeight) {
                    return block;
                }
            }
        }

        int idx = random.nextInt(craterBlocks.size());
        return craterBlocks.get(idx);
    }

    public Block requestFlow() {
        return requestFlow(Material.LAVA);
    }

    public Block requestFlow(Material material) {
        Block craterBlock = this.selectCraterBlock();
        craterBlock = craterBlock.getRelative(0, 1, 0);
        craterBlock.setType(material);

        return craterBlock;
    }

    public Block getSummitBlock() {
        List<Block> cachedCraterBlocks = this.getCraterBlocks();

        int highestY = 0;
        Block highestBlock = null;

        for (Block block : cachedCraterBlocks) {
            if (block.getY() > highestY || highestY == 0) {
                highestY = block.getY();
                highestBlock = block;
            }
        }

        if (highestBlock == null) {
            highestBlock = cachedCraterBlocks.get(0);
        }

        return highestBlock;
    }

    public void cool() {
        List<Block> cachedCraterBlocks = this.getCraterBlocks();

        Iterator<Block> iterator = cachedCraterBlocks.iterator();

        while (iterator.hasNext()) {
            Block block = iterator.next();
            block.setType(volcano.composition.getExtrusiveRockMaterial());
        }
    }

    public boolean isInCrater(Location loc) {
        return this.getTwoDimensionalDistance(loc) <= this.craterRadius;
    }

    public boolean isInLavaFlow(Location loc) {
        return this.getTwoDimensionalDistance(loc) <= this.longestFlowLength;
    }

    public boolean isBombAffected(Location loc) { return this.getTwoDimensionalDistance(loc) <= this.bombs.maxDistance; }

    public double getTwoDimensionalDistance(Location loc) {
        return TyphonUtils.getTwoDimensionalDistance(loc, this.location);
    }

    public double getThreeDimensionalDistance(Location loc) {
        return this.location.distance(loc);
    }

    public void erupt() {
        erupt.erupt();
    }

    public void erupt(int bombCount) {
        erupt.erupt(bombCount);
    }

    public void teleport(Entity entity) { teleport(entity, true); }

    public void teleport(Entity entity, boolean unstuck) {
        Location location = this.location;
        if (unstuck) location = TyphonUtils.getHighestLocation(location).add(0,2,0);
        entity.teleport(location);
    }

    public void generateSmoke(int count) {
        Random random = new Random();

        int steamRadius = (int) (random.nextDouble() * 5);

        TyphonUtils.spawnParticleWithVelocity(
                Particle.CAMPFIRE_SIGNAL_SMOKE,
                TyphonUtils.getHighestRocklikes(location).getLocation(),
                steamRadius,
                (int) (count * (4 / 3) * Math.pow(steamRadius, 3)),
                0,
                0.4,
                0
        );
    }

    public void generateSteam(int count) {
        Random random = new Random();

        Block randomBlock = TyphonUtils.getRandomBlockInRange(location.getBlock(), craterRadius);

        int steamRadius = (int) (random.nextDouble() * craterRadius / 2);

        TyphonUtils.createRisingSteam(randomBlock.getLocation(), steamRadius, count);
    }

    public void generateLavaParticle(int count) {
        World world = location.getWorld();

        for (int i = 0; i < count; i++) {
            world.spawnParticle(
                    Particle.LAVA,
                    location,
                    100
            );
        }
    }

    public boolean isStarted() {
        return this.isFlowingLava() || this.isErupting();
    }

    public boolean isFlowingLava() {
        return lavaFlow.settings.flowing;
    }

    public boolean isErupting() {
        return erupt.erupting;
    }

    public void start() {
        this.startErupting();
        this.startFlowingLava();
        this.getVolcano().trySave();
    }

    public void stop() {
        this.stopErupting();
        this.stopFlowingLava();
        this.getVolcano().trySave();
    }

    public void startFlowingLava() {
        this.initialize();
        this.status = VolcanoCraterStatus.ERUPTING;
        lavaFlow.settings.flowing = true;
    }

    public void stopFlowingLava() {
        lavaFlow.settings.flowing = false;
        this.status = (!this.isErupting()) ? VolcanoCraterStatus.MAJOR_ACTIVITY : this.status;
        this.cool();
    }

    public void startErupting() {
        this.initialize();
        this.status = VolcanoCraterStatus.ERUPTING;
        erupt.erupting = true;
    }

    public void stopErupting() {
        erupt.erupting = false;
        this.status = (!this.isFlowingLava()) ? VolcanoCraterStatus.MAJOR_ACTIVITY : this.status;
    }

    public boolean isMainCrater() {
        return this.name == null;
    }

    public String getCraterConfigFilename() {
        return this.name+".json";
    }

    public void importConfig(JSONObject configData) {
        this.enabled = (boolean) configData.get("enabled");
        this.status = VolcanoCraterStatus.getStatus((String) configData.get("status"));
        this.location = TyphonUtils.deserializeLocationForJSON((JSONObject) configData.get("location"));
        this.craterRadius = (int) (long) configData.get("radius");
        this.bombs.importConfig((JSONObject) configData.get("bombs"));
        this.erupt.importConfig((JSONObject) configData.get("erupt"));
        this.lavaFlow.importConfig((JSONObject) configData.get("lavaFlow"));
        this.record.importConfig((JSONObject) configData.get("record"));
        this.longestFlowLength = (double) configData.get("longestFlowLength");
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        configData.put("enabled", this.enabled);
        configData.put("location", TyphonUtils.serializeLocationForJSON(this.location));
        configData.put("status", this.status.toString());
        configData.put("longestFlowLength", this.longestFlowLength);
        configData.put("radius", this.craterRadius);

        JSONObject bombConfig = this.bombs.exportConfig();
        configData.put("bombs", bombConfig);

        JSONObject eruptConfig = this.erupt.exportConfig();
        configData.put("erupt", eruptConfig);

        JSONObject lavaFlowConfig = this.lavaFlow.exportConfig();
        configData.put("lavaFlow", lavaFlowConfig);

        JSONObject recordConfig = this.record.exportConfig();
        configData.put("record", recordConfig);


        return configData;
    }
}
