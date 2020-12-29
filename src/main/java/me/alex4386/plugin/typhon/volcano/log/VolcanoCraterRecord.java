package me.alex4386.plugin.typhon.volcano.log;

import me.alex4386.plugin.typhon.volcano.crater.VolcanoCrater;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.List;

public
class VolcanoCraterRecord {
    VolcanoCrater crater;

    public long startEjectaTracking = -1;
    public int currentEjectaVolume = 0;

    public List<VolcanoCraterEjectaTimeData> ejectaVolumeList = new ArrayList<>();

    public VolcanoCraterRecord(VolcanoCrater crater) {
        this.crater = crater;
    }

    public boolean isEjectaTrackingStarted() {
        return startEjectaTracking < 0;
    }

    public void startEjectaTracking() {
        if (this.isEjectaTrackingStarted()) {
            startEjectaTracking = System.currentTimeMillis();
        }
    }

    public void addEjectaVolume(int ejectaVolume) {
        this.startEjectaTracking();
        this.currentEjectaVolume += ejectaVolume;
    }

    public void endEjectaTrack() {
        if (startEjectaTracking < 0) return;
        long startTime = startEjectaTracking;
        long endTime = System.currentTimeMillis();

        VolcanoCraterEjectaTimeData timeData = new VolcanoCraterEjectaTimeData(startTime, endTime, currentEjectaVolume);
        currentEjectaVolume = 0;

        ejectaVolumeList.add(timeData);
    }

    public void importConfig(JSONObject configData) {
        JSONObject ejectaData = (JSONObject) configData.get("ejecta");
        JSONArray ejectaTimeLog = (JSONArray) ejectaData.get("timeData");

        for (Object timeDataRaw : ejectaTimeLog) {
            JSONObject timeData = (JSONObject) timeDataRaw;
            VolcanoCraterEjectaTimeData craterEjectaTimeData = new VolcanoCraterEjectaTimeData(timeData);

            ejectaVolumeList.add(craterEjectaTimeData);
        }
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        JSONObject ejectaData = new JSONObject();

        JSONArray ejectaTimeLog = new JSONArray();
        for (VolcanoCraterEjectaTimeData timeData : ejectaVolumeList) {
            ejectaTimeLog.add(timeData.serialize());
        }
        ejectaData.put("timeData", ejectaTimeLog);

        configData.put("ejecta", ejectaData);

        return configData;
    }
}
