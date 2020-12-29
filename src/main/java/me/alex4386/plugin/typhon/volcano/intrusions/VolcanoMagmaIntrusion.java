package me.alex4386.plugin.typhon.volcano.intrusions;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.volcano.Volcano;

import java.util.*;

public class VolcanoMagmaIntrusion {
    public Volcano volcano;
    public boolean enabled = true;
    public Map<String, VolcanoDike> dikes = new HashMap<>();
    public Map<String, VolcanoMagmaChamber> magmaChambers = new HashMap<>();

    private int lavaIntrusionScheduleId = -1;

    // core methods
    public VolcanoMagmaIntrusion(Volcano volcano) {
        this.volcano = volcano;
    }

    // core scheduler methods
    public void registerTask() {
        if (lavaIntrusionScheduleId == -1) {
            lavaIntrusionScheduleId = TyphonPlugin.plugin.getServer().getScheduler().scheduleSyncRepeatingTask(TyphonPlugin.plugin, () -> {

            },0L,(long)volcano.updateRate);
        }
    }

    public void unregisterTask() {
        if (lavaIntrusionScheduleId != -1) {
            TyphonPlugin.plugin.getServer().getScheduler().cancelTask(lavaIntrusionScheduleId);
            lavaIntrusionScheduleId = -1;
        }
    }
}


