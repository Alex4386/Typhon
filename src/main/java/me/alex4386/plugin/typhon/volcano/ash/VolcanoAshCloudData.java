package me.alex4386.plugin.typhon.volcano.ash;

import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;

import java.util.HashMap;
import java.util.List;

public

class VolcanoAshCloudData {
    VolcanoAsh ash;
    public BlockDisplay bd;
    public double multiplier = 0.2;

    public int maxHeight;

    HashMap<Block, Block> ashFallBaseBlocks = new HashMap<>();

    public VolcanoAshCloudData(VolcanoAsh ash, BlockDisplay bd, double multiplier) {
        this(ash, bd, multiplier, ash.vent.location.getWorld().getMaxHeight() + 100);
    }

    public VolcanoAshCloudData(VolcanoAsh ash, BlockDisplay bd, double multiplier, int maxHeight) {
        this.ash = ash;
        this.bd = bd;
        this.multiplier = multiplier;
        this.maxHeight = maxHeight;
    }

}


