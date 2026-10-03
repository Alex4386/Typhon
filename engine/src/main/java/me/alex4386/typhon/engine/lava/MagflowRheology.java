package me.alex4386.typhon.engine.lava;

/**
 * Default rheology, calibrated to textbook values for degassed surface lavas.
 *
 * <ul>
 *   <li><b>Melt viscosity</b>: Vogel–Fulcher–Tammann {@code log10 η = A + B / (T − C)} with the
 *       Giordano et al. (2008) constant {@code A = −4.55} and {@code B}, {@code C} linear in SiO₂,
 *       fitted to ≈10^2.4 Pa·s basalt at 1150 °C, ≈10^8 dacite at 900 °C, ≈10^11.8 rhyolite at
 *       850 °C. Dissolved water lowers it.
 *   <li><b>Crystals</b>: crystal fraction rises linearly from 0 at the liquidus to 0.55 at the
 *       solidus; Einstein–Roscoe {@code η·(1 − φ/0.6)^−2.5}.
 *   <li><b>Yield strength</b>: MAGFLOW (Del Negro et al. 2008) Etna fit
 *       {@code log10 τ = 13.00997 − 0.0089·T[K]}, shifted by {@code +0.15} per wt% SiO₂ above 50 so
 *       dacite (~10^4.8 Pa at 900 °C) builds domes while basalt (~2 Pa at 1150 °C) runs freely.
 *   <li><b>Liquidus/solidus</b>: 1200/1000 °C at 50 wt% SiO₂ falling to 958/700 °C at 72 wt%.
 * </ul>
 */
public final class MagflowRheology implements LavaRheology {
    public static final MagflowRheology INSTANCE = new MagflowRheology();

    private static final double MAX_CRYSTAL_FRACTION = 0.55;
    private static final double PACKING_FRACTION = 0.6;

    private static double clampSilica(double silicaWt) {
        return Math.max(45, Math.min(77, silicaWt));
    }

    @Override
    public double liquidusC(double silicaWt) {
        return 1200 - 11 * (clampSilica(silicaWt) - 50);
    }

    @Override
    public double solidusC(double silicaWt) {
        return 1000 - 13.6 * (clampSilica(silicaWt) - 50);
    }

    @Override
    public double viscosityPaS(double temperatureC, double silicaWt, double waterWt) {
        double s = clampSilica(silicaWt) - 50;
        double tK = temperatureC + 273.15;
        double b = 5600 - 11 * s;
        double c = 620 + 8 * s;
        double log = -4.55 + b / Math.max(50, tK - c);
        log -= 0.6 * Math.max(0, Math.min(6, waterWt)) * (1 + s / 15);
        log = Math.max(-1, Math.min(14, log));

        double liquidus = liquidusC(silicaWt);
        double solidus = solidusC(silicaWt);
        double crystallised = Math.max(0, Math.min(1, (liquidus - temperatureC) / (liquidus - solidus)));
        double phi = MAX_CRYSTAL_FRACTION * crystallised;
        return Math.pow(10, log) * Math.pow(1 - phi / PACKING_FRACTION, -2.5);
    }

    @Override
    public double yieldStrengthPa(double temperatureC, double silicaWt) {
        double tK = temperatureC + 273.15;
        double log = 13.00997 - 0.0089 * tK + 0.15 * (clampSilica(silicaWt) - 50);
        return Math.pow(10, Math.min(7, log));
    }
}
