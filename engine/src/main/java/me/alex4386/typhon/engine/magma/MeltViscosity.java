package me.alex4386.typhon.engine.magma;

/**
 * Silicate melt and magma viscosity estimates.
 *
 * <p>Melt viscosity uses a Vogel–Fulcher–Tammann form, {@code log10 η = A + B / (T − C)}, with the
 * Giordano et al. (2008) high-temperature limit {@code A = −4.55} and {@code B}, {@code C}
 * interpolated between basalt and rhyolite end members and lowered by dissolved water. It is a
 * deliberately simplified fit that reproduces the usual orders of magnitude:
 *
 * <ul>
 *   <li>dry basalt, 1200 °C: ~10² Pa·s; hydrous basalt (2 wt% H₂O): ~10¹ Pa·s
 *   <li>dry rhyolite, 800 °C: ~10¹¹ Pa·s; hydrous rhyolite (4 wt%): ~10⁷ Pa·s
 * </ul>
 *
 * <p>Crystals raise the bulk viscosity via the Einstein–Roscoe relation
 * {@code η = η_melt (1 − φ/φm)^−2.5} with maximum packing {@code φm = 0.6}.
 */
public final class MeltViscosity {
    public static final double A = -4.55;
    public static final double MAX_PACKING = 0.6;
    public static final double MAX_LOG10 = 14.0;
    public static final double MIN_LOG10 = -1.0;

    private static final double SILICA_BASALT = 48.0;
    private static final double SILICA_RHYOLITE = 75.0;
    private static final double B_BASALT = 5718.0;
    private static final double B_RHYOLITE = 12020.0;
    private static final double C_BASALT = 600.0;
    private static final double C_RHYOLITE = 300.0;

    private MeltViscosity() {}

    /** log10 of the crystal-free melt viscosity (Pa·s). */
    public static double meltLog10(double silicaWt, double waterWt, double temperatureC) {
        double s = clamp((silicaWt - SILICA_BASALT) / (SILICA_RHYOLITE - SILICA_BASALT), 0, 1);
        double lnWater = Math.log1p(Math.max(0, waterWt));
        double b = (B_BASALT + (B_RHYOLITE - B_BASALT) * s) * (1 - 0.12 * lnWater);
        double c = (C_BASALT + (C_RHYOLITE - C_BASALT) * s) - 42.0 * lnWater;
        double denominator = temperatureC + 273.15 - c;
        if (denominator <= 1) return MAX_LOG10;
        return clamp(A + b / denominator, MIN_LOG10, MAX_LOG10);
    }

    /** log10 of the bulk magma viscosity (Pa·s) including the crystal load. */
    public static double log10(double silicaWt, double waterWt, double temperatureC, double crystalFraction) {
        double ratio = clamp(crystalFraction, 0, 1) / MAX_PACKING;
        if (ratio >= 0.999) return MAX_LOG10;
        double crystalTerm = -2.5 * Math.log10(1 - ratio);
        return clamp(meltLog10(silicaWt, waterWt, temperatureC) + crystalTerm, MIN_LOG10, MAX_LOG10);
    }

    /** Bulk magma viscosity in Pa·s. */
    public static double of(double silicaWt, double waterWt, double temperatureC, double crystalFraction) {
        return Math.pow(10, log10(silicaWt, waterWt, temperatureC, crystalFraction));
    }

    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
