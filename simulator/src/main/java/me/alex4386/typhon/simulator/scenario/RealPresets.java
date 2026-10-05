package me.alex4386.typhon.simulator.scenario;

import static me.alex4386.typhon.simulator.scenario.ReferenceValue.Metric;

import java.util.List;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.config.WorldDefinition;
import me.alex4386.typhon.engine.geothermal.GeothermalConfig;
import me.alex4386.typhon.engine.magma.ConduitConfig;
import me.alex4386.typhon.engine.magma.MagmaChamberConfig;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.tephra.TephraConfig;
import me.alex4386.typhon.engine.subsurface.SubsurfaceConfig;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.Edifice;
import me.alex4386.typhon.engine.world.WorldSpec;
import me.alex4386.typhon.simulator.terrain.ColumnGrid;
import me.alex4386.typhon.simulator.terrain.DemImporter;
import me.alex4386.typhon.simulator.terrain.RealTerrain;

/**
 * Real-scale presets ({@code <name>-real}): km-wide domains of 10–30 m columns with geometry fitted to
 * published edifice dimensions, real geology layering and initial conditions, and a documented real
 * DEM that can replace the synthetic terrain ({@code --dem}).
 *
 * <p>Blocks are cubes of {@code L} metres in every direction (no vertical exaggeration), so eruption
 * columns also use {@code L} metres per block and may rise far above Minecraft's build height. Time
 * compression is the same as the compact presets: dormant phases ×5000, eruptions in real time.
 * Magma-system parameters are those of the compact presets (already real units, see
 * {@link Presets}).
 */
final class RealPresets {
    private RealPresets() {}

    static List<Preset> all() {
        return List.of(kilauea(), stromboli(), stHelens(), pinatubo(), surtsey(), yellowstone());
    }

    /** A real-scale preset: compact-preset magma physics on a real setting. */
    record Real(String name, String title, String description, List<String> references, double defaultHours,
            RealSetting realSetting, List<ReferenceValue> referenceValues, java.util.function.LongFunction<ColumnGrid> terrain,
            Presets.Assembly assembly) implements Preset {
        @Override
        public ColumnGrid terrain(long seed) {
            return terrain.apply(seed);
        }

        @Override
        public Scenario build(long seed, ColumnGrid grid, Scenario.Options options) {
            return assembly.build(seed, grid).options(options).build();
        }
    }

    static Scenario.Builder builder(String name, long seed, ColumnGrid terrain, RealSetting setting) {
        return new Scenario.Builder(name, seed, terrain, setting.spec(), setting.edifices());
    }

    /** Geometric scaling: blocks (and eruption columns) are L-metre cubes; dormancy ×5000, eruptions real time. */
    static VolcanoScaling scaling(double metersPerColumn) {
        return new VolcanoScaling(metersPerColumn, metersPerColumn, 5000, 1);
    }

    /** Tephra with the column cap raised to the top of the engine's coordinate range. */
    static TephraConfig tephra() {
        TephraConfig t = new TephraConfig();
        t.worldTopY = BlockPos.MAX_Y;
        return t;
    }

    /** Block (y) whose top is {@code elevation} metres. */
    static int y(double elevation, double metersPerColumn) {
        return DemImporter.groundBlock(elevation, metersPerColumn);
    }

    /** Stromboli's crater terrace, 300 m NW of the summit (m from the centre). */
    static final double CRATER_X = -300 / Math.sqrt(2);
    static final double CRATER_Z = -300 / Math.sqrt(2);

    /**
     * Edifice zone centred on the column of the primary vent at real offset (m) — volcano definitions
     * place edifices on the primary vent, so world templates reproduce the preset exactly.
     */
    static Edifice edifice(String id, double xm, double zm, double metersPerColumn, double radiusColumns, double baseZ,
            String material) {
        return new Edifice(id, Math.floor(xm / metersPerColumn) + 0.5, Math.floor(zm / metersPerColumn) + 0.5,
                radiusColumns, baseZ, material);
    }

