package me.alex4386.typhon.engine.tephra;

import java.util.Objects;
import me.alex4386.typhon.engine.volcano.MagmaState;
import me.alex4386.typhon.engine.volcano.VentSite;

/**
 * Parameters of an explosive eruption phase at one vent.
 *
 * @param vent vent the tephra is ejected from
 * @param massEruptionRate total tephra mass eruption rate (kg/s)
 * @param gasFraction exsolved gas mass fraction driving the jet (e.g. 0.01–0.05)
 * @param overpressureMPa conduit overpressure at fragmentation (MPa)
 * @param temperatureC magma temperature (°C)
 * @param silicaWt SiO₂ wt% (controls the rock bombs cool into)
 * @param ballisticFraction fraction of the erupted mass leaving as ballistic bombs
 * @param grainSize grain-size distribution of the remaining (airborne) mass
 * @param timeCompression physical seconds per simulated second of the eruption: {@code
 *     massEruptionRate} is per simulated second (it sets how much tephra each step injects), while
 *     the column itself (its height, lightning) follows the physical rate {@link
 *     #physicalMassEruptionRate()}
 */
public record ExplosivePhase(
        VentSite vent,
        double massEruptionRate,
        double gasFraction,
        double overpressureMPa,
        double temperatureC,
        double silicaWt,
        double ballisticFraction,
        GrainSizeDistribution grainSize,
        double timeCompression) {

    /** Density of dense magma used to convert DRE volume rates to mass rates (kg/m³). */
    public static final double DRE_DENSITY = 2500;

    /** A phase whose simulated time runs at physical speed. */
    public ExplosivePhase(VentSite vent, double massEruptionRate, double gasFraction, double overpressureMPa,
            double temperatureC, double silicaWt, double ballisticFraction, GrainSizeDistribution grainSize) {
        this(vent, massEruptionRate, gasFraction, overpressureMPa, temperatureC, silicaWt, ballisticFraction, grainSize, 1);
    }

    public ExplosivePhase {
        Objects.requireNonNull(vent, "vent");
        Objects.requireNonNull(grainSize, "grainSize");
        if (!(timeCompression > 0)) throw new IllegalArgumentException("timeCompression must be > 0");
        if (!(massEruptionRate >= 0)) throw new IllegalArgumentException("massEruptionRate must be >= 0");
        if (!(gasFraction >= 0 && gasFraction <= 1)) throw new IllegalArgumentException("gasFraction must be in [0, 1]");
        if (!(ballisticFraction >= 0 && ballisticFraction <= 1)) {
            throw new IllegalArgumentException("ballisticFraction must be in [0, 1]");
        }
    }

    /** Strombolian-style phase: low rate, coarse, ballistic-rich. */
    public static ExplosivePhase strombolian(VentSite vent, double massEruptionRate) {
        return new ExplosivePhase(vent, massEruptionRate, 0.01, 0.5, 1150, 50, 0.3, GrainSizeDistribution.STROMBOLIAN);
    }

    /** Vulcanian-style phase: short, violent, mixed grain sizes. */
    public static ExplosivePhase vulcanian(VentSite vent, double massEruptionRate) {
        return new ExplosivePhase(vent, massEruptionRate, 0.03, 5, 950, 60, 0.05, GrainSizeDistribution.VULCANIAN);
    }

    /** Plinian-style phase: sustained high column, fine ash. */
    public static ExplosivePhase plinian(VentSite vent, double massEruptionRate) {
        return new ExplosivePhase(vent, massEruptionRate, 0.05, 10, 850, 70, 0.01, GrainSizeDistribution.PLINIAN);
    }

    /**
     * Derives a phase from the magma system: the eruption rate comes from
     * {@link MagmaState#eruptionRate()} (DRE m³ per simulated second; its ratio to {@link
     * MagmaState#physicalEruptionRate()} is the time compression), roughly 80% of the dissolved water
     * is taken as exsolved at fragmentation, and grain size follows silica and water content.
     */
    public static ExplosivePhase fromMagma(VentSite vent, MagmaState magma, double ballisticFraction) {
        double simulated = magma.eruptionRate();
        double physical = magma.physicalEruptionRate();
        double compression = simulated > 0 && physical > 0 ? simulated / physical : 1;
        return new ExplosivePhase(
                vent,
                simulated * DRE_DENSITY,
                Math.min(1, magma.waterWt() / 100.0 * 0.8),
                magma.overpressureMPa(),
                magma.temperatureC(),
                magma.silicaWt(),
                ballisticFraction,
                GrainSizeDistribution.forMagma(magma.silicaWt(), magma.waterWt()),
                compression);
    }

    /** DRE volume eruption rate (m³ per simulated second). */
    public double volumeEruptionRate() {
        return massEruptionRate / DRE_DENSITY;
    }

    /** Mass eruption rate in physical time (kg per physical second): what the eruption column sees. */
    public double physicalMassEruptionRate() {
        return massEruptionRate / timeCompression;
    }

    /** The same phase with another simulated mass rate (compression unchanged). */
    public ExplosivePhase withMassEruptionRate(double rate) {
        return new ExplosivePhase(vent, rate, gasFraction, overpressureMPa, temperatureC, silicaWt, ballisticFraction,
                grainSize, timeCompression);
    }
}
