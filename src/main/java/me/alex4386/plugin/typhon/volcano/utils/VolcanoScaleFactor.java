package me.alex4386.plugin.typhon.volcano.utils;

import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;

public class VolcanoScaleFactor {
    public Volcano volcano;

    VolcanoScaleFactor(Volcano volcano) {
        this.volcano = volcano;
    }

    public void getScaleFactor() {
        int mainVolcanoY = volcano.manager.getSummitBlock().getY();
        double mainVentFlowDistance = volcano.mainVent.longestFlowLength;
    }
}

class VolcanoVentScaleFactor {
    public VolcanoVent vent;

    VolcanoVentScaleFactor(VolcanoVent vent) {
        this.vent = vent;
    }

    public void getScaleFactor() {
        int mainVolcanoY = vent.getSummitBlock().getY();
        double mainVentFlowDistance = vent.longestFlowLength;
    }
}
