package me.alex4386.plugin.typhon.volcano.crater;

import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogger;
import org.bukkit.Material;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class VolcanoComposition {
    public Volcano volcano;
    public List<VolcanoCompositionData> extrusiveRockComposition = VolcanoCompositionUtils.importCompositions(VolcanoCompositionUtils.defaultExtrusiveComposition);
    public List<VolcanoCompositionData> intrusiveRockComposition = VolcanoCompositionUtils.importCompositions(VolcanoCompositionUtils.defaultIntrusiveComposition);

    public VolcanoComposition(Volcano volcano) {
        this.volcano = volcano;
    }

    public Material getExtrusiveRockMaterial() {
        return VolcanoCompositionUtils.getRandomMaterial(extrusiveRockComposition);
    }

    public Material getIntrusiveRockMaterial() {
        return VolcanoCompositionUtils.getRandomMaterial(intrusiveRockComposition);
    }

    public void importConfig(JSONObject configData) {
        String extrusiveComposition = (String) configData.get("extrusive");
        String intrusiveComposition = (String) configData.get("intrusive");

        this.extrusiveRockComposition = VolcanoCompositionUtils.importCompositions(extrusiveComposition);
        this.intrusiveRockComposition = VolcanoCompositionUtils.importCompositions(intrusiveComposition);
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        String extrusiveComposition = VolcanoCompositionUtils.exportCompositions(this.extrusiveRockComposition);
        String intrusiveComposition = VolcanoCompositionUtils.exportCompositions(this.intrusiveRockComposition);

        configData.put("extrusive", extrusiveComposition);
        configData.put("intrusive", intrusiveComposition);

        return configData;
    }
}

class VolcanoCompositionUtils {
    public static String defaultExtrusiveComposition = "BLACKSTONE,27/BASALT,57/ANDESITE,16";
    public static String defaultIntrusiveComposition = "DIORITE,33/GRANITE,33/STONE,34";

    public static void importConfig(JSONObject configData) {
        defaultExtrusiveComposition = (String) configData.get("extrusive");
        defaultExtrusiveComposition = (String) configData.get("intrusive");
    }

    public static JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        configData.put("extrusive", defaultExtrusiveComposition);
        configData.put("intrusive", defaultIntrusiveComposition);

        return configData;
    }


    public static List<VolcanoCompositionData> importCompositions(String layer) {

        VolcanoLogger logger = new VolcanoLogger();
        logger.log(VolcanoLogClass.COMPOSITION, "layer "+layer);


        List<VolcanoCompositionData> composition = new ArrayList<VolcanoCompositionData>();
        double sum = 0;

        String[] parsedLayer = {layer};

        if (layer.contains("/")) {
            parsedLayer = layer.split("/");
        }

        for (int i = 0; i < parsedLayer.length; i++) {
            String[] data = parsedLayer[i].split(",");
            double percentage = Double.parseDouble(data[1]);
            VolcanoCompositionData volcCompData = new VolcanoCompositionData(
                    Double.parseDouble(data[1]),
                    Material.getMaterial(data[0])
            );
            sum += percentage;
            composition.add(volcCompData);
        }
        if (sum < 100) {
            double missing = 100f - sum;
            logger.warn(VolcanoLogClass.COMPOSITION, missing+"% of material data is missing, filling data with stone.");

            VolcanoCompositionData fittingData = new VolcanoCompositionData(
                    missing,
                    Material.STONE
            );

            composition.add(fittingData);
        } else if (sum > 100) {
            logger.warn(VolcanoLogClass.COMPOSITION, (sum - 100f)+"% of material data is overflowed, proceed with caution.");
        }
        return composition;
    }

    public static String exportCompositions(List<VolcanoCompositionData> composition) {
        String layerData = "";
        for (int i = 0; i < composition.size(); i++) {
            layerData += composition.get(i).material+","+composition.get(i).percentage;
            if ((i + 1) != composition.size()) {
                layerData += "/";
            }
        }
        return layerData;
    }

    public static Material getRandomMaterial(List<VolcanoCompositionData> composition) {
        Random random = new Random();
        double randomValue = random.nextDouble() * 100f;

        Collections.shuffle(composition);

        double base = 0.0f;
        for (int i = 0; i < composition.size(); i++) {
            VolcanoCompositionData data = composition.get(i);
            if (base <= randomValue && randomValue < base+data.percentage) {
                return data.material;
            }
            base += data.percentage;
        }

        return composition.get(0).material;
    }
}

class VolcanoCompositionData {
    public double percentage;
    public Material material;

    public VolcanoCompositionData(double percentage, Material material) {
        this.percentage = percentage;
        this.material = material;
    }
}
