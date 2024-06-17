package me.alex4386.plugin.typhon.volcano.landslide;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class VolcanoLandslide {
    public VolcanoVent vent;
    public double landslideAngle = 0;
    public int initSummitY = Integer.MIN_VALUE;

    public Iterator<Block> blockIterator = null
            ;
    public int landslideTimer = -1;

    public VolcanoLandslide(VolcanoVent vent) {
        this.vent = vent;
    }

    public void initialize() {
        this.registerTask();
    }

    public void shutdown() {
        this.unregisterTask();
    }

    public void registerTask() {
        if (this.landslideTimer < 0) {
            this.landslideTimer = Bukkit.getScheduler().scheduleSyncRepeatingTask(TyphonPlugin.plugin, this::runTask, 0, 1);
        }
    }

    public void unregisterTask() {
        if (this.landslideTimer >= 0) {
            Bukkit.getScheduler().cancelTask(this.landslideTimer);
            this.landslideTimer = -1;
        }
    }

    public void clear() {
        this.blockIterator = null;
        this.initSummitY = Integer.MIN_VALUE;
        this.landslideAngle = 0;
    }

    public void runTask() {
        if (this.blockIterator == null) return;

        int count = 5 + (int) (Math.random() * 5);
        for (int i = 0; i < count; i++) {
            if (!this.blockIterator.hasNext()) {
                break;
            }

            Block block = this.blockIterator.next();
            this.runCollapse(block);
        }

        this.runPyroclasticFlow();
        if (!this.blockIterator.hasNext()) {
            this.blockIterator = null;
        }
    }

    public void start() {
        if (!this.isConfigured()) return;

        if (this.blockIterator == null) {
            this.blockIterator = this.getTargetBlocks().iterator();
        }
    }

    public boolean isConfigured() {
        return this.initSummitY != Integer.MIN_VALUE;
    }

    public void configure() {
        this.initSummitY = (int) (this.vent.getSummitBlock().getY() + (this.vent.getRadius() / Math.sqrt(3)));
        this.landslideAngle = Math.random() * Math.PI * 2;
    }

    public void setLandslideAngle(double angle) {
        this.landslideAngle = angle;
    }

    public int getRadius() {
        if (this.initSummitY == Integer.MIN_VALUE) {
            this.configure();
        }

        return (int) ((this.initSummitY - this.vent.location.getY()) / 4);
    }

    public int getRimSummitY() {
        return this.initSummitY - this.getRadius();
    }

    public double getRimY(double x) {
        double distance = this.getRadius() + x;

        return this.getRimSummitY() - ((1.0/6.0) * distance);
    }

    public int getFloorY(double x) {
        if (x < 0 && x > -this.getRadius()) {
            return (int) (this.getRimSummitY() - this.getRadius() - x);
        }

        int zeroY = this.getRimSummitY() - this.getRadius();
        double offset = ((1.0 / 16.0) * x);
        return (int) Math.round(zeroY - offset);
    }

    public int getY(Vector vector) {
        double x = vector.getX();
        double z = vector.getZ();

        if (z == 0) return this.getFloorY(x);

        double rim = this.getRimY(x);
        double floor = this.getFloorY(x);

        double radius = this.getRadius();
        if (x < 0) {
            // cos = Math.abs(x) / r
            // sin = newRadius / r
            double cos = Math.abs(x) / radius;

            // get angle from cos
            double angle = Math.acos(cos);

            // get new radius
            //        |\
            // radius | \ r
            //        |--\
            //          x

            radius = this.getRadius() * Math.sin(angle);
        }

        if (radius == 0) return (int) floor;
        double slope = (rim - floor) / radius;
        double offset = slope * Math.abs(z);

        return (int) floor + (int) offset;
    }

    public Vector getVector(Location location) {
        double xOffset = location.getX() - this.vent.location.getX();
        double zOffset = location.getZ() - this.vent.location.getZ();

        double cos = Math.cos(this.landslideAngle);
        double sin = Math.sin(this.landslideAngle);

        double x = xOffset * cos + zOffset * sin;
        double z = -xOffset * sin + zOffset * cos;

        return new Vector(x, 0, z);
    }

    public double getLandslideVolume() {
        double topAndHalfOfBottom = 0.5 * Math.PI * Math.pow(this.getRadius(), 3);
        double slideVolume = 8 * Math.pow(this.getRadius(), 3);
        return topAndHalfOfBottom + slideVolume;
    }

    public List<Block> getTargetBlocks() {
        List<Block> blocks = VolcanoMath.getCircle(this.vent.location.getBlock(), this.getRadius());

        double cos = Math.cos(this.landslideAngle);
        double sin = Math.sin(this.landslideAngle);

        int length = this.getRadius() * 8;
        for (int x = 0; x < length; x++) {
            for (int z = -this.getRadius(); z < this.getRadius(); z++) {
                double rotatedX = x * cos - z * sin;
                double rotatedZ = x * sin + z * cos;

                Block block = this.vent.location.getBlock().getRelative((int) rotatedX, 0, (int) rotatedZ);
                if (!blocks.contains(block)) {
                    blocks.add(block);
                }
            }
        }

        return blocks;
    }

    public void runCollapse(Block block) {
        World world = block.getWorld();
        Vector vector = this.getVector(block.getLocation());
        int y = this.getY(vector);

        int currentY = TyphonUtils.getHighestRocklikes(block).getY();
        if (currentY < y) return;

        for (int i = currentY; i > y; i--) {
            Block targetBlock = block.getRelative(0, i - currentY, 0);
            if (targetBlock.getType().isSolid()) {
                Material material = i <= world.getSeaLevel() ?
                        world.isUltraWarm() ? Material.LAVA : Material.WATER
                        : Material.AIR;

                this.vent.lavaFlow.queueBlockUpdate(targetBlock, material);
            }
        }
    }

    public void runPyroclasticFlow() {
        Block source = this.getPyroclasticFlowSource();
        this.vent.ash.triggerPyroclasticFlow(source);
    }

    public Block getPyroclasticFlowSource() {
        double xOffset = this.getRadius() * 8;
        double zOffset = ((Math.random() * 2) - 1) * this.getRadius();

        double cos = Math.cos(this.landslideAngle);
        double sin = Math.sin(this.landslideAngle);

        double x = xOffset * cos - zOffset * sin;
        double z = xOffset * sin + zOffset * cos;

        return TyphonUtils.getHighestRocklikes(this.vent.location.clone().add(x, 0, z).getBlock()).getRelative(BlockFace.UP);
    }

}
