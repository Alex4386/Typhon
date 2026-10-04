package me.alex4386.typhon.engine.subsurface;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import me.alex4386.typhon.engine.save.FieldChunk;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.world.WorldModel;

/**
 * The world-level subsurface model: heat conduction ({@link SubsurfaceHeat}), groundwater
 * ({@link Groundwater}) and surface water ({@link SurfaceWater}) under and on the
 * {@link WorldModel}. One instance is shared by every volcano of a world.
 *
 * <h2>Water</h2>
 * Three stores exchange water: surface water (per surface column, stepped every
 * {@link SubsurfaceConfig#surfaceWaterStepSeconds}), the vadose zone (a bucket per solver column)
 * and the water table (per solver column). Rain infiltrates up to the ground's hydraulic
 * conductivity (the rest runs off), surface water infiltrates where the water table lies below the
 * ground, the vadose zone drains to the water table with lag {@code τ}, the water table flows
 * laterally (Dupuit) and spills out as springs where it reaches the ground, boiling removes water
 * as steam, and the sea is a fixed-level boundary. {@link #budget()} accounts for every m³.
 *
 * <h2>Heat</h2>
 * Every {@link SubsurfaceConfig#macroStepSeconds} the heat and groundwater solvers advance by that
 * period × {@link SubsurfaceConfig#timeScale}. Volcanoes register {@link HeatSources} (chamber halo
 * as the bottom boundary, vent heat pipes); surface flows add heat through {@link #addSurfaceHeat};
 * dikes through {@link #addSheetHeat}. Solver chunks far from any anomaly are not stepped
 * (DORMANT), neighbours of anomalies are stepped every {@code warmEvery} macro steps (WARM).
 *
 * <p>Iteration is in key order everywhere, so results are deterministic and independent of thread
 * count; state round-trips bit for bit through {@link #saveState}/{@link #loadState}.
 */
public final class Subsurface implements Subsystem, HydrothermalField {
    public static final String ID = "subsurface";
    private static final int SOLVER_SCHEMA = 1;
    private static final int WATER_SCHEMA = 1;

    private final WorldModel world;
    private final SubsurfaceConfig config;
    private final SubsurfaceGrid grid;
    private final SubsurfaceHeat heat;
    private final Groundwater groundwater;
    private final SurfaceWater surface;
    private final TreeMap<String, HeatSources> sources = new TreeMap<>();
    /** Pending energy (J) per solver column key and level, from hooks; applied at the next macro step. */
    private final TreeMap<Long, double[]> pendingHeat = new TreeMap<>();

    private double macroClock;
    private long macroSteps;
    // Cumulative budget terms not held by the components (m³)
    private double rain;
    private double boiled;
    private double seaGroundwater;
    private double deficit;
    private double initialGroundwater;
    private double removed;

    public Subsurface(WorldModel world, SubsurfaceConfig config) {
        config.validate();
        this.world = Objects.requireNonNull(world);
        this.config = config;
        this.grid = new SubsurfaceGrid(world, config);
        this.heat = new SubsurfaceHeat(grid, config);
        this.groundwater = new Groundwater(grid, config);
        this.surface = new SurfaceWater(world, config);
        world.setWaterSink((x, z, v) -> surface.add(x, z, v, SurfaceWater.Source.POURED));
    }

    public Subsurface(WorldModel world) {
        this(world, new SubsurfaceConfig());
    }

    // ── Subsystem ──

    @Override
    public String id() {
        return ID;
    }

    @Override
    public double periodSeconds() {
        return config.surfaceWaterStepSeconds;
    }

    @Override
    public Object config() {
        return config;
    }

    public SubsurfaceConfig configuration() {
        return config;
    }

    public WorldModel world() {
        return world;
    }

    /** Registers (or replaces) the heat sources of {@code owner} (e.g. a volcano id). Not persisted. */
    public void setHeatSources(String owner, HeatSources heatSources) {
        if (heatSources == null) sources.remove(owner);
        else sources.put(owner, heatSources);
    }

