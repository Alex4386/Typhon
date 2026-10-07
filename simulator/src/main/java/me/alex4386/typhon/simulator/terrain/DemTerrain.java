package me.alex4386.typhon.simulator.terrain;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Builds a real-scale {@link ColumnGrid} from a DEM file: read, crop around a centre, fill voids,
 * resample onto {@code metersPerColumn} columns and flood below sea level.
 */
public final class DemTerrain {
    private DemTerrain() {}

    /**
     * @param centerLat latitude of the domain centre, or {@code NaN} for the DEM's middle
     * @param centerLon longitude of the domain centre, or {@code NaN} for the DEM's middle
     * @param seaLevelZ sea level (m) or {@code NaN} for no sea; also the fill for DEM voids at sea
     */
    public static ColumnGrid load(Path file, double metersPerColumn, int halfExtentColumns, double seaLevelZ,
            double centerLat, double centerLon) throws IOException {
        return load(file, metersPerColumn, halfExtentColumns, halfExtentColumns, seaLevelZ, centerLat, centerLon);
    }

    /**
     * {@link #load} keeping DEM data out to {@code reachColumns} from the centre (at least the core's half
     * width), so columns the engine materialises beyond the core later come from the real DEM; past the
     * data the nearest edge repeats.
     */
    public static ColumnGrid load(Path file, double metersPerColumn, int halfExtentColumns, int reachColumns,
            double seaLevelZ, double centerLat, double centerLon) throws IOException {
        DemImporter.Dem dem = DemImporter.read(file, DemImporter.ReadOptions.DEFAULT);
        boolean byLatLon = !Double.isNaN(centerLat) && !Double.isNaN(centerLon);
        double[] centre = byLatLon ? dem.locate(centerLat, centerLon) : dem.centre();
        if (centre[0] < 0 || centre[1] < 0 || centre[0] > dem.rows() - 1 || centre[1] > dem.cols() - 1) {
            throw new IOException(file + ": centre " + centerLat + ", " + centerLon + " lies outside the DEM");
        }
        double half = (Math.max(halfExtentColumns, reachColumns) + 16) * metersPerColumn;
        DemImporter.Dem crop = DemImporter.crop(dem, centre[0], centre[1], half);
        double voidFill = Double.isNaN(seaLevelZ) ? crop.min() : seaLevelZ;
        crop = DemImporter.fillNoData(crop, Double.isFinite(voidFill) ? voidFill : 0);
        double[] c = byLatLon ? crop.locate(centerLat, centerLon) : crop.centre();
        return DemImporter.toGrid(crop, metersPerColumn, c[0], c[1], halfExtentColumns, seaLevelZ,
                DemImporter.SurfacePainter.DEFAULT);
    }
}
