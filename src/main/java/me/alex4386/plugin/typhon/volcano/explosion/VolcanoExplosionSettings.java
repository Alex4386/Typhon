package me.alex4386.plugin.typhon.volcano.explosion;

import org.json.simple.JSONObject;

public class VolcanoExplosionSettings {
    public int explosionDelay = VolcanoExplosionDefaultSettings.explosionDelay;
    public int explosionSize = VolcanoExplosionDefaultSettings.explosionSize;
    public int damagingExplosionSize = VolcanoExplosionDefaultSettings.damagingExplosionSize;
    public int minBombCount = VolcanoExplosionDefaultSettings.minBombCount;
    public int maxBombCount = VolcanoExplosionDefaultSettings.maxBombCount;

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

class VolcanoExplosionDefaultSettings {
    public static int explosionDelay = 100;
    public static int explosionSize = 8;
    public static int damagingExplosionSize = 2;
    public static int minBombCount = 20;
    public static int maxBombCount = 100;

    public static void importConfig(JSONObject configData) {
        VolcanoExplosionSettings settings = new VolcanoExplosionSettings();
        settings.importConfig(configData);

        explosionDelay = settings.explosionDelay;
        explosionSize = settings.explosionSize;
        damagingExplosionSize = settings.damagingExplosionSize;
        minBombCount = settings.minBombCount;
        maxBombCount = settings.maxBombCount;
    }
}
