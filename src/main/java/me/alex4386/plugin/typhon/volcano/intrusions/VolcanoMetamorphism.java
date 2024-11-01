package me.alex4386.plugin.typhon.volcano.intrusions;

import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.util.Random;

public class VolcanoMetamorphism {
    Volcano volcano;
    Random random = new Random();

    public VolcanoMetamorphism(Volcano volcano) {
        this.volcano = volcano;
    }

    public void metamorphoseBlock(Block block) {
        VolcanoVent vent = volcano.manager.getNearestVent(block);
        if (vent == null) return;

        this.metamorphoseBlock(vent, block);
    }

    public void metamorphoseBlock(VolcanoVent vent, Block block) {
        this.metamorphoseBlock(vent, block, false);
    }

    public void metamorphoseBlock(VolcanoVent vent, Block block, boolean isBomb) {
        Material material = block.getType();
        if (material.isAir()) return;

        String blockTypeName = TyphonUtils.toLowerCaseDumbEdition(material.name());

        if (blockTypeName.contains("log") || blockTypeName.contains("leaves")) {
            removeTree(block);    
        } else if (block.getType().isBurnable()) {
            block.setType(Material.AIR);
        } else {
            double silicateLevel = vent.lavaFlow.settings.silicateLevel;

            boolean typeOfDirt = (blockTypeName.contains("dirt")
                || blockTypeName.contains("podzol")
                || blockTypeName.contains("grass_block"))
                || blockTypeName.contains("farmland");

            if (block.isLiquid()) {
                return;
            }
            
            if (typeOfDirt) {
                material = Material.COARSE_DIRT;
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

        block.setType(material);
        return;
    }

    public void evaporateWater(Block block) {
        int radius = 1;

        if (block.getType() == Material.WATER) {
            if (block.getY() < block.getWorld().getSeaLevel() - 1) {
                block.setType(Material.AIR);
                return;
            }

            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        if (x == 0 && y == 0 && z == 0) continue;
                        Block nearby = block.getRelative(x, y, z);
                        if (nearby.getType() == Material.WATER) nearby.setType(Material.AIR);
                    }
                }
            }
        }
    }

    public void removeTree(Block baseBlock) {
        removeTree(baseBlock, 25);
    }

    public void removeTree(Block baseBlock, int maxRecursion) {
        if (maxRecursion < 0 || !TyphonUtils.isMaterialTree(baseBlock.getType())) return;

        BlockFace[] facesToSearch = {BlockFace.UP, BlockFace.DOWN, BlockFace.WEST, BlockFace.DOWN, BlockFace.EAST, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.DOWN};

        for (BlockFace face : facesToSearch) {
            Block block = baseBlock.getRelative(face);
            if (TyphonUtils.isMaterialTree(block.getType())) {
                removeTree(block, maxRecursion - 1);
            }
        }

        // improvise cherry log detection algorithm
        if (baseBlock.getType() == Material.CHERRY_LOG) {
            // extra search
            Block upBlock = baseBlock.getRelative(BlockFace.UP);
            for (BlockFace face : facesToSearch) {
                Block block = upBlock.getRelative(face);
                if (TyphonUtils.isMaterialTree(block.getType())) {
                    removeTree(block, maxRecursion - 1);
                }
            }
        }

        String name = TyphonUtils.toLowerCaseDumbEdition(baseBlock.getType().name());

        if (name.contains("log")) {
            baseBlock.setType(Material.COAL_BLOCK);
        } else {
            baseBlock.setType(Material.AIR);
        }
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

        if (material.isBurnable()) {
            for (BlockFace face : BlockFace.values()) {
                Block relativeBlock = block.getRelative(face);
                if (relativeBlock.getType().isAir()) {
                    relativeBlock.setType(Material.FIRE);
                }
            }
        }

        if (material == Material.WATER) {
            evaporateWater(block);
        } else if ((blockTypeName.contains("snow"))) {
            block.setType(Material.AIR);
        } else if (blockTypeName.contains("coral")) {
            block.setType(Material.SAND);
        } else if (material == Material.SHORT_GRASS || material == Material.PINK_PETALS) {
            block.setType(Material.AIR);
        } else if (material == Material.GRASS_BLOCK || material == Material.ROOTED_DIRT || material == Material.MUDDY_MANGROVE_ROOTS) {
            block.setType(Material.DIRT);
        } else if (material == Material.TALL_GRASS || material == Material.LARGE_FERN || material == Material.BAMBOO) {
            // check under block is not the same type
            Block underBlock = block.getRelative(BlockFace.DOWN);
            if (underBlock.getType() == material) {
                block.setType(Material.AIR);
            } else {
                block.setType(Material.DEAD_BUSH);
            }
        } else if (blockTypeName.contains("sapling") || material == Material.MANGROVE_PROPAGULE) {
            block.setType(Material.DEAD_BUSH);
        } else if (isFlower(material)) {
            block.setType(Material.DEAD_BUSH);
        } else if (isPlantlike(material)) {
            block.setType(Material.AIR);
        } else if (isPlaceableAnimalEgg(material)) {
            block.setType(Material.AIR);
        } else if (material == Material.SEAGRASS || material == Material.TALL_SEAGRASS) {
            block.setType(Material.AIR);
        } else if (material == Material.WATER_CAULDRON) {
            block.setType(Material.CAULDRON);
        } else if (material == Material.MOSS_BLOCK) {
            block.setType(Material.DIRT);
        } else if (blockTypeName.contains("infested")) {
            block.setType(VolcanoComposition.getExtrusiveRock(silicateLevel));
        } else if (material == Material.SEA_PICKLE) {
            block.setType(Material.AIR);
        }
    }
}
