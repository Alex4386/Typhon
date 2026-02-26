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
    public long startTick = -1;
    public int currentEjectaVolume = 0;

    public List<VolcanoVentEjectaTimeData> ejectaVolumeList = new ArrayList<>();

    public VolcanoVentRecord(VolcanoVent vent) {
        this.vent = vent;
    }

    public boolean isEjectaTrackingStarted() {
        return startEjectaTracking >= 0;
    }

    public void startEjectaTracking() {
        if (!this.isEjectaTrackingStarted()) {
            startEjectaTracking = System.currentTimeMillis();
            if (vent != null) {
                startTick = vent.location.getWorld().getFullTime();
            }
        }
    }

    public int getCurrentRecordIndex() {
        if (this.isEjectaTrackingStarted()) return ejectaVolumeList.size();
        if (ejectaVolumeList.isEmpty()) return 0;
        return ejectaVolumeList.size() - 1;
    }

    public long getCurrentLavaFlowEndTime() {
        // check if we are currently tracking ejecta
        if (this.isEjectaTrackingStarted() || this.getCurrentRecordIndex() == 0) {
            // then it is now.
            return System.currentTimeMillis();
        } else {
            // get the latest
            return this.getCurrentRecord().endOfLavaFlowTime;
        }
    }

    public long getCurrentLavaFlowEndTick() {
        // check if we are currently tracking ejecta
        if (this.isEjectaTrackingStarted() || this.getCurrentRecordIndex() == 0) {
            // then it is now.
            if (this.vent != null) {
                return this.vent.location.getWorld().getFullTime();
            }

            return -1;
        } else {
            // get the latest
            return this.getCurrentRecord().endOfLavaFlowTick;
        }
    }

    public VolcanoVentEjectaTimeData getCurrentRecord() {
        if (this.getCurrentRecordIndex() == 0) return null;
        if (this.isEjectaTrackingStarted()) {
            // we should generate this on fly
            VolcanoVentEjectaTimeData timeData = new VolcanoVentEjectaTimeData(startEjectaTracking, System.currentTimeMillis(), this.currentEjectaVolume);
            timeData.endOfLavaFlowTime = System.currentTimeMillis();
            timeData.endOfLavaFlowTick = (timeData.endOfLavaFlowTime - startEjectaTracking);

            // TODO: Generate fake record
        }

        return this.ejectaVolumeList.get(this.getCurrentRecordIndex());
    }

    public void addEjectaVolume(int ejectaVolume) {
        this.addEjectaVolume(ejectaVolume, -1);
    }

    public void addEjectaVolume(int ejectaVolume, int recordIndex) {
        // if it is existing
        if (recordIndex >= 0 && recordIndex < ejectaVolumeList.size()) {
            ejectaVolumeList.get(recordIndex).ejectaVolume += ejectaVolume;

            long currentTime = System.currentTimeMillis();
            long lastFlowTime = ejectaVolumeList.get(recordIndex).endOfLavaFlowTime;
            long diff = currentTime - lastFlowTime;

            ejectaVolumeList.get(recordIndex).endOfLavaFlowTime = currentTime;

            // only update when 20tps tick have passed.
            if (diff > 50) {
                if (vent != null) {
                    ejectaVolumeList.get(recordIndex).endOfLavaFlowTick = vent.location.getWorld().getFullTime();
                }
            }
        // this is new!
        } else {
            this.startEjectaTracking();
            this.currentEjectaVolume += ejectaVolume;
        }
    }

    public void endEjectaTrack() {
        if (startEjectaTracking < 0) return;
        long startTime = startEjectaTracking;
        long endTime = System.currentTimeMillis();

        // fallback calculation supposing 20tps
        long startTick = this.startTick;
        long fallbackTicks = (long) (((endTime - startTime) / 1000.0) * 20);
        long endTick = startTick >= 0 ? startTick + fallbackTicks : fallbackTicks;

        if (vent != null) {
            long tmpTick = vent.location.getWorld().getFullTime();

            // only accept when endTick isn't rolled back
            if (startTick < 0 || tmpTick >= startTick) {
                endTick = tmpTick;
            }
        }

        // reset to completed state
        this.startEjectaTracking = -1;
        this.startTick = -1;

        VolcanoVentEjectaTimeData timeData =
                new VolcanoVentEjectaTimeData(startTime, endTime, currentEjectaVolume);
        timeData.endOfLavaFlowTime = endTime;

        timeData.startTick = startTick;
        timeData.endTick = endTick;
        timeData.endOfLavaFlowTick = endTick;

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
