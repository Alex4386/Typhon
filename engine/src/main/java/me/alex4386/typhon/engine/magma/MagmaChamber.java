package me.alex4386.typhon.engine.magma;

import com.google.gson.JsonObject;
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.magma.MagmaCommands.InjectRecharge;
import me.alex4386.typhon.engine.magma.MagmaCommands.MagmaCommand;
import me.alex4386.typhon.engine.magma.MagmaCommands.SetSupplyRate;
import me.alex4386.typhon.engine.magma.MagmaCommands.StartEruption;
import me.alex4386.typhon.engine.magma.MagmaCommands.StopEruption;
import me.alex4386.typhon.engine.magma.MagmaEvents.Cause;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.random.SimRandom;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.volcano.MagmaState;

/**
 * Lumped (0-D) magma chamber.
 *
 * <p><b>Pressure.</b> The chamber is an elastic reservoir of volume {@code V} and effective
 * compressibility {@code β = β₀ + α/P_abs} (bubble-free system plus exsolved-gas volume fraction
 * {@code α}). Net inflow changes overpressure as {@code dP/dt = (Q_in − Q_out) / (V β)}. Recharge
 * comes from a steady deep supply with log-normal fluctuations and from {@link InjectRecharge}
 * pulses.
 *
 * <p><b>Eruption.</b> When overpressure exceeds the roof's tensile strength an eruption starts and
 * magma leaves through a cylindrical conduit following Poiseuille flow,
 * {@code Q_out = π r⁴ P / (8 μ L)}, where {@code μ} is the bulk magma viscosity — or a much lower
 * gas-dominated viscosity when dissolved water exceeds the fragmentation threshold. Pressure is
 * integrated exactly ({@code P → P_eq + (P − P_eq) e^(−t/τ)}, {@code τ = Vβ/c}). The eruption ends
 * when overpressure falls below the end threshold; if supply keeps it above (viscous conduit, high
 * supply) the eruption is persistent.
 *
 * <p><b>Thermal and chemical evolution.</b> Temperature relaxes towards the wall-rock temperature
 * and is reset by mixing with recharge. Crystal fraction follows a linear liquidus–solidus
 * interpolation (liquidus falls with SiO₂). Fractional crystallisation of a mafic assemblage
 * enriches the residual melt in SiO₂ (mass balance) and in water, which is capped by its solubility
 * {@code ≈ 0.411 √P[MPa]} wt%; the excess exsolves and slowly vents. A chamber left alone therefore
 * cools, crystallises and becomes more silicic, wetter and more explosive.
 *
 * <p><b>Time.</b> Physical time runs {@code dormantTimeScale} times faster than simulated time while
 * the chamber is quiet and {@code eruptiveTimeScale} times faster during eruptions, so dormancy that
 * takes centuries in nature passes in an hour of play. All {@link MagmaState} rates are per
 * simulated second.
 */
public final class MagmaChamber implements Subsystem, MagmaState {
    static final double ROCK_DENSITY = 2600;
    static final double MAGMA_DENSITY = 2500;
    static final double GRAVITY = 9.81;
    static final double WATER_MOLAR_MASS = 0.018;
    static final double GAS_CONSTANT = 8.314;
    static final double SOLIDUS_C = 700;
    static final double MAX_CRYSTAL_FRACTION = 0.58;
    static final double MAX_MELT_SILICA = 77;

    private final MagmaChamberConfig config;

    private double overpressure;
    private double temperature;
    private double bulkSilica;
    private double bulkWater;
    private double supplyRate;
    private boolean erupting;
    private long eruptionStartTick;
    private double eruptedVolume;
    private double eruptionRate;
    private double overpressureRate;
    private boolean pendingStart;
    private boolean pendingStop;
    private boolean pendingFlank;

    public MagmaChamber(MagmaChamberConfig config) {
        this.config = config;
        this.overpressure = config.initialOverpressureMPa();
        this.temperature = config.initialTemperatureC();
        this.bulkSilica = config.initialSilicaWt();
        this.bulkWater = config.initialWaterWt();
        this.supplyRate = config.supplyRate();
    }

    public MagmaChamberConfig config() {
        return config;
    }

    @Override
    public String id() {
        return "magma:" + config.volcanoId();
    }

