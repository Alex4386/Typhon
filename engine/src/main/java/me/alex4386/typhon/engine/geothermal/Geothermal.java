package me.alex4386.typhon.engine.geothermal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.random.SimRandom;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.MagmaState;
import me.alex4386.typhon.engine.volcano.VentKind;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.BlockState;

/**
 * Geothermal and hydrothermal activity around one volcano.
 *
 * <h2>Model</h2>
 *
 * A coarse grid ({@link GeothermalGrid}) holds the excess temperature {@code T} of the shallow
 * subsurface and its groundwater saturation {@code w}. Each step of {@code dt = stepSeconds ×
 * timeScale} simulated seconds:
 *
 * <ul>
 *   <li><b>Heat</b>: {@code ∂T/∂t = κ∇²T + a·(S_vent + S_chamber) − λT}, where the magma activity
 *       {@code a} grows with chamber temperature, overpressure and eruption rate; {@code S_vent} is a
 *       Gaussian around each vent (distance to the segment for fissures) and {@code S_chamber =
 *       H·d³/(r² + d²)^{3/2}} is the surface footprint of a buried point source at depth {@code d}.
 *       Lava on the surface adds heat via {@link #addLavaHeat}.
 *   <li><b>Water</b>: {@code w} relaxes towards a terrain-derived target (submerged ⇒ 1, otherwise
 *       {@code base + (localMeanGround − ground)·k + lakeBonus·e^(−d_lake/L)}) and boils off above
 *       100 °C.
 *   <li><b>Boiling</b>: a cell represents a shallow reservoir at {@code reservoirDepthM}; its boiling
 *       point follows the boiling-point-with-depth curve {@code T_bp = 100 + 3·z^0.7} (fit to Haas
 *       1971: ≈115 °C at 10 m, ≈150 °C at 50 m, ≈200 °C at 150 m). Liquid-dominated cells
 *       ({@code w} above {@code vapourDominatedWater}) cannot heat beyond it: excess heat leaves as
 *       boiling outflow, {@code T → T_bp} at a rate ∝ {@code (w − w_v)/(1 − w_v)}. Well-watered
 *       basins therefore stay near boiling and wet (springs, geysers), while poorly supplied ground
 *       heats up, boils dry and becomes vapour-dominated (fumaroles, acid alteration) — the
 *       liquid-/vapour-dominated dichotomy of real hydrothermal systems.
 *   <li><b>Manifestations</b>: per cell and feature, a Poisson number of formation attempts with mean
 *       {@code rate × strength × dt/3600} is drawn; each attempt picks a random column in the cell
 *       and builds the feature if local conditions allow (surface type, spacing, caps, containment).
 * </ul>
 *
 * <p>All surface edits are compare-and-set against the surface id from the {@link TerrainModel} and
 * are mirrored back into it. Blocks below the surface (geyser pipes, spring floors) are unknown to
 * the terrain model and are written unconditionally.
 */
public final class Geothermal implements Subsystem {
    private final String id;
    private final GeothermalConfig config;
    private final MagmaState magma;
    private final TerrainModel terrain;
    private final BlockPalette palette;
    private final GeothermalGrid grid;
    private final int referenceY;
    private List<VentSite> vents;

    private final TreeMap<Long, PlacedFeature> features = new TreeMap<>();
    private final Map<HydrothermalFeature, Integer> counts = new EnumMap<>(HydrothermalFeature.class);
    private double hazardClock;
    private boolean prewarmed;
    /** Last announced fumarole intensity and tick, by column key. */
    private final TreeMap<Long, double[]> fumaroleReports = new TreeMap<>();
    /** Last announced hazard concentration and tick, by {@code zone · species count + species}. */
    private final TreeMap<Long, double[]> hazardReports = new TreeMap<>();

    // Derived each step from the terrain (not persisted).
    private final boolean[] known;
    private final boolean[] submerged;
    private final int[] ground;
    private final double[] localMean;
    private final double[] lakeDistance;
    private final double[] source;

    public Geothermal(
            String volcanoId,
            GeothermalConfig config,
            BlockPos center,
            MagmaState magma,
            TerrainModel terrain,
            BlockPalette palette,
            List<VentSite> vents) {
        config.validate();
        this.id = "geothermal:" + Objects.requireNonNull(volcanoId, "volcanoId");
        this.config = config;
        this.magma = Objects.requireNonNull(magma, "magma");
        this.terrain = Objects.requireNonNull(terrain, "terrain");
        this.palette = Objects.requireNonNull(palette, "palette");
        this.referenceY = center.y();
        this.vents = List.copyOf(vents);
        this.grid = GeothermalGrid.centeredOn(center.x(), center.z(), config.radius, config.cellSize);
        this.grid.fillWater(config.baseSaturation);

        int n = grid.cellCount();
        this.known = new boolean[n];
        this.submerged = new boolean[n];
        this.ground = new int[n];
        this.localMean = new double[n];
        this.lakeDistance = new double[n];
        this.source = new double[n];
    }

    // ── Subsystem ──

    @Override
    public String id() {
        return id;
    }

    @Override
    public int interval() {
        return config.intervalTicks();
    }

