package me.alex4386.plugin.typhon.volcano.intrusions;

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

        if (block.getType().isBurnable()) {
            block.setType(Material.AIR);
        } else {
            String blockTypeName = material.name().toLowerCase();
            double silicateLevel = vent.lavaFlow.settings.silicateLevel;

            boolean typeOfDirt = (blockTypeName.contains("dirt")
                || blockTypeName.contains("podzol")
                || blockTypeName.contains("grass_block"));

            boolean isPassable = block.isPassable();

            if (block.isLiquid()) {
                return;
            }
            
            if (isPassable) {
                material = VolcanoComposition.getExtrusiveRock(silicateLevel);
            } else if (typeOfDirt) {
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

    public void evaporateBlock(Block block) {
        Material material = block.getType();
        String blockTypeName = material.name().toLowerCase();

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
        } else if (material == Material.GRASS) {
            block.setType(Material.AIR);
        } else if (material == Material.GRASS_BLOCK) {
            block.setType(Material.DIRT);
        } else if (material == Material.TALL_GRASS || material == Material.GRASS) {
            block.setType(Material.DEAD_BUSH);
        } else if (material == Material.SEAGRASS || material == Material.WATER) {
            block.setType(Material.AIR);
        } else if (material == Material.WATER_CAULDRON) {
            block.setType(Material.CAULDRON);
        } else if (material == Material.MOSS_BLOCK) {
            block.setType(Material.DIRT);
        } else if (material.name().toLowerCase().contains("infested")) {
            block.setType(VolcanoComposition.getExtrusiveRock(silicateLevel));
        }
    }
}
