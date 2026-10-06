package me.alex4386.typhon.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;
import me.alex4386.typhon.engine.config.ConfigNode;
import me.alex4386.typhon.engine.config.VolcanoDefinition;
import me.alex4386.typhon.engine.config.WorldDefinition;
import me.alex4386.typhon.engine.config.Yaml;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.magma.MagmaCommands;
import me.alex4386.typhon.engine.worlds.ConfigChanges;
import me.alex4386.typhon.engine.worlds.ConfigImpact;
import me.alex4386.typhon.engine.worlds.WorldDirectory;

/**
 * Parameter schema and changes for world sessions (protocol §3.6).
 *
 * <p>Parameters are not a hand-maintained list: every numeric or boolean leaf of the world's
 * definition trees ({@link WorldDefinition#toTree()}, {@link VolcanoDefinition#toTree()}) is one,
 * with id {@code world.<path>} or {@code volcano.<id>.<path>}. How a change is applied (live,
 * reload, reinit) and what it means for users comes from {@link ConfigImpact}, the engine's single
 * rule set; the schema publishes that prediction per parameter. {@link #META} only adds labels,
 * units, ranges and help to the important ones; a new config field shows up automatically with a
 * label derived from its name.
 *
 * <p>Changes go through the configuration API ({@link ConfigApi}, {@link Session#applyConfig}).
 * Defaults are the definitions as they were before the first change ({@code tuning/baseline.json});
 * every change is logged in {@code tuning/audit.json}.
 */
final class Tuning {
    static final String DIR = "tuning";
    static final String BASELINE = "baseline.json";
    static final String AUDIT = "audit.json";
    static final int AUDIT_LIMIT = 200;

    private Tuning() {}

    /** Display metadata for one parameter path. */
    record Meta(String label, String unit, Double min, Double max, boolean log, String help) {}

    private static Meta m(String label, String unit, Double min, Double max, boolean log, String help) {
        return new Meta(label, unit, min, max, log, help);
    }

    /** Curated metadata by scope ({@code world} / {@code volcano}) and path. Order = display order. */
    static final Map<String, Meta> META = new LinkedHashMap<>();