    @Override
    public void step(StepContext context) {
        double dt = context.dtSeconds() * config.timeScale;
        sampleTerrain();
        if (!prewarmed && config.prewarmSeconds > 0 && knownFraction() >= 0.5) {
            equilibrate(config.prewarmSeconds);
            prewarmed = true;
        }
        updateWater(dt);
        updateHeat(dt);

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

    public GeothermalGrid grid() {
        return grid;
    }

    public void setVents(List<VentSite> vents) {
        this.vents = List.copyOf(vents);
    }

    /** Absolute shallow-subsurface temperature (°C) at a block column; ambient outside the grid. */
    public double temperatureAt(int x, int z) {
        return config.ambientC + grid.excessAtBlock(x, z);
    }

    /** Groundwater saturation at a block column; base saturation outside the grid. */
    public double waterAt(int x, int z) {
        int index = grid.indexOfBlock(x, z);
        return index < 0 ? config.baseSaturation : grid.water(index);
    }

    /** Adds {@code deltaC} of excess temperature to the cell containing {@code (x, z)}. */
    public void addHeat(int x, int z, double deltaC) {
        int index = grid.indexOfBlock(x, z);
        if (index >= 0) grid.setExcess(index, Math.max(0, grid.excess(index) + deltaC));
    }

    /**
     * Heat transferred from a lava column of {@code thicknessM} at {@code lavaTemperatureC} resting
     * on block column {@code (x, z)}: the column's heat is shared with a shallow layer of the
     * cell ({@code cellSize² × lavaCouplingDepth}).
     */
    public void addLavaHeat(int x, int z, double lavaTemperatureC, double thicknessM) {
        int index = grid.indexOfBlock(x, z);
        if (index < 0 || thicknessM <= 0) return;
        double current = config.ambientC + grid.excess(index);
        double layerVolume = (double) config.cellSize * config.cellSize * config.lavaCouplingDepth;
        double delta = thicknessM * (lavaTemperatureC - current) / layerVolume;
        if (delta > 0) addHeat(x, z, delta);
    }

    /** Magma activity factor driving the heat sources (0 when the chamber is cold). */
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
     * Runs heat and groundwater only (no features, no randomness) for {@code seconds} of model time;
     * use when a volcano is created to start from a developed thermal state.
     */
    public void equilibrate(double seconds) {
        sampleTerrain();
        computeSources(); // constant while equilibrating
        // Diffusion substeps keep each chunk stable; the chunk only bounds the water/heat coupling lag.
        double chunk = Math.max(config.stepSeconds * config.timeScale, EQUILIBRATE_CHUNK_SECONDS);
        for (double t = 0; t < seconds; t += chunk) {
            double dt = Math.min(chunk, seconds - t);
            updateWater(dt);
            grid.diffuse(dt, config.diffusivity, config.surfaceLossPerSecond, source);
            boilingBuffer(dt);
        }
    }

    /** Boiling point (°C) of the shallow reservoir a cell represents (boiling-point-with-depth curve). */
    public double boilingPointC() {
        return boilingPointAtDepth(config.reservoirDepthM);
    }

    /** {@code T_bp(z) = 100 + 3·z^0.7}: fit to the boiling-point-with-depth curve of pure water. */
    static double boilingPointAtDepth(double depthM) {
        return 100 + 3.0 * StrictMath.pow(Math.max(0, depthM), 0.7);
    }

    /** True once the shallow system at the column is vapour-dominated (too dry to convect). */
    public boolean vapourDominatedAt(int x, int z) {
        return waterAt(x, z) <= config.vapourDominatedWater;
    }

    private double knownFraction() {
        int n = 0;
        for (boolean k : known) if (k) n++;
        return (double) n / known.length;
    }

    private static final double EQUILIBRATE_CHUNK_SECONDS = 300;

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

    public double fumaroleIntensity(double temperatureC) {
        return clamp((temperatureC - config.fumaroleMinC) / (config.fumaroleFullC - config.fumaroleMinC), 0, 1);
    }

    // ── Physics ──

    private void sampleTerrain() {
        int n = grid.cellCount();
        for (int idx = 0; idx < n; idx++) {
            TerrainColumn column = terrain.column(grid.cellCenterX(idx), grid.cellCenterZ(idx));
            known[idx] = column != null;
            submerged[idx] = column != null && column.submerged();
            ground[idx] = column == null ? referenceY : column.groundY();
        }
        int sx = grid.sizeX();
        int sz = grid.sizeZ();
        for (int idx = 0; idx < n; idx++) {
            int ci = grid.cellI(idx);
            int cj = grid.cellJ(idx);
            long sum = 0;
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
            localMean[idx] = count == 0 ? ground[idx] : (double) sum / count;
        }
        computeLakeDistance();
    }

    /** Chamfer (3-4) distance transform, in blocks, from each cell to the nearest submerged cell. */
    private void computeLakeDistance() {
        int sx = grid.sizeX();
        int sz = grid.sizeZ();
        double inf = Double.POSITIVE_INFINITY;
        for (int idx = 0; idx < lakeDistance.length; idx++) lakeDistance[idx] = submerged[idx] ? 0 : inf;
        double straight = grid.cellSize();
        double diagonal = grid.cellSize() * Math.sqrt(2);
        for (int j = 0; j < sz; j++) {
            for (int i = 0; i < sx; i++) {
                int idx = grid.index(i, j);
                double d = lakeDistance[idx];
                if (i > 0) d = Math.min(d, lakeDistance[idx - 1] + straight);
                if (j > 0) d = Math.min(d, lakeDistance[idx - sx] + straight);
                if (i > 0 && j > 0) d = Math.min(d, lakeDistance[idx - sx - 1] + diagonal);
                if (i + 1 < sx && j > 0) d = Math.min(d, lakeDistance[idx - sx + 1] + diagonal);
                lakeDistance[idx] = d;
            }
        }
        for (int j = sz - 1; j >= 0; j--) {
            for (int i = sx - 1; i >= 0; i--) {
                int idx = grid.index(i, j);
                double d = lakeDistance[idx];
                if (i + 1 < sx) d = Math.min(d, lakeDistance[idx + 1] + straight);
                if (j + 1 < sz) d = Math.min(d, lakeDistance[idx + sx] + straight);
                if (i + 1 < sx && j + 1 < sz) d = Math.min(d, lakeDistance[idx + sx + 1] + diagonal);
                if (i > 0 && j + 1 < sz) d = Math.min(d, lakeDistance[idx + sx - 1] + diagonal);
                lakeDistance[idx] = d;
            }
        }
    }

    private void updateWater(double dt) {
        double relax = Math.exp(-config.rechargePerSecond * dt);
        for (int idx = 0; idx < grid.cellCount(); idx++) {
            double target;
            if (!known[idx]) {
                target = config.baseSaturation;
            } else if (submerged[idx]) {
                target = 1;
            } else {
                double lake = lakeDistance[idx] == Double.POSITIVE_INFINITY
                        ? 0
                        : config.lakeSaturationBonus * Math.exp(-lakeDistance[idx] / config.lakeInfluenceBlocks);
                target = clamp(
                        config.baseSaturation + (localMean[idx] - ground[idx]) * config.elevationSaturationPerBlock + lake,
                        0,
                        1);
            }
            double w = target + (grid.water(idx) - target) * relax;
            double temperature = config.ambientC + grid.excess(idx);
            if (temperature > 100 && !submerged[idx]) {
                w *= Math.exp(-config.boilOffPerSecond * (temperature - 100) / 100 * dt);
            }
            grid.setWater(idx, clamp(w, 0, 1));
        }
    }

    private void updateHeat(double dt) {
        computeSources();
        grid.diffuse(dt, config.diffusivity, config.surfaceLossPerSecond, source);
        boilingBuffer(dt);
    }

    /**
     * Liquid-dominated dry-land cells above their boiling point lose the excess heat by boiling
     * outflow: {@code T → T_bp} at {@code boilingBufferPerSecond · (w − w_v)/(1 − w_v)} (exact
     * exponential relaxation, so stiff rates stay stable).
     */
    private void boilingBuffer(double dt) {
        if (config.boilingBufferPerSecond <= 0) return;
        double boiling = boilingPointC();
        double wv = config.vapourDominatedWater;
        for (int idx = 0; idx < grid.cellCount(); idx++) {
            if (!known[idx] || submerged[idx]) continue;
            double temperature = config.ambientC + grid.excess(idx);
            if (temperature <= boiling) continue;
            double liquid = (grid.water(idx) - wv) / (1 - wv);
            if (liquid <= 0) continue;
            double next = boiling + (temperature - boiling) * Math.exp(-config.boilingBufferPerSecond * liquid * dt);
            grid.setExcess(idx, next - config.ambientC);
        }
    }

    private void computeSources() {
        double activity = activity();
        if (activity <= 0) {
            Arrays.fill(source, 0);
            return;
        }
        BlockPos chamber = magma.chamberCenter();
        for (int idx = 0; idx < grid.cellCount(); idx++) {
            double cx = grid.cellCenterX(idx) + 0.5;
            double cz = grid.cellCenterZ(idx) + 0.5;

            double ventHeat = 0;
            for (VentSite vent : vents) {
                double d = distanceToVent(vent, cx, cz);
                double sigma = Math.max(config.cellSize, vent.craterRadius()) + config.ventHaloBlocks;
                ventHeat += Math.exp(-d * d / (2 * sigma * sigma));
            }

            double depth = Math.max(config.minChamberDepth, ground[idx] - chamber.y());
            double dx = cx - chamber.x();
            double dz = cz - chamber.z();
            double r2 = dx * dx + dz * dz;
            double halo = depth * depth * depth / Math.pow(r2 + depth * depth, 1.5);

            source[idx] = activity * (config.ventHeatRate * ventHeat + config.chamberHeatRate * halo);
        }
    }

    private static double distanceToVent(VentSite vent, double x, double z) {
        BlockPos p = vent.position();
        if (vent.kind() != VentKind.FISSURE || vent.fissureLength() == 0) {
            double dx = x - p.x();
            double dz = z - p.z();
            return Math.sqrt(dx * dx + dz * dz);
        }
        double ux = Math.cos(vent.fissureAngleRad());
        double uz = Math.sin(vent.fissureAngleRad());
        double half = vent.fissureLength() / 2.0;
        double t = clamp((x - p.x()) * ux + (z - p.z()) * uz, -half, half);
        double dx = x - (p.x() + t * ux);
        double dz = z - (p.z() + t * uz);
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ── Manifestations ──

    private void formFeatures(StepContext context, double dtHours) {
        SimRandom random = context.random();
        double minFeatureC = minFeatureTemperature();

        for (int idx = 0; idx < grid.cellCount(); idx++) {
            if (!known[idx]) continue;
            double temperature = config.ambientC + grid.excess(idx);
            if (temperature < minFeatureC) continue;
            double w = grid.water(idx);

            if (submerged[idx]) {
                if (temperature >= config.submarineVentMinC) {
                    double strength = clamp((temperature - config.submarineVentMinC) / 100, 0.1, 1);
                    attempts(context, idx, config.submarineVentFormationPerHour * strength * dtHours,
                            (x, z) -> trySubmarineVent(context, x, z));
                }
                continue;
            }

            if (temperature >= config.fumaroleMinC) {
                double intensity = Math.max(0.1, fumaroleIntensity(temperature));
                attempts(context, idx, config.fumaroleFormationPerHour * intensity * dtHours,
                        (x, z) -> tryFumarole(context, x, z));
            }
            if (inBand(temperature, config.geyserMinC, config.geyserMaxC) && w >= config.geyserMinWater) {
                double strength = 0.25 + 0.75 * (w - config.geyserMinWater) / Math.max(1e-9, 1 - config.geyserMinWater);
                attempts(context, idx, config.geyserFormationPerHour * strength * dtHours,
                        (x, z) -> tryGeyser(context, x, z));
            }
            if (inBand(temperature, config.hotSpringMinC, config.hotSpringMaxC) && w >= config.hotSpringMinWater) {
                attempts(context, idx, config.hotSpringFormationPerHour * dtHours,
                        (x, z) -> trySpring(context, x, z, temperature >= config.sulfurSpringMinC));
            }
            if (inBand(temperature, config.mudPotMinC, config.mudPotMaxC)
                    && w >= config.mudPotMinWater
                    && w <= config.mudPotMaxWater) {
                attempts(context, idx, config.mudPotFormationPerHour * dtHours, (x, z) -> tryMudPot(context, x, z));
            }
            if (temperature >= config.acidMinC && count(HydrothermalFeature.ACID_ALTERATION) < config.maxAltered) {
                double strength = clamp((temperature - config.acidMinC) / 100, 0.1, 1);
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

    private boolean tryFumarole(StepContext context, int x, int z) {
        TerrainColumn column = buildableColumn(x, z);
        if (column == null) return false;
        if (count(HydrothermalFeature.FUMAROLE) >= config.maxFumaroles) return false;
        if (hasNearby(x, z, config.fumaroleSpacing, HydrothermalFeature.FUMAROLE)) return false;
        register(new PlacedFeature(x, column.groundY(), z, HydrothermalFeature.FUMAROLE, 0));
        formed(context, HydrothermalFeature.FUMAROLE, new BlockPos(x, column.groundY(), z));
        return true;
    }

    private boolean tryGeyser(StepContext context, int x, int z) {
        if (!palette.supports(GeothermalBlocks.POTENT_SULFUR)
                || !palette.supports(GeothermalBlocks.MAGMA_BLOCK)
                || !palette.supports(GeothermalBlocks.WATER)) {
            return false;
        }
        TerrainColumn column = buildableColumn(x, z);
        if (column == null) return false;
        if (count(HydrothermalFeature.GEYSER) >= config.maxGeysers) return false;
        if (hasNearby(x, z, config.geyserSpacing, HydrothermalFeature.GEYSER)) return false;
        int g = column.groundY();
        if (!contained(x, z, g, Set.of(PlacedFeature.key(x, z)))) return false;

        int waterBlocks = 1 + context.random().nextInt(4);
        int potentY = g - waterBlocks;
        int magmaY = potentY - 1;

        set(context, new BlockPos(x, magmaY, z), null, BlockState.of(GeothermalBlocks.MAGMA_BLOCK));
        set(context, new BlockPos(x, potentY, z), null, BlockState.of(GeothermalBlocks.POTENT_SULFUR));
        for (int y = potentY + 1; y <= g; y++) {
            BlockId expected = y == g ? column.surface() : null;
            set(context, new BlockPos(x, y, z), expected, BlockState.of(GeothermalBlocks.WATER));
        }
        terrain.setColumn(x, z, new TerrainColumn(potentY, g, GeothermalBlocks.POTENT_SULFUR));
        register(new PlacedFeature(x, potentY, z, HydrothermalFeature.GEYSER, waterBlocks));

        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dz != 0) && context.random().chance(0.5)) {
                    surfaceFeature(context, x + dx, z + dz, HydrothermalFeature.SINTER,
                            pickSinterProduct(context.random()), false);
                }
            }
        }

        BlockPos anchor = new BlockPos(x, potentY, z);
        context.outbox().emit(new GeyserFormed(context.tick(), anchor, waterBlocks));
        formed(context, HydrothermalFeature.GEYSER, anchor);
        return true;
    }

    private boolean trySpring(StepContext context, int x, int z, boolean wantSulfur) {
        if (!palette.supports(GeothermalBlocks.WATER)) return false;
        boolean sulfurSpring = wantSulfur && palette.supports(GeothermalBlocks.POTENT_SULFUR);
        HydrothermalFeature kind = sulfurSpring ? HydrothermalFeature.SULFUR_SPRING : HydrothermalFeature.HOT_SPRING;

        TerrainColumn center = buildableColumn(x, z);
        if (center == null) return false;
        if (count(HydrothermalFeature.HOT_SPRING) + count(HydrothermalFeature.SULFUR_SPRING) >= config.maxHotSprings) {
            return false;
        }
        if (hasNearby(x, z, config.hotSpringSpacing, HydrothermalFeature.HOT_SPRING, HydrothermalFeature.SULFUR_SPRING)) {
            return false;
        }
        int g = center.groundY();

        List<int[]> pool = new ArrayList<>();
        Set<Long> poolKeys = new LinkedHashSet<>();
        pool.add(new int[] {x, z});
        poolKeys.add(PlacedFeature.key(x, z));
        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];
            TerrainColumn neighbour = buildableColumn(nx, nz);
            if (neighbour != null && neighbour.groundY() == g && context.random().chance(0.5)) {
                pool.add(new int[] {nx, nz});
                poolKeys.add(PlacedFeature.key(nx, nz));
            }
        }
        for (int[] p : pool) {
            if (!contained(p[0], p[1], g, poolKeys)) return false;
        }

