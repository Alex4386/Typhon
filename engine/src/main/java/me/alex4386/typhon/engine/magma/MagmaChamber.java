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
import me.alex4386.typhon.engine.magma.MagmaCommands.SetSupplyMagma;
import me.alex4386.typhon.engine.magma.MagmaCommands.SetSupplyRate;
import me.alex4386.typhon.engine.magma.MagmaCommands.StartEruption;
import me.alex4386.typhon.engine.magma.MagmaCommands.StopEruption;
import me.alex4386.typhon.engine.magma.MagmaEvents.Cause;
import me.alex4386.typhon.engine.magma.conduit.ConduitInput;
import me.alex4386.typhon.engine.magma.conduit.ConduitJson;
import me.alex4386.typhon.engine.magma.conduit.ConduitModel;
import me.alex4386.typhon.engine.magma.conduit.ConduitModel.Branch;
import me.alex4386.typhon.engine.magma.conduit.ConduitSolution;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.random.SimRandom;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.volcano.EruptiveRegime;
import me.alex4386.typhon.engine.volcano.MagmaState;

/**
 * Lumped (0-D) magma chamber feeding a resolved conduit.
 *
 * <p><b>Pressure.</b> The chamber is an elastic reservoir of volume {@code V} and effective
 * compressibility {@code β = β₀ + α/P_abs} (bubble-free system plus exsolved-gas volume fraction
 * {@code α}, H₂O and CO₂). Net inflow changes overpressure as {@code dP/dt = (Q_in − Q_out) / (V β)}.
 * Recharge comes from a steady deep supply with log-normal fluctuations and from {@link
 * InjectRecharge} pulses; the supply's rate and magma ({@link SetSupplyMagma}) can change at run time.
 *
 * <p><b>Eruption.</b> A sealed conduit fails at the roof's tensile strength; after an eruption it
 * stays open and re-opens at a much lower overpressure until it seals again (e-folding {@link
 * ConduitConfig#conduitSealTimescale()} of repose). While erupting, magma leaves at the rate of the
 * steady conduit flow ({@link ConduitModel}: exsolution, outgassing, crystallisation, fragmentation,
 * choking). A sealed conduit failing suddenly decompresses fast and follows the fastest steady flow; an
 * open conduit or a dike starts on the slowest; during the eruption the flow stays on its branch until
 * that branch disappears (Melnik &amp; Sparks 1999). Between conduit solutions the outflow is
 * linearised about the magmastatic balance, {@code Q = k (P − P₀)}, and pressure integrated exactly.
 * The eruption ends when overpressure falls below the end threshold, or when the conduit can no longer
 * carry any flow.
 *
 * <p>Nothing here selects a style. Whether magma fountains, extrudes a dome or feeds a Plinian column
 * follows from the flow; {@link #eruptiveRegime()} only names it. Gas slugs segregating in fluid magma
 * accumulate and burst (Strombolian-type explosions); outgassed gas trapped beneath a stiff,
 * crystal-rich plug pressurises until the plug fails (Vulcanian-type). Both are physical events
 * queued for the surface coupling ({@link #drainBursts()}).
 *
 * <p><b>Thermal and chemical evolution.</b> Temperature relaxes towards the wall-rock temperature;
 * recharge mixes by mass and enthalpy (sensible + latent heat; crystals carry no latent heat). Crystal
 * fraction follows a linear liquidus–solidus interpolation. Fractional crystallisation enriches the
 * residual melt in SiO₂, H₂O and CO₂; volatiles beyond solubility ({@code 0.411 √P} wt% H₂O, Henry's
 * law for CO₂) exsolve and slowly vent.
 *
 * <p><b>Time.</b> All {@link MagmaState} rates are per second. The chamber asks for short steps
 * ({@link #maxStepSeconds()}) while erupting or about to, and long ones while it recharges quietly.
 *
 * <p>References: Blake (1981), Nature 289:783-785; Huppert &amp; Woods (2002), Nature 420:493-495;
 * Wilson &amp; Head (1981), JGR 86:2971-3001; Melnik &amp; Sparks (1999), Nature 402:37-41. See {@code
 * docs/eruption-dynamics.md}.
 */
public final class MagmaChamber implements Subsystem, MagmaState {
    static final double ROCK_DENSITY = 2600;
    static final double MAGMA_DENSITY = ConduitModel.MAGMA_DENSITY;
    static final double GRAVITY = 9.81;
    static final double WATER_MOLAR_MASS = 0.018;
    static final double CO2_MOLAR_MASS = 0.044;
    static final double GAS_CONSTANT = 8.314;
    static final double SOLIDUS_C = 700;
    /** Specific heat of silicate melt (J/kg/K); Spera (2000), Encyclopedia of Volcanoes. */
    static final double MELT_HEAT_CAPACITY = 1200;
    /** Latent heat of crystallisation (J/kg), released between liquidus and solidus; Spera (2000). */
    static final double LATENT_HEAT_CRYSTALLISATION = 4.0e5;
    static final double MAX_CRYSTAL_FRACTION = 0.58;
    static final double MAX_MELT_SILICA = 77;
    static final int MAX_PENDING_BURSTS = 64;
    /** Water table below the vent assumed until the surface reports one (m). */
    static final double DEFAULT_WATER_TABLE_DEPTH_M = 200;
    /**
     * Relative change of the conduit's drivers that triggers a new steady solution during an eruption
     * (see {@link ConduitInput#closeTo}).
     */
    static final double RESOLVE_TOLERANCE = 0.02;
    /** Width (log10 Pa·s) of the transition from soft lava to a gas-tight plug. */
    static final double PLUG_TRANSITION_LOG10 = 0.5;
    /**
     * Highest overpressure the chamber walls sustain, as a multiple of the failure overpressure.
     * Chambers rupture at overpressures of the order of the host rock's tensile strength, typically a
     * few to ~20 MPa (Gudmundsson 2012, JVGR 237-238:19-41; Jellinek &amp; DePaolo 2003); once the wall
     * fails, magma beyond what the walls can store elastically leaves the elastic budget (it inflates
     * the chamber inelastically, intrudes or erupts) instead of raising the pressure further.
     */

    private MagmaChamberConfig config;

