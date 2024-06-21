package me.alex4386.plugin.typhon;

public class TyphonMultithreading {
    public static final boolean isPaperMultithread = TyphonMultithreading._isPaperMultithread();

    private static boolean _isPaperMultithread() {
        try {
            Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
            Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
