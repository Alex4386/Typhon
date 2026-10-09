package me.alex4386.typhon.engine.tephra;

/**
 * Tunables for {@link TephraSubsystem}, in SI units (lengths in metres, masses in kg, times in seconds).
 *
 * <p>The subsystem copies the config when constructed, so later edits do not affect it.
 */
public final class TephraConfig {
    // ── Ballistics ──

    /** Bomb (clast) density, kg/m³. Vesicular scoria ~1500, dense lava ~2600. */
    public double bombDensity = 2500;
    /** Drag coefficient of irregular bombs (Mastin 2001 uses ~0.6–1.0). */
    public double dragCoefficient = 1.0;
    /** Sea-level air density, kg/m³ (ISA); bomb drag follows the standard atmosphere's density with height. */
    public double airDensity = 1.225;
    public double gravity = 9.81;
    /**
     * Longest RK4 integration step for bomb trajectories, seconds (each engine step is sub-divided); a bomb
     * also never moves more than half a ground column per sub-step, so it cannot skip over terrain.
     */
    public double maxIntegrationStepSeconds = 0.1;

    /** Physical exit speed bounds, m/s. */
    public double minExitSpeed = 20;
    public double maxExitSpeed = 400;
    public double maxBombsPerSecond = 20;

    /** Bombs still airborne after this long are dropped. */
    public double maxFlightSeconds = 120;

    /** Log-normal bomb diameter distribution (m). */
    public double bombMedianDiameter = 0.3;
    public double bombDiameterSigma = 0.7;
    public double minBombDiameter = 0.05;
    public double maxBombDiameter = 2.5;
    /** Standard deviation of launch zenith angle (degrees from vertical). */
    public double launchAngleSigmaDeg = 18;
    public double maxLaunchAngleDeg = 70;

    /** Impact crater radius r = k · E^(1/3) (m, E in J); 0.0107 gives r ≈ 2 m for a 1 m bomb at 100 m/s. */
    public double craterCoefficient = 0.0107;

    // ── Ash plume and fall ──

    /** Ash transport runs every this many seconds. */
    public double ashStepSeconds = 1.0;
    /** Horizontal size of an ash grid cell (m); rounded to whole surface columns. */
    public double cellSizeM = 100;
    /** Grid width and depth in cells (domain is centred on the first vent). */
    public int gridCells = 128;
    /** Horizontal eddy diffusivity, m²/s. */
    public double diffusivity = 20;
    /** Bulk density of fresh tephra deposits, kg/m³. */
    public double depositBulkDensity = 1000;
    /** Settling velocity per {@link GrainClass} ordinal, m/s. */
    public double[] settlingVelocities = defaultSettlingVelocities();
    /** A cell's new deposit is laid on the ground once it reaches this thickness (m). */
    public double depositUpdateThickness = 0.001;

    /** Ash-fall events are emitted every this many seconds, aggregated over square regions. */
    public double ashEventSeconds = 2.0;
    /** Region size for ash-fall events, in cells. */
    public int ashEventRegionCells = 4;
    /** Regions below both thresholds are not reported. */
    public double ashFallRateThreshold = 1e-5;
    public double ashLoadThreshold = 1e-3;
    /**
     * A region's ash fall is re-announced when it starts or clears (once, with zero rates), when its
     * fall rate or airborne load changes by this fraction, and at least every
     * {@link #ashEventRefreshSeconds}. Each event stays valid until the next one for the same region.
     */
    public double ashEventChangeFraction = 0.5;
    public double ashEventRefreshSeconds = 60;

    /** Initial wind (m/s), bearing it blows towards and variability in [0, 1]. */
    public double initialWindSpeed = 5;
    public double initialWindDirectionRad = 0;
    public double initialWindVariability = 0;
    /** Airborne mass below this (kg) is discarded once the phase has ended. */
    public double minAirborneMass = 1;

    /** Lightning flash rate per (kg/s) of mass eruption rate, flashes/s. */
    public double lightningPerMassRate = 1e-5;
    public double maxLightningPerSecond = 2;
    public double lightningMinMassEruptionRate = 1e4;

    public static double[] defaultSettlingVelocities() {
        double[] v = new double[GrainClass.COUNT];
        for (GrainClass c : GrainClass.values()) v[c.ordinal()] = c.defaultSettlingVelocity();
        return v;
    }

    public TephraConfig copy() {
        TephraConfig c = new TephraConfig();
        c.bombDensity = bombDensity;
        c.dragCoefficient = dragCoefficient;
        c.airDensity = airDensity;
        c.gravity = gravity;
        c.maxIntegrationStepSeconds = maxIntegrationStepSeconds;
        c.minExitSpeed = minExitSpeed;
        c.maxExitSpeed = maxExitSpeed;
        c.maxBombsPerSecond = maxBombsPerSecond;
        c.maxFlightSeconds = maxFlightSeconds;
        c.bombMedianDiameter = bombMedianDiameter;
        c.bombDiameterSigma = bombDiameterSigma;
        c.minBombDiameter = minBombDiameter;
        c.maxBombDiameter = maxBombDiameter;
        c.launchAngleSigmaDeg = launchAngleSigmaDeg;
        c.maxLaunchAngleDeg = maxLaunchAngleDeg;
        c.craterCoefficient = craterCoefficient;
        c.ashStepSeconds = ashStepSeconds;
        c.cellSizeM = cellSizeM;
        c.gridCells = gridCells;
        c.diffusivity = diffusivity;
        c.depositBulkDensity = depositBulkDensity;
        c.settlingVelocities = settlingVelocities.clone();
        c.depositUpdateThickness = depositUpdateThickness;
        c.ashEventSeconds = ashEventSeconds;
        c.ashEventRegionCells = ashEventRegionCells;
        c.ashFallRateThreshold = ashFallRateThreshold;
        c.ashLoadThreshold = ashLoadThreshold;
        c.ashEventChangeFraction = ashEventChangeFraction;
        c.ashEventRefreshSeconds = ashEventRefreshSeconds;
        c.initialWindSpeed = initialWindSpeed;
        c.initialWindDirectionRad = initialWindDirectionRad;
        c.initialWindVariability = initialWindVariability;
        c.minAirborneMass = minAirborneMass;
        c.lightningPerMassRate = lightningPerMassRate;
        c.maxLightningPerSecond = maxLightningPerSecond;
        c.lightningMinMassEruptionRate = lightningMinMassEruptionRate;
        return c;
    }

    public void validate() {
        if (!(ashEventRefreshSeconds > 0)) throw new IllegalArgumentException("ashEventRefreshSeconds must be > 0");
        if (!(maxIntegrationStepSeconds > 0)) throw new IllegalArgumentException("maxIntegrationStepSeconds must be > 0");
        if (!(ashStepSeconds > 0) || !(ashEventSeconds > 0)) throw new IllegalArgumentException("ash periods must be > 0");
        if (!(cellSizeM > 0) || gridCells < 1) throw new IllegalArgumentException("grid must be non-empty");
        if (settlingVelocities.length != GrainClass.COUNT) {
            throw new IllegalArgumentException("settlingVelocities needs " + GrainClass.COUNT + " entries");
        }
        for (double v : settlingVelocities) {
            if (!(v > 0)) throw new IllegalArgumentException("settling velocities must be positive");
        }
        if (!(bombDensity > 0) || !(airDensity >= 0) || !(dragCoefficient >= 0)) {
            throw new IllegalArgumentException("invalid bomb physics parameters");
        }
    }
}
