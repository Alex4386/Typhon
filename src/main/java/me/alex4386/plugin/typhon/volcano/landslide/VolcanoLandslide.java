package me.alex4386.plugin.typhon.volcano.landslide;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonScheduler;
import me.alex4386.plugin.typhon.TyphonSounds;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.ash.VolcanoPyroclasticFlow;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

import java.util.*;

public class VolcanoLandslide {
    public VolcanoVent vent;
    public double landslideAngle = 0;
    public int initSummitY = Integer.MIN_VALUE;

    public Map<Chunk, Iterator<Block>> iteratorPerChunk = null;

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
            this.landslideTimer = TyphonScheduler.registerGlobalTask(
                    this::runTask, 1
            );
        }
    }

    public void unregisterTask() {
        if (this.landslideTimer >= 0) {
            TyphonScheduler.unregisterTask(this.landslideTimer);
            this.landslideTimer = -1;
        }
    }

    public void clear() {
        this.iteratorPerChunk = null;
        this.initSummitY = Integer.MIN_VALUE;
        this.landslideAngle = 0;
    }

    public void runTask() {
        if (this.iteratorPerChunk == null) return;
        if (this.iteratorPerChunk.isEmpty()) {
            this.clear();
            return;
        }

        for (Chunk chunk : this.iteratorPerChunk.keySet()) {
            if (this.iteratorPerChunk.get(chunk) == null || !this.iteratorPerChunk.get(chunk).hasNext()) {
                this.iteratorPerChunk.remove(chunk);
                continue;
            }
            TyphonScheduler.run(chunk, () -> this.runChunk(chunk));
        }

        if (Math.random() < 0.1) {
            TyphonSounds.DISTANT_EXPLOSION.play(this.vent.location, SoundCategory.BLOCKS, 2f, 1f);
        }

        this.runPyroclasticFlow();
    }

    public void runChunk(Chunk chunk) {
        Iterator<Block> iterator = this.iteratorPerChunk.get(chunk);
        if (iterator == null) return;

        int count = 0;
        while (iterator.hasNext() && count < 100) {
            Block block = iterator.next();
            this.runCollapse(block);
            count++;
        }

        if (!iterator.hasNext()) {
            this.iteratorPerChunk.remove(chunk);
        }
    }

    public void start() {
        if (!this.isConfigured()) return;

        if (this.iteratorPerChunk == null) {
            this.generateIterator();
        }
    }

    public void generateIterator() {
        List<Block> blocks = this.getTargetBlocks();
        Map<Chunk, List<Block>> blocksPerChunk = new HashMap<>();

        for (Block block : blocks) {
            Chunk chunk = block.getChunk();
            if (!blocksPerChunk.containsKey(chunk)) {
                blocksPerChunk.put(chunk, new ArrayList<>());
            }

            blocksPerChunk.get(chunk).add(block);
        }

        Map<Chunk, Iterator<Block>> iteratorPerChunk = new HashMap<>();
        for (Chunk chunk : blocksPerChunk.keySet()) {
            List<Block> blockList = blocksPerChunk.get(chunk);
            iteratorPerChunk.put(chunk, blockList.iterator());
        }

        this.iteratorPerChunk = iteratorPerChunk;
    }

    public boolean isConfigured() {
        return this.initSummitY != Integer.MIN_VALUE;
    }

    public void configure() {
        this.initSummitY = (int) (this.vent.getSummitBlock().getY() + (this.vent.getRadius() / Math.sqrt(3)));
        if (this.landslideAngle == 0) {
            this.landslideAngle = Math.random() * Math.PI * 2;
        }
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
        if (x < 0) {
            return (int) (this.getRimSummitY() - this.getRadius() - x);
        }

        int zeroY = this.getRimSummitY() - this.getRadius();
        double offset = ((1.0 / 12.0) * x);
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
        double radius = this.getRadius() * 1.25;

        Set<Block> blocks = new HashSet<>(VolcanoMath.getCircle(this.vent.location.getBlock(), (int) radius));

        double cos = Math.cos(this.landslideAngle);
        double sin = Math.sin(this.landslideAngle);

        int length = this.getRadius() * 8;
        for (double x = 0; x < length; x += 0.5) {
            for (double z = -radius; z < radius; z += 0.5) {
                double rotatedX = x * cos - z * sin;
                double rotatedZ = x * sin + z * cos;

                Block block = this.vent.location.getBlock().getRelative((int) rotatedX, 0, (int) rotatedZ);
                blocks.add(block);
            }
        }

        return blocks.stream().toList();
    }

    public void runCollapse(Block block) {
        World world = block.getWorld();
        Vector vector = this.getVector(block.getLocation());
        int y = this.getY(vector);

        Block topBlock = TyphonUtils.getHighestRocklikes(block);
        int currentY = topBlock.getY();
        System.out.println("Drilling down from " + currentY + " to " + y);
        if (currentY < y) return;

        for (int i = currentY; i > y; i--) {
            Block targetBlock = topBlock.getRelative(0, i - currentY, 0);
            if (!targetBlock.getType().isAir()) {
                Material material = i <= world.getSeaLevel() ?
                        world.isUltraWarm() ? Material.LAVA : Material.WATER
                        : Material.AIR;

                this.vent.lavaFlow.queueBlockUpdate(targetBlock, material);
            }
        }
    }

    public void runPyroclasticFlow() {
        Block source = this.getPyroclasticFlowSource();
        VolcanoPyroclasticFlow flow = this.vent.ash.triggerPyroclasticFlow(source);

        Vector direction = new Vector(1,0,0);

        double angle = this.landslideAngle;

        // add -20~20 degrees
        angle += ((Math.random() * 2) - 1) * Math.toRadians(20);

        // rotate to landslideAngle
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);

        double x = direction.getX() * cos - direction.getZ() * sin;
        double z = direction.getX() * sin + direction.getZ() * cos;

        flow.setDirection(new Vector(x, 0, z));
        this.vent.ash.createAshPlume();
    }

    public Block getPyroclasticFlowSource() {
        double xOffset = this.getRadius() * 8;
        double zOffset = ((Math.random() * 2) - 1) * this.getRadius();

        double cos = Math.cos(this.landslideAngle);
        double sin = Math.sin(this.landslideAngle);

        double x = xOffset * cos - zOffset * sin;
        double z = xOffset * sin + zOffset * cos;

        Block baseBlock = this.vent.location.clone().add(x, 255, z).getBlock();
        baseBlock = TyphonUtils.getHighestRocklikes(baseBlock);

        return TyphonUtils.getHighestRocklikes(baseBlock).getRelative(BlockFace.UP);
    }

}
