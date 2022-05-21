package me.alex4386.plugin.typhon.volcano.dome;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.json.simple.JSONObject;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.bomb.VolcanoBombListener;
import me.alex4386.plugin.typhon.volcano.erupt.VolcanoEruptStyle;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

public class VolcanoLavaDome {
    VolcanoVent vent;
    Location baseLocation;

    boolean isForming = false;

    double height = 0;

    int updateInterval = 120;
    double incrementAmount = 0.1;

    public static int domeScheduleId = -1;

    List<Block> builtBlocks = new ArrayList<>();

    public VolcanoLavaDome(VolcanoVent vent) {
        this.vent = vent; 
        this.baseLocation = TyphonUtils.getHighestRocklikes(vent.location).getLocation();
    }

    public boolean isForming() {
        return this.vent.status.getScaleFactor() > 0.5 && this.isForming;
    }

    public void registerTask() {
        if (domeScheduleId < 0) {
            domeScheduleId = Bukkit.getScheduler().scheduleSyncRepeatingTask(TyphonPlugin.plugin, (Runnable) () -> {
                if (this.isForming()) this.plumbCycle();
            }, 0L, (long) updateInterval * TyphonPlugin.minecraftTicksPerSeconds / this.vent.getVolcano().updateRate);
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

    public double calculateRadius(double height) {
        double scaledRadius = Math.sqrt(6 - (5 * this.vent.lavaFlow.settings.silicateLevel));
        return scaledRadius * height;
    }

    public double getHeight(double radius) {
        double scaledRadius = radius / height;
        double scaledHeight = (
            (-1 / (4 - (5 * (this.vent.lavaFlow.settings.silicateLevel - 0.4))) *
            Math.pow(scaledRadius, 2)) + 1
        );
        return scaledHeight * height;
    }

    public void start() {
        this.isForming = true;
    }

    public void stop() {
        this.isForming = false;
    }

    public void plumbCycle() {
        if (this.calculateRadius(this.height + 0.1) > this.vent.craterRadius * 0.8) {
            if (Math.random() < 0.0005) { this.explode(); return; }
        }
        
        if (Math.random() < 0.05) {
            this.plumbLava();
        }

        if (Math.random() < 0.1) {
            this.plumbLava(false);
        }
    }

    public void plumbLava(boolean increaseHeight) {
        if (this.calculateRadius(this.height + 0.1) < this.vent.craterRadius * 0.8) {
            if (increaseHeight) {
                this.height += incrementAmount;
                this.build();
            }
            this.flowLava();
        } else {
            this.flowLava();
        }
    }

    public void plumbLava() {
        this.plumbLava(true);
    }

    public void move(Location location) {
        this.height = 0;
        this.baseLocation = TyphonUtils.getHighestRocklikes(vent.location).getLocation();
    }

    public void explode() {
        this.stop();
        this.height = 0;

        Block explosionBlock = this.baseLocation.add(0, (int) (height / 2), 0).getBlock();
        VolcanoBombListener.lavaSplashExplosions.put(explosionBlock, this.vent);

        explosionBlock.getWorld().createExplosion(explosionBlock.getLocation(), (float) this.calculateRadius(this.height) * 1.1f, true, true);
        this.vent.setType(VolcanoVentType.CRATER);
        
        this.vent.start();
    }

    public void build() {
        int currentlyBuiltHeight = 0;

        for (int i = (int) Math.ceil(this.calculateRadius(this.height)); i >= 0; i--) {
            int height = (int) this.getHeight(i);

            if (height > currentlyBuiltHeight) {
                List<Block> cylinderBlocks = VolcanoMath.getCircle(this.baseLocation.getBlock().getRelative(0, height, 0), i);
                for (Block block: cylinderBlocks) {
                    if (block.getType().isAir()) {
                        block.setType(VolcanoComposition.getExtrusiveRock(this.vent.lavaFlow.settings.silicateLevel));
                        if (height == 1) {
                            this.vent.volcano.metamorphism.metamorphoseBlock(block.getRelative(BlockFace.DOWN));
                        }
                    }
                }

                currentlyBuiltHeight = height;
            }
        }
    }

    public void flowLava() {
        double radius = this.calculateRadius(this.height);
        Block toFlowBottom = TyphonUtils.getRandomBlockInRange(this.baseLocation.getBlock(), (int) (radius * 0.2), (int) (radius * 0.95));
        Block toFlow = TyphonUtils.getHighestRocklikes(toFlowBottom);

        this.vent.lavaFlow.flowLava(toFlow);
    }    

    public JSONObject importConfig(JSONObject json) {
        this.height = (double) json.get("height");
        this.incrementAmount = (double) json.get("incrementAmount");
        this.isForming = (boolean) json.get("isForming");
        this.baseLocation = TyphonUtils.deserializeLocationForJSON((JSONObject) json.get("baseLocation"));
        this.updateInterval = (int) json.get("updateInterval");

        return json;
    }

    public JSONObject exportConfig() {
        JSONObject json = new JSONObject();

        json.put("height", this.height);
        json.put("isForming", this.isForming);
        json.put("baseLocation", TyphonUtils.serializeLocationForJSON(this.baseLocation));
        json.put("updateInterval", this.updateInterval);
        json.put("incrementAmount", this.incrementAmount);

        return json;
    }
}