    /** Crater vent at real offset (m) from the centre, its floor on the terrain's ground. */
    static VentSite vent(String id, ColumnGrid terrain, double metersPerColumn, double xm, double zm, double radiusM) {
        int x = (int) Math.floor(xm / metersPerColumn);
        int z = (int) Math.floor(zm / metersPerColumn);
        return VentSite.crater(id, new BlockPos(x, terrain.ground(x, z), z),
                Math.max(1, (int) Math.round(radiusM / metersPerColumn)));
    }

    /** Chamber centre under a vent at an elevation (m a.s.l.; negative below sea level). */
    static BlockPos chamberAt(VentSite vent, double elevation, double metersPerColumn) {
        return new BlockPos(vent.position().x(), Math.max(BlockPos.MIN_Y + 1, y(elevation, metersPerColumn)),
                vent.position().z());
    }

    static RealTerrain.Paint rock(String rock, double vegetationBelow) {
        BlockId r = BlockId.minecraft(rock);
        BlockId grass = BlockId.minecraft("grass_block");
        BlockId sand = BlockId.minecraft("sand");
        return (xm, zm, e, submerged) -> submerged ? sand : e < vegetationBelow ? grass : r;
    }

    // ── Kīlauea ──

    static Preset kilauea() {
        double L = 20;
        int half = 256; // ±5.1 km
        RealSetting setting = new RealSetting(
                new WorldSpec(L, 4 * L, -6000, Double.NaN,
                        List.of(new WorldSpec.GeologyLayer("gabbro", -5000, 0.01)), "basalt", "basalt", L),
                List.of(edifice("kilauea-real", 0, 0, L, Double.POSITIVE_INFINITY, Double.NaN, "basalt")),
                new WorldDefinition.Geotherm(18, 60, 6.5),
                // flat basal water table at ~610 m a.s.l. under the summit (NSF drill hole, Keller et al. 1979)
                new WorldDefinition.Aquifer(30, 0.1, 0.0, 610, 0.5),
                half,
                RealSetting.DemSource.at(19.4069, -155.2834,
                        "SRTM/Copernicus show the summit after the 2018 collapse (Halema'uma'u ~500 m deep);"
                                + " the synthetic terrain uses the pre-2018 geometry."));
        return new Real(
                "kilauea-real",
                "Kīlauea summit at real scale (basaltic, effusive)",
                "A 10 km window over Kīlauea's summit at 20 m per column: a gently sloping shield (3–6°) with a"
                        + " 3 km caldera (~120 m deep) and the Halema'uma'u pit crater (1 km across, 85 m deep, pre-2008)"
                        + " over a shallow basaltic reservoir close to failure. Expect Hawaiian effusion filling the pit"
                        + " and the caldera floor.",
                List.of(
                        "Summit 1247 m; caldera ~3 x 5 km, floor ~1100 m (pre-2018) (USGS HVO)",
                        "Halema'uma'u ~1 km wide, ~85 m deep before 2008 (Wolfe et al. 1988; Neal et al. 2019)",
                        "Shield flanks 3-6 deg near the summit (Peterson & Moore 1987)",
                        "Summit reservoir 1-2 km below the caldera (Poland et al. 2014); supply 0.1-0.2 km3/yr",
                        "Effusion rates 1-10 m3/s, 2018 LERZ peak ~50-100 m3/s (Neal et al. 2019)",
                        "Basal water table ~610 m a.s.l. under the summit (Keller et al. 1979)"),
                3,
                setting,
                List.of(
                        ReferenceValue.range("Summit elevation", 1200, 1300, "m", "USGS HVO", Metric.SUMMIT_ELEVATION_M),
                        ReferenceValue.range("Effusion rate (peak)", 1, 100, "m³/s",
                                "Poland et al. 2014; Neal et al. 2019", Metric.PEAK_ERUPTION_RATE_M3S),
                        ReferenceValue.category("Eruption style", "HAWAIIAN", "basaltic, H₂O < 0.5 wt%",
                                Metric.ANY_STYLE),
                        ReferenceValue.range("Longest lava flow", 100, 10000, "m",
                                "Hawaiian flows: hundreds of m to km per day (Neal et al. 2019)", Metric.LONGEST_FLOW_M)
                                .informative("the summit eruption first ponds in Halema'uma'u; flow length over"
                                        + " a few hours is not a calibrated quantity")),
                seed -> RealTerrain.build(L, half, seed, (xm, zm) -> {
                    double d = RealTerrain.dist(xm, zm, 0, 0);
                    double shield = 1247 - Math.tan(Math.toRadians(4)) * Math.max(0, d - 1900);
                    double caldera = RealTerrain.pit(shield, d, 1900, 250, 1100);
                    return RealTerrain.pit(caldera, d, 500, 60, 1015);
                }, 4, 300, Double.NaN, rock("basalt", 0)),
                (seed, terrain) -> {
                    Scenario.Builder b = builder("kilauea-real", seed, terrain, setting);
                    VentSite vent = vent("halemaumau", terrain, L, 0, 0, 140);
                    MagmaChamberConfig chamber = MagmaChamberConfig.builder("kilauea-real", chamberAt(vent, -400, L))
                            .volume(1e9).lithostaticDepth(1500).conduitRadius(2.0).tensileStrengthMPa(10)
                            .eruptionEndOverpressureMPa(1).supplyRate(3).supplyVariability(0.2)
                            .initialSilicaWt(50).rechargeSilicaWt(50).initialWaterWt(0.4).rechargeWaterWt(0.4)
                            .initialTemperatureC(1165).rechargeTemperatureC(1180).initialOverpressureMPa(9.5)
                            .maxEruptionRate(150)
                            .build();
                    return b.volcano(VolcanoSystem.builder("kilauea-real", List.of(vent), b.terrain(), b.lava())
                            .chamber(chamber).scaling(scaling(L)).tephra(tephra())
                            .wind(7, 0.6, Presets.WIND_VARIABILITY).build());
                });
    }

