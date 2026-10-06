package me.alex4386.typhon.engine.tephra;

/**
 * Tunables for {@link TephraSubsystem}. Physical defaults use SI units with 1 block = 1 m; the
 * {@code *Scale} factors map real-world magnitudes onto Minecraft-sized volcanoes.
 *
 * <p>The subsystem copies the config when constructed, so later edits do not affect it.
 */
public final class TephraConfig {
    // ── Ballistics ──

    /** Bomb (clast) density, kg/m³. Vesicular scoria ~1500, dense lava ~2600. */
    public double bombDensity = 2500;
    /** Drag coefficient of irregular bombs (Mastin 2001 uses ~0.6–1.0). */
    public double dragCoefficient = 1.0;
    /** Air density near the ground, kg/m³ (assumed constant over Minecraft heights). */
    public double airDensity = 1.2;
    public double gravity = 9.81;
    /** Longest RK4 integration step for bomb trajectories, seconds (each engine step is sub-divided). */
    public double maxIntegrationStepSeconds = 0.0125;

    /**
     * Multiplier on the physical gas-thrust exit speed. Real Vulcanian bombs leave at 100–400 m/s and
     * land kilometres away; 0.35 keeps most bombs within a few hundred blocks.
     */
    public double ballisticSpeedScale = 0.35;
    /** Physical exit speed bounds before scaling, m/s. */
    public double minExitSpeed = 20;
    public double maxExitSpeed = 400;
    public double maxBombsPerSecond = 20;

    /**
     * Multiplier from real erupted mass to deposited model mass. Plume height is computed from the
     * real mass eruption rate, but bombs and ash fall carry {@code massScale} times that mass, so a
     * volcano modelled at {@code L} metres per block uses {@code 1/L³}.
     */
    public double massScale = 1.0;
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

    /** Bombs at least this large leave a block where they land. */
    public double minBlockDiameter = 0.5;
    /** Crater radius r = k · E^(1/3) (E in J); 0.0107 gives r ≈ 2 for a 1 m bomb at 100 m/s. */
    public double craterCoefficient = 0.0107;
    /** Conductive cooling time ∝ d², compressed to game time: seconds per m² of diameter². */
    public double coolingSecondsPerSquareMeter = 30;
    public double minCoolingSeconds = 5;
    /** Cooling time multiplier for bombs that land in water. */
    public double waterCoolingFactor = 0.1;

    // ── Ash plume and fall ──

    /** Ash transport runs every this many seconds. */
    public double ashStepSeconds = 20;
    /** Horizontal size of an ash grid cell, blocks. */
    public int cellSize = 8;
    /** Grid width and depth in cells (domain is centred on the first vent). */
    public int gridCells = 128;
    /** Horizontal eddy diffusivity, m²/s. */
    public double diffusivity = 20;
    /** Bulk density of fresh tephra deposits, kg/m³. */
    public double depositBulkDensity = 1000;
    /**
     * Plume height in blocks per real metre (Mastin et al. 2009 height × scale). 0.01 maps a 5 km
     * Vulcanian column to 50 blocks and a 15 km Plinian column to 150 blocks.
     */
    public double plumeHeightScale = 0.01;
    /** Plumes are capped at this world height. */
    public int worldTopY = 320;
    /** Settling velocity per {@link GrainClass} ordinal, m/s. */
    public double[] settlingVelocities = defaultSettlingVelocities();
    public AshPalette palette = AshPalette.defaults();
    /** Deposit thickness change (m) that triggers a block update in a cell. */
    public double depositUpdateThickness = 0.005;
    /** Relative per-column jitter on thickness thresholds, to break up cell edges. */
    public double depositJitter = 0.3;

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

    /** Initial wind in model units (blocks/s), bearing it blows towards and variability in [0, 1]. */
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
        c.ballisticSpeedScale = ballisticSpeedScale;
        c.minExitSpeed = minExitSpeed;
        c.maxExitSpeed = maxExitSpeed;
        c.maxBombsPerSecond = maxBombsPerSecond;
        c.massScale = massScale;
        c.maxFlightSeconds = maxFlightSeconds;
        c.bombMedianDiameter = bombMedianDiameter;
        c.bombDiameterSigma = bombDiameterSigma;
        c.minBombDiameter = minBombDiameter;
        c.maxBombDiameter = maxBombDiameter;
        c.launchAngleSigmaDeg = launchAngleSigmaDeg;
        c.maxLaunchAngleDeg = maxLaunchAngleDeg;
        c.minBlockDiameter = minBlockDiameter;
        c.craterCoefficient = craterCoefficient;
        c.coolingSecondsPerSquareMeter = coolingSecondsPerSquareMeter;
        c.minCoolingSeconds = minCoolingSeconds;
        c.waterCoolingFactor = waterCoolingFactor;
        c.ashStepSeconds = ashStepSeconds;
        c.cellSize = cellSize;
        c.gridCells = gridCells;
        c.diffusivity = diffusivity;
        c.depositBulkDensity = depositBulkDensity;
        c.plumeHeightScale = plumeHeightScale;
        c.worldTopY = worldTopY;
        c.settlingVelocities = settlingVelocities.clone();
        c.palette = palette;
        c.depositUpdateThickness = depositUpdateThickness;
        c.depositJitter = depositJitter;
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
        if (!(massScale > 0)) throw new IllegalArgumentException("massScale must be > 0");
        if (!(ashEventRefreshSeconds > 0)) throw new IllegalArgumentException("ashEventRefreshSeconds must be > 0");
        if (!(maxIntegrationStepSeconds > 0)) throw new IllegalArgumentException("maxIntegrationStepSeconds must be > 0");
        if (!(ashStepSeconds > 0) || !(ashEventSeconds > 0)) throw new IllegalArgumentException("ash periods must be > 0");
        if (cellSize < 1 || gridCells < 1) throw new IllegalArgumentException("grid must be non-empty");
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
