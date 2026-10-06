package me.alex4386.typhon.engine.magma;

import java.util.Objects;
import me.alex4386.typhon.engine.math.BlockPos;

/**
 * Parameters of a {@link MagmaChamber}. Defaults give a small basaltic-andesite system sized for a
 * Minecraft volcano: it recharges to failure in roughly an hour of play and an eruption drains it
 * over an hour or two.
 *
 * @param volcanoId id of the owning volcano; commands and events are keyed by it
 * @param center chamber centre in world coordinates
 * @param volume chamber volume (m³)
 * @param compressibilityPerMPa combined magma + wall-rock compressibility of the bubble-free system (1/MPa)
 * @param lithostaticDepth "physical" chamber depth (m) used for lithostatic pressure, volatile
 *     solubility and conduit length. Independent of {@code center} because Minecraft's vertical
 *     scale is compressed.
 * @param conduitRadius conduit radius (m) for the Poiseuille eruption-rate law
 * @param tensileStrengthMPa overpressure at which the roof fails and an eruption starts
 * @param eruptionEndOverpressureMPa overpressure below which an eruption stops
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
 * @param coolingTimescale e-folding time of conductive cooling (physical s)
 * @param degassingTimescale e-folding time for venting exsolved volatiles (physical s)
 * @param crystalSilicaWt SiO₂ of the crystallising (mafic) assemblage; drives melt evolution
 * @param conduit conduit-flow physics: outgassing, fragmentation, open/closed conduit, explosion
 *     cycles (see {@link ConduitConfig})
 * @param maxEruptionRate numerical safety cap on the eruption rate (m³ per physical second); the
 *     conduit model sets the actual rate
 * @param dormantTimeScale physical seconds per simulated second while not erupting
 * @param eruptiveTimeScale physical seconds per simulated second while erupting
 * @param stepPeriodSeconds how often the chamber steps (simulated seconds)
 * @param samplePeriodSeconds how often a {@link MagmaEvents.ChamberSample} is emitted (0 = never)
  * @param wallRuptureRatio override for the overpressure at which the chamber walls rupture, as a multiple of
 *     the larger of {@code tensileStrengthMPa} and the eruption threshold; NaN = computed (hoop stress, 2)
 * @param wallYieldFraction override for the share of magma beyond the rupture limit taken up by the walls
 *     yielding (inelastic growth) instead of a dike; NaN = computed from wall-rock relaxation vs. charging time
 * @param chamberId this chamber within its volcano's plumbing; {@link #MAIN} is the eruptive one
 */
