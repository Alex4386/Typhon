package me.alex4386.typhon.engine.tephra;


/**
 * Ballistic physics of volcanic bombs.
 *
 * <p>A spherical bomb of diameter d and density ρ_b in air of density ρ_a experiences
 *
 * <pre>
 *   a = g − k·|v − w|·(v − w),   k = 3·ρ_a·C_d / (4·ρ_b·d)
 * </pre>
 *
 * where w is the (horizontal) wind velocity. Larger bombs have smaller k and therefore travel
 * farther at the same exit speed. The air thins with height (International Standard Atmosphere,
 * ICAO 1993): k scales with ρ(z)/ρ₀ along the trajectory, which matters for bombs climbing kilometres. Trajectories are integrated with classic RK4. All transcendental
 * functions use {@link StrictMath} so results are bit-identical across JIT states and platforms.
 */
public final class Ballistics {
    /** Specific gas constant of water vapour, J/(kg·K). */
    public static final double R_WATER = 461.5;
    /** Atmospheric pressure, MPa. */
    public static final double ATMOSPHERE_MPA = 0.101325;

    private Ballistics() {}

    /** Sea-level standard temperature (K), lapse rate (K/m), tropopause (m) and specific gas constant of air. */
    static final double ISA_T0 = 288.15;
    static final double ISA_LAPSE = 0.0065;
    static final double ISA_TROPOPAUSE = 11_000;
    static final double R_AIR = 287.05;

    /**
     * Air density at elevation {@code z} (m a.s.l.) relative to sea level, International Standard Atmosphere:
     * {@code (T/T₀)^(g/(R L) − 1)} in the troposphere, exponential (isothermal) above the tropopause.
     */
    public static double airDensityRatio(double z, double gravity) {
        double exponent = gravity / (R_AIR * ISA_LAPSE);
        double zt = Math.min(Math.max(z, -1000), ISA_TROPOPAUSE);
        double ratio = StrictMath.pow(1 - ISA_LAPSE * zt / ISA_T0, exponent - 1);
        if (z <= ISA_TROPOPAUSE) return ratio;
        double tTrop = ISA_T0 - ISA_LAPSE * ISA_TROPOPAUSE;
        return ratio * StrictMath.exp(-gravity * (z - ISA_TROPOPAUSE) / (R_AIR * tTrop));
    }

    /** Drag factor k (1/m) for a sphere in air of density {@code airDensity}. */
    public static double dragFactor(double diameter, double bombDensity, double dragCoefficient, double airDensity) {
        return 0.75 * airDensity * dragCoefficient / (bombDensity * diameter);
    }

    public static double sphereMass(double diameter, double density) {
        return density * Math.PI / 6.0 * diameter * diameter * diameter;
    }

    /**
     * Gas-thrust exit speed from isothermal expansion of the exsolved gas (Wilson 1980 style):
     *
     * <pre>
     *   u² = 2 · n · R_w · T · ln(1 + ΔP / P_atm)
     * </pre>
     *
     * with gas mass fraction n, temperature T (K) and overpressure ΔP. Gives ~150 m/s for Strombolian
     * (n = 0.01, ΔP = 0.5 MPa) and ~370 m/s for Vulcanian (n = 0.03, ΔP = 5 MPa) conditions.
     */
    public static double gasThrustExitSpeed(double gasFraction, double temperatureC, double overpressureMPa) {
        double temperatureK = temperatureC + 273.15;
        double expansion = StrictMath.log(1 + Math.max(0, overpressureMPa) / ATMOSPHERE_MPA);
        double u2 = 2 * gasFraction * R_WATER * temperatureK * expansion;
        return u2 > 0 ? Math.sqrt(u2) : 0;
    }

    /** Impact crater radius (m): r = k·E^(1/3). */
    public static double craterRadius(double energyJoules, double coefficient) {
        return energyJoules > 0 ? coefficient * StrictMath.cbrt(energyJoules) : 0;
    }

    /**
     * Advances {@code s = {x, y, z, vx, vy, vz}} by {@code dt} seconds with RK4.
     *
     * @param k drag factor at sea level (1/m); scaled by {@link #airDensityRatio} at the bomb's elevation
     * @param wx wind x velocity (m/s)
     * @param wz wind z velocity (m/s)
     */
    public static void rk4(double[] s, double dt, double k, double gravity, double wx, double wz) {
        double x = s[0], y = s[1], z = s[2], vx = s[3], vy = s[4], vz = s[5];

        double k1 = k * airDensityRatio(y, gravity);
        double[] a1 = accel(vx, vy, vz, k1, gravity, wx, wz);
        double vx2 = vx + 0.5 * dt * a1[0], vy2 = vy + 0.5 * dt * a1[1], vz2 = vz + 0.5 * dt * a1[2];
        double k2 = k * airDensityRatio(y + 0.5 * dt * vy, gravity);
        double[] a2 = accel(vx2, vy2, vz2, k2, gravity, wx, wz);
        double vx3 = vx + 0.5 * dt * a2[0], vy3 = vy + 0.5 * dt * a2[1], vz3 = vz + 0.5 * dt * a2[2];
        double k3 = k * airDensityRatio(y + 0.5 * dt * vy2, gravity);
        double[] a3 = accel(vx3, vy3, vz3, k3, gravity, wx, wz);
        double vx4 = vx + dt * a3[0], vy4 = vy + dt * a3[1], vz4 = vz + dt * a3[2];
        double k4 = k * airDensityRatio(y + dt * vy3, gravity);
        double[] a4 = accel(vx4, vy4, vz4, k4, gravity, wx, wz);

        s[0] = x + dt / 6.0 * (vx + 2 * vx2 + 2 * vx3 + vx4);
        s[1] = y + dt / 6.0 * (vy + 2 * vy2 + 2 * vy3 + vy4);
        s[2] = z + dt / 6.0 * (vz + 2 * vz2 + 2 * vz3 + vz4);
        s[3] = vx + dt / 6.0 * (a1[0] + 2 * a2[0] + 2 * a3[0] + a4[0]);
        s[4] = vy + dt / 6.0 * (a1[1] + 2 * a2[1] + 2 * a3[1] + a4[1]);
        s[5] = vz + dt / 6.0 * (a1[2] + 2 * a2[2] + 2 * a3[2] + a4[2]);
    }

    private static double[] accel(double vx, double vy, double vz, double k, double gravity, double wx, double wz) {
        double rx = vx - wx, ry = vy, rz = vz - wz;
        double speed = Math.sqrt(rx * rx + ry * ry + rz * rz);
        return new double[] {-k * speed * rx, -gravity - k * speed * ry, -k * speed * rz};
    }
}
