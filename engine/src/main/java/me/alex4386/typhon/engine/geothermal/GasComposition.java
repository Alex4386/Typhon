package me.alex4386.typhon.engine.geothermal;

/** Mole fractions of a fumarolic gas mixture; the four fractions sum to 1. */
public record GasComposition(double h2o, double co2, double so2, double h2s) {
    /**
     * Typical composition at a given vent temperature: high-temperature (magmatic) gases are
     * SO₂-rich, low-temperature (hydrothermal) gases are H₂S-rich and almost pure steam.
     */
    public static GasComposition atTemperature(double temperatureC) {
        double t = Math.max(0, Math.min(1, (temperatureC - 100) / 300));
        double co2 = lerp(0.04, 0.09, t);
        double so2 = lerp(0.002, 0.06, t);
        double h2s = lerp(0.012, 0.01, t);
        return new GasComposition(1 - co2 - so2 - h2s, co2, so2, h2s);
    }

    public double fraction(GasSpecies species) {
        return switch (species) {
            case CO2 -> co2;
            case SO2 -> so2;
            case H2S -> h2s;
        };
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}
