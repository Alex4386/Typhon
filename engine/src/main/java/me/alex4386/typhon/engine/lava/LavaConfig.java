package me.alex4386.typhon.engine.lava;

/**
 * Physical constants and tuning knobs for {@link LavaFlow}.
 *
 * <p>All lengths are in metres; the column width comes from the world model's spec. Each parameter is marked
 * by what it is: a material property or observation (with its source), a physical criterion, or a numerical
 * setting of the solver (no physical meaning; it only bounds work or resolution).
 *
 * @param coolingScale multiplier on heat loss; 1 is physical (an experiment knob, &gt; 1 solidifies flows sooner)
 * @param densityKgM3 lava bulk density: dense basaltic melt ≈2700, vesicular flowing lava 2000–2600 (2600)
 * @param specificHeatJKgK heat capacity of basaltic melt, ≈1100–1500 J/kg·K (1150)
 * @param latentHeatJKg latent heat of crystallisation, 3.5–4.2·10⁵ J/kg for basalt (4·10⁵), released between
 *     liquidus and solidus
 * @param emissivity emissivity of basaltic lava surfaces, 0.9–0.99 (0.95)
 * @param ambientC air and ground temperature
 * @param waterC water temperature
 * @param waterHeatTransferWM2K heat-transfer coefficient from quenching lava into water (boiling); poorly
 *     constrained, an order of magnitude estimate (3000)
 * @param groundConductivityWMK thermal conductivity of the substrate rock, 1.5–2.5 W/m·K (2)
 * @param groundVolumetricHeatCapacityJM3K ρc of the substrate rock, ≈2.0–2.6·10⁶ J/m³·K (ρ ≈ 2600–2800 kg/m³,
 *     c ≈ 800–1000 J/kg·K); with the conductivity it sets the conductive boundary layer that grows under a
 *     flow as {@code √(π κ t)}
 * @param minFlowThickness numerical: thinner films do not flow (a yield-strength stop happens well above it)
 * @param relaxation numerical: max fraction of the head difference moved to one neighbour per step; when it
 *     binds, all directions of a cell are scaled by one common factor (keeping the physical split)
 * @param quenchRateKPerS cooling rate above which a melt counts as quenched (glassy, obsidian for silicic
 *     melts); order of the critical cooling rates of silicate glass formation
 * @param frontEventPeriodSeconds reporting: seconds between {@link LavaEvents.LavaFlowFront} events
 * @param maxWaterEventsPerStep reporting: cap on {@link LavaEvents.LavaOceanEntry} events per interval
 * @param crustEnabled whether quiet flows grow an insulating crust (lava tubes need it)
 * @param crustConductivityWMK thermal conductivity of the vesicular crust (and of the melt for contact
 *     problems), ≈1 W/m·K
 * @param crustDisruptionVelocity mean flow speed (m/s) above which a young crust is torn up and re-mixed
 *     (open channel instead of a roofed flow); empirical, not derived
 * @param roofTensileStrengthPa tensile strength of a crust spanning a drained channel: a roof of thickness
 *     {@code t} over a span {@code w} stands while the bending stress of its own weight,
 *     {@code ρ g w² / (2 t)}, stays below it. Intact basalt ≈10 MPa; a cooling-jointed lava crust is taken an
 *     order of magnitude weaker, as rock masses are (cf. Schultz 1995) (1 MPa)
 * @param tubeDrainThickness numerical: melt left under a roof below this thickness counts as drained
 * @param hyaloclastiteFraction share of lava quenched in water that shatters and is shed down the steepest
 *     submerged slope (builds a delta front); not derived
 * @param littoralExplosionFluxM3s reporting: entry flux into water of one column above which the entry is
 *     reported as explosive
 * @param waterEntryMinVolumeM3 reporting: submerged molten columns holding less lava than this are ignored by
 *     {@link LavaEvents.LavaOceanEntry} (quench films)
 * @param eventPeriodSeconds reporting: seconds between aggregated {@link LavaEvents.LavaOceanEntry} and
 *     {@link LavaEvents.LavaSolidified} events
 * @param maxSubsteps numerical: upper bound on flow sub-steps per engine step. The physical step is split so
 *     that the explicit flux stays within its stability limit ({@code Δt ≤ relaxation·L²/D},
 *     {@code D = ρgh³/3η}) for the most fluid moving lava, with {@code h} capped at
 *     {@code substepFlowThicknessM}; thicker channels and ponds, and anything beyond the bound, are
 *     levelled by the relaxation cap instead
 * @param substepFlowThicknessM numerical: flow thickness the sub-step size is resolved for (a typical flow
 *     lobe or front; pāhoehoe lobes are ~0.2–1 m)
 * @param coolingStepK numerical: largest temperature drop of one column per cooling sub-iteration
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
        double groundVolumetricHeatCapacityJM3K,
        double minFlowThickness,
        double relaxation,
        double quenchRateKPerS,
        double frontEventPeriodSeconds,
        int maxWaterEventsPerStep,
        boolean crustEnabled,
        double crustConductivityWMK,
        double crustDisruptionVelocity,
        double roofTensileStrengthPa,
        double tubeDrainThickness,
        double hyaloclastiteFraction,
        double littoralExplosionFluxM3s,
        double waterEntryMinVolumeM3,
        double eventPeriodSeconds,
        int maxSubsteps,
        double substepFlowThicknessM,
        double coolingStepK) {

    public LavaConfig {
        if (!(eventPeriodSeconds > 0)) throw new IllegalArgumentException("eventPeriodSeconds must be > 0");
        if (maxSubsteps < 1) throw new IllegalArgumentException("maxSubsteps must be >= 1");
        if (!(substepFlowThicknessM > 0)) throw new IllegalArgumentException("substepFlowThicknessM must be > 0");
        if (!(coolingStepK > 0)) throw new IllegalArgumentException("coolingStepK must be > 0");
        if (!(groundVolumetricHeatCapacityJM3K > 0)) {
            throw new IllegalArgumentException("groundVolumetricHeatCapacityJM3K must be > 0");
        }
        if (!(roofTensileStrengthPa > 0)) throw new IllegalArgumentException("roofTensileStrengthPa must be > 0");
    }

    /** Flow-only configuration (crust, tube and coast parameters at their defaults). */
    public LavaConfig(double coolingScale, double densityKgM3, double specificHeatJKgK,
            double latentHeatJKg, double emissivity, double ambientC, double waterC, double waterHeatTransferWM2K,
            double groundConductivityWMK, double groundVolumetricHeatCapacityJM3K, double minFlowThickness,
            double relaxation, double quenchRateKPerS, double frontEventPeriodSeconds, int maxWaterEventsPerStep) {
        this(coolingScale, densityKgM3, specificHeatJKgK, latentHeatJKg, emissivity, ambientC, waterC,
                waterHeatTransferWM2K, groundConductivityWMK, groundVolumetricHeatCapacityJM3K, minFlowThickness,
                relaxation, quenchRateKPerS, frontEventPeriodSeconds, maxWaterEventsPerStep,
                true, 1.0, 1.0, 1.0e6, 0.05, 0.5, 1.0, 0.01, 1.0, 8, 0.5, 25.0);
    }

    /**
     * Defaults at physical speed. Crust: k = 1 W/m·K (vesicular basalt crust), so a quiet pond's crust grows as
     * ≈1.6 mm·√t[s] (≈0.45 m after a day, cf. Hon et al. 1994 at Kīlauea); crust over flows faster than 1 m/s is
     * disrupted (channels), slower flows roof over (tubes) where the roof can span the channel.
     */
    public static LavaConfig defaults() {
        return new LavaConfig(
                1.0,
                2600, 1150, 4.0e5, 0.95,
                25, 15, 3000,
                2.0, 2.4e6,
                1e-3, 0.2,
                1.0,
                1.0, 32);
    }

    /** Thinnest roof (m) that spans {@code spanM} under its own weight. */
    public double minRoofThickness(double spanM) {
        return densityKgM3 * 9.81 * spanM * spanM / (2 * roofTensileStrengthPa);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public LavaConfig withCoolingScale(double coolingScale) {
        return toBuilder().coolingScale(coolingScale).build();
    }

    public LavaConfig withCrust(boolean enabled) {
        return toBuilder().crustEnabled(enabled).build();
    }

    /** Mutable copy for changing several parameters at once. */
    public static final class Builder {
        private double coolingScale, densityKgM3, specificHeatJKgK, latentHeatJKg, emissivity;
        private double ambientC, waterC, waterHeatTransferWM2K, groundConductivityWMK, groundVolumetricHeatCapacityJM3K;
        private double minFlowThickness, relaxation, quenchRateKPerS;
        private double frontEventPeriodSeconds;
        private int maxWaterEventsPerStep;
        private boolean crustEnabled;
        private double crustConductivityWMK, crustDisruptionVelocity;
        private double roofTensileStrengthPa, tubeDrainThickness, hyaloclastiteFraction, littoralExplosionFluxM3s;
        private double waterEntryMinVolumeM3;
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
            groundVolumetricHeatCapacityJM3K = c.groundVolumetricHeatCapacityJM3K;
            minFlowThickness = c.minFlowThickness;
            relaxation = c.relaxation;
            quenchRateKPerS = c.quenchRateKPerS;
            frontEventPeriodSeconds = c.frontEventPeriodSeconds;
            maxWaterEventsPerStep = c.maxWaterEventsPerStep;
            crustEnabled = c.crustEnabled;
            crustConductivityWMK = c.crustConductivityWMK;
            crustDisruptionVelocity = c.crustDisruptionVelocity;
            roofTensileStrengthPa = c.roofTensileStrengthPa;
            tubeDrainThickness = c.tubeDrainThickness;
            hyaloclastiteFraction = c.hyaloclastiteFraction;
            littoralExplosionFluxM3s = c.littoralExplosionFluxM3s;
            waterEntryMinVolumeM3 = c.waterEntryMinVolumeM3;
            eventPeriodSeconds = c.eventPeriodSeconds;
            maxSubsteps = c.maxSubsteps;
            substepFlowThicknessM = c.substepFlowThicknessM;
            coolingStepK = c.coolingStepK;
        }

        public Builder coolingScale(double v) { coolingScale = v; return this; }
        public Builder minFlowThickness(double v) { minFlowThickness = v; return this; }
        public Builder crustEnabled(boolean v) { crustEnabled = v; return this; }
        public Builder crustConductivityWMK(double v) { crustConductivityWMK = v; return this; }
        public Builder crustDisruptionVelocity(double v) { crustDisruptionVelocity = v; return this; }
        public Builder roofTensileStrengthPa(double v) { roofTensileStrengthPa = v; return this; }
        public Builder tubeDrainThickness(double v) { tubeDrainThickness = v; return this; }
        public Builder hyaloclastiteFraction(double v) { hyaloclastiteFraction = v; return this; }
        public Builder littoralExplosionFluxM3s(double v) { littoralExplosionFluxM3s = v; return this; }
        public Builder relaxation(double v) { relaxation = v; return this; }
        public Builder waterEntryMinVolumeM3(double v) { waterEntryMinVolumeM3 = v; return this; }
        public Builder eventPeriodSeconds(double v) { eventPeriodSeconds = v; return this; }
        public Builder maxSubsteps(int v) { maxSubsteps = v; return this; }
        public Builder substepFlowThicknessM(double v) { substepFlowThicknessM = v; return this; }
        public Builder coolingStepK(double v) { coolingStepK = v; return this; }

        public LavaConfig build() {
            return new LavaConfig(coolingScale, densityKgM3, specificHeatJKgK, latentHeatJKg, emissivity,
                    ambientC, waterC, waterHeatTransferWM2K, groundConductivityWMK, groundVolumetricHeatCapacityJM3K,
                    minFlowThickness, relaxation, quenchRateKPerS, frontEventPeriodSeconds, maxWaterEventsPerStep,
                    crustEnabled, crustConductivityWMK, crustDisruptionVelocity, roofTensileStrengthPa,
                    tubeDrainThickness, hyaloclastiteFraction, littoralExplosionFluxM3s, waterEntryMinVolumeM3,
                    eventPeriodSeconds, maxSubsteps, substepFlowThicknessM, coolingStepK);
        }
    }
}
