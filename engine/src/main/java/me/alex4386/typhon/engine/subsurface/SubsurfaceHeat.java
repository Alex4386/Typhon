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
 *   <li><b>Lateral</b> (explicit): conduction between neighbouring columns at the same level, face
 *       conductance {@code dz·k_h} ({@code k_h} harmonic mean), plus upwind advection by the Darcy
 *       flux {@code q = −K ∂h/∂x} in levels below both water tables. Sub-stepped for stability.
 *   <li><b>Vertical</b> (implicit, Thomas algorithm): conduction between levels with conductivity
 *       enhanced by the porous-convection Nusselt number in the saturated permeable zone
 *       ({@code Ra = ρ_w c_w α K ΔT H / λ}, {@code Nu = Ra/Ra_c} above the critical value);
 *       Robin top boundary to the surface temperature ({@code h_s} for ground, {@code h_w} under
 *       water), Dirichlet bottom boundary at background geotherm plus chamber halo. Sources (vent
 *       heat pipes, lava/PDC heat, dike sheets) enter the right-hand side. Melting/crystallisation
 *       uses an apparent heat capacity between solidus and liquidus.
 *   <li><b>Boiling</b>: a saturated cell above the boiling point at its depth below the water table
 *       ({@code T_bp = 100 + 3·d^0.7}, fit to Haas 1971) converts the excess sensible heat into
 *       steam, which leaves the column (fumarole steam flux) and lowers the water table.
 * </ol>
 *
 * <p>Cells inside a chamber sphere are held at the chamber temperature.
 */
final class SubsurfaceHeat {
    private final SubsurfaceGrid grid;
    private final SubsurfaceConfig config;

    /** Water removed by boiling during the last step, per chunk and column (m³), read by groundwater. */
    final Map<SolverChunk, double[]> boiledVolume = new IdentityHashMap<>();
    double boiledTotal;

    SubsurfaceHeat(SubsurfaceGrid grid, SubsurfaceConfig config) {
        this.grid = grid;
        this.config = config;
    }

