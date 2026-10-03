package me.alex4386.typhon.engine.massflow;

/**
 * Parameters for a mass-flow field. Physics is in real units; {@link #metersPerBlock} maps the block
 * grid onto real lengths (pass {@code VolcanoScaling.metersPerBlock()}), so cells are
 * {@code metersPerBlock} wide and one block of deposit is {@code metersPerBlock} thick.
 *
 * <p>Rheology is Voellmy–Salm: basal resistance {@code μ g cosθ + g u²/ξ} per unit mass. Typical
 * back-fitted values: dense PDCs / block-and-ash flows μ ≈ 0.1–0.3, ξ ≈ 200–1000 m/s²; lahars and
 * debris flows μ ≈ 0.02–0.15, ξ ≈ 200–2000 m/s² (water-rich lahars toward the low-drag end, like
 * floods with a friction factor of ~0.01).
 */
public final class MassFlowConfig {
    // ── Grid & time ──
    /** Real metres per block (cell width and block thickness). */
    public double metersPerBlock = 1.0;
    /** Simulated seconds per engine second. */
    public double timeScale = 1.0;
    public int stepIntervalTicks = 2;
    public double gravity = 9.81;
    /** Courant number for sub-stepping: {@code dt ≤ cfl · dx / (|u| + √(g h))}. */
    public double cfl = 0.4;
    public int maxSubsteps = 64;
    /** Hard speed cap (m/s), a guard against numerical blow-up. */
    public double maxSpeed = 150;

    // ── Rheology ──
    /** Coulomb friction coefficient μ (tangent of the angle the flow can rest on). */
    public double frictionCoefficient;
    /** Turbulent (velocity-squared) coefficient ξ (m/s²); larger means less drag. */
    public double turbulenceCoefficient;

    // ── Deposition ──
    /** Below this depth (m) the flow comes to rest and deposits en masse. */
    public double minDepth = 0.02;
    /** Background sedimentation: fraction {@code dt/τ} of the flow settles out per step (s). */
    public double sedimentationTimescale;
    /** En-masse freezing of slow flow: extra rate {@code (1/τ_stop)·(1 − u/u_stop)} below {@code stopSpeed}. */
    public double stopTimescale;
    public double stopSpeed;
    /** Deposit thickness per unit thickness of deposited flow (PDC bulk density / deposit density). */
    public double depositThicknessFactor = 0.7;
    /** Veneer thresholds, as fractions of a block of deposit. */
    public double thinVeneerBlocks = 0.1;
    public double veneerBlocks = 0.4;

    // ── PDC ──
    public double ambientC = 15;
    /** Cooling by air entrainment: {@code dT/dt = −(T − T_a)/τ} (s). */
    public double coolingTimescale = 900;
    /** Mass lost to steam/sinking while running over water: rate {@code 1/τ} (s). */
    public double waterLossTimescale = 30;
    /** Deposits laid down at or above this temperature weld. */
    public double weldingTemperatureC = 600;
    public int maxSteamEventsPerStep = 8;

    // ── Lahar ──
    /** Friction rises from {@link #frictionCoefficient} (clear water flood) to this at max sediment. */
    public double frictionAtMaxSediment;
    public double maxSedimentFraction = 0.6;
    /** Bed erosion: depth eroded per metre of flow travel per unit (1 − c/c_max), when faster than {@link #erosionSpeed}. */
    public double erosionCoefficient = 2e-3;
    public double erosionSpeed = 1.5;
    /** Deposit porosity: deposit thickness = deposited sediment volume / (1 − porosity). */
    public double porosity = 0.35;
    /** Rapid settling where a lahar enters standing water (s). */
    public double waterDepositionTimescale = 60;
    /** Deposits laid down faster than this are coarse (gravel), slower ones fine (mud). */
    public double coarseSpeed = 3;
    /**
     * Rain-triggered failure: loose deposit soaks up rain until its pores are full
     * ({@code rain ≥ porosity · thickness}); on slopes steeper than {@link #rainMinSlope} it then fails
     * as a slug of the saturated deposit plus concentrated runoff of {@code rainFailureWaterRatio ×
     * thickness}.
     */
    public double rainFailureWaterRatio = 0.3;
    /** Rain mobilises loose deposit only on bed slopes steeper than this (tan θ). */
    public double rainMinSlope = 0.1;
    /** Loose deposit thinner than this (m) is not mobilised by rain. */
    public double rainMinErodible = 0.05;

    // ── Telemetry ──
    public int frontEventInterval = 20;
    public int maxReportedCells = 256;

