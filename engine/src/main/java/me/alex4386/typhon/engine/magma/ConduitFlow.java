package me.alex4386.typhon.engine.magma;

/**
 * Steady conduit-flow physics deciding how an eruption behaves.
 *
 * <ul>
 *   <li><b>Rate.</b> Poiseuille flow {@code Q = π r⁴ ΔP / (8 μ L)}. Coherent (unfragmented) magma
 *       degasses as it rises, so its conduit viscosity is the log-mean of the chamber melt and the
 *       degassed melt. A fragmented gas–pyroclast mixture flows with a low effective viscosity.
 *   <li><b>Outgassing.</b> During ascent ({@code t_a = L/u}, {@code u = Q/πr²}) gas escapes
 *       permeably: the water reaching the fragmentation level is
 *       {@code w_deg + (w − w_deg)·e^(−t_a/τ)}. Slow ascent erupts degassed lava.
 *   <li><b>Fragmentation</b> needs both (i) enough gas: the exsolved water at the fragmentation
 *       pressure must reach the critical gas volume fraction (~0.75), and (ii) brittle failure: melt
 *       viscosity × strain rate ({@code ≈ u/((1−φ_c) r)}) above {@code k·G∞} (Papale 1999). Fluid
 *       basalt never fails brittlely; it fountains instead.
 *   <li><b>Gas segregation.</b> Conduit-filling gas slugs rise at the Taylor-bubble speed
 *       {@code min(0.345 √(gD), 0.01 ρ g D² / μ)}. If that exceeds the magma ascent speed in fluid
 *       magma, gas separates (open vent, Strombolian); otherwise gas and melt rise together (fountains).
 * </ul>
 */
public final class ConduitFlow {
    static final double GRAVITY = 9.81;
    static final double MAGMA_DENSITY = 2500;
    static final double WATER_MOLAR_MASS = 0.018;
    static final double GAS_CONSTANT = 8.314;

    private ConduitFlow() {}

    /** Snapshot of the conduit for a given chamber state. */
    public record Assessment(
            double effusiveConductance,
            double fragmentedConductance,
            double conduitViscosityLog10,
            double slugVelocity,
            boolean slugFlowPossible) {}

    public static Assessment assess(MagmaChamberConfig config, double silicaWt, double waterWt, double temperatureC,
            double crystalFraction) {
        ConduitConfig c = config.conduit();
        double degassed = MeltViscosity.log10(silicaWt, c.degassedWaterWt(), temperatureC, crystalFraction);
        double chamber = MeltViscosity.log10(silicaWt, waterWt, temperatureC, crystalFraction);
        double conduitLog = 0.5 * (degassed + chamber);
        double r = config.conduitRadius();
        double effusive = poiseuille(r, Math.pow(10, conduitLog), config.lithostaticDepth());
        double fragmented = poiseuille(r, config.fragmentedViscosity(), config.lithostaticDepth());
        double slug = slugVelocity(2 * r, Math.pow(10, conduitLog));
        return new Assessment(effusive, fragmented, conduitLog, slug, Math.pow(10, conduitLog) <= c.slugFlowMaxViscosity());
    }

    /** Poiseuille conductance (m³/s per MPa). */
    static double poiseuille(double radius, double viscosity, double length) {
        double r2 = radius * radius;
        return Math.PI * r2 * r2 * 1e6 / (8 * viscosity * length);
    }

    /** Taylor-bubble rise speed in a conduit of diameter {@code d} (m/s). */
    public static double slugVelocity(double d, double viscosity) {
        double inertial = 0.345 * Math.sqrt(GRAVITY * d);
        double viscous = 0.01 * MAGMA_DENSITY * GRAVITY * d * d / viscosity;
        return Math.min(inertial, viscous);
    }

    /** Mean ascent speed (m/s) of magma erupting at {@code rate} (m³/s) through radius {@code r}. */
    public static double ascentVelocity(double rate, double radius) {
        return rate / (Math.PI * radius * radius);
    }

    /** H₂O (wt%) still dissolved when magma rising at {@code rate} reaches the fragmentation level. */
    public static double retainedWaterWt(MagmaChamberConfig config, double waterWt, double rate) {
        ConduitConfig c = config.conduit();
        if (waterWt <= c.degassedWaterWt()) return waterWt;
        double u = ascentVelocity(rate, config.conduitRadius());
        if (!(u > 0)) return c.degassedWaterWt();
        double ascentTime = config.lithostaticDepth() / u;
        return c.degassedWaterWt() + (waterWt - c.degassedWaterWt()) * Math.exp(-ascentTime / c.permeableOutgassingTimescale());
    }

    /** Water solubility (wt%) at pressure {@code p} (MPa). */
    static double solubilityWt(double p) {
        return 0.411 * Math.sqrt(Math.max(0, p));
    }

    /** Gas volume fraction at the fragmentation pressure for magma carrying {@code waterWt}. */
    public static double gasFractionAtFragmentation(ConduitConfig c, double waterWt, double temperatureC) {
        double p = c.fragmentationPressureMPa();
        double exsolved = Math.max(0, waterWt - solubilityWt(p)) / 100;
        if (exsolved <= 0) return 0;
        double gasDensity = p * 1e6 * WATER_MOLAR_MASS / (GAS_CONSTANT * (temperatureC + 273.15));
        double ratio = exsolved * MAGMA_DENSITY / gasDensity;
        return ratio / (1 + ratio);
    }

    /** Stress (Pa) the expanding melt sustains at the fragmentation level when erupting at {@code rate}. */
    public static double fragmentationStress(MagmaChamberConfig config, double silicaWt, double temperatureC,
            double crystalFraction, double rate) {
        ConduitConfig c = config.conduit();
        double melt = Math.pow(10, MeltViscosity.log10(
                silicaWt, solubilityWt(c.fragmentationPressureMPa()), temperatureC, crystalFraction));
        double strainRate = ascentVelocity(rate, config.conduitRadius())
                / ((1 - c.fragmentationGasFraction()) * config.conduitRadius());
        return melt * strainRate;
    }

    /** True if magma erupting at {@code rate} reaches the fragmentation criterion (gas and brittle). */
    public static boolean fragments(MagmaChamberConfig config, double silicaWt, double waterWt, double temperatureC,
            double crystalFraction, double rate) {
        ConduitConfig c = config.conduit();
        double retained = retainedWaterWt(config, waterWt, rate);
        if (gasFractionAtFragmentation(c, retained, temperatureC) < c.fragmentationGasFraction()) return false;
        return fragmentationStress(config, silicaWt, temperatureC, crystalFraction, rate) >= c.brittleStressPa();
    }
}
