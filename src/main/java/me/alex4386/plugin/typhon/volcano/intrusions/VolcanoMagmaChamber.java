package me.alex4386.plugin.typhon.volcano.intrusions;

import me.alex4386.plugin.typhon.*;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.commands.VolcanoMessage;
import me.alex4386.plugin.typhon.volcano.crater.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class VolcanoMagmaChamber {
    public String name;
    public Volcano volcano;

    public Block baseBlock;
    public int baseRadius;
    public int height;

    public boolean isBuilt = false;
    public boolean isFilled = false;

    public VolcanoConstructionType constructionType = VolcanoConstructionType.NONE;

    public VolcanoMagmaChamber(Volcano volcano, String name, Block baseBlock, int baseRadius, int maxHeight) {
        this.volcano = volcano;
        this.name = name;
        this.baseBlock = baseBlock;
        this.baseRadius = baseRadius;
        this.height = maxHeight;
    }

    public VolcanoMagmaChamber(Volcano volcano, String name, JSONObject configData) {
        this.volcano = volcano;
        this.name = name;
        this.importConfig(configData);
    }

    public void fill() { fill(null); }
    public void fill(CommandSender sender) { fill(null, false); }
    public void fill(boolean useNMS) { fill(null, useNMS); }
    public void fill(CommandSender sender, boolean useNMS) {
        List<VolcanoConstructionMagmaChamberFillData> data = VolcanoConstructionUtils.getPDFMagmaChamberData(
                this.baseBlock.getLocation(), this.height, this.baseRadius, Material.LAVA, this.height > 0);

        List<List<VolcanoConstructionMagmaChamberFillData>> dataGroup = VolcanoConstruction.splitToGroups(this.baseBlock.getLocation(), data, useNMS);

        this.constructionType = VolcanoConstructionType.FILLING;

        VolcanoConstructionStatus status = new VolcanoConstructionStatus(this.constructionType, volcano, this.name+"/fill", 1, data.size());
        TyphonPlugin.constructionStatuses.add(status);

        VolcanoConstruction.runConstructionGroups(status, dataGroup.iterator(), useNMS,
                (Runnable) () -> {
                    this.constructionType = VolcanoConstructionType.NONE;
                    this.isFilled = true;
                    this.volcano.trySave();

                    TyphonPlugin.constructionStatuses.remove(status);
                    if (sender != null) {
                        VolcanoMessage msg = new VolcanoMessage(this.volcano, sender);
                        msg.info("Filled MagmaChamber "+this.name+"!");
                        if (useNMS)
                            msg.info("You might need to reconnect to see updated chunks due to NMS updates");
                    }
                }, (Runnable) () -> {
                    this.volcano.trySave();
                }
        );
    }

    public boolean delete() {
        if (this.name == null) return false;
        if (this.name.equals("main")) return false;

        this.volcano.dataLoader.deleteMagmaChambersConfig(this.name);
        this.volcano.subCraters.remove(this.name);
        return true;
    }

    public void coolDown() { coolDown(null, this.volcano.composition, false); }
    public void coolDown(VolcanoComposition composition) { coolDown(null, composition, false); }
    public void coolDown(CommandSender sender) { coolDown(sender, this.volcano.composition, false); }
    public void coolDown(CommandSender sender, boolean useNMS) { coolDown(sender, this.volcano.composition, useNMS); }

    public void coolDown(CommandSender sender, VolcanoComposition composition, boolean useNMS) {
        List<VolcanoConstructionMagmaChamberFillData> data = VolcanoConstructionUtils.getPDFMagmaChamberData(
                this.baseBlock.getLocation(), this.height, this.baseRadius, composition.getIntrusiveRockMaterial(), this.height > 0);

        List<List<VolcanoConstructionMagmaChamberFillData>> dataGroup = VolcanoConstruction.splitToGroups(this.baseBlock.getLocation(), data, useNMS);

        this.constructionType = VolcanoConstructionType.COOLING;

        VolcanoConstructionStatus status = new VolcanoConstructionStatus(this.constructionType, volcano, this.name+"/cooldown", 1, data.size());
        TyphonPlugin.constructionStatuses.add(status);

        VolcanoConstruction.runConstructionGroups(status, dataGroup.iterator(), useNMS,
                (Runnable) () -> {
                    this.constructionType = VolcanoConstructionType.NONE;
                    this.isFilled = false;
                    this.volcano.trySave();

                    TyphonPlugin.constructionStatuses.remove(status);
                    if (sender != null) {
                        VolcanoMessage msg = new VolcanoMessage(this.volcano, sender);
                        msg.info("Cooled down MagmaChamber "+this.name+"!");
                        if (useNMS)
                            msg.info("You might need to reconnect to see updated chunks due to NMS updates");
                    }
                }, (Runnable) () -> {
                    this.volcano.trySave();
                }
        );
    }

    public void build() { build(null); }
    public void build(CommandSender sender) { build(sender, false); }
    public void build(CommandSender sender, boolean useNMS) {
        List<VolcanoConstructionRaiseData> data = VolcanoConstructionUtils.getPDFConsturctionData(
                this.baseBlock.getLocation(), this.height, this.baseRadius, Material.LAVA, this.baseRadius < 0);

        List<List<VolcanoConstructionRaiseData>> dataGroup = VolcanoConstruction.splitToGroups(this.baseBlock.getLocation(), data, useNMS);

        this.constructionType = VolcanoConstructionType.BUILDING;

        VolcanoConstructionStatus status = new VolcanoConstructionStatus(this.constructionType, volcano, this.name+"/build", 1, dataGroup.size());
        TyphonPlugin.constructionStatuses.add(status);

        VolcanoConstruction.runConstructionGroups(status, dataGroup.iterator(), useNMS,
                (Runnable) () -> {
                    this.constructionType = VolcanoConstructionType.NONE;
                    this.isBuilt = true;
                    this.isFilled = true;
                    this.volcano.trySave();

                    TyphonPlugin.constructionStatuses.remove(status);
                    if (sender != null) {
                        VolcanoMessage msg = new VolcanoMessage(this.volcano, sender);
                        msg.info("Built MagmaChamber "+this.name+"!");
                        if (useNMS)
                            msg.info("You might need to reconnect to see updated chunks due to NMS updates");
                    }
                }, (Runnable) () -> {
                    this.volcano.trySave();
                }
        );

    }

    public void importConfig(JSONObject configData) {
        JSONObject baseConfig = (JSONObject) configData.get("base");

        this.baseBlock = TyphonUtils.deserializeLocationForJSON((JSONObject) baseConfig.get("location")).getBlock();
        this.baseRadius = (int) (long) baseConfig.get("radius");

        this.height = (int) (long) configData.get("height");
        this.isBuilt = (boolean) configData.get("isBuilt");
        this.isFilled = (boolean) configData.get("isFilled");

        this.constructionType = VolcanoConstructionType.getType((int) (long) configData.get("constructionType"));
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        JSONObject baseConfig = new JSONObject();
        baseConfig.put("location", TyphonUtils.serializeLocationForJSON(this.baseBlock.getLocation()));
        baseConfig.put("radius", this.baseRadius);

        configData.put("base", baseConfig);
        configData.put("height", this.height);
        configData.put("isBuilt", this.isBuilt);
        configData.put("isFilled", this.isFilled);

        configData.put("constructionType", this.constructionType.getCode());

        return configData;
    }

    public Block getRandomIntrusion() {
        VolcanoCircleOffsetXZ axis = VolcanoMath.getCenterFocusedCircleOffset(this.baseBlock, this.baseRadius);

        Block block = this.baseBlock.getRelative((int) axis.x, 0, (int)axis.z);

        return block;
    }

    public Block getMagmaDikeBaseBlock(Location location) {
        if (this.baseBlock.getWorld().getUID() == location.getWorld().getUID()) return null;

        double distance = TyphonUtils.getTwoDimensionalDistance(location, baseBlock.getLocation());
        if (distance > this.baseRadius) { return null; }

        int radiusHeight = (int) (VolcanoMath.volcanoPdfHeight(distance / (double) this.baseRadius) * this.height);
        int y = radiusHeight + this.baseBlock.getY();

        return new Location(
                this.baseBlock.getWorld(),
                location.getX(),
                y,
                location.getZ()
        ).getBlock();
    }
}
