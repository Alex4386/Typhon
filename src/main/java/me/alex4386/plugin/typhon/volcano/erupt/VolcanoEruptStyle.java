package me.alex4386.plugin.typhon.volcano.erupt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public enum VolcanoEruptStyle {
    // lava lake + lava fountain (minimal), lava flow is everything of this.
    HAWAIIAN("hawaiian", new String[]{ "effusive" }, VolcanoEruptCauseType.MAGMATIC),

    // Lava fountain (with volcanic bombs) + can have lava flows, but minimal. Usually goes into bursting out into air
    // rocks: basaltic -> scoria (basaltic + andesite), keep in mind that tuff is lessly used in here
    STROMBOLIAN("strombolian", new String[]{"stromboli"}, VolcanoEruptCauseType.MAGMATIC),

    // stromboli but, range longer + with ash
    // rocks: andestic volcanic bombs (+ tuff)
    // starts with lavadome growth
    VULCANIAN("vulcanian", new String[]{}, VolcanoEruptCauseType.MAGMATIC),

    // BUILD Lava dome first.
    // andesite lava dome -> explode it. + lava overflow + pyroclastic flows + volcanic bombs (+ tuff)
    // less volume but range is plinian or vulcanian
    // tuff pyroclastic flows
    PELEAN("pelean", new String[]{"pelèan"}, VolcanoEruptCauseType.MAGMATIC),

    // no lava overflow + caldera + top collapse + (granite) rhyolite volcano bombs (A LOT)
    // **RAINING TUFF**
    PLINIAN("plinian", new String[]{"vesuvian"}, VolcanoEruptCauseType.MAGMATIC, false),

    // slight lava flow (-> basalt + andesite) + volcano bombs (TUFF)
    // water vapor + ash jet
    SURTSEYAN("surtseyan", new String[]{"island"}, VolcanoEruptCauseType.PHREATOMAGMATIC),

    // lava flow (-> basalt + andesite + TUFF)
    // water vapor
    SUBMARINE("submarine", new String[]{}, VolcanoEruptCauseType.PHREATOMAGMATIC),

    // NO LAVA + generate maar (basically no cone generation and explode in ground) + volcano bombs (mostly tuff, and really little)
    // water vapor (toxic), no smoke
    PHREATIC("phreatic", new String[]{"hydrothermal"}, VolcanoEruptCauseType.PHREATIC),
    ;

    String rawType;
    String[] aliases;
    VolcanoEruptCauseType causeType;
    boolean flowLava;

    VolcanoEruptStyle(String rawType, String[] aliases, VolcanoEruptCauseType causeType) {
        this(rawType, aliases, causeType, true);

        if (causeType == VolcanoEruptCauseType.PHREATIC) {
            this.flowLava = false;
        }
    }

    VolcanoEruptStyle(String rawType, String[] aliases, VolcanoEruptCauseType causeType, boolean flowLava) {
        this.rawType = rawType;
        this.aliases = aliases;

        this.causeType = causeType;
        this.flowLava = flowLava;
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
        switch (this.causeType) {
            case PHREATOMAGMATIC:
            case PHREATIC:
                return true;
        }

        return false;
    }


}
