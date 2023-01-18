package me.alex4386.plugin.typhon.volcano.explosion;

import org.json.simple.JSONObject;

public class VolcanoExplosionSettings {
    public int explosionSize = VolcanoExplosionDefaultSettings.explosionSize;
    public int damagingExplosionSize = VolcanoExplosionDefaultSettings.damagingExplosionSize;
    public int minBombCount = VolcanoExplosionDefaultSettings.minBombCount;
    public int maxBombCount = VolcanoExplosionDefaultSettings.maxBombCount;
    public int delayTicks = VolcanoExplosionDefaultSettings.delayTicks;
    public int queueSize = VolcanoExplosionDefaultSettings.queueSize;

    public void importConfig(JSONObject configData) {
        JSONObject explosionSchedulersData = (JSONObject) configData.get("scheduler");
        JSONObject explosionSizeData = (JSONObject) explosionSchedulersData.get("size");
        JSONObject bombCountData = (JSONObject) configData.get("bombCount");

        explosionSize = (int) (long) explosionSizeData.get("withoutDamage");
        damagingExplosionSize = (int) (long) explosionSizeData.get("withDamage");
        minBombCount = (int) (long) bombCountData.get("min");
        maxBombCount = (int) (long) bombCountData.get("max");
        delayTicks = (int) (long) configData.getOrDefault("delay", (long) VolcanoExplosionDefaultSettings.delayTicks);
        queueSize = (int) (long) configData.getOrDefault("queueSize", (long) VolcanoExplosionDefaultSettings.queueSize);
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        JSONObject explosionSizeData = new JSONObject();
        explosionSizeData.put("withoutDamage", explosionSize);
        explosionSizeData.put("withDamage", damagingExplosionSize);

        JSONObject explosionSchedulerData = new JSONObject();
        explosionSchedulerData.put("size", explosionSizeData);

        JSONObject bombCountData = new JSONObject();
        bombCountData.put("min", minBombCount);
        bombCountData.put("max", maxBombCount);

        configData.put("scheduler", explosionSchedulerData);
        configData.put("bombCount", bombCountData);
        configData.put("delay", delayTicks);
        configData.put("queueSize", queueSize);

        return configData;
    }
}

class VolcanoExplosionDefaultSettings {
    public static int explosionSize = 8;
    public static int damagingExplosionSize = 2;
    public static int minBombCount = 30;
    public static int maxBombCount = 40;
    public static int delayTicks = 20 * 4;
    public static int queueSize = 20;

    public static void importConfig(JSONObject configData) {
        VolcanoExplosionSettings settings = new VolcanoExplosionSettings();
        settings.importConfig(configData);

        explosionSize = settings.explosionSize;
        damagingExplosionSize = settings.damagingExplosionSize;
        minBombCount = settings.minBombCount;
        maxBombCount = settings.maxBombCount;
        delayTicks = settings.delayTicks;
        queueSize = settings.queueSize;
    }
}
