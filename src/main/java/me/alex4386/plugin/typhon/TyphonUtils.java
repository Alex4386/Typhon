package me.alex4386.plugin.typhon;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.util.Vector;
import org.json.simple.JSONObject;

public class TyphonUtils {
    public static boolean isMaterialPlant(Material material) {

        String materialType = TyphonUtils.toLowerCaseDumbEdition(material.name());
        return (materialType.contains("flower")
                || materialType.contains("sapling")
                || materialType.contains("_grass")
                || material == Material.SUGAR_CANE
                || material == Material.BAMBOO
                || material == Material.KELP
                || materialType.contains("_leaves")
                || materialType.contains("mangrove")
                || materialType.contains("azalea")
                || materialType.contains("_mushroom")
                || materialType.contains("_tulip")
                || material == Material.POPPY
                || material == Material.DANDELION
                || material == Material.LILAC
                || material == Material.ROSE_BUSH
                || material == Material.PEONY
                || material == Material.SUNFLOWER
                || material == Material.LILY_OF_THE_VALLEY
                || material == Material.WITHER_ROSE
                || material == Material.PINK_PETALS
                || material == Material.SPORE_BLOSSOM
                || materialType.contains("vine")
                || materialType.contains("pitcher_")
                || materialType.contains("_dripleaf")
                || materialType.contains("fern")
                || materialType.contains("_berries")
        );
    }

    public static boolean isBlockFlowable(Block block) {
        BlockFace[] flowableFaces = new BlockFace[] {
            BlockFace.DOWN,
            BlockFace.NORTH,
            BlockFace.SOUTH,
            BlockFace.EAST,
            BlockFace.WEST
        };

        for (BlockFace face : flowableFaces) {
            Block target = block.getRelative(face);
            Material targetMaterial = target.getType();

            // due to implementation, the lava can flow into water.
            if (targetMaterial.isAir() || targetMaterial == Material.WATER) {
                return true;
            }
        }

        return false;
    }

    public static List<Chunk> getChunksInRadius(Chunk src, double radius) {
        List<Chunk> chunks = new ArrayList<>();
        int chunkRadius = (int) Math.ceil(radius / 16);

        int srcX = src.getX();
        int srcZ = src.getZ();

        for (int x = -chunkRadius; x <= chunkRadius; x++) {
            for (int z = -chunkRadius; z <= chunkRadius; z++) {
                chunks.add(src.getWorld().getChunkAt(srcX + x, srcZ + z));
            }
        }

        return chunks;
    }

    public static Consumer<Block> getBlockFaceUpdater(Block fromBlock, Block block) {
        return getBlockFaceUpdater(block.getLocation().subtract(fromBlock.getLocation()).toVector());
    }

    public static Consumer<Block> getBlockFaceUpdater(Vector vector) {
        return block -> {
            BlockData blockData = block.getBlockData();
            BlockFace face = TyphonUtils.getAdequateBlockFace(vector);

            if (blockData instanceof Directional directional) {
                directional.setFacing(face);
                block.setBlockData(directional);
            }

            if (blockData instanceof Orientable orientable) {
                if (face == BlockFace.UP || face == BlockFace.DOWN) {
                    orientable.setAxis(Axis.Y);
                } else if (face == BlockFace.NORTH || face == BlockFace.SOUTH) {
                    orientable.setAxis(Axis.Z);
                } else {
                    orientable.setAxis(Axis.X);
                }

                block.setBlockData(orientable);
            }
        };
    }

    public static BlockFace getAdequateBlockFace(Block fromBlock, Block toBlock) {
        Vector diff = toBlock.getLocation().toVector().subtract(fromBlock.getLocation().toVector());
        return getAdequateBlockFace(diff);
    }

    public static BlockFace getAdequateBlockFace(Vector vector) {
        double x = vector.getX();
        double z = vector.getZ();

        BlockFace face = BlockFace.DOWN;
        if (x == 0 && z == 0) return face;

        if (Math.abs(x) > Math.abs(z)) {
            if (x > 0) {
                face = BlockFace.EAST;
            } else {
                face = BlockFace.WEST;
            }
        } else {
            if (z > 0) {
                face = BlockFace.SOUTH;
            } else {
                face = BlockFace.NORTH;
            }
        }

        return face;
    }