    /** Current chamber volume (m³): the configured volume plus magma stored inelastically. */
    private double volume;
    /** Magma accommodated beyond the walls' elastic capacity (m³; chamber growth after wall failure). */
    private double inelasticGrowth;
    /** Magma pushed out of the ruptured walls and not yet claimed by a dike (m³). */
    private double ruptureExcess;
    /** The waiting rupture magma has been offered to dikes for a full chamber step. */
    private boolean ruptureOffered;
    /** Magma that left the chamber into dikes, from rupture and as dikes grew (m³, cumulative). */
    private double intrudedVolume;
    /** Magma received from / sent to other chambers of the plumbing (m³, cumulative). */
    private double transferredIn;
    private double transferredOut;
    /**
     * Whether this chamber feeds the surface itself (the main chamber); a deeper or side chamber only
     * exchanges magma with the others and intrudes dikes. Set by the volcano's assembly, not saved.
     */
    private boolean eruptive = true;
    /**
     * Overpressure the erupting chamber relaxes towards, where outflow equals supply
     * ({@code P₀ + Q_in/k}); NaN while not erupting. Reporting only, recomputed every step.
     */
    private double balanceOverpressure = Double.NaN;
    private double overpressure;
    private double temperature;
    private double bulkSilica;
    private double bulkWater;
    private double bulkCo2;
    private double supplyRate;
    private double supplyVariability;
    private double rechargeTemperature;
    private double rechargeSilica;
    private double rechargeWater;
    private double rechargeCo2;
    private double rechargeCrystals;
    private boolean erupting;
    private double eruptionStartTime;
    private int eruptionCount;
    private double lastTime;
    private double eruptedVolume;
    private double eruptionRate;
    private double overpressureRate;
    private boolean pendingStart;
    private boolean pendingStop;
    private boolean pendingFlank;
    /** Share of the conduit's conductance the open vents can take (fissure feeders, seals); 1 = unrestricted. */
    private double outletCapacity = 1;
    /** True while the summit vent is sealed: the roof cannot fail there, only a dike can open a path. */
    private boolean summitBlocked;
    private EruptiveRegime regime = EruptiveRegime.QUIESCENT;
    private double conduitOpenness;
    private double ventAmbientPa = ConduitInput.ATMOSPHERE_PA;
    private double waterTableDepthM = DEFAULT_WATER_TABLE_DEPTH_M;
    /** Drivers of the current steady conduit flow, and the flow itself (null when not erupting). */
    private ConduitInput conduitInput;
    private ConduitSolution conduit;
    /** The solution before the current one (for the secant outflow slope): overpressure and rate. */
    private double previousSolvedOverpressure;
    private double previousSolvedRate;
    private double slugGas;
    private double nextSlugMass;
    private double plugGas;
    private double resealRemaining;
    private final ArrayDeque<ConduitBurst> bursts = new ArrayDeque<>();

    // Forecast cache: a pure function of the rounded input, so it needs no saving.
    private ConduitInput forecastKey;
    private ConduitSolution forecast;

    public MagmaChamber(MagmaChamberConfig config) {
        this.config = config;
        this.volume = config.volume();
        this.overpressure = Math.min(config.initialOverpressureMPa(), ruptureCap(config));
        this.temperature = config.initialTemperatureC();
        this.bulkSilica = config.initialSilicaWt();
        this.bulkWater = config.initialWaterWt();
        this.bulkCo2 = config.initialCo2Wt();
        this.conduitOpenness = config.conduit().initialOpenness();
        resetSupplyFromConfig();
    }

    @Override
    public MagmaChamberConfig config() {
        return config;
    }

    /**
     * Live retune: what acts from now on (supply and recharge magma, rock, wall and conduit properties,
     * time scales) is taken at once. The chamber's initial state, size and position define its state and
     * are refused (they need a reset).
     */
    @Override
    public boolean reconfigure(Object c) {
        if (!(c instanceof MagmaChamberConfig n) || !n.volcanoId().equals(config.volcanoId())) return false;
        if (!n.chamberId().equals(config.chamberId()) || !n.center().equals(config.center()) || n.volume() != config.volume()
                || n.initialTemperatureC() != config.initialTemperatureC() || n.initialSilicaWt() != config.initialSilicaWt()
                || n.initialWaterWt() != config.initialWaterWt() || n.initialCo2Wt() != config.initialCo2Wt()
                || n.initialOverpressureMPa() != config.initialOverpressureMPa()
                || n.conduit().initialOpenness() != config.conduit().initialOpenness()) {
            return false;
        }
        boolean supply = n.supplyRate() != config.supplyRate() || n.supplyVariability() != config.supplyVariability()
                || n.rechargeTemperatureC() != config.rechargeTemperatureC() || n.rechargeSilicaWt() != config.rechargeSilicaWt()
                || n.rechargeWaterWt() != config.rechargeWaterWt() || n.rechargeCo2Wt() != config.rechargeCo2Wt()
                || n.rechargeCrystalFraction() != config.rechargeCrystalFraction();
        config = n;
        // the configured supply is the source of truth once it changes (as when a world reopens with it)
        if (supply) resetSupplyFromConfig();
        return true;
    }

    @Override
    public String id() {
        return id(config.volcanoId(), config.chamberId());
    }

    /** Subsystem id of chamber {@code chamberId} of a volcano ({@code magma:<volcano>} for its main chamber). */
    public static String id(String volcanoId, String chamberId) {
        return MagmaChamberConfig.MAIN.equals(chamberId) ? "magma:" + volcanoId : "magma:" + volcanoId + ":" + chamberId;
    }

    /** 0D, touches only this volcano's magma/seismic/alert chain: may run beside other volcanoes'. */
    @Override
    public String concurrencyLane() {
        return "volcano:" + config.volcanoId();
    }

    @Override
    public double periodSeconds() {
        return config.stepPeriodSeconds();
    }

    /** Longest step while erupting (s): the conduit solution and gas budget are relinearised this often. */
    public static final double ERUPTING_STEP_SECONDS = 20;
    /** Longest step of a quiet chamber (s). */
    public static final double QUIET_STEP_SECONDS = 86_400;

    /**
     * While erupting (or at failure) {@link #ERUPTING_STEP_SECONDS}; while quiet, a quarter of the time
     * the pressurisation needs to reach roof failure (so the eruption starts on time), at most
     * {@link #QUIET_STEP_SECONDS}. The pressurisation is the larger of the last observed rate and the
     * supply's elastic rate {@code Q / (V·β)}, so a fresh chamber is not stepped past its failure.
     */
    @Override
    public double maxStepSeconds() {
        if (erupting || pendingStart || pendingFlank) return ERUPTING_STEP_SECONDS;
        if (!eruptive || summitBlocked) return QUIET_STEP_SECONDS;
        double gap = failureOverpressureMPa() - overpressure;
        if (gap <= 0) return ERUPTING_STEP_SECONDS;
        double rate = Math.max(overpressureRate, Math.max(0, supplyRate) / (volume * effectiveCompressibility()));
        if (!(rate > 0)) return QUIET_STEP_SECONDS;
        return Math.max(config.stepPeriodSeconds(), Math.min(QUIET_STEP_SECONDS, 0.25 * gap / rate));
    }

    @Override
    public MagmaEvents.ChamberSample snapshot() {
        return sample(lastTime);
    }

    @Override
    public void registerCommands(CommandBus bus) {
        bus.register(SetSupplyRate.class, this::handleIfTargeted);
        bus.register(SetSupplyMagma.class, this::handleIfTargeted);
        bus.register(InjectRecharge.class, this::handleIfTargeted);
        bus.register(StartEruption.class, this::handleIfTargeted);
        bus.register(StopEruption.class, this::handleIfTargeted);
        bus.register(MagmaCommands.ChamberCommand.class, this::handleIfTargeted);
    }

