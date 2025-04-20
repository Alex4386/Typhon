package me.alex4386.plugin.typhon.volcano.intrusions;

import me.alex4386.plugin.typhon.TyphonBlocks;
import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Orientable;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

public class VolcanoMetamorphism {
    Volcano volcano;
    Random random = new Random();

    public VolcanoMetamorphism(Volcano volcano) {
        this.volcano = volcano;
    }

    public void metamorphoseBlock(Block block, boolean isLavaContact) {
        VolcanoVent vent = volcano.manager.getNearestVent(block);
        if (vent == null) return;

        this.metamorphoseBlock(vent, block, isLavaContact);
    }

    public static boolean isDirt(Material material) {
        String blockTypeName = TyphonUtils.toLowerCaseDumbEdition(material.name());

        boolean typeOfDirt = (blockTypeName.contains("dirt")
                || blockTypeName.contains("podzol")
                || blockTypeName.contains("grass_block"))
                || blockTypeName.contains("farmland");

        return typeOfDirt;
    }

    public static boolean isNaturalSoil(Material material) {
        return VolcanoMetamorphism.isDirt(material);
    }

    public void metamorphoseBlock(VolcanoVent vent, Block block, boolean isLavaContact) {
        Material material = block.getType();
        if (material.isAir()) return;

        String blockTypeName = TyphonUtils.toLowerCaseDumbEdition(material.name());

        if (blockTypeName.contains("log") || blockTypeName.contains("leaves")) {
            if (!isLavaContact) {
                this.killTree(block);
            } else {
                this.removeTree(block);
            }
        } else if (block.getType().isBurnable()) {
            this.setBlock(block, Material.AIR);
        } else {

            boolean typeOfDirt = VolcanoMetamorphism.isDirt(material);

            if (block.isLiquid()) {
                // if it is lava, cool it down if it is not registered
                return;
            }
            
            if (typeOfDirt) {
                if (block.getType() == Material.COARSE_DIRT) {
                    material = Material.STONE;
                } else {
                    material = Material.COARSE_DIRT;
                }
            } else if (blockTypeName.contains("cobblestone") || blockTypeName.contains("gravel") || blockTypeName.contains("infested")) {
                if (blockTypeName.contains("infested")) {
                    block.getWorld().playSound(block.getLocation(), Sound.ENTITY_SILVERFISH_DEATH, 1f, 0f);
                }

                material = Material.STONE;
            } else if (material == Material.SAND) {
                material = Material.SANDSTONE;
            } else if (material == Material.RED_SAND) {
                material = Material.RED_SANDSTONE;
            } else if (material == Material.CLAY) {
                material = Material.TERRACOTTA;
            } else {
                return;
            }
        }

        if (material == Material.STONE) {
            material = VolcanoComposition.getExtrusiveRock(vent.lavaFlow.settings.silicateLevel);
        }

        vent.lavaFlow.queueBlockUpdate(block, material);
        return;
    }

    public void setBlock(Block block, Material material) {
        this.setBlock(block, material, null);
    }

    public void setBlock(Block block, Material material, Consumer<Block> e) {
        if (this.volcano != null && this.volcano.mainVent != null && this.volcano.mainVent.lavaFlow != null && !TyphonPlugin.isShuttingdown) {
            this.volcano.mainVent.lavaFlow.queueBlockUpdate(block, material, e);
        } else {
            TyphonBlocks.setBlockType(block, material);
            e.accept(block);
        }
    }