    // ── Stromboli ──

    static Preset stromboli() {
        double L = 15;
        int half = 256; // ±3.8 km
        RealSetting setting = new RealSetting(
                new WorldSpec(L, 4 * L, -4000, 0,
                        List.of(new WorldSpec.GeologyLayer("sediment", -2500, 0.15)), "basalt", "scoria", L),
                List.of(edifice("stromboli-real", CRATER_X, CRATER_Z, L, 600, -2500, "basalt")),
                new WorldDefinition.Geotherm(17, 80, 6.5),
                new WorldDefinition.Aquifer(5, 0.15, 0.05, 0, 0.2), // thin basal lens at sea level
                half,
                RealSetting.DemSource.at(38.7939, 15.2133,
                        "Land DEMs set the sea to 0 m: use EMODnet or GEBCO bathymetry for the submarine flanks."));
        double craterX = CRATER_X;
        double craterZ = CRATER_Z;
        return new Real(
                "stromboli-real",
                "Stromboli at real scale (persistent Strombolian)",
                "Stromboli island at 15 m per column: a 924 m cone reaching the shoreline ~2 km out and plunging to"
                        + " ~-700 m at the domain edge, with the Sciara del Fuoco scar on the NW flank and the crater"
                        + " terrace at ~750 m, 300 m NW of the summit. Expect persistent mild explosions.",
                List.of(
                        "Summit 924 m; subaerial island ~12.6 km2; edifice ~3 km above the sea floor (Rosi et al. 2013)",
                        "Crater terrace ~750 m a.s.l. at the head of the Sciara del Fuoco (NW flank, ~35 deg)",
                        "Shoshonitic basalt SiO2 ~49-52 wt%, H2O 2.5-3.5 wt% (Bertagnini et al. 2003)",
                        "Explosions every ~10-20 min (3-6/h) up to ~10-20/h at times (Ripepe et al. 2008)",
                        "Shallow reservoir ~2-4 km below sea level (Bertagnini et al. 2003)"),
                2,
                setting,
                List.of(
                        ReferenceValue.range("Summit elevation", 900, 950, "m", "Rosi et al. 2013", Metric.SUMMIT_ELEVATION_M),
                        ReferenceValue.range("Explosion rate", 3, 20, "per hour", "Ripepe et al. 2008",
                                Metric.EXPLOSIONS_PER_HOUR),
                        ReferenceValue.category("Eruption style", "STROMBOLIAN", "open-vent basaltic system",
                                Metric.ANY_STYLE)),
                seed -> RealTerrain.build(L, half, seed, (xm, zm) -> {
                    double d = RealTerrain.dist(xm, zm, 0, 0);
                    double cone = 924 - 2924 * Math.pow(Math.min(1, d / 7500), 0.9);
                    // Sciara del Fuoco: a trough on the NW flank (azimuth ~315°), deepest at mid-slope
                    double az = Math.atan2(-zm, xm); // 0 = east, +90° = north
                    double off = Math.abs(Math.toDegrees(Math.atan2(Math.sin(az - Math.toRadians(135)),
                            Math.cos(az - Math.toRadians(135)))));
                    if (off < 18 && d > 250) cone -= 110 * (1 - off / 18) * Math.min(1, (d - 250) / 600);
                    return RealTerrain.crater(cone, RealTerrain.dist(xm, zm, craterX, craterZ), 130, 45);
                }, 6, 150, 0, rock("blackstone", -1e9)),
                (seed, terrain) -> {
                    Scenario.Builder b = builder("stromboli-real", seed, terrain, setting);
                    VentSite vent = vent("crater-terrace", terrain, L, craterX, craterZ, 60);
                    MagmaChamberConfig chamber = MagmaChamberConfig.builder("stromboli-real", chamberAt(vent, -3000, L))
                            .volume(5e7).lithostaticDepth(3000).conduitRadius(0.8).tensileStrengthMPa(8)
                            .eruptionEndOverpressureMPa(0.5).supplyRate(0.4).supplyVariability(0.4)
                            .initialSilicaWt(50).rechargeSilicaWt(50).initialWaterWt(2.7).rechargeWaterWt(2.7)
                            .initialTemperatureC(1140).rechargeTemperatureC(1150).initialOverpressureMPa(1.5)
                            .conduit(ConduitConfig.DEFAULT.withInitialOpenness(1).withReopenOverpressureMPa(1.5))
                            .build();
                    return b.volcano(VolcanoSystem.builder("stromboli-real", List.of(vent), b.terrain(), b.lava())
                            .chamber(chamber).scaling(scaling(L)).tephra(tephra()).ballisticFraction(0.3)
                            .wind(8, 1.2, Presets.WIND_VARIABILITY).build());
                });
    }

