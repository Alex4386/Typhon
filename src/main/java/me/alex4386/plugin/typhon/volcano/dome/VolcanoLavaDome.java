package me.alex4386.plugin.typhon.volcano.dome;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonScheduler;
import me.alex4386.plugin.typhon.TyphonSounds;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.bomb.VolcanoBomb;
import me.alex4386.plugin.typhon.volcano.bomb.VolcanoBombListener;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoEruptStyle;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class VolcanoLavaDome {
    VolcanoVent vent;


    public static int domeScheduleId = -1;
    public int baseY = Integer.MIN_VALUE;

    Location baseLocation = null;
    long plumbedLava = 0;

    static double shapeExponent = 0.7;


    List<VolcanoLavaDomeLavaFlow> domeFlows = new ArrayList<>();

    public VolcanoLavaDome(VolcanoVent vent) {
        this.vent = vent;
        this.baseLocation = null;
    }

    public Location getBaseLocation() {
        return baseLocation;
    }

    public long getPlumbedLava() {
        return plumbedLava;
    }

    public int getTargetDomeHeight() {
        return (int) Math.ceil(Math.pow((3.0 * this.plumbedLava) / (2 * Math.PI), 1.0/3.0));
    }

    public double getTargetHeightByDistance(double r) {
        double height = this.getTargetDomeHeight(); // current dome height
        double radius = this.getTargetBasin(); // R from growth model
        double exponent = shapeExponent;

        if (r > radius) return 0; // outside the dome footprint

        double normalized = r / radius;
        double targetHeight = height * (1 - Math.pow(normalized, exponent));
        return Math.max(0, targetHeight);
    }

    public double getTargetYAt(Location loc) {
        double distance = TyphonUtils.getTwoDimensionalDistance(this.baseLocation, loc);
        return this.getTargetHeightByDistance(distance) + this.getBaseY();
    }

    public boolean isConfigured() {
        return this.baseLocation != null && this.baseY != Integer.MIN_VALUE;
    }

    public void configure() {
        if (!this.isConfigured()) {
            this.postConeBuildHandler();
            this.baseLocation = this.vent.getCoreBlock().getLocation();
            this.getBaseY();
        }
    }

    public int getInitialBaseY() {
        if (this.vent.isCaldera()) {
            return TyphonUtils.getHighestRocklikes(this.baseLocation).getY();
        }

        return Math.max(this.vent.getSummitBlock().getY() - this.vent.getRadius(), (int) this.baseLocation.getY());
    }

    public int getBaseY() {
        if (this.baseY == Integer.MIN_VALUE) {
            this.baseY = this.getInitialBaseY();
        }

        return this.baseY;
    }

    public void resetAll() {
        this.baseY = Integer.MIN_VALUE;
        this.plumbedLava = 0;
    }

    public void registerTask() {
        if (domeScheduleId < 0) {
            domeScheduleId = TyphonScheduler.registerGlobalTask(
                            (Runnable) this::runDomeLavaTick,
                            1L);
        }
    }

    public void runDomeLavaTick() {
        Iterator<VolcanoLavaDomeLavaFlow> iterator = this.domeFlows.iterator();
        while (iterator.hasNext()) {
            VolcanoLavaDomeLavaFlow flow = iterator.next();
            flow.runTick();

            if (flow.finished) {
                iterator.remove();
            }
        }
    }

    public void postConeBuildHandler() {
        // check if current eruption is lavadome eruption
        if (this.vent.erupt.getStyle() == VolcanoEruptStyle.LAVA_DOME) {
            // if then, the values should be retained.
            // skip reset
            return;
        }

        this.resetAll();
    }

    public void unregisterTask() {
        if (domeScheduleId >= 0) {
            TyphonScheduler.unregisterTask(domeScheduleId);
            domeScheduleId = -1;
        }
    }

    public void initialize() {
        this.registerTask();
    }

    public void shutdown() {
        this.coolDownAll();
        this.unregisterTask();
    }

    public void coolDownAll() {
        Iterator<VolcanoLavaDomeLavaFlow> iterator = this.domeFlows.iterator();
        while (iterator.hasNext()) {
            VolcanoLavaDomeLavaFlow flow = iterator.next();
            flow.forceCoolDown();
        }
    }

    static double sourceRange = 3.0;
    public Block getSourceBlock() {
        double angle = Math.random() * 2 * Math.PI;
        return this.getSourceBlock(angle);
    }

    public Block getSourceBlock(double angle) {
        Location locWithSourceRange = this.baseLocation.clone();
        double radius = sourceRange * (1.0 - Math.pow(Math.random(), 2));
        locWithSourceRange.add(Math.sin(angle) * radius, 0, Math.cos(angle) * radius);

        return TyphonUtils.getHighestRocklikes(locWithSourceRange).getRelative(BlockFace.UP);
    }

    public void preStartArgumentValidityCheck() {
        Block highestBlock = TyphonUtils.getHighestRocklikes(this.baseLocation);
        if (highestBlock.getY() < this.baseY) {
            this.baseY = highestBlock.getY();
        }


    }

    public int domeFlowCounts() {
        return domeFlows.size();
    }

    public double getTargetBasin() {
        return Math.pow(this.getTargetDomeHeight(), 1.0 / shapeExponent);
    }

    public void flowLava() {
        double targetBasin = this.getTargetBasin();
        int randomRange = (int) Math.max(0.0, Math.ceil(Math.max(10, targetBasin)) - sourceRange);
        double distance = (1 - Math.pow(Math.random(), 2)) * (randomRange + sourceRange);

        double angle = Math.random() * 2 * Math.PI;
        Block srcBlock = this.getSourceBlock(angle);
        Block targetBlock = this.baseLocation.clone().add(Math.sin(angle) * distance, 0, Math.cos(angle) * angle).getBlock();

        if (srcBlock.getRelative(0, -1 ,0).getType() == Material.MAGMA_BLOCK) {
            // the underlying block haven't fully flowed yet. use this as srcBlock.
            srcBlock = srcBlock.getRelative(0, -1, 0);
        }

        this.domeFlows.add(new VolcanoLavaDomeLavaFlow(this, srcBlock, targetBlock));
        this.plumbedLava++;
    }

    public boolean isDomeLargeEnough() {
        return this.getTargetBasin() > 10;
    }

    public Block getFractureTarget() {
        // if the dome is large enough, flow "real" lava from the side of the dome.
        double targetBasin = this.getTargetBasin();
        double distance = (Math.pow(1 - Math.random(), 2) * 0.5 + 0.5) * targetBasin;

        return TyphonUtils.getFairRandomBlockInRange(this.baseLocation.getBlock(), (int) distance, (int) distance);
    }

    public void ooze() {
        Block target = this.getFractureTarget();
        this.vent.lavaFlow.flowLava(TyphonUtils.getHighestRocklikes(
                target
        ).getRelative(BlockFace.UP));

        if (Math.random() < 0.0001) {
            TyphonSounds.getRandomLavaFragmenting().play(this.baseLocation.getBlock().getLocation(), SoundCategory.BLOCKS, 0.1f, 1);
            TyphonUtils.smoothBlockHeights(target, 5, Material.TUFF);
        }

    }

    public void explode() {
        // if the dome is large enough, explode the side profile of the dome.
        TyphonSounds.EARTH_CRACKING.play(this.baseLocation.getBlock().getLocation(), SoundCategory.BLOCKS, 2f, 0f);

        Block target = this.getFractureTarget();
        target = TyphonUtils.getHighestRocklikes(target);
        this.vent.ash.triggerPyroclasticFlow(target);
    }

    public JSONObject importConfig(JSONObject json) {
        this.plumbedLava = (long) json.get("plumbedLava");
        this.baseY = (int) (long) json.get("baseY");

        JSONObject locationJSON = (JSONObject) json.get("baseLocation");
        if (locationJSON != null) {
            this.baseLocation = TyphonUtils.deserializeLocationForJSON((JSONObject) json.get("baseLocation"));
        }

        return json;
    }

    public JSONObject exportConfig() {
        JSONObject json = new JSONObject();

        if (this.baseLocation != null) {
            json.put("baseLocation", TyphonUtils.serializeLocationForJSON(this.baseLocation));
        }

        json.put("baseY", this.baseY);
        json.put("plumbedLava", this.plumbedLava);

        return json;
    }
}
