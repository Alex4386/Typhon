package me.alex4386.typhon.engine.lava;

/**
 * Physical constants and tuning knobs for {@link LavaFlow}.
 *
 * <p>All lengths are real metres. The grid geometry comes from {@link #metersPerBlock}: a column is
 * {@code L × L} m, and one block of solidified rock is {@code L} m thick.
 *
 * @param coolingScale multiplier on heat loss (&gt; 1 makes flows solidify sooner)
 * @param densityKgM3 lava bulk density
 * @param specificHeatJKgK heat capacity above the liquidus
 * @param latentHeatJKg latent heat of crystallisation, released between liquidus and solidus
 * @param emissivity radiating surface emissivity
 * @param ambientC air and ground temperature
 * @param waterC water temperature
 * @param waterHeatTransferWM2K convective/boiling heat-transfer coefficient into water
 * @param groundConductivityWMK conductivity of the substrate
 * @param groundBoundaryLayerM thermal boundary layer used for basal conduction
 * @param minFlowThickness thinner films do not flow
 * @param relaxation max fraction of the head difference moved to one neighbour per step; when it
 *     binds, all directions of a cell are scaled by one common factor (keeping the physical split)
 * @param renderMinThickness films thinner than this fraction of a block are not shown as blocks
 * @param quenchRateKPerS physical cooling rate above which lava counts as quenched (glassy)
 * @param columnarMinThickness flows at least this thick that cool slowly form columnar joints
 * @param frontEventPeriodSeconds seconds between {@link LavaEvents.LavaFlowFront} events
 * @param maxWaterEventsPerStep cap on {@link LavaEvents.LavaOceanEntry} events per interval
 * @param crustEnabled whether quiet flows grow an insulating crust (lava tubes need it)
 * @param crustConductivityWMK thermal conductivity of the (vesicular) crust
 * @param crustMinThickness thinner columns cool as a whole instead of growing a crust
 * @param crustDisruptionVelocity mean flow speed (m/s) above which the crust is torn up and
 *     re-mixed (open channel instead of a roofed flow)
 * @param crustRenderThickness crust at least this thick shows as a magma-block skin
 * @param tubeMinRoofThickness a drained column keeps its crust as a tube roof only if the crust is
 *     at least this thick; thinner roofs collapse into the void
 * @param tubeDrainThickness melt left under a roof below this thickness counts as drained
 * @param hyaloclastiteFraction share of lava quenched in water that shatters and is shed down the
 *     steepest submerged slope (builds a delta front)
 * @param littoralExplosionFluxM3s entry flux into water of one column above which the entry is
 *     reported as explosive
 * @param metersPerBlock real metres per block (grid cell width and solid-block thickness)
 * @param waterEntryMinVolumeM3 submerged molten columns holding less lava than this are ignored by
 *     {@link LavaEvents.LavaOceanEntry} (quench films)
 * @param eventPeriodSeconds seconds between aggregated {@link LavaEvents.LavaOceanEntry} and
 *     {@link LavaEvents.LavaSolidified} events
 * @param maxSubsteps upper bound on flow sub-steps per engine step. The physical step is split so
 *     that the explicit flux stays within its stability limit ({@code Δt ≤ relaxation·L²/D},
 *     {@code D = ρgh³/3η}) for the most fluid moving lava, with {@code h} capped at
 *     {@code substepFlowThicknessM}; thicker channels and ponds, and anything beyond the bound, are
 *     levelled by the relaxation cap instead
 * @param substepFlowThicknessM flow thickness the sub-step size is resolved for (a typical flow lobe
 *     or front; pāhoehoe lobes are ~0.2–1 m)
 * @param coolingStepK largest temperature drop of one column per cooling sub-iteration; the heat
 *     balance of a long step is integrated in such sub-iterations (per column, so thin films
 *     that cool fast do not hold up the rest)
 */