    // ── Mount St. Helens ──

    static Preset stHelens() {
        double L = 20;
        int half = 256; // ±5.1 km
        RealSetting setting = new RealSetting(
                new WorldSpec(L, 4 * L, -6000, Double.NaN,
                        List.of(new WorldSpec.GeologyLayer("granite", -2000, 0.01)), "andesite", "soil", L),
                List.of(edifice("st-helens-real", 0, 0, L, 300, 1100, "dacite")),
                new WorldDefinition.Geotherm(8, 40, 6.5),
                new WorldDefinition.Aquifer(30, 0.1, 0.6, Double.NaN, 0.4),
                half,
                RealSetting.DemSource.at(46.1914, -122.1956,
                        "DEMs show the post-1980 horseshoe crater (summit 2549 m); the synthetic terrain is the"
                                + " pre-1980 cone."));
        return new Real(
                "st-helens-real",
                "Mount St. Helens at real scale (Plinian, then dome growth)",
                "The pre-1980 cone at 20 m per column: summit 2950 m on a ~1200 m plateau, basal radius ~6 km,"
                        + " concave flanks, over a water-rich dacite reservoir at failure. Expect a Plinian column,"
                        + " ballistics and downwind ash, then degassed dome extrusion.",
                List.of(
                        "Pre-1980 summit 2950 m, basal diameter ~10-12 km (Mullineaux & Crandell 1981)",
                        "Dacite SiO2 63-64 wt%, 880-930 C, H2O 4.6-6 wt% (Rutherford et al. 1985)",
                        "Reservoir 7-8 km depth (Scandone & Malone 1985; Pallister et al. 1992)",
                        "18 May 1980 Plinian MER ~1-2e7 kg/s, column 19-24 km (Carey & Sigurdsson 1985)",
                        "2004-2008 dome growth ~1-2 m3/s of degassed dacite (Schilling et al. 2008)"),
                8,
                setting,
                List.of(
                        ReferenceValue.range("Summit elevation (pre-1980)", 2900, 3000, "m",
                                "Mullineaux & Crandell 1981", Metric.SUMMIT_ELEVATION_M),
                        ReferenceValue.range("Plinian column top", 19, 25, "km a.s.l.", "Carey & Sigurdsson 1985",
                                Metric.PLUME_TOP_KM),
                        ReferenceValue.range("Peak eruption rate (DRE)", 2000, 8000, "m³/s",
                                "1-2e7 kg/s / 2500 kg/m³ (Carey & Sigurdsson 1985)", Metric.PEAK_ERUPTION_RATE_M3S),
                        ReferenceValue.category("Later style", "LAVA_DOME", "2004-2008 (Schilling et al. 2008)",
                                Metric.ANY_STYLE),
                        ReferenceValue.range("Explosions during dome growth", 1, Double.NaN, "",
                                "Vulcanian explosions through the dome, 1980-86 and 2004-08 (Swanson & Holcomb 1990;"
                                        + " Scott et al. 2008)", Metric.EXPLOSIONS_AFTER_DOME)),
                seed -> RealTerrain.build(L, half, seed, (xm, zm) -> {
                    // pre-1980: a symmetric cone topped by a small summit dome, no crater
                    return RealTerrain.cone(RealTerrain.dist(xm, zm, 0, 0), 2950, 1200, 6000, 1.6);
                }, 12, 400, Double.NaN, rock("andesite", 1500)),
                (seed, terrain) -> {
                    Scenario.Builder b = builder("st-helens-real", seed, terrain, setting);
                    VentSite vent = vent("summit", terrain, L, 0, 0, 120);
                    MagmaChamberConfig chamber = MagmaChamberConfig.builder("st-helens-real", chamberAt(vent, -4800, L))
                            .volume(5e9).lithostaticDepth(7500).conduitRadius(15).tensileStrengthMPa(15)
                            .eruptionEndOverpressureMPa(2).supplyRate(1.0).supplyVariability(0.2)
                            .initialSilicaWt(64).rechargeSilicaWt(62).initialWaterWt(4.6).rechargeWaterWt(4.6)
                            .initialTemperatureC(920).rechargeTemperatureC(950).initialOverpressureMPa(15.1)
                            .maxEruptionRate(8000)
                            .build();
                    return b.volcano(VolcanoSystem.builder("st-helens-real", List.of(vent), b.terrain(), b.lava())
                            .chamber(chamber).scaling(scaling(L)).tephra(tephra()).ballisticFraction(0.02)
                            .wind(15, 0.0, Presets.WIND_VARIABILITY).build());
                });
    }

