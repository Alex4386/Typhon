package me.alex4386.plugin.typhon.volcano.log;

import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;

import org.bukkit.block.Block;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class VolcanoVentRecord {
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

    public int getRecordIndex() {
        return ejectaVolumeList.size();
    }

    public long getCurrentLavaFlowEndTime() {
        // check if we are currently tracking ejecta
        if (startEjectaTracking >= 0 || this.getRecordIndex() == 0) {
            // then it is now.
            return System.currentTimeMillis();
        } else {
            // get the latest
            return ejectaVolumeList.get(this.getRecordIndex() - 1).endOfLavaFlowTime;
        }
    }

    public void addEjectaVolume(int ejectaVolume) {
        this.startEjectaTracking();
        this.currentEjectaVolume += ejectaVolume;
    }

    public void addEjectaVolume(int ejectaVolume, int recordIndex) {
        if (recordIndex >= 0 && recordIndex < ejectaVolumeList.size()) {
            ejectaVolumeList.get(recordIndex).ejectaVolume += ejectaVolume;
            
            // also updating this via recordIdx mean the lavaflow is still going on.
            // update endOfLavaFlowTracking
            ejectaVolumeList.get(recordIndex).endOfLavaFlowTime = System.currentTimeMillis();
        } else {
            this.addEjectaVolume(ejectaVolume);
        }
    }

    public void endEjectaTrack() {
        if (startEjectaTracking < 0) return;
        long startTime = startEjectaTracking;
        long endTime = System.currentTimeMillis();

        VolcanoVentEjectaTimeData timeData =
                new VolcanoVentEjectaTimeData(startTime, endTime, currentEjectaVolume);
        timeData.endOfLavaFlowTime = endTime;

        // Capture vent metadata snapshot
        Block summitBlock = vent.getSummitBlock();
        if (summitBlock != null) {
            timeData.summitX = summitBlock.getX();
            timeData.summitY = summitBlock.getY();
            timeData.summitZ = summitBlock.getZ();
        }
        timeData.baseY = vent.bombs.getBaseY();
        timeData.silicateLevel = vent.lavaFlow.settings.silicateLevel;
        timeData.gasContent = vent.lavaFlow.settings.gasContent;
        timeData.eruptionStyle = vent.erupt.getStyle().toString();
        timeData.ventType = vent.getType().toString();
        timeData.craterRadius = vent.craterRadius;
        timeData.longestFlowLength = vent.longestFlowLength;
        timeData.longestNormalLavaFlowLength = vent.longestNormalLavaFlowLength;
        timeData.currentFlowLength = vent.currentFlowLength;
        timeData.currentNormalLavaFlowLength = vent.currentNormalLavaFlowLength;

        currentEjectaVolume = 0;
        startEjectaTracking = -1;

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

    public boolean isEjectaTrackOngoing() {
        return startEjectaTracking >= 0;
    }

    public int getLatestRecordIndex() {
        return Math.max(0, ejectaVolumeList.size() - 1);
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
