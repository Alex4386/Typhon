package me.alex4386.plugin.typhon.volcano.lavaflow;

import org.json.simple.JSONObject;

public class VolcanoLavaFlowSettings {
    public boolean flowing = false;

    public int flowed = VolcanoLavaFlowDefaultSettings.flowed;
    public int delayFlowed = VolcanoLavaFlowDefaultSettings.delayFlowed;

    public double silicateLevel = 0.63;
    public double gasContent = 0.4;

    public void importConfig(JSONObject configData) {
        this.flowing = (boolean) configData.get("flowing");

        this.flowed = (int) (long) configData.get("flowed");
        this.delayFlowed = (int) (long) configData.get("delay");

        this.silicateLevel = (double) configData.get("silicateLevel");
        this.gasContent = (double) configData.getOrDefault("gasContent", 0.4);
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        configData.put("flowing", this.flowing);

        configData.put("flowed", this.flowed);
        configData.put("delay", this.delayFlowed);

        configData.put("silicateLevel", this.silicateLevel);
        configData.put("gasContent", this.gasContent);

        return configData;
    }
}
