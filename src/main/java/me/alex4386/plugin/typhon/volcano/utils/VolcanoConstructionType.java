package me.alex4386.plugin.typhon.volcano.utils;

public enum VolcanoConstructionType {
    NONE(0),
    BUILDING(1),
    FILLING(2),
    COOLING(3);

    private int code;

    VolcanoConstructionType(int code) {
        this.code = code;
    }

    public int getCode() {
        return this.code;
    }

    public boolean isWorking() {
        return this.code != 0;
    }

    public static VolcanoConstructionType getType(int code) {
        for (VolcanoConstructionType type : VolcanoConstructionType.values()) {
            if (type.getCode() == code) {
                return type;
            }
        }

        return null;
    }
}
