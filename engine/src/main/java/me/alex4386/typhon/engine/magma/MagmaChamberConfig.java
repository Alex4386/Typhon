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
 * @param initialTemperatureC chamber temperature at creation
 * @param initialSilicaWt bulk SiO₂ at creation (wt%)
 * @param initialWaterWt bulk H₂O at creation (wt%)
 * @param initialOverpressureMPa overpressure at creation
 * @param wallTemperatureC temperature the chamber relaxes towards by conduction
 * @param coolingTimescale e-folding time of conductive cooling (physical s)
 * @param degassingTimescale e-folding time for venting exsolved volatiles (physical s)
 * @param crystalSilicaWt SiO₂ of the crystallising (mafic) assemblage; drives melt evolution
 * @param conduit conduit-flow physics: outgassing, fragmentation, open/closed conduit, explosion
 *     cycles (see {@link ConduitConfig})
 * @param fragmentedViscosity effective conduit viscosity (Pa·s) of a fragmented gas–pyroclast mixture
 * @param maxEruptionRate cap on the eruption rate (m³ per physical second)
 * @param dormantTimeScale physical seconds per simulated second while not erupting
 * @param eruptiveTimeScale physical seconds per simulated second while erupting
 * @param stepIntervalTicks how often the chamber steps
 * @param sampleIntervalTicks how often a {@link MagmaEvents.ChamberSample} is emitted (0 = never)
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
        double initialTemperatureC,
        double initialSilicaWt,
        double initialWaterWt,
        double initialOverpressureMPa,
        double wallTemperatureC,
        double coolingTimescale,
        double degassingTimescale,
        double crystalSilicaWt,
        ConduitConfig conduit,
        double fragmentedViscosity,
        double maxEruptionRate,
        double dormantTimeScale,
        double eruptiveTimeScale,
        int stepIntervalTicks,
        int sampleIntervalTicks) {

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
        requirePositive("fragmentedViscosity", fragmentedViscosity);
        requirePositive("maxEruptionRate", maxEruptionRate);
        requirePositive("dormantTimeScale", dormantTimeScale);
        requirePositive("eruptiveTimeScale", eruptiveTimeScale);
        if (stepIntervalTicks < 1) throw new IllegalArgumentException("stepIntervalTicks must be >= 1");
        if (sampleIntervalTicks < 0) throw new IllegalArgumentException("sampleIntervalTicks must be >= 0");
    }

    /**
     * Overpressure that re-opens an open conduit, kept between the eruption end threshold and the
     * tensile strength.
     */
    public double reopenOverpressureMPa() {
        double low = Math.min(tensileStrengthMPa, eruptionEndOverpressureMPa + 0.5);
        return Math.max(low, Math.min(tensileStrengthMPa, conduit.reopenOverpressureMPa()));
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
        b.initialTemperatureC = initialTemperatureC;
        b.initialSilicaWt = initialSilicaWt;
        b.initialWaterWt = initialWaterWt;
        b.initialOverpressureMPa = initialOverpressureMPa;
        b.wallTemperatureC = wallTemperatureC;
        b.coolingTimescale = coolingTimescale;
        b.degassingTimescale = degassingTimescale;
        b.crystalSilicaWt = crystalSilicaWt;
        b.conduit = conduit;
        b.fragmentedViscosity = fragmentedViscosity;
        b.maxEruptionRate = maxEruptionRate;
        b.dormantTimeScale = dormantTimeScale;
        b.eruptiveTimeScale = eruptiveTimeScale;
        b.stepIntervalTicks = stepIntervalTicks;
        b.sampleIntervalTicks = sampleIntervalTicks;
        return b;
    }

    private static void requirePositive(String name, double value) {
        if (!(value > 0)) throw new IllegalArgumentException(name + " must be positive: " + value);
    }

    public static final class Builder {
        private final String volcanoId;
        private final BlockPos center;
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
        private double initialTemperatureC = 1100;
        private double initialSilicaWt = 52;
        private double initialWaterWt = 2.0;
        private double initialOverpressureMPa = 0;
        private double wallTemperatureC = 400;
        private double coolingTimescale = 2e10;
        private double degassingTimescale = 1e9;
        private double crystalSilicaWt = 47;
        private ConduitConfig conduit = ConduitConfig.DEFAULT;
        private double fragmentedViscosity = 100;
        private double maxEruptionRate = 100;
        private double dormantTimeScale = 5000;
        private double eruptiveTimeScale = 1;
        private int stepIntervalTicks = 20;
        private int sampleIntervalTicks = 100;

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
        public Builder initialTemperatureC(double v) { initialTemperatureC = v; return this; }
        public Builder initialSilicaWt(double v) { initialSilicaWt = v; return this; }
        public Builder initialWaterWt(double v) { initialWaterWt = v; return this; }
        public Builder initialOverpressureMPa(double v) { initialOverpressureMPa = v; return this; }
        public Builder wallTemperatureC(double v) { wallTemperatureC = v; return this; }
        public Builder coolingTimescale(double v) { coolingTimescale = v; return this; }
        public Builder degassingTimescale(double v) { degassingTimescale = v; return this; }
        public Builder crystalSilicaWt(double v) { crystalSilicaWt = v; return this; }
        public Builder conduit(ConduitConfig v) { conduit = v; return this; }
        public Builder fragmentedViscosity(double v) { fragmentedViscosity = v; return this; }
        public Builder maxEruptionRate(double v) { maxEruptionRate = v; return this; }
        public Builder dormantTimeScale(double v) { dormantTimeScale = v; return this; }
        public Builder eruptiveTimeScale(double v) { eruptiveTimeScale = v; return this; }
        public Builder stepIntervalTicks(int v) { stepIntervalTicks = v; return this; }
        public Builder sampleIntervalTicks(int v) { sampleIntervalTicks = v; return this; }

        public MagmaChamberConfig build() {
            return new MagmaChamberConfig(volcanoId, center, volume, compressibilityPerMPa, lithostaticDepth,
                    conduitRadius, tensileStrengthMPa, eruptionEndOverpressureMPa, supplyRate, supplyVariability,
                    rechargeTemperatureC, rechargeSilicaWt, rechargeWaterWt, initialTemperatureC, initialSilicaWt,
                    initialWaterWt, initialOverpressureMPa, wallTemperatureC, coolingTimescale, degassingTimescale,
                    crystalSilicaWt, conduit, fragmentedViscosity, maxEruptionRate, dormantTimeScale,
                    eruptiveTimeScale, stepIntervalTicks, sampleIntervalTicks);
        }
    }
}
