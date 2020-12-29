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
    public int constructionStage = 0;

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
    public void fill(CommandSender sender) {
        List<VolcanoConstructionMagmaChamberFillData> data = VolcanoConstructionUtils.getPDFMagmaChamberData(
                this.baseBlock.getLocation(), this.height, this.baseRadius, Material.LAVA, this.height > 0);

        VolcanoMessage msg = new VolcanoMessage(this.volcano);

        this.constructionType = VolcanoConstructionType.FILLING;

        VolcanoConstructionStatus status = new VolcanoConstructionStatus(this.constructionType, volcano, this.name+"/fill", 1, data.size());
        TyphonPlugin.constructionStatuses.add(status);

        for (int i = 0; i < this.constructionStage; i++) {
            data.remove(0);
            status.stageComplete();
        }

        VolcanoConstruction.runConstructions(data.iterator(), true,
                (Runnable) () -> {
                    this.constructionType = VolcanoConstructionType.NONE;
                    this.isFilled = true;
                    this.constructionStage = 0;
                    this.volcano.trySave();

                    TyphonPlugin.constructionStatuses.remove(status);
                    if (sender == null) {
                        sender.sendMessage("Build Complete!");
                    }
                }, (Runnable) () -> {
                    status.stageComplete();

                    this.constructionStage = status.getCurrentStage();
                    this.volcano.trySave();
                }
        );
    }

    public void coolDown() { coolDown(null, this.volcano.composition); }
    public void coolDown(VolcanoComposition composition) { coolDown(null, composition); }
    public void coolDown(CommandSender sender) { coolDown(sender, this.volcano.composition); }

    public void coolDown(CommandSender sender, VolcanoComposition composition) {
        List<VolcanoConstructionMagmaChamberFillData> data = VolcanoConstructionUtils.getPDFMagmaChamberData(
                this.baseBlock.getLocation(), this.height, this.baseRadius, composition.getIntrusiveRockMaterial(), this.height > 0);

        this.constructionType = VolcanoConstructionType.COOLING;

        VolcanoConstructionStatus status = new VolcanoConstructionStatus(this.constructionType, volcano, this.name+"/cooldown", 1, data.size());
        TyphonPlugin.constructionStatuses.add(status);

        for (int i = 0; i < this.constructionStage; i++) {
            data.remove(0);
            status.stageComplete();
        }

        VolcanoConstruction.runConstructions(data.iterator(), true,
                (Runnable) () -> {
                    this.constructionType = VolcanoConstructionType.NONE;
                    this.isFilled = false;
                    this.constructionStage = 0;
                    this.volcano.trySave();

                    TyphonPlugin.constructionStatuses.remove(status);
                    if (sender == null) {
                        sender.sendMessage("Build Complete!");
                    }
                }, (Runnable) () -> {
                    status.stageComplete();

                    this.constructionStage = status.getCurrentStage();
                    this.volcano.trySave();
                }
        );
    }

    public void build() { build(null); }
    public void build(CommandSender sender) { build(sender, true); }
    public void build(CommandSender sender, boolean useNMS) {
        List<VolcanoConstructionRaiseData> data = VolcanoConstructionUtils.getPDFConsturctionData(
                this.baseBlock.getLocation(), this.height, this.baseRadius, Material.LAVA, this.baseRadius < 0);

        this.constructionType = VolcanoConstructionType.BUILDING;

        VolcanoConstructionStatus status = new VolcanoConstructionStatus(this.constructionType, volcano, this.name+"/build", 1, data.size());
        TyphonPlugin.constructionStatuses.add(status);

        for (int i = 0; i < this.constructionStage; i++) {
            data.remove(0);
            status.stageComplete();
        }

        VolcanoConstruction.runConstructions(data.iterator(), useNMS,
                (Runnable) () -> {
                    this.constructionType = VolcanoConstructionType.NONE;
                    this.isBuilt = true;
                    this.constructionStage = 0;
                    this.volcano.trySave();

                    TyphonPlugin.constructionStatuses.remove(status);
                    if (sender == null) {
                        sender.sendMessage("Build Complete!");
                    }
                }, (Runnable) () -> {
                    status.stageComplete();

                    this.constructionStage = status.getCurrentStage();
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

        this.constructionStage = (int) (long) configData.get("constructionStage");
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

        configData.put("constructionStage", this.constructionStage);
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