        BlockId floor = sulfurSpring ? GeothermalBlocks.POTENT_SULFUR : null;
        for (int[] p : pool) {
            TerrainColumn column = terrain.column(p[0], p[1]);
            set(context, new BlockPos(p[0], g, p[1]), column.surface(), BlockState.of(GeothermalBlocks.WATER));
            if (floor != null) set(context, new BlockPos(p[0], g - 1, p[1]), null, BlockState.of(floor));
            terrain.setColumn(p[0], p[1], new TerrainColumn(g - 1, g, floor != null ? floor : column.surface()));
            register(new PlacedFeature(p[0], g, p[1], kind, 0));
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
            TerrainColumn column = terrain.column(rx, rz);
            if (column == null || column.groundY() < g || column.groundY() > g + 1) continue;
            if (sulfurSpring) {
                surfaceFeature(context, rx, rz, HydrothermalFeature.SULFUR_DEPOSIT, GeothermalBlocks.SULFUR, false);
            } else {
                surfaceFeature(context, rx, rz, HydrothermalFeature.SINTER, pickSinterProduct(context.random()), false);
            }
        }

        formed(context, kind, new BlockPos(x, g, z));
        return true;
    }

    private boolean tryMudPot(StepContext context, int x, int z) {
        TerrainColumn column = buildableColumn(x, z);
        if (column == null) return false;
        if (count(HydrothermalFeature.MUD_POT) >= config.maxMudPots) return false;
        if (hasNearby(x, z, config.mudPotSpacing, HydrothermalFeature.MUD_POT)) return false;
        if (!surfaceFeature(context, x, z, HydrothermalFeature.MUD_POT, GeothermalBlocks.MUD, false)) return false;
        for (int[] d : CARDINALS) {
            if (context.random().chance(0.5)) {
                surfaceFeature(context, x + d[0], z + d[1], HydrothermalFeature.MUD_POT, GeothermalBlocks.MUD, false);
            }
        }
        formed(context, HydrothermalFeature.MUD_POT, new BlockPos(x, column.groundY(), z));
        return true;
    }

    private boolean trySubmarineVent(StepContext context, int x, int z) {
        if (features.containsKey(PlacedFeature.key(x, z))) return false;
        TerrainColumn column = terrain.column(x, z);
        if (column == null || !column.submerged() || !config.alterableSurfaces.contains(column.surface())) return false;
        if (count(HydrothermalFeature.SUBMARINE_VENT) >= config.maxSubmarineVents) return false;
        if (hasNearby(x, z, config.submarineVentSpacing, HydrothermalFeature.SUBMARINE_VENT)) return false;
        if (!surfaceFeature(context, x, z, HydrothermalFeature.SUBMARINE_VENT, GeothermalBlocks.MAGMA_BLOCK, true)) {
            return false;
        }
        for (int[] d : CARDINALS) {
            if (context.random().chance(0.3)) {
                surfaceFeature(context, x + d[0], z + d[1], HydrothermalFeature.SULFUR_DEPOSIT, GeothermalBlocks.SULFUR, true);
            }
        }
        formed(context, HydrothermalFeature.SUBMARINE_VENT, new BlockPos(x, column.groundY(), z));
        return true;
    }

    /**
     * Acid-sulfate alteration grows outward from fumaroles and altered ground, where condensing steam
     * acidifies the soil whatever the deeper saturation. An isolated patch nucleates only on
     * unsaturated (two-phase / vapour-dominated) ground, with probability
     * {@code alterationNucleationFactor}.
     */
    private boolean tryAcidAlteration(StepContext context, int x, int z, boolean unsaturated, SimRandom random) {
        if (count(HydrothermalFeature.ACID_ALTERATION) >= config.maxAltered) return false;
        boolean adjacent = hasNearby(x, z, config.alterationGrowthRadius,
                HydrothermalFeature.FUMAROLE, HydrothermalFeature.ACID_ALTERATION, HydrothermalFeature.SULFUR_DEPOSIT);
        if (!adjacent && !(unsaturated && random.chance(config.alterationNucleationFactor))) return false;
        return tryAlteration(context, x, z, HydrothermalFeature.ACID_ALTERATION, pickAcidProduct(random));
    }

    /** Sinter spreads from springs, geysers and existing sinter; isolated seeps are rare. */
    private boolean trySinter(StepContext context, int x, int z, SimRandom random) {
        if (count(HydrothermalFeature.SINTER) >= config.maxSinter) return false;
        boolean adjacent = hasNearby(x, z, config.sinterGrowthRadius, HydrothermalFeature.HOT_SPRING,
                HydrothermalFeature.SULFUR_SPRING, HydrothermalFeature.GEYSER, HydrothermalFeature.SINTER);
        if (!adjacent && !random.chance(config.sinterNucleationFactor)) return false;
        return tryAlteration(context, x, z, HydrothermalFeature.SINTER, pickSinterProduct(random));
    }

    /** Cinnabar precipitates where cooling spring fluids reach the surface: next to springs, geysers or sinter. */
    private boolean tryCinnabar(StepContext context, int x, int z) {
        if (count(HydrothermalFeature.CINNABAR) >= config.maxCinnabar) return false;
        if (config.cinnabarSpringRadius > 0 && !hasNearby(x, z, config.cinnabarSpringRadius,
                HydrothermalFeature.HOT_SPRING, HydrothermalFeature.SULFUR_SPRING, HydrothermalFeature.GEYSER,
                HydrothermalFeature.SINTER)) {
            return false;
        }
        return tryAlteration(context, x, z, HydrothermalFeature.CINNABAR, GeothermalBlocks.CINNABAR);
    }

    private boolean tryAlteration(StepContext context, int x, int z, HydrothermalFeature kind, BlockId product) {
        TerrainColumn column = buildableColumn(x, z);
        if (column == null) return false;
        if (!surfaceFeature(context, x, z, kind, product, false)) return false;
        formed(context, kind, new BlockPos(x, column.groundY(), z));
        return true;
    }

    /**
     * Replaces the surface block of an unoccupied, alterable column with {@code preferred} (resolved
     * through the palette) and registers the feature. {@code underwater} selects submerged columns
     * instead of dry ones.
     */
    private boolean surfaceFeature(
            StepContext context, int x, int z, HydrothermalFeature kind, BlockId preferred, boolean underwater) {
        if (features.containsKey(PlacedFeature.key(x, z))) return false;
        TerrainColumn column = terrain.column(x, z);
        if (column == null || column.submerged() != underwater) return false;
        if (!config.alterableSurfaces.contains(column.surface())) return false;
        BlockId resolved = palette.resolve(preferred);
        if (resolved == null) return false;
        set(context, new BlockPos(x, column.groundY(), z), column.surface(), BlockState.of(resolved));
        terrain.setGround(x, z, column.groundY(), resolved);
        register(new PlacedFeature(x, column.groundY(), z, kind, 0));
        return true;
    }

    // ── Sulfur deposition around fumaroles ──

    private void depositSulfur(StepContext context, double dtHours) {
        SimRandom random = context.random();
        int r = config.sulfurDepositRadius;
        for (PlacedFeature fumarole : features(HydrothermalFeature.FUMAROLE)) {
            double intensity = fumaroleIntensity(temperatureAt(fumarole.x(), fumarole.z()));
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

    private void coatFumarole(StepContext context, PlacedFeature fumarole) {
        if (fumarole.level() > 0) return;
        TerrainColumn column = terrain.column(fumarole.x(), fumarole.z());
        if (column == null || column.submerged() || column.groundY() != fumarole.y()) return;
        if (!config.alterableSurfaces.contains(column.surface())) return;
        BlockId sulfur = palette.resolve(GeothermalBlocks.SULFUR);
        if (sulfur == null) return;
        set(context, new BlockPos(fumarole.x(), fumarole.y(), fumarole.z()), column.surface(), BlockState.of(sulfur));
        terrain.setGround(fumarole.x(), fumarole.z(), fumarole.y(), sulfur);
        features.put(PlacedFeature.key(fumarole.x(), fumarole.z()), fumarole.withLevel(1));
    }

    private void depositAt(StepContext context, int x, int z) {
        PlacedFeature existing = features.get(PlacedFeature.key(x, z));
        if (existing == null) {
            if (surfaceFeature(context, x, z, HydrothermalFeature.SULFUR_DEPOSIT, GeothermalBlocks.SULFUR, false)) {
                formed(context, HydrothermalFeature.SULFUR_DEPOSIT, new BlockPos(x, features.get(PlacedFeature.key(x, z)).y(), z));
            }
            return;
        }
        if (existing.kind() != HydrothermalFeature.SULFUR_DEPOSIT || existing.level() >= config.maxSpikeHeight) return;
        growSpike(context, existing);
    }

    private void growSpike(StepContext context, PlacedFeature deposit) {
        int oldHeight = deposit.level();
        int newHeight = oldHeight + 1;
        BlockState[] column = new BlockState[newHeight];
        for (int i = 0; i < newHeight; i++) {
            BlockState resolved = palette.resolve(spikeState(i, newHeight));
            if (resolved == null) return;
            column[i] = resolved;
        }
        for (int i = 0; i < newHeight; i++) {
            BlockId expected = i < oldHeight ? column[i].id() : GeothermalBlocks.AIR;
            set(context, new BlockPos(deposit.x(), deposit.y() + 1 + i, deposit.z()), expected, column[i]);
        }
        features.put(PlacedFeature.key(deposit.x(), deposit.z()), deposit.withLevel(newHeight));
    }

    /**
     * State of the {@code index}-th (from the bottom) block of an upward spike of {@code height},
     * following pointed dripstone's thickness sequence: base, middle…, frustum, tip.
     */
    static BlockState spikeState(int index, int height) {
        String thickness;
        if (index == height - 1) thickness = "tip";
        else if (index == height - 2) thickness = "frustum";
        else if (index == 0) thickness = "base";
        else thickness = "middle";
        return BlockState.of(GeothermalBlocks.SULFUR_SPIKE)
                .with("vertical_direction", "up")
                .with("thickness", thickness);
    }

    // ── Events ──

    /**
     * Announces fumarole activity when it starts, when its intensity changes by
     * {@code fumaroleReportDelta}, at least every {@code fumaroleRefreshSeconds}, and once with
     * intensity 0 when it dies down. Hosts keep rendering the last announced state.
     */
    private void emitFumaroleActivity(StepContext context) {
        long refreshTicks = Math.max(1, Math.round(config.fumaroleRefreshSeconds * 20));
        for (PlacedFeature fumarole : features(HydrothermalFeature.FUMAROLE)) {
            long key = PlacedFeature.key(fumarole.x(), fumarole.z());
            double temperature = temperatureAt(fumarole.x(), fumarole.z());
            double intensity = fumaroleIntensity(temperature);
            double[] last = fumaroleReports.get(key);
            boolean report;
            if (intensity <= 0) {
                report = last != null && last[0] > 0;
            } else {
                report = last == null
                        || Math.abs(intensity - last[0]) >= config.fumaroleReportDelta
                        || context.tick() - (long) last[1] >= refreshTicks;
            }
            if (!report) continue;
            fumaroleReports.put(key, new double[] {intensity, context.tick()});
            context.outbox().emit(new FumaroleActivity(
                    context.tick(),
                    new BlockPos(fumarole.x(), fumarole.y() + 1, fumarole.z()),
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
        long refreshTicks = Math.max(1, Math.round(config.hazardRefreshSeconds * 20));
        double radius = zoneCells * config.cellSize * Math.sqrt(0.5);
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
                        double intensity = fumaroleIntensity(temperature);
                        if (intensity <= 0) continue;
                        GasComposition gas = GasComposition.atTemperature(temperature);
                        for (int s = 0; s < speciesList.length; s++) {
                            double ppm = gas.fraction(speciesList[s]) * intensity * config.gasFluxPpm;
                            if (speciesList[s] == GasSpecies.CO2) {
                                // CO₂ is denser than air and pools in depressions.
                                ppm *= 1 + clamp((localMean[idx] - ground[idx]) / 4, 0, 2);
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
                        context.outbox().emit(new GasHazard(context.tick(), zoneCenter(zi, zj, zoneCells), radius,
                                speciesList[s], 0, 0));
                        continue;
                    }
                    boolean report = last == null
                            || Math.abs(ppm - last[0]) >= config.hazardChangeFraction * last[0]
                            || context.tick() - (long) last[1] >= refreshTicks;
                    if (!report) continue;
                    hazardReports.put(key, new double[] {ppm, context.tick()});
                    context.outbox().emit(new GasHazard(context.tick(), zoneCenter(zi, zj, zoneCells), radius,
                            speciesList[s], ppm, validFor));
                }
            }
        }
    }

    /**
     * Hazard centre: the middle of the zone, one block above its ground. It is the same for every
     * event of a zone, so hosts can key hazards by centre and species.
     */
    private BlockPos zoneCenter(int zi, int zj, int zoneCells) {
        int i = Math.min(grid.sizeX() - 1, zi * zoneCells + zoneCells / 2);
        int j = Math.min(grid.sizeZ() - 1, zj * zoneCells + zoneCells / 2);
        int idx = grid.index(i, j);
        int y = known[idx] ? ground[idx] : referenceY;
        return new BlockPos(grid.cellMinX(idx), y + 1, grid.cellMinZ(idx));
    }

    private void formed(StepContext context, HydrothermalFeature kind, BlockPos pos) {
        context.outbox().emit(new HydrothermalFeatureFormed(context.tick(), kind, pos));
    }

    // ── Helpers ──

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** Known, dry, alterable and unoccupied column, or {@code null}. */
    private TerrainColumn buildableColumn(int x, int z) {
        if (features.containsKey(PlacedFeature.key(x, z))) return null;
        TerrainColumn column = terrain.column(x, z);
        if (column == null || column.submerged()) return null;
        if (!config.alterableSurfaces.contains(column.surface())) return null;
        return column;
    }

    /** True if water placed at height {@code g} in column (x, z) is walled in by its neighbours. */
    private boolean contained(int x, int z, int g, Set<Long> sameBody) {
        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];
            if (sameBody.contains(PlacedFeature.key(nx, nz))) continue;
            TerrainColumn neighbour = terrain.column(nx, nz);
            if (neighbour == null || neighbour.groundY() < g || neighbour.submerged()) return false;
        }
        return true;
    }

    private boolean hasNearby(int x, int z, int spacing, HydrothermalFeature... kinds) {
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

    private void register(PlacedFeature feature) {
        features.put(PlacedFeature.key(feature.x(), feature.z()), feature);
        counts.merge(feature.kind(), 1, Integer::sum);
    }

    private void set(StepContext context, BlockPos pos, BlockId expected, BlockState preferred) {
        BlockState resolved = palette.resolve(preferred);
        if (resolved == null) return;
        context.outbox().setBlock(BlockChange.replace(pos, expected, resolved));
    }

    private static final BlockId[] ACID_PRODUCTS = {
        GeothermalBlocks.WHITE_TERRACOTTA,
        GeothermalBlocks.CLAY,
        GeothermalBlocks.YELLOW_TERRACOTTA,
        GeothermalBlocks.ORANGE_TERRACOTTA,
        GeothermalBlocks.RED_TERRACOTTA,
    };
    private static final double[] ACID_WEIGHTS = {3, 2, 2, 1, 1};

    private static BlockId pickAcidProduct(SimRandom random) {
        double total = 0;
        for (double w : ACID_WEIGHTS) total += w;
        double pick = random.nextDouble() * total;
        for (int i = 0; i < ACID_PRODUCTS.length; i++) {
            pick -= ACID_WEIGHTS[i];
            if (pick < 0) return ACID_PRODUCTS[i];
        }
        return ACID_PRODUCTS[ACID_PRODUCTS.length - 1];
    }

    private static BlockId pickSinterProduct(SimRandom random) {
        return random.chance(0.7) ? GeothermalBlocks.CALCITE : GeothermalBlocks.DIORITE;
    }

    private double minFeatureTemperature() {
        return Math.min(
                Math.min(Math.min(config.fumaroleMinC, config.geyserMinC), Math.min(config.hotSpringMinC, config.mudPotMinC)),
                Math.min(Math.min(config.acidMinC, config.sinterMinC), Math.min(config.cinnabarMinC, config.submarineVentMinC)));
    }

    private static boolean inBand(double value, double min, double max) {
        return value >= min && value <= max;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ── Persistence ──

    @Override
    public void saveState(JsonObject out) {
        out.addProperty("minX", grid.minX());
        out.addProperty("minZ", grid.minZ());
        out.addProperty("sizeX", grid.sizeX());
        out.addProperty("sizeZ", grid.sizeZ());
        out.addProperty("cellSize", grid.cellSize());
        out.addProperty("excess", encode(grid.excess));
        out.addProperty("water", encode(grid.water));
        out.addProperty("hazardClock", hazardClock);
        JsonArray list = new JsonArray();
        for (PlacedFeature feature : features.values()) {
            JsonArray entry = new JsonArray();
            entry.add(feature.x());
            entry.add(feature.y());
            entry.add(feature.z());
            entry.add(feature.kind().name());
            entry.add(feature.level());
            list.add(entry);
        }
        out.add("features", list);
        out.addProperty("prewarmed", prewarmed);
        out.add("fumaroleReports", saveReports(fumaroleReports));
        out.add("hazardReports", saveReports(hazardReports));
    }

    private static JsonArray saveReports(TreeMap<Long, double[]> reports) {
        JsonArray list = new JsonArray();
        for (Map.Entry<Long, double[]> e : reports.entrySet()) {
            JsonArray entry = new JsonArray();
            entry.add(e.getKey());
            entry.add(Double.doubleToRawLongBits(e.getValue()[0]));
            entry.add((long) e.getValue()[1]);
            list.add(entry);
        }
        return list;
    }

    private static void loadReports(JsonObject in, String name, TreeMap<Long, double[]> target) {
        target.clear();
        if (!in.has(name)) return;
        for (JsonElement element : in.getAsJsonArray(name)) {
            JsonArray entry = element.getAsJsonArray();
            target.put(entry.get(0).getAsLong(), new double[] {
                Double.longBitsToDouble(entry.get(1).getAsLong()), entry.get(2).getAsLong()});
        }
    }

    @Override
    public void loadState(JsonObject in) {
        if (in.get("minX").getAsInt() != grid.minX()
                || in.get("minZ").getAsInt() != grid.minZ()
                || in.get("sizeX").getAsInt() != grid.sizeX()
                || in.get("sizeZ").getAsInt() != grid.sizeZ()
                || in.get("cellSize").getAsInt() != grid.cellSize()) {
            throw new IllegalArgumentException("Saved geothermal grid does not match the configured grid for " + id);
        }
        decode(in.get("excess").getAsString(), grid.excess);
        decode(in.get("water").getAsString(), grid.water);
        hazardClock = in.get("hazardClock").getAsDouble();
        features.clear();
        counts.clear();
        for (JsonElement element : in.getAsJsonArray("features")) {
            JsonArray entry = element.getAsJsonArray();
            register(new PlacedFeature(
                    entry.get(0).getAsInt(),
                    entry.get(1).getAsInt(),
                    entry.get(2).getAsInt(),
                    HydrothermalFeature.valueOf(entry.get(3).getAsString()),
                    entry.get(4).getAsInt()));
        }
        prewarmed = in.has("prewarmed") && in.get("prewarmed").getAsBoolean();
        loadReports(in, "fumaroleReports", fumaroleReports);
        loadReports(in, "hazardReports", hazardReports);
    }

    private static String encode(double[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Double.BYTES);
        for (double v : values) buffer.putLong(Double.doubleToRawLongBits(v));
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private static void decode(String encoded, double[] target) {
        ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(encoded));
        if (buffer.remaining() != target.length * Double.BYTES) {
            throw new IllegalArgumentException("Saved field has wrong length");
        }
        for (int i = 0; i < target.length; i++) target[i] = Double.longBitsToDouble(buffer.getLong());
    }
}
