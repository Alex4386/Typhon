package me.alex4386.typhon.simulator.scenario;

import me.alex4386.typhon.engine.deformation.GeodeticStation;
import me.alex4386.typhon.engine.volcano.VentSite;

/**
 * Virtual GNSS/tilt networks for the presets. Coordinates follow the deformation model's convention:
 * east = +x, north = −z (metres).
 */
final class Stations {
    private Stations() {}

    /** A station {@code eastM}, {@code northM} metres from a vent. */
    static GeodeticStation at(String name, VentSite vent, double eastM, double northM) {
        return new GeodeticStation(name, vent.position().x() + eastM, vent.position().z() - northM);
    }
}
