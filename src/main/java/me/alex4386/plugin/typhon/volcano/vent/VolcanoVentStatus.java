package me.alex4386.plugin.typhon.volcano.vent;

public enum VolcanoVentStatus {
    EXTINCT("EXTINCT", 0.0),
    DORMANT("DORMANT", 0.04),
    MINOR_ACTIVITY("MINOR_ACTIVITY", 0.1),
    MAJOR_ACTIVITY("MAJOR_ACTIVITY", 0.8),
    ERUPTING("ERUPTING", 1);

    String string;
    double scaleFactor;

    VolcanoVentStatus(String string, double scaleFactor) {
        this.string = string;
        this.scaleFactor = scaleFactor;
    }

    public String toString() {
        return this.string;
    }

    public double getScaleFactor() {
        return this.scaleFactor;
    }

    public static boolean isValidStatus(String string) {
        return getStatus(string) != null;
    }

    public static VolcanoVentStatus getStatus(String string) {
        for (VolcanoVentStatus status : VolcanoVentStatus.values()) {
            if (status.toString().equalsIgnoreCase(string)) {
                return status;
            }
        }

        return null;
    }

    public boolean isActive() {
        return !this.equals(EXTINCT);
    }

    public VolcanoVentStatus increase() {
        switch (this) {
            case EXTINCT:
                return EXTINCT;
            case DORMANT:
                return MINOR_ACTIVITY;
            case MINOR_ACTIVITY:
                return MAJOR_ACTIVITY;
            case MAJOR_ACTIVITY:
            case ERUPTING:
            default:
                return ERUPTING;
        }
    }

    public VolcanoVentStatus decrease() {
        switch (this) {
            case EXTINCT:
                return EXTINCT;
            case DORMANT:
            case MINOR_ACTIVITY:
                return DORMANT;
            case MAJOR_ACTIVITY:
                return MINOR_ACTIVITY;
            case ERUPTING:
            default:
                return MAJOR_ACTIVITY;
        }
    }
}