    static {
        META.put("world:climate.rainfallMmPerHour", m("Rain", "mm/h", 0.0, 200.0, false,
                "Rain over the whole map. Soaks in, fills lakes, can start mudflows on fresh ash."));
        META.put("world:climate.evaporationMmPerHour", m("Evaporation", "mm/h", 0.0, 10.0, false, null));
        META.put("world:climate.wind.speed", m("Wind speed", "m/s", 0.0, 60.0, false, "Carries ash and gas downwind."));
        META.put("world:climate.wind.bearingDeg", m("Wind blows towards", "°", 0.0, 360.0, false, "Compass bearing, 90 = east."));
        META.put("world:climate.wind.variability", m("Wind gustiness", null, 0.0, 1.0, false, null));
        META.put("world:scaling.dormantTimeCompression", m("Volcano time while quiet", "×", 1.0, 1e7, true,
                "Volcanoes recharge over years. Volcano processes run this many times faster than simulated time while quiet."));
        META.put("world:scaling.eruptiveTimeCompression", m("Volcano time while erupting", "×", 1.0, 1e4, true,
                "Usually small so lava flows and fountains move at a believable pace."));

        META.put("volcano:magma.chamber.supplyRate", m("Magma supply rate", "m³/s", 0.0, 100.0, true,
                "Magma rising into the chamber from below. Kīlauea ~0.1–0.2 m³/s."));
        META.put("volcano:magma.chamber.supplyVariability", m("Supply variability", null, 0.0, 1.0, false,
                "How much the supply pulses (0 = steady)."));
        META.put("volcano:magma.chamber.rechargeTemperatureC", m("New magma temperature", "°C", 650.0, 1350.0, false,
                "Temperature of magma supplied from now on. Basalt ~1150–1250, rhyolite ~750–900."));
        META.put("volcano:magma.chamber.rechargeSilicaWt", m("New magma silica (SiO₂)", "wt%", 42.0, 78.0, false,
                "More silica = stickier magma and more explosive eruptions. Basalt ~50, andesite ~60, rhyolite ~74."));
        META.put("volcano:magma.chamber.rechargeWaterWt", m("New magma water (H₂O)", "wt%", 0.0, 8.0, false,
                "Dissolved water drives explosions as it turns to gas. Hawaiʻi ~0.5, arcs 3–6."));
        META.put("volcano:timeCompression.dormant", m("Volcano time while quiet", "×", 1.0, 1e7, true,
                "Overrides the world setting for this volcano."));
        META.put("volcano:timeCompression.eruptive", m("Volcano time while erupting", "×", 1.0, 1e4, true,
                "Overrides the world setting for this volcano."));
        META.put("volcano:magma.chamber.volume", m("Chamber volume", "m³", 1e6, 1e13, true, "Size of the magma reservoir."));
        META.put("volcano:magma.chamber.lithostaticDepth", m("Chamber depth", "m", 200.0, 20000.0, false, "Depth of the chamber below the surface."));
        META.put("volcano:magma.chamber.tensileStrengthMPa", m("Roof strength", "MPa", 0.5, 100.0, true,
                "Overpressure needed to crack the rock above the chamber and start an eruption."));
        META.put("volcano:magma.chamber.eruptionEndOverpressureMPa", m("Eruption stops below", "MPa", 0.0, 50.0, false,
                "Overpressure at which an eruption runs out of push."));
        META.put("volcano:magma.chamber.maxEruptionRate", m("Maximum eruption rate", "m³/s", 1.0, 1e6, true, null));
        META.put("volcano:magma.chamber.initialTemperatureC", m("Starting magma temperature", "°C", 650.0, 1350.0, false, null));
        META.put("volcano:magma.chamber.initialSilicaWt", m("Starting magma silica (SiO₂)", "wt%", 42.0, 78.0, false, null));
        META.put("volcano:magma.chamber.initialWaterWt", m("Starting magma water (H₂O)", "wt%", 0.0, 8.0, false, null));
        META.put("volcano:magma.chamber.initialOverpressureMPa", m("Starting overpressure", "MPa", 0.0, 100.0, false, null));
        META.put("volcano:magma.chamber.wallTemperatureC", m("Wall-rock temperature", "°C", 0.0, 1000.0, false, null));
        META.put("volcano:magma.chamber.coolingTimescale", m("Cooling time", "s", null, null, true, "e-folding time of chamber cooling into the wall rock."));
        META.put("volcano:magma.chamber.degassingTimescale", m("Degassing time", "s", null, null, true, null));
        META.put("volcano:ballisticFraction", m("Share of erupted mass as bombs", null, 0.0, 1.0, false, null));
        META.put("volcano:magma.chamber.compressibilityPerMPa", m("Chamber compressibility", "/MPa", 1e-5, 1e-2, true, "How much the chamber (magma and walls) yields per MPa: a stiffer chamber pressurises faster."));
        META.put("volcano:magma.chamber.conduitRadius", m("Conduit radius", "m", 0.1, 200.0, true, "Radius of the summit conduit; flow scales with its fourth power."));
        META.put("volcano:magma.chamber.rechargeCo2Wt", m("New magma CO₂", "wt%", 0.0, 3.0, false, "Dissolved CO₂ in the magma supplied from depth; it exsolves deep and drives degassing."));
        META.put("volcano:magma.chamber.rechargeCrystalFraction", m("New magma crystals", "fraction", 0.0, 0.6, false, "Crystal content of the supplied magma; crystals stiffen it."));
        META.put("volcano:magma.chamber.initialCo2Wt", m("Starting magma CO₂", "wt%", 0.0, 3.0, false, null));
        META.put("volcano:magma.chamber.crystalSilicaWt", m("Crystal silica", "wt%", 35.0, 75.0, false, "Silica of the crystals that grow; the melt left behind is enriched accordingly."));
        META.put("volcano:magma.chamber.stepPeriodSeconds", m("Chamber step", "s", 0.05, 60.0, true, "How often the chamber model runs (simulated seconds)."));
        META.put("volcano:magma.chamber.samplePeriodSeconds", m("Chamber sample interval", "s", 0.0, 600.0, false, "How often chamber readings are reported."));
        META.put("volcano:magma.conduit.initialOpenness", m("Conduit open at start", "fraction", 0.0, 1.0, false, "0 = sealed, 1 = open summit conduit at the start."));
        META.put("volcano:magma.conduit.reopenOverpressureMPa", m("Reopening pressure", "MPa", 0.0, 100.0, false, "Overpressure that reopens a partly open conduit."));
        META.put("volcano:magma.conduit.conduitSealTimescale", m("Conduit sealing time", "s", 1e3, 1e10, true, "How fast an open conduit seals itself between eruptions (e-folding time)."));
        META.put("volcano:magma.conduit.fragmentationPorosity", m("Fragmentation porosity", "fraction", 0.3, 0.95, false, "Gas fraction at which the rising magma shatters into ash (explosive above it)."));
        META.put("volcano:magma.conduit.brittleStressPa", m("Brittle stress", "Pa", 1e5, 1e10, true, "Shear stress at which viscous magma breaks (strain-rate fragmentation)."));
        META.put("volcano:magma.conduit.foamStrengthPa", m("Foam strength", "Pa", 1e3, 1e9, true, "Bubble-wall strength the gas overpressure must exceed to fragment."));
        META.put("volcano:magma.conduit.turbulentFrictionFactor", m("Turbulent friction", null, 0.001, 0.2, true, "Wall friction factor of turbulent gas–particle flow above fragmentation."));
        META.put("volcano:magma.conduit.referencePermeability", m("Magma permeability", "m²", 1e-16, 1e-8, true, "Permeability of connected bubbles; high values let gas escape (effusive)."));
        META.put("volcano:magma.conduit.percolationThreshold", m("Percolation threshold", "fraction", 0.05, 0.8, false, "Porosity above which bubbles connect and gas can flow through the magma."));
        META.put("volcano:magma.conduit.wallPermeability", m("Wall-rock permeability", "m²", 1e-20, 1e-9, true, "How easily gas leaks sideways into the conduit walls."));
        META.put("volcano:magma.conduit.gasViscosity", m("Gas viscosity", "Pa·s", 1e-6, 1e-3, true, null));
        META.put("volcano:magma.conduit.microlitesPerWtWater", m("Microlites per wt% water lost", "fraction", 0.0, 1.0, false, "Crystals grown in the conduit as the melt degasses; they stiffen it."));
        META.put("volcano:magma.conduit.crystallisationTimescale", m("Conduit crystallisation time", "s", 1.0, 1e8, true, null));
        META.put("volcano:magma.conduit.maxCrystalFraction", m("Maximum crystals", "fraction", 0.3, 0.8, false, "Crystal fraction at which the magma locks up."));
        META.put("volcano:magma.conduit.bubbleRadiusM", m("Bubble radius", "m", 1e-6, 1e-1, true, null));
        META.put("volcano:magma.conduit.surfaceTension", m("Melt surface tension", "N/m", 0.01, 1.0, true, null));
        META.put("volcano:magma.conduit.coalescenceViscosity", m("Bubble coalescence viscosity", "Pa·s", 1.0, 1e8, true, "Below this melt viscosity bubbles merge into slugs (Strombolian bursts)."));
        META.put("volcano:magma.conduit.slugLengthDiameters", m("Gas slug length", "conduit diameters", 0.5, 50.0, true, null));
        META.put("volcano:magma.conduit.plugViscosityLog10", m("Plug viscosity (log₁₀)", "Pa·s", 6.0, 16.0, false, "Viscosity of the degassed plug capping the conduit."));
        META.put("volcano:magma.conduit.plugStrengthMPa", m("Plug strength", "MPa", 0.0, 100.0, false, "Pressure the plug holds before it fails explosively (Vulcanian)."));
        META.put("volcano:magma.conduit.plugCapDepthM", m("Plug depth", "m", 0.0, 5000.0, false, null));
        META.put("volcano:magma.conduit.plugPorosity", m("Plug porosity", "fraction", 0.0, 0.9, false, null));
        META.put("volcano:magma.conduit.exsolutionTimescale", m("Gas exsolution time", "s", 1e-3, 1e5, true, null));
        META.put("volcano:magma.conduit.wallSlipStressPa", m("Wall slip stress", "Pa", 1e3, 1e9, true, "Shear stress at which a crystal-rich plug slips along the walls."));
        META.put("volcano:magma.conduit.wallFrictionCoefficient", m("Wall friction", null, 0.0, 1.0, false, null));
        META.put("volcano:magma.conduit.gridSteps", m("Conduit solver steps", null, 20.0, 2000.0, true, "Depth steps of the conduit flow solution (accuracy vs. speed)."));
        META.put("volcano:dikes.stepPeriodSeconds", m("Dike step", "s", 0.05, 60.0, true, null));
        META.put("volcano:dikes.startOffsetBlocks", m("Dike start spread", "blocks", 0.0, 200.0, false, "Dikes start within this distance of the chamber centre."));
        META.put("volcano:dikes.shearModulusPa", m("Crust stiffness (shear modulus)", "Pa", 1e8, 1e11, true, "Stiffer crust opens dikes less for the same pressure."));
        META.put("volcano:dikes.poissonRatio", m("Poisson's ratio", null, 0.05, 0.49, false, null));
        META.put("volcano:dikes.rockDensity", m("Crust density", "kg/m³", 1500.0, 3300.0, false, "Denser crust buoys magma up (dense basalt otherwise needs pushing)."));
        META.put("volcano:dikes.minOpening", m("Thinnest dike", "m", 0.01, 10.0, true, null));
        META.put("volcano:dikes.maxOpening", m("Thickest dike", "m", 0.1, 50.0, true, null));
        META.put("volcano:dikes.maxStrikeLength", m("Longest dike along strike", "m", 10.0, 50000.0, true, null));
        META.put("volcano:dikes.minCharacteristicHeight", m("Smallest dike height used for driving pressure", "m", 10.0, 5000.0, true, null));
        META.put("volcano:dikes.maxSpeed", m("Fastest dike", "m/s", 0.01, 50.0, true, "Upper limit of the dike tip speed (basaltic dikes rise at ~0.1–5 m/s)."));
        META.put("volcano:dikes.freezeSpeed", m("Dike freezing speed", "m/s", 1e-5, 1.0, true, "A dike slower than this freezes against the wall rock and stalls."));
        META.put("volcano:dikes.stallPressureMPa", m("Dike stall pressure", "MPa", 0.0, 50.0, false, "Driving pressure below which a dike stops."));
        META.put("volcano:dikes.maxSubstepMeters", m("Dike sub-step", "m", 1.0, 1000.0, true, null));
        META.put("volcano:dikes.deflectionStrength", m("Steering by the edifice", null, 0.0, 20.0, false, "How strongly the volcano's slopes turn dikes towards the flanks."));
        META.put("volcano:dikes.edificeDepthScale", m("Edifice influence depth", "m", 10.0, 20000.0, true, null));
        META.put("volcano:dikes.slopeSampleRadius", m("Slope sampling radius", "blocks", 1.0, 64.0, false, null));
        META.put("volcano:dikes.headingNoise", m("Dike wander", null, 0.0, 2.0, false, "Random wander of the dike path."));
        META.put("volcano:dikes.headingCorrelationLength", m("Dike wander length", "m", 1.0, 10000.0, true, null));
        META.put("volcano:dikes.hypocentersPerKm", m("Earthquakes per km of dike", "/km", 0.0, 500.0, false, null));
        META.put("volcano:dikes.hypocenterJitterBlocks", m("Earthquake scatter", "blocks", 0.0, 50.0, false, null));
        META.put("volcano:dikes.minFissureLength", m("Shortest fissure", "blocks", 1.0, 100.0, false, null));
        META.put("volcano:dikes.maxFissureLength", m("Longest fissure", "blocks", 1.0, 1000.0, false, null));
        META.put("volcano:dikes.maxRecordedDikes", m("Dikes remembered", null, 1.0, 200.0, false, null));
        META.put("volcano:magma.chamber.wallRuptureRatio", m("Wall rupture limit", "× roof strength", 1.0, 10.0, false,
                "Overpressure at which the chamber walls break, as a multiple of the roof strength (or eruption threshold)."
                        + " The chamber never holds more; magma beyond it leaves through a dike."));
        META.put("volcano:magma.chamber.wallYieldFraction", m("Wall yielding", "fraction", 0.0, 1.0, false,
                "Share of the magma beyond the rupture limit that the walls absorb by deforming (the chamber grows,"
                        + " the ground inflates) instead of feeding a dike. 0 = all into dikes, 1 = chamber growth only."));
        META.put("volcano:dikes.ruptureNucleation", m("Wall rupture opens a dike", null, null, null, false,
                "When the walls rupture, a dike opens at once and carries the excess magma, even during an eruption."
                        + " Off: the chamber grows instead and dikes only form at random."));
        META.put("volcano:dikes.nucleateDuringEruption", m("Dikes during eruptions", null, null, null, false,
                "Allow random dike nucleation while the summit erupts (flank fissures mid-eruption)."));
        META.put("volcano:dikes.initiationPressureRatio", m("Dike onset", "× roof strength", 0.05, 0.99, false,
                "Random dike nucleation starts once overpressure passes this share of the roof strength."));
        META.put("volcano:dikes.maxInitiationRate", m("Dike rate at roof strength", "/s", 1e-6, 1.0, true,
                "Random nucleation rate (per simulated second) at full roof strength and a fully sealed conduit."));
        META.put("volcano:dikes.maxConcurrentDikes", m("Dikes at once", null, 0.0, 10.0, false,
                "How many dikes may rise at the same time. 0 = no dikes at all (rupture magma grows the chamber)."));
        META.put("volcano:dikes.conduitSealing", m("Summit conduit sealing", "fraction", 0.0, 1.0, false,
                "0 = open summit conduit (pressure vents there, no random dikes); 1 = sealed (dikes likely)."));
    }

