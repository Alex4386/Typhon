package me.alex4386.plugin.typhon.volcano.vent;

import me.alex4386.plugin.typhon.TyphonScheduler;

public class VolcanoVentBuilder {
    VolcanoVent vent;

    boolean isEnabled = false;

    private VolcanoVentBuilderType type;
    protected double yThreshold;

    private int taskId = -1;
    boolean autoStartEnabled = false;

    public VolcanoVentBuilder(VolcanoVent vent) {
        this.vent = vent;
    }

    public void initialize() {
        this.registerTask();
    }

    public void shutdown() {
        this.unregisterTask();
    }

    public void registerTask() {
        if (taskId < 0) {
            taskId = TyphonScheduler.registerGlobalTask(() -> {
                if (this.type != null && this.isEnabled) {
                    if (this.isPredicateMatch()) {
                        this.onPredicateMatch();
                    }
                }
            }, 20L);
        }
    }

    public void unregisterTask() {
        if (taskId >= 0) {
            TyphonScheduler.unregisterTask(taskId);
            taskId = -1;
        }
    }

    public void setType(VolcanoVentBuilderType type) {
        this.type = type;
    }

    public VolcanoVentBuilderType getType() {
        return this.type;
    }

    public boolean setArguments(String[] args) {
        return this.type.setArguments(this, args);
    }

    public void enable() {
        this.isEnabled = true;
    }

    public boolean isRunning() {
        return this.isEnabled;
    }

    private boolean isPredicateMatch() {
        switch (type) {
            case Y_THRESHOLD:
                return vent.getSummitBlock().getY() > yThreshold;
            default:
                break;
        }

        return false;
    }

    private void onPredicateMatch() {
        this.type = null;
        this.isEnabled = false;
        this.vent.stop();
    }

}
