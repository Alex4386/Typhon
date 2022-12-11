package me.alex4386.plugin.typhon.volcano.erupt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public enum VolcanoEruptStyle {
    // lava lake + lava fountain (minimal), lava flow is everything of this.
    HAWAIIAN("hawaiian", new String[] { "effusive" }, VolcanoEruptCauseType.MAGMATIC, 4, 0.1, 0.1),

    // Lava fountain (with volcanic bombs) + can have lava flows, but minimal.
    // Usually goes into
    // bursting out into air
    // rocks: basaltic -> scoria (basaltic + andesite), keep in mind that tuff is
    // lessly used in
    // here
    STROMBOLIAN(
            "strombolian", new String[] { "stromboli" }, VolcanoEruptCauseType.MAGMATIC, 0, 1, 0.3),

    // stromboli but, range longer + with ash
    // rocks: andestic volcanic bombs (+ tuff)
    VULCANIAN("vulcanian", new String[] {}, VolcanoEruptCauseType.MAGMATIC, 0.3, 5, 1),

    // BUILD Lava dome first.
    // andesite lava dome -> explode it. + lava overflow + pyroclastic flows +
    // volcanic bombs (+
    // tuff)
    // less volume but range is plinian or vulcanian
    // tuff pyroclastic flows
    PELEAN("pelean", new String[] { "pelèan" }, VolcanoEruptCauseType.MAGMATIC, 1, 5, 2),

    // no lava overflow + caldera + top collapse + (granite) rhyolite volcano bombs
    // (A LOT)
    // **RAINING TUFF**
    PLINIAN("plinian", new String[] { "vesuvian" }, VolcanoEruptCauseType.MAGMATIC, 0, 0, 2),
    ;

    String rawType;
    String[] aliases;
    VolcanoEruptCauseType causeType;

    public double lavaMultiplier;
    public double bombMultiplier;
    public double ashMultiplier;

    public boolean canFormCaldera = false;

    VolcanoEruptStyle(
            String rawType,
            String[] aliases,
            VolcanoEruptCauseType causeType,
            double lavaMultiplier,
            double bombMultiplier,
            double ashMultiplier) {
        this.rawType = rawType;
        this.aliases = aliases;

        this.causeType = causeType;

        this.lavaMultiplier = lavaMultiplier;
        this.bombMultiplier = bombMultiplier;
        this.ashMultiplier = ashMultiplier;
    }

    VolcanoEruptStyle(
            String rawType,
            String[] aliases,
            VolcanoEruptCauseType causeType,
            double lavaMultiplier,
            double bombMultiplier,
            double ashMultiplier,
            boolean canFormCaldera
    ) {
        this(rawType, aliases, causeType, lavaMultiplier, bombMultiplier, ashMultiplier);
        this.canFormCaldera = canFormCaldera;
    }

    public double getPyroclasticFlowMultiplier() {
        if (this == VolcanoEruptStyle.VULCANIAN || this == VolcanoEruptStyle.PLINIAN) return 0.2;
        else if (this == VolcanoEruptStyle.PELEAN) return 0.6;

        return 0;
    }

    public static VolcanoEruptStyle getVolcanoEruptStyle(String name) {
        for (VolcanoEruptStyle type : VolcanoEruptStyle.values()) {
            List<String> names = new ArrayList<String>(Arrays.asList(type.aliases));
            names.add(type.rawType);

            if (names.contains(name.toLowerCase())) {
                return type;
            }
        }

        return null;
    }

    public String toString() {
        return this.rawType;
    }

    public boolean isHydroVolcanic() {
        return this.causeType.isHydroVolcanic();
    }

    public boolean flowsLava() {
        return this.lavaMultiplier > 0 && this.causeType != VolcanoEruptCauseType.PHREATIC;
    }

    public boolean isExplosive() {
        return this.bombMultiplier > 0;
    }
}