    /**
     * The physically sensible part of a range: values outside {@code [low, high]} are accepted (the
     * hard {@link Meta} range still applies) but the schema flags them and {@code setParams} warns.
     */
    record Advice(Double low, Double high, String warning) {}

    /** Advice by scope:path, like {@link #META}. */
    static final Map<String, Advice> ADVICE = new LinkedHashMap<>();

    static {
        String fast = "Above ~100× lava, fountains and ash advance hundreds of metres per tick and the chamber drains in"
                + " a few steps; results stay bounded but look unrealistic.";
        ADVICE.put("world:scaling.eruptiveTimeCompression", new Advice(1.0, 100.0, fast));
        ADVICE.put("volcano:timeCompression.eruptive", new Advice(1.0, 100.0, fast));
        String quiet = "Above ~10⁵× a year of recharge passes in minutes; dikes and unrest are skipped over.";
        ADVICE.put("world:scaling.dormantTimeCompression", new Advice(null, 1e5, quiet));
        ADVICE.put("volcano:timeCompression.dormant", new Advice(null, 1e5, quiet));
        ADVICE.put("volcano:magma.chamber.supplyRate", new Advice(null, 10.0,
                "Long-term supply above ~10 m³/s exceeds any active volcano (Kīlauea, among the highest, ~3–6 m³/s ="
                        + " 0.1–0.2 km³/yr; Etna ~1); the chamber sits at its rupture limit and keeps opening dikes."));
        ADVICE.put("volcano:magma.chamber.supplyVariability", new Advice(null, 0.6,
                "Large pulses make the supply intermittent; eruptions will start and stop abruptly."));
        ADVICE.put("volcano:magma.chamber.tensileStrengthMPa", new Advice(0.5, 20.0,
                "Measured rock tensile strengths are 0.5–9 MPa (in situ ~3); stronger roofs store implausible pressure."));
        ADVICE.put("volcano:magma.chamber.volume", new Advice(1e7, 1e12,
                "Shallow chambers are ~0.01–1000 km³ (10⁷–10¹² m³)."));
        ADVICE.put("volcano:magma.chamber.wallRuptureRatio", new Advice(1.0, 3.0,
                "Chamber walls fail at a few times the tensile strength at most; higher limits store implausible pressure."));
        ADVICE.put("volcano:magma.chamber.maxEruptionRate", new Advice(null, 1e5,
                "Only the largest Plinian eruptions exceed ~10⁵ m³/s (Pinatubo 1991 peaked near 10⁵–10⁶)."));
    }

