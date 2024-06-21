package me.alex4386.plugin.typhon.volcano.vent;


import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public enum VolcanoVentBuilderType {
    Y_THRESHOLD("y_threshold");

    private String name;
    VolcanoVentBuilderType(String name) {
        this.name = name;
    }

    public String getName() {
        return this.name;
    }

    boolean setArguments(VolcanoVentBuilder builder, String[] args) {
        if (this == Y_THRESHOLD) {
            if (args.length > 0) {
                try {
                    builder.yThreshold = Double.parseDouble(args[0]);
                    return true;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }

        return false;
    }

    Map<String, String> getArgumentMap(VolcanoVentBuilder builder) {
        Map<String, String> map = new HashMap<>();
        if (this == Y_THRESHOLD) {
            map.put("y_threshold", String.valueOf(builder.yThreshold));
        }

        return map;
    }

    boolean isPredicateMatch(VolcanoVentBuilder builder) {
        if (this == Y_THRESHOLD) {
            return builder.vent.getSummitBlock().getY() >= builder.yThreshold;
        }

        return false;
    }

    public static VolcanoVentBuilderType fromName(String name) {
        for (VolcanoVentBuilderType type : VolcanoVentBuilderType.values()) {
            if (type.getName().equalsIgnoreCase(name)) {
                return type;
            }
        }

        return null;
    }
}