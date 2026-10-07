package me.alex4386.typhon.simulator.scenario;

import java.util.List;
import me.alex4386.typhon.engine.config.WorldDefinition;
import me.alex4386.typhon.engine.world.Edifice;
import me.alex4386.typhon.engine.world.WorldSpec;

/**
 * The real-world setting of a preset: geology, initial conditions, the domain and where a
 * real DEM for it comes from.
 *
 * @param spec world-model grid and geology (metres per column, datum, basement cake, sea level, ...)
 * @param edifices volcano edifices applied when columns are imported
 * @param geotherm initial temperature field (for the subsurface solvers)
 * @param aquifer initial water table (for the subsurface solvers)
 * @param halfExtentColumns domain half-width in columns (the domain is {@code 2·half·L} metres across)
 * @param dem where to get a real DEM for the domain
 */
public record RealSetting(WorldSpec spec, List<Edifice> edifices, WorldDefinition.Geotherm geotherm,
        WorldDefinition.Aquifer aquifer, int halfExtentColumns, DemSource dem) {
    public RealSetting {
        edifices = List.copyOf(edifices);
    }

    public double metersPerColumn() {
        return spec.metersPerColumn();
    }

    /** Domain width (m). */
    public double domainMeters() {
        return 2.0 * halfExtentColumns * spec.metersPerColumn();
    }

    /**
     * A real DEM for the preset.
     *
     * @param lat latitude of the domain centre (the main vent), degrees north
     * @param lon longitude of the domain centre, degrees east
     * @param srtmTile SRTM 1-arc-second tile name ({@code .hgt}, e.g. {@code N19W156})
     * @param copernicusTile Copernicus GLO-30 COG file name
     * @param notes caveats (topography epoch, missing bathymetry, ...)
     */
    public record DemSource(double lat, double lon, String srtmTile, String copernicusTile, String notes) {
        /** Copernicus GLO-30 download URL (public AWS open-data bucket, no account needed). */
        public String copernicusUrl() {
            String base = copernicusTile.replace(".tif", "");
            return "https://copernicus-dem-30m.s3.amazonaws.com/" + base + "/" + copernicusTile;
        }

        /** Copernicus GLO-30 COG file name for the 1°×1° tile containing (lat, lon). */
        public static String copernicusTileFor(double lat, double lon) {
            int la = (int) Math.floor(lat);
            int lo = (int) Math.floor(lon);
            return String.format(java.util.Locale.ROOT, "Copernicus_DSM_COG_10_%s%02d_00_%s%03d_00_DEM.tif",
                    la >= 0 ? "N" : "S", Math.abs(la), lo >= 0 ? "E" : "W", Math.abs(lo));
        }

        /** SRTM tile name for the 1°×1° tile containing (lat, lon), e.g. {@code N19W156}. */
        public static String srtmTileFor(double lat, double lon) {
            int la = (int) Math.floor(lat);
            int lo = (int) Math.floor(lon);
            return String.format(java.util.Locale.ROOT, "%s%02d%s%03d", la >= 0 ? "N" : "S", Math.abs(la),
                    lo >= 0 ? "E" : "W", Math.abs(lo));
        }

        /** A synthetic setting with no real place behind it (no DEM to download). */
        public static DemSource synthetic(String notes) {
            return new DemSource(Double.NaN, Double.NaN, "", "", notes);
        }

        /** Whether a real DEM exists for the setting. */
        public boolean available() {
            return Double.isFinite(lat) && Double.isFinite(lon);
        }

        public static DemSource at(double lat, double lon, String notes) {
            return new DemSource(lat, lon, srtmTileFor(lat, lon), copernicusTileFor(lat, lon), notes);
        }
    }
}
