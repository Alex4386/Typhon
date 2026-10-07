package me.alex4386.typhon.engine.assembly;

import me.alex4386.typhon.engine.magma.conduit.ConduitInput;
import me.alex4386.typhon.engine.magma.conduit.ConduitModel;
import me.alex4386.typhon.engine.magma.conduit.ConduitSolution;
import me.alex4386.typhon.engine.magma.conduit.ConduitSolution.Fragmentation;
import me.alex4386.typhon.engine.tephra.GrainSizeDistribution;

/**
 * Continuous partition of the conduit's outflow at the vent into lava, fountain fall-back, ballistics,
 * an eruption column and its collapsing share, and magma–water explosions. No eruption style is chosen:
 * each share follows from the flow and from clast and water physics (see {@code
 * docs/eruption-dynamics.md}, "Surface partition").
 *
 * <ul>
 *   <li><b>Jet.</b> A choked jet keeps expanding to ambient pressure above the vent:
 *       {@code u_j² = u_v² + 2 n R T ln(p_v / p_a)}.
 *   <li><b>Clasts.</b> Fragments are log-normal around a median set by how the melt broke: brittle
 *       and foam fragmentation of viscous melt gives fine ash (finer the more gas expanded it), inertial
 *       tearing of fluid melt gives centimetre clots (Namiki &amp; Manga 2008). The jet supports clasts
 *       whose settling speed it exceeds, {@code d* = 3 C_d ρ_g u_j² / (4 ρ_p g)}; larger ones, and
 *       anything over 64 mm, fall back around the vent.
 *   <li><b>Fall-back.</b> Coarse clasts rise to {@code u_j²/2g} and land hot if their flight is short
 *       against their cooling time {@code ρ c_p d / 6h}; a dense fountain shields its interior. Hot
 *       landings coalesce into clastogenic lava; the rest is scoria and spatter (ballistics).
 *   <li><b>Column.</b> Supported fine clasts form the column; the share that collapses rises
 *       continuously as the jet's gas content falls below the Woods (1988) collapse threshold.
 *   <li><b>Water.</b> External water (sea or lake over an open crater, groundwater flowing into the
 *       conduit) meets the magma at a water/magma mass ratio {@code R}; the fragmentation efficiency
 *       peaks near {@code R ≈ 0.3} (Wohletz &amp; Sheridan 1983) and hydrostatic pressure suppresses
 *       steam expansion with depth. Water-fragmented magma is fine ash; its wet share falls out near
 *       the vent as tuff.
 * </ul>
 *
 * All rates are physical (kg per physical second); temperatures in °C.
 */
