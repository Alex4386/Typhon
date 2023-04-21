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
    public int baseY = Integer.MIN_VALUE;

    public int getBaseY() {
        if (baseY == Integer.MIN_VALUE) {
            baseY = (int) vent.averageVentHeight();
        }

        return baseY;
    }

    public void resetBaseY() {
        this.baseY = Integer.MIN_VALUE;
    }

    public VolcanoBombs(VolcanoVent vent) {
        this.vent = vent;

        Vector vector = TyphonUtils.calculateVelocity(
                new Vector(0, 0, 0), new Vector(0, 0, vent.craterRadius), 4);
    }

    public void initialize() {

    }

    public void reset() {
        this.resetBaseY();
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
        if (Math.random() < 0.95) {
            VolcanoBomb bomb = this.generateConeBuildingBomb();
            if (bomb == null) bomb = this.generateRandomBomb(hostLocation);
            return bomb;
        } else {
            return this.generateRandomBomb(hostLocation);
        }
    }

    public VolcanoBomb generateRandomBomb(Location hostLocation) {
        double multiplier = this.vent.erupt.getStyle().bombMultiplier;
        if (multiplier < 0)
            return null;

        double maxRadius = (1.25 + (Math.random() * 1.0))
                * hostLocation.getWorld().getHighestBlockYAt(hostLocation)
                * multiplier;

        double minRadius = Math.max(vent.craterRadius, 20);

        Block destination = TyphonUtils.getFairRandomBlockInRange(hostLocation.getBlock(), (int) minRadius, (int) maxRadius);

        float bombPower = (float) VolcanoMath.getZeroFocusedRandom(0) * (maxBombPower - minBombPower)
                + minBombPower;
        int bombRadius = (int) (Math.floor(
                VolcanoMath.getZeroFocusedRandom()
                        * (maxBombRadius - minBombRadius))
                + minBombRadius);

        return this.generateBombToDestination(
                hostLocation, destination.getLocation(), bombPower, bombRadius, bombDelay);
    }


    public VolcanoBomb generateBombToDestination(Location destination, int bombRadius) {
        Location hostLocation = this.getLaunchLocation();


        float bombPower = (float) VolcanoMath.getZeroFocusedRandom() * (maxBombPower - minBombPower)
                + minBombPower;

        return this.generateBombToDestination(hostLocation, destination, bombPower, bombRadius, this.bombDelay);
    }

    public VolcanoBomb generateBombToDestination(Location destination) {
        Random random = new Random();

        double volcanoHeight = vent.averageVentHeight() - vent.location.getY();
        double volcanoMax = Math.min(vent.location.getWorld().getMaxHeight() - vent.location.getY(), 150.0);

        float volcanoScaleVar = Math.min(1, (float) (volcanoHeight / volcanoMax));
        int bombRadius = (int) ((Math.floor(random.nextDouble() * (maxBombRadius - minBombRadius))
                * volcanoScaleVar)
                + minBombRadius);

        return this.generateBombToDestination(destination, bombRadius);
    }

    public double distanceHeightRatio() {
        double silicateLevel = this.vent.lavaFlow.settings.silicateLevel;
        double ratio = Math.min(Math.max(0, (silicateLevel - 0.45) / (0.7 - 0.45)), 1);
        return ((Math.sqrt(3) - 1) * (1 - ratio)) + 1;
    }

    public VolcanoBomb generateConeBuildingBomb() {
        int minRadius = this.vent.craterRadius;
        int baseY = this.getBaseY();

        int minimumScaffoldBombRadius = minRadius * 2;
        double minimumScaffoldConeHeight = (minimumScaffoldBombRadius / this.distanceHeightRatio());
        double minimumRequiredSummitHeight = baseY + minimumScaffoldConeHeight;

        double effectiveSummitHeight = Math.max(minimumRequiredSummitHeight, this.vent.getSummitBlock().getY());
        int baseYHeight = (int) (effectiveSummitHeight - baseY);

        double coneRadius = (baseYHeight * this.distanceHeightRatio());
        int maxRadius = (int) Math.max(coneRadius * 1.5, minRadius);
        int defaultRadius = maxRadius;

        boolean outsideCinderCone = false;
        if (Math.random() < 0.25) {
            maxRadius = (int) Math.max(this.vent.longestNormalLavaFlowLength, maxRadius);
            outsideCinderCone = true;
        }

        int distance = (int) ((1 - Math.pow(Math.random(), 2)) * (maxRadius - minRadius) + minRadius);
        if (outsideCinderCone) {
            distance = (int) (Math.random() * (maxRadius - defaultRadius) + defaultRadius);
        }

        double adequateHeight = baseY + effectiveSummitHeight - (distance / this.distanceHeightRatio());
        double distanceFromCore = distance;

        Block randomBlock = TyphonUtils.getHighestRocklikes(TyphonUtils.getFairRandomBlockInRange(this.vent.getCoreBlock(), (int) distanceFromCore, (int) distanceFromCore));
        double diff = adequateHeight - randomBlock.getY();

        if (diff > 0) {
            int maxBombRadius = 1;
            if (distanceFromCore < this.vent.craterRadius * 2) maxBombRadius = 1;
            else maxBombRadius = (int) Math.min(4, (distanceFromCore / this.vent.craterRadius));

            int radius = 0;
            if (diff < 1) radius = 0;
            else if (diff <= 3) radius = 1;
            else radius = (int) Math.min(maxBombRadius, (diff - 1) / 2);

            return this.generateBombToDestination(randomBlock.getLocation(), radius);
        } else if (diff < 0) {
            if (Math.random() < 0.25) {
                if (TyphonUtils.getTwoDimensionalDistance(this.vent.getNearestCoreBlock(randomBlock.getLocation()).getLocation(), randomBlock.getLocation()) < this.vent.longestNormalLavaFlowLength) {
                    // inside volcano.
                    if (randomBlock.getY() > randomBlock.getWorld().getSeaLevel()) {
                        randomBlock.getWorld().createExplosion(
                                randomBlock.getLocation(),
                                (float) (2f + (Math.random() * 4f))
                        );
                    }
                }
            }
        }

        return null;
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

            if (!bomb.isLanded) {
                bomb.emergencyLand();
            }

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
                        bomb.block.getLocation().getBlock().setType(Material.AIR);
                        bomb.emergencyLand();
                    } else {
                        bomb.land();
                    }
    
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
        maxDistance = (double) configData.getOrDefault("maxDistance", 0);
        baseY = (int) ((long) configData.get("baseY"));
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
        configData.put("baseY", baseY);

        return configData;
    }
}
