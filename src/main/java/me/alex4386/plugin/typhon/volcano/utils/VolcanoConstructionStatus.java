package me.alex4386.plugin.typhon.volcano.utils;

import me.alex4386.plugin.typhon.volcano.Volcano;
import org.bukkit.Location;

public class VolcanoConstructionStatus {
    public VolcanoConstructionType type;
    public Volcano volcano;
    public String jobName;
    public int currentStage;
    public int totalStages;

    public Location lastLocation = null;

    public VolcanoConstructionStatus(VolcanoConstructionType type, Volcano volcano, String jobName, int currentStage, int totalStages) {
        this.type = type;
        this.volcano = volcano;
        this.jobName = jobName;
        this.currentStage = currentStage;
        this.totalStages = totalStages;
    }

    public int getCurrentStage() {
        return this.currentStage;
    }

    public void setCurrentStage(int currentStage) {
        this.currentStage = currentStage;
    }

    public void stageComplete() {
        this.currentStage++;
    }

    public boolean isCompleted() {
        return this.totalStages <= this.currentStage;
    }

    public void setLastLocation(Location location) {
        this.lastLocation = location;
    }
}
