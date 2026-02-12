package me.alex4386.plugin.typhon.volcano.log;

import org.json.simple.JSONObject;

public class VolcanoVentEjectaTimeData {
    public long startTime;
    public long endTime;

    public int ejectaVolume;

    // Snapshot metadata captured at eruption end
    public int summitX = Integer.MIN_VALUE;
    public int summitY = Integer.MIN_VALUE;
    public int summitZ = Integer.MIN_VALUE;
    public int baseY = Integer.MIN_VALUE;
    public double silicateLevel = -1;
    public double gasContent = -1;
    public String eruptionStyle = null;
    public String ventType = null;
    public int craterRadius = -1;
    public double longestFlowLength = -1;
    public double longestNormalLavaFlowLength = -1;
    public double currentFlowLength = -1;
    public double currentNormalLavaFlowLength = -1;

    public VolcanoVentEjectaTimeData(long startTime, long endTime, int ejectaVolume) {
        this.startTime = startTime;
        this.endTime = endTime;
        this.ejectaVolume = ejectaVolume;
    }

    public VolcanoVentEjectaTimeData(JSONObject jsonObject) {
        this.load(jsonObject);
    }

    public boolean hasMetadata() {
        return summitY != Integer.MIN_VALUE;
    }

    public void load(JSONObject jsonObject) {
        JSONObject timeData = (JSONObject) jsonObject.get("time");
        this.startTime = (long) timeData.get("start");
        this.endTime = (long) timeData.get("end");

        this.ejectaVolume = (int) (long) jsonObject.get("volume");

        // Load metadata if present (backward-compatible)
        JSONObject meta = (JSONObject) jsonObject.get("metadata");
        if (meta != null) {
            JSONObject summit = (JSONObject) meta.get("summit");
            if (summit != null) {
                this.summitX = (int) (long) summit.get("x");
                this.summitY = (int) (long) summit.get("y");
                this.summitZ = (int) (long) summit.get("z");
            }
            if (meta.containsKey("baseY")) this.baseY = (int) (long) meta.get("baseY");
            if (meta.containsKey("silicateLevel")) this.silicateLevel = (double) meta.get("silicateLevel");
            if (meta.containsKey("gasContent")) this.gasContent = (double) meta.get("gasContent");
            if (meta.containsKey("eruptionStyle")) this.eruptionStyle = (String) meta.get("eruptionStyle");
            if (meta.containsKey("ventType")) this.ventType = (String) meta.get("ventType");
            if (meta.containsKey("craterRadius")) this.craterRadius = (int) (long) meta.get("craterRadius");
            if (meta.containsKey("longestFlowLength")) this.longestFlowLength = (double) meta.get("longestFlowLength");
            if (meta.containsKey("longestNormalLavaFlowLength")) this.longestNormalLavaFlowLength = (double) meta.get("longestNormalLavaFlowLength");
            if (meta.containsKey("currentFlowLength")) this.currentFlowLength = (double) meta.get("currentFlowLength");
            if (meta.containsKey("currentNormalLavaFlowLength")) this.currentNormalLavaFlowLength = (double) meta.get("currentNormalLavaFlowLength");
        }
    }

    @SuppressWarnings("unchecked")
    public JSONObject serialize() {
        JSONObject jsonObject = new JSONObject();

        JSONObject timeData = new JSONObject();
        timeData.put("start", startTime);
        timeData.put("end", endTime);

        jsonObject.put("time", timeData);
        jsonObject.put("volume", ejectaVolume);

        // Serialize metadata if present
        if (hasMetadata()) {
            JSONObject meta = new JSONObject();
            JSONObject summit = new JSONObject();
            summit.put("x", summitX);
            summit.put("y", summitY);
            summit.put("z", summitZ);
            meta.put("summit", summit);
            meta.put("baseY", baseY);
            meta.put("silicateLevel", silicateLevel);
            meta.put("gasContent", gasContent);
            meta.put("eruptionStyle", eruptionStyle);
            meta.put("ventType", ventType);
            meta.put("craterRadius", craterRadius);
            meta.put("longestFlowLength", longestFlowLength);
            meta.put("longestNormalLavaFlowLength", longestNormalLavaFlowLength);
            meta.put("currentFlowLength", currentFlowLength);
            meta.put("currentNormalLavaFlowLength", currentNormalLavaFlowLength);
            jsonObject.put("metadata", meta);
        }

        return jsonObject;
    }
}
