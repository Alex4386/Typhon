package me.alex4386.plugin.typhon.volcano.vent;

public enum VolcanoVentStatus {
    EXTINCT("EXTINCT", 0.0, false),
    DORMANT("DORMANT", 0.09, false),
    MINOR_ACTIVITY("MINOR_ACTIVITY", 0.3, true),
    MAJOR_ACTIVITY("MAJOR_ACTIVITY", 0.7, true),
    ERUPTION_IMMINENT("ERUPTION_IMMINENT", 0.9, true),
    ERUPTING("ERUPTING", 1, true);

    String string;
    double scaleFactor;
    boolean elevated;

    VolcanoVentStatus(String string, double scaleFactor, boolean elevated) {
        this.string = string;
        this.scaleFactor = scaleFactor;
        this.elevated = elevated;
    }

    public boolean hasElevatedActivity() {
        return this.elevated;
    }

    public String toString() {
        return this.string;
    }

    public double getScaleFactor() {
        return this.scaleFactor;
    }

    public double getThermalScaleFactor() {
        return Math.pow(this.scaleFactor, 1.5);
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
            case ERUPTION_IMMINENT:
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
            case ERUPTION_IMMINENT:
            default:
                return MAJOR_ACTIVITY;
        }
    }
}
