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
        int maxWaterEventsPerStep) {

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

    public LavaConfig withTimeScale(double timeScale) {
        return new LavaConfig(timeScale, coolingScale, densityKgM3, specificHeatJKgK, latentHeatJKg, emissivity,
                ambientC, waterC, waterHeatTransferWM2K, groundConductivityWMK, groundBoundaryLayerM, minFlowThickness,
                relaxation, renderMinThickness, quenchRateKPerS, columnarMinThickness, frontEventInterval,
                maxWaterEventsPerStep);
    }

    public LavaConfig withCoolingScale(double coolingScale) {
        return new LavaConfig(timeScale, coolingScale, densityKgM3, specificHeatJKgK, latentHeatJKg, emissivity,
                ambientC, waterC, waterHeatTransferWM2K, groundConductivityWMK, groundBoundaryLayerM, minFlowThickness,
                relaxation, renderMinThickness, quenchRateKPerS, columnarMinThickness, frontEventInterval,
                maxWaterEventsPerStep);
    }
}