public record MagmaChamberConfig(
        String volcanoId,
        BlockPos center,
        double volume,
        double compressibilityPerMPa,
        double lithostaticDepth,
        double conduitRadius,
        double tensileStrengthMPa,
        double eruptionEndOverpressureMPa,
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
        double dormantTimeScale,
        double eruptiveTimeScale,
        double stepPeriodSeconds,
        double samplePeriodSeconds,
        double wallRuptureRatio,
        double wallYieldFraction,
        String chamberId) {

    public MagmaChamberConfig {
        Objects.requireNonNull(volcanoId, "volcanoId");
        Objects.requireNonNull(center, "center");
        requirePositive("volume", volume);
        requirePositive("compressibilityPerMPa", compressibilityPerMPa);
        requirePositive("lithostaticDepth", lithostaticDepth);
        requirePositive("conduitRadius", conduitRadius);
        requirePositive("tensileStrengthMPa", tensileStrengthMPa);
        if (eruptionEndOverpressureMPa >= tensileStrengthMPa) {
            throw new IllegalArgumentException("eruptionEndOverpressureMPa must be below tensileStrengthMPa");
        }
        if (supplyRate < 0) throw new IllegalArgumentException("supplyRate must be >= 0");
        if (supplyVariability < 0) throw new IllegalArgumentException("supplyVariability must be >= 0");
        requirePositive("coolingTimescale", coolingTimescale);
        requirePositive("degassingTimescale", degassingTimescale);
        Objects.requireNonNull(conduit, "conduit");
        MagmaCommands.validateMagma(rechargeTemperatureC, rechargeSilicaWt, rechargeWaterWt, rechargeCo2Wt,
                rechargeCrystalFraction);
        if (!(initialCo2Wt >= 0)) throw new IllegalArgumentException("initialCo2Wt must be >= 0");
        requirePositive("maxEruptionRate", maxEruptionRate);
        requirePositive("dormantTimeScale", dormantTimeScale);
        requirePositive("eruptiveTimeScale", eruptiveTimeScale);
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

    /**
     * Overpressure that re-opens an open conduit, kept between the eruption end threshold and the
     * tensile strength.
     */
    public double reopenOverpressureMPa() {
        double low = Math.min(tensileStrengthMPa, eruptionEndOverpressureMPa + 0.5);
        return Math.max(low, Math.min(tensileStrengthMPa, conduit.reopenOverpressureMPa()));
    }

    /** Id of a volcano's main (eruptive) chamber, the one its summit conduit and vents draw from. */
    public static final String MAIN = "main";

    /** Whether this is the volcano's main (eruptive) chamber. */
    public boolean isMain() {
        return MAIN.equals(chamberId);
    }

    public static Builder builder(String volcanoId, BlockPos center) {
        return new Builder(volcanoId, center);
    }

    public Builder toBuilder() {
        Builder b = new Builder(volcanoId, center);
        b.volume = volume;
        b.compressibilityPerMPa = compressibilityPerMPa;
        b.lithostaticDepth = lithostaticDepth;
        b.conduitRadius = conduitRadius;
        b.tensileStrengthMPa = tensileStrengthMPa;
        b.eruptionEndOverpressureMPa = eruptionEndOverpressureMPa;
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
        b.dormantTimeScale = dormantTimeScale;
        b.eruptiveTimeScale = eruptiveTimeScale;
        b.stepPeriodSeconds = stepPeriodSeconds;
        b.samplePeriodSeconds = samplePeriodSeconds;
        b.wallRuptureRatio = wallRuptureRatio;
        b.wallYieldFraction = wallYieldFraction;
        b.chamberId = chamberId;
        return b;
    }

    private static void requirePositive(String name, double value) {
        if (!(value > 0)) throw new IllegalArgumentException(name + " must be positive: " + value);
    }

    public static final class Builder {
        private final String volcanoId;
        private BlockPos center;
        private double volume = 1e10; // 10 km³; real chambers are ~1–100 km³
        private double compressibilityPerMPa = 2e-4;
        private double lithostaticDepth = 4000;
        private double conduitRadius = 1.5;
        private double tensileStrengthMPa = 15;
        private double eruptionEndOverpressureMPa = 2;
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
        private double wallTemperatureC = 400;
        private double coolingTimescale = 2e10;
        private double degassingTimescale = 1e9;
        private double crystalSilicaWt = 47;
        private ConduitConfig conduit = ConduitConfig.DEFAULT;
        private double maxEruptionRate = 1e6;
        private double dormantTimeScale = 5000;
        private double eruptiveTimeScale = 1;
        private double stepPeriodSeconds = 1.0;
        private double samplePeriodSeconds = 5.0;
        private double wallRuptureRatio = Double.NaN; // computed (MagmaChamber#wallRuptureRatio)
        private double wallYieldFraction = Double.NaN; // computed (MagmaChamber#wallYieldFraction)
        private String chamberId = MAIN;

        private Builder(String volcanoId, BlockPos center) {
            this.volcanoId = volcanoId;
            this.center = center;
        }

        public Builder volume(double v) { volume = v; return this; }
        public Builder compressibilityPerMPa(double v) { compressibilityPerMPa = v; return this; }
        public Builder lithostaticDepth(double v) { lithostaticDepth = v; return this; }
        public Builder conduitRadius(double v) { conduitRadius = v; return this; }
        public Builder tensileStrengthMPa(double v) { tensileStrengthMPa = v; return this; }
        public Builder eruptionEndOverpressureMPa(double v) { eruptionEndOverpressureMPa = v; return this; }
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
        public Builder dormantTimeScale(double v) { dormantTimeScale = v; return this; }
        public Builder eruptiveTimeScale(double v) { eruptiveTimeScale = v; return this; }
        public Builder stepPeriodSeconds(double v) { stepPeriodSeconds = v; return this; }
        public Builder samplePeriodSeconds(double v) { samplePeriodSeconds = v; return this; }
        public Builder wallRuptureRatio(double v) { wallRuptureRatio = v; return this; }
        public Builder wallYieldFraction(double v) { wallYieldFraction = v; return this; }
        public Builder chamberId(String v) { chamberId = v; return this; }
        public Builder center(BlockPos v) { center = Objects.requireNonNull(v); return this; }

        public MagmaChamberConfig build() {
            return new MagmaChamberConfig(volcanoId, center, volume, compressibilityPerMPa, lithostaticDepth,
                    conduitRadius, tensileStrengthMPa, eruptionEndOverpressureMPa, supplyRate, supplyVariability,
                    rechargeTemperatureC, rechargeSilicaWt, rechargeWaterWt, rechargeCo2Wt, rechargeCrystalFraction,
                    initialTemperatureC, initialSilicaWt, initialWaterWt, initialCo2Wt, initialOverpressureMPa,
                    wallTemperatureC, coolingTimescale, degassingTimescale, crystalSilicaWt, conduit, maxEruptionRate,
                    dormantTimeScale,
                    eruptiveTimeScale, stepPeriodSeconds, samplePeriodSeconds, wallRuptureRatio, wallYieldFraction, chamberId);
        }
    }
}
