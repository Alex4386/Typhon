package me.alex4386.plugin.typhon.volcano.bomb;

import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoCircleOffsetXZ;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

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

    public int minBombRadius = VolcanoBombsDefault.minBombRadius;
    public int maxBombRadius = VolcanoBombsDefault.maxBombRadius;

    public int bombDelay = VolcanoBombsDefault.bombDelay;
    public double maxDistance = 0;

    public int maximumFallingBlocks = 1000;

    public VolcanoBombs(VolcanoVent vent) {
        this.vent = vent;

        Vector vector = TyphonUtils.calculateVelocity(
                new Vector(0, 0, 0), new Vector(0, 0, vent.craterRadius), 4);
    }


    public Location getLaunchLocation() {
        Location hostLocation;
        if (vent.getType() == VolcanoVentType.FISSURE) {
            hostLocation = TyphonUtils.getHighestLocation(this.vent.selectCoreBlock().getLocation());
        } else {
            int theY = Math.max(vent.getSummitBlock().getY(), vent.location.getBlockY());
            hostLocation = new Location(
                    vent.location.getWorld(),
                    vent.location.getX(),
                    theY,
                    vent.location.getZ());
        }
        return hostLocation;
    }

    public VolcanoBomb generateBomb() {
        Location hostLocation = this.getLaunchLocation();
        return generateBomb(hostLocation);
    }

    public VolcanoBomb generateBomb(Location hostLocation) {
        double multiplier = this.vent.erupt.getStyle().bombMultiplier;
        if (multiplier < 0)
            return null;

        double maxRadius = (1.25 + (Math.random() * 1.0))
                * hostLocation.getWorld().getHighestBlockYAt(hostLocation)
                * multiplier;
        double minRadius = Math.min(vent.craterRadius * 0.7, 20);

        double absoluteMinimumForMax = Math.max(vent.craterRadius * 2, 40);
        double hawaiianFlow = vent.longestNormalLavaFlowLength / 2;

        // 30 degrees
        double adequateCinderConeBaseWidth = (vent.getSummitBlock().getY() - vent.location.getY()) * Math.sqrt(3);
        double calculatedConeBaseWidth = Math.max(absoluteMinimumForMax, adequateCinderConeBaseWidth);

        maxRadius = Math.min(300, Math.max(hawaiianFlow, calculatedConeBaseWidth)) * multiplier;
        maxRadius = Math.min(300 * multiplier, Math.max(maxDistance, maxRadius));

        VolcanoCircleOffsetXZ offsetXZ = VolcanoMath.getCenterFocusedCircleOffset(
                hostLocation.getBlock(), (int) maxRadius, (int) minRadius);
        Block destination = hostLocation.getBlock().getRelative((int) offsetXZ.x, 0, (int) offsetXZ.z);

        float bombPower = (float) VolcanoMath.getZeroFocusedRandom(0) * (maxBombPower - minBombPower)
                + minBombPower;
        int bombRadius = (int) (Math.floor(
                VolcanoMath.getZeroFocusedRandom()
                        * (maxBombRadius - minBombRadius))
                + minBombRadius);

        return this.generateBombToDestination(
                hostLocation, destination.getLocation(), bombPower, bombRadius, bombDelay);
    }


    public VolcanoBomb generateBombToDestination(Location destination) {
        Location hostLocation = this.getLaunchLocation();

        Random random = new Random();

        double volcanoHeight = vent.averageVentHeight() - vent.location.getY();
        double volcanoMax = Math.min(vent.location.getWorld().getMaxHeight() - vent.location.getY(), 150.0);
        float volcanoScaleVar = Math.min(1, (float) (volcanoHeight / volcanoMax));

        float bombPower = (float) VolcanoMath.getZeroFocusedRandom() * (maxBombPower - minBombPower)
                + minBombPower;
        int bombRadius = (int) ((Math.floor(random.nextDouble() * (maxBombRadius - minBombRadius))
                * volcanoScaleVar)
                + minBombRadius);

        return this.generateBombToDestination(hostLocation, destination, bombPower, bombRadius, this.bombDelay);
    }

    public VolcanoBomb generateBombToDestination(
            Location location,
            Location destination,
            float bombPower,
            int bombRadius,
            int bombDelay) {

        VolcanoBomb bomb = new VolcanoBomb(
                vent,
                location,
                destination,
                bombPower,
                bombRadius,
                bombDelay);
        
        return bomb;
    }

    public void requestBombLaunch() {
        if (bombMap.size() > maximumFallingBlocks) {
            VolcanoBomb bomb = this.generateBomb();
            bomb.land();
        } else {
            this.launchBomb();
        }
    }

    public void launchBomb() {
        VolcanoBomb bomb = this.generateBomb();
        this.launchSpecifiedBomb(bomb);
    }

    public void launchBombToDestination(Location location) {
        VolcanoBomb bomb = this.generateBomb(location);
    }

    public void launchSpecifiedBomb(VolcanoBomb bomb) {
        bomb.launch();
        if (bomb.block != null) {
            bombMap.put(bomb.block, bomb);
        }
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

            if (block != null) {
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
                    if ((bomb.prevLocation.equals(bombLocation)
                            && VolcanoBombListener.groundChecker(bombLocation, bomb.bombRadius))
                            || block.isOnGround()) {
    
                        bomb.land();
                        bomb.stopTrail();
                        iterator.remove();
                        continue;
                    } else {
                        bomb.prevLocation = bomb.block.getLocation();
                    }
                }
    
                // Living over 1 min
                if (bomb.lifeTime >= 60) {
                    // Bukkit.getLogger().log(Level.INFO, "Volcano Bomb from Volcano
                    // "+volcano.name+"
                    // died.");
                    bomb.stopTrail();
    
                    if (bomb.block != null) {
                        bomb.block.remove();
                        bomb.block.getLocation().getBlock().setType(Material.AIR);
                        bomb.block = null;
                    }
    
                    bomb.land();
                    iterator.remove();
                } else {
                    bomb.lifeTime++;
                }
            }
        }
    }

    public void importConfig(JSONObject configData) {
        JSONObject bombPower = (JSONObject) configData.get("explosionPower");
        JSONObject bombRadius = (JSONObject) configData.get("radius");

        minBombPower = (float) ((double) bombPower.get("min"));
        maxBombPower = (float) ((double) bombPower.get("max"));
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

        JSONObject bombRadius = new JSONObject();
        bombRadius.put("min", minBombRadius);
        bombRadius.put("max", maxBombRadius);

        configData.put("explosionPower", bombPower);
        configData.put("radius", bombRadius);
        configData.put("delay", bombDelay);
        configData.put("maxDistance", maxDistance);

        return configData;
    }
}
