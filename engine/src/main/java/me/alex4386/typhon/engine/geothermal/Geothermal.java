package me.alex4386.typhon.engine.geothermal;

import me.alex4386.typhon.engine.config.ConfigCopy;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Supplier;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.random.SimRandom;
import me.alex4386.typhon.engine.sim.Parallel;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.subsurface.HeatSources;
import me.alex4386.typhon.engine.subsurface.HydrothermalField;
import me.alex4386.typhon.engine.subsurface.WaterSaturation;
import me.alex4386.typhon.engine.subsurface.Subsurface;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.MagmaState;
import me.alex4386.typhon.engine.volcano.VentKind;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.LayerView;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialClass;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.Provenance;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;

/**
 * Geothermal and hydrothermal manifestations around one volcano: fumaroles, sulfur, geysers, hot
 * and sulfur springs, mud pots, sinter, alteration, cinnabar, submarine vents and gas hazards.
 *
 * <h2>Model</h2>
 *
 * The physics lives in the world-level subsurface model ({@link HydrothermalField}, normally the
 * shared {@link me.alex4386.typhon.engine.subsurface.Subsurface}): heat conduction and advection,
 * the water table, boiling and steam. This subsystem
 * <ul>
 *   <li>supplies the volcano's heat to it ({@link HeatSources}): the chamber's conductive halo and
 *       vent heat pipes whose power grows with the magma activity (chamber temperature,
 *       overpressure, eruption rate);
 *   <li>samples it every step on a coarse feature grid ({@link GeothermalGrid}): the temperature of
 *       the shallow reservoir at {@code reservoirDepthM}, and its liquid saturation
 *       {@code w = clamp(½ + (R − d)/(2R))} from the water-table depth {@code d}
 *       (a water table at the surface gives 1, at the reservoir depth ½, at twice that depth 0;
 *       boiling lowers the table where the aquifer cannot resupply the steam);
 *   <li>decides manifestations from those fields: per cell and feature, a Poisson number of
 *       formation attempts with mean {@code rate × strength × dt/3600} is drawn; each attempt picks
 *       a random column of the cell and forms the feature if local conditions allow (dry, solid
 *       ground; spacing; caps; containment of pools).
 * </ul>
 * Wet ground is held near its boiling point by the subsurface model (liquid-dominated: springs,
 * geysers, sinter), while poorly supplied ground boils dry and heats up (vapour-dominated:
 * fumaroles, acid alteration) — the dichotomy of real hydrothermal systems emerges from the water
 * table and boiling physics rather than a parameterised saturation.
 *
 * <p>Features act on the world model: spring pools and geyser vents are shallow excavations holding
 * water, siliceous sinter and native sulfur are thin precipitated layers, and acid-sulfate alteration
 * turns the topmost rock into clay ({@link DepositType#HYDROTHERMAL} units of this volcano). The
 * features themselves are listed ({@link #featuresByColumn()}) and announced with events.
 *
 * <p>References: Fournier (1989), Annu. Rev. Earth Planet. Sci. 17:13-53 (Yellowstone hydrothermal system); Haas (1971), Econ. Geol. 66:940-946 (boiling point with depth). See {@code docs/references.md}.
 */
public final class Geothermal implements Subsystem, HeatSources {
    private final String id;
    private final String volcanoId;
    private final GeothermalConfig config;
    private final MagmaState magma;
    private final TerrainModel terrain;
    private final HydrothermalField field;
    private final GeothermalGrid grid;
    /** Column width L (m). */
    private final double l;
    /** Ground elevation (m) assumed where the ground is unknown (the grid centre's). */
    private final double referenceZ;
    private Supplier<List<VentSite>> vents;

    private final TreeMap<Long, PlacedFeature> features = new TreeMap<>();
    private final Map<HydrothermalFeature, Integer> counts = new EnumMap<>(HydrothermalFeature.class);
    private double hazardClock;
    private boolean prewarmed;
    /** Columns lava has covered: column key → time (s) it was last covered. */
    private final TreeMap<Long, Double> lavaCover = new TreeMap<>();
    /** Time of the current step (s). */
    private double now;
    /** Last announced fumarole intensity and time (s), by column key. */
    private final TreeMap<Long, double[]> fumaroleReports = new TreeMap<>();
    /** Last announced hazard concentration and time (s), by {@code zone · species count + species}. */
    private final TreeMap<Long, double[]> hazardReports = new TreeMap<>();

    // Derived each step from the terrain and the subsurface (not persisted).
    private final boolean[] known;
    private final boolean[] submerged;
    /** Ground elevation (m) at each cell centre. */
    private final double[] ground;
    /** Mean ground height of the surrounding 5×5 cells (for gas pooling in depressions). */
    private final double[] localMean;
    /**
     * Boiling point (°C) of water at the reservoir depth (hydrostatic below the water table, atmospheric
     * at the ground where the table lies deeper) and at the ground surface (altitude-dependent).
     */
    private final double[] boilingAtReservoir;
    private final double[] boilingAtSurface;

    private final Point3 center;

    public Geothermal(
            String volcanoId,
            GeothermalConfig config,
            Point3 center,
            MagmaState magma,
            TerrainModel terrain,
            List<VentSite> vents,
            HydrothermalField field) {
        config.validate();
        this.volcanoId = Objects.requireNonNull(volcanoId, "volcanoId");
        this.id = "geothermal:" + volcanoId;
        this.config = config;
        this.magma = Objects.requireNonNull(magma, "magma");
        this.terrain = Objects.requireNonNull(terrain, "terrain");
        this.field = Objects.requireNonNull(field, "field");
        this.center = center;
        this.referenceZ = center.y();
        List<VentSite> initial = List.copyOf(vents);
        this.vents = () -> initial;
        this.l = terrain.world().spec().metersPerColumn();
        this.grid = GeothermalGrid.centeredOn(center.columnX(l), center.columnZ(l), columns(config.radiusM),
                columns(config.cellSizeM));

        int n = grid.cellCount();
        this.known = new boolean[n];
        this.submerged = new boolean[n];
        this.ground = new double[n];
        this.localMean = new double[n];
        this.boilingAtReservoir = new double[n];
        this.boilingAtSurface = new double[n];
        java.util.Arrays.fill(boilingAtReservoir, 100);
        java.util.Arrays.fill(boilingAtSurface, 100);
    }