    // ── Pinatubo ──

    static Preset pinatubo() {
        double L = 30;
        int half = 256; // ±7.7 km
        RealSetting setting = new RealSetting(
                new WorldSpec(L, 4 * L, -8000, Double.NaN,
                        List.of(new WorldSpec.GeologyLayer("gabbro", -1500, 0.01)), "andesite", "soil", L),
                List.of(edifice("pinatubo-real", 0, 0, L, 240, 400, "dacite")),
                new WorldDefinition.Geotherm(26, 40, 6.5),
                new WorldDefinition.Aquifer(20, 0.1, 0.6, Double.NaN, 0.5),
                half,
                RealSetting.DemSource.at(15.1429, 120.3496,
                        "DEMs show the 2.5 km post-1991 caldera and its lake; the synthetic terrain is pre-1991."));
        return new Real(
                "pinatubo-real",
                "Pinatubo at real scale (Plinian)",
                "The pre-1991 edifice at 30 m per column: a 1745 m summit rising from ~400 m foothills over a cool,"
                        + " water-saturated dacite reservoir at failure. Expect a sustained Plinian column far above"
                        + " Minecraft's build height (blocks are 30 m cubes) and a broad downwind ash blanket.",
                List.of(
                        "Pre-1991 summit 1745 m (Newhall & Punongbayan 1996)",
                        "Dacite SiO2 64-65 wt%, ~780 C, H2O 6-6.5 wt% (Rutherford & Devine 1996)",
                        "Reservoir > 6 km depth (Pallister et al. 1996)",
                        "15 June 1991 climactic column 35-40 km (Holasek et al. 1996), MER ~1e9 kg/s",
                        "3.7-5.3 km3 DRE erupted (Scott et al. 1996)"),
                1,
                setting,
                List.of(
                        ReferenceValue.range("Summit elevation (pre-1991)", 1700, 1800, "m",
                                "Newhall & Punongbayan 1996", Metric.SUMMIT_ELEVATION_M),
                        ReferenceValue.range("Plinian column top", 35, 40, "km a.s.l.", "Holasek et al. 1996",
                                Metric.PLUME_TOP_KM),
                        ReferenceValue.range("Peak eruption rate (DRE)", 2e5, 6e5, "m³/s",
                                "~1e9 kg/s / 2500 kg/m³", Metric.PEAK_ERUPTION_RATE_M3S),
                        ReferenceValue.range("Ash deposit downwind / upwind", 3, Double.NaN, "",
                                "fall deposit elongated downwind (WSW, Typhoon Yunya) (Paladio-Melosantos et al. 1996)",
                                Metric.ASH_DOWNWIND_RATIO)),
                seed -> RealTerrain.build(L, half, seed, (xm, zm) -> {
                    // pre-1991: a dissected cone topped by a dome complex, no open crater
                    return RealTerrain.cone(RealTerrain.dist(xm, zm, 0, 0), 1745, 400, 7000, 1.8);
                }, 35, 900, Double.NaN, rock("andesite", 900)),
                (seed, terrain) -> {
                    Scenario.Builder b = builder("pinatubo-real", seed, terrain, setting);
                    VentSite vent = vent("summit", terrain, L, 0, 0, 240);
                    MagmaChamberConfig chamber = MagmaChamberConfig.builder("pinatubo-real", chamberAt(vent, -5300, L))
                            .volume(4e10).lithostaticDepth(7000).conduitRadius(25).tensileStrengthMPa(15)
                            .eruptionEndOverpressureMPa(2).supplyRate(2).supplyVariability(0.1)
                            .initialSilicaWt(64.5).rechargeSilicaWt(64.5).initialWaterWt(6.2).rechargeWaterWt(6.2)
                            .initialTemperatureC(780).rechargeTemperatureC(800).initialOverpressureMPa(15.1)
                            .maxEruptionRate(4e5)
                            .build();
                    return b.volcano(VolcanoSystem.builder("pinatubo-real", List.of(vent), b.terrain(), b.lava())
                            .chamber(chamber).scaling(scaling(L)).tephra(tephra()).ballisticFraction(0.005)
                            .wind(20, Math.PI, Presets.WIND_VARIABILITY).build());
                });
    }

