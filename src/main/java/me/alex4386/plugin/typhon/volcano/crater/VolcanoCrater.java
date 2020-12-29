package me.alex4386.plugin.typhon.volcano.crater;

import me.alex4386.plugin.typhon.*;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.VolcanoStatus;
import me.alex4386.plugin.typhon.volcano.bomb.VolcanoBombs;
import me.alex4386.plugin.typhon.volcano.lavaflow.VolcanoLavaCoolData;
import me.alex4386.plugin.typhon.volcano.lavaflow.VolcanoLavaFlow;
import me.alex4386.plugin.typhon.volcano.log.VolcanoCraterRecord;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.json.simple.JSONObject;

import java.io.IOException;
import java.util.*;

public class VolcanoCrater {
    public static int defaultCraterRadius = 10;

    public boolean enabled = true;

    public Volcano volcano;
    public String name = null;

    public Location location;
    public int craterRadius = defaultCraterRadius;

    public double longestFlowLength;

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

    public void initialize() {
        // bombs don't need initialization
        erupt.initialize();
        lavaFlow.initialize();

        this.getCraterBlocks();
    }

    public void shutdown() {
        // bombs don't need shutdown
        erupt.shutdown();
        lavaFlow.shutdown();
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
            int minimumTolerantHeight = (int) this.averageCraterHeight() - 2;

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
        return this.getTwoDimensionalDistance(loc) < this.longestFlowLength;
    }

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

    public void generateSmoke(int count) {
        TyphonNMSUtils.createParticle(
                Particle.CLOUD,
                location,
                count
        );
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
        volcano.status = VolcanoStatus.ERUPTING;
        lavaFlow.settings.flowing = true;
    }

    public void stopFlowingLava() {
        lavaFlow.settings.flowing = false;
        volcano.status = (volcano.manager.currentlyStartedCraters().size() == 0) ? VolcanoStatus.MAJOR_ACTIVITY : volcano.status;
        this.cool();
    }

    public void startErupting() {
        this.initialize();
        volcano.status = VolcanoStatus.ERUPTING;
        erupt.erupting = true;
    }

    public void stopErupting() {
        erupt.erupting = false;
        volcano.status = (volcano.manager.currentlyStartedCraters().size() == 0) ? VolcanoStatus.MAJOR_ACTIVITY : volcano.status;
    }

    public boolean isMainCrater() {
        return this.name == null;
    }

    public String getCraterConfigFilename() {
        return this.name+".json";
    }

    public void importConfig(JSONObject configData) {
        this.enabled = (boolean) configData.get("enabled");

        this.location = TyphonUtils.deserializeLocationForJSON((JSONObject) configData.get("location"));
        this.craterRadius = (int) (long) configData.get("radius");
        this.bombs.importConfig((JSONObject) configData.get("bombs"));
        this.erupt.importConfig((JSONObject) configData.get("erupt"));
        this.lavaFlow.importConfig((JSONObject) configData.get("lavaFlow"));
        this.record.importConfig((JSONObject) configData.get("record"));
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        configData.put("enabled", this.enabled);
        configData.put("location", TyphonUtils.serializeLocationForJSON(this.location));
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