    /** Centre of the feature grid (m). */
    public Point3 center() {
        return center;
    }

    /** A distance (m) in whole surface columns (at least one). */
    private int columns(double meters) {
        return Math.max(1, (int) Math.round(meters / l));
    }

    public String volcanoId() {
        return volcanoId;
    }

    // ── Subsystem ──

    /** Live retune; the feature grid's layout (radius, cell size) is refused. */
    @Override
    public boolean reconfigure(Object c) {
        if (!(c instanceof GeothermalConfig n) || !ConfigCopy.same(n, config, "radiusM", "cellSizeM")) return false;
        n.validate();
        ConfigCopy.into(n, config);
        return true;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public double periodSeconds() {
        return config.stepSeconds;
    }

    /** Longest step (s): feature formation and the shallow reservoir are integrated over at most this. */
    public static final double MAX_STEP_SECONDS = 86_400;

    @Override
    public double maxStepSeconds() {
        return MAX_STEP_SECONDS;
    }

    @Override
    public Object config() {
        return config;
    }

    @Override
    public void step(StepContext context) {
        parallel = context.parallel();
        now = context.time();
        buryUnderLava(context);
        double dt = context.dtSeconds();
        sampleTerrain();
        if (!prewarmed && config.prewarmSeconds > 0 && knownFraction() >= 0.5) {
            equilibrate(config.prewarmSeconds);
            prewarmed = true;
        }
        sampleField();

        double dtHours = dt / 3600.0;
        formFeatures(context, dtHours);
        depositSulfur(context, dtHours);
        emitFumaroleActivity(context);

        hazardClock += context.dtSeconds();
        if (hazardClock >= config.hazardIntervalSeconds) {
            hazardClock = 0;
            emitHazards(context);
        }
    }

    // ── Public API ──

    /** The coarse feature grid, holding the last sampled reservoir excess temperature and saturation. */
    public GeothermalGrid grid() {
        return grid;
    }

    public HydrothermalField field() {
        return field;
    }

    /** The vents whose heat pipes warm the ground (read each time: fissures open and vents form). */
    public void setVents(Supplier<List<VentSite>> vents) {
        this.vents = java.util.Objects.requireNonNull(vents);
    }

    /** Shallow-reservoir temperature (°C) at a column, as last sampled; ambient outside the grid. */
    public double temperatureAt(int x, int z) {
        return config.ambientC + grid.excessAtColumn(x, z);
    }

    /** Shallow-reservoir liquid saturation at a column, as last sampled; 0 outside the grid. */
    public double waterAt(int x, int z) {
        int index = grid.indexOfColumn(x, z);
        return index < 0 ? 0 : grid.water(index);
    }

    /**
     * Heat from a lava column of {@code thicknessM} at {@code lavaTemperatureC} resting on column
     * {@code (x, z)} for one second: conduction through the flow's lower half,
     * {@code k·(T_lava − T_ground)/(h/2)}, into the top of the ground.
     */
    public void addLavaHeat(int x, int z, double lavaTemperatureC, double thicknessM) {
        if (!(thicknessM > 0)) return;
        lavaCover.put(PlacedFeature.key(x, z), now);
        double groundC = field.temperatureC(x, z, 0);
        double flux = config.lavaConductivity * Math.max(0, lavaTemperatureC - groundC) / Math.max(0.25, thicknessM / 2);
        field.addSurfaceHeat(x, z, flux * l * l);
    }

    /** Magma activity factor driving the vent heat pipes (0 when the chamber is cold). */
    public double activity() {
        double thermal = clamp(
                (magma.temperatureC() - config.activityMinChamberC)
                        / (config.activityFullChamberC - config.activityMinChamberC),
                0,
                1.5);
        double pressure = 1 + clamp(magma.overpressureMPa() / config.overpressureFullMPa, 0, 1);
        double eruption = magma.erupting() ? 1 + Math.min(1, magma.eruptionRate() / config.eruptionRateFull) : 1;
        return thermal * pressure * eruption;
    }

    /**
     * Spins the subsurface up for {@code seconds} of physical time (no features, no randomness) when
     * the field supports it — use when a volcano is created to start from a developed hydrothermal
     * system.
     */
    public void equilibrate(double seconds) {
        if (field instanceof Subsurface subsurface) subsurface.equilibrate(seconds);
        sampleTerrain();
        sampleField();
    }

    /** Boiling point (°C) at the reservoir depth under the column, as last sampled (100 outside the grid). */
    public double boilingPointAt(int x, int z) {
        int idx = grid.indexOfColumn(x, z);
        return idx < 0 ? 100 : boilingAtReservoir[idx];
    }

    /** True when the shallow system at the column is vapour-dominated (too dry to convect). */
    public boolean vapourDominatedAt(int x, int z) {
        return waterAt(x, z) <= config.vapourDominatedWater;
    }

    private double knownFraction() {
        int n = 0;
        for (boolean k : known) if (k) n++;
        return (double) n / known.length;
    }

    public Map<Long, PlacedFeature> featuresByColumn() {
        return Collections.unmodifiableMap(features);
    }

    public List<PlacedFeature> features(HydrothermalFeature kind) {
        List<PlacedFeature> result = new ArrayList<>();
        for (PlacedFeature feature : features.values()) {
            if (feature.kind() == kind) result.add(feature);
        }
        return result;
    }

    public int count(HydrothermalFeature kind) {
        return counts.getOrDefault(kind, 0);
    }

    /**
     * Fumarole intensity in [0, 1] of cell {@code idx} at reservoir temperature {@code temperatureC}: 0 below
     * the local boiling point (no steam), rising to 1 at {@link GeothermalConfig#fumaroleFullC}.
     */
    double fumaroleIntensity(int idx, double temperatureC) {
        double boiling = idx < 0 ? 100 : boilingAtReservoir[idx];
        return clamp((temperatureC - boiling) / Math.max(1, config.fumaroleFullC - boiling), 0, 1);
    }

    /** Fumarole intensity at a column (see {@link #fumaroleIntensity(int, double)}). */
    public double fumaroleIntensityAt(int x, int z) {
        return fumaroleIntensity(grid.indexOfColumn(x, z), temperatureAt(x, z));
    }

    // ── Heat sources (HeatSources) ──

    /** The chamber's conductive halo; positions in column coordinates (m / L), as {@link HeatSources} expects. */
    @Override
    public List<Chamber> chambers() {
        double t = magma.temperatureC();
        if (!(t > 0)) return List.of();
        Point3 c = magma.chamberCenter();
        WorldModel world = terrain.world();
        int cx = c.columnX(l);
        int cz = c.columnZ(l);
        double surface = world.isKnown(cx, cz) ? world.surfaceZ(cx, cz) : referenceZ;
        double physical = magma.physicalDepthM();
        double depth = physical > 0 ? physical : surface - c.y();
        if (!(depth > 0)) return List.of();
        double centre = surface - depth;
        double volume = magma.volumeM3();
        double fromVolume = volume > 0 ? Math.cbrt(3 * volume / (4 * Math.PI)) : config.chamberRadiusM;
        double radius = Math.min(fromVolume, 0.5 * depth);
        return List.of(new Chamber(c.x() / l, c.z() / l, centre, surface, radius, t, magma.wallHeatPowerW()));
    }

    /** The vents' heat pipes; positions in column coordinates (m / L), as {@link HeatSources} expects. */
    @Override
    public List<Vent> vents() {
        double activity = activity();
        if (activity <= 0 || config.ventHeatPowerW <= 0) return List.of();
        List<Vent> list = new ArrayList<>();
        for (VentSite vent : vents.get()) {
            Point3 p = vent.position();
            double sigma = Math.max(config.cellSizeM, vent.craterRadiusM()) + config.ventHaloM;
            double extent = vent.kind() == VentKind.FISSURE ? vent.fissureLengthM() / 2 : 0;
            // Gas and fluid rising from the magma cannot heat the rock above the magma's temperature.
            list.add(new Vent(p.x() / l, p.z() / l, activity * config.ventHeatPowerW,
                    Math.sqrt(sigma * sigma + extent * extent), config.ventPipeDepthM, magma.temperatureC()));
        }
        return list;
    }

    // ── Sampling ──

    /**
     * Executor for the per-cell sampling loops (the engine's, set each step). Cells only write their
     * own slots and read the world and subsurface, which nothing edits meanwhile.
     */
    private Parallel parallel = Parallel.of(Parallel.defaultThreads());

    private void sampleTerrain() {
        int n = grid.cellCount();
        WorldModel world = terrain.world();
        parallel.forEach(n, SAMPLE_GRAIN, idx -> {
            int x = grid.cellCenterX(idx);
            int z = grid.cellCenterZ(idx);
            boolean k = world.isKnown(x, z);
            known[idx] = k;
            ground[idx] = k ? world.surfaceZ(x, z) : referenceZ;
            submerged[idx] = k && submergedColumn(x, z);
        });
        int sx = grid.sizeX();
        int sz = grid.sizeZ();
        parallel.forEach(n, SAMPLE_GRAIN, idx -> {
            int ci = grid.cellI(idx);
            int cj = grid.cellJ(idx);
            double sum = 0;
            int count = 0;
            for (int j = Math.max(0, cj - 2); j <= Math.min(sz - 1, cj + 2); j++) {
                for (int i = Math.max(0, ci - 2); i <= Math.min(sx - 1, ci + 2); i++) {
                    int other = grid.index(i, j);
                    if (known[other]) {
                        sum += ground[other];
                        count++;
                    }
                }
            }
            localMean[idx] = count == 0 ? ground[idx] : sum / count;
        });
    }

    private static final int SAMPLE_GRAIN = 64;

    /** Reads the reservoir temperature and liquid saturation of every cell from the subsurface. */
    private void sampleField() {
        double r = config.reservoirDepthM;
        parallel.forEach(grid.cellCount(), SAMPLE_GRAIN, idx -> {
            int x = grid.cellCenterX(idx);
            int z = grid.cellCenterZ(idx);
            if (!known[idx] || !field.known(x, z)) {
                grid.setExcess(idx, 0);
                grid.setWater(idx, 0);
                return;
            }
            double temperature = field.temperatureC(x, z, r);
            grid.setExcess(idx, Math.max(0, temperature - config.ambientC));
            double depth = field.waterTableDepthM(x, z);
            // the boiling point at the reservoir: hydrostatic below the water table (or the sea surface),
            // the atmosphere's at the ground where the table lies deeper (Haas 1971, IAPWS-IF97)
            double groundZ = ground[idx];
            double sea = terrain.world().waterZ(x, z);
            if (submerged[idx]) {
                double tableZ = Double.isFinite(sea) && sea > groundZ ? sea : groundZ + field.surfaceWaterDepthM(x, z);
                boilingAtReservoir[idx] = WaterSaturation.boilingPointC(tableZ - groundZ + r, tableZ);
            } else {
                double below = r - depth; // reservoir depth below the water table
                boilingAtReservoir[idx] = below > 0
                        ? WaterSaturation.boilingPointC(below, groundZ - depth)
                        : WaterSaturation.boilingPointC(0, groundZ);
            }
            boilingAtSurface[idx] = WaterSaturation.boilingPointC(0, groundZ);
            double liquid = clamp(0.5 + (r - depth) / (2 * r), 0, 1);
            grid.setWater(idx, submerged[idx] ? 1 : liquid);
        });
    }

    // ── Manifestations ──

    private void formFeatures(StepContext context, double dtHours) {
        SimRandom random = context.random();
        for (int idx = 0; idx < grid.cellCount(); idx++) {
            if (!known[idx]) continue;
            double temperature = config.ambientC + grid.excess(idx);
            if (temperature < minFeatureTemperature(idx)) continue;
            double boiling = boilingAtReservoir[idx];
            double surfaceBoiling = boilingAtSurface[idx];
            double w = grid.water(idx);

            if (submerged[idx]) {
                if (temperature >= config.submarineVentMinC) {
                    double strength = clamp((temperature - config.submarineVentMinC) / 100, 0.1, 1);
                    attempts(context, idx, config.submarineVentFormationPerHour * strength * dtHours,
                            (x, z) -> trySubmarineVent(context, x, z));
                }
                continue;
            }

            // steam reaches the surface where the reservoir water boils (Fournier 1989)
            if (temperature >= boiling) {
                double intensity = Math.max(0.1, fumaroleIntensity(idx, temperature));
                attempts(context, idx, config.fumaroleFormationPerHour * intensity * dtHours,
                        (x, z) -> tryFumarole(context, x, z));
            }
            // a geyser's water is liquid at depth but above the surface boiling point, so it flashes as it
            // rises (Hurwitz & Manga 2017)
            if (inBand(temperature, surfaceBoiling, Math.max(surfaceBoiling, boiling)) && w >= config.geyserMinWater) {
                double strength = 0.25 + 0.75 * (w - config.geyserMinWater) / Math.max(1e-9, 1 - config.geyserMinWater);
                attempts(context, idx, config.geyserFormationPerHour * strength * dtHours,
                        (x, z) -> tryGeyser(context, x, z));
            }
            if (inBand(temperature, config.hotSpringMinC, surfaceBoiling) && w >= config.hotSpringMinWater) {
                attempts(context, idx, config.hotSpringFormationPerHour * dtHours,
                        (x, z) -> trySpring(context, x, z, temperature >= config.sulfurSpringMinC));
            }
            if (inBand(temperature, config.mudPotMinC, boiling)
                    && w >= config.mudPotMinWater
                    && w <= config.mudPotMaxWater) {
                attempts(context, idx, config.mudPotFormationPerHour * dtHours, (x, z) -> tryMudPot(context, x, z));
            }
            // acid-sulfate alteration needs steam carrying H₂S: boiling at the reservoir
            if (temperature >= boiling && count(HydrothermalFeature.ACID_ALTERATION) < config.maxAltered) {
                double strength = clamp((temperature - boiling) / 100, 0.1, 1);
                boolean unsaturated = w < config.acidMaxWater;
                attempts(context, idx, config.acidAlterationPerHour * strength * dtHours,
                        (x, z) -> tryAcidAlteration(context, x, z, unsaturated, random));
            }
            if (inBand(temperature, config.sinterMinC, config.sinterMaxC) && w >= config.sinterMinWater
                    && count(HydrothermalFeature.SINTER) < config.maxSinter) {
                attempts(context, idx, config.sinterPerHour * dtHours, (x, z) -> trySinter(context, x, z, random));
            }
            if (inBand(temperature, config.cinnabarMinC, config.cinnabarMaxC) && w >= config.cinnabarMinWater
                    && count(HydrothermalFeature.CINNABAR) < config.maxCinnabar) {
                attempts(context, idx, config.cinnabarPerHour * dtHours, (x, z) -> tryCinnabar(context, x, z));
            }
        }
    }

    private interface ColumnAction {
        boolean apply(int x, int z);
    }

    /** Draws a Poisson number (≤ 2) of attempts, each at a random column of the cell. */
    private void attempts(StepContext context, int cell, double mean, ColumnAction action) {
        if (mean <= 0) return;
        SimRandom random = context.random();
        int n = Math.min(2, random.nextPoisson(mean));
        for (int k = 0; k < n; k++) {
            int x = grid.cellMinX(cell) + random.nextInt(grid.cellSize());
            int z = grid.cellMinZ(cell) + random.nextInt(grid.cellSize());
            action.apply(x, z);
        }
    }

    /** Depth (m) of a hot-spring pool. */
    static final double POOL_DEPTH_M = 1.0;
    /** Thickness (m) of a sinter apron laid around a spring or geyser, or by spreading sinter. */
    static final double SINTER_M = 0.05;
    /** Thickness (m) of one sulfur build-up stage (sublimate crust, mound). */
    static final double SULFUR_STAGE_M = 0.02;
    /** Depth (m) of rock acid-sulfate alteration turns into clay. */
    static final double ALTERATION_DEPTH_M = 0.5;
    /** Thickness (m) of the clay-rich mud of a mud pot. */
    static final double MUD_M = 0.3;
    /** A pool's neighbours may stand at most this much (m) below or above its centre to share its water. */
    static final double POOL_LEVEL_TOLERANCE_M = 0.25;

    private boolean tryFumarole(StepContext context, int x, int z) {
        if (!buildable(x, z)) return false;
        if (count(HydrothermalFeature.FUMAROLE) >= config.maxFumaroles) return false;
        if (hasNearby(x, z, config.fumaroleSpacingM, HydrothermalFeature.FUMAROLE)) return false;
        register(new PlacedFeature(x, z, surface(x, z), HydrothermalFeature.FUMAROLE, 0));
        formed(context, HydrothermalFeature.FUMAROLE, x, z);
        return true;
    }

    private boolean tryGeyser(StepContext context, int x, int z) {
        if (!buildable(x, z)) return false;
        if (count(HydrothermalFeature.GEYSER) >= config.maxGeysers) return false;
        if (hasNearby(x, z, config.geyserSpacingM, HydrothermalFeature.GEYSER)) return false;
        double level = surface(x, z);
        if (!contained(x, z, level, Set.of(PlacedFeature.key(x, z)))) return false;

        // the vent pit: 1–4 m deep, flooded to the old surface
        int depth = 1 + context.random().nextInt(4);
        excavate(x, z, depth, level);
        register(new PlacedFeature(x, z, level, HydrothermalFeature.GEYSER, depth));
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dz != 0) && context.random().chance(0.5)) {
                    precipitate(context, x + dx, z + dz, HydrothermalFeature.SINTER, MaterialTable.SINTER, SINTER_M, false);
                }
            }
        }
        formed(context, HydrothermalFeature.GEYSER, x, z);
        return true;
    }

    private boolean trySpring(StepContext context, int x, int z, boolean sulfurSpring) {
        HydrothermalFeature kind = sulfurSpring ? HydrothermalFeature.SULFUR_SPRING : HydrothermalFeature.HOT_SPRING;
        if (!buildable(x, z)) return false;
        if (count(HydrothermalFeature.HOT_SPRING) + count(HydrothermalFeature.SULFUR_SPRING) >= config.maxHotSprings) {
            return false;
        }
        if (hasNearby(x, z, config.hotSpringSpacingM, HydrothermalFeature.HOT_SPRING, HydrothermalFeature.SULFUR_SPRING)) {
            return false;
        }
        double level = surface(x, z);

        List<int[]> pool = new ArrayList<>();
        Set<Long> poolKeys = new LinkedHashSet<>();
        pool.add(new int[] {x, z});
        poolKeys.add(PlacedFeature.key(x, z));
        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];
            if (buildable(nx, nz) && Math.abs(surface(nx, nz) - level) <= POOL_LEVEL_TOLERANCE_M
                    && context.random().chance(0.5)) {
                pool.add(new int[] {nx, nz});
                poolKeys.add(PlacedFeature.key(nx, nz));
            }
        }
        for (int[] p : pool) {
            if (!contained(p[0], p[1], level, poolKeys)) return false;
        }

        int unit = hydrothermalUnit(context);
        for (int[] p : pool) {
            excavate(p[0], p[1], POOL_DEPTH_M, level);
            // sulfur springs line their floor with native sulfur
            if (sulfurSpring) terrain.world().deposit(p[0], p[1], SULFUR_STAGE_M, MaterialTable.SULFUR, unit);
            register(new PlacedFeature(p[0], p[1], level, kind, 0));
        }

        Set<Long> rim = new LinkedHashSet<>();
        for (int[] p : pool) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    long key = PlacedFeature.key(p[0] + dx, p[1] + dz);
                    if (!poolKeys.contains(key)) rim.add(key);
                }
            }
        }
        for (long key : rim) {
            if (!context.random().chance(0.6)) continue;
            int rx = (int) (key >> 32);
            int rz = (int) key;
            if (!terrain.world().isKnown(rx, rz)) continue;
            double s = surface(rx, rz);
            if (s < level || s > level + 1) continue;
            if (sulfurSpring) {
                precipitate(context, rx, rz, HydrothermalFeature.SULFUR_DEPOSIT, MaterialTable.SULFUR, SULFUR_STAGE_M, false);
            } else {
                precipitate(context, rx, rz, HydrothermalFeature.SINTER, MaterialTable.SINTER, SINTER_M, false);
            }
        }

        formed(context, kind, x, z);
        return true;
    }

    private boolean tryMudPot(StepContext context, int x, int z) {
        if (!buildable(x, z)) return false;
        if (count(HydrothermalFeature.MUD_POT) >= config.maxMudPots) return false;
        if (hasNearby(x, z, config.mudPotSpacingM, HydrothermalFeature.MUD_POT)) return false;
        if (!alter(context, x, z, HydrothermalFeature.MUD_POT, MUD_M, false)) return false;
        for (int[] d : CARDINALS) {
            if (context.random().chance(0.5)) alter(context, x + d[0], z + d[1], HydrothermalFeature.MUD_POT, MUD_M, false);
        }
        formed(context, HydrothermalFeature.MUD_POT, x, z);
        return true;
    }

    private boolean trySubmarineVent(StepContext context, int x, int z) {
        if (features.containsKey(PlacedFeature.key(x, z))) return false;
        if (!terrain.world().isKnown(x, z) || !submergedColumn(x, z) || !solidTop(x, z)) return false;
        if (count(HydrothermalFeature.SUBMARINE_VENT) >= config.maxSubmarineVents) return false;
        if (hasNearby(x, z, config.submarineVentSpacingM, HydrothermalFeature.SUBMARINE_VENT)) return false;
        register(new PlacedFeature(x, z, surface(x, z), HydrothermalFeature.SUBMARINE_VENT, 0));
        for (int[] d : CARDINALS) {
            if (context.random().chance(0.3)) {
                precipitate(context, x + d[0], z + d[1], HydrothermalFeature.SULFUR_DEPOSIT, MaterialTable.SULFUR,
                        SULFUR_STAGE_M, true);
            }
        }
        formed(context, HydrothermalFeature.SUBMARINE_VENT, x, z);
        return true;
    }

    /**
     * Acid-sulfate alteration grows outward from fumaroles and altered ground, where condensing steam
     * acidifies the soil whatever the deeper saturation. An isolated patch nucleates only on
     * unsaturated (two-phase / vapour-dominated) ground, with probability
     * {@code alterationNucleationFactor}. The top {@link #ALTERATION_DEPTH_M} of rock becomes clay.
     */
    private boolean tryAcidAlteration(StepContext context, int x, int z, boolean unsaturated, SimRandom random) {
        if (count(HydrothermalFeature.ACID_ALTERATION) >= config.maxAltered) return false;
        boolean adjacent = hasNearby(x, z, config.alterationGrowthRadiusM,
                HydrothermalFeature.FUMAROLE, HydrothermalFeature.ACID_ALTERATION, HydrothermalFeature.SULFUR_DEPOSIT);
        if (!adjacent && !(unsaturated && random.chance(config.alterationNucleationFactor))) return false;
        if (!buildable(x, z)) return false;
        if (!alter(context, x, z, HydrothermalFeature.ACID_ALTERATION, ALTERATION_DEPTH_M, false)) return false;
        formed(context, HydrothermalFeature.ACID_ALTERATION, x, z);
        return true;
    }

    /** Sinter spreads from springs, geysers and existing sinter; isolated seeps are rare. */
    private boolean trySinter(StepContext context, int x, int z, SimRandom random) {
        if (count(HydrothermalFeature.SINTER) >= config.maxSinter) return false;
        boolean adjacent = hasNearby(x, z, config.sinterGrowthRadiusM, HydrothermalFeature.HOT_SPRING,
                HydrothermalFeature.SULFUR_SPRING, HydrothermalFeature.GEYSER, HydrothermalFeature.SINTER);
        if (!adjacent && !random.chance(config.sinterNucleationFactor)) return false;
        if (!buildable(x, z)) return false;
        if (!precipitate(context, x, z, HydrothermalFeature.SINTER, MaterialTable.SINTER, SINTER_M, false)) return false;
        formed(context, HydrothermalFeature.SINTER, x, z);
        return true;
    }

    /**
     * Cinnabar (HgS) precipitates where cooling spring fluids reach the surface: next to springs, geysers or
     * sinter. Its volume is negligible; it is recorded as a feature only.
     */
    private boolean tryCinnabar(StepContext context, int x, int z) {
        if (count(HydrothermalFeature.CINNABAR) >= config.maxCinnabar) return false;
        if (config.cinnabarSpringRadiusM > 0 && !hasNearby(x, z, config.cinnabarSpringRadiusM,
                HydrothermalFeature.HOT_SPRING, HydrothermalFeature.SULFUR_SPRING, HydrothermalFeature.GEYSER,
                HydrothermalFeature.SINTER)) {
            return false;
        }
        if (!buildable(x, z)) return false;
        register(new PlacedFeature(x, z, surface(x, z), HydrothermalFeature.CINNABAR, 0));
        formed(context, HydrothermalFeature.CINNABAR, x, z);
        return true;
    }

    /**
     * Lays {@code thicknessM} of a hydrothermal precipitate on an unoccupied column with a solid top and
     * registers the feature; {@code underwater} selects submerged columns instead of dry ones.
     */
    private boolean precipitate(StepContext context, int x, int z, HydrothermalFeature kind, Material material,
            double thicknessM, boolean underwater) {
        if (!vacantSurface(x, z, underwater)) return false;
        terrain.world().deposit(x, z, thicknessM, material, hydrothermalUnit(context));
        register(new PlacedFeature(x, z, surface(x, z), kind, 0));
        return true;
    }

    /** Turns the top {@code depthM} of an unoccupied column's ground into clay and registers the feature. */
    private boolean alter(StepContext context, int x, int z, HydrothermalFeature kind, double depthM, boolean underwater) {
        if (!vacantSurface(x, z, underwater)) return false;
        double s = surface(x, z);
        terrain.world().fill(x, z, s - depthM, s, MaterialTable.CLAY, hydrothermalUnit(context));
        register(new PlacedFeature(x, z, s, kind, 0));
        return true;
    }

    /** Unoccupied, known column with a solid top, dry (or submerged when {@code underwater}). */
    private boolean vacantSurface(int x, int z, boolean underwater) {
        if (features.containsKey(PlacedFeature.key(x, z))) return false;
        if (!terrain.world().isKnown(x, z) || submergedColumn(x, z) != underwater) return false;
        return solidTop(x, z);
    }

    /**
     * Lowers a column by {@code depthM} and fills the hollow with water up to {@code waterLevel} (a spring
     * pool or a geyser vent).
     */
    private void excavate(int x, int z, double depthM, double waterLevel) {
        WorldModel world = terrain.world();
        world.erode(x, z, depthM, false);
        double existing = world.waterZ(x, z);
        world.setWaterZ(x, z, Double.isFinite(existing) ? Math.max(existing, waterLevel) : waterLevel);
    }

    private int hydrothermalUnit(StepContext context) {
        return Provenance.unitFor(terrain.world(), volcanoId, -1, DepositType.HYDROTHERMAL, context.time(), Double.NaN,
                Double.NaN);
    }

    // ── Sulfur deposition around fumaroles ──

    private void depositSulfur(StepContext context, double dtHours) {
        SimRandom random = context.random();
        int r = columns(config.sulfurDepositRadiusM);
        for (PlacedFeature fumarole : features(HydrothermalFeature.FUMAROLE)) {
            double intensity = fumaroleIntensityAt(fumarole.x(), fumarole.z());
            if (intensity <= 0) continue;
            int n = Math.min(3, random.nextPoisson(config.sulfurDepositPerHour * intensity * dtHours));
            for (int k = 0; k < n; k++) {
                int dx = random.nextInt(-r, r + 1);
                int dz = random.nextInt(-r, r + 1);
                if (dx == 0 && dz == 0) {
                    coatFumarole(context, fumarole);
                } else {
                    depositAt(context, fumarole.x() + dx, fumarole.z() + dz);
                }
            }
        }
    }

    /** The fumarole's opening gets a crust of sublimated sulfur. */
    private void coatFumarole(StepContext context, PlacedFeature fumarole) {
        if (fumarole.level() > 0) return;
        int x = fumarole.x();
        int z = fumarole.z();
        if (!terrain.world().isKnown(x, z) || submergedColumn(x, z) || !solidTop(x, z)) return;
        terrain.world().deposit(x, z, SULFUR_STAGE_M, MaterialTable.SULFUR, hydrothermalUnit(context));
        features.put(PlacedFeature.key(x, z), fumarole.withLevel(1));
    }

    private void depositAt(StepContext context, int x, int z) {
        PlacedFeature existing = features.get(PlacedFeature.key(x, z));
        if (existing == null) {
            if (precipitate(context, x, z, HydrothermalFeature.SULFUR_DEPOSIT, MaterialTable.SULFUR, SULFUR_STAGE_M, false)) {
                formed(context, HydrothermalFeature.SULFUR_DEPOSIT, x, z);
            }
            return;
        }
        if (existing.kind() != HydrothermalFeature.SULFUR_DEPOSIT || existing.level() >= config.maxSulfurStages) return;
        // the deposit builds up: one more stage of sulfur
        terrain.world().deposit(x, z, SULFUR_STAGE_M, MaterialTable.SULFUR, hydrothermalUnit(context));
        features.put(PlacedFeature.key(x, z), existing.withLevel(existing.level() + 1));
    }

    // ── Events ──

    /**
     * Announces fumarole activity when it starts, when its intensity changes by
     * {@code fumaroleReportDelta}, at least every {@code fumaroleRefreshSeconds}, and once with
     * intensity 0 when it dies down. Hosts keep rendering the last announced state.
     */
    private void emitFumaroleActivity(StepContext context) {
        for (PlacedFeature fumarole : features(HydrothermalFeature.FUMAROLE)) {
            long key = PlacedFeature.key(fumarole.x(), fumarole.z());
            double temperature = temperatureAt(fumarole.x(), fumarole.z());
            double intensity = fumaroleIntensity(grid.indexOfColumn(fumarole.x(), fumarole.z()), temperature);
            double[] last = fumaroleReports.get(key);
            boolean report;
            if (intensity <= 0) {
                report = last != null && last[0] > 0;
            } else {
                report = last == null
                        || Math.abs(intensity - last[0]) >= config.fumaroleReportDelta
                        || context.time() - last[1] >= config.fumaroleRefreshSeconds;
            }
            if (!report) continue;
            fumaroleReports.put(key, new double[] {intensity, context.time()});
            context.outbox().emit(new FumaroleActivity(
                    context.time(),
                    Point3.columnCentre(fumarole.x(), fumarole.z(), fumarole.elevation(), l),
                    intensity,
                    GasComposition.atTemperature(temperature)));
        }
    }

    /**
     * Gas hazards aggregated over square zones of {@code hazardZoneCells} cells: per zone and species
     * the peak concentration, centred on the zone and covering it. A zone is announced
     * when it appears, when its concentration changes by {@code hazardChangeFraction}, at least every
     * {@code hazardRefreshSeconds}, and once with concentration 0 when it clears.
     */
    private void emitHazards(StepContext context) {
        int zoneCells = config.hazardZoneCells;
        int zonesX = (grid.sizeX() + zoneCells - 1) / zoneCells;
        int zonesZ = (grid.sizeZ() + zoneCells - 1) / zoneCells;
        GasSpecies[] speciesList = GasSpecies.values();
        double radius = zoneCells * grid.cellSize() * l * Math.sqrt(0.5);
        double validFor = config.hazardRefreshSeconds + config.hazardIntervalSeconds;

        double[] peak = new double[speciesList.length];
        for (int zj = 0; zj < zonesZ; zj++) {
            for (int zi = 0; zi < zonesX; zi++) {
                Arrays.fill(peak, 0);
                for (int j = zj * zoneCells; j < Math.min(grid.sizeZ(), (zj + 1) * zoneCells); j++) {
                    for (int i = zi * zoneCells; i < Math.min(grid.sizeX(), (zi + 1) * zoneCells); i++) {
                        int idx = grid.index(i, j);
                        if (!known[idx]) continue;
                        double temperature = config.ambientC + grid.excess(idx);
                        double intensity = fumaroleIntensity(idx, temperature);
                        if (intensity <= 0) continue;
                        GasComposition gas = GasComposition.atTemperature(temperature);
                        for (int s = 0; s < speciesList.length; s++) {
                            double ppm = gas.fraction(speciesList[s]) * intensity * config.gasFluxPpm;
                            if (speciesList[s] == GasSpecies.CO2) {
                                // CO₂ is denser than air and pools in depressions.
                                ppm *= 1 + clamp((localMean[idx] - ground[idx]) / CO2_POOLING_DEPTH_M, 0, 2);
                            }
                            peak[s] = Math.max(peak[s], ppm);
                        }
                    }
                }

                int zone = zj * zonesX + zi;
                for (int s = 0; s < speciesList.length; s++) {
                    long key = (long) zone * speciesList.length + s;
                    double[] last = hazardReports.get(key);
                    double ppm = peak[s] >= config.minHazardPpm ? peak[s] : 0;
                    if (ppm == 0) {
                        if (last == null) continue;
                        hazardReports.remove(key);
                        context.outbox().emit(new GasHazard(context.time(), zoneCenter(zi, zj, zoneCells), radius,
                                speciesList[s], 0, 0));
                        continue;
                    }
                    boolean report = last == null
                            || Math.abs(ppm - last[0]) >= config.hazardChangeFraction * last[0]
                            || context.time() - last[1] >= config.hazardRefreshSeconds;
                    if (!report) continue;
                    hazardReports.put(key, new double[] {ppm, context.time()});
                    context.outbox().emit(new GasHazard(context.time(), zoneCenter(zi, zj, zoneCells), radius,
                            speciesList[s], ppm, validFor));
                }
            }
        }
    }

    /** A depression this much (m) below its surroundings doubles the CO₂ concentration pooling in it. */
    static final double CO2_POOLING_DEPTH_M = 5;

    /**
     * Hazard centre: the middle of the zone, on its ground (m). It is the same for every event of a zone,
     * so hosts can key hazards by centre and species.
     */
    private Point3 zoneCenter(int zi, int zj, int zoneCells) {
        int i = Math.min(grid.sizeX() - 1, zi * zoneCells + zoneCells / 2);
        int j = Math.min(grid.sizeZ() - 1, zj * zoneCells + zoneCells / 2);
        int idx = grid.index(i, j);
        double y = known[idx] ? ground[idx] : referenceZ;
        return Point3.columnCentre(grid.cellMinX(idx), grid.cellMinZ(idx), y, l);
    }

    private void formed(StepContext context, HydrothermalFeature kind, int x, int z) {
        context.outbox().emit(new HydrothermalFeatureFormed(context.time(), kind,
                Point3.columnCentre(x, z, surface(x, z), l)));
    }

    // ── Helpers ──

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Known, dry, unoccupied column with a solid top that lava has not covered recently. */
    private boolean buildable(int x, int z) {
        if (features.containsKey(PlacedFeature.key(x, z))) return false;
        if (lavaCovered(x, z)) return false;
        if (!terrain.world().isKnown(x, z) || submergedColumn(x, z)) return false;
        return solidTop(x, z);
    }

    /** Ground elevation (m) of a known column. */
    private double surface(int x, int z) {
        return terrain.world().surfaceZ(x, z);
    }

    /** Standing water over the column (the world's, or the subsurface model's surface water). */
    private boolean submergedColumn(int x, int z) {
        WorldModel world = terrain.world();
        double w = world.waterZ(x, z);
        return (Double.isFinite(w) && w > world.surfaceZ(x, z)) || field.surfaceWaterDepthM(x, z) >= config.submergedDepthM;
    }

    /** The top layer is rock, tephra or soil (not a cavity, water or ice). */
    private boolean solidTop(int x, int z) {
        WorldModel world = terrain.world();
        int n = world.layerCount(x, z);
        if (n == 0) return false;
        MaterialClass cls = world.layer(x, z, n - 1).materialInfo().materialClass();
        return cls == MaterialClass.ROCK || cls == MaterialClass.TEPHRA || cls == MaterialClass.SOIL;
    }

    /** True if water standing at {@code level} (m) in column (x, z) is walled in by its neighbours. */
    private boolean contained(int x, int z, double level, Set<Long> sameBody) {
        WorldModel world = terrain.world();
        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];
            if (sameBody.contains(PlacedFeature.key(nx, nz))) continue;
            if (!world.isKnown(nx, nz) || world.surfaceZ(nx, nz) < level || submergedColumn(nx, nz)) return false;
        }
        return true;
    }

    private boolean hasNearby(int x, int z, double spacingM, HydrothermalFeature... kinds) {
        int spacing = columns(spacingM);
        for (int dz = -spacing; dz <= spacing; dz++) {
            for (int dx = -spacing; dx <= spacing; dx++) {
                PlacedFeature feature = features.get(PlacedFeature.key(x + dx, z + dz));
                if (feature == null) continue;
                for (HydrothermalFeature kind : kinds) {
                    if (feature.kind() == kind) return true;
                }
            }
        }
        return false;
    }

    /**
     * True while lava covers column (x, z) or covered it within {@link GeothermalConfig#lavaExclusionSeconds}
     * (s): no surface feature belongs on a fresh flow.
     */
    boolean lavaCovered(int x, int z) {
        Double at = lavaCover.get(PlacedFeature.key(x, z));
        return at != null && now - at <= config.lavaExclusionSeconds;
    }

    /**
     * Features on columns lava reached since the last step are buried: removed (their entity goes) with
     * a {@link HydrothermalFeatureBuried} event. Cover older than the exclusion window is forgotten.
     */
    private void buryUnderLava(StepContext context) {
        if (lavaCover.isEmpty()) return;
        List<Long> stale = new ArrayList<>();
        for (Map.Entry<Long, Double> e : lavaCover.entrySet()) {
            if (now - e.getValue() > config.lavaExclusionSeconds) {
                stale.add(e.getKey());
                continue;
            }
            PlacedFeature f = features.remove(e.getKey());
            if (f == null) continue;
            counts.merge(f.kind(), -1, Integer::sum);
            context.outbox().emit(new HydrothermalFeatureBuried(context.time(), f.kind(),
                    Point3.columnCentre(f.x(), f.z(), f.elevation(), l)));
        }
        for (long k : stale) lavaCover.remove(k);
    }

    private void register(PlacedFeature feature) {
        features.put(PlacedFeature.key(feature.x(), feature.z()), feature);
        counts.merge(feature.kind(), 1, Integer::sum);
    }

    private double minFeatureTemperature(int idx) {
        return Math.min(Math.min(Math.min(boilingAtSurface[idx], boilingAtReservoir[idx]), Math.min(config.hotSpringMinC, config.mudPotMinC)),
                Math.min(Math.min(config.sinterMinC, config.cinnabarMinC), config.submarineVentMinC));
    }

    private static boolean inBand(double value, double min, double max) {
        return value >= min && value <= max;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ── Persistence ──

    @Override
    public void saveState(StateWriter writer) {
        JsonObject out = writer.json();
        out.addProperty("hazardClock", hazardClock);
        JsonArray list = new JsonArray();
        for (PlacedFeature feature : features.values()) {
            JsonArray entry = new JsonArray();
            entry.add(feature.x());
            entry.add(feature.z());
            entry.add(feature.elevation());
            entry.add(feature.kind().name());
            entry.add(feature.level());
            list.add(entry);
        }
        out.add("features", list);
        out.addProperty("prewarmed", prewarmed);
        JsonArray cover = new JsonArray();
        for (Map.Entry<Long, Double> e : lavaCover.entrySet()) {
            JsonArray entry = new JsonArray();
            entry.add(e.getKey());
            entry.add(Double.doubleToRawLongBits(e.getValue()));
            cover.add(entry);
        }
        out.add("lavaCover", cover);
        out.addProperty("now", Double.doubleToRawLongBits(now));
        out.add("fumaroleReports", saveReports(fumaroleReports));
        out.add("hazardReports", saveReports(hazardReports));
    }

    private static JsonArray saveReports(TreeMap<Long, double[]> reports) {
        JsonArray list = new JsonArray();
        for (Map.Entry<Long, double[]> e : reports.entrySet()) {
            JsonArray entry = new JsonArray();
            entry.add(e.getKey());
            entry.add(Double.doubleToRawLongBits(e.getValue()[0]));
            entry.add(Double.doubleToRawLongBits(e.getValue()[1]));
            list.add(entry);
        }
        return list;
    }

    private static void loadReports(JsonObject in, String name, TreeMap<Long, double[]> target) {
        target.clear();
        for (JsonElement element : in.getAsJsonArray(name)) {
            JsonArray entry = element.getAsJsonArray();
            target.put(entry.get(0).getAsLong(), new double[] {
                Double.longBitsToDouble(entry.get(1).getAsLong()), Double.longBitsToDouble(entry.get(2).getAsLong())});
        }
    }

    @Override
    public void loadState(StateReader reader) {
        JsonObject in = reader.json();
        hazardClock = in.get("hazardClock").getAsDouble();
        features.clear();
        counts.clear();
        for (JsonElement element : in.getAsJsonArray("features")) {
            JsonArray entry = element.getAsJsonArray();
            register(new PlacedFeature(
                    entry.get(0).getAsInt(),
                    entry.get(1).getAsInt(),
                    entry.get(2).getAsDouble(),
                    HydrothermalFeature.valueOf(entry.get(3).getAsString()),
                    entry.get(4).getAsInt()));
        }
        prewarmed = in.get("prewarmed").getAsBoolean();
        lavaCover.clear();
        for (JsonElement element : in.getAsJsonArray("lavaCover")) {
            JsonArray entry = element.getAsJsonArray();
            lavaCover.put(entry.get(0).getAsLong(), Double.longBitsToDouble(entry.get(1).getAsLong()));
        }
        now = Double.longBitsToDouble(in.get("now").getAsLong());
        loadReports(in, "fumaroleReports", fumaroleReports);
        loadReports(in, "hazardReports", hazardReports);
    }


}
