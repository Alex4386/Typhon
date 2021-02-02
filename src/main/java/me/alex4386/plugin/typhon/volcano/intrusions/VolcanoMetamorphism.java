package me.alex4386.plugin.typhon.volcano.intrusions;

import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import org.bukkit.Material;
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
        Material material = block.getType();
        int surfaceY = TyphonUtils.getHighestRocklikes(block.getLocation()).getY();

        boolean isExtrusive = block.getLocation().getBlockY() < (surfaceY - 5);

        if (block.getType().isBurnable()) {
            material = Material.LAVA;
            block.getWorld().createExplosion(block.getLocation(), 4f, false);

            block.setType(volcano.composition.getExtrusiveRockMaterial());
        } else {
            String blockTypeName = material.name().toLowerCase();

            double randomDouble = random.nextDouble();

            if (
                    (blockTypeName.contains("stone") && !blockTypeName.contains("sand") && !blockTypeName.contains("black"))
                    || blockTypeName.contains("ore")
                    || blockTypeName.contains("diorite")
                    || blockTypeName.contains("granite")
                    || blockTypeName.contains("andesite")
                    || blockTypeName.contains("dirt")
                    || blockTypeName.contains("podzol")
                    || blockTypeName.contains("grass")
            ) {
                material = isExtrusive ? this.volcano.composition.getExtrusiveRockMaterial() : this.volcano.composition.getIntrusiveRockMaterial();
            } else if (blockTypeName.contains("sand")) {
                material = isExtrusive ? this.volcano.composition.getExtrusiveRockMaterial() : (
                    randomDouble < 0.3 ? Material.QUARTZ_PILLAR : this.volcano.composition.getIntrusiveRockMaterial()
                );
            } else {
                return;
            }
        }

        block.setType(material);
        if (material == Material.LAVA) {
            this.volcano.bombLavaFlow.registerLavaCoolData(block);
        }
        return;
    }


    public void evaporateBlock(Block block) {
        Material material = block.getType();
        String blockTypeName = material.name().toLowerCase();

        if (
                (blockTypeName.contains("snow"))
        ) {
            block.setType(Material.AIR);
        } else if (material == Material.GRASS) {
            block.setType(Material.AIR);
        } else if (material == Material.GRASS_BLOCK) {
            block.setType(Material.DIRT);
        } else if (material.isBurnable()) {
            for (BlockFace face : BlockFace.values()) {
                Block relativeBlock = block.getRelative(face);
                if (relativeBlock.getType().isAir()) {
                    relativeBlock.setType(Material.FIRE);
                }
            }
        }
    }


}
