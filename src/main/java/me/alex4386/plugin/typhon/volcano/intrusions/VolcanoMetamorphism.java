package me.alex4386.plugin.typhon.volcano.intrusions;

import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.VolcanoComposition;
import me.alex4386.plugin.typhon.volcano.bomb.VolcanoBombListener;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerBucketEmptyEvent;

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
            block.setType(Material.AIR);
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
                material = isExtrusive ? VolcanoComposition.getExtrusiveRock(volcano.silicateLevel) : VolcanoComposition.getIntrusiveRock(volcano.silicateLevel);
            } else if (blockTypeName.contains("sand")) {
                material = isExtrusive ? VolcanoComposition.getExtrusiveRock(volcano.silicateLevel) : (
                    VolcanoComposition.getIntrusiveRock(volcano.silicateLevel)
                );
            } else {
                return;
            }
        }

        block.setType(material);
        if (material == Material.LAVA) {
            VolcanoVent vent = this.volcano.manager.getNearestVent(block);
            if (vent != null) {
                vent.lavaFlow.registerLavaCoolData(block, true);
            }
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
        } else if (material == Material.WATER) {
            block.setType(Material.AIR);
        } else if (material == Material.WATER_CAULDRON) {
            block.setType(Material.CAULDRON);
        }
    }


}
