package me.alex4386.typhon.engine.subsurface;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Heat transport in the subsurface grid.
 *
 * <p>Per macro step of length {@code dt} (operator split):
 * <ol>
 *   <li><b>Lateral</b> (explicit, sub-stepped for stability, applied to the temperatures): conduction between neighbouring columns at the same level, face
 *       conductance {@code dz·k_h} ({@code k_h} harmonic mean), plus upwind advection by the Darcy
 *       flux {@code q = −K ∂h/∂x} in levels below both water tables, in advective form (inflow
 *       mixes in {@code ρ_w c_w q (T_up − T)}; never heats a cell beyond its neighbours). Sub-stepped
 *       for stability.
 *   <li><b>Vertical</b> (implicit, Thomas algorithm): conduction between levels with conductivity
 *       enhanced by the porous-convection Nusselt number in the saturated permeable zone
 *       ({@code Ra = ρ_w c_w α K ΔT H / λ}, {@code Nu = Ra/Ra_c} above the critical value);
 *       Robin top boundary to the surface temperature ({@code h_s} for ground, {@code h_w} under
 *       water), Dirichlet bottom boundary at background geotherm plus chamber halo. Sources (vent
 *       heat pipes, lava/PDC heat, dike sheets) enter the right-hand side. Melting/crystallisation
 *       uses an apparent heat capacity between solidus and liquidus.
 *   <li><b>Boiling</b>: a saturated cell above the boiling point at its depth below the water table
 *       ({@code T_bp = 100 + 3·d^0.7}, fit to Haas 1971) converts the excess sensible heat into
 *       steam, which leaves the column (fumarole steam flux) and is taken from the aquifer's storage
 *       (the water table drops). Where the aquifer resupplies the boiled water the zone stays at
 *       the boiling point (liquid-dominated); where it cannot, the table falls below the heated
 *       zone, which then dries out and heats up (vapour-dominated). The steam fraction records the
 *       share of pore water flashed per step and decays once boiling stops.
 * </ol>
 *
 * <p><b>Chambers.</b> The cells a chamber sphere overlaps, and the bottom cells of the columns
 * whose base lies inside it, are heated towards the chamber temperature by at most the chamber's
 * wall heat power ({@link HeatSources.Chamber#wallPowerW()}): the energy comes from the magma
 * model's own budget, so a chamber cannot feed the hydrothermal system more heat than it loses.
 * Those columns have an insulated base (the chamber supplies their heat), and magma cells do not
 * boil (they hold no groundwater).
 */
final class SubsurfaceHeat {
    private final SubsurfaceGrid grid;
    private final SubsurfaceConfig config;

    private static final ThreadLocal<double[][]> SCRATCH = ThreadLocal.withInitial(() -> new double[5][0]);

    /** Water removed by boiling during the last step, per chunk and column (m³), read by groundwater. */
    final Map<SolverChunk, double[]> boiledVolume = new IdentityHashMap<>();
    double boiledTotal;

    SubsurfaceHeat(SubsurfaceGrid grid, SubsurfaceConfig config) {
        this.grid = grid;
        this.config = config;
        this.parallel = new Parallel(config);
    }

    /** Critical temperature of pure water (°C); above it there is no liquid–vapour transition. */
    static final double CRITICAL_TEMPERATURE_C = WaterSaturation.CRITICAL_TEMPERATURE_C;

    /**
     * Boiling point (°C) {@code depth} metres below a water table at elevation {@code waterTableZ}
     * (IAPWS-IF97 saturation at the hydrostatic pressure; see {@link WaterSaturation}).
     */
    static double boilingPoint(double depth, double waterTableZ) {
        return WaterSaturation.boilingPointC(depth, waterTableZ);
    }

    // ── Background, halo and initial state ──

    /**
     * Temperature anomaly (°C) at a point from the conductive halo of all chambers: a sphere of
     * radius {@code a} below an isothermal plane (image source),
     * {@code ΔT(P) = ΔT₀·(1/r − 1/r')/(1/a − 1/(2d))}, {@code ΔT₀ = T_c − T_bg(d)}.
     */
    double halo(List<HeatSources.Chamber> chambers, double x, double z, double elevation) {
        double l = grid.world().spec().metersPerColumn();
        double total = 0;
        for (HeatSources.Chamber ch : chambers) {
            double depth = Math.max(ch.radiusM() * 1.01, ch.surfaceElevation() - ch.centerElevation());
            double dx = (x - ch.x()) * l;
            double dz = (z - ch.z()) * l;
            double rho2 = dx * dx + dz * dz;
            double dy = elevation - ch.centerElevation();
            double r = Math.sqrt(rho2 + dy * dy);
            double pointDepth = ch.surfaceElevation() - elevation;
            double excessAtCentre = ch.temperatureC() - grid.backgroundTemperature(depth);
            if (excessAtCentre <= 0) continue;
            if (r <= ch.radiusM()) {
                total = Math.max(total, ch.temperatureC() - grid.backgroundTemperature(pointDepth));
                continue;
            }
            double imageElevation = 2 * ch.surfaceElevation() - ch.centerElevation();
            double dyImage = elevation - imageElevation;
            double rImage = Math.sqrt(rho2 + dyImage * dyImage);
            double norm = 1 / ch.radiusM() - 1 / (2 * depth);
            double f = (1 / r - 1 / rImage) / norm;
            if (f > 0) total += excessAtCentre * Math.min(1, f);
        }
        return total;
    }

    /** Whether a point lies inside a chamber; returns its temperature or NaN. */
    static double insideChamber(List<HeatSources.Chamber> chambers, double l, double x, double z, double elevation) {
        for (HeatSources.Chamber ch : chambers) {
            double dx = (x - ch.x()) * l;
            double dz = (z - ch.z()) * l;
            double dy = elevation - ch.centerElevation();
            if (dx * dx + dz * dz + dy * dy <= ch.radiusM() * ch.radiusM()) return ch.temperatureC();
        }
        return Double.NaN;
    }

    /** The chamber containing a point, or {@code null}. */
    static HeatSources.Chamber chamberAt(List<HeatSources.Chamber> chambers, double l, double x, double z,
            double elevation) {
        for (HeatSources.Chamber ch : chambers) {
            double dx = (x - ch.x()) * l;
            double dz = (z - ch.z()) * l;
            double dy = elevation - ch.centerElevation();
            if (dx * dx + dz * dz + dy * dy <= ch.radiusM() * ch.radiusM()) return ch;
        }
        return null;
    }

    /** Whether a chamber holds its cells at its temperature (unbounded heat supply). */
    static boolean fixedTemperature(HeatSources.Chamber chamber) {
        return chamber != null && Double.isNaN(chamber.wallPowerW());
    }

    /** Solver-column centre in column coordinates. */
    double centreX(SolverChunk ch, int c) {
        return (ch.gx(c) + 0.5) * grid.ratio();
    }

    double centreZ(SolverChunk ch, int c) {
        return (ch.gz(c) + 0.5) * grid.ratio();
    }

    /** Steady background + halo profile for a fresh column. */
    void initialize(SolverChunk ch, int c, List<HeatSources.Chamber> chambers) {
        int n = grid.levels();
        double x = centreX(ch, c);
        double z = centreZ(ch, c);
        for (int k = 0; k < n; k++) {
            double depth = grid.centerDepth(k);
            double elevation = ch.surfaceZ[c] - depth;
            ch.temperature[c * n + k] = grid.backgroundTemperature(depth) + halo(chambers, x, z, elevation);
            ch.steam[c * n + k] = 0;
        }
    }

    /**
     * Caps a freshly initialised column's water-saturated rock at the boiling point for its depth
     * below the water table. Liquid water cannot exist above that curve: a conductive halo laid
     * straight under a shallow water table would otherwise store superheated pore water that all
     * flashes on the first step. Liquid-dominated geothermal reservoirs follow this
     * boiling-point-for-depth profile (Grant &amp; Bixley 2011, Geothermal Reservoir Engineering, ch. 2).
     * Magma cells and dry rock above the water table keep their halo temperature.
     */
    void capToBoilingCurve(SolverChunk ch, int c, List<HeatSources.Chamber> chambers) {
        int n = grid.levels();
        double l = grid.world().spec().metersPerColumn();
        double x = centreX(ch, c);
        double z = centreZ(ch, c);
        for (int k = 0; k < n; k++) {
            int i = c * n + k;
            double elevation = grid.cellElevation(ch, c, k);
            if (elevation >= ch.head[c] || ch.porosity[i] <= 0) continue;
            if (chamberAt(chambers, l, x, z, ch.surfaceZ[c] - grid.centerDepth(k)) != null) continue;
            double cap = Math.min(CRITICAL_TEMPERATURE_C, boilingPoint(ch.head[c] - elevation, ch.head[c]));
            if (ch.temperature[i] > cap) ch.temperature[i] = cap;
        }
    }

    /**
     * Bottom boundary temperature: the chamber temperature where the column's base lies in a
     * fixed-temperature chamber, {@code NaN} (insulated; the chamber's bounded heat enters the bottom
     * cell instead) where it lies in a chamber with a wall power, else geotherm plus halo.
     */
    double bottomTemperature(SolverChunk ch, int c, List<HeatSources.Chamber> chambers) {
        double depth = grid.totalDepth();
        HeatSources.Chamber inside = chamberAt(chambers, grid.world().spec().metersPerColumn(), centreX(ch, c),
                centreZ(ch, c), ch.surfaceZ[c] - depth);
        if (inside != null) return fixedTemperature(inside) ? inside.temperatureC() : Double.NaN;
        return grid.backgroundTemperature(depth) + halo(chambers, centreX(ch, c), centreZ(ch, c), ch.surfaceZ[c] - depth);
    }

    // ── Step ──

    /**
     * Advances the given chunks by their own time steps.
     *
     * @param steps chunk → physical time step (s)
     * @param sources chunk → per-cell energy added this step (J), may be absent
     * @param waterDepth surface-water depth above a column (m), for the top boundary
     */
    void step(Map<SolverChunk, Double> steps, Map<SolverChunk, double[]> sources, List<HeatSources.Chamber> chambers,
            Groundwater groundwater, ColumnWater waterDepth) {
        boiledVolume.clear();
        boiledTotal = 0;
        currentChambers = chambers;
        if (steps.isEmpty()) return;
        int n = grid.levels();
        List<SolverChunk> chunks = new ArrayList<>(steps.keySet()); // key order
        // Sub-step for lateral stability with the largest step requested.
        double maxDt = 0;
        for (double dt : steps.values()) maxDt = Math.max(maxDt, dt);
        long t0 = System.nanoTime();
        double[][] before = new double[chunks.size()][];
        forEach(chunks, (idx, ch) -> before[idx] = ch.temperature.clone());
        double[] stable = new double[chunks.size()];
        forEach(chunks, (idx, ch) -> stable[idx] = stableLateralStep(ch));
        double minStable = Double.MAX_VALUE;
        for (double v : stable) minStable = Math.min(minStable, v);
        int substeps = Math.max(1, (int) Math.ceil(maxDt / minStable));
        long t1 = System.nanoTime();
        lastSubsteps = substeps;
        Map<SolverChunk, double[]> lateral = new IdentityHashMap<>();
        for (SolverChunk ch : chunks) lateral.put(ch, new double[SolverChunk.AREA * n]);
        // Net groundwater volume each cell receives laterally over the step (m³); the vertical solve
        // routes it up or down its column.
        Map<SolverChunk, double[]> converging = new IdentityHashMap<>();
        for (SolverChunk ch : chunks) converging.put(ch, new double[SolverChunk.AREA * n]);
        // Lateral transport: explicit sub-steps, applied directly to the temperatures. Each column
        // gathers the energy through its own four faces (computed antisymmetrically, so heat is
        // conserved exactly), which makes chunks independent and safe to process in parallel.
        double area = grid.area();
        for (int s = 0; s < substeps; s++) {
            int sub = substeps;
            forEach(chunks, (idx, ch) -> lateralEnergy(ch, steps.get(ch) / sub, steps, lateral.get(ch),
                    converging.get(ch)));
            forEach(chunks, (idx, ch) -> {
                double[] lat = lateral.get(ch);
                for (int c = 0; c < SolverChunk.AREA; c++) {
                    if (!ch.exists[c]) continue;
                    for (int k = 0; k < n; k++) {
                        int i = c * n + k;
                        if (lat[i] != 0) ch.temperature[i] += lat[i] / capacity(ch, c, k, area * grid.thickness(k));
                    }
                }
            });
        }
        long t2 = System.nanoTime();
        Map<SolverChunk, double[]> allSources = chamberHeat(chunks, steps, sources, chambers);
        // Vertical conduction and sources: one implicit step per column.
        double[] bottomPerChunk = new double[chunks.size()];
        forEach(chunks, (idx, ch) -> {
            double[] src = allSources.get(ch);
            double[] net = converging.get(ch);
            double dt = steps.get(ch);
            double bottom = 0;
            for (int c = 0; c < SolverChunk.AREA; c++) {
                if (!ch.exists[c]) continue;
                bottom += vertical(ch, c, dt, net, src, 1.0, chambers, waterDepth.depth(ch, c));
            }
            bottomPerChunk[idx] = bottom;
        });
        bottomInflow = 0;
        for (double v : bottomPerChunk) bottomInflow += v;
        long t3 = System.nanoTime();
        double[][] boiledPerChunk = new double[chunks.size()][];
        forEach(chunks, (idx, ch) -> {
            double dt = steps.get(ch);
            double[] boiled = new double[SolverChunk.AREA];
            for (int c = 0; c < SolverChunk.AREA; c++) {
                if (ch.exists[c]) boil(ch, c, dt, boiled);
            }
            boiledPerChunk[idx] = boiled;
            double change = 0;
            double[] old = before[idx];
            for (int i = 0; i < old.length; i++) change = Math.max(change, Math.abs(ch.temperature[i] - old[i]));
            ch.lastChange = change;
        });
        for (int idx = 0; idx < chunks.size(); idx++) {
            boiledVolume.put(chunks.get(idx), boiledPerChunk[idx]);
            for (double v : boiledPerChunk[idx]) boiledTotal += v;
        }
        long t4 = System.nanoTime();
        timings[0] = t1 - t0;
        timings[1] = t2 - t1;
        timings[2] = t3 - t2;
        timings[3] = t4 - t3;
    }

    /** Heat capacity of a whole cell (J/K), including pore water and latent heat of melting. */
    double capacityOf(SolverChunk ch, int c, int k) {
        return capacity(ch, c, k, grid.area() * grid.thickness(k));
    }

    /** Sensible heat stored in the grid above 0 °C (J), for energy-budget diagnostics. */
    double heatContent() {
        double total = 0;
        int n = grid.levels();
        double area = grid.area();
        for (SolverChunk ch : grid.chunks()) {
            for (int c = 0; c < SolverChunk.AREA; c++) {
                if (!ch.exists[c]) continue;
                for (int k = 0; k < n; k++) {
                    total += capacity(ch, c, k, area * grid.thickness(k)) * ch.temperature[c * n + k];
                }
            }
        }
        return total;
    }

    /** Heat that entered through the bottom boundary during the last step (J; negative = lost). */
    double bottomInflow;

    /** Chambers of the current step (boiling skips their cells). */
    private List<HeatSources.Chamber> currentChambers = List.of();

    /** Heat the chambers gave to the grid during the last step (J), per chamber in list order. */
    double[] chamberDelivered = new double[0];
    /** Heat the overlapped cells would have absorbed to reach the chamber temperature (J). */
    double[] chamberDemand = new double[0];

    /**
     * Adds each chamber's heat to the cells it overlaps (and the bottom cells of the columns whose
     * base lies inside it). A cell's demand is the power that would bring it to the chamber
     * temperature within its step; the chamber delivers that demand scaled down so the total never
     * exceeds its wall power times the share of its surface above the grid bottom. Sequential in
     * chunk-key order, so the result does not depend on the thread count.
     */
    private Map<SolverChunk, double[]> chamberHeat(List<SolverChunk> chunks, Map<SolverChunk, Double> steps,
            Map<SolverChunk, double[]> sources, List<HeatSources.Chamber> chambers) {
        chamberDelivered = new double[chambers.size()];
        chamberDemand = new double[chambers.size()];
        if (chambers.isEmpty()) return sources;
        Map<SolverChunk, double[]> merged = new IdentityHashMap<>(sources);
        int n = grid.levels();
        double l = grid.world().spec().metersPerColumn();
        double area = grid.area();
        double bottomDepth = grid.totalDepth();
        List<double[]> demands = new ArrayList<>(); // {chunk index, cell, power, dt}
        for (int ci = 0; ci < chambers.size(); ci++) {
            HeatSources.Chamber chamber = chambers.get(ci);
            demands.clear();
            double total = 0;
            double r2 = chamber.radiusM() * chamber.radiusM();
            for (int idx = 0; idx < chunks.size(); idx++) {
                SolverChunk ch = chunks.get(idx);
                double dt = steps.get(ch);
                for (int c = 0; c < SolverChunk.AREA; c++) {
                    if (!ch.exists[c]) continue;
                    double dx = (centreX(ch, c) - chamber.x()) * l;
                    double dz = (centreZ(ch, c) - chamber.z()) * l;
                    double h2 = dx * dx + dz * dz;
                    if (h2 > r2) continue;
                    double dyBase = ch.surfaceZ[c] - bottomDepth - chamber.centerElevation();
                    boolean baseInside = h2 + dyBase * dyBase <= r2;
                    for (int k = 0; k < n; k++) {
                        double dy = ch.surfaceZ[c] - grid.centerDepth(k) - chamber.centerElevation();
                        boolean inside = h2 + dy * dy <= r2 || (k == n - 1 && baseInside);
                        if (!inside) continue;
                        int i = c * n + k;
                        double excess = chamber.temperatureC() - ch.temperature[i];
                        if (excess <= 0) continue;
                        double power = capacity(ch, c, k, area * grid.thickness(k)) * excess / dt;
                        demands.add(new double[] {idx, i, power, dt});
                        total += power;
                    }
                }
            }
            if (total <= 0 || fixedTemperature(chamber)) continue; // fixed-temperature cells: see vertical()
            double budget = chamber.wallPowerW();
            double scale = 1;
            if (!Double.isNaN(budget)) {
                double available = Math.max(0, budget) * shareAboveGridBottom(chamber, bottomDepth);
                scale = Math.min(1, available / total);
            }
            for (double[] d : demands) {
                SolverChunk ch = chunks.get((int) d[0]);
                double[] src = merged.get(ch);
                if (src == null || src == sources.get(ch)) {
                    src = src == null ? new double[SolverChunk.AREA * n] : src.clone();
                    merged.put(ch, src);
                }
                double energy = scale * d[2] * d[3];
                src[(int) d[1]] += energy;
                chamberDelivered[ci] += energy;
                chamberDemand[ci] += d[2] * d[3];
            }
        }
        return merged;
    }

    /**
     * Share of a sphere's surface above the grid bottom: a spherical cap of height {@code h} has area
     * {@code 2πah}, so the share is {@code h/(2a)}.
     */
    static double shareAboveGridBottom(HeatSources.Chamber chamber, double bottomDepth) {
        double topDepth = chamber.surfaceElevation() - chamber.centerElevation() - chamber.radiusM();
        double h = bottomDepth - topDepth;
        return Math.max(0, Math.min(1, h / (2 * chamber.radiusM())));
    }

    /** Wall time (ns) of the last step: stability check, lateral transport, vertical solve, boiling. */
    final long[] timings = new long[4];
    int lastSubsteps;

    /** Surface-water depth above a solver column (m). */
    interface ColumnWater {
        double depth(SolverChunk ch, int c);
    }

    private double stableLateralStep(SolverChunk ch) {
        double dx = grid.dx();
        double minStep = Double.MAX_VALUE;
        int n = grid.levels();
        for (int c = 0; c < SolverChunk.AREA; c++) {
            if (!ch.exists[c]) continue;
            for (int k = 0; k < n; k++) {
                int i = c * n + k;
                double cap = Math.max(1e3, ch.heatCapacity[i] + ch.porosity[i] * SubsurfaceGrid.WATER_DENSITY
                        * SubsurfaceGrid.WATER_HEAT_CAPACITY);
                double kappa = ch.conductivity[i] / cap;
                // Conduction only: advection is flux-limited in lateralEnergy, so very permeable
                // ground (scoria, pumice) cannot force thousands of sub-steps.
                double rate = 4 * kappa / (dx * dx);
                if (rate > 0) minStep = Math.min(minStep, 0.4 / rate);
            }
        }
        return minStep;
    }

    private double capacity(SolverChunk ch, int c, int k, double volume) {
        int i = ch.levels * c + k;
        double t = ch.temperature[i];
        double saturated = belowWaterTable(ch, c, k) ? 1 - ch.steam[i] : 0;
        double perVolume = ch.heatCapacity[i]
                + ch.porosity[i] * saturated * SubsurfaceGrid.WATER_DENSITY * SubsurfaceGrid.WATER_HEAT_CAPACITY;
        double sol = ch.solidus[i];
        double liq = ch.liquidus[i];
        if (!Double.isNaN(sol) && !Double.isNaN(liq) && liq > sol && t > sol && t < liq) {
            perVolume += ch.density[i] * config.latentHeatMeltJkg / (liq - sol);
        }
        return Math.max(1e3, perVolume) * volume;
    }

    private boolean belowWaterTable(SolverChunk ch, int c, int k) {
        return grid.cellElevation(ch, c, k) < ch.head[c];
    }

    private static final int[][] FACES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * Lateral conduction + Darcy advection energy (J) over {@code dt} into each column of a chunk,
     * gathered through its four faces.
     */
    private void lateralEnergy(SolverChunk ch, double dt, Map<SolverChunk, Double> stepped, double[] mine,
            double[] converging) {
        java.util.Arrays.fill(mine, 0);
        int n = grid.levels();
        double dx = grid.dx();
        Double myStep = stepped.get(ch);
        for (int c = 0; c < SolverChunk.AREA; c++) {
            if (!ch.exists[c]) continue;
            int gx = ch.gx(c);
            int gz = ch.gz(c);
            for (int[] face : FACES) {
                int ngx = gx + face[0];
                int ngz = gz + face[1];
                SolverChunk other = grid.neighbourChunk(ch, ngx, ngz);
                if (other == null) continue;
                int oc = SolverChunk.column(ngx, ngz);
                if (!other.exists[oc]) continue;
                // Faces exchange heat only between chunks stepped together with the same time step;
                // others (dormant, or warm next to hot) are treated as insulating for this step.
                if (other != ch && !myStep.equals(stepped.get(other))) continue;
                double headGradient = (other.head[oc] - ch.head[c]) / dx;
                for (int k = 0; k < n; k++) {
                    int i = c * n + k;
                    int j = oc * n + k;
                    double conductance = grid.thickness(k) * harmonic(ch.conductivity[i], other.conductivity[j]);
                    double ti = ch.temperature[i];
                    double tj = other.temperature[j];
                    double energy = conductance * (tj - ti) * dt; // conduction into ch
                    if (headGradient != 0 && belowWaterTable(ch, c, k) && belowWaterTable(other, oc, k)
                            && !magma(ch, c, k) && !magma(other, oc, k)) {
                        // Advection: heat carried by groundwater between the columns (+ = ch → other).
                        double kHyd = harmonic(ch.hydraulicK[i], other.hydraulicK[j]);
                        double flow = -kHyd * headGradient * grid.thickness(k) * dx; // m³/s
                        // Limit the advected heat to a fifth of the smaller cell's heat capacity per
                        // sub-step (upwind Courant ≤ 0.2 per face): stable and still symmetric.
                        double volume = grid.area() * grid.thickness(k);
                        double limit = 0.2 * Math.min(capacity(ch, c, k, volume), capacity(other, oc, k, volume))
                                / (SubsurfaceGrid.WATER_DENSITY * SubsurfaceGrid.WATER_HEAT_CAPACITY * dt);
                        if (flow > limit) flow = limit;
                        else if (flow < -limit) flow = -limit;
                        // Advective (upwind) form: inflowing water mixes in at its temperature and
                        // displaces water at the cell's own. Where the flow converges (or diverges)
                        // the displaced water rises (sinks) through the column: vertical() carries
                        // it, so heat is conserved and no cell is heated beyond its neighbours.
                        if (flow < 0) {
                            energy += SubsurfaceGrid.WATER_DENSITY * SubsurfaceGrid.WATER_HEAT_CAPACITY * -flow * (tj - ti) * dt;
                        }
                        converging[i] -= flow * dt;
                    }
                    mine[i] += energy;
                }
            }
        }
    }

    // ── Parallelism ──

    private interface ChunkWork {
        void run(int index, SolverChunk chunk);
    }

    /** The engine's shared executor (see {@link Parallel}). */
    final Parallel parallel;

    /**
     * Runs {@code work} for every chunk on the shared executor. Work items only write their own chunk
     * (or their own slot), so results do not depend on the number of threads.
     */
    private void forEach(List<SolverChunk> chunks, ChunkWork work) {
        parallel.forEach(chunks, work::run);
    }

    static double harmonic(double a, double b) {
        return a + b > 0 ? 2 * a * b / (a + b) : 0;
    }

    /**
     * Implicit vertical conduction of one column (Thomas algorithm), with upwind advection of the
     * groundwater that converged on ({@code converging} > 0) or diverged from its saturated levels:
     * it rises (sinks) to the top of each contiguous flowing segment, where it leaves (the water
     * table, springs, boiling) or is resupplied at that cell's temperature.
     */
    private double vertical(SolverChunk ch, int c, double dt, double[] converging, double[] sources, double sourceShare,
            List<HeatSources.Chamber> chambers, double waterDepth) {
        int n = grid.levels();
        double area = grid.area();
        double[][] scratch = SCRATCH.get();
        if (scratch[0].length != n) {
            for (int a = 0; a < scratch.length; a++) scratch[a] = new double[n];
        }
        double[] lower = scratch[0];
        double[] diag = scratch[1];
        double[] upper = scratch[2];
        double[] rhs = scratch[3];
        double[] g = scratch[4]; // conductance between k and k+1 (W/K)
        java.util.Arrays.fill(lower, 0);
        java.util.Arrays.fill(upper, 0);
        double nusselt = nusselt(ch, c);
        double waterTableDepth = ch.surfaceZ[c] - ch.head[c];
        for (int k = 0; k < n - 1; k++) {
            double ka = effectiveConductivity(ch, c, k, nusselt, waterTableDepth);
            double kb = effectiveConductivity(ch, c, k + 1, nusselt, waterTableDepth);
            double resistance = grid.thickness(k) / (2 * ka) + grid.thickness(k + 1) / (2 * kb);
            g[k] = area / resistance;
        }
        double exchange = waterDepth > 0.05 || ch.sea[c] ? config.waterExchangeWm2K : config.surfaceExchangeWm2K;
        double k0 = effectiveConductivity(ch, c, 0, nusselt, waterTableDepth);
        double gTop = area / (grid.thickness(0) / (2 * k0) + 1 / exchange);
        // The base conducts into unmodelled deep rock: plain conductivity, no convective boost (a
        // Nusselt-enhanced link to a fixed-temperature reservoir is an unbounded heat source).
        double kn = Math.max(1e-3, ch.conductivity[c * n + n - 1]);
        double gBottom = area / (grid.thickness(n - 1) / (2 * kn));
        double tBottom = bottomTemperature(ch, c, chambers);
        double l = grid.world().spec().metersPerColumn();
        double x = centreX(ch, c);
        double z = centreZ(ch, c);
        for (int k = 0; k < n; k++) {
            int i = c * n + k;
            double cap = capacity(ch, c, k, area * grid.thickness(k));
            double d = cap;
            double r = cap * ch.temperature[i] + (sources != null ? sources[i] * sourceShare : 0);
            if (k > 0) {
                d += dt * g[k - 1];
                lower[k] = -dt * g[k - 1];
            }
            if (k < n - 1) {
                d += dt * g[k];
                upper[k] = -dt * g[k];
            }
            if (k == 0) {
                d += dt * gTop;
                r += dt * gTop * config.surfaceTemperatureC;
            }
            if (k == n - 1 && !Double.isNaN(tBottom)) {
                d += dt * gBottom;
                r += dt * gBottom * tBottom;
            }
            if (!chambers.isEmpty()) {
                HeatSources.Chamber inside = chamberAt(chambers, l, x, z, ch.surfaceZ[c] - grid.centerDepth(k));
                if (fixedTemperature(inside)) {
                    d = 1;
                    r = inside.temperatureC();
                    if (k > 0) lower[k] = 0;
                    if (k < n - 1) upper[k] = 0;
                }
            }
            diag[k] = d;
            rhs[k] = r;
        }
        if (converging != null) advectVertically(ch, c, converging, diag, lower, upper);
        // Thomas algorithm
        for (int k = 1; k < n; k++) {
            double m = lower[k] / diag[k - 1];
            diag[k] -= m * upper[k - 1];
            rhs[k] -= m * rhs[k - 1];
        }
        int base = c * n;
        ch.temperature[base + n - 1] = rhs[n - 1] / diag[n - 1];
        for (int k = n - 2; k >= 0; k--) {
            ch.temperature[base + k] = (rhs[k] - upper[k] * ch.temperature[base + k + 1]) / diag[k];
        }
        // Heat that entered through the bottom boundary during the (implicit) step.
        return Double.isNaN(tBottom) ? 0 : dt * gBottom * (tBottom - ch.temperature[base + n - 1]);
    }

    /**
     * Adds the column's vertical groundwater advection to the tridiagonal system. {@code flux} is
     * the volume crossing the top face of a level upwards over the step (negative = downwards):
     * the sum of what converged on the flowing levels below it. Upwind: the water carries the
     * temperature of the level it leaves. A level also loses the {@code ρc·V·T} that the advective
     * lateral form left out of its convergence, so per level only inflows at other temperatures
     * change it (bounded, conservative).
     */
    private void advectVertically(SolverChunk ch, int c, double[] converging, double[] diag, double[] lower,
            double[] upper) {
        int n = grid.levels();
        double rc = SubsurfaceGrid.WATER_DENSITY * SubsurfaceGrid.WATER_HEAT_CAPACITY;
        double flux = 0;
        for (int k = n - 1; k >= 0; k--) {
            int i = c * n + k;
            boolean flowing = belowWaterTable(ch, c, k) && !magma(ch, c, k);
            if (!flowing) {
                flux = 0;
                continue;
            }
            double v = converging[i];
            flux += v;
            diag[k] -= rc * v;
            boolean aboveFlows = k > 0 && belowWaterTable(ch, c, k - 1) && !magma(ch, c, k - 1);
            if (!aboveFlows) {
                // Top of the segment: up-flow leaves at this level's temperature; down-flow is
                // resupplied at it. Either way: diag += ρc·flux.
                diag[k] += rc * flux;
                flux = 0;
            } else if (flux > 0) {
                diag[k] += rc * flux;      // leaves k ...
                upper[k - 1] -= rc * flux; // ... into k−1 at T_k
            } else if (flux < 0) {
                diag[k - 1] -= rc * flux;  // leaves k−1 ...
                lower[k] += rc * flux;     // ... into k at T_{k−1}
            }
        }
    }

    private double effectiveConductivity(SolverChunk ch, int c, int k, double nusselt, double waterTableDepth) {
        int i = c * grid.levels() + k;
        double kk = Math.max(1e-3, ch.conductivity[i]);
        if (nusselt > 1 && grid.centerDepth(k) > waterTableDepth && permeable(ch, c, k)) kk *= nusselt;
        return kk;
    }

    /** Groundwater can flow through the cell: permeable rock, not magma. */
    private boolean permeable(SolverChunk ch, int c, int k) {
        return ch.hydraulicK[c * grid.levels() + k] > 1e-9 && !magma(ch, c, k);
    }

    /**
     * The cell lies in a chamber. Magma (and its ductile, > ~400 °C margin; Fournier 1999) holds no
     * circulating groundwater: hydrothermal convection cools a chamber across its roof by conduction
     * and must not reach into it, or it quenches the grid's magma faster than the chamber supplies.
     */
    private boolean magma(SolverChunk ch, int c, int k) {
        if (currentChambers.isEmpty()) return false;
        double l = grid.world().spec().metersPerColumn();
        return !Double.isNaN(insideChamber(currentChambers, l, centreX(ch, c), centreZ(ch, c),
                ch.surfaceZ[c] - grid.centerDepth(k)));
    }

    /** Porous-convection Nusselt number of the saturated permeable zone below the water table. */
    double nusselt(SolverChunk ch, int c) {
        int n = grid.levels();
        double wtDepth = ch.surfaceZ[c] - ch.head[c];
        int top = -1;
        int bottom = -1;
        double h = 0;
        double kSum = 0;
        double lambdaSum = 0;
        for (int k = 0; k < n; k++) {
            int i = c * n + k;
            if (grid.centerDepth(k) <= wtDepth) continue;
            if (!permeable(ch, c, k)) {
                if (top >= 0) break;
                continue;
            }
            if (top < 0) top = k;
            bottom = k;
            double dz = grid.thickness(k);
            h += dz;
            kSum += ch.hydraulicK[i] * dz;
            lambdaSum += ch.conductivity[i] * dz;
        }
        if (top < 0 || bottom <= top) return 1;
        double dT = ch.temperature[c * n + bottom] - ch.temperature[c * n + top];
        if (dT <= 0) return 1;
        double kMean = kSum / h;
        double lambda = Math.max(1e-3, lambdaSum / h);
        double ra = SubsurfaceGrid.WATER_DENSITY * SubsurfaceGrid.WATER_HEAT_CAPACITY * config.waterExpansivity * kMean
                * dT * h / lambda;
        return Math.max(1, Math.min(config.maxNusselt, ra / config.criticalRayleigh));
    }

    /** Boiling of saturated cells hotter than the boiling point at their depth below the water table. */
    private void boil(SolverChunk ch, int c, double dt, double[] boiled) {
        int n = grid.levels();
        double area = grid.area();
        double collapse = Math.exp(-dt / config.steamCollapseSeconds);
        double steamMass = 0;
        double l = grid.world().spec().metersPerColumn();
        double x = centreX(ch, c);
        double z = centreZ(ch, c);
        for (int k = 0; k < n; k++) {
            int i = c * n + k;
            double elevation = grid.cellElevation(ch, c, k);
            boolean magma = !currentChambers.isEmpty()
                    && !Double.isNaN(insideChamber(currentChambers, l, x, z, ch.surfaceZ[c] - grid.centerDepth(k)));
            if (elevation >= ch.head[c] || ch.porosity[i] <= 0 || magma) {
                ch.steam[i] *= collapse;
                continue;
            }
            double t = ch.temperature[i];
            if (t <= 60) { // below any boiling point on Earth's surface (≈ 70 °C at 9 km): skip the curve
                ch.steam[i] *= collapse;
                continue;
            }
            double depth = ch.head[c] - elevation;
            double tbp = boilingPoint(depth, ch.head[c]);
            // No boiling beyond water's critical point (supercritical fluid) or in (partly) molten rock.
            boolean noPhaseChange = tbp >= CRITICAL_TEMPERATURE_C
                    || (!Double.isNaN(ch.solidus[i]) && t >= ch.solidus[i]);
            if (t <= tbp || noPhaseChange) {
                ch.steam[i] *= collapse;
                continue;
            }
            double volume = area * grid.thickness(k);
            double cap = capacity(ch, c, k, volume);
            double excess = cap * (t - tbp); // J
            // Below the water table the pores are refilled by the aquifer (the boiled water is taken
            // from its storage, lowering the table), so a whole pore volume can flash per step.
            double water = ch.porosity[i] * volume * SubsurfaceGrid.WATER_DENSITY; // kg
            // The flashed pores refill only as fast as the rock lets water in: Darcy inflow under a
            // unit (gravity) gradient through the cell's top face, ρ_w·K·A·dt. Tight rock heats up
            // instead (conduction-dominated); permeable ground boils at the rate it is resupplied.
            double resupply = SubsurfaceGrid.WATER_DENSITY * ch.hydraulicK[i] * area * dt;
            double mass = Math.min(Math.min(water, resupply), excess / config.latentHeatVaporJkg);
            if (mass <= 0) continue;
            // The phase change takes the latent heat. The water leaves at the cell's temperature and
            // the groundwater that replaces it is mixed in by the (advective-form) lateral term.
            ch.temperature[i] = t - mass * config.latentHeatVaporJkg / cap;
            ch.steam[i] = Math.max(ch.steam[i] * collapse, mass / water);
            steamMass += mass;
        }
        ch.steamFlux[c] = steamMass / dt;
        double vol = steamMass / SubsurfaceGrid.WATER_DENSITY;
        boiled[c] = vol;
    }

    /** Chunks in key order that need stepping, helper for callers. */
    static List<SolverChunk> list(Iterable<SolverChunk> chunks) {
        List<SolverChunk> list = new ArrayList<>();
        for (SolverChunk ch : chunks) list.add(ch);
        return list;
    }
}