    /** Warning for {@code value} at a scope:path, or null when it is in the sensible range. */
    static String advise(String metaKey, String label, double value) {
        Advice a = ADVICE.get(metaKey);
        if (a == null) return null;
        if ((a.low() != null && value < a.low()) || (a.high() != null && value > a.high())) return label + ": " + a.warning();
        return null;
    }

    /**
     * Parameters the engine computes from physics unless the definition overrides them: their value is
     * {@code null} (NaN in the definition) while computed, and the schema carries {@code auto:true} plus
     * the live {@code computed} value; {@code setParams} with {@code null} goes back to computing.
     */
    static final java.util.Set<String> AUTO = java.util.Set.of(
            "volcano:magma.chamber.wallRuptureRatio", "volcano:magma.chamber.wallYieldFraction");

    /** An {@code injectMagma} batch beyond this share of the chamber volume ruptures the walls. */
    static final double INJECT_RUPTURE_SHARE = 0.1;

    /** Group headings by scope:path prefix (first match wins, in order). */
    private static final List<String[]> GROUPS = List.of(
            new String[] {"world:climate", "Weather"},
            new String[] {"world:scaling", "Time scale"},
            new String[] {"world:subsurface", "Underground heat and water (solver)"},
            new String[] {"volcano:magma.chamber.supply", "Magma supply"},
            new String[] {"volcano:magma.chamber.recharge", "Magma supply"},
            new String[] {"volcano:timeCompression", "Time scale"},
            new String[] {"volcano:magma.chamber.initial", "Magma chamber at start"},
            new String[] {"volcano:magma.chamber", "Magma chamber"},
            new String[] {"volcano:magma.conduit", "Conduit and eruption style"},
            new String[] {"volcano:ballistic", "Conduit and eruption style"},
            new String[] {"volcano:dikes", "Dikes (magma intrusions)"},
            new String[] {"volcano:geothermal", "Hot springs and fumaroles"},
            new String[] {"volcano:massFlows", "Pyroclastic flows and mudflows"},
            new String[] {"volcano:tephra", "Ash and bombs"},
            new String[] {"volcano:deformation", "Ground deformation"},
            new String[] {"volcano:", "Other"});

