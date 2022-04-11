package me.alex4386.plugin.typhon.volcano.bomb;

import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.FallingBlock;
import org.bukkit.util.Vector;
import org.json.simple.JSONObject;

import java.util.*;

public class VolcanoBombs {
    public VolcanoVent vent;

    // changed to hashmap to make indexing and querying way faster.
    public Map<FallingBlock, VolcanoBomb> bombMap = new HashMap<>();

    public float minBombPower = VolcanoBombsDefault.minBombPower;
    public float maxBombPower = VolcanoBombsDefault.maxBombPower;

    public float minBombLaunchPower = VolcanoBombsDefault.minBombLaunchPower;
    public float maxBombLaunchPower = VolcanoBombsDefault.maxBombLaunchPower;

    public int minBombRadius = VolcanoBombsDefault.minBombRadius;
    public int maxBombRadius = VolcanoBombsDefault.maxBombRadius;

    public int bombDelay = VolcanoBombsDefault.bombDelay;
    public double maxDistance = 0;

    public VolcanoBombs(VolcanoVent vent) {
        this.vent = vent;

        Vector vector = TyphonUtils.calculateVelocity(
                new Vector(0,0, 0),
                new Vector(0, 0, vent.craterRadius),
                4
        );
        this.minBombLaunchPower = vector.getBlockZ();
    }

    public void launchBomb(Location location, double bombLaunchPower, float bombPower, int bombRadius, int bombDelay) {
        Random random = new Random();

        // get random radian angle
        double randomAngle = Math.random() * Math.PI * 2;

        // super power ratio
        double powerRatioX = Math.sin(randomAngle);
        double powerRatioZ = Math.cos(randomAngle);

        float powerX = (float) (bombLaunchPower * powerRatioX);
        float powerZ = (float) (bombLaunchPower * powerRatioZ);

        int launchY = 15;
        float powerY = (random.nextFloat() * (float) 1.5) + ((launchY + 4 - 9) / (float) 25.0) + 3f;

        bombRadius = (bombRadius < 1) ? 1 : bombRadius;

        VolcanoBomb bomb = new VolcanoBomb(vent, location, powerX, powerY, powerZ, bombPower, bombRadius, bombDelay);

        bombMap.put(bomb.block, bomb);
    }

    public void launchBombToDestination(Location location, Location destination, float bombPower, int bombRadius, int bombDelay) {

        int maxY = vent.getSummitBlock().getY();
        int yToLaunch = maxY - vent.location.getWorld().getHighestBlockYAt(vent.location);

        Vector vector = TyphonUtils.calculateVelocity(
                new Vector(0,0,0),
                destination.toVector().subtract(location.toVector()),
                yToLaunch + 6
        );

        VolcanoBomb bomb = new VolcanoBomb(vent, location, (float) vector.getX(), (float) vector.getY(), (float) vector.getZ(), bombPower, bombRadius, bombDelay);

        bombMap.put(bomb.block, bomb);
    }

    public void launchBomb(Location hostLocation) {
        Random random = new Random();

        double volcanoHeight = vent.averageVentHeight() - vent.location.getY();
        double volcanoMax = Math.min(vent.location.getWorld().getMaxHeight() - vent.location.getY(), 150.0);
        float volcanoScaleVar = Math.min(1, (float) (volcanoHeight / volcanoMax));

        // calculate maximum power
        double maxPower = 20.0 * (this.vent.longestFlowLength / 150);

        double minBombLaunchPower = this.minBombLaunchPower * volcanoScaleVar;
        double maxBombLaunchPower = Math.max(this.maxBombLaunchPower, maxPower);

        double bombLaunchPower = (((maxBombLaunchPower - minBombLaunchPower) * Math.random()) + minBombLaunchPower);
        float bombPower = (float) VolcanoMath.getZeroFocusedRandom() * (maxBombPower - minBombPower) + minBombPower;
        int bombRadius = (int) ((Math.floor(random.nextDouble() * (maxBombRadius - minBombRadius)) * volcanoScaleVar) + minBombRadius);

        launchBomb(hostLocation, bombLaunchPower, bombPower, bombRadius, this.bombDelay);
    }

    public Location getLaunchLocation() {
        Location hostLocation;
        if (vent.getType() == VolcanoVentType.FISSURE) {
            Block whereToFlow = vent.selectCoreBlock();
            hostLocation = whereToFlow.getLocation();
        } else {
            int theY = Math.max(vent.getSummitBlock().getY(), vent.location.getBlockY());
            hostLocation = new Location(vent.location.getWorld(), vent.location.getX(), theY, vent.location.getZ());
        }
        return hostLocation;
    }