    public void evaporateWater(Block block) {
        int radius = 1;

        if (block.getType() == Material.WATER) {
            if (block.getY() < block.getWorld().getSeaLevel() - 1) {
                this.setBlock(block, Material.AIR);
                return;
            }

            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (x == 0 && y == 0 && z == 0) continue;
                        Block nearby = block.getRelative(x, y, z);
                        if (nearby.getType() == Material.WATER) this.setBlock(nearby, Material.AIR);
                    }
                }
            }
        }
    }

    private int getRecursionAmount(Block baseBlock) {
        String name = TyphonUtils.toLowerCaseDumbEdition(baseBlock.getType().name());
        if (name.contains("cherry")) {
            return 45;
        } else {
            return 30;
        }
    }

    public void killTree(Block baseBlock) {
        removeTree(baseBlock, this.getRecursionAmount(baseBlock), true);
    }

    public void removeTree(Block baseBlock) {
        removeTree(baseBlock, this.getRecursionAmount(baseBlock), false);
    }

    public void removeTree(Block baseBlock, int maxRecursion, boolean leavesOnly) {
        HashSet<Block> visitedBlocks = new HashSet<>();
        HashSet<Block> treeBlocks = new HashSet<>();
        HashSet<Block> logBlocks = new HashSet<>();
        removeTree(baseBlock, maxRecursion, leavesOnly, visitedBlocks, treeBlocks, logBlocks);

        double percentage = leavesOnly ? 0.5 : 1;
        if (Math.random() < percentage) {
            timberTree(logBlocks);
        }
    }

    public void timberTree(Set<Block> logBlocks) {
        if (logBlocks.isEmpty()) return;

        Block rootBlock = logBlocks.iterator().next();
        for (Block block : logBlocks) {
            if (block.getY() < rootBlock.getY()) {
                rootBlock = block;
            }
        }

        VolcanoVent vent = volcano.manager.getNearestVent(rootBlock);

        Block ventBlock = vent.location.getBlock();
        Location ventLocation = ventBlock.getRelative(0, -ventBlock.getY(), 0).getLocation();
        Location rootBlockLoc = rootBlock.getRelative(0, -rootBlock.getY(), 0).getLocation();

        Vector newY = rootBlockLoc.subtract(ventLocation).toVector().setY(0).normalize();

        // For each block in the set, calculate its new location in the transposed coordinate system
        for (Block block : logBlocks) {
            if (block.isEmpty()) continue;

            // Get the relative position of the block to the rootBlock
            Vector relativePosition = block.getLocation().toVector().subtract(rootBlock.getLocation().toVector());
            Vector rotatedVector = VolcanoMath.rotateVectorToYAxis(newY, relativePosition);

            // Create the new location based on the transformed coordinates
            Location newLocation = rootBlock.getLocation().clone().add(rotatedVector.getX(), rotatedVector.getY(), rotatedVector.getZ());

            Block targetBlock = TyphonUtils.getHighestRocklikes(newLocation.getBlock());
            targetBlock = targetBlock.getRelative(BlockFace.UP);

            // rotate the blockface to the new direction
            BlockFace newFace = TyphonUtils.getAdequateBlockFace(newY);
            Consumer<Block> callback = TyphonUtils.getBlockFaceUpdater(newFace);

            this.setBlock(targetBlock, block.getType(), callback);
            this.setBlock(block, Material.AIR);
        }
    }

    private void removeTree(Block baseBlock, int maxRecursion, boolean leavesOnly, Set<Block> visitedBlocks, Set<Block> treeBlocks, Set<Block> logBlocks) {
        if (visitedBlocks.contains(baseBlock)) return;
        visitedBlocks.add(baseBlock);

        if (maxRecursion < 0 || !TyphonUtils.isMaterialTree(baseBlock.getType())) return;
        treeBlocks.add(baseBlock);

        BlockFace[] facesToSearch = {BlockFace.UP, BlockFace.DOWN, BlockFace.WEST, BlockFace.DOWN, BlockFace.EAST, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.DOWN};

        for (BlockFace face : facesToSearch) {
            Block block = baseBlock.getRelative(face);
            if (TyphonUtils.isMaterialTree(block.getType())) {
                removeTree(block, maxRecursion - 1, leavesOnly, visitedBlocks, treeBlocks, logBlocks);
            }
        }

        // improvise cherry log detection algorithm
        if (baseBlock.getType() == Material.CHERRY_LOG || baseBlock.getType() == Material.ACACIA_LOG) {
            // extra search
            Block upBlock = baseBlock.getRelative(BlockFace.UP);
            for (BlockFace face : facesToSearch) {
                Block block = upBlock.getRelative(face);
                if (TyphonUtils.isMaterialTree(block.getType())) {
                    removeTree(block, maxRecursion - 1, leavesOnly, visitedBlocks, treeBlocks, logBlocks);
                }
            }
        }

        String name = TyphonUtils.toLowerCaseDumbEdition(baseBlock.getType().name());

        if (name.contains("log")) {
            if (!leavesOnly) {
                // if it is a log, turn it into a dead log, and timber it on the ground
                // set facing direction to facing outward of the vent

                Location loc = baseBlock.getLocation();
                this.setBlock(baseBlock, Material.COAL_BLOCK);
            }

            logBlocks.add(baseBlock);
        } else {
            this.setBlock(baseBlock, Material.AIR);
        }

        treeBlocks.add(baseBlock);
    }

    public void removePlant(Block baseBlock) {
        removePlant(baseBlock, 25);
    }

    public void removePlant(Block baseBlock, int maxRecursion) {
        removePlant(baseBlock, maxRecursion, new HashSet<>());
    }

    private void removePlant(Block baseBlock, int maxRecursion, Set<Block> visitedBlocks) {
        if (maxRecursion < 0 || !TyphonUtils.isMaterialPlant(baseBlock.getType())) return;
        if (visitedBlocks.contains(baseBlock)) return;

        visitedBlocks.add(baseBlock);
        BlockFace[] facesToSearch = {BlockFace.UP};

        for (BlockFace face : facesToSearch) {
            Block block = baseBlock.getRelative(face);
            if (TyphonUtils.isMaterialPlant(block.getType())) {
                removePlant(block, maxRecursion - 1, visitedBlocks);
            }
        }

        this.setBlock(baseBlock, Material.AIR);
    }

    public boolean isFlower(Material material) {
        return material == Material.DANDELION ||
                material == Material.POPPY ||
                material == Material.BLUE_ORCHID ||
                material == Material.ALLIUM ||
                material == Material.AZURE_BLUET ||
                material == Material.RED_TULIP ||
                material == Material.ORANGE_TULIP ||
                material == Material.WHITE_TULIP ||
                material == Material.PINK_TULIP ||
                material == Material.OXEYE_DAISY ||
                material == Material.CORNFLOWER ||
                material == Material.LILY_OF_THE_VALLEY ||
                material == Material.TORCHFLOWER ||
                material == Material.WITHER_ROSE ||
                material == Material.SUNFLOWER ||
                material == Material.LILAC ||
                material == Material.ROSE_BUSH ||
                material == Material.PEONY ||
                material == Material.PITCHER_PLANT ||
                material == Material.PITCHER_POD;
    }

    public boolean isPlantlike(Material material) {
        return isFlower(material) ||
                material == Material.SMALL_DRIPLEAF ||
                material == Material.BIG_DRIPLEAF ||
                material == Material.BIG_DRIPLEAF_STEM ||
                material == Material.GLOW_LICHEN ||
                material == Material.HANGING_ROOTS ||
                material == Material.ROOTED_DIRT ||
                material == Material.MANGROVE_ROOTS ||
                material == Material.MUDDY_MANGROVE_ROOTS ||
                material == Material.SPORE_BLOSSOM;
    }

    public boolean isPlaceableAnimalEgg(Material material) {
        return material == Material.DRAGON_EGG ||
                material == Material.TURTLE_EGG ||
                material == Material.SNIFFER_EGG ||
                material == Material.FROGSPAWN;
    }

    public void evaporateBlock(Block block) {
        Material material = block.getType();
        String blockTypeName = TyphonUtils.toLowerCaseDumbEdition(material.name());

        VolcanoVent vent = volcano.manager.getNearestVent(block);
        double silicateLevel = vent == null ? 0.45 : vent.lavaFlow.settings.silicateLevel;

        Material target = null;

        if (material.isBurnable()) {
            for (BlockFace face : BlockFace.values()) {
                Block relativeBlock = block.getRelative(face);
                if (relativeBlock.getType().isAir()) {
                    target = Material.FIRE;
                }
            }
        }

        if (material == Material.WATER) {
            evaporateWater(block);
        } else if ((blockTypeName.contains("snow"))) {
            target = Material.AIR;
        } else if (blockTypeName.contains("coral")) {
            target = Material.SAND;
        } else if (material == Material.SHORT_GRASS) {
            target = Material.SHORT_DRY_GRASS;
        } else if (material == Material.TALL_GRASS) {
            target = Material.TALL_DRY_GRASS;
        } else if (material == Material.SHORT_DRY_GRASS || material == Material.TALL_DRY_GRASS) {
            if (Math.random() < 0.2) {
                target = Material.AIR;
            }
        } else if (material == Material.PINK_PETALS || material == Material.BUSH || blockTypeName.endsWith("_bush") || material == Material.LEAF_LITTER) {
            target = Material.AIR;
        } else if (material == Material.GRASS_BLOCK || material == Material.ROOTED_DIRT || material == Material.MUDDY_MANGROVE_ROOTS) {
            target = Material.DIRT;
        } else if (material == Material.CACTUS_FLOWER) {
            target = Material.AIR;
            Block underBlock = block.getRelative(BlockFace.DOWN);
            if (underBlock.getType() == Material.CACTUS) {
                evaporateBlock(underBlock);
            }
        } else if (material == Material.CACTUS) {
            target = Material.AIR;
            Block underBlock = block.getRelative(BlockFace.DOWN);
            if (underBlock.getType() == Material.CACTUS) {
                evaporateBlock(underBlock);
            }
        } else if (material == Material.LARGE_FERN || material == Material.BAMBOO) {
            // check under block is not the same type
            Block underBlock = block.getRelative(BlockFace.DOWN);
            if (underBlock.getType() == material) {
                target = Material.AIR;
            } else {
                target = Material.DEAD_BUSH;
            }
        } else if (blockTypeName.contains("sapling") || material == Material.MANGROVE_PROPAGULE) {
            target = Material.DEAD_BUSH;
        } else if (isFlower(material)) {
            target = Material.DEAD_BUSH;
        } else if (isPlantlike(material)) {
            target = Material.AIR;
        } else if (isPlaceableAnimalEgg(material)) {
            target = Material.AIR;
        } else if (material == Material.SEAGRASS || material == Material.TALL_SEAGRASS) {
            target = Material.AIR;
        } else if (material == Material.WATER_CAULDRON) {
            target = Material.CAULDRON;
        } else if (material == Material.MOSS_BLOCK) {
            target = Material.DIRT;
        } else if (blockTypeName.contains("infested")) {
            target = VolcanoComposition.getExtrusiveRock(silicateLevel);
        } else if (material == Material.SEA_PICKLE) {
            target = Material.AIR;
        }

        if (target != null) {
            this.setBlock(block, target);
        }
    }
}
