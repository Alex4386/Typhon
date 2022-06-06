package me.alex4386.plugin.typhon.volcano.vent;

public enum VolcanoVentType {
    CRATER("crater"),
    FISSURE("fissure");

    String string;

    VolcanoVentType(String string) {
        this.string = string;
    }

    public String toString() {
        return this.string;
    }

    public static VolcanoVentType fromString(String string) {
        for (VolcanoVentType type : VolcanoVentType.values()) {
            if (type.toString().equalsIgnoreCase(string)) {
                return type;
            }
        }

        return null;
    }
}