    private static Map<Block, TyphonCache<org.bukkit.block.Block>> highestRocklikesBlockCacheMap = new HashMap<>();

    public static int getMinimumY(org.bukkit.World world) {
        return world.getMinHeight();
    }

    public static int getMaximumY(org.bukkit.World world) {
        return world.getMaxHeight();
    }

    public static org.bukkit.Location getHighestLocation(org.bukkit.Location loc) {
        int y = loc.getWorld().getHighestBlockYAt(loc);
        return new org.bukkit.Location(loc.getWorld(), loc.getX(), y, loc.getZ());
    }

    public static org.bukkit.Location getHighestOceanFloor(org.bukkit.Location loc) {
        int y = loc.getWorld().getHighestBlockYAt(loc, org.bukkit.HeightMap.OCEAN_FLOOR);
        return new org.bukkit.Location(loc.getWorld(), loc.getX(), y, loc.getZ());
    }

    public static List<Block> getNearByBlocks(org.bukkit.block.Block baseBlock) {
        return getNearByBlocks(baseBlock, 1);
    }

    public static org.bukkit.Location getHighestBedrock(org.bukkit.Location loc) {
        int x = loc.getBlockX();
        int z = loc.getBlockZ();

        int MAX_BEDROCK_HEIGHT = 10;

        org.bukkit.block.Block block = loc.getWorld().getBlockAt(x, 1, z);

        for (int i = getMinimumY(loc.getWorld()); i <= MAX_BEDROCK_HEIGHT; i++) {
            if (block.getType() != org.bukkit.Material.BEDROCK) {
                return new org.bukkit.Location(loc.getWorld(), x, i - 1, z);
            } else {
                block = block.getRelative(org.bukkit.block.BlockFace.UP);
            }
        }

        // Um... This really shouldn't happen.
        return new org.bukkit.Location(loc.getWorld(), x, getMinimumY(loc.getWorld()), z);
    }

    public static List<org.bukkit.block.Block> getNearByBlocks(
            org.bukkit.block.Block baseBlock, int radius) {
        List<org.bukkit.block.Block> nearByBlocks = new ArrayList<>();

        for (int i = -radius; i <= radius; i++) {
            for (int j = -radius; j <= radius; j++) {
                for (int k = -radius; k <= radius; k++) {
                    if (i == 0 && j == 0 && k == 0) continue;
                    if (Math.pow(i, 2) + Math.pow(j, 2) + Math.pow(k, 2) > Math.pow(radius, 2))
                        continue;

                    nearByBlocks.add(baseBlock.getRelative(i, j, k));
                }
            }
        }

        return nearByBlocks;
    }

    public static int getLavaLevel(org.bukkit.block.Block block) {
        org.bukkit.block.data.Levelled levelled =
                (org.bukkit.block.data.Levelled) block.getBlockData();
        return levelled.getLevel();
    }

    public static void setLavaLevel(org.bukkit.block.Block block, int level) {
        org.bukkit.block.data.Levelled levelled =
                (org.bukkit.block.data.Levelled) block.getBlockData();
        levelled.setLevel(level);

        block.setBlockData(levelled);
    }

    public static double getTwoDimensionalDistance(
            org.bukkit.Location loc1, org.bukkit.Location loc2) {
        double x = loc1.getX() - loc2.getX();
        double z = loc1.getZ() - loc2.getZ();

        return Math.sqrt(Math.pow(x, 2) + Math.pow(z, 2));
    }

    public static double getTwoDimensionalDistance(
            org.bukkit.util.Vector vec1, org.bukkit.util.Vector vec2) {
        double x = vec1.getX() - vec2.getX();
        double z = vec1.getZ() - vec2.getZ();

        return Math.sqrt(Math.pow(x, 2) + Math.pow(z, 2));
    }

    public static String blockLocationTostring(org.bukkit.block.Block block) {
        if (block == null) return "NULL";
        return block.getX()
                + ","
                + block.getY()
                + ","
                + block.getZ()
                + " @ "
                + block.getWorld().getName();
    }

    public static String readFile(java.io.File file)
            throws java.io.FileNotFoundException, java.io.IOException {
        java.io.BufferedReader bufferedReader =
                new java.io.BufferedReader(new java.io.FileReader(file));

        String str = "";
        String tmp;
        while ((tmp = bufferedReader.readLine()) != null) {
            str += tmp + "\n";
        }

        return str;
    }

