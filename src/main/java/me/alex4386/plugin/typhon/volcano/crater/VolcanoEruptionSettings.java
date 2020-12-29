package me.alex4386.plugin.typhon.volcano.crater;


import org.json.simple.JSONObject;

public class VolcanoEruptionSettings {
    public int explosionDelay = VolcanoEruptionDefaultSettings.explosionDelay;
    public int explosionSize = VolcanoEruptionDefaultSettings.explosionSize;
    public int damagingExplosionSize = VolcanoEruptionDefaultSettings.damagingExplosionSize;
    public int minBombCount = VolcanoEruptionDefaultSettings.minBombCount;
    public int maxBombCount = VolcanoEruptionDefaultSettings.maxBombCount;

    public void importConfig(JSONObject configData) {
        JSONObject explosionData = (JSONObject) configData.get("explosion");
        JSONObject explosionSizeData = (JSONObject) explosionData.get("size");
        JSONObject bombCountData = (JSONObject) configData.get("bombCount");

        explosionDelay = (int) (long) explosionData.get("delay");
        explosionSize = (int) (long) explosionSizeData.get("withoutDamage");
        damagingExplosionSize = (int) (long) explosionSizeData.get("withDamage");
        minBombCount = (int) (long) bombCountData.get("min");
        maxBombCount = (int) (long) bombCountData.get("max");
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        JSONObject explosionSizeData = new JSONObject();
        explosionSizeData.put("withoutDamage", explosionSize);
        explosionSizeData.put("withDamage", damagingExplosionSize);

        JSONObject explosionData = new JSONObject();
        explosionData.put("delay", explosionDelay);
        explosionData.put("size", explosionSizeData);

        JSONObject bombCountData = new JSONObject();
        bombCountData.put("min", minBombCount);
        bombCountData.put("max", maxBombCount);

        configData.put("explosion", explosionData);
        configData.put("bombCount", bombCountData);

        return configData;
    }
}

class VolcanoEruptionDefaultSettings {
    public static int explosionDelay = 10;
    public static int explosionSize = 20;
    public static int damagingExplosionSize = 2;
    public static int minBombCount = 20;
    public static int maxBombCount = 100;

    public static void importConfig(JSONObject configData) {
        VolcanoEruptionSettings settings = new VolcanoEruptionSettings();
        settings.importConfig(configData);

        explosionDelay = settings.explosionDelay;
        explosionSize = settings.explosionSize;
        damagingExplosionSize = settings.damagingExplosionSize;
        minBombCount = settings.minBombCount;
        maxBombCount = settings.maxBombCount;
    }
}