    /** Volcano paths never offered (identity, geometry the client cannot sensibly edit). */
    private static final List<String> VOLCANO_SKIP = List.of("id", "magma.chamber.center.", "edifice.", "vents");

    /** World paths offered: all but those a running world cannot take (they need a new world). */
    private static boolean worldOffered(String path) {
        ConfigImpact.Impact i = ConfigImpact.world(path);
        return !path.equals("name") && !path.startsWith("terrain")
                && !(i.kind() == ConfigImpact.Kind.REINIT && i.target() == ConfigImpact.Target.WORLD);
    }

    /**
     * Settings the chamber Inspector shows for a volcano (by path prefix, live ones only): its magma
     * supply and recharge magma, the walls' mechanics and the dikes. The client shows what the schema
     * lists here and decides nothing itself.
     */
    private static final List<String> CHAMBER_PANEL = List.of("magma.chamber.supply", "magma.chamber.recharge",
            "magma.chamber.wall", "dikes.");

    // ── Injection fields ──

    /** One {@code injectMagma} field and the volcano setting its default comes from. */
    record InjectField(String id, String label, String unit, double min, double max, boolean log, String help,
            ToDoubleFunction<MagmaChamberConfig> configured, ToDoubleFunction<MagmaChamber.SupplyMagma> current) {}

    /**
     * Fields of {@code injectMagma} besides {@code volumeM3}, in {@link MagmaCommands.InjectRecharge}
     * argument order. To add one: add an entry here and pass it on in {@link #injection}; the client
     * builds its dialog from this list.
     */
    static final List<InjectField> INJECT_FIELDS = List.of(
            new InjectField("temperatureC", "Temperature", "°C", 650, 1350, false, null,
                    MagmaChamberConfig::rechargeTemperatureC, MagmaChamber.SupplyMagma::temperatureC),
            new InjectField("silicaWt", "Silica (SiO₂)", "wt%", 42, 78, false, "Sets how sticky the magma is",
                    MagmaChamberConfig::rechargeSilicaWt, MagmaChamber.SupplyMagma::silicaWt),
            new InjectField("waterWt", "Water (H₂O)", "wt%", 0, 8, false, "Dissolved gas that drives explosions",
                    MagmaChamberConfig::rechargeWaterWt, MagmaChamber.SupplyMagma::waterWt),
            new InjectField("co2Wt", "Carbon dioxide (CO₂)", "wt%", 0, 3, false,
                    "Less soluble than water: exsolves deep and drives gas-rich, explosive ascent",
                    MagmaChamberConfig::rechargeCo2Wt, MagmaChamber.SupplyMagma::co2Wt),
            new InjectField("crystalFraction", "Crystals", "fraction", 0, 0.6, false,
                    "Share of the magma already crystallised; crystals stiffen it and carry no latent heat",
                    MagmaChamberConfig::rechargeCrystalFraction, MagmaChamber.SupplyMagma::crystalFraction));

    static final double INJECT_DEFAULT_M3 = 5e6;

