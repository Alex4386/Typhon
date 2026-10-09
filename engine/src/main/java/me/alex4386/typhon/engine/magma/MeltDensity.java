package me.alex4386.typhon.engine.magma;

/**
 * Density of silicate melt and of bubbly magma, shared by the chamber, its conduit and dikes so that all
 * of them weigh the same magma alike.
 *
 * <p>Anhydrous melt: ~2750 kg/m³ for basalt to ~2480 for rhyolite, a linear fit of the order of melt
 * densities from partial molar volumes (Lange &amp; Carmichael 1987). Dissolved water adds its partial molar
 * volume, {@code V̄(H₂O) ≈ 22.9 cm³/mol} (Ochs &amp; Lange 1999), to the melt's specific volume: hydrous
 * melts are lighter (rhyolite with 5 wt% H₂O ≈ 2.3 g/cm³). Exsolved gas is an ideal gas at the magma's
 * pressure and temperature.
 *
 * <p>References: Lange &amp; Carmichael (1987), Geochim. Cosmochim. Acta 51:2931-2946; Ochs &amp; Lange (1999),
 * Science 283:1314-1317. See {@code docs/references.md}.
 */
public final class MeltDensity {
    private MeltDensity() {}

    /** Partial molar volume of dissolved H₂O (m³/mol). */
    static final double WATER_PARTIAL_MOLAR_VOLUME = 22.9e-6;
    static final double WATER_MOLAR_MASS = 0.018;
    static final double CO2_MOLAR_MASS = 0.044;
    static final double GAS_CONSTANT = 8.314;

    /** Anhydrous melt density (kg/m³) for {@code silicaWt}. */
    public static double anhydrousKgPerM3(double silicaWt) {
        return Math.max(2300, Math.min(2800, 2750 - 10 * (silicaWt - 48)));
    }

    /** Melt density (kg/m³) with {@code dissolvedWaterWt} wt% H₂O in solution. */
    public static double meltKgPerM3(double silicaWt, double dissolvedWaterWt) {
        double x = Math.max(0, dissolvedWaterWt) / 100;
        double specific = (1 - x) / anhydrousKgPerM3(silicaWt) + x * WATER_PARTIAL_MOLAR_VOLUME / WATER_MOLAR_MASS;
        return 1 / specific;
    }

    /**
     * Volume (m³) of the gas exsolved from 1 kg of magma carrying {@code exsolvedWaterWt} and
     * {@code exsolvedCo2Wt} wt% of free H₂O and CO₂, at {@code pressurePa} and {@code temperatureC}.
     */
    public static double gasVolumePerKg(double exsolvedWaterWt, double exsolvedCo2Wt, double pressurePa,
            double temperatureC) {
        double moles = (Math.max(0, exsolvedWaterWt) / WATER_MOLAR_MASS + Math.max(0, exsolvedCo2Wt) / CO2_MOLAR_MASS) / 100;
        if (!(moles > 0)) return 0;
        return moles * GAS_CONSTANT * (temperatureC + 273.15) / pressurePa;
    }

    /** Bulk density (kg/m³) of melt of density {@code melt} carrying {@code gasVolumePerKg} of bubbles. */
    public static double bubbly(double melt, double gasVolumePerKg) {
        return melt / (1 + melt * Math.max(0, gasVolumePerKg));
    }
}
