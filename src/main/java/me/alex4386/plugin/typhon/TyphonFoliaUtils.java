package me.alex4386.plugin.typhon;

public class TyphonFoliaUtils {
    /**
     * Whether the server running is a Folia server.
     * If then, enable all of multi-threading functions.
     */
    public static final boolean isFolia = TyphonFoliaUtils._isFolia();

    private static boolean _isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
