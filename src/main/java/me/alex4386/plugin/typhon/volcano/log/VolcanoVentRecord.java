package me.alex4386.plugin.typhon.volcano.log;

import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.log.VolcanoVentEjectaTimeData;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.List;

public
class VolcanoVentRecord {
    VolcanoVent vent;

    public long startEjectaTracking = -1;
    public int currentEjectaVolume = 0;

    public List<VolcanoVentEjectaTimeData> ejectaVolumeList = new ArrayList<>();

    public VolcanoVentRecord(VolcanoVent vent) {
        this.vent = vent;
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

        VolcanoVentEjectaTimeData timeData = new VolcanoVentEjectaTimeData(startTime, endTime, currentEjectaVolume);
        currentEjectaVolume = 0;

        ejectaVolumeList.add(timeData);
    }

    public long getTotalEjecta() {
        long total = 0;
        for (VolcanoVentEjectaTimeData timeData : ejectaVolumeList) {
            total += timeData.ejectaVolume;
        }
        total += this.currentEjectaVolume;

        return total;
    }

    public void importConfig(JSONObject configData) {
        JSONObject ejectaData = (JSONObject) configData.get("ejecta");
        JSONArray ejectaTimeLog = (JSONArray) ejectaData.get("timeData");

        for (Object timeDataRaw : ejectaTimeLog) {
            JSONObject timeData = (JSONObject) timeDataRaw;
            VolcanoVentEjectaTimeData ventEjectaTimeData = new VolcanoVentEjectaTimeData(timeData);

            ejectaVolumeList.add(ventEjectaTimeData);
        }
    }

    public JSONObject exportConfig() {
        JSONObject configData = new JSONObject();

        JSONObject ejectaData = new JSONObject();

        JSONArray ejectaTimeLog = new JSONArray();
        for (VolcanoVentEjectaTimeData timeData : ejectaVolumeList) {
            ejectaTimeLog.add(timeData.serialize());
        }
        ejectaData.put("timeData", ejectaTimeLog);

        configData.put("ejecta", ejectaData);

        return configData;
    }
}
