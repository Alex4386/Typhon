package me.alex4386.typhon.engine.geomorph;

import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.math.BlockPos;

/** The magma chamber as seen by its roof: what piston (caldera / pit) collapse needs. */
public interface ChamberRoof {
    /** Chamber centre (block coordinates; only x/z are used). */
    /** Centre of the chamber under the roof (m). */
    Point3 center();

    /** Depth of the chamber top below the surface (m). */
    double depthM();

    double volumeM3();

    /** Chamber pressure above lithostatic (MPa); negative = underpressure. */
    double overpressureMPa();

    /** Effective compressibility of chamber + magma (1/MPa). */
    double compressibilityPerMPa();

    /** The roof sank into the chamber by {@code volumeM3}, recompressing it. */
    void subside(double volumeM3);

    static ChamberRoof of(MagmaChamber chamber) {
        return new ChamberRoof() {
            @Override
            public Point3 center() {
                return chamber.chamberCenter();
            }

            @Override
            public double depthM() {
                return chamber.physicalDepthM();
            }

            @Override
            public double volumeM3() {
                return chamber.volumeM3();
            }

            @Override
            public double overpressureMPa() {
                return chamber.overpressureMPa();
            }

            @Override
            public double compressibilityPerMPa() {
                return chamber.effectiveCompressibility();
            }

            @Override
            public void subside(double volumeM3) {
                chamber.compress(volumeM3);
            }
        };
    }
}