    // ── Surtsey ──

    static Preset surtsey() {
        double L = 10;
        int half = 192; // ±1.9 km
        RealSetting setting = new RealSetting(
                new WorldSpec(L, 4 * L, -3000, 0,
                        List.of(new WorldSpec.GeologyLayer("basalt", -400, 0.05)), "sediment", "sediment", L),
                List.of(edifice("surtsey-real", 0, 0, L, 60, -130, "hyaloclastite")),
                new WorldDefinition.Geotherm(5, 60, 6.5),
                new WorldDefinition.Aquifer(0, 0.2, 0.0, 0, 0.5), // saturated by the sea
                half,
                RealSetting.DemSource.at(63.3033, -20.6046,
                        "Land DEMs only cover today's island (~1.3 km2, 155 m): use EMODnet bathymetry for the shelf."));
        return new Real(
                "surtsey-real",
                "Surtsey at real scale (submarine to emergent)",
                "A 3.8 km window of the Icelandic shelf at 10 m per column: sea floor at -130 m and a young"
                        + " hyaloclastite cone whose summit is 15 m below sea level (the state a few days into the"
                        + " November 1963 eruption). Expect Surtseyan explosions while sea water reaches the vent,"
                        + " then effusion building an island.",
                List.of(
                        "Eruption began Nov 1963 at ~130 m water depth; the island emerged 14 Nov (Thorarinsson 1967)",
                        "Alkali olivine basalt, SiO2 ~46-47 wt%, 1150-1180 C",
                        "Surtseyan (phreatomagmatic) until the vent was sealed from the sea (April 1964), then effusive",
                        "Island 2.7 km2 and 173 m high by 1967 (Jakobsson et al. 2000)"),
                2,
                setting,
                List.of(
                        ReferenceValue.range("Final highest point", 0, 173, "m a.s.l.",
                                "island emerges; 173 m by 1967 (Jakobsson et al. 2000)", Metric.FINAL_MAX_ELEVATION_M),
                        ReferenceValue.category("Eruption style", "HAWAIIAN", "effusive once sealed (1964-67)",
                                Metric.ANY_STYLE),
                        ReferenceValue.category("Vent-water sequence", "SURTSEYAN→EFFUSIVE",
                                "explosive while sea water reached the vent, effusive once the tephra ring sealed it"
                                        + " (Thorarinsson 1967; Jakobsson et al. 2000)", Metric.PHREATOMAGMATIC_SEQUENCE),
                        ReferenceValue.value("Surtseyan phase duration", 3400, "h",
                                "14 Nov 1963 to 4 Apr 1964, ~142 days (Thorarinsson 1967)", Metric.PHREATOMAGMATIC_HOURS)
                                .informative("the scenario starts with the vent at 15 m depth and runs for hours;"
                                        + " months of tephra-ring growth are not reproduced")),
                seed -> RealTerrain.build(L, half, seed, (xm, zm) -> {
                    double d = RealTerrain.dist(xm, zm, 0, 0);
                    double cone = RealTerrain.cone(d, -15, -130, 500, 1.3);
                    return RealTerrain.crater(cone, d, 60, 12);
                }, 3, 200, 0, rock("basalt", -1e9)),
                (seed, terrain) -> {
                    Scenario.Builder b = builder("surtsey-real", seed, terrain, setting);
                    VentSite vent = vent("surtur", terrain, L, 0, 0, 40);
                    MagmaChamberConfig chamber = MagmaChamberConfig.builder("surtsey-real", chamberAt(vent, -3000, L))
                            .volume(5e8).lithostaticDepth(3000).conduitRadius(2.0).tensileStrengthMPa(12)
                            .eruptionEndOverpressureMPa(1).supplyRate(2).supplyVariability(0.2)
                            .initialSilicaWt(46.5).rechargeSilicaWt(46.5).initialWaterWt(0.7).rechargeWaterWt(0.7)
                            .initialTemperatureC(1170).rechargeTemperatureC(1180).initialOverpressureMPa(11.8)
                            .maxEruptionRate(100)
                            .build();
                    return b.volcano(VolcanoSystem.builder("surtsey-real", List.of(vent), b.terrain(), b.lava())
                            .chamber(chamber).scaling(scaling(L)).tephra(tephra())
                            .wind(10, 0.8, Presets.WIND_VARIABILITY).build());
                });
    }

