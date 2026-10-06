package me.alex4386.typhon.simulator.scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.geothermal.GeothermalConfig;
import me.alex4386.typhon.engine.subsurface.SubsurfaceConfig;
import me.alex4386.typhon.engine.magma.ConduitConfig;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.simulator.terrain.ColumnGrid;
import me.alex4386.typhon.simulator.terrain.TerrainGenerators;
import me.alex4386.typhon.simulator.terrain.TerrainGenerators.Cone;

/**
 * Built-in scenarios inspired by real volcanoes.
 *
 * <p>Magma properties (composition, temperature, water, reservoir depth, supply and eruption rates) are
 * taken from the literature values listed in each preset's {@link Preset#references()}; edifice sizes are
 * shrunk to Minecraft through {@link VolcanoScaling}. Every preset starts close to the state that makes
 * its signature behaviour happen soon (e.g. a chamber just below failure).
 */
public final class Presets {
    private static final Map<String, Preset> PRESETS = new LinkedHashMap<>();

    static {
        register(kilauea());
        register(stromboli());
        register(stHelens());
        register(pinatubo());
        register(surtsey());
        register(yellowstone());
        for (Preset real : RealPresets.all()) register(real);
    }

    private Presets() {}

    private static void register(Preset preset) {
        PRESETS.put(preset.name(), preset);
    }

    public static List<Preset> all() {
        return List.copyOf(PRESETS.values());
    }

    public static Preset get(String name) {
        Preset preset = PRESETS.get(name);
        if (preset == null) throw new IllegalArgumentException("Unknown preset '" + name + "'. Known: " + PRESETS.keySet());
        return preset;
    }

    /** Build step of a preset: terrain → scenario builder (engine options are applied afterwards). */
    @FunctionalInterface
    interface Assembly {
        Scenario.Builder build(long seed, ColumnGrid terrain);
    }

    record Defined(String name, String title, String description, List<String> references, double defaultHours,
            LongFunction<ColumnGrid> terrainFactory, Assembly assembly) implements Preset {
        @Override
        public ColumnGrid terrain(long seed) {
            return terrainFactory.apply(seed);
        }

        @Override
        public Scenario build(long seed, ColumnGrid terrain, Scenario.Options options) {
            return assembly.build(seed, terrain).options(options).build();
        }
    }

    // ── helpers ──

    /** Crater vent whose floor is the ground at (x, z). */
    static VentSite crater(String id, ColumnGrid terrain, int x, int z, int radius) {
        return VentSite.crater(id, new BlockPos(x, terrain.ground(x, z), z), radius);
    }

    /** Chamber centre a few dozen blocks below the vent, inside the overworld. */
    static BlockPos chamberBelow(VentSite vent, int depthBlocks) {
        BlockPos p = vent.position();
        return new BlockPos(p.x(), Math.max(-56, p.y() - depthBlocks), p.z());
    }

    /** Wind variability used by every preset (real speeds are scaled by Froude similarity in the engine). */
    static final double WIND_VARIABILITY = 0.3;

    // ── presets ──

    static Preset kilauea() {
        VolcanoScaling scaling = new VolcanoScaling(8, 100);
        return new Defined(
                "kilauea",
                "Kīlauea-like shield (basaltic, effusive)",
                "A broad shield with a summit pit crater over a shallow basaltic reservoir that is close to failure."
                        + " Expect a Hawaiian-style effusive eruption: low-viscosity lava flows run far down the gentle"
                        + " flanks, low explosivity, continuous tremor.",
                List.of(
                        "Tholeiitic basalt, SiO2 ~50 wt%, eruption T ~1150-1170 C, H2O ~0.3-0.5 wt% (Kilauea summit/ERZ lavas)",
                        "Summit reservoir ~1-2 km below Halema'uma'u; long-term supply ~0.1-0.2 km3/yr (~3-6 m3/s)",
                        "Effusion rates typically 1-10 m3/s; 2018 LERZ peaked ~50-100 m3/s",
                        "Shield flank slopes ~3-10 deg; summit 1247 m above sea level"),
                24, // a day of the eruption (the reservoir starts a few minutes from failure)
                seed -> TerrainGenerators.shield(256, seed, 44, 300, 10, 6),
                (seed, terrain) -> {
                    Scenario.Builder b = new Scenario.Builder("kilauea", seed, terrain);
                    VentSite vent = crater("halemaumau", terrain, 0, 0, 6);
                    MagmaChamberConfig chamber = MagmaChamberConfig.builder("kilauea", chamberBelow(vent, 40))
                            .volume(1e9)
                            .lithostaticDepth(1500)
                            .conduitRadius(1.5) // feeder ~3 m across (Wilson & Head 1981); peak effusion within 1-100 m3/s
                            .tensileStrengthMPa(10)
                            .eruptionEndOverpressureMPa(1)
                            .supplyRate(3)
                            .supplyVariability(0.2)
                            .initialSilicaWt(50).rechargeSilicaWt(50)
                            .initialWaterWt(0.4).rechargeWaterWt(0.4)
                            .initialTemperatureC(1165).rechargeTemperatureC(1180)
                            .initialOverpressureMPa(9.9999)
                            .build();
                    VolcanoSystem volcano = VolcanoSystem.builder("kilauea", List.of(vent), b.terrain(), b.lava())
                            .chamber(chamber)
                            .stations(Stations.network("KIL", vent, 40, 90))
                            .scaling(scaling)
                            .wind(7, 0.6, WIND_VARIABILITY)
                            .build();
                    return b.volcano(volcano);
                });
    }

