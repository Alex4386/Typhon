package me.alex4386.plugin.typhon.volcano.utils;

import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.crater.VolcanoCrater;

public class VolcanoScaleFactor {
    public Volcano volcano;

    VolcanoScaleFactor(Volcano volcano) {
        this.volcano = volcano;
    }

    public void getScaleFactor() {
        int mainVolcanoY = volcano.manager.getSummitBlock().getY();
        double mainCraterFlowDistance = volcano.mainCrater.longestFlowLength;
    }

}

class VolcanoCraterScaleFactor {
    public VolcanoCrater crater;

    VolcanoCraterScaleFactor(VolcanoCrater crater) {
        this.crater = crater;
    }

    public void getScaleFactor() {
        int mainVolcanoY = crater.getSummitBlock().getY();
        double mainCraterFlowDistance = crater.longestFlowLength;
    }

}