    @Override
    public int interval() {
        return config.stepIntervalTicks();
    }

    @Override
    public void registerCommands(CommandBus bus) {
        bus.register(SetSupplyRate.class, this::handleIfTargeted);
        bus.register(InjectRecharge.class, this::handleIfTargeted);
        bus.register(StartEruption.class, this::handleIfTargeted);
        bus.register(StopEruption.class, this::handleIfTargeted);
    }

    private void handleIfTargeted(MagmaCommand command) {
        if (command.volcanoId().equals(config.volcanoId())) handle(command);
    }

    void handle(MagmaCommand command) {
        switch (command) {
            case SetSupplyRate c -> supplyRate = c.supplyRate();
            case InjectRecharge c -> inject(c.volume(), c.temperatureC(), c.silicaWt(), c.waterWt());
            case StartEruption c -> {
                pendingStart = true;
                pendingStop = false;
            }
            case StopEruption c -> {
                pendingStop = true;
                pendingStart = false;
            }
        }
    }

    @Override
    public void step(StepContext context) {
        long tick = context.tick();
        double dt = context.dtSeconds();

        applyOverrides(context);

        double scale = erupting ? config.eruptiveTimeScale() : config.dormantTimeScale();
        double physicalDt = dt * scale;
        double supply = currentSupply(context.random());
        double inflow = supply * physicalDt;

        mix(inflow, config.rechargeTemperatureC(), config.rechargeSilicaWt(), config.rechargeWaterWt());
        temperature = config.wallTemperatureC()
                + (temperature - config.wallTemperatureC()) * Math.exp(-physicalDt / config.coolingTimescale());
        double phi = crystalFraction();
        bulkWater -= exsolvedWaterWt() * (1 - phi) * (1 - Math.exp(-physicalDt / config.degassingTimescale()));

        double stiffness = config.volume() * effectiveCompressibility(); // m³ per MPa
        double previous = overpressure;
        double erupted = 0;

        if (erupting) {
            double conductance = conduitConductance(); // m³/s per MPa
            if (overpressure > 0) {
                conductance = Math.min(conductance, config.maxEruptionRate() / overpressure);
            }
            double equilibrium = supply / conductance;
            double tau = stiffness / conductance;
            overpressure = equilibrium + (overpressure - equilibrium) * Math.exp(-physicalDt / tau);
            erupted = Math.max(0, inflow - stiffness * (overpressure - previous));
            eruptedVolume += erupted;
            if (overpressure <= config.eruptionEndOverpressureMPa()) {
                endEruption(context, Cause.AUTOMATIC);
            }
        } else {
            overpressure += inflow / stiffness;
            if (overpressure >= config.tensileStrengthMPa()) {
                startEruption(context, Cause.AUTOMATIC);
            }
        }

        eruptionRate = erupting ? erupted / dt : 0;
        overpressureRate = (overpressure - previous) / dt;

        int sampleInterval = config.sampleIntervalTicks();
        if (sampleInterval > 0 && Math.floorDiv(tick, sampleInterval) != Math.floorDiv(tick - interval(), sampleInterval)) {
            context.outbox().emit(sample(tick));
        }
    }

    /**
     * Starts an eruption at the current overpressure on the chamber's next step because magma reached
     * the surface through a dike (no artificial pressure boost, unlike a forced start).
     */
    public void requestFlankEruption() {
        pendingFlank = true;
    }

    private void applyOverrides(StepContext context) {
        if (pendingFlank) {
            pendingFlank = false;
            if (!erupting && overpressure > config.eruptionEndOverpressureMPa()) {
                startEruption(context, Cause.DIKE);
            }
        }
        if (pendingStop) {
            pendingStop = false;
            if (erupting) {
                overpressure = Math.min(overpressure, config.eruptionEndOverpressureMPa());
                endEruption(context, Cause.FORCED);
            }
        }
        if (pendingStart) {
            pendingStart = false;
            if (!erupting) {
                overpressure = Math.max(overpressure, config.tensileStrengthMPa());
                startEruption(context, Cause.FORCED);
            }
        }
    }

