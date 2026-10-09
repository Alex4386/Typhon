package me.alex4386.typhon.engine.magma;

import java.util.Objects;
import me.alex4386.typhon.engine.math.Point3;

/**
 * Parameters of a {@link MagmaChamber}, in physical units and physical time. Defaults give a small
 * basaltic-andesite system.
 *
 * @param volcanoId id of the owning volcano; commands and events are keyed by it
 * @param center chamber centre (m; {@code y} is its elevation)
 * @param volume chamber volume (m³)
 * @param compressibilityPerMPa combined magma + wall-rock compressibility of the bubble-free system (1/MPa);
 *     NaN = computed: bubble-free melt ({@code ~1e-10 Pa⁻¹}) plus the compliance of a spherical cavity in an
 *     elastic crust, {@code 3/(4μ)} (McTigue 1987)
 * @param lithostaticDepth chamber depth below the surface (m), for lithostatic pressure, volatile
 *     solubility and conduit length
 * @param conduitRadius conduit radius (m) for the Poiseuille eruption-rate law
 * @param tensileStrengthMPa overpressure at which the roof fails and an eruption starts
 * @param supplyRate deep magma supply (m³ per physical second)
 * @param supplyVariability log-normal σ of per-step supply fluctuations (0 = steady)
 * @param rechargeTemperatureC temperature of the supplied magma
 * @param rechargeSilicaWt SiO₂ of the supplied magma (wt%)
 * @param rechargeWaterWt H₂O of the supplied magma (wt%)
 * @param rechargeCo2Wt CO₂ of the supplied magma's melt (wt%)
 * @param rechargeCrystalFraction crystal volume fraction the supplied magma carries
 * @param initialTemperatureC chamber temperature at creation
 * @param initialSilicaWt bulk SiO₂ at creation (wt%)
 * @param initialWaterWt bulk H₂O at creation (wt%)
 * @param initialCo2Wt bulk CO₂ at creation (wt%)
 * @param initialOverpressureMPa overpressure at creation
 * @param wallTemperatureC temperature the chamber relaxes towards by conduction
 * @param coolingTimescale e-folding time of conductive cooling (physical s); NaN = computed from conduction
 *     out of a sphere of the chamber's volume (see {@code MagmaChamber#coolingTimescaleSeconds})
 * @param degassingTimescale e-folding time for venting exsolved volatiles (physical s); NaN = computed from the
 *     Stokes rise of bubbles across the chamber (see {@code MagmaChamber#degassingTimescaleSeconds})
 * @param crystalSilicaWt SiO₂ of the crystallising (mafic) assemblage; drives melt evolution
 * @param conduit conduit-flow physics: outgassing, fragmentation, open/closed conduit, explosion
 *     cycles (see {@link ConduitConfig})
 * @param maxEruptionRate numerical safety cap on the eruption rate (m³ per physical second); the
 *     conduit model sets the actual rate
 * @param stepPeriodSeconds how often the chamber steps (seconds)
 * @param samplePeriodSeconds how often a {@link MagmaEvents.ChamberSample} is emitted (0 = never)
  * @param wallRuptureRatio override for the overpressure at which the chamber walls rupture, as a multiple of
 *     the larger of {@code tensileStrengthMPa} and the eruption threshold; NaN = computed (hoop stress, 2)
 * @param wallYieldFraction override for the share of magma beyond the rupture limit taken up by the walls
 *     yielding (inelastic growth) instead of a dike; NaN = computed from wall-rock relaxation vs. charging time
 * @param freezeVolume the chamber never grows: no wall yielding, and magma beyond the rupture limit that no dike
 *     takes is refused at the deep source (the supply backs up) instead of enlarging the chamber
  * @param chamberId this chamber within its volcano's plumbing; {@link #MAIN} is the eruptive one
 */
