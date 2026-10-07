package me.alex4386.typhon.engine.subsurface;

/**
 * Liquid–vapour saturation of pure water: the boiling point at a pressure, the atmospheric pressure at an
 * elevation, and the boiling point at a depth below the water table.
 *
 * <p>References: IAPWS-IF97 (Wagner et al. 2000, J. Eng. Gas Turbines Power 122:150–182), region 4
 * backward equation (Eq. 31) for {@code T_sat(p)}; ICAO standard atmosphere (ISO 2533:1975) for the
 * barometric pressure below 11 km. The hydrostatic column uses 1000 kg/m³: hot water is lighter
 * (~920 kg/m³ at 200 °C), so at 1 km the boiling point is ~1 % (3 K) above Haas's (1971) curve, which
 * integrates the density of boiling water.
 */
public final class WaterSaturation {
    /** Critical point of water (IAPWS-IF97): above it there is no liquid–vapour transition. */
    public static final double CRITICAL_TEMPERATURE_C = 373.946;
    public static final double CRITICAL_PRESSURE_PA = 22.064e6;
    /** Triple-point pressure (Pa): the lower validity bound of the saturation line. */
    static final double TRIPLE_PRESSURE_PA = 611.213;
    static final double WATER_DENSITY = 1000;
    static final double GRAVITY = 9.81;

    private static final double N1 = 0.11670521452767e4;
    private static final double N2 = -0.72421316703206e6;
    private static final double N3 = -0.17073846940092e2;
    private static final double N4 = 0.12020824702470e5;
    private static final double N5 = -0.32325550322333e7;
    private static final double N6 = 0.14915108613530e2;
    private static final double N7 = -0.48232657361591e4;
    private static final double N8 = 0.40511340542057e6;
    private static final double N9 = -0.23855557567849;
    private static final double N10 = 0.65017534844798e3;

    private WaterSaturation() {}

    /** Saturation (boiling) temperature (°C) at pressure {@code p} (Pa); the critical temperature above it. */
    public static double saturationTemperatureC(double pressurePa) {
        if (pressurePa >= CRITICAL_PRESSURE_PA) return CRITICAL_TEMPERATURE_C;
        double p = Math.max(TRIPLE_PRESSURE_PA, pressurePa) / 1e6; // MPa
        double beta = Math.sqrt(Math.sqrt(p));
        double b2 = beta * beta;
        double e = b2 + N3 * beta + N6;
        double f = N1 * b2 + N4 * beta + N7;
        double g = N2 * b2 + N5 * beta + N8;
        double d = 2 * g / (-f - Math.sqrt(f * f - 4 * e * g));
        double s = N10 + d;
        return (s - Math.sqrt(s * s - 4 * (N9 + N10 * d))) / 2 - 273.15;
    }

    /** Standard-atmosphere pressure (Pa) at {@code elevationM} above sea level (sea-level value below 0). */
    public static double atmosphericPressurePa(double elevationM) {
        double h = Math.min(11_000, Math.max(-500, elevationM));
        return 101_325 * Math.pow(1 - 2.25577e-5 * h, 5.25588);
    }

    /**
     * Boiling point (°C) {@code depthM} below a water table at elevation {@code waterTableZ} (m): the
     * hydrostatic pressure of the water above plus the atmosphere at the table (a submarine table is the
     * sea surface).
     */
    public static double boilingPointC(double depthM, double waterTableZ) {
        return saturationTemperatureC(atmosphericPressurePa(waterTableZ) + WATER_DENSITY * GRAVITY * Math.max(0, depthM));
    }
}
