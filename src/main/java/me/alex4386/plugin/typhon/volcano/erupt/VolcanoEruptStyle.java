package me.alex4386.plugin.typhon.volcano.erupt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import me.alex4386.plugin.typhon.TyphonUtils;

public enum VolcanoEruptStyle {
    // lava lake + lava fountain (minimal), lava flow is everything of this.
    HAWAIIAN("hawaiian", new String[] { "effusive" }, VolcanoEruptCauseType.MAGMATIC, 5, 0.2, 0.4),

    // Lava fountain (with volcanic bombs) + can have lava flows, but minimal.
    // Usually goes into
    // bursting out into air
    // rocks: basaltic -> scoria (basaltic + andesite), keep in mind that tuff is
    // lessly used in
    // here
    STROMBOLIAN(
            "strombolian", new String[] { "stromboli" }, VolcanoEruptCauseType.MAGMATIC, 0, 1, 1),

    // stromboli but, range longer + with ash
    // rocks: andestic volcanic bombs (+ tuff)
    VULCANIAN("vulcanian", new String[] {}, VolcanoEruptCauseType.MAGMATIC, 3, 2, 2),

    // BUILD Lava dome first.
    // andesite lava dome -> explode it. + lava overflow + pyroclastic flows +
    // volcanic bombs (+
    // tuff)
    // less volume but range is plinian or vulcanian
    // tuff pyroclastic flows
    PELEAN("pelean", new String[] { "pelèan" }, VolcanoEruptCauseType.MAGMATIC, 5, 4, 4),

    // no lava overflow + caldera + top collapse + (granite) rhyolite volcano bombs
    // (A LOT)
    // **RAINING TUFF**
    PLINIAN("plinian", new String[] { "vesuvian" }, VolcanoEruptCauseType.MAGMATIC, 0, 0, 5),

    // **SPECIAL**
    // This is a special type of eruption for lava dome eruptions.
    LAVA_DOME("lava_dome", new String[] { "dome" }, VolcanoEruptCauseType.MAGMATIC, 1, 0, 0),
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
        if (this.ashMultiplier > 1) {
            return this.ashMultiplier - 1;
        }

        return 0;
    }

    public static VolcanoEruptStyle getVolcanoEruptStyle(String name) {
        for (VolcanoEruptStyle type : VolcanoEruptStyle.values()) {
            List<String> names = new ArrayList<String>(Arrays.asList(type.aliases));
            names.add(type.rawType);

            if (names.contains(TyphonUtils.toLowerCaseDumbEdition(name))) {
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