public final class VentPartition {
    static final double GRAVITY = 9.81;
    static final double R_STEAM = 461.5;
    static final double CLAST_DENSITY = 1500;
    static final double DRAG_COEFFICIENT = 1.0;
    /** Fragments larger than this (m) are ballistic whatever the jet. */
    static final double BALLISTIC_SIZE = 0.064;
    static final double SIGMA_LN = 1.5;
    /** Clasts finer than this (m) collapse into density currents; coarser ones rain back. */
    static final double ASH_SIZE = 1e-3;
    /** Median clast of inertially torn fluid melt (m). */
    static final double INERTIAL_MEDIAN = 0.02;
    /** Median of brittle/foam fragmentation at negligible expansion (m). */
    static final double BRITTLE_MEDIAN = 5e-4;
    /**
     * Median of magma–water (Surtseyan) fragmentation (m): Surtsey's tephra has Md_φ ≈ 1–2.5, i.e. 0.2–0.5 mm,
     * very poorly sorted (Walker &amp; Croasdale 1972; Thorarinsson 1967).
     */
    static final double WATER_MEDIAN = 3e-4;
    /**
     * Water/magma mass ratio below which water cannot reach all the magma (it is consumed before contact
     * spreads through the melt); above it every bit of magma passing a flooded vent meets water (Wohletz &amp;
     * Sheridan 1983: interaction regimes begin at R ≈ 0.1).
     */
    static final double CONTACT_WATER_RATIO = 0.1;
    /**
     * Share of the fine (plume-supported) wet ash that aggregates (accretionary lapilli, ash clusters) in the
     * steam-saturated plume and falls out near the vent with the jets: wet aggregation removes most fine ash
     * proximally (Van Eaton et al. 2012; Brown, Bonadonna &amp; Durant 2012).
     */
    static final double WET_AGGREGATION = 0.7;
    /** Latent heat of vaporisation of water (J/kg). */
    static final double WATER_LATENT_HEAT = 2.26e6;
    static final double CLAST_HEAT_CAPACITY = 1100;
    /** Heat transfer coefficient of a clast in flight (W/m²/K, forced convection + radiation). */
    static final double CLAST_HEAT_TRANSFER = 700;
    /** Mass flux (kg/s) at which a fountain becomes optically thick and shields its interior. */
    static final double OPAQUE_FOUNTAIN_FLUX = 1e5;
    /** Water/magma mass ratio of peak fragmentation efficiency (Wohletz &amp; Sheridan 1983). */
    static final double OPTIMAL_WATER_RATIO = 0.3;
    /**
     * Water depth (m) below which hydrostatic pressure suppresses explosive steam expansion entirely
     * (explosive interaction is confined to the upper ~100–200 m; Kokelaar 1986).
     */
    static final double SUPPRESSION_DEPTH_M = 150;
    static final double WATER_DENSITY = 1000;
    static final double AIR_DENSITY = 1.0;
    static final double WATER_HEAT_CAPACITY = 4200;
    static final double MAGMA_HEAT_CAPACITY = 1200;
    /** Aquifer permeability (m²) of fractured volcanic rock feeding groundwater into a conduit. */
    static final double AQUIFER_PERMEABILITY = 1e-11;
    static final double WATER_VISCOSITY = 1e-3;
    /** Depth range (m) below the water table over which groundwater can reach the magma. */
    static final double AQUIFER_INTERACTION_DEPTH_M = 300;

    /**
     * Water that can reach the magma at the vent.
     *
     * @param surfaceDepthM water standing over the vent floor (m)
     * @param openFraction share of the crater rim open to that water (0–1): open water is resupplied freely
     * @param waterTableDepthM depth of the water table below the vent (m; ≤ 0 when it stands above)
     * @param slurryFraction how much of the vent mouth is filled with water-saturated loose tephra (0–1)
     * @param slurryPorosity porosity of that fill
     * @param seepageKgPerS water that can seep into a crater cut off from open water, through the
     *     saturated edifice (kg/s); NaN when the crater is open to the sea or a lake (no limit)
     */
    public record Water(double surfaceDepthM, double openFraction, double waterTableDepthM, double slurryFraction,
            double slurryPorosity, double seepageKgPerS) {
        public static final Water DRY = new Water(0, 0, Double.POSITIVE_INFINITY);

        /** Open water and groundwater only (no vent fill). */
        public Water(double surfaceDepthM, double openFraction, double waterTableDepthM) {
            this(surfaceDepthM, openFraction, waterTableDepthM, 0, 0, Double.NaN);
        }
    }