public record LavaConfig(
        double coolingScale,
        double densityKgM3,
        double specificHeatJKgK,
        double latentHeatJKg,
        double emissivity,
        double ambientC,
        double waterC,
        double waterHeatTransferWM2K,
        double groundConductivityWMK,
        double groundBoundaryLayerM,
        double minFlowThickness,
        double relaxation,
        double renderMinThickness,
        double quenchRateKPerS,
        double columnarMinThickness,
        double frontEventPeriodSeconds,
        int maxWaterEventsPerStep,
        boolean crustEnabled,
        double crustConductivityWMK,
        double crustMinThickness,
        double crustDisruptionVelocity,
        double crustRenderThickness,
        double tubeMinRoofThickness,
        double tubeDrainThickness,
        double hyaloclastiteFraction,
        double littoralExplosionFluxM3s,
        double metersPerBlock,
        double waterEntryMinVolumeM3,
        double eventPeriodSeconds,
        int maxSubsteps,
        double substepFlowThicknessM,
        double coolingStepK) {

    public LavaConfig {
        if (!(metersPerBlock > 0)) throw new IllegalArgumentException("metersPerBlock must be > 0");
        if (!(eventPeriodSeconds > 0)) throw new IllegalArgumentException("eventPeriodSeconds must be > 0");
        if (maxSubsteps < 1) throw new IllegalArgumentException("maxSubsteps must be >= 1");
        if (!(substepFlowThicknessM > 0)) throw new IllegalArgumentException("substepFlowThicknessM must be > 0");
        if (!(coolingStepK > 0)) throw new IllegalArgumentException("coolingStepK must be > 0");
    }

    /** Flow-only configuration (crust, tube and coast parameters at their defaults). */
    public LavaConfig(double coolingScale, double densityKgM3, double specificHeatJKgK,
            double latentHeatJKg, double emissivity, double ambientC, double waterC, double waterHeatTransferWM2K,
            double groundConductivityWMK, double groundBoundaryLayerM, double minFlowThickness, double relaxation,
            double renderMinThickness, double quenchRateKPerS, double columnarMinThickness, double frontEventPeriodSeconds,
            int maxWaterEventsPerStep) {
        this(coolingScale, densityKgM3, specificHeatJKgK, latentHeatJKg, emissivity, ambientC, waterC,
                waterHeatTransferWM2K, groundConductivityWMK, groundBoundaryLayerM, minFlowThickness, relaxation,
                renderMinThickness, quenchRateKPerS, columnarMinThickness, frontEventPeriodSeconds, maxWaterEventsPerStep,
                true, 1.0, 0.3, 1.0, 0.25, 1.0, 0.05, 0.5, 1.0, 1.0, 0.01, 1.0, 8, 0.5, 25.0);
    }

    /**
     * Defaults at physical speed. Crust: k = 1 W/m·K (vesicular basalt crust), so a quiet pond's
     * crust grows as ≈1.6 mm·√t[s] (≈0.45 m after a day, cf. Hon et al. 1994 at Kīlauea); crust over
     * flows faster than 1 m/s is disrupted (channels), slower flows roof over (tubes).
     */
    public static LavaConfig defaults() {
        return new LavaConfig(
                1.0,
                2600, 1150, 4.0e5, 0.95,
                25, 15, 3000,
                2.0, 0.5,
                1e-3, 0.2, 0.02,
                1.0, 2.0,
                1.0, 32);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public LavaConfig withCoolingScale(double coolingScale) {
        return toBuilder().coolingScale(coolingScale).build();
    }

    public LavaConfig withMetersPerBlock(double metersPerBlock) {
        return toBuilder().metersPerBlock(metersPerBlock).build();
    }

    /** Area of one column (m²). */
    public double cellAreaM2() {
        return metersPerBlock * metersPerBlock;
    }

    /** Volume of one block (m³). */
    public double blockVolumeM3() {
        return metersPerBlock * metersPerBlock * metersPerBlock;
    }

    public LavaConfig withCrust(boolean enabled) {
        return toBuilder().crustEnabled(enabled).build();
    }

    /** Mutable copy for changing several parameters at once. */
    public static final class Builder {
        private double coolingScale, densityKgM3, specificHeatJKgK, latentHeatJKg, emissivity;
        private double ambientC, waterC, waterHeatTransferWM2K, groundConductivityWMK, groundBoundaryLayerM;
        private double minFlowThickness, relaxation, renderMinThickness, quenchRateKPerS, columnarMinThickness;
        private double frontEventPeriodSeconds;
        private int maxWaterEventsPerStep;
        private boolean crustEnabled;
        private double crustConductivityWMK, crustMinThickness, crustDisruptionVelocity, crustRenderThickness;
        private double tubeMinRoofThickness, tubeDrainThickness, hyaloclastiteFraction, littoralExplosionFluxM3s;
        private double metersPerBlock, waterEntryMinVolumeM3;
        private double eventPeriodSeconds;
        private int maxSubsteps;
        private double substepFlowThicknessM;
        private double coolingStepK;

        private Builder(LavaConfig c) {
            coolingScale = c.coolingScale;
            densityKgM3 = c.densityKgM3;
            specificHeatJKgK = c.specificHeatJKgK;
            latentHeatJKg = c.latentHeatJKg;
            emissivity = c.emissivity;
            ambientC = c.ambientC;
            waterC = c.waterC;
            waterHeatTransferWM2K = c.waterHeatTransferWM2K;
            groundConductivityWMK = c.groundConductivityWMK;
            groundBoundaryLayerM = c.groundBoundaryLayerM;
            minFlowThickness = c.minFlowThickness;
            relaxation = c.relaxation;
            renderMinThickness = c.renderMinThickness;
            quenchRateKPerS = c.quenchRateKPerS;
            columnarMinThickness = c.columnarMinThickness;
            frontEventPeriodSeconds = c.frontEventPeriodSeconds;
            maxWaterEventsPerStep = c.maxWaterEventsPerStep;
            crustEnabled = c.crustEnabled;
            crustConductivityWMK = c.crustConductivityWMK;
            crustMinThickness = c.crustMinThickness;
            crustDisruptionVelocity = c.crustDisruptionVelocity;
            crustRenderThickness = c.crustRenderThickness;
            tubeMinRoofThickness = c.tubeMinRoofThickness;
            tubeDrainThickness = c.tubeDrainThickness;
            hyaloclastiteFraction = c.hyaloclastiteFraction;
            littoralExplosionFluxM3s = c.littoralExplosionFluxM3s;
            metersPerBlock = c.metersPerBlock;
            waterEntryMinVolumeM3 = c.waterEntryMinVolumeM3;
            eventPeriodSeconds = c.eventPeriodSeconds;
            maxSubsteps = c.maxSubsteps;
            substepFlowThicknessM = c.substepFlowThicknessM;
            coolingStepK = c.coolingStepK;
        }

        public Builder coolingScale(double v) { coolingScale = v; return this; }
        public Builder minFlowThickness(double v) { minFlowThickness = v; return this; }
        public Builder renderMinThickness(double v) { renderMinThickness = v; return this; }
        public Builder crustEnabled(boolean v) { crustEnabled = v; return this; }
        public Builder crustConductivityWMK(double v) { crustConductivityWMK = v; return this; }
        public Builder crustMinThickness(double v) { crustMinThickness = v; return this; }
        public Builder crustDisruptionVelocity(double v) { crustDisruptionVelocity = v; return this; }
        public Builder crustRenderThickness(double v) { crustRenderThickness = v; return this; }
        public Builder tubeMinRoofThickness(double v) { tubeMinRoofThickness = v; return this; }
        public Builder tubeDrainThickness(double v) { tubeDrainThickness = v; return this; }
        public Builder hyaloclastiteFraction(double v) { hyaloclastiteFraction = v; return this; }
        public Builder littoralExplosionFluxM3s(double v) { littoralExplosionFluxM3s = v; return this; }
        public Builder relaxation(double v) { relaxation = v; return this; }
        public Builder metersPerBlock(double v) { metersPerBlock = v; return this; }
        public Builder waterEntryMinVolumeM3(double v) { waterEntryMinVolumeM3 = v; return this; }
        public Builder eventPeriodSeconds(double v) { eventPeriodSeconds = v; return this; }
        public Builder maxSubsteps(int v) { maxSubsteps = v; return this; }
        public Builder substepFlowThicknessM(double v) { substepFlowThicknessM = v; return this; }
        public Builder coolingStepK(double v) { coolingStepK = v; return this; }

        public LavaConfig build() {
            return new LavaConfig(coolingScale, densityKgM3, specificHeatJKgK, latentHeatJKg, emissivity,
                    ambientC, waterC, waterHeatTransferWM2K, groundConductivityWMK, groundBoundaryLayerM,
                    minFlowThickness, relaxation, renderMinThickness, quenchRateKPerS, columnarMinThickness,
                    frontEventPeriodSeconds, maxWaterEventsPerStep, crustEnabled, crustConductivityWMK, crustMinThickness,
                    crustDisruptionVelocity, crustRenderThickness, tubeMinRoofThickness, tubeDrainThickness,
                    hyaloclastiteFraction, littoralExplosionFluxM3s, metersPerBlock, waterEntryMinVolumeM3,
                    eventPeriodSeconds, maxSubsteps, substepFlowThicknessM, coolingStepK);
        }
    }
}
