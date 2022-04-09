package me.alex4386.plugin.typhon.volcano.intrusions;

import me.alex4386.plugin.typhon.TyphonUtils;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.utils.VolcanoMath;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.json.simple.JSONObject;

import java.util.List;

public class VolcanoDike {
    public Volcano volcano;

    public String name;

    public boolean enabled;

    public Block dikeBase;
    public Block currentIntrusionBlock;
    public int dikeRadius;

    public VolcanoMagmaChamber baseChamber;

    public VolcanoDike(Volcano volcano, VolcanoMagmaChamber baseChamber, Block dikeBase, int dikeRadius) {
        this.volcano = volcano;
        this.dikeBase = dikeBase;
        this.dikeRadius = dikeRadius;
        this.baseChamber = baseChamber;
    }

    public VolcanoDike(Volcano volcano, JSONObject configData) {
        this.volcano = volcano;
        this.importConfig(configData);
    }

    public List<Block> getDikeFullCylinderBlocks() {
        return VolcanoMath.getCylinder(this.dikeBase, this.dikeRadius, this.getTotalDikeLength());
    }

    public List<Block> getDikeCurrentCylinderBlocks() {
        return VolcanoMath.getCylinder(this.dikeBase, this.dikeRadius, this.getCurrentDikeLength() );
    }

    public List<Block> getBaseCircle() {
        return VolcanoMath.getCircle(this.dikeBase, this.dikeRadius);
    }

    public int getTotalDikeLength() {
        return this.getSurfaceY() - this.dikeBase.getY();
    }

    public int getCurrentDikeLength() {
        return this.currentIntrusionBlock.getY() - this.dikeBase.getY();
    }

    public boolean isThisDikeBlocked() {
        for (Block block : this.getDikeFullCylinderBlocks()) {
            if (block.getType() != Material.LAVA) {
                return true;
            }
        }
        return false;
    }

    public void dikeCooldown() {
        for (Block block : this.getDikeCurrentCylinderBlocks()) {
            if (block.getType() == Material.LAVA) {
                if (block.getY() + 5 <= getSurfaceY()) {
                    block.setType(volcano.composition.getExtrusiveRockMaterial());
                } else {
                    block.setType(volcano.composition.getIntrusiveRockMaterial());
                }
            }
        }
        this.currentIntrusionBlock = this.dikeBase;
    }

    public int getSurfaceY() {
        int highestY = 0;

        for (Block block : this.getBaseCircle()) {
            int y = TyphonUtils.getHighestRocklikes(block.getLocation()).getY();
            highestY = Math.max(y, highestY);
        }

        return highestY;
    }

    public int howMuchToIntrude() {
        return this.getSurfaceY() - this.currentIntrusionBlock.getY();
    }

    public int getIntrusionTotalDistance() {
        return this.getSurfaceY() - this.dikeBase.getY();
    }

    public void showSmoke() {
        Location smokeLoc = TyphonUtils.getHighestOceanFloor(this.dikeBase.getLocation());
        float multiplier = ((float) this.howMuchToIntrude() / (float) this.getIntrusionTotalDistance());

        smokeLoc.getWorld().spawnParticle(
                Particle.CLOUD,
                smokeLoc,
                (int)(500 * multiplier)
        );
    }

    public void importConfig(JSONObject configData) {
        this.enabled = (boolean) configData.get("enabled");

        this.dikeBase = TyphonUtils.deserializeLocationForJSON((JSONObject) configData.get("base")).getBlock();
        this.currentIntrusionBlock = TyphonUtils.deserializeLocationForJSON((JSONObject) configData.get("intrudeLocation")).getBlock();
        this.dikeRadius = (int) configData.get("radius");
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        configData.put("enabled", this.enabled);

        configData.put("base", TyphonUtils.serializeLocationForJSON(dikeBase.getLocation()));
        configData.put("intrudeLocation", TyphonUtils.serializeLocationForJSON(currentIntrusionBlock.getLocation()));
        configData.put("radius", dikeRadius);

        return configData;
    }
}
