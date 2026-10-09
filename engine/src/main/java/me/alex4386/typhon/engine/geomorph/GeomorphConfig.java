package me.alex4386.typhon.engine.geomorph;

/**
 * Parameters of {@link Geomorphology}. Physics is in real units (m, Pa, s); the world model is in
 * real metres already. The knobs below are numerical budgets or explicitly marked scale choices; the
 * material strengths themselves live in {@link RockStrength}.
 */
public final class GeomorphConfig {
    /** Seconds between steps. */
    public double stepPeriodSeconds = 20;

    // ── Slope stability ──
    /** Deepest slip plane examined by the shallow (infinite-slope) analysis (m). */
    public double maxSlabDepthM = 30;
    /** Columns flatter than this (tan β) are never examined. */
    public double minSlope = 0.1;
    /** Relaxation passes per step (failures cascading up- and downslope continue next step). */
    public int maxIterations = 6;
    /** Columns evaluated per step at most; the rest stay queued (deterministically, by key). */
    public int maxColumnsPerStep = 16_384;
    /** World-model tiles (32×32 columns) re-examined per step by the background sweep. */
    public int sweepTilesPerStep = 4;
    /** Downslope steps followed to measure a slope's relief (Culmann analysis). */
    public int reliefSteps = 48;
    /**
     * A failure cluster whose failed volume reaches this (m³) mobilises as a flow (debris avalanche,
     * debris flow or block-and-ash flow) instead of settling as talus beside its scar. Smaller rock falls
     * and slides run out with a roughly constant reach angle (H/L ≈ 0.6); the volume-dependent excess
     * mobility of rock avalanches, which a flow model reproduces, sets in from about 10⁵ m³: Scheidegger's
     * (1973, Rock Mechanics 5) regression {@code log(H/L) = 0.624 − 0.157 log V} is fitted to events above
     * that volume (cf. Hsü 1975, who places the long-runout "sturzstroms" above ~10⁶ m³).
     */
    public double avalancheMinVolumeM3 = 1e5;
    /**
     * Failures at least this large (m³) are reported one by one as {@code SlopeFailure} events (and
     * kept in the history); smaller ones are summed per step into {@code MassWasting}.
     */
    public double reportMinVolumeM3 = 100;
    /** Talus / ejecta / avalanche debris porosity (that of {@code MaterialTable.DEBRIS}). */
    public double debrisPorosity = 0.30;
    /** Failed masses at least this hot run as pyroclastic block-and-ash flows (°C). */
    public double hotCollapseTemperatureC = 400;
    /**
     * {@code tan φ} of wave-reworked loose slopes at the water surface over {@code tan φ} in air. Below the
     * wave base the dry angle applies: grains settle under water at about their dry angle (Courrech du Pont
     * et al. 2003). Shallow submarine tephra aprons stand at ~20–25° (Moore 1985).
     */
    public double submergedReposeFactor = me.alex4386.typhon.engine.world.ReposeRelaxation.DEFAULT_SUBMERGED_FACTOR;
    /** Depth (m) below which waves no longer rework loose slopes (storm wave base ~20–50 m); 0 = never. */
    public double waveBaseM = me.alex4386.typhon.engine.world.ReposeRelaxation.DEFAULT_WAVE_BASE_M;

    // ── Seismic shaking ──
    /**
     * Shaking below this PGA (g) is ignored: its pseudo-static load (k_h = 0.01) changes a slope's
     * factor of safety by about 1 %. It also sets how far a quake's shaking reaches (the distance where the
     * attenuation of {@link GroundMotion} falls to it), so a large earthquake shakes a wide area.
     */
    public double minPgaG = 0.02;

    // ── Hydrothermal alteration ──
    /**
     * Alteration time constant at {@link #alterationReferenceC} with fluids present (s of real time):
     * {@code dA/dt = (1 − A) · w / τ · exp(−E_a/R (1/T − 1/T_ref))}. Pervasive argillic alteration of
     * edifice rock takes centuries to millennia (Reid 2004, Geology 32; John et al. 2008, JVGR 175).
     */
    public double alterationTimescaleSeconds = 300 * 3.156e7;
    public double alterationReferenceC = 250;
    /** Activation energy (J/mol) of silicate dissolution / clay formation (≈50–80 kJ/mol; Brantley 2008). */
    public double alterationActivationJPerMol = 60_000;
    /** Depth (m) below the top of the altering body at which its temperature and fluids are sampled. */
    public double alterationSampleDepthM = 10;

