package me.alex4386.plugin.typhon;

import org.bukkit.block.Block;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class TyphonUtils {

    /* These are added for future-proofing */
    public static int getMinimumY(org.bukkit.World world) {
        // preparing for 1.17's world minHeight and maxHeight changes

        return 0;
    }

    public static int getMaximumY(org.bukkit.World world) {
        // preparing for 1.17's world minHeight and maxHeight changes

        return world.getMaxHeight() - getMinimumY(world);
    }

    public static org.bukkit.Location getHighestLocation(org.bukkit.Location loc) {
        int y = loc.getWorld().getHighestBlockYAt(loc);
        return new org.bukkit.Location(
                loc.getWorld(),
                loc.getX(),
                y,
                loc.getZ()
        );
    }

    public static org.bukkit.Location getHighestOceanFloor(org.bukkit.Location loc) {
        int y = loc.getWorld().getHighestBlockYAt(loc, org.bukkit.HeightMap.OCEAN_FLOOR);
        return new org.bukkit.Location(
                loc.getWorld(),
                loc.getX(),
                y,
                loc.getZ()
        );
    }

    public static List<Block> getNearByBlocks(org.bukkit.block.Block baseBlock) {
        return getNearByBlocks(baseBlock, 1);
    }

    public static org.bukkit.Location getLowestBedrockCeiling(org.bukkit.Location loc) {
        int x = loc.getBlockX();
        int z = loc.getBlockZ();

        int MAX_BEDROCK_HEIGHT = 10;

        org.bukkit.block.Block block = loc.getWorld().getBlockAt(x, 1, z);

        for (int i = getMinimumY(loc.getWorld()); i <= MAX_BEDROCK_HEIGHT; i++) {
            if (block.getType() != org.bukkit.Material.BEDROCK) {
                return new org.bukkit.Location(
                        loc.getWorld(),
                        x,
                        i - 1,
                        z
                );
            } else {
                block = block.getRelative(org.bukkit.block.BlockFace.UP);
            }
        }


        System.out.println("EXCEPTION OCCURRED! FUCK - LOWEST BEDROCK CEILING WAS NOT FOUND AT x = "+x+", z = "+z);
        // Um... This really shouldn't happen.
        return null;
    }

    public static List<org.bukkit.block.Block> getNearByBlocks(org.bukkit.block.Block baseBlock, int radius) {
        List<org.bukkit.block.Block> nearByBlocks = new ArrayList<>();

        for (int i = -radius; i <= radius; i++) {
            for (int j = -radius; j <= radius; j++) {
                for (int k = -radius; k <= radius; k++) {
                    if (i == 0 && j == 0 && k == 0) continue;
                    if (Math.pow(i,2) + Math.pow(j,2) + Math.pow(k,2) > Math.pow(radius,2)) continue;

                    nearByBlocks.add(
                            baseBlock.getRelative(i, j, k)
                    );
                }
            }
        }

        return nearByBlocks;
    }

    public static int getLavaLevel(org.bukkit.block.Block block) {
        org.bukkit.block.data.Levelled levelled = (org.bukkit.block.data.Levelled) block.getBlockData();
        return levelled.getLevel();
    }

    public static void setLavaLevel(org.bukkit.block.Block block, int level) {
        org.bukkit.block.data.Levelled levelled = (org.bukkit.block.data.Levelled) block.getBlockData();
        levelled.setLevel(level);

        block.setBlockData(levelled);
    }

    public static double getTwoDimensionalDistance(org.bukkit.Location loc1, org.bukkit.Location loc2) {
        double x = loc1.getX() - loc2.getX();
        double z = loc1.getZ() - loc2.getZ();

        return Math.sqrt(Math.pow(x,2) + Math.pow(z,2));
    }

    public static String blockLocationTostring(org.bukkit.block.Block block) {
        if (block == null) return "NULL";
        return block.getX()+","+block.getY()+","+block.getZ()+" @ "+block.getWorld().getName();
    }

    public static String readFile(java.io.File file) throws java.io.FileNotFoundException, java.io.IOException {
        java.io.BufferedReader bufferedReader = new java.io.BufferedReader(new java.io.FileReader(file));

        String str = "";
        String tmp;
        while ((tmp = bufferedReader.readLine()) != null) {
            str += tmp+"\n";
        }

        return str;
    }

    public static org.json.simple.JSONObject parseJSON(java.io.File file) throws org.json.simple.parser.ParseException, java.io.IOException {
        return parseJSON(readFile(file));
    }

    public static org.json.simple.JSONObject parseJSON(String string) throws org.json.simple.parser.ParseException {
        org.json.simple.parser.JSONParser parser = new org.json.simple.parser.JSONParser();
        org.json.simple.JSONObject object = (org.json.simple.JSONObject) parser.parse(string);

        return object;
    }

    public static org.json.simple.JSONObject serializeLocationForJSON(org.bukkit.Location loc) {
        JSONObject object = new JSONObject();

        String world = loc.getWorld().getName();
        double x = loc.getX();
        double y = loc.getY();
        double z = loc.getZ();

        object.put("world", world);
        object.put("x", x);
        object.put("y", y);
        object.put("z", z);

        return object;
    }

    public static org.bukkit.Location deserializeLocationForJSON(JSONObject locationJSON) {
        String world = (String) locationJSON.get("world");
        double x = (double) locationJSON.get("x");
        double y = (double) locationJSON.get("y");
        double z = (double) locationJSON.get("z");

        return new org.bukkit.Location(org.bukkit.Bukkit.getWorld(world), x, y, z);
    }

    public static void writeJSON(java.io.File file, org.json.simple.JSONObject jsonObject) throws java.io.IOException {
        writeString(file, jsonObject.toJSONString());
    }

    public static void writeString(java.io.File file, String string) throws java.io.IOException {
        if (!file.exists()) file.createNewFile();

        java.io.FileWriter writer = new java.io.FileWriter(file);
        writer.write(string);
        writer.close();
    }

    public static org.bukkit.block.Block getHighestNonTreeSolid(org.bukkit.block.Block block) {
        return getHighestNonTreeSolid(block.getLocation());
    }

    public static org.bukkit.block.Block getHighestNonTreeSolid(org.bukkit.Location loc) {
        org.bukkit.block.Block highestBlock = getHighestLocation(loc).getBlock();

        while (isMaterialTree(highestBlock.getType()) || highestBlock.isPassable()) {
            highestBlock = highestBlock.getRelative(0, -1, 0);
        }

        return highestBlock;
    }

    public static boolean isMaterialTree(org.bukkit.Material material) {
        String materialType = material.name().toLowerCase();
        return (
                materialType.contains("leaves") ||
                        materialType.contains("log") ||
                        materialType.contains("plank") ||
                        materialType.contains("wood")
        );
    }

    public static org.bukkit.block.Block getHighestRocklikes(org.bukkit.block.Block block) {
        return getHighestRocklikes(block.getLocation());
    }

    public static org.bukkit.block.Block getHighestRocklikes(org.bukkit.Location loc) {
        org.bukkit.block.Block highestBlock = getHighestLocation(loc).getBlock();

        while (!isMaterialRocklikes(highestBlock.getType())) {
            highestBlock = highestBlock.getRelative(0, -1, 0);
        }

        return highestBlock;
    }

    public static boolean isMaterialRocklikes(org.bukkit.Material material) {
        String materialType = material.name().toLowerCase();
        return (
            materialType.contains("stone") ||
            materialType.contains("granite") ||
            materialType.contains("diorite") ||
            materialType.contains("andesite") ||
            materialType.contains("grass_block") ||
            materialType.contains("dirt") ||
            materialType.contains("rock") ||
            materialType.contains("sand") ||
            materialType.contains("gravel") ||
            materialType.contains("ore") ||
            materialType.contains("quartz") ||
            materialType.contains("magma") ||
            materialType.contains("obsidian")
        );
    }

    public static String getDirections(org.bukkit.Location from, org.bukkit.Location to) {
        if (!from.getWorld().equals(to.getWorld())) {
            return "Different World";
        }

        TyphonNavigationResult navigationResult = getNavigation(from, to);

        double destinationYaw = navigationResult.yawDegree;
        double directDistance = navigationResult.distance;

        String destinationString = Math.abs(Math.floor(destinationYaw))+" degrees "
                + ((Math.abs(destinationYaw) < 1) ? "Forward" : (destinationYaw < 0) ? "Left" : "Right");

        if (Double.isNaN(destinationYaw) || directDistance < 1) {
            return "Arrived!";
        }

        destinationString += " / "+String.format("%.2f", directDistance)+" blocks";

        return destinationString;
    }

    public static TyphonNavigationResult getNavigation(org.bukkit.Location from, org.bukkit.Location to) {
        if (from.getWorld().getUID() != to.getWorld().getUID()) {
            return null;
        }

        float userYawN = from.getYaw() - 180;

        double distanceN = from.getBlockZ() - to.getBlockZ();
        double distanceE = to.getBlockX() - from.getBlockX();
        double distanceDirect = Math.sqrt(Math.pow(distanceN, 2) + Math.pow(distanceE, 2));

        double theta;
        theta = Math.toDegrees(Math.acos(distanceN / distanceDirect));
        theta = (distanceE > 0) ? theta : -theta;

        double destinationYaw = theta - userYawN;
        destinationYaw = (Math.abs(destinationYaw) > 180) ? -(360 - destinationYaw) : destinationYaw;

        if (Double.isNaN(destinationYaw)) { destinationYaw = 0; }

        return new TyphonNavigationResult(destinationYaw, distanceDirect);
    }
}