    // ── Yellowstone ──

    static Preset yellowstone() {
        double L = 30;
        int half = 256; // ±7.7 km
        RealSetting setting = new RealSetting(
                new WorldSpec(L, 4 * L, -8000, Double.NaN,
                        List.of(new WorldSpec.GeologyLayer("granite", -3000, 0.01)), "rhyolite", "soil", L),
                List.of(),
                new WorldDefinition.Geotherm(1, 60, 6.5),
                new WorldDefinition.Aquifer(5, 0.2, 0.8, Double.NaN, 0.4),
                half,
                RealSetting.DemSource.at(44.4605, -110.8281,
                        "Centred on Old Faithful (Upper Geyser Basin); the synthetic terrain is a generic slice of"
                                + " the caldera floor with a rim to the west and a lake to the east."));
        double lakeX = 3500;
        double lakeZ = 0;
        return new Real(
                "yellowstone-real",
                "Yellowstone caldera floor at real scale (hydrothermal)",
                "A 15 km slice of the Yellowstone caldera at 30 m per column: a rhyolite plateau at ~2400 m with the"
                        + " caldera rim rising ~250 m to the west, a 60 m deep lake at 2357 m to the east and four"
                        + " hydrothermal basins over a large, crystal-rich reservoir. Nothing erupts; the heat drives"
                        + " fumaroles, geysers, springs and alteration (geothermal time ×30).",
                List.of(
                        "Caldera floor ~2200-2500 m; rim relief ~100-300 m (Christiansen 2001)",
                        "Yellowstone Lake 2357 m, West Thumb ~ 60 m deep (Morgan et al. 2003)",
                        "Reservoir 5-17 km deep, mostly crystal mush (Farrell et al. 2014; Huang et al. 2015)",
                        "> 500 geysers, ~10,000 thermal features; heat flow ~2000 mW/m2 (Hurwitz & Lowenstern 2014)"),
                3,
                setting,
                List.of(
                        ReferenceValue.range("Plateau / rim elevation", 2300, 2700, "m", "Christiansen 2001",
                                Metric.SUMMIT_ELEVATION_M),
                        ReferenceValue.range("Geysers formed", 1, Double.NaN, "", "> 500 in reality (preset cap 20)",
                                Metric.GEYSERS),
                        ReferenceValue.range("Hot / sulfur springs formed", 1, Double.NaN, "",
                                "thousands of hot springs (Fournier 1989)", Metric.SPRINGS),
                        ReferenceValue.range("Eruptions", 0, 0, "", "no eruption in the last ~70 kyr (Christiansen 2001)",
                                Metric.ERUPTIONS)),
                seed -> {
                    ColumnGrid grid = RealTerrain.build(L, half, seed, (xm, zm) -> {
                        double plateau = 2400 + 250 * smoothStep(-4000, -6500, xm); // caldera rim to the west
                        double lake = RealTerrain.dist(xm, zm, lakeX, lakeZ);
                        return RealTerrain.crater(plateau, lake, 2600, 100);
                    }, 30, 1200, Double.NaN, rock("tuff", 2380));
                    RealTerrain.lake(grid, L, lakeX, lakeZ, 2600, 2357);
                    return grid;
                },
                (seed, terrain) -> {
                    Scenario.Builder b = builder("yellowstone-real", seed, terrain, setting);
                    List<VentSite> basins = List.of(
                            vent("upper-geyser-basin", terrain, L, 0, 0, 240),
                            vent("norris", terrain, L, -1500, -3500, 180),
                            vent("mud-volcano", terrain, L, 2000, 3000, 180),
                            vent("west-thumb", terrain, L, lakeX - 1500, 600, 180));
                    MagmaChamberConfig chamber = MagmaChamberConfig.builder("yellowstone-real",
                                    new BlockPos(0, y(-3600, L), 0))
                            .volume(1e10).lithostaticDepth(6000).tensileStrengthMPa(20).supplyRate(0.001)
                            .supplyVariability(0).initialSilicaWt(75).rechargeSilicaWt(75).initialWaterWt(4)
                            .rechargeWaterWt(4).initialTemperatureC(820).rechargeTemperatureC(850)
                            .initialOverpressureMPa(0)
                            .build();
                    GeothermalConfig geothermal = new GeothermalConfig();
                    geothermal.radius = 192;
                    geothermal.timeScale = 30;
                    // Basin heat flux and a wet plateau as in the compact Yellowstone preset (Fournier 1989).
                    geothermal.ventHeatPowerW = 3e7;
                    geothermal.ventPipeDepthM = 300;
                    geothermal.maxGeysers = 20;
                    SubsurfaceConfig subsurface = VolcanoSystem.defaultSubsurfaceConfig(scaling(L));
                    subsurface.initialWaterTableDepthM = 3;
                    subsurface.rainfallMmPerHour = 0.1;
                    subsurface.gradientCPerKm = 100; // caldera heat flow ~30–40× the continental average
                    return b.volcano(VolcanoSystem.builder("yellowstone-real", basins, b.terrain(), b.lava())
                            .chamber(chamber).scaling(scaling(L)).tephra(tephra()).geothermal(geothermal)
                            .subsurfaceConfig(subsurface)
                            .geothermalPrewarm(300 * 365.25 * 86400).build());
                });
    }

    /** 0 at {@code from}, 1 at {@code to}, smooth in between (either order). */
    static double smoothStep(double from, double to, double v) {
        double t = Math.max(0, Math.min(1, (v - from) / (to - from)));
        return t * t * (3 - 2 * t);
    }
}