    static Preset stromboli() {
        VolcanoScaling scaling = new VolcanoScaling(4, 100);
        return new Defined(
                "stromboli",
                "Stromboli-like cone (persistent mild explosive)",
                "A steep cone fed by gas-charged but fluid basalt through a narrow conduit, with supply roughly"
                        + " matching what the conduit can drain. Expect Strombolian activity: a mix of small lava"
                        + " output and frequent mild explosions with ballistics, sustained over the run.",
                List.of(
                        "Shoshonitic basalt, SiO2 ~49-52 wt%, T ~1100-1150 C",
                        "Deep 'LP' magma carries ~2.5-3.5 wt% H2O; shallow degassed magma <0.5 wt%",
                        "Explosions every ~10-20 min, ejecting ballistics up to ~100-200 m; magma supply ~0.1-0.5 m3/s",
                        "Edifice ~924 m a.s.l. (~3 km from sea floor); summit craters a few tens of metres across"),
                2,
                seed -> TerrainGenerators.cone(192, seed, new Cone(70, 170, 7, 5, 1.25, BlockId.minecraft("blackstone"))),
                (seed, terrain) -> {
                    Scenario.Builder b = new Scenario.Builder("stromboli", seed, terrain);
                    VentSite vent = crater("summit", terrain, 0, 0, 5);
                    MagmaChamberConfig chamber = MagmaChamberConfig.builder("stromboli", chamberBelow(vent, 48))
                            .volume(5e7)
                            .lithostaticDepth(3000)
                            .conduitRadius(0.8)
                            .tensileStrengthMPa(8)
                            .eruptionEndOverpressureMPa(0.5)
                            .supplyRate(0.002)
                            .supplyVariability(0.4)
                            .initialSilicaWt(50).rechargeSilicaWt(50)
                            .initialWaterWt(2.7).rechargeWaterWt(2.7)
                            .initialCo2Wt(0.3).rechargeCo2Wt(0.3)
                            .initialTemperatureC(1140).rechargeTemperatureC(1150)
                            // An open conduit over a CO₂-rich basaltic chamber below its re-opening pressure:
                            // whatever activity there is comes from chamber gas rising through it.
                            .initialOverpressureMPa(0)
                            .conduit(ConduitConfig.DEFAULT.withInitialOpenness(1).withReopenOverpressureMPa(1.5))
                            .build();
                    VolcanoSystem volcano = VolcanoSystem.builder("stromboli", List.of(vent), b.terrain(), b.lava())
                            .chamber(chamber)
                            .stations(Stations.network("STR", vent, 40, 90))
                            .scaling(scaling)
                            .wind(8, 1.2, WIND_VARIABILITY)
                            .build();
                    return b.volcano(volcano);
                });
    }

    static Preset stHelens() {
        VolcanoScaling scaling = new VolcanoScaling(10, 100);
        return new Defined(
                "st-helens",
                "Mount St. Helens-like stratovolcano (dacite, explosive then dome)",
                "A steep stratovolcano over a water-rich dacite reservoir at failure. Expect a Plinian/Vulcanian"
                        + " explosive phase with a high column, ballistics and downwind ash fall; as the chamber"
                        + " depressurises the style may relax towards dome extrusion.",
                List.of(
                        "Dacite, SiO2 ~63-64 wt%, T ~880-930 C, H2O ~4.6-6 wt% (1980 pumice)",
                        "Reservoir ~7-8 km depth; 18 May 1980 Plinian phase MER ~1-2e7 kg/s, column ~24-25 km",
                        "1980 DRE volume ~0.2-0.25 km3; 2004-2008 dome growth ~1-2 m3/s of degassed dacite",
                        "Pre-1980 summit ~2950 m; edifice basal radius ~6-8 km"),
                2,
                seed -> TerrainGenerators.cone(256, seed, new Cone(100, 230, 8, 4, 1.6, BlockId.minecraft("andesite"))),
                (seed, terrain) -> {
                    Scenario.Builder b = new Scenario.Builder("st-helens", seed, terrain);
                    VentSite vent = crater("summit", terrain, 0, 0, 6);
                    MagmaChamberConfig chamber = MagmaChamberConfig.builder("st-helens", chamberBelow(vent, 56))
                            .volume(5e9)
                            .lithostaticDepth(7500)
                            .conduitRadius(15)
                            .tensileStrengthMPa(15)
                            .eruptionEndOverpressureMPa(2)
                            .supplyRate(1.0)
                            .supplyVariability(0.2)
                            .initialSilicaWt(64).rechargeSilicaWt(62)
                            .initialWaterWt(4.6).rechargeWaterWt(4.6)
                            .initialTemperatureC(920).rechargeTemperatureC(950)
                            .initialOverpressureMPa(15.1)
                            .build();
                    VolcanoSystem volcano = VolcanoSystem.builder("st-helens", List.of(vent), b.terrain(), b.lava())
                            .chamber(chamber)
                            .stations(Stations.network("MSH", vent, 40, 90))
                            .scaling(scaling)
                            .wind(15, 0.0, WIND_VARIABILITY)
                            .build();
                    return b.volcano(volcano);
                });
    }

