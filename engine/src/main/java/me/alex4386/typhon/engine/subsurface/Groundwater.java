package me.alex4386.typhon.engine.subsurface;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Unconfined groundwater under the Dupuit assumption: one water-table elevation {@code h} per solver
 * column,
 *
 * <pre>  S_y ∂h/∂t = ∇·(T ∇h) + R − Q</pre>
 *
 * with transmissivity {@code T = Σ K_k·sat_k} integrated over the saturated part of the column's
 * levels (so impermeable basement and cavities carry no flow), recharge {@code R} draining from the
 * vadose zone, and sinks {@code Q} (boiling). Face transmissivity is the harmonic mean of the two
 * columns' effective conductivities times their mean saturated thickness (as in MODFLOW).
 *
 * <p>Each macro step is solved implicitly (Picard-linearised transmissivity) with red-black SOR
 * iterated to convergence or a fixed cap; the new heads are then obtained by a conservative update
 * from the face fluxes, so water is conserved exactly. Columns below sea level are fixed-head
 * boundaries; water above the ground surface leaves as springs into the surface-water field.
 * Iteration order is fixed, so results do not depend on threading.
 */
final class Groundwater {
    private final SubsurfaceGrid grid;
    private final SubsurfaceConfig config;

    // Budget of the last step (m³)
    double rechargeVolume;
    double springVolume;
    double seaExchange; // net flow into fixed-head (sea) columns
    double boiledVolume;
    double deficitVolume;
    int lastIterations;

    /** Receives spring discharge: water leaving the aquifer at a column. */
    interface SpringSink {
        void accept(SolverChunk ch, int c, double volumeM3);
    }

    Groundwater(SubsurfaceGrid grid, SubsurfaceConfig config) {
        this.grid = grid;
        this.config = config;
    }

    /** Lowest permitted water-table elevation in a column (just above the grid bottom). */
    double floor(SolverChunk ch, int c) {
        return ch.surfaceZ[c] - grid.totalDepth() + 0.01;
    }

    /** Saturated thickness and transmissivity (m²/s) of a column for head {@code h}. */
    double[] transmissivity(SolverChunk ch, int c, double h) {
        int n = grid.levels();
        double sat = 0;
        double t = 0;
        for (int k = 0; k < n; k++) {
            double top = ch.surfaceZ[c] - grid.topDepth(k);
            double bottom = top - grid.thickness(k);
            double s = Math.max(0, Math.min(h, top) - bottom);
            if (s <= 0) continue;
            sat += s;
            t += ch.hydraulicK[c * n + k] * s;
        }
        return new double[] {sat, t};
    }

    /** Stored water above the grid bottom (m³) — for budgets. */
    double storage() {
        double sum = 0;
        double area = grid.area();
        for (SolverChunk ch : grid.chunks()) {
            for (int c = 0; c < SolverChunk.AREA; c++) {
                if (ch.exists[c] && !ch.sea[c]) {
                    sum += config.specificYield * area * (ch.head[c] - floor(ch, c)) + ch.vadose[c] * area;
                }
            }
        }
        return sum;
    }

