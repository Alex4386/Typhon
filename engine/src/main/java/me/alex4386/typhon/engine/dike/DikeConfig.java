package me.alex4386.typhon.engine.dike;

import me.alex4386.typhon.engine.volcano.VolcanoScaling;

/**
 * Parameters of dike initiation and propagation. Physical quantities are in real units; the
 * model-world geometry uses {@link #metersPerBlock}.
 */
public final class DikeConfig {
    /** Engine ticks between steps. */
    public int stepIntervalTicks = 20;
    /** Physical seconds simulated per engine second while a dike propagates. */
    public double timeScale = 1.0;
    /** Real metres per block of horizontal model geometry (see {@link VolcanoScaling}). */
    public double metersPerBlock = VolcanoScaling.DEFAULT.metersPerBlock();

    // ── Initiation ──

    /**
     * How sealed the summit conduit is, in [0, 1]. 0 = open conduit: overpressure always vents at the
     * summit and no dikes form spontaneously; 1 = sealed: rupture through the chamber walls is likely.
     */
    public double conduitSealing = 0.5;
    /** Dikes may nucleate once overpressure exceeds this fraction of the roof tensile strength. */
    public double initiationPressureRatio = 0.85;
    /** Nucleation rate (per simulated second) at full strength and full sealing; grows quadratically. */
    public double maxInitiationRate = 1.0 / 60.0;
    public int maxConcurrentDikes = 1;
    /** Dikes nucleate within this horizontal distance of the chamber centre (blocks). */
    public double startOffsetBlocks = 8;

    // ── Mechanics ──

    public double shearModulusPa = 3e9;
    public double poissonRatio = 0.25;
    public double rockDensity = 2600;
    /** Observed dike thicknesses (m); the elastic opening estimate is clamped to this range. */
    public double minOpening = 0.2;
    public double maxOpening = 3.0;
    /** Maximum along-strike extent of a dike (m). */
    public double maxStrikeLength = 500;
    /** Lower bound on the height used for the pressure gradient (m), avoids singular starts. */
    public double minCharacteristicHeight = 200;
    /** Upper bound on tip speed (m/s). */
    public double maxSpeed = 5.0;
    /** Below this tip speed magma freezes faster than the dike advances: the dike stalls (m/s). */
    public double freezeSpeed = 0.01;
    /** The dike stalls when its driving pressure falls below this (MPa). */
    public double stallPressureMPa = 0.5;
    /** Longest advance integrated in one sub-step (m). */
    public double maxSubstepMeters = 50;

    // ── Path ──

    /** Strength of deflection down the edifice slope near the surface (dimensionless). */
    public double deflectionStrength = 3.0;
    /** Depth over which edifice stresses fade (m); deflection ∝ exp(−depth / scale). */
    public double edificeDepthScale = 1500;
    /** Half-width of the slope stencil on the terrain (blocks). */
    public int slopeSampleRadius = 8;
    /**
     * Stationary standard deviation of the random heading perturbation (dimensionless slope). The
     * perturbation is an Ornstein–Uhlenbeck process along the path, so dikes wander but stay roughly
     * vertical.
     */
    public double headingNoise = 0.1;
    /** Correlation length of the heading perturbation along the path (m). */
    public double headingCorrelationLength = 500;

    // ── Seismicity and output ──

    /** Mean number of VT hypocentres per km of tip advance. */
    public double hypocentersPerKm = 25;
    /** Horizontal scatter of hypocentres around the tip (blocks). */
    public double hypocenterJitterBlocks = 3;
    public int minFissureLength = 3;
    public int maxFissureLength = 60;
    /** Finished dikes kept for queries and deformation (oldest are dropped first). */
    public int maxRecordedDikes = 16;

    public static DikeConfig defaults() {
        return new DikeConfig();
    }

    public DikeConfig withScaling(VolcanoScaling scaling) {
        DikeConfig c = copy();
        c.metersPerBlock = scaling.metersPerBlock();
        return c;
    }

    public DikeConfig copy() {
        DikeConfig c = new DikeConfig();
        c.stepIntervalTicks = stepIntervalTicks;
        c.timeScale = timeScale;
        c.metersPerBlock = metersPerBlock;
        c.conduitSealing = conduitSealing;
        c.initiationPressureRatio = initiationPressureRatio;
        c.maxInitiationRate = maxInitiationRate;
        c.maxConcurrentDikes = maxConcurrentDikes;
        c.startOffsetBlocks = startOffsetBlocks;
        c.shearModulusPa = shearModulusPa;
        c.poissonRatio = poissonRatio;
        c.rockDensity = rockDensity;
        c.minOpening = minOpening;
        c.maxOpening = maxOpening;
        c.maxStrikeLength = maxStrikeLength;
        c.minCharacteristicHeight = minCharacteristicHeight;
        c.maxSpeed = maxSpeed;
        c.freezeSpeed = freezeSpeed;
        c.stallPressureMPa = stallPressureMPa;
        c.maxSubstepMeters = maxSubstepMeters;
        c.deflectionStrength = deflectionStrength;
        c.edificeDepthScale = edificeDepthScale;
        c.slopeSampleRadius = slopeSampleRadius;
        c.headingNoise = headingNoise;
        c.headingCorrelationLength = headingCorrelationLength;
        c.hypocentersPerKm = hypocentersPerKm;
        c.hypocenterJitterBlocks = hypocenterJitterBlocks;
        c.minFissureLength = minFissureLength;
        c.maxFissureLength = maxFissureLength;
        c.maxRecordedDikes = maxRecordedDikes;
        return c;
    }

    void validate() {
        if (stepIntervalTicks < 1) throw new IllegalArgumentException("stepIntervalTicks must be >= 1");
        requirePositive("timeScale", timeScale);
        requirePositive("metersPerBlock", metersPerBlock);
        if (!(conduitSealing >= 0 && conduitSealing <= 1)) {
            throw new IllegalArgumentException("conduitSealing must be in [0, 1]");
        }
        if (!(initiationPressureRatio > 0 && initiationPressureRatio < 1)) {
            throw new IllegalArgumentException("initiationPressureRatio must be in (0, 1)");
        }
        if (maxConcurrentDikes < 0) throw new IllegalArgumentException("maxConcurrentDikes must be >= 0");
        requirePositive("shearModulusPa", shearModulusPa);
        if (!(poissonRatio > 0 && poissonRatio < 0.5)) {
            throw new IllegalArgumentException("poissonRatio must be in (0, 0.5)");
        }
        if (!(minOpening > 0 && maxOpening >= minOpening)) throw new IllegalArgumentException("invalid opening bounds");
        requirePositive("maxStrikeLength", maxStrikeLength);
        requirePositive("minCharacteristicHeight", minCharacteristicHeight);
        requirePositive("maxSpeed", maxSpeed);
        requirePositive("maxSubstepMeters", maxSubstepMeters);
        requirePositive("edificeDepthScale", edificeDepthScale);
        requirePositive("headingCorrelationLength", headingCorrelationLength);
        if (slopeSampleRadius < 1) throw new IllegalArgumentException("slopeSampleRadius must be >= 1");
        if (minFissureLength < 1 || maxFissureLength < minFissureLength) {
            throw new IllegalArgumentException("invalid fissure length bounds");
        }
        if (maxRecordedDikes < 1) throw new IllegalArgumentException("maxRecordedDikes must be >= 1");
    }

    private static void requirePositive(String name, double value) {
        if (!(value > 0)) throw new IllegalArgumentException(name + " must be > 0: " + value);
    }
}
