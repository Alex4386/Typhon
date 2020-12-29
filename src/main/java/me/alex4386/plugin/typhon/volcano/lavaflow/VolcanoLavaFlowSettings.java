package me.alex4386.plugin.typhon.volcano.lavaflow;

import org.json.simple.JSONObject;

public
class VolcanoLavaFlowSettings {
    public boolean enabled = VolcanoLavaFlowDefaultSettings.enabled;
    public boolean flowing = false;

    public int flowed = VolcanoLavaFlowDefaultSettings.flowed;
    public int delayFlowed = VolcanoLavaFlowDefaultSettings.delayFlowed;

    public void importConfig(JSONObject configData) {
        this.enabled = (boolean) configData.get("enabled");
        this.flowing = (boolean) configData.get("flowing");

        this.flowed = (int) (long) configData.get("flowed");
        this.delayFlowed = (int) (long) configData.get("delay");
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        configData.put("enabled", this.enabled);
        configData.put("flowing", this.flowing);

        configData.put("flowed", this.flowed);
        configData.put("delay", this.delayFlowed);

        return configData;
    }
}

