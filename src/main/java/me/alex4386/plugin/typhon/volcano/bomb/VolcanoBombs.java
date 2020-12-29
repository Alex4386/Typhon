package me.alex4386.plugin.typhon.volcano.bomb;

import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.crater.VolcanoCrater;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import org.bukkit.Location;
import org.bukkit.entity.FallingBlock;
import org.json.simple.JSONObject;

import java.util.*;

public class VolcanoBombs {
    public VolcanoCrater crater;

    // changed to hashmap to make indexing and querying way faster.
    public Map<FallingBlock, VolcanoBomb> bombMap = new HashMap<>();

    public float minBombPower = VolcanoBombsDefault.minBombPower;
    public float maxBombPower = VolcanoBombsDefault.maxBombPower;

    public float minBombLaunchPower = VolcanoBombsDefault.minBombLaunchPower;
    public float maxBombLaunchPower = VolcanoBombsDefault.maxBombLaunchPower;

    public int minBombRadius = VolcanoBombsDefault.minBombRadius;
    public int maxBombRadius = VolcanoBombsDefault.maxBombRadius;

    public int bombDelay = VolcanoBombsDefault.bombDelay;

    public VolcanoBombs(VolcanoCrater crater) {
        this.crater = crater;
    }

    public void launchBomb(Location location, double bombLaunchPower, float bombPower, int bombRadius, int bombDelay) {
        // get random radian angle
        double randomAngle = Math.random() * Math.PI * 2;

        // super power ratio
        double powerRatioX = Math.sin(randomAngle);
        double powerRatioZ = Math.cos(randomAngle);

        float powerX = (float) (bombLaunchPower * powerRatioX);
        float powerZ = (float) (bombLaunchPower * powerRatioZ);

        bombRadius = (bombRadius < 1) ? 1 : bombRadius;

        VolcanoBomb bomb = new VolcanoBomb(crater, location, powerX, powerZ, bombPower, bombRadius, bombDelay);

        bombMap.put(bomb.block, bomb);
    }

    public void launchBomb(Location hostLocation) {
        Random random = new Random();

        float volcanoScaleVar = (crater.getSummitBlock().getY() / (float)crater.getVolcano().heightLimit);

        double bombLaunchPower = (((maxBombLaunchPower - minBombLaunchPower) * Math.random()) + minBombLaunchPower) * volcanoScaleVar;
        float bombPower = (float) VolcanoMath.getZeroFocusedRandom() * (maxBombPower - minBombPower) + minBombPower;
        int bombRadius = (int) ((Math.floor(random.nextDouble() * (maxBombRadius - minBombRadius)) * volcanoScaleVar) + minBombRadius);

        launchBomb(hostLocation, bombLaunchPower, bombPower, bombRadius, this.bombDelay);
    }


    public void launchBomb() {
        int theY = crater.getSummitBlock().getY() >= crater.location.getWorld().getHighestBlockYAt(crater.location) ? crater.getSummitBlock().getY() : crater.location.getWorld().getHighestBlockYAt(crater.location)+2;
        Location hostLocation = new Location(crater.location.getWorld(), crater.location.getX(), theY, crater.location.getZ());

        launchBomb(hostLocation);
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

        return configData;
    }

}