    static Preset pinatubo() {
        VolcanoScaling scaling = new VolcanoScaling(20, 125);
        return new Defined(
                "pinatubo",
                "Pinatubo-like Plinian eruption (cold, wet dacite)",
                "A large stratovolcano above a big, cool, water-saturated dacite reservoir at failure. Expect a"
                        + " sustained Plinian column reaching the top of the world, intense volcanic lightning and a"
                        + " broad downwind ash blanket.",
                List.of(
                        "Dacite, SiO2 ~64-65 wt%, T ~780 C, H2O ~6-6.5 wt% (1991 pumice)",
                        "Reservoir > 6 km depth; climactic phase 15 June 1991, ~0.7-1.5e5 m3/s DRE (Mastin et al. 2009)",
                        "Column 35-40 km; ~3.7-5.3 km3 DRE erupted",
                        "Pre-1991 summit ~1745 m"),
                1,
                seed -> TerrainGenerators.cone(320, seed, new Cone(90, 300, 10, 4, 1.5, BlockId.minecraft("andesite"))),
                (seed, terrain) -> {
                    Scenario.Builder b = new Scenario.Builder("pinatubo", seed, terrain);
                    VentSite vent = crater("summit", terrain, 0, 0, 8);
                    MagmaChamberConfig chamber = MagmaChamberConfig.builder("pinatubo", chamberBelow(vent, 60))
                            .volume(4e10)
                            .lithostaticDepth(7000)
                            .conduitRadius(60)
                            .tensileStrengthMPa(15)
                            .eruptionEndOverpressureMPa(2)
                            .supplyRate(2)
                            .supplyVariability(0.1)
                            .initialSilicaWt(64.5).rechargeSilicaWt(64.5)
                            .initialWaterWt(6.2).rechargeWaterWt(6.2)
                            .initialTemperatureC(780).rechargeTemperatureC(800)
                            .initialOverpressureMPa(15.1) // 0.8-1.6 km3 DRE in ~3 h (Mastin et al. 2009, Table 1)
                            .build();
                    VolcanoSystem volcano = VolcanoSystem.builder("pinatubo", List.of(vent), b.terrain(), b.lava())
                            .chamber(chamber)
                            .stations(Stations.network("PIN", vent, 40, 90))
                            .scaling(scaling)
                            .wind(20, Math.PI, WIND_VARIABILITY)
                            .build();
                    return b.volcano(volcano);
                });
    }

    static Preset surtsey() {
        VolcanoScaling scaling = new VolcanoScaling(4, 100);
        int seaLevel = 62;
        return new Defined(
                "surtsey",
                "Surtsey-like submarine eruption (emergent island)",
                "A basaltic seamount whose summit lies a few metres below sea level. Lava erupts into water,"
                        + " quenches to pillow lava and builds the edifice towards the surface.",
                List.of(
                        "Alkali olivine basalt, SiO2 ~46-47 wt%, T ~1150-1180 C",
                        "Eruption began Nov 1963 from ~130 m water depth; island emerged within days",
                        "Explosive (Surtseyan) phase while sea water reached the vent; effusive once isolated (1964-67)",
                        "Total ~1 km3 erupted; island ~1.4 km2 at end of eruption"),
                3,
                seed -> TerrainGenerators.island(192, seed, seaLevel, seaLevel - 32, seaLevel - 4, 140, 6),
                (seed, terrain) -> {
                    Scenario.Builder b = new Scenario.Builder("surtsey", seed, terrain);
                    VentSite vent = crater("surtur", terrain, 0, 0, 4);
                    MagmaChamberConfig chamber = MagmaChamberConfig.builder("surtsey", chamberBelow(vent, 48))
                            .volume(5e8)
                            .lithostaticDepth(3000)
                            .conduitRadius(2.0)
                            .tensileStrengthMPa(12)
                            .eruptionEndOverpressureMPa(1)
                            .supplyRate(2)
                            .supplyVariability(0.2)
                            .initialSilicaWt(46.5).rechargeSilicaWt(46.5)
                            .initialWaterWt(0.7).rechargeWaterWt(0.7)
                            .initialTemperatureC(1170).rechargeTemperatureC(1180)
                            .initialOverpressureMPa(11.99996)
                            .build();
                    VolcanoSystem volcano = VolcanoSystem.builder("surtsey", List.of(vent), b.terrain(), b.lava())
                            .chamber(chamber)
                            .stations(Stations.network("SUR", vent, 40, 90))
                            .scaling(scaling)
                            .wind(10, 0.8, WIND_VARIABILITY)
                            .build();
                    return b.volcano(volcano);
                });
    }