    void step(double dt, Map<SolverChunk, double[]> boiled, SpringSink springs) {
        rechargeVolume = 0;
        springVolume = 0;
        seaExchange = 0;
        boiledVolume = 0;
        deficitVolume = 0;

        // Flatten existing columns in key order.
        List<SolverChunk> chunkOf = new ArrayList<>();
        List<Integer> colOf = new ArrayList<>();
        for (SolverChunk ch : grid.chunks()) {
            for (int c = 0; c < SolverChunk.AREA; c++) {
                if (ch.exists[c]) {
                    chunkOf.add(ch);
                    colOf.add(c);
                }
            }
        }
        int m = chunkOf.size();
        if (m == 0) return;
        java.util.HashMap<Long, Integer> index = new java.util.HashMap<>(m * 2);
        for (int i = 0; i < m; i++) {
            SolverChunk ch = chunkOf.get(i);
            int c = colOf.get(i);
            index.put(SubsurfaceGrid.key(ch.gx(c), ch.gz(c)), i);
        }
        int[] east = new int[m];
        int[] south = new int[m];
        boolean[] red = new boolean[m];
        double[] h0 = new double[m];
        double[] storageCoef = new double[m];
        double[] source = new double[m]; // m³/s
        boolean[] fixed = new boolean[m];
        double[] keff = new double[m];
        double[] sat = new double[m];
        double area = grid.area();
        double sea = grid.world().spec().seaLevelZ();
        double drainFactor = 1 - Math.exp(-dt / config.vadoseLagSeconds);
        for (int i = 0; i < m; i++) {
            SolverChunk ch = chunkOf.get(i);
            int c = colOf.get(i);
            int gx = ch.gx(c);
            int gz = ch.gz(c);
            Integer e = index.get(SubsurfaceGrid.key(gx + 1, gz));
            Integer s = index.get(SubsurfaceGrid.key(gx, gz + 1));
            east[i] = e == null ? -1 : e;
            south[i] = s == null ? -1 : s;
            red[i] = ((gx + gz) & 1) == 0;
            fixed[i] = ch.sea[c];
            if (fixed[i]) ch.head[c] = sea;
            h0[i] = ch.head[c];
            storageCoef[i] = config.specificYield * area / dt;
            double drain = ch.vadose[c] * drainFactor;
            ch.vadose[c] -= drain;
            double recharge = drain * area;
            rechargeVolume += recharge;
            double[] b = boiled.get(ch);
            double boil = b == null ? 0 : b[c];
            boiledVolume += boil;
            source[i] = (recharge - boil) / dt;
            double[] st = transmissivity(ch, c, h0[i]);
            sat[i] = st[0];
            keff[i] = st[0] > 0 ? st[1] / st[0] : 0;
        }
        // Face transmissivities (east and south of each column).
        double[] tEast = new double[m];
        double[] tSouth = new double[m];
        for (int i = 0; i < m; i++) {
            if (east[i] >= 0) tEast[i] = face(keff, sat, i, east[i]);
            if (south[i] >= 0) tSouth[i] = face(keff, sat, i, south[i]);
        }
        // Diagonal: storage + Σ face T. Neighbour sums need west/north too.
        double[] diag = new double[m];
        for (int i = 0; i < m; i++) diag[i] += storageCoef[i];
        for (int i = 0; i < m; i++) {
            if (east[i] >= 0) {
                diag[i] += tEast[i];
                diag[east[i]] += tEast[i];
            }
            if (south[i] >= 0) {
                diag[i] += tSouth[i];
                diag[south[i]] += tSouth[i];
            }
        }
        int[] west = new int[m];
        int[] north = new int[m];
        java.util.Arrays.fill(west, -1);
        java.util.Arrays.fill(north, -1);
        for (int i = 0; i < m; i++) {
            if (east[i] >= 0) west[east[i]] = i;
            if (south[i] >= 0) north[south[i]] = i;
        }

        double[] h = h0.clone();
        double omega = config.sorOmega;
        int iterations = 0;
        for (; iterations < config.groundwaterIterations; iterations++) {
            double maxChange = 0;
            for (int colour = 0; colour < 2; colour++) {
                for (int i = 0; i < m; i++) {
                    if (fixed[i] || red[i] != (colour == 0)) continue;
                    double sum = storageCoef[i] * h0[i] + source[i];
                    if (east[i] >= 0) sum += tEast[i] * h[east[i]];
                    if (west[i] >= 0) sum += tEast[west[i]] * h[west[i]];
                    if (south[i] >= 0) sum += tSouth[i] * h[south[i]];
                    if (north[i] >= 0) sum += tSouth[north[i]] * h[north[i]];
                    double gs = sum / diag[i];
                    double next = h[i] + omega * (gs - h[i]);
                    maxChange = Math.max(maxChange, Math.abs(next - h[i]));
                    h[i] = next;
                }
            }
            if (maxChange < 1e-10) {
                iterations++;
                break;
            }
        }
        lastIterations = iterations;

        // Conservative update from face fluxes (exact water balance).
        double[] net = new double[m]; // m³ into each column over dt
        for (int i = 0; i < m; i++) {
            if (east[i] >= 0) {
                double f = tEast[i] * (h[i] - h[east[i]]) * dt;
                net[i] -= f;
                net[east[i]] += f;
            }
            if (south[i] >= 0) {
                double f = tSouth[i] * (h[i] - h[south[i]]) * dt;
                net[i] -= f;
                net[south[i]] += f;
            }
        }
        for (int i = 0; i < m; i++) {
            SolverChunk ch = chunkOf.get(i);
            int c = colOf.get(i);
            if (fixed[i]) {
                seaExchange += net[i];
                ch.head[c] = sea;
                // Water the boundary column received from recharge/boiling is also exchanged.
                seaExchange += source[i] * dt;
                continue;
            }
            double next = h0[i] + (net[i] + source[i] * dt) / (config.specificYield * area);
            double lowest = floor(ch, c);
            if (next < lowest) {
                deficitVolume += (lowest - next) * config.specificYield * area;
                next = lowest;
            }
            if (next > ch.surfaceZ[c]) {
                double excess = (next - ch.surfaceZ[c]) * config.specificYield * area;
                springVolume += excess;
                springs.accept(ch, c, excess);
                next = ch.surfaceZ[c];
            }
            ch.head[c] = next;
        }
    }

    private static double face(double[] keff, double[] sat, int a, int b) {
        double k = SubsurfaceHeat.harmonic(keff[a], keff[b]);
        return k * 0.5 * (sat[a] + sat[b]);
    }
}