    private void startEruption(StepContext context, Cause cause) {
        erupting = true;
        eruptionStartTick = context.tick();
        eruptedVolume = 0;
        context.outbox().emit(new MagmaEvents.EruptionStarted(context.tick(), config.volcanoId(), overpressure, cause));
    }

    private void endEruption(StepContext context, Cause cause) {
        erupting = false;
        eruptionRate = 0;
        context.outbox().emit(new MagmaEvents.EruptionEnded(
                context.tick(), config.volcanoId(), eruptedVolume, context.tick() - eruptionStartTick, cause));
    }

    private double currentSupply(SimRandom random) {
        double sigma = config.supplyVariability();
        if (supplyRate == 0 || sigma == 0) return supplyRate;
        return supplyRate * Math.exp(sigma * random.nextGaussian() - 0.5 * sigma * sigma);
    }

    private void inject(double volume, double temperatureC, double silicaWt, double waterWt) {
        double stiffness = config.volume() * effectiveCompressibility();
        mix(volume, temperatureC, silicaWt, waterWt);
        overpressure += volume / stiffness;
    }

    private void mix(double volume, double temperatureC, double silicaWt, double waterWt) {
        if (volume <= 0) return;
        double f = Math.min(1, volume / config.volume());
        temperature += f * (temperatureC - temperature);
        bulkSilica += f * (silicaWt - bulkSilica);
        bulkWater += f * (waterWt - bulkWater);
    }

    private MagmaEvents.ChamberSample sample(long tick) {
        return new MagmaEvents.ChamberSample(tick, config.volcanoId(), overpressure, overpressureRate, temperature,
                silicaWt(), waterWt(), exsolvedWaterWt(), crystalFraction(), viscosityLog10(), supplyRate,
                eruptionRate, eruptedVolume, erupting);
    }

    /**
     * Removes magma into an intrusion (dike or sill) without erupting it. Overpressure drops by
     * {@code volume / (V β)}, the same elastic relation that recharge raises it by.
     *
     * @return the overpressure drop (MPa)
     */
    public double withdraw(double volume) {
        if (!(volume > 0)) return 0;
        double drop = volume / (config.volume() * effectiveCompressibility());
        overpressure -= drop;
        return drop;
    }

    // ── Derived physics ──

    /** Lithostatic pressure at the chamber's physical depth (MPa). */
    public double lithostaticPressureMPa() {
        return ROCK_DENSITY * GRAVITY * config.lithostaticDepth() / 1e6;
    }

    /** Liquidus temperature for the bulk composition (°C). */
    public double liquidusC() {
        return liquidusC(bulkSilica);
    }

    static double liquidusC(double silicaWt) {
        return 1250 - 9 * (MeltViscosity.clamp(silicaWt, 45, MAX_MELT_SILICA) - 48);
    }

    /** H₂O solubility in the melt at chamber pressure (wt%). */
    public double waterSolubilityWt() {
        return 0.411 * Math.sqrt(Math.max(0.1, lithostaticPressureMPa() + overpressure));
    }

    /** Water exsolved as a gas phase, per unit melt (wt%). */
    public double exsolvedWaterWt() {
        return Math.max(0, meltTotalWater() - waterSolubilityWt());
    }

    private double meltTotalWater() {
        return bulkWater / (1 - crystalFraction());
    }

    /** Volume fraction of exsolved gas in the magma. */
    public double gasVolumeFraction() {
        double x = exsolvedWaterWt() / 100;
        if (x <= 0) return 0;
        double absolutePa = (lithostaticPressureMPa() + Math.max(0, overpressure)) * 1e6;
        double gasDensity = absolutePa * WATER_MOLAR_MASS / (GAS_CONSTANT * (temperature + 273.15));
        double ratio = x * MAGMA_DENSITY / gasDensity;
        return ratio / (1 + ratio);
    }

    /** Compressibility of chamber + magma including exsolved gas (1/MPa). */
    public double effectiveCompressibility() {
        double absolute = lithostaticPressureMPa() + Math.max(0, overpressure);
        return config.compressibilityPerMPa() + gasVolumeFraction() / absolute;
    }

    /** log10 of the bulk magma viscosity (Pa·s). */
    public double viscosityLog10() {
        return MeltViscosity.log10(silicaWt(), waterWt(), temperature, crystalFraction());
    }