    /**
     * The partition of one steady vent flow.
     *
     * @param magmaMassFlux magma leaving the vent (kg/s)
     * @param lavaMassFlux coherent lava plus clastogenic lava from hot fall-back (kg/s)
     * @param clastogenicMassFlux of which from hot fall-back (kg/s)
     * @param ballisticMassFlux cooled fall-back and bombs (kg/s)
     * @param ballisticSpeed launch speed of ballistics (m/s)
     * @param fountainHeightM height coarse clasts reach (m)
     * @param columnMassFlux tephra in the column, magmatic and phreatomagmatic (kg/s): lofted plus
     *     the ash that collapses into PDCs
     * @param collapseFraction share of the column that collapses into PDCs (0–1)
     * @param columnGasFraction gas mass fraction of the column mixture at the vent
     * @param columnTemperatureC its temperature
     * @param columnVelocity its exit velocity (m/s)
     * @param grainSize grain-size distribution of the lofted tephra
     * @param waterMagmaRatio water/magma mass ratio of the interaction (0 if none)
     * @param waterFragmentedMassFlux magma fragmented by water (kg/s)
     * @param wetFalloutMassFlux wet tephra and quench-granulated lava (hyaloclastite) piling up near the
     *     vent as tuff (kg/s)
     * @param steamMassFlux steam raised (kg/s)
     * @param jetMassFlux coarse water-fragmented ejecta in cock's-tail jets (kg/s)
     * @param jetSpeed their launch speed (m/s)
     * @param medianClastM median clast diameter of magmatic fragmentation (m; NaN if coherent)
     */
    public record Result(
            double magmaMassFlux,
            double lavaMassFlux,
            double clastogenicMassFlux,
            double ballisticMassFlux,
            double ballisticSpeed,
            double fountainHeightM,
            double columnMassFlux,
            double collapseFraction,
            double columnGasFraction,
            double columnTemperatureC,
            double columnVelocity,
            GrainSizeDistribution grainSize,
            double waterMagmaRatio,
            double waterFragmentedMassFlux,
            double wetFalloutMassFlux,
            double steamMassFlux,
            double jetMassFlux,
            double jetSpeed,
            double medianClastM) {

        /** Share of the magma that leaves as lava. */
        public double lavaShare() {
            return magmaMassFlux > 0 ? lavaMassFlux / magmaMassFlux : 0;
        }
    }

    /** Collapse threshold of the column's gas fraction, for given mass flux, velocity and temperature. */
    public interface CollapseThreshold {
        double criticalGasFraction(double massFlux, double velocity, double temperatureC);
    }

    private VentPartition() {}