    /** Boiling point (°C) at {@code depth} metres below the water table (hydrostatic, pure water). */
    static double boilingPoint(double depth) {
        return 100 + 3.0 * StrictMath.pow(Math.max(0, depth), 0.7);
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

    double bottomTemperature(SolverChunk ch, int c, List<HeatSources.Chamber> chambers) {
        double depth = grid.totalDepth();
        double inside = insideChamber(chambers, grid.world().spec().metersPerColumn(), centreX(ch, c), centreZ(ch, c),
                ch.surfaceZ[c] - depth);
        if (!Double.isNaN(inside)) return inside;
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
        if (steps.isEmpty()) return;
        int n = grid.levels();
        // Sub-step for lateral stability with the largest step requested.
        double maxDt = 0;
        for (double dt : steps.values()) maxDt = Math.max(maxDt, dt);
        int substeps = Math.max(1, (int) Math.ceil(maxDt / stableLateralStep(steps.keySet())));
        Map<SolverChunk, double[]> lateral = new IdentityHashMap<>();
        for (SolverChunk ch : steps.keySet()) lateral.put(ch, new double[SolverChunk.AREA * n]);
        for (int s = 0; s < substeps; s++) {
            for (double[] a : lateral.values()) java.util.Arrays.fill(a, 0);
            for (Map.Entry<SolverChunk, Double> e : steps.entrySet()) {
                lateralEnergy(e.getKey(), e.getValue() / substeps, steps, lateral, groundwater);
            }
            for (Map.Entry<SolverChunk, Double> e : steps.entrySet()) {
                SolverChunk ch = e.getKey();
                double dt = e.getValue() / substeps;
                double[] src = sources.get(ch);
                double[] lat = lateral.get(ch);
                for (int c = 0; c < SolverChunk.AREA; c++) {
                    if (!ch.exists[c]) continue;
                    vertical(ch, c, dt, lat, src, s == 0 ? 1.0 : 0.0, chambers, waterDepth.depth(ch, c));
                }
            }
        }
        for (Map.Entry<SolverChunk, Double> e : steps.entrySet()) {
            SolverChunk ch = e.getKey();
            double dt = e.getValue();
            double[] boiled = new double[SolverChunk.AREA];
            for (int c = 0; c < SolverChunk.AREA; c++) {
                if (ch.exists[c]) boil(ch, c, dt, boiled);
            }
            boiledVolume.put(ch, boiled);
        }
    }

    /** Surface-water depth above a solver column (m). */
    interface ColumnWater {
        double depth(SolverChunk ch, int c);
    }

    private double stableLateralStep(Iterable<SolverChunk> chunks) {
        double dx = grid.dx();
        double minStep = Double.POSITIVE_INFINITY;
        int n = grid.levels();
        for (SolverChunk ch : chunks) {
            for (int c = 0; c < SolverChunk.AREA; c++) {
                if (!ch.exists[c]) continue;
                double gradient = headGradient(ch, c);
                for (int k = 0; k < n; k++) {
                    int i = c * n + k;
                    double cap = Math.max(1e3, ch.heatCapacity[i] + ch.porosity[i] * SubsurfaceGrid.WATER_DENSITY
                            * SubsurfaceGrid.WATER_HEAT_CAPACITY);
                    double kappa = ch.conductivity[i] / cap;
                    double velocity = belowWaterTable(ch, c, k) ? ch.hydraulicK[i] * gradient : 0;
                    double adv = velocity * SubsurfaceGrid.WATER_DENSITY * SubsurfaceGrid.WATER_HEAT_CAPACITY / cap;
                    double rate = 4 * kappa / (dx * dx) + 4 * adv / dx;
                    if (rate > 0) minStep = Math.min(minStep, 0.4 / rate);
                }
            }
        }
        return Double.isInfinite(minStep) ? Double.MAX_VALUE : minStep;
    }

    /** Largest water-table slope from a column to its existing neighbours. */
    private double headGradient(SolverChunk ch, int c) {
        int gx = ch.gx(c);
        int gz = ch.gz(c);
        double max = 0;
        int[][] around = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : around) {
            SolverChunk o = grid.chunkOf(gx + d[0], gz + d[1]);
            if (o == null) continue;
            int oc = SolverChunk.column(gx + d[0], gz + d[1]);
            if (!o.exists[oc]) continue;
            max = Math.max(max, Math.abs(o.head[oc] - ch.head[c]) / grid.dx());
        }
        return max;
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

    /** Lateral conduction + Darcy advection energy (J) over {@code dt} for one chunk's faces east/south. */
    private void lateralEnergy(SolverChunk ch, double dt, Map<SolverChunk, Double> stepped,
            Map<SolverChunk, double[]> lateral, Groundwater groundwater) {
        int n = grid.levels();
        double dx = grid.dx();
        double[] mine = lateral.get(ch);
        for (int c = 0; c < SolverChunk.AREA; c++) {
            if (!ch.exists[c]) continue;
            int gx = ch.gx(c);
            int gz = ch.gz(c);
            for (int dir = 0; dir < 2; dir++) {
                int ngx = dir == 0 ? gx + 1 : gx;
                int ngz = dir == 0 ? gz : gz + 1;
                SolverChunk other = grid.chunkOf(ngx, ngz);
                if (other == null) continue;
                int oc = SolverChunk.column(ngx, ngz);
                if (!other.exists[oc]) continue;
                // Faces exchange heat only between chunks stepped together with the same time step;
                // others (dormant, or warm next to hot) are treated as insulating for this step.
                if (other != ch && !stepped.get(ch).equals(stepped.get(other))) continue;
                double[] theirs = lateral.get(other);
                double headGradient = (other.head[oc] - ch.head[c]) / dx;
                for (int k = 0; k < n; k++) {
                    int i = c * n + k;
                    int j = oc * n + k;
                    double ka = ch.conductivity[i];
                    double kb = other.conductivity[j];
                    double kh = ka + kb > 0 ? 2 * ka * kb / (ka + kb) : 0;
                    double conductance = grid.thickness(k) * kh; // face area dz·dx over distance dx
                    double ti = ch.temperature[i];
                    double tj = other.temperature[j];
                    // Conduction into ch.
                    double energy = conductance * (tj - ti) * dt;
                    if (belowWaterTable(ch, c, k) && belowWaterTable(other, oc, k)) {
                        // Advection: heat carried by groundwater from ch to other (negative = reverse).
                        double kHyd = harmonic(ch.hydraulicK[i], other.hydraulicK[j]);
                        double flow = -kHyd * headGradient * grid.thickness(k) * dx; // m³/s, + = ch → other
                        double upwind = flow > 0 ? ti : tj;
                        energy -= SubsurfaceGrid.WATER_DENSITY * SubsurfaceGrid.WATER_HEAT_CAPACITY * flow * upwind * dt;
                    }
                    mine[i] += energy;
                    theirs[j] -= energy;
                }
            }
        }
    }

    static double harmonic(double a, double b) {
        return a + b > 0 ? 2 * a * b / (a + b) : 0;
    }

    /** Implicit vertical conduction of one column (Thomas algorithm). */
    private void vertical(SolverChunk ch, int c, double dt, double[] lateral, double[] sources, double sourceShare,
            List<HeatSources.Chamber> chambers, double waterDepth) {
        int n = grid.levels();
        double area = grid.area();
        double[] lower = new double[n];
        double[] diag = new double[n];
        double[] upper = new double[n];
        double[] rhs = new double[n];
        double[] g = new double[n]; // conductance between k and k+1 (W/K)
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
        double kn = effectiveConductivity(ch, c, n - 1, nusselt, waterTableDepth);
        double gBottom = area / (grid.thickness(n - 1) / (2 * kn));
        double tBottom = bottomTemperature(ch, c, chambers);
        double l = grid.world().spec().metersPerColumn();
        double x = centreX(ch, c);
        double z = centreZ(ch, c);
        for (int k = 0; k < n; k++) {
            int i = c * n + k;
            double cap = capacity(ch, c, k, area * grid.thickness(k));
            double d = cap;
            double r = cap * ch.temperature[i] + lateral[i] + (sources != null ? sources[i] * sourceShare : 0);
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
            if (k == n - 1) {
                d += dt * gBottom;
                r += dt * gBottom * tBottom;
            }
            double inside = insideChamber(chambers, l, x, z, ch.surfaceZ[c] - grid.centerDepth(k));
            if (!Double.isNaN(inside)) {
                d = 1;
                r = inside;
                if (k > 0) lower[k] = 0;
                if (k < n - 1) upper[k] = 0;
            }
            diag[k] = d;
            rhs[k] = r;
        }
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
    }

    private double effectiveConductivity(SolverChunk ch, int c, int k, double nusselt, double waterTableDepth) {
        int i = c * grid.levels() + k;
        double kk = Math.max(1e-3, ch.conductivity[i]);
        if (nusselt > 1 && grid.centerDepth(k) > waterTableDepth && ch.hydraulicK[i] > 1e-9) kk *= nusselt;
        return kk;
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
            if (ch.hydraulicK[i] <= 1e-9) {
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
        for (int k = 0; k < n; k++) {
            int i = c * n + k;
            double elevation = grid.cellElevation(ch, c, k);
            if (elevation >= ch.head[c] || ch.porosity[i] <= 0) {
                ch.steam[i] *= collapse;
                continue;
            }
            double depth = ch.head[c] - elevation;
            double tbp = boilingPoint(depth);
            double t = ch.temperature[i];
            if (t <= tbp) {
                ch.steam[i] *= collapse;
                continue;
            }
            double volume = area * grid.thickness(k);
            double cap = capacity(ch, c, k, volume);
            double excess = cap * (t - tbp); // J
            double water = ch.porosity[i] * (1 - ch.steam[i]) * volume * SubsurfaceGrid.WATER_DENSITY; // kg
            double mass = Math.min(water, excess / config.latentHeatVaporJkg);
            if (mass <= 0) continue;
            ch.temperature[i] = t - mass * config.latentHeatVaporJkg / cap;
            ch.steam[i] = Math.min(1, ch.steam[i] + mass / (ch.porosity[i] * volume * SubsurfaceGrid.WATER_DENSITY));
            steamMass += mass;
        }
        ch.steamFlux[c] = steamMass / dt;
        double vol = steamMass / SubsurfaceGrid.WATER_DENSITY;
        boiled[c] = vol;
        boiledTotal += vol;
    }

    /** Chunks in key order that need stepping, helper for callers. */
    static List<SolverChunk> list(Iterable<SolverChunk> chunks) {
        List<SolverChunk> list = new ArrayList<>();
        for (SolverChunk ch : chunks) list.add(ch);
        return list;
    }
}