    static Preset yellowstone() {
        VolcanoScaling scaling = new VolcanoScaling(8, 100);
        return new Defined(
                "yellowstone",
                "Yellowstone-like hydrothermal caldera (no eruption)",
                "A caldera with a lake above a large, cool, crystal-rich rhyolite reservoir with negligible"
                        + " overpressure. Nothing erupts; instead the heat drives the hydrothermal system: fumaroles,"
                        + " sulfur deposits, geysers, hot springs, mud pots and alteration accumulate in the geyser"
                        + " basins (geothermal time is compressed 30x).",
                List.of(
                        "Rhyolite, SiO2 ~75-77 wt%, T ~750-850 C; reservoir ~5-17 km deep, mostly crystal mush",
                        "Caldera ~45 x 85 km (0.64 Ma); Yellowstone Lake ~ 2357 m a.s.l.",
                        "Mean heat flow ~2000 mW/m2 (~30-40x continental average); > 500 geysers, ~10,000 thermal features",
                        "Shallow hydrothermal reservoir temperatures ~200-270 C"),
                90, // hydrothermal features form over days
                seed -> TerrainGenerators.caldera(256, seed, 40, 160, 240, 34, 4),
                (seed, terrain) -> {
                    Scenario.Builder b = new Scenario.Builder("yellowstone", seed, terrain);
                    // Heat sources are hydrothermal basins, not eruptive vents; the geothermal grid is centred on
                    // the caldera above the chamber. West Thumb lies in the lake, like its real counterpart.
                    List<VentSite> basins = List.of(
                            crater("upper-geyser-basin", terrain, 40, 20, 8),
                            crater("norris", terrain, 30, -80, 6),
                            crater("mud-volcano", terrain, 70, 90, 6),
                            crater("west-thumb", terrain, -100, 10, 6));
                    MagmaChamberConfig chamber = MagmaChamberConfig.builder("yellowstone",
                                    new BlockPos(0, -40, 0))
                            .volume(1e10)
                            .lithostaticDepth(6000)
                            .tensileStrengthMPa(20)
                            .supplyRate(0.001)
                            .supplyVariability(0)
                            .initialSilicaWt(75).rechargeSilicaWt(75)
                            .initialWaterWt(4).rechargeWaterWt(4)
                            .initialTemperatureC(820).rechargeTemperatureC(850)
                            .initialOverpressureMPa(0)
                            .build();
                    GeothermalConfig geothermal = new GeothermalConfig();
                    geothermal.radius = 192;
                    // Basin heat flux of a few hundred W/m² (Upper Geyser Basin–Norris order; the caldera as a whole
                    // discharges ~5 GW, Fournier 1989): at 820 °C the activity is ~0.44, so ~1.3·10⁷ W per basin.
                    geothermal.ventHeatPowerW = 3e7;
                    geothermal.ventPipeDepthM = 300;
                    geothermal.maxGeysers = 20;       // > 500 geysers in reality; the densest field on Earth
                    // Wet plateau (~500–1500 mm/yr) of porous rhyolite and sinter: a shallow water table.
                    SubsurfaceConfig subsurface = VolcanoSystem.defaultSubsurfaceConfig(scaling);
                    subsurface.initialWaterTableDepthM = 3;
                    subsurface.rainfallMmPerHour = 0.1;
                    subsurface.gradientCPerKm = 100; // caldera heat flow ~30–40× the continental average
                    VolcanoSystem volcano = VolcanoSystem.builder("yellowstone", basins, b.terrain(), b.lava())
                            .chamber(chamber)
                            .stations(Stations.network("YEL", basins.get(0), 40, 90))
                            .scaling(scaling)
                            .geothermal(geothermal)
                            .subsurfaceConfig(subsurface)
                            .geothermalPrewarm(300 * 365.25 * 86400) // centuries of spin-up: a developed hydrothermal system
                            .build();
                    return b.volcano(volcano);
                });
    }
}