    private void handleIfTargeted(MagmaCommand command) {
        if (!command.volcanoId().equals(config.volcanoId())) return;
        if (command instanceof MagmaCommands.ChamberCommand c) {
            if (c.chamberId().equals(config.chamberId())) handle(c.command());
        } else if (config.isMain()) {
            handle(command); // volcano-wide commands address the main chamber
        }
    }

    void handle(MagmaCommand command) {
        switch (command) {
            case SetSupplyRate c -> supplyRate = c.supplyRate();
            case SetSupplyMagma c -> {
                if (c.supplyRate() != null) supplyRate = c.supplyRate();
                if (c.temperatureC() != null) rechargeTemperature = c.temperatureC();
                if (c.silicaWt() != null) rechargeSilica = c.silicaWt();
                if (c.waterWt() != null) rechargeWater = c.waterWt();
                if (c.co2Wt() != null) rechargeCo2 = c.co2Wt();
                if (c.crystalFraction() != null) rechargeCrystals = c.crystalFraction();
                if (c.variability() != null) supplyVariability = c.variability();
            }
            case InjectRecharge c -> inject(c.volume(), c.temperatureC(), c.silicaWt(), c.waterWt(),
                    c.co2Wt() != null ? c.co2Wt() : rechargeCo2,
                    c.crystalFraction() != null ? c.crystalFraction() : rechargeCrystals);
            case MagmaCommands.ChamberCommand c -> handle(c.command());
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
        // Wall magma that no dike claimed since the last step (dikes blocked, none could start, no dike
        // model) stays around the chamber: the walls yield and the chamber grows instead.
        absorbRuptureExcess();

        double supply = currentSupply(context.random());
        double inflow = supply * dt;

        mix(inflow, rechargeTemperature, rechargeSilica, rechargeWater, rechargeCo2, rechargeCrystals);
        temperature = config.wallTemperatureC()
                + (temperature - config.wallTemperatureC()) * Math.exp(-dt / config.coolingTimescale());
        double phi = crystalFraction();
        double vented = 1 - Math.exp(-dt / config.degassingTimescale());
        double ventedWater = exsolvedWaterWt() * (1 - phi) * vented;
        double ventedCo2 = exsolvedCo2Wt() * (1 - phi) * vented;
        bulkWater -= ventedWater;
        bulkCo2 -= ventedCo2;
        // Free gas leaving the chamber rises through an open conduit (through the crust otherwise).
        double magmaMass = volume * MAGMA_DENSITY;
        double conduitShare = erupting ? 1 : conduitOpenness;
        double gasWater = ventedWater / 100 * magmaMass * conduitShare;
        double gasCo2 = ventedCo2 / 100 * magmaMass * conduitShare;

        double stiffness = volume * effectiveCompressibility(); // m³ per MPa
        double previous = overpressure;
        double erupted = 0;

        if (erupting) {
            ConduitSolution flow = updateConduit();
            if (flow == null) {
                endEruption(context, Cause.AUTOMATIC); // the conduit can no longer carry magma
            } else if (outletCapacity <= 0) {
                // Every outlet froze or was sealed: the chamber stops venting but keeps its pressure.
                endEruption(context, Cause.SEALED);
                overpressure += inflow / stiffness;
            } else {
                // Outflow linearised about the last solutions: Q = k (P − P₀).
                double solvedAt = solvedOverpressureMPa();
                double conductance = flow.dreRateM3PerS() / Math.max(1e-6, solvedAt - zeroFlowOverpressureMPa());
                double slope = (flow.dreRateM3PerS() - previousSolvedRate) / (solvedAt - previousSolvedOverpressure);
                boolean sameBranch = previousSolvedRate > 0
                        && Math.abs(Math.log(flow.dreRateM3PerS() / previousSolvedRate)) < Math.log(3);
                if (sameBranch && Math.abs(solvedAt - previousSolvedOverpressure) > 1e-3 && slope > 0) {
                    conductance = slope; // m³/s per MPa
                }
                double base = solvedAt - flow.dreRateM3PerS() / conductance;
                conductance = Math.min(conductance, config.maxEruptionRate() / Math.max(1e-6, overpressure - base));
                // Fissure feeders narrowing as they freeze throttle the outflow.
                conductance *= outletCapacity;
                balanceOverpressure = conductance > 0 ? base + supply / conductance : Double.NaN;
                if (conductance > 0) {
                    double equilibrium = base + supply / conductance;
                    double tau = stiffness / conductance;
                    overpressure = equilibrium + (overpressure - equilibrium) * Math.exp(-dt / tau);
                } else {
                    overpressure += inflow / stiffness;
                }
                double stored = relieveRupture(supply);
                erupted = Math.max(0, inflow - stored - stiffness * (overpressure - previous));
                eruptedVolume += erupted;

                runConduitGas(context, flow, dt, gasWater, gasCo2);
                setRegime(context, describe(flow));
                if (overpressure <= config.eruptionEndOverpressureMPa()) {
                    endEruption(context, Cause.AUTOMATIC);
                }
            }
        } else {
            balanceOverpressure = Double.NaN;
            percolateGas(context, dt, gasWater, gasCo2);
            conduitOpenness *= Math.exp(-dt / config.conduit().conduitSealTimescale());
            overpressure += inflow / stiffness;
            relieveRupture(supply);
            if (eruptive && !summitBlocked && overpressure >= failureOverpressureMPa()) {
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

    /**
     * Share (0–1) of the conduit's hydraulic conductance the open vents can carry, reported by the
     * surface coupling: 1 for an open summit, less while flow is squeezed through narrowing fissure
     * feeders, 0 once every outlet froze or was sealed (the eruption then ends with the chamber still
     * pressurised). Used from the next step.
     */
    public void setOutletCapacity(double capacity) {
        this.outletCapacity = Math.max(0, Math.min(1, capacity));
    }

    public double outletCapacity() {
        return outletCapacity;
    }

    /**
     * Marks the summit vent sealed: no automatic or forced onset through the roof; pressure builds
     * until a dike opens a flank path.
     */
    public void setSummitBlocked(boolean blocked) {
        this.summitBlocked = blocked;
    }

    public boolean summitBlocked() {
        return summitBlocked;
    }

    /**
     * Conditions at the vent the conduit flow exits into, reported by the surface coupling: ambient
     * pressure (atmosphere plus water standing over the vent) and the depth of the water table below
     * the vent, which sets how much gas the conduit can lose into its wall. Used from the next step.
     */
    public void setVentEnvironment(double ambientPressurePa, double waterTableDepthM) {
        if (ambientPressurePa > 0) this.ventAmbientPa = ambientPressurePa;
        if (waterTableDepthM >= 0) this.waterTableDepthM = waterTableDepthM;
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
            if (eruptive && !erupting && !summitBlocked) {
                overpressure = Math.max(overpressure, config.tensileStrengthMPa());
                startEruption(context, Cause.FORCED);
            }
        }
    }

    private void startEruption(StepContext context, Cause cause) {
        // A sealed roof failing decompresses the column suddenly: the fastest steady flow. An open
        // conduit or a dike-fed fissure lets magma start rising on the slowest.
        boolean sealed = cause != Cause.DIKE && conduitOpenness < 0.5;
        ConduitInput in = conduitInput(overpressure);
        ConduitSolution flow = ConduitModel.solve(in, config.conduit(), sealed ? Branch.FASTEST : Branch.SLOWEST);
        if (flow == null) return; // the chamber cannot lift magma to the surface
        conduitInput = in;
        conduit = flow;
        previousSolvedOverpressure = 0;
        previousSolvedRate = 0;
        erupting = true;
        eruptionStartTime = context.time();
        eruptionCount++;
        eruptedVolume = 0;
        slugGas = 0;
        plugGas = 0;
        resealRemaining = 0;
        context.outbox().emit(new MagmaEvents.EruptionStarted(context.time(), config.volcanoId(), overpressure, cause));
        setRegime(context, describe(flow));
    }

    private void endEruption(StepContext context, Cause cause) {
        erupting = false;
        eruptionRate = 0;
        // A plugged outlet leaves the conduit sealed; otherwise it stays open for a while.
        conduitOpenness = cause == Cause.SEALED ? 0 : 1;
        conduit = null;
        conduitInput = null;
        context.outbox().emit(new MagmaEvents.EruptionEnded(
                context.time(), config.volcanoId(), eruptedVolume, context.time() - eruptionStartTime, cause));
        setRegime(context, EruptiveRegime.QUIESCENT);
    }

    // ── Conduit ──

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

    /**
     * Overpressure (MPa) at which a column of bubble-free magma just balances the chamber: magma lighter
     * than the crust rises even below lithostatic pressure.
     */
    public double zeroFlowOverpressureMPa() {
        return -(ROCK_DENSITY - MAGMA_DENSITY) * GRAVITY * config.lithostaticDepth() / 1e6;
    }

    /** Drivers of the conduit flow at a given chamber overpressure. */
    ConduitInput conduitInput(double overpressureMPa) {
        double phi = crystalFraction();
        double pressure = Math.max(0.1, lithostaticPressureMPa() + overpressureMPa) * 1e6;
        return new ConduitInput(pressure, temperature, silicaWt(), waterWt(), meltCo2Wt(),
                exsolvedWaterWt() / 100 * (1 - phi), phi, config.conduitRadius(), config.lithostaticDepth(),
                ventAmbientPa, waterTableDepthM);
    }

    /** Keeps the steady flow up to date with the chamber, staying on the current branch. */
    private ConduitSolution updateConduit() {
        ConduitInput in = conduitInput(overpressure);
        if (conduit != null && in.closeTo(conduitInput, RESOLVE_TOLERANCE)) return conduit;
        ConduitSolution next = conduit == null
                ? ConduitModel.solve(in, config.conduit(), Branch.SLOWEST)
                : ConduitModel.solveNear(in, config.conduit(), conduit.massFluxKgPerS());
        if (conduit != null) {
            previousSolvedOverpressure = solvedOverpressureMPa();
            previousSolvedRate = conduit.dreRateM3PerS();
        }
        conduitInput = in;
        conduit = next;
        return next;
    }

    /** Chamber overpressure (MPa) the current conduit solution was computed at. */
    private double solvedOverpressureMPa() {
        return conduitInput.chamberPressurePa() / 1e6 - lithostaticPressureMPa();
    }

    /** The steady flow while erupting, physical units; {@code null} otherwise. */
    @Override
    public ConduitSolution conduitFlow() {
        return erupting ? conduit : null;
    }

    /**
     * The flow a failure of the conduit as it is now would start (at the failure overpressure, on the
     * branch its openness implies); {@code null} if the chamber could not lift magma to the surface.
     */
    @Override
    public ConduitSolution forecastFlow() {
        double op = Math.max(overpressure, failureOverpressureMPa());
        ConduitInput key = conduitInput(op).rounded();
        Branch branch = conduitOpenness < 0.5 ? Branch.FASTEST : Branch.SLOWEST;
        if (!key.equals(forecastKey)) {
            forecastKey = key;
            forecast = ConduitModel.solve(key, config.conduit(), branch);
        }
        return forecast;
    }

    @Override
    public double ventAmbientPressurePa() {
        return ventAmbientPa;
    }

    /** Names a steady flow (telemetry only). */
    public static EruptiveRegime describe(ConduitSolution flow) {
        if (flow == null) return EruptiveRegime.QUIESCENT;
        if (flow.fragmented()) {
            boolean nearVent = !(flow.fragmentationDepthM() > 50);
            return flow.fragmentation() == ConduitSolution.Fragmentation.INERTIAL && nearVent
                    ? EruptiveRegime.FOUNTAINING : EruptiveRegime.EXPLOSIVE;
        }
        if (flow.segregatedGasFraction() > 0.5) return EruptiveRegime.OPEN_VENT;
        if (flow.exitMeltViscosityLog10() >= 8) return EruptiveRegime.DOME;
        return EruptiveRegime.EFFUSIVE;
    }

    private void setRegime(StepContext context, EruptiveRegime next) {
        if (next == regime) return;
        EruptiveRegime previous = regime;
        regime = next;
        context.outbox().emit(new MagmaEvents.EruptiveRegimeChanged(
                context.time(), config.volcanoId(), previous, next, ascentVelocity(), ventWaterWt()));
    }

    // ── Conduit gas: slug bursts and plug failures ──

    /**
     * Gas reaching the top of the conduit during an eruption: slugs that segregated from fluid magma,
     * plus chamber gas rising through it, coalesce and burst; gas outgassed from stiff lava is trapped
     * beneath its plug until the plug fails.
     */
    private void runConduitGas(StepContext context, ConduitSolution flow, double dt, double chamberWaterKg,
            double chamberCo2Kg) {
        ConduitConfig c = config.conduit();
        double r = config.conduitRadius();

        // In a fragmenting flow the segregated gas streams up with the jet (churn flow): no discrete slugs.
        double coalescing = flow.fragmented() ? 0 : coalescence(Math.pow(10, flow.exitMeltViscosityLog10()));
        double chamberGas = (chamberWaterKg + chamberCo2Kg) * coalescing;
        double slugGasKg = flow.fragmented() ? 0 : flow.slugGasFluxKgPerS() * dt + chamberGas;
        double gasConstant = flow.slugGasFluxKgPerS() > 0 || chamberGas <= 0 ? flow.exitGasConstant()
                : gasConstant(chamberWaterKg, chamberCo2Kg);
        accumulateSlugs(context, slugGasKg, gasConstant);

        // Plug: degassing-induced crystallisation stiffens coherent lava; the gas it outgasses is trapped
        // beneath the stiff cap until the cap fails.
        double stiffness = plugStiffness(flow);
        if (stiffness > 0.01) {
            if (resealRemaining > 0) {
                resealRemaining = Math.max(0, resealRemaining - dt); // gas escapes the broken plug
            } else {
                double passive = flow.passiveGasFluxKgPerS() * dt + (chamberWaterKg + chamberCo2Kg) - chamberGas;
                plugGas += stiffness * passive;
            }
            double strength = stiffness * c.plugStrengthMPa();
            if (plugPressureMPa() >= strength) {
                double gas = plugGas;
                double ejecta = MAGMA_DENSITY * (1 - c.plugPorosity()) * Math.PI * r * r * c.plugCapDepthM();
                queueBurst(new ConduitBurst(context.time(), ConduitBurst.Kind.PLUG, gas, ejecta,
                        plugPressureMPa(), temperature, silicaWt(), burstDuration(c.plugCapDepthM(), strength * 1e6)));
                plugGas = 0;
                // The porous zone reseals once fresh magma has risen through it.
                resealRemaining = c.plugCapDepthM() / Math.max(flow.ascentVelocity(), 1e-9);
            }
        } else {
            plugGas = 0;
            resealRemaining = 0;
        }
    }

    /**
     * Between eruptions, chamber gas rising through the open conduit's standing magma: in fluid magma
     * it coalesces into slugs that burst at the surface (persistent open-vent activity); otherwise it
     * escapes passively.
     */
    private void percolateGas(StepContext context, double dt, double waterKg, double co2Kg) {
        plugGas = 0;
        resealRemaining = 0;
        double gas = (waterKg + co2Kg) * coalescence(Math.pow(10, viscosityLog10()));
        if (gas > 0) accumulateSlugs(context, gas, gasConstant(waterKg, co2Kg));
        else slugGas = 0;
    }

    /** Share of rising gas that coalesces into slugs in magma of viscosity {@code eta} (Pa·s). */
    private double coalescence(double eta) {
        return 1 / (1 + eta / config.conduit().coalescenceViscosity());
    }

    private static double gasConstant(double waterKg, double co2Kg) {
        double total = waterKg + co2Kg;
        return total > 0 ? (waterKg * 461.5 + co2Kg * 188.9) / total : 461.5;
    }

    /**
     * Adds slug gas and bursts every slug that is complete. A slug fills {@link
     * ConduitConfig#slugLengthDiameters()} conduit diameters at the pressure of the melt head it lifts.
     */
    private void accumulateSlugs(StepContext context, double gasKg, double gasConstant) {
        if (!(gasKg > 0)) {
            slugGas = 0;
            return;
        }
        ConduitConfig c = config.conduit();
        SimRandom random = context.random();
        double r = config.conduitRadius();
        double tK = temperature + 273.15;
        slugGas += gasKg;
        double length = c.slugLengthDiameters() * 2 * r;
        double burstOverpressure = MAGMA_DENSITY * GRAVITY * length; // the melt head the slug lifts
        double meanMass = (ventAmbientPa + burstOverpressure) / (gasConstant * tK) * Math.PI * r * r * length;
        if (nextSlugMass <= 0) nextSlugMass = sampleSlugMass(random, meanMass);
        for (int n = 0; slugGas >= nextSlugMass && n < 8; n++) {
            double gas = nextSlugMass;
            slugGas -= gas;
            double ejecta = MAGMA_DENSITY * Math.PI * r * r * 2 * r; // the magma cap over the slug
            queueBurst(new ConduitBurst(context.time(), ConduitBurst.Kind.SLUG, gas, ejecta,
                    burstOverpressure / 1e6, temperature, silicaWt(), burstDuration(length, burstOverpressure)));
            nextSlugMass = sampleSlugMass(random, meanMass);
        }
        // Bound the backlog: a gas flux too high for discrete slugs is continuous churn flow.
        slugGas = Math.min(slugGas, 8 * meanMass);
    }

    /** 0 for soft lava, 1 for a gas-tight crystal-rich plug, from the vent viscosity. */
    double plugStiffness(ConduitSolution flow) {
        if (flow.fragmented()) return 0;
        double x = (flow.exitMeltViscosityLog10() - config.conduit().plugViscosityLog10()) / PLUG_TRANSITION_LOG10;
        return 1 / (1 + Math.exp(-x));
    }

    /** Time (physical s) for a burst to empty a gas pocket of {@code length} at {@code overpressurePa}. */
    private static double burstDuration(double length, double overpressurePa) {
        return Math.max(1, length / Math.sqrt(Math.max(1, overpressurePa) / MAGMA_DENSITY));
    }

    /** Gamma(k = 4) slug masses: quasi-periodic bursts around the mean. */
    private static double sampleSlugMass(SimRandom random, double mean) {
        double sum = 0;
        for (int i = 0; i < 4; i++) sum += random.nextExponential(1);
        return mean * sum / 4;
    }

    /** Pressure of gas trapped beneath a dome plug (ideal gas in the porous upper conduit, MPa). */
    public double plugPressureMPa() {
        ConduitConfig c = config.conduit();
        double r = config.conduitRadius();
        double volume = Math.PI * r * r * c.plugCapDepthM() * c.plugPorosity();
        return plugGas * GAS_CONSTANT * (temperature + 273.15) / (WATER_MOLAR_MASS * volume) / 1e6;
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

    // ── Supply and mixing ──

    private double currentSupply(SimRandom random) {
        double sigma = supplyVariability;
        if (supplyRate == 0 || sigma == 0) return supplyRate;
        return supplyRate * Math.exp(sigma * random.nextGaussian() - 0.5 * sigma * sigma);
    }

    private void inject(double volume, double temperatureC, double silicaWt, double waterWt, double co2Wt,
            double crystals) {
        double stiffness = this.volume * effectiveCompressibility();
        mix(volume, temperatureC, silicaWt, waterWt, co2Wt, crystals);
        overpressure += volume / stiffness;
        relieveRupture(Double.POSITIVE_INFINITY); // an injected batch arrives at once
    }

    /** Overpressure (MPa) at which the chamber walls rupture; never exceeded. */
    public double ruptureOverpressureMPa() {
        return wallRuptureRatio(config) * Math.max(config.tensileStrengthMPa(), failureOverpressureMPa());
    }

    private static double ruptureCap(MagmaChamberConfig c) {
        return wallRuptureRatio(c) * Math.max(c.tensileStrengthMPa(), c.conduit().reopenOverpressureMPa());
    }

    /**
     * Hoop stress at the wall of a pressurised spherical cavity is half its overpressure, so the walls fail in
     * tension at twice the tensile strength (Tait, Jaupart & Vergniolle 1989) — unless the config overrides it.
     */
    static final double HOOP_RUPTURE_RATIO = 2.0;

    /** The rupture limit in use, as a multiple of the roof strength or eruption threshold (override or computed). */
    public double wallRuptureRatio() {
        return wallRuptureRatio(config);
    }

    static double wallRuptureRatio(MagmaChamberConfig c) {
        return Double.isNaN(c.wallRuptureRatio()) ? HOOP_RUPTURE_RATIO : c.wallRuptureRatio();
    }

    /** Wall-rock creep: Arrhenius viscosity η = η₀·exp(E/R·(1/T − 1/T₀)) (crustal rock near a chamber). */
    static final double WALL_VISCOSITY_REF_PAS = 1e19; // at 700 °C (Jellinek & DePaolo 2003)
    static final double WALL_VISCOSITY_REF_K = 973.15;
    static final double WALL_ACTIVATION_J_MOL = 3.0e5;

    /** Viscosity of the wall rock (Pa·s) at the configured wall temperature, clamped to 10¹⁶–10²⁵. */
    public double wallViscosityPaS() {
        double t = config.wallTemperatureC() + 273.15;
        double eta = WALL_VISCOSITY_REF_PAS * StrictMath.exp(WALL_ACTIVATION_J_MOL / GAS_CONSTANT
                * (1 / Math.max(t, 200) - 1 / WALL_VISCOSITY_REF_K));
        return Math.min(1e25, Math.max(1e16, eta));
    }

    /**
     * Share of rupture magma the walls absorb by yielding. Computed unless overridden: walls relax viscously
     * over {@code τ_r = η / ΔP}; recharge brings the chamber to its rupture limit over {@code τ_c = ΔP_c·dV/dP / Q}.
     * When {@code τ_c ≫ τ_r} the hot walls creep and the chamber grows; when {@code τ_c ≪ τ_r} they fracture and
     * dikes carry the magma (Jellinek & DePaolo 2003): {@code f = τ_c / (τ_c + τ_r)}.
     */
    public double wallYieldFraction() {
        return wallYieldFraction(supplyRate);
    }

    /** {@link #wallYieldFraction()} for a chamber charged at {@code chargeRate} (m³/s; infinite = a sudden batch). */
    double wallYieldFraction(double chargeRate) {
        if (!Double.isNaN(config.wallYieldFraction())) return config.wallYieldFraction();
        if (!(chargeRate < Double.POSITIVE_INFINITY)) return 0;
        double cap = ruptureOverpressureMPa();
        double relax = wallViscosityPaS() / (cap * 1e6);
        double charge = cap * volume * effectiveCompressibility() / Math.max(chargeRate, 1e-12);
        return charge / (charge + relax);
    }

    /**
     * Wall failure: overpressure above {@link #ruptureOverpressureMPa()} cannot be stored elastically and
     * the pressure stays at that limit. The excess magma leaves the chamber: {@code wallYieldFraction} of it
     * is taken up by the walls yielding (inelastic growth); the rest fractures its way out and waits for a
     * dike to carry it ({@link #takeRuptureExcess()}). Returns the volume that left the elastic chamber (m³).
     */
    private double relieveRupture(double chargeRate) {
        double cap = ruptureOverpressureMPa();
        if (!(overpressure > cap)) return 0;
        double excess = (overpressure - cap) * volume * effectiveCompressibility();
        overpressure = cap;
        double yielded = wallYieldFraction(chargeRate) * excess;
        volume += yielded;
        inelasticGrowth += yielded;
        ruptureExcess += excess - yielded;
        return excess;
    }

    private void absorbRuptureExcess() {
        if (!(ruptureExcess > 0)) return;
        if (!ruptureOffered) {
            ruptureOffered = true; // dikes step before the chamber's next step and may still take it
            return;
        }
        ruptureOffered = false;
        volume += ruptureExcess;
        inelasticGrowth += ruptureExcess;
        ruptureExcess = 0;
    }

    /** Magma that left the chamber into dikes so far (m³). */
    public double intrudedVolumeM3() {
        return intrudedVolume;
    }

    /** Magma stored by the walls yielding so far (inelastic chamber growth, m³). */
    public double wallGrowthM3() {
        return inelasticGrowth;
    }

    /**
     * Overpressure an erupting chamber settles at, where outflow balances supply (MPa); above
     * {@link #ruptureOverpressureMPa()} the walls fail before it is reached. NaN while not erupting.
     */
    public double balanceOverpressureMPa() {
        return balanceOverpressure;
    }

    /** Magma beyond the rupture limit waiting for a dike (m³); a dike that starts now carries it. */
    public double ruptureExcessM3() {
        return ruptureExcess;
    }

    /** Hands the waiting rupture magma to a dike; returns its volume (m³). */
    public double takeRuptureExcess() {
        double v = ruptureExcess;
        ruptureExcess = 0;
        ruptureOffered = false;
        intrudedVolume += v;
        return v;
    }

    /** Magma stored by inelastic growth after wall failure (m³). */
    @Override
    public double inelasticVolumeChangeM3() {
        return inelasticGrowth + ruptureExcess;
    }

    /**
     * Mixes {@code volume} of magma into the chamber by mass (SiO₂, H₂O, CO₂) and enthalpy. Melt
     * carries latent heat that crystals have already released, so the mixture's temperature is found
     * from the combined enthalpy with the chamber's own crystallinity–temperature relation.
     */
    private void mix(double volume, double temperatureC, double silicaWt, double waterWt, double co2Wt,
            double crystals) {
        if (!(volume > 0)) return;
        double f = volume / (this.volume + volume);
        double melt = 1 - Math.max(0, Math.min(1, crystals));
        double enthalpy = enthalpy(temperature, crystalFraction());
        enthalpy += f * (enthalpy(temperatureC, crystals) - enthalpy);
        bulkSilica += f * (silicaWt - bulkSilica);
        bulkWater += f * (waterWt * melt - bulkWater);
        bulkCo2 += f * (co2Wt * melt - bulkCo2);
        temperature = temperatureForEnthalpy(enthalpy);
    }

    /** Specific enthalpy (J/kg): sensible heat plus the latent heat still held by the melt. */
    static double enthalpy(double temperatureC, double crystalFraction) {
        double crystallised = Math.max(0, Math.min(1, crystalFraction / MAX_CRYSTAL_FRACTION));
        return MELT_HEAT_CAPACITY * temperatureC + LATENT_HEAT_CRYSTALLISATION * (1 - crystallised);
    }

    /** Temperature at which the chamber's magma has specific enthalpy {@code h} (monotonic: bisection). */
    private double temperatureForEnthalpy(double h) {
        double lo = -273;
        double hi = 3000;
        for (int i = 0; i < 60; i++) {
            double mid = 0.5 * (lo + hi);
            if (enthalpy(mid, crystalFraction(mid, bulkSilica)) < h) lo = mid;
            else hi = mid;
        }
        return 0.5 * (lo + hi);
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

    /** Lets this chamber erupt through the summit conduit (the main chamber) or not (deeper or side chambers). */
    public void setEruptive(boolean eruptive) {
        this.eruptive = eruptive;
    }

    public boolean eruptive() {
        return eruptive;
    }

    /**
     * Magma arriving from another chamber of the plumbing at {@code rateM3PerS}: mixed in, raising the
     * overpressure by {@code volume / (V β)}; beyond the walls' limit it ruptures them like recharge does.
     */
    public void transferIn(double volume, double rateM3PerS, double temperatureC, double silicaWt, double waterWt, double co2Wt,
            double crystals) {
        if (!(volume > 0)) return;
        double stiffness = this.volume * effectiveCompressibility();
        mix(volume, temperatureC, silicaWt, waterWt, co2Wt, crystals);
        overpressure += volume / stiffness;
        relieveRupture(rateM3PerS);
        transferredIn += volume;
    }

    /** Magma leaving for another chamber: overpressure drops by {@code volume / (V β)}. Returns the drop (MPa). */
    public double transferOut(double volume) {
        if (!(volume > 0)) return 0;
        double drop = volume / (this.volume * effectiveCompressibility());
        overpressure -= drop;
        transferredOut += volume;
        return drop;
    }

    /** Magma received from other chambers so far (m³). */
    public double transferredInM3() {
        return transferredIn;
    }

    /** Magma sent to other chambers so far (m³). */
    public double transferredOutM3() {
        return transferredOut;
    }

    /** Magma density used for magmastatic heads (kg/m³). */
    public static double magmaDensity() {
        return MAGMA_DENSITY;
    }

    /** Country-rock density for lithostatic pressure (kg/m³). */
    public static double rockDensity() {
        return ROCK_DENSITY;
    }

    public double withdraw(double volume) {
        if (!(volume > 0)) return 0;
        intrudedVolume += volume;
        double drop = volume / (this.volume * effectiveCompressibility());
        overpressure -= drop;
        return drop;
    }

    /**
     * The chamber roof subsided into the chamber by {@code volume} (piston caldera or pit collapse):
     * the reverse of {@link #withdraw}, overpressure rises by {@code volume / (V β)}.
     *
     * @return the overpressure rise (MPa)
     */
    public double compress(double volume) {
        if (!(volume > 0)) return 0;
        double rise = volume / (config.volume() * effectiveCompressibility());
        overpressure += rise;
        return rise;
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

    private double absolutePressureMPa() {
        return Math.max(0.1, lithostaticPressureMPa() + overpressure);
    }

    /** H₂O solubility in the melt at chamber pressure (wt%). */
    public double waterSolubilityWt() {
        return ConduitModel.SOLUBILITY * Math.sqrt(absolutePressureMPa());
    }

    /** CO₂ solubility in the melt at chamber pressure (wt%, Henry's law). */
    public double co2SolubilityWt() {
        return ConduitModel.CO2_SOLUBILITY * absolutePressureMPa();
    }

    /** Water exsolved as a gas phase, per unit melt (wt%). */
    public double exsolvedWaterWt() {
        return Math.max(0, meltTotalWater() - waterSolubilityWt());
    }

    /** CO₂ exsolved as a gas phase, per unit melt (wt%). */
    public double exsolvedCo2Wt() {
        return Math.max(0, meltCo2Wt() - co2SolubilityWt());
    }

    private double meltTotalWater() {
        return bulkWater / (1 - crystalFraction());
    }

    /** Total CO₂ of the melt (dissolved and exsolved, wt%). */
    public double meltCo2Wt() {
        return Math.max(0, bulkCo2) / (1 - crystalFraction());
    }

    /** Volume fraction of exsolved gas (H₂O + CO₂) in the magma. */
    public double gasVolumeFraction() {
        double melt = 1 - crystalFraction();
        double moles = exsolvedWaterWt() / 100 * melt / WATER_MOLAR_MASS + exsolvedCo2Wt() / 100 * melt / CO2_MOLAR_MASS;
        if (moles <= 0) return 0;
        double gasVolume = moles * GAS_CONSTANT * (temperature + 273.15) / (absolutePressureMPa() * 1e6); // per kg
        double ratio = gasVolume * MAGMA_DENSITY;
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
     * True when erupting magma fragments in the conduit or at the vent. Before an eruption: whether
     * failure of the conduit as it is now would.
     */
    public boolean fragmented() {
        ConduitSolution flow = erupting ? conduit : forecastFlow();
        return flow != null && flow.fragmented();
    }

    /** log10 of the viscosity (Pa·s) of the lava at the vent (current or forecast flow). */
    public double conduitViscosityLog10() {
        ConduitSolution flow = erupting ? conduit : forecastFlow();
        return flow != null ? flow.exitMeltViscosityLog10() : viscosityLog10();
    }

    @Override
    public EruptiveRegime eruptiveRegime() {
        return regime;
    }

    /** Dissolved H₂O reaching the vent (wt%); the chamber value when not erupting. */
    @Override
    public double ventWaterWt() {
        return erupting && conduit != null ? conduit.exitDissolvedWaterWt() : waterWt();
    }

    /** Magma ascent speed in the lower conduit (m/s); 0 when not erupting. */
    public double ascentVelocity() {
        return erupting && conduit != null ? conduit.ascentVelocity() : 0;
    }

    // ── MagmaState ──

    @Override
    public double physicalDepthM() {
        return config.lithostaticDepth();
    }

    @Override
    public double volumeM3() {
        return volume;
    }

    /**
     * Heat lost through the wall by the conductive cooling of {@link #step}: the temperature relaxes
     * towards the wall temperature with e-folding time {@code τ}, which at bulk density {@code ρ} and
     * effective heat capacity {@code c_eff} (specific heat plus latent heat of crystallisation spread
     * over the liquidus–solidus interval) is a power {@code P = ρ·c_eff·V·(T − T_wall)/τ}.
     */
    @Override
    public double wallHeatPowerW() {
        double excess = temperature - config.wallTemperatureC();
        if (!(excess > 0)) return 0;
        double liquidus = liquidusC();
        double cEff = MELT_HEAT_CAPACITY;
        if (temperature > SOLIDUS_C && temperature < liquidus && liquidus > SOLIDUS_C) {
            cEff += LATENT_HEAT_CRYSTALLISATION / (liquidus - SOLIDUS_C);
        }
        return MAGMA_DENSITY * cEff * volume * excess / config.coolingTimescale();
    }

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
        return crystalFraction(temperature, bulkSilica);
    }

    static double crystalFraction(double temperatureC, double bulkSilicaWt) {
        double liquidus = liquidusC(bulkSilicaWt);
        double x = (liquidus - temperatureC) / (liquidus - SOLIDUS_C);
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

    public double bulkCo2Wt() {
        return bulkCo2;
    }

    public double supplyRate() {
        return supplyRate;
    }

    /** The magma the deep supply currently delivers (settable with {@link SetSupplyMagma}). */
    public record SupplyMagma(double rate, double temperatureC, double silicaWt, double waterWt, double co2Wt,
            double crystalFraction, double variability) {}

    public SupplyMagma supply() {
        return new SupplyMagma(supplyRate, rechargeTemperature, rechargeSilica, rechargeWater, rechargeCo2,
                rechargeCrystals, supplyVariability);
    }

    /**
     * Sets the deep magma supply (m³/s) directly; call between steps (e.g. when a world applies a
     * changed definition to restored state). Running hosts use {@code MagmaCommands.SetSupplyRate}.
     */
    public void setSupplyRate(double rate) {
        if (!(rate >= 0)) throw new IllegalArgumentException("supply rate must be >= 0");
        this.supplyRate = rate;
    }

    /**
     * Re-applies the configured supply (rate, variability and magma) over the saved one; call between
     * steps when a world's definition changed.
     */
    public void resetSupplyFromConfig() {
        supplyRate = config.supplyRate();
        supplyVariability = config.supplyVariability();
        rechargeTemperature = config.rechargeTemperatureC();
        rechargeSilica = config.rechargeSilicaWt();
        rechargeWater = config.rechargeWaterWt();
        rechargeCo2 = config.rechargeCo2Wt();
        rechargeCrystals = config.rechargeCrystalFraction();
    }

    /**
     * Number of eruptions started so far: the current (or most recent) eruption's id. Deposits are
     * attributed to it, so stratigraphy shows one unit per eruption. 0 before the first eruption.
     */
    public int eruptionCount() {
        return eruptionCount;
    }

    /** Simulation time (s) at which the current or most recent eruption started. */
    public double eruptionStartTime() {
        return eruptionStartTime;
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
        out.addProperty("outletCapacity", outletCapacity);
        out.addProperty("summitBlocked", summitBlocked);
        out.addProperty("overpressure", overpressure);
        out.addProperty("volume", volume);
        out.addProperty("inelasticGrowth", inelasticGrowth);
        out.addProperty("ruptureExcess", ruptureExcess);
        out.addProperty("ruptureOffered", ruptureOffered);
        out.addProperty("intrudedVolume", intrudedVolume);
        out.addProperty("transferredIn", transferredIn);
        out.addProperty("transferredOut", transferredOut);
        out.addProperty("temperature", temperature);
        out.addProperty("bulkSilica", bulkSilica);
        out.addProperty("bulkWater", bulkWater);
        out.addProperty("bulkCo2", bulkCo2);
        out.addProperty("supplyRate", supplyRate);
        out.addProperty("supplyVariability", supplyVariability);
        out.addProperty("rechargeTemperature", rechargeTemperature);
        out.addProperty("rechargeSilica", rechargeSilica);
        out.addProperty("rechargeWater", rechargeWater);
        out.addProperty("rechargeCo2", rechargeCo2);
        out.addProperty("rechargeCrystals", rechargeCrystals);
        out.addProperty("erupting", erupting);
        out.addProperty("eruptionStartTime", eruptionStartTime);
        out.addProperty("eruptionCount", eruptionCount);
        out.addProperty("lastTime", lastTime);
        out.addProperty("eruptedVolume", eruptedVolume);
        out.addProperty("eruptionRate", eruptionRate);
        out.addProperty("overpressureRate", overpressureRate);
        out.addProperty("regime", regime.name());
        out.addProperty("conduitOpenness", conduitOpenness);
        out.addProperty("ventAmbientPa", ventAmbientPa);
        out.addProperty("waterTableDepthM", waterTableDepthM);
        if (conduitInput != null && conduit != null) {
            out.add("conduitInput", ConduitJson.write(conduitInput));
            out.add("conduit", ConduitJson.write(conduit));
            out.addProperty("previousSolvedOverpressure", previousSolvedOverpressure);
            out.addProperty("previousSolvedRate", previousSolvedRate);
        }
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
        outletCapacity = number(in, "outletCapacity", 1);
        summitBlocked = in.has("summitBlocked") && in.get("summitBlocked").getAsBoolean();
        overpressure = in.get("overpressure").getAsDouble();
        volume = in.has("volume") ? in.get("volume").getAsDouble() : config.volume();
        inelasticGrowth = in.has("inelasticGrowth") ? in.get("inelasticGrowth").getAsDouble() : 0;
        ruptureExcess = in.has("ruptureExcess") ? in.get("ruptureExcess").getAsDouble() : 0;
        ruptureOffered = in.has("ruptureOffered") && in.get("ruptureOffered").getAsBoolean();
        intrudedVolume = in.has("intrudedVolume") ? in.get("intrudedVolume").getAsDouble() : 0;
        transferredIn = in.has("transferredIn") ? in.get("transferredIn").getAsDouble() : 0;
        transferredOut = in.has("transferredOut") ? in.get("transferredOut").getAsDouble() : 0;
        temperature = in.get("temperature").getAsDouble();
        bulkSilica = in.get("bulkSilica").getAsDouble();
        bulkWater = in.get("bulkWater").getAsDouble();
        bulkCo2 = number(in, "bulkCo2", config.initialCo2Wt());
        supplyRate = in.get("supplyRate").getAsDouble();
        supplyVariability = number(in, "supplyVariability", config.supplyVariability());
        rechargeTemperature = number(in, "rechargeTemperature", config.rechargeTemperatureC());
        rechargeSilica = number(in, "rechargeSilica", config.rechargeSilicaWt());
        rechargeWater = number(in, "rechargeWater", config.rechargeWaterWt());
        rechargeCo2 = number(in, "rechargeCo2", config.rechargeCo2Wt());
        rechargeCrystals = number(in, "rechargeCrystals", config.rechargeCrystalFraction());
        erupting = in.get("erupting").getAsBoolean();
        eruptionStartTime = in.get("eruptionStartTime").getAsDouble();
        eruptionCount = in.has("eruptionCount") ? in.get("eruptionCount").getAsInt() : 0;
        lastTime = in.get("lastTime").getAsDouble();
        eruptedVolume = in.get("eruptedVolume").getAsDouble();
        eruptionRate = in.get("eruptionRate").getAsDouble();
        overpressureRate = in.get("overpressureRate").getAsDouble();
        regime = in.has("regime") ? EruptiveRegime.valueOf(in.get("regime").getAsString()) : EruptiveRegime.QUIESCENT;
        conduitOpenness = number(in, "conduitOpenness", config.conduit().initialOpenness());
        ventAmbientPa = number(in, "ventAmbientPa", ConduitInput.ATMOSPHERE_PA);
        waterTableDepthM = number(in, "waterTableDepthM", DEFAULT_WATER_TABLE_DEPTH_M);
        if (in.has("conduitInput") && in.has("conduit")) {
            conduitInput = ConduitJson.readInput(in.getAsJsonObject("conduitInput"));
            conduit = ConduitJson.readSolution(in.getAsJsonObject("conduit"));
            previousSolvedOverpressure = number(in, "previousSolvedOverpressure", 0);
            previousSolvedRate = number(in, "previousSolvedRate", 0);
        } else {
            conduitInput = null;
            conduit = null;
        }
        slugGas = number(in, "slugGas", 0);
        nextSlugMass = number(in, "nextSlugMass", 0);
        plugGas = number(in, "plugGas", 0);
        resealRemaining = number(in, "resealRemaining", 0);
        forecastKey = null;
        forecast = null;
        bursts.clear();
        if (in.has("bursts")) {
            for (JsonElement e : in.getAsJsonArray("bursts")) {
                JsonObject o = e.getAsJsonObject();
                bursts.addLast(new ConduitBurst(o.get("time").getAsDouble(), ConduitBurst.Kind.parse(o.get("kind").getAsString()),
                        o.get("gas").getAsDouble(), o.get("ejecta").getAsDouble(), o.get("overpressure").getAsDouble(),
                        o.get("temperature").getAsDouble(), o.get("silica").getAsDouble(), o.get("duration").getAsDouble()));
            }
        }
    }

    private static double number(JsonObject in, String key, double fallback) {
        return in.has(key) ? in.get(key).getAsDouble() : fallback;
    }
}