    /**
     * @param flow the steady conduit flow (physical)
     * @param ambientPa pressure at the vent
     * @param conduitRadiusM conduit radius
     * @param silicaWt SiO₂ of the magma (grain-size tails)
     * @param water water that can reach the vent
     * @param collapse collapse threshold of the column (Woods 1988)
     */
    public static Result partition(ConduitSolution flow, double ambientPa, double conduitRadiusM, double silicaWt,
            Water water, CollapseThreshold collapse) {
        double magma = flow.magmaMassFluxKgPerS();
        double tC = flow.exitTemperatureC();
        double tK = tC + 273.15;

        // Water–magma interaction: open water reaching the conduit, and (Surtseyan) magma rising into a vent
        // filled with water-saturated tephra.
        double openRatio = waterMagmaRatio(water, conduitRadiusM, magma);
        double slurry = Math.max(0, Math.min(1, water.slurryFraction()));
        double slurryRatio = slurry > 0 ? slurryRatio(water, conduitRadiusM, magma) : 0;
        double ratio = slurry * slurryRatio + (1 - slurry) * openRatio;
        double submergence = Math.min(1, Math.max(0, water.surfaceDepthM()) / SUPPRESSION_DEPTH_M);
        double suppression = (1 - submergence) * (1 - submergence);
        // Clasts falling back into water standing in the crater are quenched, never welded into lava.
        boolean flooded = water.surfaceDepthM() > 0;
        // Two separate things (they were conflated before): how much of the magma meets water at all, and how
        // explosively the part that does reacts. Contact is set by water supply: magma rising through a flooded
        // or slurry-filled vent all meets water once R exceeds ~0.1. The Wohletz–Sheridan efficiency curve (with
        // hydrostatic suppression) is the thermal-to-mechanical conversion of that interaction: it sets the
        // explosive share; in shallow water the rest is quenched and granulated in place (hyaloclastite,
        // Kokelaar 1983, 1986), deeper it carries on as it would have (pillows, quenched fall-back).
        double contact = slurry * contactShare(slurryRatio) + (1 - slurry) * contactShare(openRatio);
        double efficiency = (slurry * interactionEfficiency(slurryRatio) + (1 - slurry) * interactionEfficiency(openRatio))
                * suppression;
        double explosive = magma * contact * Math.min(1, efficiency);
        double granulatedWet = (magma * contact - explosive) * (1 - submergence);
        double wetMagma = explosive + granulatedWet;
        double dryMagma = magma - wetMagma;

        // Magmatic jet.
        double gas = Math.max(0, flow.exitGasMassFraction());
        double gasConstant = flow.exitGasConstant();
        double expansion = flow.choked() && flow.exitPressurePa() > ambientPa
                ? 2 * gas * gasConstant * tK * Math.log(flow.exitPressurePa() / ambientPa) : 0;
        double jet = Math.sqrt(flow.exitVelocity() * flow.exitVelocity() + expansion);

        double lava = 0;
        double clastogenic = 0;
        double ballistic = 0;
        double fountainHeight = 0;
        double magmaticColumn = 0;
        double median = Double.NaN;
        double cutoff = BALLISTIC_SIZE;
        double hot = 0;
        double quenched = 0;
        if (!flow.fragmented()) {
            // Coherent lava meeting the water of a flooded crater granulates into hyaloclastite at
            // shallow depth; deeper it extrudes as pillows.
            double granulated = flooded ? dryMagma * (1 - submergence) : 0;
            lava = dryMagma - granulated;
            quenched = granulated;
        } else {
            median = flow.fragmentation() == Fragmentation.INERTIAL
                    ? INERTIAL_MEDIAN : BRITTLE_MEDIAN / (1 + 20 * flow.exitGasVolumeFraction() * gas / 0.05);
            // The jet carries clasts it outruns, but only those the buoyant plume above can hold up stay
            // aloft; the rest fall back within the fountain.
            double gasDensity = ambientPa / (gasConstant * tK);
            double jetSupported = 3 * DRAG_COEFFICIENT * gasDensity * jet * jet / (4 * CLAST_DENSITY * GRAVITY);
            cutoff = Math.min(Math.min(jetSupported, plumeSupportedSize(dryMagma)), BALLISTIC_SIZE);
            double coarse = 1 - lognormalCdf(cutoff, median);
            double fallBack = dryMagma * coarse;
            magmaticColumn = dryMagma - fallBack;
            fountainHeight = jet * jet / (2 * GRAVITY);
            // Coarse clasts above the median of the fall-back size range dominate its mass.
            double clast = Math.max(cutoff, median) * Math.exp(SIGMA_LN * SIGMA_LN / 2);
            double coolingTime = CLAST_DENSITY * CLAST_HEAT_CAPACITY * clast / (6 * CLAST_HEAT_TRANSFER);
            double flightTime = 2 * jet / GRAVITY;
            double opacity = 1 - Math.exp(-fallBack / OPAQUE_FOUNTAIN_FLUX);
            double exposed = flightTime * (1 - opacity);
            hot = flooded ? 0 : Math.exp(-exposed / coolingTime); // share still molten on landing
            clastogenic = fallBack * hot;
            ballistic = fallBack - clastogenic;
            lava = clastogenic;
        }

        // Under water the magmatic jet is braked and quenched: only a vent shallow enough breaks the surface
        // (the same hydrostatic suppression as for the steam interaction, nothing below SUPPRESSION_DEPTH_M).
        // What stays under water — the fall-back clasts and the fines the jet would have lofted — is chilled in
        // place and piles up at the vent as hyaloclastite (Kokelaar 1986; Head &amp; Wilson 2003), instead of
        // flying as bombs or feeding an eruption column.
        if (flooded) {
            double breach = suppression;
            double drowned = (ballistic + magmaticColumn) * (1 - breach);
            ballistic *= breach;
            magmaticColumn *= breach;
            fountainHeight *= breach;
            quenched += drowned;
        }

        // Water-fragmented magma: fine ash, steam-driven; its wet share falls out as tuff, its coarse
        // tail flies in jets.
        double steam = 0;
        double wetFallout = 0;
        double jetMass = 0;
        double jetSpeed = 0;
        double wetColumn = 0;
        double wetGas = 0;
        double wetCutoff = BALLISTIC_SIZE;
        double wetTemperature = tC;
        if (wetMagma > 0) {
            // the quenched remainder granulates where it meets the water and piles up at the vent
            wetFallout = granulatedWet;
            // Steam raised by the explosive share: the magma's heat above 100 °C boils at most
            // c_m (T − 100) / (c_w · 80 K + L) of water per kg (~0.5), and no more than the water present.
            double boilable = MAGMA_HEAT_CAPACITY * Math.max(0, tC - 100) / (WATER_HEAT_CAPACITY * 80 + WATER_LATENT_HEAT);
            steam = explosive * Math.min(ratio, boilable);
            // The jet is a dense mixture: tephra, steam and the liquid water (slurry) it entrains; only the steam
            // expands, so its speed follows the steam's share of the whole mixture. Observed cock's-tail jets reach
            // 200–500 m, i.e. leave at ~60–100 m/s (Thorarinsson 1967; Moore 1985).
            double liquid = Math.max(0, explosive * ratio - steam);
            wetGas = explosive > 0 ? steam / (steam + explosive + liquid) : 0;
            double hydrostatic = WATER_DENSITY * GRAVITY * Math.max(0, water.surfaceDepthM());
            double drivePa = Math.max(1e5, hydrostatic);
            jetSpeed = Math.sqrt(2 * wetGas * R_STEAM * 373.15 * Math.log(1 + drivePa / ambientPa));
            wetTemperature = 100 + (tC - 100) / (1 + ratio * WATER_HEAT_CAPACITY / MAGMA_HEAT_CAPACITY);
            double gasDensity = ambientPa / (R_STEAM * (wetTemperature + 273.15));
            double supported = 3 * DRAG_COEFFICIENT * gasDensity * jetSpeed * jetSpeed / (4 * CLAST_DENSITY * GRAVITY);
            wetCutoff = Math.min(Math.min(supported, plumeSupportedSize(explosive)), BALLISTIC_SIZE);
            double coarse = 1 - lognormalCdf(wetCutoff, WATER_MEDIAN);
            // the jets carry the coarse tephra and the aggregated fine ash; only the rest of the fines is lofted
            double fine = explosive * (1 - coarse);
            jetMass = explosive * coarse + fine * WET_AGGREGATION;
            wetColumn = fine * (1 - WET_AGGREGATION);
        }

        // The column: magmatic and phreatomagmatic tephra rise together.
        double column = magmaticColumn + wetColumn;
        double columnGas = 0;
        double columnTemperature = tC;
        double columnVelocity = 0;
        double collapsing = 0;
        GrainSizeDistribution grain = GrainSizeDistribution.PLINIAN;
        if (column > 0) {
            // The gas leaves with the supported fines; fall-back clasts shed theirs.
            double magmaticGasMass = magmaticColumn > 0 ? dryMagma * gas / Math.max(1e-9, 1 - gas) : 0;
            double wetGasMass = wetColumn * wetGas / Math.max(1e-9, 1 - wetGas);
            double gasMass = magmaticGasMass + wetGasMass;
            columnGas = Math.min(0.99, gasMass / (gasMass + column));
            columnTemperature = (magmaticColumn * tC + wetColumn * wetTemperature) / column;
            columnVelocity = (magmaticColumn * jet + wetColumn * jetSpeed) / column;
            double[] magmatic = grainFractions(Double.isNaN(median) ? WATER_MEDIAN : median, cutoff);
            double[] wet = grainFractions(WATER_MEDIAN, wetCutoff);
            double[] mixed = new double[magmatic.length];
            for (int i = 0; i < mixed.length; i++) {
                mixed[i] = (magmaticColumn * magmatic[i] + wetColumn * wet[i]) / column + 1e-6;
            }
            grain = new GrainSizeDistribution(mixed);
            if (collapse != null && columnGas > 0 && columnVelocity > 0) {
                double critical = collapse.criticalGasFraction(column, columnVelocity, columnTemperature);
                double c = critical > 0 ? 1 / (1 + Math.exp(Math.log(columnGas / critical) / 0.15)) : 0;
                // A collapsing column sorts itself: ash (< 1 mm) feeds density currents, coarser clasts
                // rain back around the vent like fountain fall-back.
                double magmaticAsh = magmaticColumn * conditionalBelow(ASH_SIZE, Double.isNaN(median) ? WATER_MEDIAN : median, cutoff);
                double wetAsh = wetColumn * conditionalBelow(ASH_SIZE, WATER_MEDIAN, wetCutoff);
                double magmaticCoarse = c * (magmaticColumn - magmaticAsh);
                double wetCoarse = c * (wetColumn - wetAsh);
                double pdc = c * (magmaticAsh + wetAsh);
                clastogenic += magmaticCoarse * hot;
                ballistic += magmaticCoarse * (1 - hot);
                lava += magmaticCoarse * hot;
                wetFallout += wetCoarse;
                column -= magmaticCoarse + wetCoarse;
                collapsing = column > 0 ? pdc / column : 0;
            }
        }

        wetFallout += quenched;
        return new Result(magma, lava, clastogenic, ballistic, jet, fountainHeight, column, collapsing, columnGas,
                columnTemperature, columnVelocity, grain, ratio, wetMagma, wetFallout, steam, jetMass, jetSpeed,
                median);
    }

