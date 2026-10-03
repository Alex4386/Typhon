package me.alex4386.typhon.engine.massflow;

/**
 * Whether an explosive eruption column rises buoyantly or collapses into pyroclastic density
 * currents, from a steady top-hat jet/plume model (Woods 1988; cf. Wilson, Sparks &amp; Walker 1980).
 *
 * <p>Integrated upward from the vent, with air entrainment coefficient α:
 *
 * <pre>
 *   dQ/dz = 2π r α ρ_a u                      (mass flux Q = π r² ρ u)
 *   d(Qu)/dz = π r² g (ρ_a − ρ)               (momentum flux)
 *   d(Q c_p T)/dz = c_pa T_a dQ/dz − Q g      (enthalpy flux)
 * </pre>
 *
 * The mixture density follows from solids (density ρ_s) plus magmatic gas and entrained air as ideal
 * gases at ambient pressure. Near the vent the mixture is much denser than air; it must entrain and
 * heat enough air to become buoyant before its momentum runs out. If the jet stalls while still
 * denser than air, the column collapses. Collapse is favoured by high mass eruption rates (wide
 * vents entrain relatively less), low exit velocities and low gas contents.
 *
 * <p>Atmosphere: ICAO standard troposphere (lapse 6.5 K/km to 11 km, isothermal above).
 */
public final class ColumnCollapse {
    public static final double ENTRAINMENT = 0.09;
    public static final double SOLID_DENSITY = 2500;
    private static final double G = 9.81;
    private static final double R_AIR = 287.0;
    private static final double R_STEAM = 461.5;
    private static final double CP_SOLID = 1100;
    private static final double CP_STEAM = 1850;
    private static final double CP_AIR = 1000;
    private static final double T0 = 288.15;
    private static final double P0 = 101325;
    private static final double LAPSE = 0.0065;
    private static final double TROPOPAUSE = 11000;
    private static final double MAX_HEIGHT = 50000;

    /**
     * @param collapses the jet stalled while denser than the atmosphere
     * @param heightM height of collapse, or of the transition to a buoyant plume
     * @param ventRadiusM vent radius implied by the mass flux, exit velocity and density
     * @param ventDensity bulk density of the erupting mixture at the vent (kg/m³)
     */
    public record Result(boolean collapses, double heightM, double ventRadiusM, double ventDensity) {}

    private ColumnCollapse() {}

    /**
     * @param massEruptionRate total mass flux (kg/s)
     * @param exitVelocity exit velocity at the vent (m/s)
     * @param gasMassFraction exsolved magmatic gas (H₂O) mass fraction, e.g. 0.01–0.06
     * @param temperatureC eruption temperature (°C)
     */
    public static Result analyze(double massEruptionRate, double exitVelocity, double gasMassFraction,
            double temperatureC) {
        if (!(massEruptionRate > 0) || !(exitVelocity > 0)) throw new IllegalArgumentException("rates must be positive");
        if (!(gasMassFraction > 0 && gasMassFraction < 1)) throw new IllegalArgumentException("gas fraction must be in (0, 1)");
        Column col = new Column(massEruptionRate, gasMassFraction);
        double t = temperatureC + 273.15;
        double rho0 = col.density(massEruptionRate, t, 0);
        double r0 = Math.sqrt(massEruptionRate / (Math.PI * rho0 * exitVelocity));

        double q = massEruptionRate;
        double m = q * exitVelocity;
        double h = q * col.cp(q) * t;
        double z = 0;
        while (z < MAX_HEIGHT) {
            double u = m / q;
            double temp = h / (q * col.cp(q));
            double rho = col.density(q, temp, z);
            double rhoA = airDensity(z);
            if (rho < rhoA) return new Result(false, z, r0, rho0);
            if (u < 1e-3 * exitVelocity || u < 0.1) return new Result(true, z, r0, rho0);

            double r = Math.sqrt(q / (Math.PI * rho * u));
            double dz = Math.max(0.05, Math.min(25, 0.02 * r));
            // RK2 (midpoint) on (Q, M, H)
            double[] k1 = col.derivatives(q, m, h, z);
            double qm = q + 0.5 * dz * k1[0];
            double mm = m + 0.5 * dz * k1[1];
            double hm = h + 0.5 * dz * k1[2];
            if (mm <= 0) return new Result(true, z, r0, rho0);
            double[] k2 = col.derivatives(qm, mm, hm, z + 0.5 * dz);
            q += dz * k2[0];
            m += dz * k2[1];
            h += dz * k2[2];
            z += dz;
            if (m <= 0) return new Result(true, z, r0, rho0);
        }
        return new Result(false, z, r0, rho0);
    }

    static double airTemperature(double z) {
        return T0 - LAPSE * Math.min(z, TROPOPAUSE);
    }

    static double airPressure(double z) {
        double exponent = G / (R_AIR * LAPSE);
        if (z <= TROPOPAUSE) return P0 * StrictMath.pow(airTemperature(z) / T0, exponent);
        double pTrop = P0 * StrictMath.pow(airTemperature(TROPOPAUSE) / T0, exponent);
        return pTrop * StrictMath.exp(-G * (z - TROPOPAUSE) / (R_AIR * airTemperature(TROPOPAUSE)));
    }

    static double airDensity(double z) {
        return airPressure(z) / (R_AIR * airTemperature(z));
    }

    /** Fixed vent fluxes of solids and magmatic gas; entrained air is {@code Q − Q₀}. */
    private record Column(double q0, double gasFraction) {
        double solids() {
            return (1 - gasFraction) * q0;
        }

        double gas() {
            return gasFraction * q0;
        }

        double cp(double q) {
            double air = Math.max(0, q - q0);
            return (solids() * CP_SOLID + gas() * CP_STEAM + air * CP_AIR) / q;
        }

        double density(double q, double temperatureK, double z) {
            double air = Math.max(0, q - q0);
            double gasMass = gas() + air;
            double rGas = (gas() * R_STEAM + air * R_AIR) / gasMass;
            double specificVolume = (solids() / q) / SOLID_DENSITY + (gasMass / q) * rGas * temperatureK / airPressure(z);
            return 1 / specificVolume;
        }

        double[] derivatives(double q, double m, double h, double z) {
            double u = m / q;
            double temp = h / (q * cp(q));
            double rho = density(q, temp, z);
            double rhoA = airDensity(z);
            double area = q / (rho * u); // π r²
            double r = Math.sqrt(area / Math.PI);
            double dq = 2 * Math.PI * r * ENTRAINMENT * rhoA * u;
            double dm = area * G * (rhoA - rho);
            double dh = CP_AIR * airTemperature(z) * dq - q * G;
            return new double[] {dq, dm, dh};
        }
    }
}