    /** {@code injectMagma} fields with defaults from the volcano's configured supply magma. */
    static JsonArray injectSchema(MagmaChamberConfig c) {
        JsonArray out = new JsonArray();
        double sensible = INJECT_RUPTURE_SHARE * c.volume();
        JsonObject vol = spec("volumeM3", "Volume", "m³", "Batch", 1e3, 1e10, true,
                "How much magma to add. A large eruption is 10⁷–10⁹ m³.");
        vol.add("default", Json.num(Math.min(INJECT_DEFAULT_M3, sensible)));
        JsonObject r = new JsonObject();
        r.add("max", Json.num(sensible));
        vol.add("recommended", r);
        vol.addProperty("warning", "Batches above ~" + (int) (INJECT_RUPTURE_SHARE * 100) + "% of the chamber volume crack"
                + " its walls: the pressure stops at the rupture limit and the excess grows the chamber (and the ground).");
        out.add(vol);
        for (InjectField f : INJECT_FIELDS) {
            JsonObject j = spec(f.id(), f.label(), f.unit(), "Magma", f.min(), f.max(), f.log(), f.help());
            j.add("default", Json.num(f.configured().applyAsDouble(c)));
            out.add(j);
        }
        return out;
    }

    /** Builds the engine command, validating ranges; missing fields use the magma the supply delivers now. */
    static MagmaCommands.InjectRecharge injection(String volcanoId, JsonObject cmd, MagmaChamber.SupplyMagma supply) {
        Double vol = Json.dbl(cmd, "volumeM3");
        if (vol == null || !(vol > 0) || vol > 1e12) throw new IllegalArgumentException("volumeM3 must be in (0, 1e12]");
        double[] v = new double[INJECT_FIELDS.size()];
        for (int i = 0; i < v.length; i++) {
            InjectField f = INJECT_FIELDS.get(i);
            Double x = Json.dbl(cmd, f.id());
            if (x == null) {
                v[i] = f.current().applyAsDouble(supply);
            } else if (!(x >= f.min() && x <= f.max())) {
                throw new IllegalArgumentException(f.id() + " must be in [" + f.min() + ", " + f.max() + "] " + f.unit());
            } else {
                v[i] = x;
            }
        }
        return new MagmaCommands.InjectRecharge(volcanoId, vol, v[0], v[1], v[2], v[3], v[4]);
    }

    /** The note acknowledging an injection, or null when the batch is small enough for the walls. */
    static String injectionWarning(double volumeM3, MagmaChamber chamber) {
        if (volumeM3 <= INJECT_RUPTURE_SHARE * chamber.volumeM3()) return null;
        return String.format(java.util.Locale.ROOT, "%.3g m³ is %.0f%% of the chamber: its walls rupture at %.1f MPa and"
                + " the rest grows the chamber", volumeM3, 100 * volumeM3 / chamber.volumeM3(), chamber.ruptureOverpressureMPa());
    }

    // ── Schema ──

    private static JsonObject spec(String id, String label, String unit, String group, Double min, Double max,
            boolean log, String help) {
        JsonObject j = new JsonObject();
        j.addProperty("id", id);
        j.addProperty("label", label);
        if (unit != null) j.addProperty("unit", unit);
        if (help != null) j.addProperty("help", help);
        j.addProperty("group", group);
        j.addProperty("type", "number");
        if (min != null) j.add("min", Json.num(min));
        if (max != null) j.add("max", Json.num(max));
        if (log) j.addProperty("log", true);
        j.addProperty("apply", "live");
        return j;
    }

    /** Current definitions of a world directory. */
    record Definitions(Map<String, Object> world, Map<String, Map<String, Object>> volcanoes, Map<String, String> names) {
        /** The definitions a running world runs (the files can trail live changes by a moment). */
        static Definitions of(me.alex4386.typhon.engine.worlds.World w) {
            Map<String, Map<String, Object>> vs = new TreeMap<>();
            Map<String, String> names = new TreeMap<>();
            for (VolcanoDefinition v : w.volcanoDefinitions()) {
                vs.put(v.id(), v.toTree());
                names.put(v.id(), v.name());
            }
            return new Definitions(w.definition().toTree(), vs, names);
        }

        static Definitions read(Path dir) {
            WorldDirectory wd = new WorldDirectory(dir);
            Map<String, Map<String, Object>> vs = new TreeMap<>();
            Map<String, String> names = new TreeMap<>();
            for (VolcanoDefinition v : wd.readVolcanoes()) {
                vs.put(v.id(), v.toTree());
                names.put(v.id(), v.name());
            }
            return new Definitions(wd.readWorld().toTree(), vs, names);
        }
    }

    /** A parameter found in the definitions. */
    record Leaf(String id, String scope, String volcanoId, String path, Object value) {
        /** What changing it needs ({@link ConfigImpact}, the only source of these rules). */
        ConfigImpact.Impact impact() {
            return volcanoId == null ? ConfigImpact.world(path) : ConfigImpact.volcano(path);
        }

        String metaKey() {
            return (volcanoId == null ? "world:" : "volcano:") + path;
        }
    }