public record MagmaChamberConfig(
        String volcanoId,
        Point3 center,
        double volume,
        double compressibilityPerMPa,
        double lithostaticDepth,
        double conduitRadius,
        double tensileStrengthMPa,
        double supplyRate,
        double supplyVariability,
        double rechargeTemperatureC,
        double rechargeSilicaWt,
        double rechargeWaterWt,
        double rechargeCo2Wt,
        double rechargeCrystalFraction,
        double initialTemperatureC,
        double initialSilicaWt,
        double initialWaterWt,
        double initialCo2Wt,
        double initialOverpressureMPa,
        double wallTemperatureC,
        double coolingTimescale,
        double degassingTimescale,
        double crystalSilicaWt,
        ConduitConfig conduit,
        double maxEruptionRate,
        double stepPeriodSeconds,
        double samplePeriodSeconds,
        double wallRuptureRatio,
        double wallYieldFraction,
        String chamberId,
        boolean freezeVolume) {

    public MagmaChamberConfig {
        Objects.requireNonNull(volcanoId, "volcanoId");
        Objects.requireNonNull(center, "center");
        requirePositive("volume", volume);
        requirePositiveOrNaN("compressibilityPerMPa", compressibilityPerMPa);
        requirePositive("lithostaticDepth", lithostaticDepth);
        requirePositive("conduitRadius", conduitRadius);
        requirePositive("tensileStrengthMPa", tensileStrengthMPa);
        if (supplyRate < 0) throw new IllegalArgumentException("supplyRate must be >= 0");
        if (supplyVariability < 0) throw new IllegalArgumentException("supplyVariability must be >= 0");
        requirePositiveOrNaN("coolingTimescale", coolingTimescale);
        requirePositiveOrNaN("degassingTimescale", degassingTimescale);
        Objects.requireNonNull(conduit, "conduit");
        MagmaCommands.validateMagma(rechargeTemperatureC, rechargeSilicaWt, rechargeWaterWt, rechargeCo2Wt,
                rechargeCrystalFraction);
        if (!(initialCo2Wt >= 0)) throw new IllegalArgumentException("initialCo2Wt must be >= 0");
        requirePositive("maxEruptionRate", maxEruptionRate);
        if (!(stepPeriodSeconds > 0)) throw new IllegalArgumentException("stepPeriodSeconds must be > 0");
        if (!(samplePeriodSeconds >= 0)) throw new IllegalArgumentException("samplePeriodSeconds must be >= 0");
        Objects.requireNonNull(chamberId, "chamberId");
        if (!chamberId.matches("[a-z0-9][a-z0-9_-]*")) throw new IllegalArgumentException("chamberId must be [a-z0-9_-]: " + chamberId);
        if (!Double.isNaN(wallRuptureRatio) && !(wallRuptureRatio >= 1)) {
            throw new IllegalArgumentException("wallRuptureRatio must be >= 1 (or NaN = computed): " + wallRuptureRatio);
        }
        if (!Double.isNaN(wallYieldFraction) && !(wallYieldFraction >= 0 && wallYieldFraction <= 1)) {
            throw new IllegalArgumentException("wallYieldFraction must be in [0, 1] (or NaN = computed): " + wallYieldFraction);
        }
    }


    /** Id of a volcano's main (eruptive) chamber, the one its summit conduit and vents draw from. */
    public static final String MAIN = "main";

    /** Whether this is the volcano's main (eruptive) chamber. */
    public boolean isMain() {
        return MAIN.equals(chamberId);
    }

    /** Chamber depth (m) when a definition does not give one. */
    public static final double DEFAULT_LITHOSTATIC_DEPTH_M = 4000;

    public static Builder builder(String volcanoId, Point3 center) {
        return new Builder(volcanoId, center);
    }

    public Builder toBuilder() {
        Builder b = new Builder(volcanoId, center);
        b.volume = volume;
        b.compressibilityPerMPa = compressibilityPerMPa;
        b.lithostaticDepth = lithostaticDepth;
        b.conduitRadius = conduitRadius;
        b.tensileStrengthMPa = tensileStrengthMPa;
        b.supplyRate = supplyRate;
        b.supplyVariability = supplyVariability;
        b.rechargeTemperatureC = rechargeTemperatureC;
        b.rechargeSilicaWt = rechargeSilicaWt;
        b.rechargeWaterWt = rechargeWaterWt;
        b.rechargeCo2Wt = rechargeCo2Wt;
        b.rechargeCrystalFraction = rechargeCrystalFraction;
        b.initialTemperatureC = initialTemperatureC;
        b.initialSilicaWt = initialSilicaWt;
        b.initialWaterWt = initialWaterWt;
        b.initialCo2Wt = initialCo2Wt;
        b.initialOverpressureMPa = initialOverpressureMPa;
        b.wallTemperatureC = wallTemperatureC;
        b.coolingTimescale = coolingTimescale;
        b.degassingTimescale = degassingTimescale;
        b.crystalSilicaWt = crystalSilicaWt;
        b.conduit = conduit;
        b.maxEruptionRate = maxEruptionRate;
        b.stepPeriodSeconds = stepPeriodSeconds;
        b.samplePeriodSeconds = samplePeriodSeconds;
        b.wallRuptureRatio = wallRuptureRatio;
        b.wallYieldFraction = wallYieldFraction;
        b.freezeVolume = freezeVolume;
        b.chamberId = chamberId;
        return b;
    }

    private static void requirePositive(String name, double value) {
        if (!(value > 0)) throw new IllegalArgumentException(name + " must be positive: " + value);
    }

    private static void requirePositiveOrNaN(String name, double value) {
        if (!Double.isNaN(value) && !(value > 0)) throw new IllegalArgumentException(name + " must be positive (or NaN = computed): " + value);
    }

    public static final class Builder {
        private final String volcanoId;
        private Point3 center;
        private double volume = 1e10; // 10 km³; real chambers are ~1–100 km³
        private double compressibilityPerMPa = Double.NaN; // computed (MagmaChamber#bubbleFreeCompressibility)
        private double lithostaticDepth = DEFAULT_LITHOSTATIC_DEPTH_M;
        private double conduitRadius = 1.5; // basaltic feeders are metres across (Wilson & Head 1981)
        // overpressure at roof failure: of the order of the host rock's tensile strength, a few to ~20 MPa
        // (Gudmundsson 2012; Jellinek & DePaolo 2003)
        private double tensileStrengthMPa = 15;
        private double supplyRate = 0.3; // between arc (~0.01–0.1) and hotspot (~1–5) supply
        private double supplyVariability = 0.3;
        private double rechargeTemperatureC = 1180;
        private double rechargeSilicaWt = 50;
        private double rechargeWaterWt = 1.5;
        private double rechargeCo2Wt = 0;
        private double rechargeCrystalFraction = 0;
        private double initialTemperatureC = 1100;
        private double initialSilicaWt = 52;
        private double initialWaterWt = 2.0;
        private double initialCo2Wt = 0;
        private double initialOverpressureMPa = 0;
        // host rock around a long-lived reservoir, heated above the regional geotherm: a few hundred °C
        // (order of magnitude; poorly constrained, depends on the reservoir's history)
        private double wallTemperatureC = 400;
        private double coolingTimescale = Double.NaN; // computed (MagmaChamber#coolingTimescaleSeconds)
        private double degassingTimescale = Double.NaN; // computed (MagmaChamber#degassingTimescaleSeconds)
        // olivine + clinopyroxene + plagioclase of a basaltic cumulate (~45–48 wt% SiO₂)
        private double crystalSilicaWt = 47;
        private ConduitConfig conduit = ConduitConfig.DEFAULT;
        private double maxEruptionRate = 1e6;
        private double stepPeriodSeconds = 20;
        private double samplePeriodSeconds = 5.0;
        private double wallRuptureRatio = Double.NaN; // computed (MagmaChamber#wallRuptureRatio)
        private double wallYieldFraction = Double.NaN; // computed (MagmaChamber#wallYieldFraction)
        private boolean freezeVolume = false;
        private String chamberId = MAIN;

        private Builder(String volcanoId, Point3 center) {
            this.volcanoId = volcanoId;
            this.center = center;
        }

        public Builder volume(double v) { volume = v; return this; }
        public Builder compressibilityPerMPa(double v) { compressibilityPerMPa = v; return this; }
        public Builder lithostaticDepth(double v) { lithostaticDepth = v; return this; }
        public Builder conduitRadius(double v) { conduitRadius = v; return this; }
        public Builder tensileStrengthMPa(double v) { tensileStrengthMPa = v; return this; }
        public Builder supplyRate(double v) { supplyRate = v; return this; }
        public Builder supplyVariability(double v) { supplyVariability = v; return this; }
        public Builder rechargeTemperatureC(double v) { rechargeTemperatureC = v; return this; }
        public Builder rechargeSilicaWt(double v) { rechargeSilicaWt = v; return this; }
        public Builder rechargeWaterWt(double v) { rechargeWaterWt = v; return this; }
        public Builder rechargeCo2Wt(double v) { rechargeCo2Wt = v; return this; }
        public Builder rechargeCrystalFraction(double v) { rechargeCrystalFraction = v; return this; }
        public Builder initialTemperatureC(double v) { initialTemperatureC = v; return this; }
        public Builder initialSilicaWt(double v) { initialSilicaWt = v; return this; }
        public Builder initialWaterWt(double v) { initialWaterWt = v; return this; }
        public Builder initialCo2Wt(double v) { initialCo2Wt = v; return this; }
        public Builder initialOverpressureMPa(double v) { initialOverpressureMPa = v; return this; }
        public Builder wallTemperatureC(double v) { wallTemperatureC = v; return this; }
        public Builder coolingTimescale(double v) { coolingTimescale = v; return this; }
        public Builder degassingTimescale(double v) { degassingTimescale = v; return this; }
        public Builder crystalSilicaWt(double v) { crystalSilicaWt = v; return this; }
        public Builder conduit(ConduitConfig v) { conduit = v; return this; }
        public Builder maxEruptionRate(double v) { maxEruptionRate = v; return this; }
        public Builder stepPeriodSeconds(double v) { stepPeriodSeconds = v; return this; }
        public Builder samplePeriodSeconds(double v) { samplePeriodSeconds = v; return this; }
        public Builder wallRuptureRatio(double v) { wallRuptureRatio = v; return this; }
        public Builder wallYieldFraction(double v) { wallYieldFraction = v; return this; }
        public Builder freezeVolume(boolean v) { freezeVolume = v; return this; }
        public Builder chamberId(String v) { chamberId = v; return this; }
        public Builder center(Point3 v) { center = Objects.requireNonNull(v); return this; }

        public MagmaChamberConfig build() {
            return new MagmaChamberConfig(volcanoId, center, volume, compressibilityPerMPa, lithostaticDepth,
                    conduitRadius, tensileStrengthMPa, supplyRate, supplyVariability,
                    rechargeTemperatureC, rechargeSilicaWt, rechargeWaterWt, rechargeCo2Wt, rechargeCrystalFraction,
                    initialTemperatureC, initialSilicaWt, initialWaterWt, initialCo2Wt, initialOverpressureMPa,
                    wallTemperatureC, coolingTimescale, degassingTimescale, crystalSilicaWt, conduit, maxEruptionRate,
                    stepPeriodSeconds, samplePeriodSeconds, wallRuptureRatio, wallYieldFraction, chamberId, freezeVolume);
        }
    }
}
