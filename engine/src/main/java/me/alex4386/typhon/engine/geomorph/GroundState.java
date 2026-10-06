package me.alex4386.typhon.engine.geomorph;

import me.alex4386.typhon.engine.subsurface.Subsurface;

/**
 * What slope stability and alteration need to know about the ground beneath a column: heat, water
 * and steam. Backed by the {@link Subsurface} model in a full volcano; tests may stub it.
 */
public interface GroundState {
    /** Ground temperature (°C) {@code depthM} below the surface. */
    double temperatureC(int x, int z, double depthM);

    /** Depth of the water table below the surface (m); ≤ 0 when the ground is saturated to the top. */
    double waterTableDepthM(int x, int z);

    /** Water held in the unsaturated zone (m of water); infiltrated rain wets the top {@code vadose/n}. */
    double vadoseM(int x, int z);

    /** Standing or running surface water (m). */
    double surfaceWaterDepthM(int x, int z);

    /** Steam volume fraction of the pore space at depth. */
    double steamFraction(int x, int z, double depthM);

    /** Dry ground at 15 °C everywhere. */
    GroundState DRY = new GroundState() {
        @Override
        public double temperatureC(int x, int z, double depthM) {
            return 15;
        }

        @Override
        public double waterTableDepthM(int x, int z) {
            return Double.POSITIVE_INFINITY;
        }

        @Override
        public double vadoseM(int x, int z) {
            return 0;
        }

        @Override
        public double surfaceWaterDepthM(int x, int z) {
            return 0;
        }

        @Override
        public double steamFraction(int x, int z, double depthM) {
            return 0;
        }
    };

    static GroundState of(Subsurface subsurface) {
        if (subsurface == null) return DRY;
        return new GroundState() {
            @Override
            public double temperatureC(int x, int z, double depthM) {
                return subsurface.temperatureC(x, z, depthM);
            }

            @Override
            public double waterTableDepthM(int x, int z) {
                return subsurface.known(x, z) ? subsurface.waterTableDepthM(x, z) : Double.POSITIVE_INFINITY;
            }

            @Override
            public double vadoseM(int x, int z) {
                return subsurface.vadoseM(x, z);
            }

            @Override
            public double surfaceWaterDepthM(int x, int z) {
                return subsurface.surfaceWaterDepthM(x, z);
            }

            @Override
            public double steamFraction(int x, int z, double depthM) {
                return subsurface.steamFraction(x, z, depthM);
            }
        };
    }
}