    static List<Leaf> leaves(Definitions d) {
        List<Leaf> out = new ArrayList<>();
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten("", d.world(), flat);
        for (Map.Entry<String, Object> e : flat.entrySet()) {
            if (worldOffered(e.getKey())) out.add(new Leaf("world." + e.getKey(), "world", null, e.getKey(), e.getValue()));
        }
        for (Map.Entry<String, Map<String, Object>> v : d.volcanoes().entrySet()) {
            flat.clear();
            flatten("", v.getValue(), flat);
            for (Map.Entry<String, Object> e : flat.entrySet()) {
                String p = e.getKey();
                if (VOLCANO_SKIP.stream().anyMatch(s -> s.endsWith(".") ? p.startsWith(s) : p.equals(s))) continue;
                out.add(new Leaf("volcano." + v.getKey() + "." + p, "volcano:" + v.getKey(), v.getKey(), p, e.getValue()));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> tree, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : tree.entrySet()) {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m) flatten(path, (Map<String, Object>) m, out);
            else if (v instanceof Number || v instanceof Boolean) out.put(path, v);
            else if (".nan".equalsIgnoreCase(String.valueOf(v))) out.put(path, Double.NaN); // a computed (auto) value
        }
    }

    private static String group(Leaf l, Map<String, String> names) {
        String key = l.metaKey();
        String heading = "Other";
        for (String[] g : GROUPS) {
            if (key.startsWith(g[0])) {
                heading = g[1];
                break;
            }
        }
        return (l.volcanoId() == null ? "World" : names.getOrDefault(l.volcanoId(), l.volcanoId())) + " · " + heading;
    }

    /** Position of a group in the panel (curated groups first, in {@link #GROUPS} order). */
    private static int groupRank(Leaf l) {
        String key = l.metaKey();
        for (int i = 0; i < GROUPS.size(); i++) if (key.startsWith(GROUPS.get(i)[0])) return i;
        return GROUPS.size();
    }

    /** Name suffix → unit, and how many trailing characters to drop from the label. */
    private static final Object[][] SUFFIXES = {
        {"MmPerHour", "mm/h", 9}, {"PerHour", "/h", 0}, {"TemperatureC", "°C", 1}, {"CPerKm", "°C/km", 6},
        {"PerMPa", "1/MPa", 6}, {"MPa", "MPa", 3}, {"Wt", "wt%", 2}, {"Seconds", "s", 7}, {"Timescale", "s", 0},
        {"Deg", "°", 3}, {"KgS", "kg/s", 3}, {"Kg", "kg", 2}, {"M3", "m³", 2}, {"DepthM", "m", 1}, {"Pa", "Pa", 2}};

