package me.alex4386.typhon.engine.seismic;

import java.util.Objects;
import me.alex4386.typhon.engine.math.BlockPos;

/**
 * Parameters of a {@link SeismicityModel}. Rates are events per simulated second.
 *
 * @param volcanoId id of the owning volcano
 * @param conduitTop top of the conduit (vent floor); hypocentres are scattered between the chamber
 *     and this point
 * @param failureOverpressureMPa roof strength; VT rates accelerate as overpressure approaches it
 *     (should match the chamber's tensile strength)
 * @param backgroundVtRate VT rate of a quiet volcano
 * @param vtPerMPa VT events per MPa of pressurisation (rate ∝ dP/dt)
 * @param maxAcceleration cap on the failure-forecast acceleration factor {@code 1 / (1 − P/P_f)}
 * @param maxEventRate cap on any single event rate
 * @param backgroundLpRate LP rate of a quiet volcano
 * @param lpPerEruptionRate LP events/s per m³/s of erupting magma
 * @param tremorEpisodeRate tremor episode onset rate per √(m³/s) of eruption rate
 * @param tremorMeanDurationSeconds mean tremor episode length (exponential)
 * @param tremorBaseMagnitude equivalent magnitude of tremor at 0 m³/s (grows with log rate)
 * @param explosionRate explosion rate at full explosivity
 * @param swarmTriggerProbability chance that a VT event starts a swarm
 * @param swarmMeanDurationSeconds mean swarm length (exponential)
 * @param swarmRateMultiplier VT rate multiplier during a swarm
 * @param vtBValue Gutenberg–Richter b for VT events
 * @param swarmBValue b for swarm VTs (swarms are rich in small events)
 * @param lpBValue b for LP events
 * @param explosionBValue b for explosion quakes
 * @param minMagnitude completeness / smallest generated magnitude
 * @param maxMagnitude largest VT magnitude
 * @param lpMaxMagnitude largest LP magnitude
 * @param explosionMinMagnitude smallest explosion quake
 * @param explosionMaxMagnitude largest explosion quake
 * @param hypocenterSpread lateral scatter of hypocentres (blocks, 1σ)
 * @param rsamWindowSeconds averaging time constant of RSAM
 * @param rateWindowSeconds averaging time constant of the event-rate estimates
 * @param stepIntervalTicks how often the model steps
 * @param sampleIntervalTicks how often an {@link RsamSample} is emitted (0 = never)
 */
