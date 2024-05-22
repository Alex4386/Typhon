package me.alex4386.plugin.typhon.volcano.dome;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.bomb.VolcanoBombListener;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class VolcanoLavaDome {
    VolcanoVent vent;

    Location baseLocation = null;

    long plumbedLava = 0;

    public static int domeScheduleId = -1;
    public int baseY = Integer.MIN_VALUE;

    List<VolcanoLavaDomeLavaFlow> domeFlows = new ArrayList<>();

    public VolcanoLavaDome(VolcanoVent vent) {
        this.vent = vent;
        this.baseLocation = null;
    }

    public int getTargetDomeHeight() {
        return (int) Math.ceil(Math.pow((3.0 * this.plumbedLava) / (2 * Math.PI), 1.0/3.0));
    }

    public double getTargetHeightByDistance(double x) {
        double targetHeight = -0.01 * (Math.pow(x, 2)) + this.getTargetDomeHeight();
        return Math.max(0, targetHeight);
    }

    public double getTargetYAt(Location loc) {
        double distance = TyphonUtils.getTwoDimensionalDistance(this.baseLocation, loc);
        return this.getTargetHeightByDistance(distance) + this.getBaseY();
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

    public void resetBaseY() {
        this.baseY = Integer.MIN_VALUE;
    }

    public void registerTask() {
        if (domeScheduleId < 0) {
            domeScheduleId = Bukkit.getScheduler()
                    .scheduleSyncRepeatingTask(
                            TyphonPlugin.plugin,
                            (Runnable) this::runDomeLavaTick,
                            0L,
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

    public void unregisterTask() {
        if (domeScheduleId >= 0) {
            Bukkit.getScheduler().cancelTask(domeScheduleId);
            domeScheduleId = -1;
        }
    }

    public void initialize() {
        this.registerTask();
    }

    public void shutdown() {
        this.unregisterTask();
    }

    public Block getSourceBlock() {
        return TyphonUtils.getHighestRocklikes(this.baseLocation).getRelative(BlockFace.UP);
    }

    public void flowLava() {
        int randomRange = this.getTargetDomeHeight() * 2;
        double distance = Math.pow(1 - Math.random(), 2) * randomRange;

        double angle = Math.random() * 2 * Math.PI;
        double offsetX = distance * Math.cos(angle);
        double offsetZ = distance * Math.sin(angle);

        Location targetLocation = this.baseLocation.clone().add(offsetX, 0, offsetZ);
        Block targetBlock = TyphonUtils.getHighestRocklikes(targetLocation).getRelative(BlockFace.UP);

        this.domeFlows.add(new VolcanoLavaDomeLavaFlow(this, this.getSourceBlock(), targetBlock));
        this.plumbedLava++;
    }

    public JSONObject importConfig(JSONObject json) {
        this.plumbedLava = (long) json.get("plumbedLava");
        this.baseY = (int) (long) json.get("baseY");
        this.baseLocation = TyphonUtils.deserializeLocationForJSON((JSONObject) json.get("baseLocation"));

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
