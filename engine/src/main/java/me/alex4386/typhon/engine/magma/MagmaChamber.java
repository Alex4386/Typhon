package me.alex4386.typhon.engine.magma;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
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
import me.alex4386.typhon.engine.volcano.EruptiveRegime;
import me.alex4386.typhon.engine.volcano.MagmaState;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;

/**
 * Lumped (0-D) magma chamber.
 *
 * <p><b>Pressure.</b> The chamber is an elastic reservoir of volume {@code V} and effective
 * compressibility {@code β = β₀ + α/P_abs} (bubble-free system plus exsolved-gas volume fraction
 * {@code α}). Net inflow changes overpressure as {@code dP/dt = (Q_in − Q_out) / (V β)}. Recharge
 * comes from a steady deep supply with log-normal fluctuations and from {@link InjectRecharge}
 * pulses.
 *
 * <p><b>Eruption.</b> When overpressure exceeds the failure pressure an eruption starts and magma
 * leaves through a cylindrical conduit following Poiseuille flow, {@code Q_out = π r⁴ P / (8 μ L)}.
 * A sealed conduit fails at the roof's tensile strength; after an eruption the conduit stays open
 * and re-opens at a much lower overpressure until it seals again (e-folding
 * {@link ConduitConfig#conduitSealTimescale()} of repose). Pressure is integrated exactly
 * ({@code P → P_eq + (P − P_eq) e^(−t/τ)}, {@code τ = Vβ/c}). The eruption ends when overpressure
 * falls below the end threshold; if supply keeps it above, the eruption is persistent.
 *
 * <p><b>Conduit regime.</b> {@link ConduitFlow} decides how the magma leaves: a closed conduit failing
 * at high overpressure decompresses fast, so gas-rich, viscous magma fragments into a sustained
 * explosive column ({@link EruptiveRegime#EXPLOSIVE}); slowly rising magma outgasses and extrudes as
 * a dome ({@link EruptiveRegime#DOME}); fluid magma either fountains or, when gas slugs outrun the
 * melt, erupts from an open vent ({@link EruptiveRegime#OPEN_VENT}). An explosive phase relaxes to
 * effusion once the ascent slows enough to outgas; it only resumes if the chamber fails again.
 *
 * <p><b>Explosions.</b> Open vents accumulate a share of the exsolving gas into slugs that burst
 * (Strombolian; gamma-distributed slug masses give quasi-periodic intervals). Domes trap gas beneath a
 * plug in the porous upper conduit until its pressure exceeds the plug strength (Vulcanian); a new
 * plug then needs time to seal. Bursts are queued for the surface coupling ({@link #drainBursts()}).
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
    static final int MAX_PENDING_BURSTS = 64;
    /** Hysteresis on the slug-speed / ascent-speed comparison that separates fountains from open vents. */
    static final double REGIME_HYSTERESIS = 1.1;

    private final MagmaChamberConfig config;

    private double overpressure;
    private double temperature;
    private double bulkSilica;
    private double bulkWater;
    private double supplyRate;
    private boolean erupting;
    private double eruptionStartTime;
    private double lastTime;
    private double eruptedVolume;
    private double eruptionRate;
    private double overpressureRate;
    private boolean pendingStart;
    private boolean pendingStop;
    private boolean pendingFlank;
    private EruptiveRegime regime = EruptiveRegime.QUIESCENT;
    private double conduitOpenness;
    private double ventWater;
    private double ascentVelocity;
    private double slugGas;
    private double nextSlugMass;
    private double plugGas;
    private double resealRemaining;
    private final ArrayDeque<ConduitBurst> bursts = new ArrayDeque<>();

    public MagmaChamber(MagmaChamberConfig config) {
        this.config = config;
        this.overpressure = config.initialOverpressureMPa();
        this.temperature = config.initialTemperatureC();
        this.bulkSilica = config.initialSilicaWt();
        this.bulkWater = config.initialWaterWt();
        this.supplyRate = config.supplyRate();
        this.conduitOpenness = config.conduit().initialOpenness();
    }

    @Override
    public MagmaChamberConfig config() {
        return config;
    }

    @Override
    public String id() {
        return "magma:" + config.volcanoId();
    }

    @Override
    public double periodSeconds() {
        return config.stepPeriodSeconds();
    }

    @Override
    public MagmaEvents.ChamberSample snapshot() {
        return sample(lastTime);
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
        lastTime = context.time();
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

            double physicalRate = erupted / physicalDt;
            updateRegime(context, physicalRate);
            runConduitGas(context, physicalRate, physicalDt);
            if (overpressure <= config.eruptionEndOverpressureMPa()) {
                endEruption(context, Cause.AUTOMATIC);
            }
        } else {
            conduitOpenness *= Math.exp(-physicalDt / config.conduit().conduitSealTimescale());
            overpressure += inflow / stiffness;
            if (overpressure >= failureOverpressureMPa()) {
                startEruption(context, Cause.AUTOMATIC);
            }
        }

        eruptionRate = erupting ? erupted / dt : 0;
        overpressureRate = (overpressure - previous) / dt;

        if (context.crossed(config.samplePeriodSeconds())) {
            context.outbox().emit(sample(context.time()));
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
        // A dike-fed flank eruption uses a fresh, already-open pathway: no sudden roof failure.
        boolean closed = cause != Cause.DIKE && conduitOpenness < 0.5;
        EruptiveRegime onset = onsetRegime(closed);
        ConduitFlow.Assessment a = assess();
        double onsetRate = cappedRate(onset == EruptiveRegime.EXPLOSIVE ? a.fragmentedConductance() : a.effusiveConductance());
        ascentVelocity = ConduitFlow.ascentVelocity(onsetRate, config.conduitRadius());
        ventWater = ConduitFlow.retainedWaterWt(config, waterWt(), onsetRate);
        erupting = true;
        eruptionStartTime = context.time();
        eruptedVolume = 0;
        slugGas = 0;
        plugGas = 0;
        resealRemaining = 0;
        context.outbox().emit(new MagmaEvents.EruptionStarted(context.time(), config.volcanoId(), overpressure, cause));
        setRegime(context, onset);
    }

    private void endEruption(StepContext context, Cause cause) {
        erupting = false;
        eruptionRate = 0;
        conduitOpenness = 1;
        context.outbox().emit(new MagmaEvents.EruptionEnded(
                context.time(), config.volcanoId(), eruptedVolume, context.time() - eruptionStartTime, cause));
        setRegime(context, EruptiveRegime.QUIESCENT);
    }

    // ── Conduit regime ──

    /**
     * Overpressure at which the next eruption starts: the tensile strength for a sealed conduit,
     * down to the re-opening pressure for one left open by a recent eruption.
     */
    public double failureOverpressureMPa() {
        double tensile = config.tensileStrengthMPa();
        return tensile + (config.reopenOverpressureMPa() - tensile) * conduitOpenness;
    }

    /** Openness of the conduit: 1 right after an eruption, decaying towards 0 (sealed) in repose. */
    public double conduitOpenness() {
        return conduitOpenness;
    }

    private ConduitFlow.Assessment assess() {
        return ConduitFlow.assess(config, silicaWt(), waterWt(), temperature, crystalFraction());
    }

    /** Rate (m³/s) through a conduit of {@code conductance}; before an eruption, at failure pressure. */
    private double cappedRate(double conductance) {
        double pressure = erupting ? overpressure : Math.max(overpressure, failureOverpressureMPa());
        return Math.min(config.maxEruptionRate(), conductance * Math.max(0, pressure));
    }

    private boolean fragmentsAt(double rate) {
        return ConduitFlow.fragments(config, silicaWt(), waterWt(), temperature, crystalFraction(), rate);
    }

    /**
     * Regime at eruption onset. A sealed conduit failing at high overpressure decompresses the magma
     * column suddenly, so it is tested at the fragmented-flow rate; an open conduit or dike lets
     * magma start rising at the coherent-flow rate.
     */
    private EruptiveRegime onsetRegime(boolean closedConduit) {
        ConduitFlow.Assessment a = assess();
        double coherent = cappedRate(a.effusiveConductance());
        if (closedConduit && fragmentsAt(cappedRate(a.fragmentedConductance()))) return EruptiveRegime.EXPLOSIVE;
        if (!closedConduit && fragmentsAt(coherent)) return EruptiveRegime.EXPLOSIVE;
        return effusiveRegime(a, coherent, null);
    }

    private EruptiveRegime effusiveRegime(ConduitFlow.Assessment a, double rate, EruptiveRegime previous) {
        if (!a.slugFlowPossible()) return EruptiveRegime.DOME;
        double u = ConduitFlow.ascentVelocity(rate, config.conduitRadius());
        double slug = a.slugVelocity();
        if (previous == EruptiveRegime.OPEN_VENT) {
            return u > REGIME_HYSTERESIS * slug ? EruptiveRegime.FOUNTAINING : EruptiveRegime.OPEN_VENT;
        }
        if (previous == EruptiveRegime.FOUNTAINING) {
            return slug > REGIME_HYSTERESIS * u ? EruptiveRegime.OPEN_VENT : EruptiveRegime.FOUNTAINING;
        }
        return slug > u ? EruptiveRegime.OPEN_VENT : EruptiveRegime.FOUNTAINING;
    }

    /** Re-evaluates the regime for the rate actually erupted this step (physical m³/s). */
    private void updateRegime(StepContext context, double physicalRate) {
        ascentVelocity = ConduitFlow.ascentVelocity(physicalRate, config.conduitRadius());
        ventWater = ConduitFlow.retainedWaterWt(config, waterWt(), physicalRate);
        EruptiveRegime next;
        if (regime == EruptiveRegime.EXPLOSIVE) {
            // Sustained fragmentation stops once the column decelerates enough to outgas or flow
            // ductilely; it does not resume until the chamber fails again.
            next = fragmentsAt(physicalRate) ? EruptiveRegime.EXPLOSIVE : effusiveRegime(assess(), physicalRate, null);
        } else {
            next = effusiveRegime(assess(), physicalRate, regime);
        }
        setRegime(context, next);
    }

    private void setRegime(StepContext context, EruptiveRegime next) {
        if (next == regime) return;
        EruptiveRegime previous = regime;
        regime = next;
        if (next != EruptiveRegime.OPEN_VENT) slugGas = 0;
        if (next != EruptiveRegime.DOME) {
            plugGas = 0;
            resealRemaining = 0;
        }
        context.outbox().emit(new MagmaEvents.EruptiveRegimeChanged(
                context.time(), config.volcanoId(), previous, next, ascentVelocity, ventWaterWt()));
    }

    // ── Conduit gas: Strombolian slugs and Vulcanian plugs ──

    private void runConduitGas(StepContext context, double physicalRate, double physicalDt) {
        ConduitConfig c = config.conduit();
        double gasFlux = physicalRate * ConduitFlow.MAGMA_DENSITY * Math.max(0, waterWt() - c.degassedWaterWt()) / 100;
        SimRandom random = context.random();
        if (regime == EruptiveRegime.OPEN_VENT) {
            slugGas += c.slugGasFraction() * gasFlux * physicalDt;
            if (nextSlugMass <= 0) nextSlugMass = sampleSlugMass(random);
            for (int n = 0; slugGas >= nextSlugMass && n < 8; n++) {
                double gas = nextSlugMass;
                slugGas -= gas;
                queueBurst(new ConduitBurst(context.time(), ConduitBurst.Kind.STROMBOLIAN, gas,
                        gas * (1 / c.strombolianGasMassFraction() - 1), c.slugOverpressureMPa(), temperature, silicaWt(),
                        c.strombolianDurationSeconds()));
                nextSlugMass = sampleSlugMass(random);
            }
        } else if (regime == EruptiveRegime.DOME) {
            if (resealRemaining > 0) {
                resealRemaining = Math.max(0, resealRemaining - physicalDt); // gas escapes through the broken plug
            } else {
                plugGas += c.plugTrappedGasFraction() * gasFlux * physicalDt;
            }
            if (plugPressureMPa() >= c.plugStrengthMPa()) {
                double gas = plugGas;
                queueBurst(new ConduitBurst(context.time(), ConduitBurst.Kind.VULCANIAN, gas,
                        gas * (1 / c.vulcanianGasMassFraction() - 1), plugPressureMPa(), temperature, silicaWt(),
                        c.vulcanianDurationSeconds()));
                plugGas = 0;
                resealRemaining = c.plugResealSeconds();
            }
        }
    }

    /** Gamma(k = 4) slug masses: quasi-periodic bursts around the mean. */
    private double sampleSlugMass(SimRandom random) {
        double sum = 0;
        for (int i = 0; i < 4; i++) sum += random.nextExponential(1);
        return config.conduit().slugGasMassKg() * sum / 4;
    }

    /** Pressure of gas trapped beneath a dome plug (ideal gas in the porous upper conduit, MPa). */
    public double plugPressureMPa() {
        ConduitConfig c = config.conduit();
        double r = config.conduitRadius();
        double volume = Math.PI * r * r * c.plugCapDepthM() * c.plugPorosity();
        return plugGas * ConduitFlow.GAS_CONSTANT * (temperature + 273.15) / (ConduitFlow.WATER_MOLAR_MASS * volume) / 1e6;
    }

    private void queueBurst(ConduitBurst burst) {
        if (bursts.size() >= MAX_PENDING_BURSTS) bursts.pollFirst();
        bursts.addLast(burst);
    }

    /** Takes the explosions produced since the last call (oldest first). */
    public List<ConduitBurst> drainBursts() {
        List<ConduitBurst> out = new ArrayList<>(bursts);
        bursts.clear();
        return out;
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

    private MagmaEvents.ChamberSample sample(double time) {
        return new MagmaEvents.ChamberSample(time, config.volcanoId(), overpressure, overpressureRate, temperature,
                silicaWt(), waterWt(), exsolvedWaterWt(), crystalFraction(), viscosityLog10(), supplyRate,
                eruptionRate, eruptedVolume, erupting, regime, ventWaterWt(), conduitOpenness);
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

    /**
     * True when erupting magma fragments into a gas-driven (explosive) flow. Before an eruption: whether
     * failure of the conduit as it is now would start explosively.
     */
    public boolean fragmented() {
        if (erupting) return regime == EruptiveRegime.EXPLOSIVE;
        return onsetRegime(conduitOpenness < 0.5) == EruptiveRegime.EXPLOSIVE;
    }

    /** log10 of the viscosity (Pa·s) of coherent magma rising through the conduit (partly degassed). */
    public double conduitViscosityLog10() {
        return assess().conduitViscosityLog10();
    }

    /** Poiseuille conduit conductance (m³/s per MPa of overpressure) for the current regime. */
    double conduitConductance() {
        ConduitFlow.Assessment a = assess();
        return fragmented() ? a.fragmentedConductance() : a.effusiveConductance();
    }

    @Override
    public EruptiveRegime eruptiveRegime() {
        return regime;
    }

    /** Dissolved H₂O reaching the fragmentation level (wt%); the chamber value when not erupting. */
    @Override
    public double ventWaterWt() {
        return erupting ? ventWater : waterWt();
    }

    /** Mean magma ascent speed in the conduit during the last eruptive step (m/s). */
    public double ascentVelocity() {
        return erupting ? ascentVelocity : 0;
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

    /**
     * Sets the deep magma supply (m³/s) directly; call between steps (e.g. when a world applies a
     * changed definition to restored state). Running hosts use {@code MagmaCommands.SetSupplyRate}.
     */
    public void setSupplyRate(double rate) {
        if (!(rate >= 0)) throw new IllegalArgumentException("supply rate must be >= 0");
        this.supplyRate = rate;
    }

    /** DRE volume erupted so far in the current (or last) eruption (m³). */
    public double eruptedVolume() {
        return eruptedVolume;
    }

    // ── Persistence ──

    @Override
    public void saveState(StateWriter writer) {
        JsonObject out = writer.json();
        out.addProperty("pendingStart", pendingStart);
        out.addProperty("pendingStop", pendingStop);
        out.addProperty("pendingFlank", pendingFlank);
        out.addProperty("overpressure", overpressure);
        out.addProperty("temperature", temperature);
        out.addProperty("bulkSilica", bulkSilica);
        out.addProperty("bulkWater", bulkWater);
        out.addProperty("supplyRate", supplyRate);
        out.addProperty("erupting", erupting);
        out.addProperty("eruptionStartTime", eruptionStartTime);
        out.addProperty("lastTime", lastTime);
        out.addProperty("eruptedVolume", eruptedVolume);
        out.addProperty("eruptionRate", eruptionRate);
        out.addProperty("overpressureRate", overpressureRate);
        out.addProperty("regime", regime.name());
        out.addProperty("conduitOpenness", conduitOpenness);
        out.addProperty("ventWater", ventWater);
        out.addProperty("ascentVelocity", ascentVelocity);
        out.addProperty("slugGas", slugGas);
        out.addProperty("nextSlugMass", nextSlugMass);
        out.addProperty("plugGas", plugGas);
        out.addProperty("resealRemaining", resealRemaining);
        JsonArray queued = new JsonArray();
        for (ConduitBurst b : bursts) {
            JsonObject o = new JsonObject();
            o.addProperty("time", b.time());
            o.addProperty("kind", b.kind().name());
            o.addProperty("gas", b.gasMassKg());
            o.addProperty("ejecta", b.ejectaMassKg());
            o.addProperty("overpressure", b.overpressureMPa());
            o.addProperty("temperature", b.temperatureC());
            o.addProperty("silica", b.silicaWt());
            o.addProperty("duration", b.durationSeconds());
            queued.add(o);
        }
        out.add("bursts", queued);
    }

    @Override
    public void loadState(StateReader reader) {
        JsonObject in = reader.json();
        pendingStart = in.has("pendingStart") && in.get("pendingStart").getAsBoolean();
        pendingStop = in.has("pendingStop") && in.get("pendingStop").getAsBoolean();
        pendingFlank = in.has("pendingFlank") && in.get("pendingFlank").getAsBoolean();
        overpressure = in.get("overpressure").getAsDouble();
        temperature = in.get("temperature").getAsDouble();
        bulkSilica = in.get("bulkSilica").getAsDouble();
        bulkWater = in.get("bulkWater").getAsDouble();
        supplyRate = in.get("supplyRate").getAsDouble();
        erupting = in.get("erupting").getAsBoolean();
        eruptionStartTime = in.get("eruptionStartTime").getAsDouble();
        lastTime = in.get("lastTime").getAsDouble();
        eruptedVolume = in.get("eruptedVolume").getAsDouble();
        eruptionRate = in.get("eruptionRate").getAsDouble();
        overpressureRate = in.get("overpressureRate").getAsDouble();
        regime = in.has("regime") ? EruptiveRegime.valueOf(in.get("regime").getAsString())
                : (erupting ? EruptiveRegime.FOUNTAINING : EruptiveRegime.QUIESCENT);
        conduitOpenness = in.has("conduitOpenness") ? in.get("conduitOpenness").getAsDouble() : config.conduit().initialOpenness();
        ventWater = in.has("ventWater") ? in.get("ventWater").getAsDouble() : waterWt();
        ascentVelocity = in.has("ascentVelocity") ? in.get("ascentVelocity").getAsDouble() : 0;
        slugGas = in.has("slugGas") ? in.get("slugGas").getAsDouble() : 0;
        nextSlugMass = in.has("nextSlugMass") ? in.get("nextSlugMass").getAsDouble() : 0;
        plugGas = in.has("plugGas") ? in.get("plugGas").getAsDouble() : 0;
        resealRemaining = in.has("resealRemaining") ? in.get("resealRemaining").getAsDouble() : 0;
        bursts.clear();
        if (in.has("bursts")) {
            for (JsonElement e : in.getAsJsonArray("bursts")) {
                JsonObject o = e.getAsJsonObject();
                bursts.addLast(new ConduitBurst(o.get("time").getAsDouble(), ConduitBurst.Kind.valueOf(o.get("kind").getAsString()),
                        o.get("gas").getAsDouble(), o.get("ejecta").getAsDouble(), o.get("overpressure").getAsDouble(),
                        o.get("temperature").getAsDouble(), o.get("silica").getAsDouble(), o.get("duration").getAsDouble()));
            }
        }
    }
}
