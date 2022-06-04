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

            if (
                blockTypeName.contains("dirt")
                || blockTypeName.contains("podzol")
                || blockTypeName.contains("grass")
                || blockTypeName.contains("sand")
            ) {
                material = 
                    isBomb ?
                        VolcanoComposition.getBombRock(silicateLevel) :
                        VolcanoComposition.getExtrusiveRock(silicateLevel);
            } else {
                return;
            }
        }

        block.setType(material);
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
