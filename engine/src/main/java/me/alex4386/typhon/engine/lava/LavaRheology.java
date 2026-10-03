package me.alex4386.typhon.engine.lava;

/**
 * Temperature- and composition-dependent lava properties used by {@link LavaFlow}.
 *
 * <p>Kept behind an interface so a richer melt model (e.g. the magma chamber's viscosity model) can
 * replace {@link MagflowRheology} without touching the cellular automaton.
 */
public interface LavaRheology {
    /** Effective (melt + crystals) dynamic viscosity in Pa·s. */
    double viscosityPaS(double temperatureC, double silicaWt, double waterWt);

    /** Bingham yield strength in Pa. */
    double yieldStrengthPa(double temperatureC, double silicaWt);

    /** Temperature above which the lava is fully molten. */
    double liquidusC(double silicaWt);

    /** Temperature below which the lava is solid rock. */
    double solidusC(double silicaWt);
}
