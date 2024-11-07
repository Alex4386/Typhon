package me.alex4386.plugin.typhon.volcano.ash;

import me.alex4386.plugin.typhon.TyphonUtils;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;

import java.util.HashMap;

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

    public Block getAshFallTarget() {
        double range = Math.random() * 5 * this.multiplier;
        double angle = Math.random() * 2 * Math.PI;
        return TyphonUtils.getHighestRocklikes(bd.getLocation().add(
                Math.sin(angle) * range,
                0,
                Math.cos(angle) * range
        ));
    }

    public void fallAsh() {

        if (!ash.vent.caldera.isForming()) {
            Block ashTarget = this.getAshFallTarget();
            if (ashFallBaseBlocks.get(ashTarget) != null) {
                ashTarget = ashFallBaseBlocks.get(ashTarget);
            } else {
                ashFallBaseBlocks.put(ashTarget, ashTarget);
            }

            if (ashTarget.getY() <= ash.getTargetY(ashTarget.getLocation())) {
                this.ash.vent.lavaFlow.queueBlockUpdate(ashTarget, Material.TUFF);
            }
        }
    }
}


