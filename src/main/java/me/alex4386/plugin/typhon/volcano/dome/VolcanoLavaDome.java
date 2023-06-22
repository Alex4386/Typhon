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
import java.util.List;

public class VolcanoLavaDome {
    VolcanoVent vent;

    Location baseLocation = null;

    boolean baseYDefined = true;
    int baseY = 0;

    boolean isForming = false;

    double height = 0;

    int updateInterval = 120;
    double incrementAmount = 0.1;

    public static int domeScheduleId = -1;

    List<Block> builtBlocks = new ArrayList<>();

    public VolcanoLavaDome(VolcanoVent vent) {
        this.vent = vent;
        this.baseLocation = null;
    }

    public Location getLocation() {
        if (this.baseLocation == null) {
            if (this.vent.location != null && !this.baseYDefined) {
                Block block = TyphonUtils.getHighestRocklikes(this.vent.location);
                this.baseY = block.getY();
                this.baseYDefined = true;

                return new Location(
                        this.vent.location.getWorld(),
                        this.vent.location.getX(),
                        this.baseY,
                        this.vent.location.getZ());
            } else if (this.vent.location != null) {
                return new Location(
                        this.vent.location.getWorld(),
                        this.vent.location.getX(),
                        this.baseY,
                        this.vent.location.getZ());
            }
            return null;
        }

        return this.baseLocation;
    }

    public boolean isForming() {
        return this.isForming;
    }

    public void registerTask() {
        if (domeScheduleId < 0) {
            domeScheduleId = Bukkit.getScheduler()
                    .scheduleSyncRepeatingTask(
                            TyphonPlugin.plugin,
                            (Runnable) () -> {
                                double shouldDo = Math.random();
                                if (shouldDo < this.vent.getStatus().getScaleFactor()
                                        && this.isForming())
                                    this.plumbCycle();
                            },
                            0L,
                            (long) updateInterval
                                    * TyphonPlugin.minecraftTicksPerSeconds
                                    / this.vent.getVolcano().updateRate);
        }
    }

    public void unregisterTask() {
        if (domeScheduleId >= 0) {
            Bukkit.getScheduler().cancelTask(domeScheduleId);
            domeScheduleId = -1;
        }
    }

    public void postConeBuildHandler() {
        if (this.baseYDefined) {
            this.baseYDefined = false;
            this.baseLocation = null;
            this.getLocation();
        } else {
            this.baseLocation = TyphonUtils.getHighestRocklikes(this.baseLocation).getLocation();
        }
    }

    public void initialize() {
        this.registerTask();
    }

    public void shutdown() {
        this.unregisterTask();
    }

    public double calculateRadius(double height) {
        double scaledRadius = Math.sqrt(6.0 - (5.0 * this.vent.lavaFlow.settings.silicateLevel));
        return scaledRadius * height;
    }

    public double getHeight(double radius) {
        double scaledRadius = radius / height;
        double scaledHeight = ((-1
                / (4 - (5 * (this.vent.lavaFlow.settings.silicateLevel - 0.4)))
                * Math.pow(scaledRadius, 2))
                + 1);
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
            if (Math.random() < 0.0005) {
                this.explode();
                return;
            }
        }

        if (Math.random() < 0.1) {
            this.plumbLava();
        }

        if (Math.random() < 0.4) {
            this.plumbLava(false);
        }
    }

    public void plumbLava(boolean increaseHeight) {
        if (this.calculateRadius(this.height + 0.1) < this.vent.craterRadius * 0.8) {
            if (increaseHeight) {
                this.height += incrementAmount;
                this.build();
                this.vent.volcano.trySave(false);
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
        this.baseY = 0;
        this.baseYDefined = false;
        this.baseLocation = TyphonUtils.getHighestRocklikes(vent.location).getLocation();
        this.vent.volcano.trySave(true);
    }

    public void explode() {
        this.stop();
        this.height = 0;

        Block explosionBlock = this.getLocation().add(0, (int) (height / 2), 0).getBlock();
        VolcanoBombListener.lavaSplashExplosions.put(explosionBlock, this.vent);

        explosionBlock
                .getWorld()
                .createExplosion(
                        explosionBlock.getLocation(),
                        (float) this.calculateRadius(this.height) * 1.1f,
                        true,
                        true);
        this.vent.setType(VolcanoVentType.CRATER);

        this.vent.start();
    }

    public void build() {
        int currentlyBuiltHeight = 0;
        if (this.vent.caldera.isForming()) return;

        for (int i = (int) Math.ceil(this.calculateRadius(this.height)); i >= 0; i--) {
            int height = (int) this.getHeight(i);

            if (height > currentlyBuiltHeight) {
                List<Block> cylinderBlocks = VolcanoMath.getCircle(
                        this.getLocation().getBlock().getRelative(0, height, 0), i);
                for (Block block : cylinderBlocks) {
                    if (block.getType().isAir()) {
                        block.setType(
                                VolcanoComposition.getExtrusiveRock(
                                        this.vent.lavaFlow.settings.silicateLevel));
                        if (height == 1) {
                            this.vent.volcano.metamorphism.metamorphoseBlock(
                                    block.getRelative(BlockFace.DOWN));
                        }
                    }
                }

                currentlyBuiltHeight = height;
            }
        }
    }

    public void flowLava() {
        double radius = this.calculateRadius(this.height);
        Block toFlowBottom = TyphonUtils.getRandomBlockInRange(
                this.getLocation().getBlock(), (int) (radius * 0.2), (int) (radius * 0.95));
        Block toFlowTop = this.getLocation().getWorld().getBlockAt(
                toFlowBottom.getX(),
                (int) (this.baseY + this.height + 5),
                toFlowBottom.getZ()
        );
        Block toFlow = TyphonUtils.getHighestRocklikes(toFlowTop);

        this.vent.lavaFlow.flowLava(toFlow);
    }

    public JSONObject importConfig(JSONObject json) {
        this.height = (double) json.get("height");
        this.incrementAmount = (double) json.get("incrementAmount");
        this.isForming = (boolean) json.get("isForming");
        this.updateInterval = (int) (long) json.get("updateInterval");

        boolean baseYDefined = (boolean) json.get("baseYDefined");
        if (baseYDefined) {
            this.baseY = (int) (long) json.get("baseY");
        } else {
            this.baseLocation = TyphonUtils.deserializeLocationForJSON((JSONObject) json.get("baseLocation"));
        }

        return json;
    }

    public JSONObject exportConfig() {
        JSONObject json = new JSONObject();

        json.put("height", this.height);
        json.put("isForming", this.isForming);

        if (this.baseYDefined && this.baseLocation == null) {
            json.put("baseYDefined", this.baseYDefined);
            json.put("baseY", this.baseY);
        } else {
            json.put("baseYDefined", false);
            json.put("baseLocation", TyphonUtils.serializeLocationForJSON(this.baseLocation));
        }

        json.put("updateInterval", this.updateInterval);
        json.put("incrementAmount", this.incrementAmount);

        return json;
    }
}
