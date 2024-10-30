package me.alex4386.plugin.typhon;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;

public class TyphonMultithreading {
    public static final boolean isPaperMultithread = TyphonMultithreading._isPaperMultithread();
    private static Boolean _isFolia = null;

    private static boolean _isPaperMultithread() {
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static boolean isFolia() {
        if (_isFolia != null) {
            return _isFolia;
        }

        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            _isFolia = true;
        } catch (ClassNotFoundException e) {
            _isFolia = false;
        }

        return _isFolia;
    }
}