public record SeismicConfig(
        String volcanoId,
        BlockPos conduitTop,
        double failureOverpressureMPa,
        double backgroundVtRate,
        double vtPerMPa,
        double maxAcceleration,
        double maxEventRate,
        double backgroundLpRate,
        double lpPerEruptionRate,
        double tremorEpisodeRate,
        double tremorMeanDurationSeconds,
        double tremorBaseMagnitude,
        double explosionRate,
        double swarmTriggerProbability,
        double swarmMeanDurationSeconds,
        double swarmRateMultiplier,
        double vtBValue,
        double swarmBValue,
        double lpBValue,
        double explosionBValue,
        double minMagnitude,
        double maxMagnitude,
        double lpMaxMagnitude,
        double explosionMinMagnitude,
        double explosionMaxMagnitude,
        double hypocenterSpread,
        double rsamWindowSeconds,
        double rateWindowSeconds,
        int stepIntervalTicks,
        int sampleIntervalTicks) {

    public SeismicConfig {
        Objects.requireNonNull(volcanoId, "volcanoId");
        Objects.requireNonNull(conduitTop, "conduitTop");
        if (!(failureOverpressureMPa > 0)) throw new IllegalArgumentException("failureOverpressureMPa must be positive");
        if (!(maxAcceleration >= 1)) throw new IllegalArgumentException("maxAcceleration must be >= 1");
        if (!(maxMagnitude > minMagnitude)) throw new IllegalArgumentException("maxMagnitude must exceed minMagnitude");
        if (!(lpMaxMagnitude > minMagnitude)) throw new IllegalArgumentException("lpMaxMagnitude must exceed minMagnitude");
        if (!(explosionMaxMagnitude > explosionMinMagnitude)) {
            throw new IllegalArgumentException("explosionMaxMagnitude must exceed explosionMinMagnitude");
        }
        if (!(rsamWindowSeconds > 0) || !(rateWindowSeconds > 0)) {
            throw new IllegalArgumentException("averaging windows must be positive");
        }
        if (!(tremorMeanDurationSeconds > 0) || !(swarmMeanDurationSeconds > 0)) {
            throw new IllegalArgumentException("mean durations must be positive");
        }
        if (stepIntervalTicks < 1) throw new IllegalArgumentException("stepIntervalTicks must be >= 1");
        if (sampleIntervalTicks < 0) throw new IllegalArgumentException("sampleIntervalTicks must be >= 0");
    }

    public static Builder builder(String volcanoId, BlockPos conduitTop) {
        return new Builder(volcanoId, conduitTop);
    }

    public static final class Builder {
        private final String volcanoId;
        private final BlockPos conduitTop;
        private double failureOverpressureMPa = 15;
        private double backgroundVtRate = 2e-4;
        private double vtPerMPa = 1.5;
        private double maxAcceleration = 20;
        private double maxEventRate = 2;
        private double backgroundLpRate = 1e-4;
        private double lpPerEruptionRate = 0.01;
        private double tremorEpisodeRate = 0.02;
        private double tremorMeanDurationSeconds = 60;
        private double tremorBaseMagnitude = 0.5;
        private double explosionRate = 0.05;
        private double swarmTriggerProbability = 0.02;
        private double swarmMeanDurationSeconds = 120;
        private double swarmRateMultiplier = 10;
        private double vtBValue = 1.0;
        private double swarmBValue = 1.5;
        private double lpBValue = 1.6;
        private double explosionBValue = 1.2;
        private double minMagnitude = 0;
        private double maxMagnitude = 5;
        private double lpMaxMagnitude = 3;
        private double explosionMinMagnitude = 1;
        private double explosionMaxMagnitude = 3.5;
        private double hypocenterSpread = 20;
        private double rsamWindowSeconds = 60;
        private double rateWindowSeconds = 120;
        private int stepIntervalTicks = 10;
        private int sampleIntervalTicks = 100;

        private Builder(String volcanoId, BlockPos conduitTop) {
            this.volcanoId = volcanoId;
            this.conduitTop = conduitTop;
        }

        public Builder failureOverpressureMPa(double v) { failureOverpressureMPa = v; return this; }
        public Builder backgroundVtRate(double v) { backgroundVtRate = v; return this; }
        public Builder vtPerMPa(double v) { vtPerMPa = v; return this; }
        public Builder maxAcceleration(double v) { maxAcceleration = v; return this; }
        public Builder maxEventRate(double v) { maxEventRate = v; return this; }
        public Builder backgroundLpRate(double v) { backgroundLpRate = v; return this; }
        public Builder lpPerEruptionRate(double v) { lpPerEruptionRate = v; return this; }
        public Builder tremorEpisodeRate(double v) { tremorEpisodeRate = v; return this; }
        public Builder tremorMeanDurationSeconds(double v) { tremorMeanDurationSeconds = v; return this; }
        public Builder tremorBaseMagnitude(double v) { tremorBaseMagnitude = v; return this; }
        public Builder explosionRate(double v) { explosionRate = v; return this; }
        public Builder swarmTriggerProbability(double v) { swarmTriggerProbability = v; return this; }
        public Builder swarmMeanDurationSeconds(double v) { swarmMeanDurationSeconds = v; return this; }
        public Builder swarmRateMultiplier(double v) { swarmRateMultiplier = v; return this; }
        public Builder vtBValue(double v) { vtBValue = v; return this; }
        public Builder swarmBValue(double v) { swarmBValue = v; return this; }
        public Builder lpBValue(double v) { lpBValue = v; return this; }
        public Builder explosionBValue(double v) { explosionBValue = v; return this; }
        public Builder minMagnitude(double v) { minMagnitude = v; return this; }
        public Builder maxMagnitude(double v) { maxMagnitude = v; return this; }
        public Builder lpMaxMagnitude(double v) { lpMaxMagnitude = v; return this; }
        public Builder explosionMinMagnitude(double v) { explosionMinMagnitude = v; return this; }
        public Builder explosionMaxMagnitude(double v) { explosionMaxMagnitude = v; return this; }
        public Builder hypocenterSpread(double v) { hypocenterSpread = v; return this; }
        public Builder rsamWindowSeconds(double v) { rsamWindowSeconds = v; return this; }
        public Builder rateWindowSeconds(double v) { rateWindowSeconds = v; return this; }
        public Builder stepIntervalTicks(int v) { stepIntervalTicks = v; return this; }
        public Builder sampleIntervalTicks(int v) { sampleIntervalTicks = v; return this; }

        public SeismicConfig build() {
            return new SeismicConfig(volcanoId, conduitTop, failureOverpressureMPa, backgroundVtRate, vtPerMPa,
                    maxAcceleration, maxEventRate, backgroundLpRate, lpPerEruptionRate, tremorEpisodeRate,
                    tremorMeanDurationSeconds, tremorBaseMagnitude, explosionRate, swarmTriggerProbability,
                    swarmMeanDurationSeconds, swarmRateMultiplier, vtBValue, swarmBValue, lpBValue, explosionBValue,
                    minMagnitude, maxMagnitude, lpMaxMagnitude, explosionMinMagnitude, explosionMaxMagnitude,
                    hypocenterSpread, rsamWindowSeconds, rateWindowSeconds, stepIntervalTicks, sampleIntervalTicks);
        }
    }
}