    /** Share of the magma that meets water at water/magma mass ratio {@code r} (see {@link #CONTACT_WATER_RATIO}). */
    static double contactShare(double r) {
        return r > 0 ? Math.min(1, r / CONTACT_WATER_RATIO) : 0;
    }

    /**
     * Fragmentation efficiency of magma meeting water at mass ratio {@code r}: peaks at {@link
     * #OPTIMAL_WATER_RATIO} and falls off on both sides, too little water to drive steam explosions, too much
     * quenching the melt without explosive expansion (Wohletz &amp; Sheridan 1983; Wohletz 1986).
     */
    static double interactionEfficiency(double r) {
        return r > 0 ? r / OPTIMAL_WATER_RATIO * Math.exp(1 - r / OPTIMAL_WATER_RATIO) : 0;
    }

    /**
     * Water/magma mass ratio of magma rising into a vent filled with water-saturated tephra (a slurry):
     * each volume of magma mixes with a comparable volume of slurry and so with its pore water,
     * {@code R = φ ρ_w / ρ_m} (bulk interaction; Kokelaar 1983, 1986), unless the water drawn out cannot be
     * replaced: a crater cut off from open water is refilled only by seepage through the edifice and
     * groundwater flowing into the conduit.
     */
    static double slurryRatio(Water water, double conduitRadiusM, double magmaMassFlux) {
        if (!(magmaMassFlux > 0)) return 0;
        double porosity = Math.max(0, Math.min(0.9, water.slurryPorosity()));
        double mixing = porosity * WATER_DENSITY / ConduitModel.MAGMA_DENSITY;
        double seepage = water.seepageKgPerS();
        if (Double.isNaN(seepage)) return mixing;
        double resupply = (Math.max(0, seepage) + aquiferInflow(water, conduitRadiusM)) / magmaMassFlux;
        return Math.min(mixing, resupply);
    }