    @Override
    public void step(StepContext context) {
        double dt = context.dtSeconds();
        prepare();
        surface.step(dt, infiltration());
        macroClock += dt;
        if (macroClock + 1e-9 >= config.macroStepSeconds) {
            macroClock -= config.macroStepSeconds;
            if (macroClock < 1e-9) macroClock = 0;
            macroStep(config.macroStepSeconds * config.timeScale, config.macroStepSeconds, true);
        }
    }

    /** Brings the grid and surface water up to date with the world model (new columns, lakes, edits). */
    void prepare() {
        List<HeatSources.Chamber> chambers = chambers();
        grid.refresh((ch, c) -> initializeColumn(ch, c, chambers));
        surface.seedLakes();
    }

    private void initializeColumn(SolverChunk ch, int c, List<HeatSources.Chamber> chambers) {
        heat.initialize(ch, c, chambers);
        double head = ch.surfaceZ[c] - config.initialWaterTableDepthM;
        if (!Double.isNaN(ch.lakeZ[c])) head = Math.max(head, Math.min(ch.lakeZ[c], ch.surfaceZ[c]));
        double sea = world.spec().seaLevelZ();
        if (ch.sea[c]) head = sea;
        head = Math.max(head, groundwater.floor(ch, c));
        ch.head[c] = head;
        ch.vadose[c] = 0;
        if (!ch.sea[c]) initialGroundwater += config.specificYield * grid.area() * (head - groundwater.floor(ch, c));
        ch.activity = SolverChunk.Activity.HOT; // evaluate on the next macro step
    }

    private List<HeatSources.Chamber> chambers() {
        List<HeatSources.Chamber> list = new ArrayList<>();
        for (HeatSources s : sources.values()) list.addAll(s.chambers());
        return list;
    }

    private SurfaceWater.Infiltration infiltration() {
        return new SurfaceWater.Infiltration() {
            @Override
            public double capacity(int x, int z) {
                int gx = grid.solverCoord(x);
                int gz = grid.solverCoord(z);
                SolverChunk ch = grid.chunkOf(gx, gz);
                if (ch == null) return 0;
                int c = SolverChunk.column(gx, gz);
                if (!ch.exists[c] || ch.sea[c] || ch.head[c] >= ch.surfaceZ[c] - 0.01) return 0;
                return ch.hydraulicK[c * grid.levels()];
            }

            @Override
            public void accept(int x, int z, double volumeM3) {
                int gx = grid.solverCoord(x);
                int gz = grid.solverCoord(z);
                SolverChunk ch = grid.chunkOf(gx, gz);
                ch.vadose[SolverChunk.column(gx, gz)] += volumeM3 / grid.area();
            }
        };
    }

    /**
     * One heat + groundwater step of {@code dtPhysical} seconds. {@code dtSim} is the simulated time it
     * covers (rain is integrated over physical time).
     */
    void macroStep(double dtPhysical, double dtSim, boolean withSurface) {
        macroSteps++;
        List<HeatSources.Chamber> chambers = chambers();
        if (withSurface) applyRain(dtPhysical);
        Map<SolverChunk, Double> steps = activityAndSteps(dtPhysical);
        Map<SolverChunk, double[]> energy = sourceEnergy(dtPhysical, steps);
        heat.step(steps, energy, chambers, groundwater, (ch, c) -> {
            int x = ch.outletX[c];
            int z = ch.outletZ[c];
            return surface.depth(x, z);
        });
        boiled += heat.boiledTotal;
        groundwater.step(dtPhysical, heat.boiledVolume,
                (ch, c, v) -> surface.add(ch.outletX[c], ch.outletZ[c], v, SurfaceWater.Source.SPRING));
        seaGroundwater += groundwater.seaExchange;
        deficit += groundwater.deficitVolume;
    }

