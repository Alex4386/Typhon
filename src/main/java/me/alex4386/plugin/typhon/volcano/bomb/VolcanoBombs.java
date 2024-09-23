package me.alex4386.plugin.typhon.volcano.bomb;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonScheduler;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentType;

import org.bukkit.Bukkit;
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

    public int exploderJob = -1;

    public float minBombPower = VolcanoBombsDefault.minBombPower;
    public float maxBombPower = VolcanoBombsDefault.maxBombPower;

    public int minBombRadius = VolcanoBombsDefault.minBombRadius;
    public int maxBombRadius = VolcanoBombsDefault.maxBombRadius;

    public int bombDelay = VolcanoBombsDefault.bombDelay;
    public double maxDistance = 0;

    public int maximumFallingBlocks = 1000;
    public int baseY = Integer.MIN_VALUE;

    boolean isBaseYConfigured = true;

    public int getBaseY() {
        if (baseY == Integer.MIN_VALUE) {
            baseY = (int) vent.averageVentHeight();
        } else {
            if (!isBaseYConfigured) {
                int average = (int) vent.averageVentHeight();
                if (average < baseY) {
                    baseY = average;
                }
                isBaseYConfigured = true;
            }
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
        this.registerTask();
    }

    public void shutdown() {
        this.unregisterTask();
        this.cleanupAllBombs();
    }

    public void reset() {
        this.resetBaseY();
    }

    public void registerTask() {
        if (exploderJob < 0) {
            this.exploderJob = TyphonScheduler.registerGlobalTask(
                    () -> {
                        this.processExploderTimer();
                    },
                    this.vent.getVolcano().updateRate
            );
        }
    }

    public void unregisterTask() {
        if (exploderJob >= 0) {
            TyphonScheduler.unregisterTask(this.exploderJob);
            this.exploderJob = -1;
        }
    }

    public void processExploderTimer() {
        for (VolcanoBomb bomb : this.bombMap.values()) {
            if (bomb.explodeTimer < 0) continue;

            if (bomb.explodeTimer > 0) {
                bomb.explodeTimer = bomb.explodeTimer - (int) this.vent.getVolcano().updateRate;

                // safety net - always false, but just to make sure
                if (bomb.explodeTimer < 0) bomb.explodeTimer = 0;
            } else {
                // should explode
                bomb.explode();
                bomb.explodeTimer = -1;
            }
        }
    }

    public void cleanupAllBombs() {
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

        double maxRadius = (1.25 + Math.random())
                * hostLocation.getWorld().getHighestBlockYAt(hostLocation)
                * Math.pow(1.1, multiplier);

        double minRadius = Math.max(vent.craterRadius, 20);

        Block destination = TyphonUtils.getHighestRocklikes(TyphonUtils.getFairRandomBlockInRange(hostLocation.getBlock(), (int) minRadius, (int) maxRadius));

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
        int bombRadius = (int) (Math.floor(random.nextDouble() * (maxBombRadius - minBombRadius)) + minBombRadius);
        return this.generateBombToDestination(destination, bombRadius);
    }

    public double distanceHeightRatio() {
        double silicateLevel = this.vent.lavaFlow.settings.silicateLevel;
        double ratio = Math.min(Math.max(0, (silicateLevel - 0.45) / (0.7 - 0.45)), 1);
        return ((Math.sqrt(3) - 1) * (1 - ratio)) + 1;
    }

    public double getEffectiveConeY() {
        int baseY = this.getBaseY();

        int minimumScaffoldBombRadius = this.vent.getRadius() * 2;
        double minimumScaffoldConeHeight = (minimumScaffoldBombRadius / this.distanceHeightRatio());
        double minimumRequiredSummitHeight = baseY + minimumScaffoldConeHeight;

        double fakeConeHeight = this.vent.getRadius() / this.distanceHeightRatio();
        double summitBlockTargetHeight = this.vent.getSummitBlock().getY() + fakeConeHeight;

        return Math.max(minimumRequiredSummitHeight, summitBlockTargetHeight);
    }

    public int getEffectiveConeHeight() {
        return (int) (this.getEffectiveConeY() - this.getBaseY());
    }

    public double getAdequateHeightFromDistance(double distance) {
        return this.getEffectiveConeY() - (distance / this.distanceHeightRatio());
    }

    public VolcanoBomb generateConeBuildingBomb() {
        int minRadius = 3;

        int baseYHeight = this.getEffectiveConeHeight();
        double coneRadius = (baseYHeight * this.distanceHeightRatio());
        int maxRadius = (int) Math.max(coneRadius * 1.5, minRadius);
        int defaultRadius = maxRadius;

        boolean outsideCinderCone = false;
        if (Math.random() < 0.001) {
            maxRadius = (int) Math.max(this.vent.longestNormalLavaFlowLength, maxRadius);
            outsideCinderCone = true;
        }

        int distance = (int) ((1 - Math.pow(Math.random(), 2)) * (maxRadius - minRadius) + minRadius);
        if (outsideCinderCone) {
            distance = (int) (Math.pow(Math.random(), 2) * (maxRadius - defaultRadius) + defaultRadius);
        }

        double distanceFromCore = distance;
        double adequateHeight = this.getAdequateHeightFromDistance(distanceFromCore);

        Block randomBlock = TyphonUtils.getHighestRocklikes(
                TyphonUtils.getFairRandomBlockInRange(
                        this.vent.getCoreBlock(), (int) distanceFromCore, (int) distanceFromCore));

        double diff = adequateHeight - randomBlock.getY();

        if (diff > 0) {
            int maxBombRadius = 1;
            if (distanceFromCore < this.vent.craterRadius * 1) maxBombRadius = 1;
            else maxBombRadius = (int) Math.min(4, (distanceFromCore / this.vent.craterRadius));

            // if diff is too big
            if (distanceFromCore < diff) {
                if (distanceFromCore < this.vent.craterRadius * 5) {
                    double targetMax = Math.pow((Math.max(0, distanceFromCore - this.vent.craterRadius) / this.vent.craterRadius * 4), 2);
                    maxBombRadius = (int) Math.max(Math.min(8 * targetMax, diff / 2), maxBombRadius);
                }
            }

            int radius = 0;
            if (diff < 1) radius = 0;
            else if (diff <= 3) radius = 1;
            else radius = 2;

            double height = this.vent.averageVentHeight() - this.vent.location.getBlockY();
            if ((height / 3) < radius) {
                radius = Math.max(1, Math.min((int) (height / 3), radius));
            }

            int willGrowUpTo = randomBlock.getY() + radius;
            if (willGrowUpTo > this.vent.averageVentHeight() + 2) {
                radius -= (willGrowUpTo - (int) (this.vent.averageVentHeight() + 2));
            }

            if (radius < 0) return null;
            return this.generateBombToDestination(randomBlock.getLocation(), radius);
        } else if (diff < 0 && (randomBlock.getY() > this.getBaseY())) {
            if (Math.random() < 0.1 && diff < -3) {
                if (randomBlock.getY() > randomBlock.getWorld().getSeaLevel()) {
                    randomBlock.getWorld().createExplosion(
                            randomBlock.getLocation(),
                            (float) (2f + (Math.random() * 2f))
                    );
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
        this.isBaseYConfigured = false;
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
