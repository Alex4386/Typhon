package me.alex4386.plugin.typhon.volcano.utils;

import me.alex4386.plugin.typhon.volcano.Volcano;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public

class VolcanoConstructionUtils {

    public static List<VolcanoConstructionRaiseData> getLaccolithConstructionData(Volcano volcano, int maxHeight, int baseRadius) {
        return getPDFConsturctionData(volcano.location, maxHeight, baseRadius, Material.LAVA, false);
    }

    public static List<VolcanoConstructionRaiseData> getLopolithConstructionData(Volcano volcano, int maxHeight, int baseRadius) {
        return getPDFConsturctionData(volcano.location, maxHeight, baseRadius, Material.LAVA, true);
    }

    public static List<VolcanoConstructionRaiseData> getLaccolithConstructionData(Location location, int maxHeight, int baseRadius) {
        return getPDFConsturctionData(location, maxHeight, baseRadius, Material.LAVA, false);
    }

    public static List<VolcanoConstructionRaiseData> getLopolithConstructionData(Location location, int maxHeight, int baseRadius) {
        return getPDFConsturctionData(location, maxHeight, baseRadius, Material.LAVA, true);
    }

    public static List<VolcanoConstructionRaiseData> getCalderaConstructionData(Location location, int maxHeight, int baseRadius) {
        return getPDFConsturctionData(location, maxHeight, baseRadius, Material.AIR, true);
    }

    public static Map<Integer, Integer> getPDFHeightMap(int baseRadius, int maxHeight) {
        Map<Integer, Integer> radiusHeightMap = new LinkedHashMap<>();
        int currentHeight = 0;

        for (int i = baseRadius; i >= 0; i--) {
            int radiusHeight = (int) (VolcanoMath.volcanoPdfHeight(i / (double) baseRadius) * maxHeight);

            if (radiusHeight != currentHeight) {
                int heightDiff = radiusHeight - currentHeight;
                currentHeight = radiusHeight;
                radiusHeightMap.put(i, heightDiff);
            }
        }

        return radiusHeightMap;
    }

    public static List<VolcanoConstructionMagmaChamberFillData> getPDFMagmaChamberData(Location location, int maxHeight, int baseRadius, Material replacement, boolean isSink) {
        Map<Integer, Integer> radiusHeightMap = getPDFHeightMap(baseRadius, maxHeight);

        List<VolcanoConstructionMagmaChamberFillData> volcanoMagmaChamberFillData = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : radiusHeightMap.entrySet()) {
            int radius = entry.getKey();
            int raise = entry.getValue();

            List<Block> circleBlocks = VolcanoMath.getCircle(location.getBlock(), radius);

            for (Block block:circleBlocks) {
                volcanoMagmaChamberFillData.add(
                        new VolcanoConstructionMagmaChamberFillData(
                                block,
                                isSink ? -raise : raise,
                                replacement
                        )
                );
            }
        }
        return volcanoMagmaChamberFillData;
    }

    public static List<VolcanoConstructionRaiseData> getPDFConsturctionData(Location location, int maxHeight, int baseRadius, Material replacement, boolean isSink) {
        Map<Integer, Integer> radiusHeightMap = getPDFHeightMap(baseRadius, maxHeight);

        List<VolcanoConstructionRaiseData> volcanoConstructionRaiseData = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : radiusHeightMap.entrySet()) {
            int radius = entry.getKey();
            int raise = entry.getValue();

            List<Block> circleBlocks = VolcanoMath.getCircle(location.getBlock(), radius);

            for (Block block:circleBlocks) {
                volcanoConstructionRaiseData.add(
                        new VolcanoConstructionRaiseData(
                                block,
                                isSink ? -raise : raise,
                                replacement
                        )
                );
            }
        }

        return volcanoConstructionRaiseData;
    }


}