    /** "rechargeSilicaWt" → label "Recharge silica", unit "wt%". */
    static String[] humanize(String path) {
        String key = path.substring(path.lastIndexOf('.') + 1);
        String unit = null;
        String base = key;
        for (Object[] s : SUFFIXES) {
            String suffix = (String) s[0];
            if (key.endsWith(suffix) && key.length() > suffix.length()) {
                unit = (String) s[1];
                base = key.substring(0, key.length() - (int) s[2]);
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char ch = base.charAt(i);
            if (i == 0) sb.append(Character.toUpperCase(ch));
            else if (Character.isUpperCase(ch) && !Character.isUpperCase(base.charAt(i - 1))) sb.append(' ').append(Character.toLowerCase(ch));
            else sb.append(ch);
        }
        return new String[] {sb.toString(), unit};
    }

    /** The {@code schema} message of a session (§3.6). */
    static JsonObject schema(Session s) {
        JsonObject o = Json.obj("schema");
        o.addProperty("sessionId", s.id);
        JsonObject commands = new JsonObject();
        o.add("commands", commands);
        JsonArray params = new JsonArray();
        o.add("params", params);
        Path dir = s.worldDir();
        MagmaChamberConfig c = s.firstChamberConfig();
        if (c != null) commands.add("injectMagma", injectSchema(c));
        // per volcano: defaults from that volcano's own supply magma (the plain entry is the first's)
        s.chamberConfigs().forEach((id, cfg) -> commands.add("injectMagma@" + id, injectSchema(cfg)));
        if (dir == null) {
            o.addProperty("tunable", false);
            o.addProperty("reason", "This world runs in memory only, so its settings cannot be changed. Start it from the"
                    + " Worlds page (it is then saved to disk) to tune it.");
            o.add("audit", new JsonArray());
            return o;
        }
        o.addProperty("tunable", true);
        Definitions now = s.live().session() != null ? Definitions.of(s.live().session()) : Definitions.read(dir);
        Map<String, Object> defaults = baselineValues(dir, now);
        List<Leaf> leaves = new ArrayList<>(leaves(now));
        List<String> metaOrder = new ArrayList<>(META.keySet());
        leaves.sort((a, b) -> {
            int g = Integer.compare(groupRank(a), groupRank(b));
            if (g != 0) return g;
            int va = a.volcanoId() == null ? -1 : 0;
            int vb = b.volcanoId() == null ? -1 : 0;
            if (va != vb) return Integer.compare(va, vb);
            int c1 = a.volcanoId() == null || b.volcanoId() == null ? 0 : a.volcanoId().compareTo(b.volcanoId());
            if (c1 != 0) return c1;
            int ia = metaOrder.indexOf(a.metaKey());
            int ib = metaOrder.indexOf(b.metaKey());
            if (ia < 0) ia = Integer.MAX_VALUE;
            if (ib < 0) ib = Integer.MAX_VALUE;
            return ia != ib ? Integer.compare(ia, ib) : a.path().compareTo(b.path());
        });
        for (Leaf l : leaves) {
            JsonObject spec = paramSpec(l, now.names(), defaults.get(l.id()));
            if (AUTO.contains(l.metaKey())) {
                spec.addProperty("auto", true);
                spec.add("computed", Json.num(s.computedParam(l.volcanoId(), l.metaKey())));
            }
            params.add(spec);
        }
        JsonObject chamberPanel = new JsonObject();
        for (Leaf l : leaves) {
            if (l.volcanoId() == null || l.impact().kind() != ConfigImpact.Kind.LIVE) continue;
            if (CHAMBER_PANEL.stream().noneMatch(prefix -> l.path().startsWith(prefix))) continue;
            if (!chamberPanel.has(l.volcanoId())) chamberPanel.add(l.volcanoId(), new JsonArray());
            chamberPanel.getAsJsonArray(l.volcanoId()).add(l.id());
        }
        JsonObject panels = new JsonObject();
        panels.add("chamber", chamberPanel);
        o.add("panels", panels);
        o.add("audit", readAudit(dir));
        return o;
    }

    private static JsonObject paramSpec(Leaf l, Map<String, String> names, Object def) {
        ConfigImpact.Impact impact = l.impact();
        Meta meta = META.get(l.metaKey());
        String[] h = humanize(l.path());
        JsonObject j = spec(l.id(), meta != null ? meta.label() : h[0], meta != null ? meta.unit() : h[1], group(l, names),
                meta == null ? null : meta.min(), meta == null ? null : meta.max(), meta != null && meta.log(),
                meta == null ? null : meta.help());
        if (l.value() instanceof Boolean b) {
            j.addProperty("type", "boolean");
            j.addProperty("value", b);
            if (def instanceof Boolean d) j.addProperty("default", d);
        } else {
            Number n = (Number) l.value();
            j.add("value", Json.num(n.doubleValue()));
            if (def instanceof Number d) j.add("default", Json.num(d.doubleValue()));
            if (n instanceof Integer || n instanceof Long) j.add("step", Json.num(1));
        }
        // a prediction of what a change does; the response to the actual change is authoritative
        j.addProperty("apply", ConfigApi.kindName(impact.kind()));
        j.add("impact", ConfigApi.impactJson(impact, l.volcanoId() == null ? null : names.getOrDefault(l.volcanoId(), l.volcanoId())));
        if (l.volcanoId() != null) j.addProperty("volcanoId", l.volcanoId());
        Advice a = ADVICE.get(l.metaKey());
        if (a != null) {
            JsonObject r = new JsonObject();
            if (a.low() != null) r.add("min", Json.num(a.low()));
            if (a.high() != null) r.add("max", Json.num(a.high()));
            j.add("recommended", r);
            j.addProperty("warning", a.warning());
            if (l.value() instanceof Number n) {
                String w = advise(l.metaKey(), meta != null ? meta.label() : h[0], n.doubleValue());
                if (w != null) j.addProperty("outOfRange", true);
            }
        }
        return j;
    }

    // ── Baseline and audit ──

    /** Leaf values of the definitions before the first change (written on first use). */
    static Map<String, Object> baselineValues(Path dir, Definitions now) {
        Path file = dir.resolve(DIR).resolve(BASELINE);
        Definitions base = now;
        try {
            if (Files.isRegularFile(file)) {
                JsonObject j = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                Map<String, Map<String, Object>> vs = new TreeMap<>();
                for (Map.Entry<String, JsonElement> e : j.getAsJsonObject("volcanoes").entrySet()) {
                    vs.put(e.getKey(), toJava(e.getValue().getAsJsonObject()));
                }
                base = new Definitions(toJava(j.getAsJsonObject("world")), vs, now.names());
            } else {
                Files.createDirectories(file.getParent());
                JsonObject j = new JsonObject();
                j.add("world", Json.GSON.toJsonTree(now.world()));
                j.add("volcanoes", Json.GSON.toJsonTree(now.volcanoes()));
                Files.writeString(file, Json.GSON.toJson(j), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, Object> out = new TreeMap<>();
        for (Leaf l : leaves(base)) out.put(l.id(), l.value());
        return out;
    }

    static Map<String, Object> toJava(JsonObject o) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) m.put(e.getKey(), toJava(e.getValue()));
        return m;
    }

    static Object toJavaValue(JsonElement e) {
        return toJava(e);
    }

    private static Object toJava(JsonElement e) {
        if (e.isJsonObject()) return toJava(e.getAsJsonObject());
        if (e.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement x : e.getAsJsonArray()) list.add(toJava(x));
            return list;
        }
        if (e.isJsonNull()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) return p.getAsDouble();
        return p.getAsString();
    }

    static JsonArray readAudit(Path dir) {
        Path file = dir.resolve(DIR).resolve(AUDIT);
        try {
            if (!Files.isRegularFile(file)) return new JsonArray();
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
        } catch (IOException | RuntimeException e) {
            return new JsonArray();
        }
    }

    static void appendAudit(Path dir, List<JsonObject> entries) {
        JsonArray all = readAudit(dir);
        for (JsonObject e : entries) all.add(e);
        while (all.size() > AUDIT_LIMIT) all.remove(0);
        try {
            Files.createDirectories(dir.resolve(DIR));
            Files.writeString(dir.resolve(DIR).resolve(AUDIT), Json.GSON.toJson(all), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