    // ── Explosion craters ──
    /** Multiplier on the Sato &amp; Taniguchi (1997) crater diameter. Scale knob (1 = literature). */
    public double craterDiameterScale = 1.0;
    /** Smallest crater radius excavated (m); smaller explosions leave no resolvable crater. */
    public double minCraterRadiusM = 1.0;
    /** Largest crater radius excavated by one explosion (m). */
    public double maxCraterRadiusM = 600;

    // ── Caldera / pit collapse ──
    /** Ring-fault cohesion (Pa) of the chamber roof (rock mass; see {@link RockStrength}). */
    public double ringFaultCohesionPa = 6e5;
    public double ringFaultFrictionDeg = 40;
    /** Horizontal-to-vertical stress ratio acting on the ring fault, {@code ν/(1−ν)} for ν = 0.25. */
    public double ringFaultStressRatio = 1.0 / 3.0;
    /** Roofs with depth/diameter above this do not subside coherently (Roche &amp; Druitt 2001). */
    public double maxPistonAspectRatio = 1.0;

    public GeomorphConfig copy() {
        GeomorphConfig c = new GeomorphConfig();
        c.stepPeriodSeconds = stepPeriodSeconds;
        c.maxSlabDepthM = maxSlabDepthM;
        c.minSlope = minSlope;
        c.maxIterations = maxIterations;
        c.maxColumnsPerStep = maxColumnsPerStep;
        c.sweepTilesPerStep = sweepTilesPerStep;
        c.reliefSteps = reliefSteps;
        c.avalancheMinVolumeM3 = avalancheMinVolumeM3;
        c.reportMinVolumeM3 = reportMinVolumeM3;
        c.debrisPorosity = debrisPorosity;
        c.hotCollapseTemperatureC = hotCollapseTemperatureC;
        c.submergedReposeFactor = submergedReposeFactor;
        c.waveBaseM = waveBaseM;
        c.minPgaG = minPgaG;
        c.alterationTimescaleSeconds = alterationTimescaleSeconds;
        c.alterationReferenceC = alterationReferenceC;
        c.alterationActivationJPerMol = alterationActivationJPerMol;
        c.alterationSampleDepthM = alterationSampleDepthM;
        c.craterDiameterScale = craterDiameterScale;
        c.minCraterRadiusM = minCraterRadiusM;
        c.maxCraterRadiusM = maxCraterRadiusM;
        c.ringFaultCohesionPa = ringFaultCohesionPa;
        c.ringFaultFrictionDeg = ringFaultFrictionDeg;
        c.ringFaultStressRatio = ringFaultStressRatio;
        c.maxPistonAspectRatio = maxPistonAspectRatio;
        return c;
    }

    void validate() {
        if (!(stepPeriodSeconds > 0)) throw new IllegalArgumentException("stepPeriodSeconds must be > 0");
        if (!(maxSlabDepthM > 0)) throw new IllegalArgumentException("maxSlabDepthM must be > 0");
        if (!(submergedReposeFactor > 0 && submergedReposeFactor <= 1)) {
            throw new IllegalArgumentException("submergedReposeFactor must be in (0, 1]");
        }
        if (!(waveBaseM >= 0)) throw new IllegalArgumentException("waveBaseM must be >= 0");
        if (maxIterations < 1 || maxColumnsPerStep < 1 || sweepTilesPerStep < 0 || reliefSteps < 1) {
            throw new IllegalArgumentException("budgets must be positive");
        }
        if (!(debrisPorosity >= 0 && debrisPorosity < 1)) throw new IllegalArgumentException("debrisPorosity in [0,1)");
        if (!(alterationTimescaleSeconds > 0)) throw new IllegalArgumentException("alterationTimescaleSeconds must be > 0");
        if (!(craterDiameterScale >= 0)) throw new IllegalArgumentException("craterDiameterScale must be >= 0");
    }
}