    /**
     * Water/magma mass ratio at the vent. Sea or lake water flows into an open crater under its
     * hydrostatic head through a band one conduit radius deep around the conduit; groundwater seeps in
     * by Darcy flow from the aquifer the conduit crosses below the water table.
     */
    static double waterMagmaRatio(Water water, double conduitRadiusM, double magmaMassFlux) {
        if (!(magmaMassFlux > 0)) return 0;
        double inflow = 0;
        double depth = Math.max(0, water.surfaceDepthM());
        double open = Math.max(0, Math.min(1, water.openFraction()));
        if (depth > 0 && open > 0) {
            double speed = Math.sqrt(2 * GRAVITY * depth);
            double band = Math.min(depth, conduitRadiusM);
            inflow += WATER_DENSITY * speed * 2 * Math.PI * conduitRadiusM * band * open;
        }
        inflow += aquiferInflow(water, conduitRadiusM);
        return inflow / magmaMassFlux;
    }

    /** Groundwater seeping into the conduit by Darcy flow from the aquifer below the water table (kg/s). */
    static double aquiferInflow(Water water, double conduitRadiusM) {
        double table = water.waterTableDepthM();
        if (!(table < AQUIFER_INTERACTION_DEPTH_M)) return 0;
        double saturated = AQUIFER_INTERACTION_DEPTH_M - Math.max(0, table);
        double darcy = AQUIFER_PERMEABILITY / WATER_VISCOSITY * WATER_DENSITY * GRAVITY; // m/s under a unit gradient
        return WATER_DENSITY * darcy * 2 * Math.PI * conduitRadiusM * saturated;
    }