    /** Dense pyroclastic density current (block-and-ash flow / dense basal underflow). */
    public static MassFlowConfig pdc() {
        MassFlowConfig c = new MassFlowConfig();
        c.frictionCoefficient = 0.18;
        c.turbulenceCoefficient = 500;
        c.sedimentationTimescale = 600;
        c.stopTimescale = 5;
        c.stopSpeed = 3;
        c.frictionAtMaxSediment = c.frictionCoefficient;
        return c;
    }

    /** Lahar (hyperconcentrated flow to debris flow, depending on sediment fraction). */
    public static MassFlowConfig lahar() {
        MassFlowConfig c = new MassFlowConfig();
        c.frictionCoefficient = 0.03;
        c.frictionAtMaxSediment = 0.12;
        c.turbulenceCoefficient = 1000;
        c.sedimentationTimescale = 3600;
        c.stopTimescale = 20;
        c.stopSpeed = 0.5;
        return c;
    }

    public MassFlowConfig copy() {
        MassFlowConfig c = new MassFlowConfig();
        c.metersPerBlock = metersPerBlock;
        c.timeScale = timeScale;
        c.stepIntervalTicks = stepIntervalTicks;
        c.gravity = gravity;
        c.cfl = cfl;
        c.maxSubsteps = maxSubsteps;
        c.maxSpeed = maxSpeed;
        c.frictionCoefficient = frictionCoefficient;
        c.turbulenceCoefficient = turbulenceCoefficient;
        c.minDepth = minDepth;
        c.sedimentationTimescale = sedimentationTimescale;
        c.stopTimescale = stopTimescale;
        c.stopSpeed = stopSpeed;
        c.depositThicknessFactor = depositThicknessFactor;
        c.thinVeneerBlocks = thinVeneerBlocks;
        c.veneerBlocks = veneerBlocks;
        c.ambientC = ambientC;
        c.coolingTimescale = coolingTimescale;
        c.waterLossTimescale = waterLossTimescale;
        c.weldingTemperatureC = weldingTemperatureC;
        c.maxSteamEventsPerStep = maxSteamEventsPerStep;
        c.frictionAtMaxSediment = frictionAtMaxSediment;
        c.maxSedimentFraction = maxSedimentFraction;
        c.erosionCoefficient = erosionCoefficient;
        c.erosionSpeed = erosionSpeed;
        c.porosity = porosity;
        c.waterDepositionTimescale = waterDepositionTimescale;
        c.coarseSpeed = coarseSpeed;
        c.rainFailureWaterRatio = rainFailureWaterRatio;
        c.rainMinSlope = rainMinSlope;
        c.rainMinErodible = rainMinErodible;
        c.frontEventInterval = frontEventInterval;
        c.maxReportedCells = maxReportedCells;
        return c;
    }

    void validate() {
        requirePositive("metersPerBlock", metersPerBlock);
        requirePositive("timeScale", timeScale);
        requirePositive("gravity", gravity);
        requirePositive("cfl", cfl);
        requirePositive("maxSpeed", maxSpeed);
        requirePositive("turbulenceCoefficient", turbulenceCoefficient);
        requirePositive("minDepth", minDepth);
        requirePositive("sedimentationTimescale", sedimentationTimescale);
        requirePositive("stopTimescale", stopTimescale);
        requirePositive("depositThicknessFactor", depositThicknessFactor);
        requirePositive("coolingTimescale", coolingTimescale);
        requirePositive("waterLossTimescale", waterLossTimescale);
        requirePositive("waterDepositionTimescale", waterDepositionTimescale);
        requirePositive("maxSedimentFraction", maxSedimentFraction);
        if (stepIntervalTicks < 1) throw new IllegalArgumentException("stepIntervalTicks must be >= 1");
        if (maxSubsteps < 1) throw new IllegalArgumentException("maxSubsteps must be >= 1");
        if (frictionCoefficient < 0 || frictionAtMaxSediment < 0) {
            throw new IllegalArgumentException("friction coefficients must be >= 0");
        }
        if (stopSpeed < 0) throw new IllegalArgumentException("stopSpeed must be >= 0");
        if (rainFailureWaterRatio < 0) throw new IllegalArgumentException("rainFailureWaterRatio must be >= 0");
        if (!(porosity >= 0 && porosity < 1)) throw new IllegalArgumentException("porosity must be in [0, 1)");
        if (frontEventInterval < 0 || maxReportedCells < 0) throw new IllegalArgumentException("telemetry limits must be >= 0");
    }

    private static void requirePositive(String name, double value) {
        if (!(value > 0)) throw new IllegalArgumentException(name + " must be > 0: " + value);
    }
}
