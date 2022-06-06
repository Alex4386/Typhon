package me.alex4386.plugin.typhon.volcano.log;

import org.json.simple.JSONObject;

public class VolcanoVentEjectaTimeData {
    long startTime;
    long endTime;

    int ejectaVolume;

    public VolcanoVentEjectaTimeData(long startTime, long endTime, int ejectaVolume) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.ejectaVolume = ejectaVolume;
    }

    public VolcanoVentEjectaTimeData(JSONObject jsonObject) {
        this.load(jsonObject);
    }

    public void load(JSONObject jsonObject) {
        JSONObject timeData = (JSONObject) jsonObject.get("time");
        this.startTime = (long) timeData.get("start");
        this.endTime = (long) timeData.get("end");

        this.ejectaVolume = (int) (long) jsonObject.get("volume");
    }

    public JSONObject serialize() {
        JSONObject jsonObject = new JSONObject();

        JSONObject timeData = new JSONObject();
        timeData.put("start", startTime);
        timeData.put("end", endTime);

        jsonObject.put("time", timeData);
        jsonObject.put("volume", ejectaVolume);

        return jsonObject;
    }
}