    private void applyRain(double dt) {
        double rate = config.rainfallMmPerHour / 1000.0 / 3600.0; // m/s
        if (rate <= 0) return;
        double area = grid.area();
        int n = grid.levels();
        for (SolverChunk ch : grid.chunks()) {
            for (int c = 0; c < SolverChunk.AREA; c++) {
                if (!ch.exists[c]) continue;
                double total = rate * dt * area;
                rain += total;
                if (ch.sea[c]) {
                    surface.seaOutflow += total;
                    continue;
                }
                boolean saturated = ch.head[c] >= ch.surfaceZ[c] - 0.01;
                double capacity = saturated ? 0 : ch.hydraulicK[c * n];
                double infiltrated = Math.min(rate, capacity) * dt * area;
                ch.vadose[c] += infiltrated / area;
                double runoff = total - infiltrated;
                if (runoff > 0 && !surface.add(ch.outletX[c], ch.outletZ[c], runoff, SurfaceWater.Source.RUNOFF)) {
                    rain -= runoff; // fell outside the known world
                }
            }
        }
    }

    /** Updates chunk activity levels and returns the chunks to step with their time steps. */
    private Map<SolverChunk, Double> activityAndSteps(double dt) {
        int n = grid.levels();
        Map<Long, Boolean> anomalous = new TreeMap<>();
        for (SolverChunk ch : grid.chunks()) {
            boolean hot = pendingHeatIn(ch);
            for (int c = 0; c < SolverChunk.AREA && !hot; c++) {
                if (!ch.exists[c]) continue;
                for (int k = 0; k < n; k++) {
                    double anomaly = ch.temperature[c * n + k] - grid.backgroundTemperature(grid.centerDepth(k));
                    if (Math.abs(anomaly) > config.hotAnomalyC) {
                        hot = true;
                        break;
                    }
                }
            }
            anomalous.put(SubsurfaceGrid.key(ch.cx, ch.cz), hot);
        }
        for (HeatSources s : sources.values()) {
            for (HeatSources.Vent v : s.vents()) {
                if (v.powerW() <= 0) continue;
                int gx = grid.solverCoord((int) Math.floor(v.x()));
                int gz = grid.solverCoord((int) Math.floor(v.z()));
                anomalous.put(SubsurfaceGrid.key(Math.floorDiv(gx, SolverChunk.SIZE), Math.floorDiv(gz, SolverChunk.SIZE)), true);
            }
        }
        Map<SolverChunk, Double> steps = new LinkedHashMap<>();
        for (SolverChunk ch : grid.chunks()) {
            if (!ch.anyExists()) continue;
            // HOT: anomalous or next to an anomaly (so heat never crosses into a chunk that is not
            // stepped with it); WARM: within two chunks; DORMANT otherwise.
            SolverChunk.Activity want = SolverChunk.Activity.DORMANT;
            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) {
                    if (!Boolean.TRUE.equals(anomalous.get(SubsurfaceGrid.key(ch.cx + dx, ch.cz + dz)))) continue;
                    SolverChunk.Activity level = Math.abs(dx) <= 1 && Math.abs(dz) <= 1
                            ? SolverChunk.Activity.HOT : SolverChunk.Activity.WARM;
                    if (level.ordinal() > want.ordinal()) want = level;
                }
            }
            if (want.ordinal() >= ch.activity.ordinal()) {
                ch.activity = want;
                ch.quietSteps = 0;
            } else if (++ch.quietSteps > config.demoteAfter) {
                ch.activity = SolverChunk.Activity.values()[ch.activity.ordinal() - 1];
                ch.quietSteps = 0;
            }
            switch (ch.activity) {
                case HOT -> steps.put(ch, dt);
                case WARM -> {
                    if (++ch.warmCounter >= config.warmEvery) {
                        ch.warmCounter = 0;
                        steps.put(ch, dt * config.warmEvery);
                    }
                }
                case DORMANT -> { }
            }
        }
        return steps;
    }

    private boolean pendingHeatIn(SolverChunk ch) {
        // Keys order by gx first; all columns of the chunk's gx range lie in [lo, hi].
        long lo = ((long) ch.cx * SolverChunk.SIZE) << 32;
        long hi = (((long) ch.cx * SolverChunk.SIZE + SolverChunk.SIZE) << 32) - 1;
        for (long key : pendingHeat.subMap(lo, true, hi, true).keySet()) {
            int gz = (int) key;
            if (Math.floorDiv(gz, SolverChunk.SIZE) == ch.cz) return true;
        }
        return false;
    }

    /** Per-cell energy (J) for this macro step: vent heat pipes plus pending hook energy. */
    private Map<SolverChunk, double[]> sourceEnergy(double dt, Map<SolverChunk, Double> steps) {
        int n = grid.levels();
        Map<SolverChunk, double[]> energy = new IdentityHashMap<>();
        double l = world.spec().metersPerColumn();
        for (HeatSources s : sources.values()) {
            for (HeatSources.Vent v : s.vents()) {
                if (v.powerW() <= 0) continue;
                double sigmaCols = Math.max(grid.ratio(), v.sigmaM() / l);
                int reach = (int) Math.ceil(3 * sigmaCols / grid.ratio()) + 1;
                int cgx = grid.solverCoord((int) Math.floor(v.x()));
                int cgz = grid.solverCoord((int) Math.floor(v.z()));
                List<double[]> targets = new ArrayList<>(); // gx, gz, weight
                double sum = 0;
                for (int gz = cgz - reach; gz <= cgz + reach; gz++) {
                    for (int gx = cgx - reach; gx <= cgx + reach; gx++) {
                        if (!grid.exists(gx, gz)) continue;
                        double ddx = ((gx + 0.5) * grid.ratio() - v.x()) / sigmaCols;
                        double ddz = ((gz + 0.5) * grid.ratio() - v.z()) / sigmaCols;
                        double w = Math.exp(-0.5 * (ddx * ddx + ddz * ddz));
                        if (w < 1e-4) continue;
                        targets.add(new double[] {gx, gz, w});
                        sum += w;
                    }
                }
                if (sum <= 0) continue;
                double total = v.powerW() * dt;
                for (double[] t : targets) {
                    SolverChunk ch = grid.chunkOf((int) t[0], (int) t[1]);
                    if (!steps.containsKey(ch)) continue; // dormant chunks receive nothing
                    int c = SolverChunk.column((int) t[0], (int) t[1]);
                    double[] e = energy.computeIfAbsent(ch, k -> new double[SolverChunk.AREA * n]);
                    double share = total * t[2] / sum;
                    distributeOverDepth(e, c, share, v.pipeDepthM());
                }
            }
        }
        // Hook energy is only consumed by chunks that step this time; others keep accumulating.
        List<Long> consumed = new ArrayList<>();
        for (Map.Entry<Long, double[]> p : pendingHeat.entrySet()) {
            int gx = (int) (p.getKey() >> 32);
            int gz = (int) (long) p.getKey();
            SolverChunk ch = grid.chunkOf(gx, gz);
            if (ch == null || !steps.containsKey(ch)) continue;
            int c = SolverChunk.column(gx, gz);
            if (!ch.exists[c]) continue;
            double[] e = energy.computeIfAbsent(ch, k -> new double[SolverChunk.AREA * n]);
            double[] src = p.getValue();
            for (int k = 0; k < n; k++) e[c * n + k] += src[k];
            consumed.add(p.getKey());
        }
        for (long k : consumed) pendingHeat.remove(k);
        return energy;
    }

    private void distributeOverDepth(double[] e, int c, double joules, double depth) {
        int n = grid.levels();
        double reach = Math.max(grid.thickness(0), depth);
        double placed = 0;
        for (int k = 0; k < n && grid.topDepth(k) < reach; k++) {
            double overlap = Math.min(reach, grid.topDepth(k) + grid.thickness(k)) - grid.topDepth(k);
            double part = joules * overlap / reach;
            e[c * n + k] += part;
            placed += part;
        }
        e[c * n] += joules - placed; // rounding remainder
    }

    // ── Spin-up ──

    /**
     * Runs heat and groundwater only (no surface water, rain or randomness) for {@code seconds} of
     * physical time in steps of at most {@code maxStep} seconds — e.g. to develop a hydrothermal
     * system around a new volcano before play starts.
     */
    public void equilibrate(double seconds, double maxStep) {
        prepare();
        double t = 0;
        while (t < seconds - 1e-9) {
            double dt = Math.min(maxStep, seconds - t);
            macroStep(dt, dt, false);
            t += dt;
        }
    }

    public void equilibrate(double seconds) {
        equilibrate(seconds, Math.max(config.macroStepSeconds, Math.min(seconds / 50, 30 * 86400.0)));
    }

    // ── Hooks ──

    @Override
    public void addSurfaceHeat(int x, int z, double joules) {
        if (!(joules > 0)) return;
        int gx = grid.solverCoord(x);
        int gz = grid.solverCoord(z);
        if (!grid.exists(gx, gz)) return;
        pendingHeat.computeIfAbsent(SubsurfaceGrid.key(gx, gz), k -> new double[grid.levels()])[0] += joules;
    }

    /** Heat flux (W/m²) from something resting on a column for {@code seconds}. */
    public void addSurfaceHeatFlux(int x, int z, double wattsPerM2, double seconds) {
        double l = world.spec().metersPerColumn();
        addSurfaceHeat(x, z, wattsPerM2 * l * l * seconds);
    }

    /** A point of a dike/sill sheet: column coordinates, elevation (m) and the sheet length it stands for (m). */
    public record SheetSample(double x, double z, double elevation, double lengthM) {}

    /**
     * Heat released by an intruded sheet {@code widthM} thick at {@code temperatureC} cooling to
     * the host rock: per sample {@code ρ·(c·ΔT + L)·width·length·Δx} joules into the cell containing
     * it (the sheet is assumed to extend across one solver column).
     */
    public void addSheetHeat(List<SheetSample> samples, double widthM, double temperatureC) {
        double rho = 2700;
        double c = 1100;
        for (SheetSample s : samples) {
            int x = (int) Math.floor(s.x());
            int z = (int) Math.floor(s.z());
            int gx = grid.solverCoord(x);
            int gz = grid.solverCoord(z);
            SolverChunk ch = grid.chunkOf(gx, gz);
            if (ch == null) continue;
            int col = SolverChunk.column(gx, gz);
            if (!ch.exists[col]) continue;
            int k = grid.levelAtDepth(ch.surfaceZ[col] - s.elevation());
            if (k < 0) continue;
            double ambient = ch.temperature[col * grid.levels() + k];
            double dT = Math.max(0, temperatureC - ambient);
            double joules = rho * (c * dT + config.latentHeatMeltJkg) * widthM * s.lengthM() * grid.dx();
            pendingHeat.computeIfAbsent(SubsurfaceGrid.key(gx, gz), key -> new double[grid.levels()])[k] += joules;
        }
    }

    /** Adds water at a column (a player's bucket, a host). */
    public void addWater(int x, int z, double volumeM3) {
        surface.add(x, z, volumeM3, SurfaceWater.Source.POURED);
    }

    /** Removes up to {@code volumeM3} of surface water at a column; returns the volume removed. */
    public double removeWater(int x, int z, double volumeM3) {
        SurfaceWater.Tile t = surface.tile(x, z);
        if (t == null || !(volumeM3 > 0)) return 0;
        int i = SurfaceWater.local(x, z);
        if (t.fixed(i)) {
            removed += volumeM3; // open water is unlimited
            surface.seaInflow += volumeM3;
            return volumeM3;
        }
        double available = t.depth[i] * surface.cellArea();
        double take = Math.min(available, volumeM3);
        t.depth[i] -= take / surface.cellArea();
        if (t.depth[i] < 1e-12) t.depth[i] = 0;
        removed += take;
        return take;
    }

    // ── Queries (HydrothermalField) ──

    private SolverChunk chunkAt(int x, int z) {
        return grid.chunkOf(grid.solverCoord(x), grid.solverCoord(z));
    }

    @Override
    public boolean known(int x, int z) {
        SolverChunk ch = chunkAt(x, z);
        return ch != null && ch.exists[SolverChunk.column(grid.solverCoord(x), grid.solverCoord(z))];
    }

    @Override
    public double temperatureC(int x, int z, double depthM) {
        SolverChunk ch = chunkAt(x, z);
        int c = SolverChunk.column(grid.solverCoord(x), grid.solverCoord(z));
        if (ch == null || !ch.exists[c]) return grid.backgroundTemperature(depthM);
        int n = grid.levels();
        if (depthM <= grid.centerDepth(0)) {
            // Between the surface (exchange temperature) and the first cell centre.
            double f = Math.max(0, depthM) / grid.centerDepth(0);
            return config.surfaceTemperatureC + f * (ch.temperature[c * n] - config.surfaceTemperatureC);
        }
        for (int k = 0; k < n - 1; k++) {
            if (depthM <= grid.centerDepth(k + 1)) {
                double f = (depthM - grid.centerDepth(k)) / (grid.centerDepth(k + 1) - grid.centerDepth(k));
                return ch.temperature[c * n + k] + f * (ch.temperature[c * n + k + 1] - ch.temperature[c * n + k]);
            }
        }
        return ch.temperature[c * n + n - 1];
    }

    @Override
    public double waterTableDepthM(int x, int z) {
        SolverChunk ch = chunkAt(x, z);
        int c = SolverChunk.column(grid.solverCoord(x), grid.solverCoord(z));
        if (ch == null || !ch.exists[c]) return config.initialWaterTableDepthM;
        double ground = world.isKnown(x, z) ? world.surfaceZ(x, z) : ch.surfaceZ[c];
        return ground - ch.head[c];
    }

    @Override
    public double steamFraction(int x, int z, double depthM) {
        SolverChunk ch = chunkAt(x, z);
        int c = SolverChunk.column(grid.solverCoord(x), grid.solverCoord(z));
        if (ch == null || !ch.exists[c]) return 0;
        int k = grid.levelAtDepth(depthM);
        return k < 0 ? 0 : ch.steam[c * grid.levels() + k];
    }

    @Override
    public double steamFluxKgPerSm2(int x, int z) {
        SolverChunk ch = chunkAt(x, z);
        int c = SolverChunk.column(grid.solverCoord(x), grid.solverCoord(z));
        if (ch == null || !ch.exists[c]) return 0;
        return ch.steamFlux[c] / grid.area();
    }

    @Override
    public double surfaceWaterDepthM(int x, int z) {
        return surface.depth(x, z);
    }

    /** Water-table elevation (m) under a column, {@code NaN} outside the model. */
    public double waterTableZ(int x, int z) {
        SolverChunk ch = chunkAt(x, z);
        int c = SolverChunk.column(grid.solverCoord(x), grid.solverCoord(z));
        return ch == null || !ch.exists[c] ? Double.NaN : ch.head[c];
    }

    /** Vadose-zone water (m equivalent depth) under a column. */
    public double vadoseM(int x, int z) {
        SolverChunk ch = chunkAt(x, z);
        int c = SolverChunk.column(grid.solverCoord(x), grid.solverCoord(z));
        return ch == null || !ch.exists[c] ? 0 : ch.vadose[c];
    }

    /** Cumulative spring discharge into the surface-water field (m³). */
    public double springDischarge() {
        return surface.springInflow;
    }

    /** Cumulative rain runoff into the surface-water field (m³). */
    public double runoff() {
        return surface.runoff;
    }

    SubsurfaceGrid grid() {
        return grid;
    }

    public WaterBudget budget() {
        return new WaterBudget(rain, surface.poured, surface.seededVolume, initialGroundwater,
                surface.seaInflow, deficit, surface.evaporated, boiled, surface.seaOutflow, seaGroundwater, removed,
                surface.rejected, surface.volume(), groundwater.storage());
    }

    /** Solver-column width (m) and number of levels, for hosts and tests. */
    public double solverSpacing() {
        return grid.dx();
    }

    public int levels() {
        return grid.levels();
    }

    public double levelCenterDepth(int k) {
        return grid.centerDepth(k);
    }

    public long macroSteps() {
        return macroSteps;
    }

    /** Last groundwater solve's SOR iterations. */
    public int lastGroundwaterIterations() {
        return groundwater.lastIterations;
    }

    /** Chunk counts by activity: {DORMANT, WARM, HOT}. */
    public int[] activityCounts() {
        int[] counts = new int[3];
        for (SolverChunk ch : grid.chunks()) if (ch.anyExists()) counts[ch.activity.ordinal()]++;
        return counts;
    }

    public int solverColumns() {
        int n = 0;
        for (SolverChunk ch : grid.chunks()) for (boolean e : ch.exists) if (e) n++;
        return n;
    }

    /** Summary for dashboards. */
    public record Snapshot(long macroSteps, int solverColumns, int hotChunks, int warmChunks, int dormantChunks,
            int surfaceWaterTiles, WaterBudget budget) {}

    @Override
    public Object snapshot() {
        int[] a = activityCounts();
        return new Snapshot(macroSteps, solverColumns(), a[2], a[1], a[0], surface.tileCount(), budget());
    }

    // ── Persistence ──

    @Override
    public void saveState(StateWriter out) {
        JsonObject json = out.json();
        json.addProperty("macroClock", macroClock);
        json.addProperty("macroSteps", macroSteps);
        JsonObject b = new JsonObject();
        b.addProperty("rain", rain);
        b.addProperty("boiled", boiled);
        b.addProperty("seaGroundwater", seaGroundwater);
        b.addProperty("deficit", deficit);
        b.addProperty("initialGroundwater", initialGroundwater);
        b.addProperty("removed", removed);
        b.addProperty("poured", surface.poured);
        b.addProperty("runoff", surface.runoff);
        b.addProperty("springInflow", surface.springInflow);
        b.addProperty("evaporated", surface.evaporated);
        b.addProperty("infiltrated", surface.infiltrated);
        b.addProperty("seaOutflow", surface.seaOutflow);
        b.addProperty("seaInflow", surface.seaInflow);
        b.addProperty("seeded", surface.seededVolume);
        b.addProperty("rejected", surface.rejected);
        json.add("budget", b);
        JsonArray seeded = new JsonArray();
        surface.seeded.forEach(seeded::add);
        json.add("seededTiles", seeded);
        JsonArray pending = new JsonArray();
        for (Map.Entry<Long, double[]> e : pendingHeat.entrySet()) {
            JsonArray entry = new JsonArray();
            entry.add(e.getKey());
            for (double v : e.getValue()) entry.add(v);
            pending.add(entry);
        }
        json.add("pendingHeat", pending);
        JsonArray activity = new JsonArray();
        StateWriter.Field solver = out.field("solver", SOLVER_SCHEMA);
        for (SolverChunk ch : grid.chunks()) {
            JsonArray a = new JsonArray();
            a.add(ch.cx);
            a.add(ch.cz);
            a.add(ch.activity.ordinal());
            a.add(ch.quietSteps);
            a.add(ch.warmCounter);
            activity.add(a);
            solver.put(ch.cx, ch.cz, new FieldChunk()
                    .booleans("initialized", ch.initialized.clone())
                    .doubles("temperature", ch.temperature.clone())
                    .doubles("steam", ch.steam.clone())
                    .doubles("head", ch.head.clone())
                    .doubles("vadose", ch.vadose.clone())
                    .doubles("steamFlux", ch.steamFlux.clone()));
        }
        json.add("chunks", activity);
        StateWriter.Field open = out.field("openWater", WATER_SCHEMA);
        for (Map.Entry<Long, double[]> e : surface.openWater.entrySet()) {
            open.put((int) (e.getKey() >> 32), (int) (long) e.getKey(), new FieldChunk().doubles("level", e.getValue().clone()));
        }
        StateWriter.Field water = out.field("surfaceWater", WATER_SCHEMA);
        for (SurfaceWater.Tile t : surface.tiles()) {
            water.put(t.tx, t.tz, new FieldChunk()
                    .doubles("depth", t.depth.clone())
                    .doubles("qEast", t.qEast.clone())
                    .doubles("qSouth", t.qSouth.clone()));
        }
    }

    @Override
    public void loadState(StateReader in) {
        JsonObject json = in.json();
        if (!json.has("macroClock")) return;
        macroClock = json.get("macroClock").getAsDouble();
        macroSteps = json.get("macroSteps").getAsLong();
        JsonObject b = json.getAsJsonObject("budget");
        rain = b.get("rain").getAsDouble();
        boiled = b.get("boiled").getAsDouble();
        seaGroundwater = b.get("seaGroundwater").getAsDouble();
        deficit = b.get("deficit").getAsDouble();
        initialGroundwater = b.get("initialGroundwater").getAsDouble();
        removed = b.get("removed").getAsDouble();
        surface.poured = b.get("poured").getAsDouble();
        surface.runoff = b.get("runoff").getAsDouble();
        surface.springInflow = b.get("springInflow").getAsDouble();
        surface.evaporated = b.get("evaporated").getAsDouble();
        surface.infiltrated = b.get("infiltrated").getAsDouble();
        surface.seaOutflow = b.get("seaOutflow").getAsDouble();
        surface.seaInflow = b.get("seaInflow").getAsDouble();
        surface.seededVolume = b.get("seeded").getAsDouble();
        surface.rejected = b.get("rejected").getAsDouble();
        surface.seeded.clear();
        for (JsonElement e : json.getAsJsonArray("seededTiles")) surface.seeded.add(e.getAsLong());
        pendingHeat.clear();
        for (JsonElement e : json.getAsJsonArray("pendingHeat")) {
            JsonArray entry = e.getAsJsonArray();
            double[] values = new double[entry.size() - 1];
            for (int i = 0; i < values.length; i++) values[i] = entry.get(i + 1).getAsDouble();
            pendingHeat.put(entry.get(0).getAsLong(), values);
        }
        Map<Long, JsonArray> activity = new TreeMap<>();
        for (JsonElement e : json.getAsJsonArray("chunks")) {
            JsonArray a = e.getAsJsonArray();
            activity.put(SubsurfaceGrid.key(a.get(0).getAsInt(), a.get(1).getAsInt()), a);
        }
        StateReader.Field solver = in.field("solver");
        if (solver != null) {
            if (solver.schemaVersion() != SOLVER_SCHEMA) {
                throw new IllegalStateException("Unsupported subsurface solver schema " + solver.schemaVersion());
            }
            for (StateReader.Entry entry : solver.chunks()) {
                SolverChunk ch = new SolverChunk(entry.chunkX(), entry.chunkZ(), grid.levels());
                FieldChunk d = entry.data();
                System.arraycopy(d.booleans("initialized"), 0, ch.initialized, 0, SolverChunk.AREA);
                System.arraycopy(d.doubles("temperature"), 0, ch.temperature, 0, ch.temperature.length);
                System.arraycopy(d.doubles("steam"), 0, ch.steam, 0, ch.steam.length);
                System.arraycopy(d.doubles("head"), 0, ch.head, 0, SolverChunk.AREA);
                System.arraycopy(d.doubles("vadose"), 0, ch.vadose, 0, SolverChunk.AREA);
                System.arraycopy(d.doubles("steamFlux"), 0, ch.steamFlux, 0, SolverChunk.AREA);
                JsonArray a = activity.get(SubsurfaceGrid.key(ch.cx, ch.cz));
                if (a != null) {
                    ch.activity = SolverChunk.Activity.values()[a.get(2).getAsInt()];
                    ch.quietSteps = a.get(3).getAsInt();
                    ch.warmCounter = a.get(4).getAsInt();
                }
                grid.putChunk(ch);
            }
        }
        surface.openWater.clear();
        StateReader.Field open = in.field("openWater");
        if (open != null) {
            for (StateReader.Entry entry : open.chunks()) {
                surface.openWater.put(SurfaceWater.key(entry.chunkX(), entry.chunkZ()), entry.data().doubles("level").clone());
            }
        }
        StateReader.Field water = in.field("surfaceWater");
        if (water != null) {
            if (water.schemaVersion() != WATER_SCHEMA) {
                throw new IllegalStateException("Unsupported surface water schema " + water.schemaVersion());
            }
            for (StateReader.Entry entry : water.chunks()) {
                SurfaceWater.Tile t = new SurfaceWater.Tile(entry.chunkX(), entry.chunkZ());
                FieldChunk d = entry.data();
                System.arraycopy(d.doubles("depth"), 0, t.depth, 0, SurfaceWater.AREA);
                System.arraycopy(d.doubles("qEast"), 0, t.qEast, 0, SurfaceWater.AREA);
                System.arraycopy(d.doubles("qSouth"), 0, t.qSouth, 0, SurfaceWater.AREA);
                surface.putTile(t);
            }
        }
        grid.invalidate();
    }
}
