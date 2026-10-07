package me.alex4386.typhon.simulator.scenario;

import java.util.List;
import me.alex4386.typhon.engine.deformation.GeodeticStation;
import me.alex4386.typhon.engine.volcano.VentSite;

/**
 * Virtual GNSS/tilt networks for the presets. Coordinates follow the deformation model's convention:
 * east = +x, north = −z (world columns).
 */
final class Stations {
    private Stations() {}

    /** A station {@code eastM}, {@code northM} metres from a vent, on {@code metersPerColumn} columns. */
    static GeodeticStation at(String name, VentSite vent, double eastM, double northM, double metersPerColumn) {
        return new GeodeticStation(name, vent.block(metersPerColumn).x() + (int) Math.round(eastM / metersPerColumn),
                vent.block(metersPerColumn).z() - (int) Math.round(northM / metersPerColumn));
    }

    /**
     * A compact network: a station on the crater rim to the north, one on the east flank and one at the
     * southern base ({@code flank} and {@code base} in columns from the vent).
     */
    static List<GeodeticStation> network(String prefix, VentSite vent, int flank, int base) {
        int rim = vent.craterRadius() + 2;
        return List.of(
                at(prefix + "-RIM-N", vent, 0, rim, 1),
                at(prefix + "-FLK-E", vent, flank, 0, 1),
                at(prefix + "-BAS-S", vent, 0, -base, 1));
    }
}