    public void launchBombToDestination(Location destination) {
        Location hostLocation = this.getLaunchLocation();

        Random random = new Random();

        double volcanoHeight = vent.averageVentHeight() - vent.location.getY();
        double volcanoMax = Math.min(vent.location.getWorld().getMaxHeight() - vent.location.getY(), 150.0);
        float volcanoScaleVar = Math.min(1, (float) (volcanoHeight / volcanoMax));

        float bombPower = (float) VolcanoMath.getZeroFocusedRandom() * (maxBombPower - minBombPower) + minBombPower;
        int bombRadius = (int) ((Math.floor(random.nextDouble() * (maxBombRadius - minBombRadius)) * volcanoScaleVar) + minBombRadius);

        launchBombToDestination(hostLocation, destination, bombPower, bombRadius, this.bombDelay);
    }

    public void launchBomb() {
        Location hostLocation = this.getLaunchLocation();
        launchBomb(hostLocation);
    }

    public void shutdown() {
        Iterator<Map.Entry<FallingBlock, VolcanoBomb>> iterator = bombMap.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<FallingBlock, VolcanoBomb> entry = iterator.next();
            FallingBlock block = entry.getKey();
            VolcanoBomb bomb = entry.getValue();

            bomb.isLanded = true;
            iterator.remove();

            block.remove();
        }
    }


    public void trackAll() {
        Iterator<Map.Entry<FallingBlock, VolcanoBomb>> iterator = bombMap.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<FallingBlock, VolcanoBomb> entry = iterator.next();
            FallingBlock block = entry.getKey();
            VolcanoBomb bomb = entry.getValue();

            if (!block.getLocation().getChunk().isLoaded()) {
                block.getLocation().getChunk().load();
            }
            block.setTicksLived(1);

            if (bomb.isLanded) {
                bomb.stopTrail();
                iterator.remove();
                continue;
            }

            Location bombLocation = block.getLocation();

            if (bomb.prevLocation == null) {
                bomb.prevLocation = bombLocation;
            } else {
                if ((bomb.prevLocation.equals(bombLocation) && VolcanoBombListener.groundChecker(bombLocation, bomb.bombRadius)) || block.isOnGround()) {

                    bomb.land();
                    bomb.stopTrail();
                    iterator.remove();
                    continue;
                } else {
                    bomb.prevLocation = bomb.block.getLocation();
                }
            }

            // Living over 1 min
            if (bomb.lifeTime >= 120) {
                //Bukkit.getLogger().log(Level.INFO, "Volcano Bomb from Volcano "+volcano.name+" died.");
                bomb.stopTrail();
                bomb.block.remove();
                bomb.block.getLocation().getBlock().setType(Material.AIR);

                iterator.remove();
            } else {
                bomb.lifeTime++;
            }


        }

    }


    public void importConfig(JSONObject configData) {
        JSONObject bombPower = (JSONObject) configData.get("explosionPower");
        JSONObject bombLaunchPower = (JSONObject) configData.get("launchPower");
        JSONObject bombRadius = (JSONObject) configData.get("radius");

        minBombPower = (float) ((double) bombPower.get("min"));
        maxBombPower = (float) ((double) bombPower.get("max"));
        minBombLaunchPower = (float) ((double) bombLaunchPower.get("min"));
        maxBombLaunchPower = (float) ((double) bombLaunchPower.get("max"));
        minBombRadius = (int) ((long) bombRadius.get("min"));
        maxBombRadius = (int) ((long) bombRadius.get("max"));
        bombDelay = (int) ((long) configData.get("delay"));
        maxDistance = (double) configData.get("maxDistance");
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        JSONObject bombPower = new JSONObject();
        bombPower.put("min", minBombPower);
        bombPower.put("max", maxBombPower);

        JSONObject bombLaunchPower = new JSONObject();
        bombLaunchPower.put("min", minBombLaunchPower);
        bombLaunchPower.put("max", maxBombLaunchPower);

        JSONObject bombRadius = new JSONObject();
        bombRadius.put("min", minBombRadius);
        bombRadius.put("max", maxBombRadius);

        configData.put("explosionPower", bombPower);
        configData.put("launchPower", bombLaunchPower);
        configData.put("radius", bombRadius);
        configData.put("delay", bombDelay);
        configData.put("maxDistance", maxDistance);

        return configData;
    }

}
