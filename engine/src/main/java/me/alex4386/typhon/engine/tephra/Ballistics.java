package me.alex4386.typhon.engine.tephra;

import me.alex4386.typhon.engine.world.BlockId;

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
 * farther at the same exit speed. Trajectories are integrated with classic RK4. All transcendental
 * functions use {@link StrictMath} so results are bit-identical across JIT states and platforms.
 */
public final class Ballistics {
    /** Specific gas constant of water vapour, J/(kg·K). */
    public static final double R_WATER = 461.5;
    /** Atmospheric pressure, MPa. */
    public static final double ATMOSPHERE_MPA = 0.101325;

    private Ballistics() {}

    /** Drag factor k (1/m) for a sphere. */
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

    /** Impact crater radius in blocks: r = k·E^(1/3). */
    public static double craterRadius(double energyJoules, double coefficient) {
        return energyJoules > 0 ? coefficient * StrictMath.cbrt(energyJoules) : 0;
    }

    /** Rock a hot bomb cools into, by SiO₂ wt%. */
    public static BlockId cooledBombRock(double silicaWt) {
        if (silicaWt < 52) return BlockId.minecraft("basalt");
        if (silicaWt < 57) return BlockId.minecraft("blackstone");
        if (silicaWt < 65) return BlockId.minecraft("andesite");
        return BlockId.minecraft("tuff");
    }

    /**
     * Advances {@code s = {x, y, z, vx, vy, vz}} by {@code dt} seconds with RK4.
     *
     * @param k drag factor (1/m)
     * @param wx wind x velocity (m/s)
     * @param wz wind z velocity (m/s)
     */
    public static void rk4(double[] s, double dt, double k, double gravity, double wx, double wz) {
        double x = s[0], y = s[1], z = s[2], vx = s[3], vy = s[4], vz = s[5];

        double[] a1 = accel(vx, vy, vz, k, gravity, wx, wz);
        double vx2 = vx + 0.5 * dt * a1[0], vy2 = vy + 0.5 * dt * a1[1], vz2 = vz + 0.5 * dt * a1[2];
        double[] a2 = accel(vx2, vy2, vz2, k, gravity, wx, wz);
        double vx3 = vx + 0.5 * dt * a2[0], vy3 = vy + 0.5 * dt * a2[1], vz3 = vz + 0.5 * dt * a2[2];
        double[] a3 = accel(vx3, vy3, vz3, k, gravity, wx, wz);
        double vx4 = vx + dt * a3[0], vy4 = vy + dt * a3[1], vz4 = vz + dt * a3[2];
        double[] a4 = accel(vx4, vy4, vz4, k, gravity, wx, wz);

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