    public static org.json.simple.JSONObject parseJSON(java.io.File file)
            throws org.json.simple.parser.ParseException, java.io.IOException {
        return parseJSON(readFile(file));
    }

    public static org.json.simple.JSONObject parseJSON(String string)
            throws org.json.simple.parser.ParseException {
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

    public static org.bukkit.TreeType getAdequateTreeTypeForBiome(org.bukkit.block.Biome biome) {
        switch (biome) {
            case FOREST:
            case FLOWER_FOREST:
                return org.bukkit.TreeType.TREE;
            case BIRCH_FOREST:
            case STONY_PEAKS:
                return org.bukkit.TreeType.BIRCH;
            case DARK_FOREST:
                return org.bukkit.TreeType.DARK_OAK;
            case JUNGLE:
                return org.bukkit.TreeType.JUNGLE;
            case SWAMP:
                return org.bukkit.TreeType.SWAMP;
            case SAVANNA:
            case SAVANNA_PLATEAU:
                return org.bukkit.TreeType.ACACIA;
            case TAIGA:
                return org.bukkit.TreeType.REDWOOD;


        }

        return null;
    }

    public static void writeJSON(java.io.File file, org.json.simple.JSONObject jsonObject)
            throws java.io.IOException {
        writeString(file, jsonObject.toJSONString());
    }

    public static void writeString(java.io.File file, String string) throws java.io.IOException {
        if (!file.exists()) file.createNewFile();

        java.io.FileWriter writer = new java.io.FileWriter(file);
        writer.write(string);
        writer.close();
    }

    public static String toLowerCaseDumbEdition(String pString) {
        if (pString != null) {
            char[] retChar = pString.toCharArray();
            for (int idx = 0; idx < pString.length(); idx++) {
                char c = retChar[idx];
                if (c >= 'A' && c <= 'Z') {
                    retChar[idx] = (char) (c | 32);
                }
            }
            return new String(retChar);
        } else {
            return null;
        }
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

    public static org.bukkit.block.Block getRandomBlockInRange(
            org.bukkit.block.Block block, int minRange, int maxRange) {
        Random random = new Random();
        
        // mitigation
        int range = maxRange - minRange;

        // If this is happening, something have been gone wrong
        if (range < 0) {
            // TyphonUtils.stackTraceMe();
            range = 0;
        }

        int offsetRadius = (range > 0 ? random.nextInt(range) : 0) + minRange;
        double angle = random.nextDouble() * 2 * Math.PI;

        int offsetX = (int) (Math.sin(angle) * offsetRadius);
        int offsetZ = (int) (Math.cos(angle) * offsetRadius);

        return block.getRelative(offsetX, 0, offsetZ);
    }

    public static org.bukkit.block.Block getFairRandomBlockInRange(
        org.bukkit.block.Block block, int minRange, int maxRange) {
        Random random = new Random();
        
        // mitigation
        int range = maxRange - minRange;

        // If this is happening, something have been gone wrong
        if (range < 0) {
            // TyphonUtils.stackTraceMe();
            range = 0;
        }

        double offset = range * (1 - Math.pow(Math.random(), 2.0)) + minRange;
        double angle = random.nextDouble() * 2 * Math.PI;

        int offsetX = (int) (Math.sin(angle) * offset);
        int offsetZ = (int) (Math.cos(angle) * offset);

        return block.getRelative(offsetX, 0, offsetZ);
    }


    public static org.bukkit.block.Block getRandomBlockInRange(
            org.bukkit.block.Block block, int range) {
        Random random = new Random();

        int offsetRadius = random.nextInt(range);
        double angle = random.nextDouble() * 2 * Math.PI;

        int offsetX = (int) (Math.sin(angle) * offsetRadius);
        int offsetZ = (int) (Math.cos(angle) * offsetRadius);

        return block.getRelative(offsetX, 0, offsetZ);
    }

    public static void stackTraceMe() {
        try {
            throw new Exception("manually triggered stacktrace");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static long getBlockUpdatesPerSecond() {
        World world = Bukkit.getWorlds().get(0);
        Location spawnLoc = world.getSpawnLocation();
        Location baseLocation = new Location(world, spawnLoc.getX(), world.getMinHeight(), spawnLoc.getZ());

        Block baseBlock = baseLocation.getBlock();
        Material baseBlockMaterial = baseBlock.getType();

        long blockUpdateStartTime = System.nanoTime();
        baseLocation.getBlock().setType(Material.LAVA);

        long blockUpdateEndTime = System.nanoTime();
        long elapsedNanoSecondPerBlockUpdate = blockUpdateEndTime - blockUpdateStartTime;

        baseBlock.setType(baseBlockMaterial);

        long blockUpdatesPerMilliSecond = 1000000 / elapsedNanoSecondPerBlockUpdate;
        long blockUpdatesPerSecond = blockUpdatesPerMilliSecond * 1000;

        TyphonPlugin.logger.debug(
                VolcanoLogClass.CONSTRUCTION,
                "block update took:" + elapsedNanoSecondPerBlockUpdate + "ns.");
        TyphonPlugin.logger.debug(
                VolcanoLogClass.CONSTRUCTION, blockUpdatesPerSecond + " block updates per second");

        return blockUpdatesPerSecond;
    }

    public static boolean isMaterialTree(org.bukkit.Material material) {
        if (material == Material.BEEHIVE) return true;
        String materialType = TyphonUtils.toLowerCaseDumbEdition(material.name());
        
        return (materialType.contains("leaves")
                || materialType.contains("log")
                || materialType.contains("plank")
                || materialType.contains("wood")
                || materialType.contains("sapling"))
                || materialType.contains("vine") || isMaterialNameContainsTreeName(materialType);
    }

    public static boolean isMaterialTreeLeaves(org.bukkit.Material material) {
        String materialType = TyphonUtils.toLowerCaseDumbEdition(material.name());
        return (materialType.contains("leaves") || materialType.contains("sapling"));
    }

    private static boolean isMaterialNameContainsTreeName(String materialType) {
        return (materialType.contains("oak")
                || materialType.contains("spruce")
                || materialType.contains("birch")
                || materialType.contains("jungle")
                || materialType.contains("acacia")
                || materialType.contains("dark_oak")
                || materialType.contains("crimson")
                || materialType.contains("warped")
                || materialType.contains("mangrove")
                || materialType.contains("cherry")
                || materialType.contains("bamboo"));
    }

    public static org.bukkit.block.Block getHighestRocklikes(org.bukkit.block.Block block) {
        return getHighestRocklikes(block, true);
    }

    public static org.bukkit.block.Block getHighestRocklikes(org.bukkit.block.Block block, boolean useCache) {
        return getHighestRocklikes(block.getLocation(), useCache);
    }

    public static org.bukkit.block.Block getHighestRocklikes(org.bukkit.Location location) {
        return getHighestRocklikes(location, true);
    }

    public static org.bukkit.block.Block getHighestRocklikes(org.bukkit.Location loc, boolean useCache) {
        org.bukkit.block.Block block = loc.getBlock();

        if (useCache) {
            TyphonCache<org.bukkit.block.Block> t = highestRocklikesBlockCacheMap.get(block);
            if (t != null) {
                if (!t.isExpired()) return t.getTarget();
            }
        }

        org.bukkit.block.Block highestBlock = getHighestLocation(loc).getBlock();

        while (!isMaterialRocklikes(highestBlock.getType())) {
            highestBlock = highestBlock.getRelative(0, -1, 0);

            if (highestBlock.getY() <= highestBlock.getWorld().getMinHeight()) {
                break;
            }
        }

        highestRocklikesBlockCacheMap.put(block, new TyphonCache<>(highestBlock));
        return highestBlock;
    }

    public static boolean isMaterialRocklikes(org.bukkit.Material material) {
        String materialType = TyphonUtils.toLowerCaseDumbEdition(material.name());
        return (materialType.contains("stone")
                || materialType.contains("deepslate")
                || materialType.contains("netherrack")
                || materialType.contains("granite")
                || materialType.contains("diorite")
                || materialType.contains("andesite")
                || materialType.contains("grass_block")
                || materialType.contains("dirt")
                || materialType.contains("rock")
                || materialType.contains("sand")
                || materialType.contains("gravel")
                || materialType.contains("ore")
                || materialType.contains("iron")
                || materialType.contains("gold")
                || materialType.contains("copper")
                || materialType.contains("quartz")
                || materialType.contains("magma")
                || materialType.contains("obsidian")
                || materialType.contains("basalt")
                || materialType.contains("debris")
                || materialType.contains("amethyst")
                || materialType.contains("clay")
                || materialType.contains("terracotta")
                || materialType.contains("tuff")
            );
    }
    public static boolean containsWater(Block block) {
        return containsLiquidWater(block) || containsIce(block) || containsSnow(block);
    }

    public static boolean containsIce(Block block) {
        switch (block.getType()) {
            case ICE:
            case FROSTED_ICE:
            case PACKED_ICE:
            case BLUE_ICE:
                return true;
            default:
                return false;
        }
    }

    public static boolean containsSnow(Block block) {
        switch (block.getType()) {
            case SNOW:
            case SNOWBALL:
            case SNOW_BLOCK:
            case POWDER_SNOW:
            case POWDER_SNOW_BUCKET:
            case POWDER_SNOW_CAULDRON:
                return true;

            default:
                return false;
        }
    }

    public static void removeSeaGrass(Block baseBlock) {
        boolean isSeaGrass = false;
        switch (baseBlock.getType()) {
            case KELP:
            case KELP_PLANT:
            case SEAGRASS:
            case TALL_SEAGRASS:
                isSeaGrass = true;
            default:
        }

        if (isSeaGrass) {
            Block upperBlock = baseBlock.getRelative(BlockFace.UP);
            removeSeaGrass(upperBlock);
            baseBlock.setType(Material.WATER);
        }
    }

    public static boolean containsLiquidWater(Block block) {
        switch (block.getType()) {
            case WATER:
            case WATER_CAULDRON:
            case WATER_BUCKET:
            case KELP:
            case KELP_PLANT:
            case SEAGRASS:
            case TALL_SEAGRASS:
            case BUBBLE_COLUMN:
                return true;

            default:
        }

        BlockData data = block.getBlockData();
        if (data instanceof Waterlogged) {
            Waterlogged wlData = (Waterlogged) data;
            if (wlData.isWaterlogged()) return true;
        }

        return false;
    }

    public static void spawnParticleWithVelocity(
            Particle particle,
            Location loc,
            double radius,
            int count,
            double offsetX,
            double offsetY,
            double offsetZ) {
        org.bukkit.World world = loc.getWorld();
        double baseX = loc.getX();
        double baseY = loc.getY();
        double baseZ = loc.getZ();

        if (radius == 0) {
            Location tmpLoc = loc;
            Vector vector = new Vector(offsetX, offsetY, offsetZ);
            Vector normalized = vector.normalize().multiply(-0.25);

            for (int i = 0; i < count; i++) {
                world.spawnParticle(particle, tmpLoc, 0, offsetX, offsetY, offsetZ);
                tmpLoc.add(normalized.getX(), normalized.getY(), normalized.getZ());
            }
            return;
        }

        double radiusSquared = Math.pow(radius, 2);

        // imagine a sphere, that has volume of count.
        // get the radius.
        // then you'll have a sphere that has amount of count's
        // particle.
        // now you shrink it accordingly to given radius, you'll get a offset.
        double referenceSphereVolume = count;
        double referenceSphereRadiusCubed = 3 / (4 * Math.PI) * referenceSphereVolume;

        double referenceSphereRadius = Math.cbrt(referenceSphereRadiusCubed);
        double offset = radius / referenceSphereRadius;

        int axisRepeat = (int) (2 * referenceSphereRadius);

        for (int xIdx = 0; xIdx < axisRepeat; xIdx++) {
            for (int yIdx = 0; yIdx < axisRepeat; yIdx++) {
                for (int zIdx = 0; zIdx < axisRepeat; zIdx++) {
                    double offsetXLoc = -radius + (xIdx * offset);
                    double offsetYLoc = -radius + (yIdx * offset);
                    double offsetZLoc = -radius + (zIdx * offset);

                    double x = baseX + offsetXLoc;
                    double y = baseY + offsetYLoc;
                    double z = baseZ + offsetZLoc;

                    if (Math.pow(offsetXLoc, 2) + Math.pow(offsetYLoc, 2) + Math.pow(offsetZLoc, 2)
                            < radiusSquared) {
                        org.bukkit.Location tmpLoc = new org.bukkit.Location(world, x, y, z);

                        world.spawnParticle(particle, tmpLoc, 0, offsetX, offsetY, offsetZ);
                    }
                }
            }
        }
    }

    public static void createRisingSteam(Location location, int radius, int count) {
        createRisingSteam(location, radius, count, false);
    }

    public static void createRisingSteam(Location location, int radius, int count, boolean mute) {
        Particle type = Particle.CLOUD;
        
        if (location.getBlock().getType() == Material.WATER || location.getBlock().getRelative(BlockFace.UP).getType() == Material.WATER) {
            type = Particle.BUBBLE_COLUMN_UP;
            Location waterLevel = location.getWorld().getHighestBlockAt(location).getLocation().add(0,1,0);
            
            if (waterLevel.getY() - location.getY() < 10) {
                createRisingSteam(waterLevel, radius, count, mute);
            }
        }

        Location cloudSpawnTarget = TyphonUtils.getHighestRocklikes(location).getLocation().add(0,0.5,0);

        TyphonUtils.spawnParticleWithVelocity(
                type,
                cloudSpawnTarget,
                0,
                10,
                0,
                0.4,
                0);

        if (!mute) {
            location.getWorld().playSound(location, Sound.BLOCK_LAVA_AMBIENT, .15f * count, 0);
            location.getWorld().playSound(location, Sound.ENTITY_WIND_CHARGE_WIND_BURST, .2f * count, 0);
        }
    }

    public static int getVEIScale(long data) {
        if (data < Math.pow(10, 4)) {
            return 0;
        } else if (data <= Math.pow(10, 6)) {
            return 1;
        } else if (data <= Math.pow(10, 7)) {
            return 2;
        } else if (data <= Math.pow(10, 8)) {
            // 0.1 km^3
            return 3;
        } else if (data <= Math.pow(10, 9)) {
            return 4;
        } else if (data <= Math.pow(10, 10)) {
            return 5;
        } else if (data <= Math.pow(10, 11)) {
            return 6;
        } else if (data <= Math.pow(10, 12)) {
            return 7;
        } else {
            return 8;
        }
    }

    public static Vector calculateVelocity(Vector from, Vector to, int heightGain) {
        // Gravity of a potion
        double gravity = 0.115;

        // Block locations
        int endGain = to.getBlockY() - from.getBlockY();
        double horizDist = getTwoDimensionalDistance(from, to);

        // Height gain
        int gain = heightGain;

        double maxGain = gain > (endGain + gain) ? gain : (endGain + gain);
        if (maxGain < 0) {
            maxGain = 0;
        }

        // Solve quadratic equation for velocity
        double a = -horizDist * horizDist / (4 * maxGain);
        double b = horizDist;
        double c = -endGain;

        double slope = -b / (2 * a) - Math.sqrt(b * b - 4 * a * c) / (2 * a);

        // Vertical velocity
        double vy = Math.sqrt(maxGain * gravity);

        // Horizontal velocity
        double vh = vy / slope;

        // Calculate horizontal direction
        int dx = to.getBlockX() - from.getBlockX();
        int dz = to.getBlockZ() - from.getBlockZ();
        double mag = Math.sqrt(dx * dx + dz * dz);

        double dirx = dx / mag;
        double dirz = dz / mag;

        if (mag == 0) {
            dirx = 0;
            dirz = 0;
        }

        // Horizontal velocity components
        double vx = vh * dirx;
        double vz = vh * dirz;

        return new Vector(vx, vy, vz);
    }

    public static boolean isNumber(String string) {
        try {
            Double.parseDouble(string);
        } catch (NumberFormatException e) {
            return false;
        }

        return true;
    }

    public static List<File> getAllChunkFiles(World world) {
        File regionDirectory = new File(world.getWorldFolder(), "region");
        File[] regions = regionDirectory.listFiles();
        List<File> regionFiles = Arrays.stream(regions)
                .filter(file ->
                        !file.isDirectory() && file.getName().endsWith(".mca")
                )
                .collect(Collectors.toList());

        return regionFiles;
    }

    public static long getChunkCount(World world) {
        List<File> regionFiles = getAllChunkFiles(world);
        return regionFiles.size();
    }

    public static long getWorldArea(World world) {
        return getChunkCount(world) * 16 * 16;
    }
}