    /** True when erupting magma fragments into a gas-driven (explosive) flow. */
    public boolean fragmented() {
        return waterWt() >= config.fragmentationWaterWt();
    }

    /** Poiseuille conduit conductance (m³/s per MPa of overpressure). */
    double conduitConductance() {
        double viscosity = fragmented() ? config.fragmentedViscosity() : Math.pow(10, viscosityLog10());
        double r = config.conduitRadius();
        return Math.PI * r * r * r * r * 1e6 / (8 * viscosity * config.lithostaticDepth());
    }

    // ── MagmaState ──

    @Override
    public BlockPos chamberCenter() {
        return config.center();
    }

    @Override
    public double overpressureMPa() {
        return overpressure;
    }

    @Override
    public double overpressureRateMPaPerSecond() {
        return overpressureRate;
    }

    @Override
    public double temperatureC() {
        return temperature;
    }

    /** SiO₂ of the residual melt (what erupts), enriched by fractional crystallisation. */
    @Override
    public double silicaWt() {
        double phi = crystalFraction();
        double melt = (bulkSilica - phi * config.crystalSilicaWt()) / (1 - phi);
        return MeltViscosity.clamp(melt, bulkSilica, MAX_MELT_SILICA);
    }

    /** Dissolved H₂O in the melt (wt%). */
    @Override
    public double waterWt() {
        return Math.min(meltTotalWater(), waterSolubilityWt());
    }

    @Override
    public double crystalFraction() {
        double liquidus = liquidusC(bulkSilica);
        double x = (liquidus - temperature) / (liquidus - SOLIDUS_C);
        return MAX_CRYSTAL_FRACTION * MeltViscosity.clamp(x, 0, 1);
    }

    @Override
    public double eruptionRate() {
        return eruptionRate;
    }

    @Override
    public boolean erupting() {
        return erupting;
    }

    public double bulkSilicaWt() {
        return bulkSilica;
    }

    public double bulkWaterWt() {
        return bulkWater;
    }

    public double supplyRate() {
        return supplyRate;
    }

    /** DRE volume erupted so far in the current (or last) eruption (m³). */
    public double eruptedVolume() {
        return eruptedVolume;
    }

    // ── Persistence ──

    @Override
    public void saveState(JsonObject out) {
        out.addProperty("pendingStart", pendingStart);
        out.addProperty("pendingStop", pendingStop);
        out.addProperty("pendingFlank", pendingFlank);
        out.addProperty("overpressure", overpressure);
        out.addProperty("temperature", temperature);
        out.addProperty("bulkSilica", bulkSilica);
        out.addProperty("bulkWater", bulkWater);
        out.addProperty("supplyRate", supplyRate);
        out.addProperty("erupting", erupting);
        out.addProperty("eruptionStartTick", eruptionStartTick);
        out.addProperty("eruptedVolume", eruptedVolume);
        out.addProperty("eruptionRate", eruptionRate);
        out.addProperty("overpressureRate", overpressureRate);
        out.addProperty("pendingStart", pendingStart);
        out.addProperty("pendingStop", pendingStop);
    }

    @Override
    public void loadState(JsonObject in) {
        pendingStart = in.has("pendingStart") && in.get("pendingStart").getAsBoolean();
        pendingStop = in.has("pendingStop") && in.get("pendingStop").getAsBoolean();
        pendingFlank = in.has("pendingFlank") && in.get("pendingFlank").getAsBoolean();
        overpressure = in.get("overpressure").getAsDouble();
        temperature = in.get("temperature").getAsDouble();
        bulkSilica = in.get("bulkSilica").getAsDouble();
        bulkWater = in.get("bulkWater").getAsDouble();
        supplyRate = in.get("supplyRate").getAsDouble();
        erupting = in.get("erupting").getAsBoolean();
        eruptionStartTick = in.get("eruptionStartTick").getAsLong();
        eruptedVolume = in.get("eruptedVolume").getAsDouble();
        eruptionRate = in.get("eruptionRate").getAsDouble();
        overpressureRate = in.get("overpressureRate").getAsDouble();
        pendingStart = in.get("pendingStart").getAsBoolean();
        pendingStop = in.get("pendingStop").getAsBoolean();
    }
}
