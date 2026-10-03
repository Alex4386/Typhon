package me.alex4386.typhon.engine.lava;

/**
 * Physical constants and tuning knobs for {@link LavaFlow}.
 *
 * @param timeScale simulated seconds per engine second for flow (&gt; 1 speeds flows up for gameplay)
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
 * @param relaxation max fraction of the head difference moved to one neighbour per step (1/(n+1))
 * @param renderMinThickness thinner films are not shown as blocks
 * @param quenchRateKPerS physical cooling rate above which lava counts as quenched (glassy)
 * @param columnarMinThickness flows at least this thick that cool slowly form columnar joints
 * @param frontEventInterval ticks between {@link LavaEvents.LavaFlowFront} events
 * @param maxWaterEventsPerStep cap on {@link LavaEvents.LavaEnteredWater} events per step
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
 */
public record LavaConfig(
        double timeScale,
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
        int frontEventInterval,
        int maxWaterEventsPerStep,
        boolean crustEnabled,
        double crustConductivityWMK,
        double crustMinThickness,
        double crustDisruptionVelocity,
        double crustRenderThickness,
        double tubeMinRoofThickness,
        double tubeDrainThickness,
        double hyaloclastiteFraction,
        double littoralExplosionFluxM3s) {

    /** Flow-only configuration (crust, tube and coast parameters at their defaults). */
    public LavaConfig(double timeScale, double coolingScale, double densityKgM3, double specificHeatJKgK,
            double latentHeatJKg, double emissivity, double ambientC, double waterC, double waterHeatTransferWM2K,
            double groundConductivityWMK, double groundBoundaryLayerM, double minFlowThickness, double relaxation,
            double renderMinThickness, double quenchRateKPerS, double columnarMinThickness, int frontEventInterval,
            int maxWaterEventsPerStep) {
        this(timeScale, coolingScale, densityKgM3, specificHeatJKgK, latentHeatJKg, emissivity, ambientC, waterC,
                waterHeatTransferWM2K, groundConductivityWMK, groundBoundaryLayerM, minFlowThickness, relaxation,
                renderMinThickness, quenchRateKPerS, columnarMinThickness, frontEventInterval, maxWaterEventsPerStep,
                true, 1.0, 0.3, 1.0, 0.25, 1.0, 0.05, 0.5, 1.0);
    }

    /**
     * Defaults at physical speed. Crust: k = 1 W/m·K (vesicular basalt crust), so a quiet pond's
     * crust grows as ≈1.6 mm·√t[s] (≈0.45 m after a day, cf. Hon et al. 1994 at Kīlauea); crust over
     * flows faster than 1 m/s is disrupted (channels), slower flows roof over (tubes).
     */
    public static LavaConfig defaults() {
        return new LavaConfig(
                1.0, 1.0,
                2600, 1150, 4.0e5, 0.95,
                25, 15, 3000,
                2.0, 0.5,
                1e-3, 0.2, 0.02,
                1.0, 2.0,
                20, 32);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public LavaConfig withTimeScale(double timeScale) {
        return toBuilder().timeScale(timeScale).build();
    }

    public LavaConfig withCoolingScale(double coolingScale) {
        return toBuilder().coolingScale(coolingScale).build();
    }

    public LavaConfig withCrust(boolean enabled) {
        return toBuilder().crustEnabled(enabled).build();
    }

    /** Mutable copy for changing several parameters at once. */
    public static final class Builder {
        private double timeScale, coolingScale, densityKgM3, specificHeatJKgK, latentHeatJKg, emissivity;
        private double ambientC, waterC, waterHeatTransferWM2K, groundConductivityWMK, groundBoundaryLayerM;
        private double minFlowThickness, relaxation, renderMinThickness, quenchRateKPerS, columnarMinThickness;
        private int frontEventInterval, maxWaterEventsPerStep;
        private boolean crustEnabled;
        private double crustConductivityWMK, crustMinThickness, crustDisruptionVelocity, crustRenderThickness;
        private double tubeMinRoofThickness, tubeDrainThickness, hyaloclastiteFraction, littoralExplosionFluxM3s;

        private Builder(LavaConfig c) {
            timeScale = c.timeScale;
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
            frontEventInterval = c.frontEventInterval;
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
        }

        public Builder timeScale(double v) { timeScale = v; return this; }
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

        public LavaConfig build() {
            return new LavaConfig(timeScale, coolingScale, densityKgM3, specificHeatJKgK, latentHeatJKg, emissivity,
                    ambientC, waterC, waterHeatTransferWM2K, groundConductivityWMK, groundBoundaryLayerM,
                    minFlowThickness, relaxation, renderMinThickness, quenchRateKPerS, columnarMinThickness,
                    frontEventInterval, maxWaterEventsPerStep, crustEnabled, crustConductivityWMK, crustMinThickness,
                    crustDisruptionVelocity, crustRenderThickness, tubeMinRoofThickness, tubeDrainThickness,
                    hyaloclastiteFraction, littoralExplosionFluxM3s);
        }
    }
}