    /**
     * Largest clast (m) a buoyant plume fed by {@code massFlux} (kg/s) holds up: its height from the
     * DRE flux (Mastin et al. 2009), the buoyancy flux from the height in a stratified atmosphere
     * ({@code H = 8.2 F^¼ N^−¾}, N = 0.01 s⁻¹; Morton et al. 1956), the plume's rise speed a third of
     * the way up ({@code w ≈ 1.66 (F/z)^⅓}), and the clast whose settling speed in air matches it.
     */
    static double plumeSupportedSize(double massFlux) {
        double q = massFlux / 2500;
        if (!(q > 0)) return 0;
        double height = 2000 * Math.pow(q, 0.241);
        double buoyancyFlux = Math.pow(height / (8.2 * Math.pow(0.01, -0.75)), 4);
        double w = 1.66 * Math.cbrt(buoyancyFlux / (height / 3));
        return 3 * DRAG_COEFFICIENT * AIR_DENSITY * w * w / (4 * CLAST_DENSITY * GRAVITY);
    }

    /** Mass fraction of a log-normal (median {@code median}, σ_ln {@link #SIGMA_LN}) below {@code size}. */
    static double lognormalCdf(double size, double median) {
        if (!(size > 0)) return 0;
        double x = Math.log(size / median) / (SIGMA_LN * Math.sqrt(2));
        return 0.5 * (1 + erf(x));
    }

    /**
     * Mass in the four tephra classes (lapilli &gt; 2 mm, coarse ash 0.5–2 mm, medium 0.063–0.5 mm, fine
     * &lt; 63 µm) of a log-normal population with the given median, truncated above at {@code cutoff}.
     */
    static double[] grainFractions(double median, double cutoff) {
        double total = Math.max(1e-12, lognormalCdf(cutoff, median));
        double fine = lognormalCdf(Math.min(63e-6, cutoff), median);
        double medium = lognormalCdf(Math.min(5e-4, cutoff), median) - fine;
        double coarse = lognormalCdf(Math.min(2e-3, cutoff), median) - fine - medium;
        double lapilli = total - fine - medium - coarse;
        return new double[] {lapilli / total, coarse / total, medium / total, fine / total};
    }

    /** Share of a log-normal population truncated at {@code cutoff} that is finer than {@code size}. */
    static double conditionalBelow(double size, double median, double cutoff) {
        double total = lognormalCdf(cutoff, median);
        return total > 0 ? Math.min(1, lognormalCdf(Math.min(size, cutoff), median) / total) : 1;
    }

    /** Abramowitz &amp; Stegun 7.1.26 (|error| &lt; 1.5e-7). */
    static double erf(double x) {
        double sign = Math.signum(x);
        double a = Math.abs(x);
        double t = 1 / (1 + 0.3275911 * a);
        double y = 1 - ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t
                * Math.exp(-a * a);
        return sign * y;
    }

    /** Ambient pressure under {@code depthM} of standing water (Pa). */
    public static double ambientPressurePa(double waterDepthM) {
        return ConduitInput.ATMOSPHERE_PA + WATER_DENSITY * GRAVITY * Math.max(0, waterDepthM);
    }
}
