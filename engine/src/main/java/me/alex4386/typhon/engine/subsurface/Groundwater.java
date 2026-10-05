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
 * vadose zone, and sinks {@code Q} (boiling). With {@link SubsurfaceConfig#regionalBoundary} the
 * columns on the edge of the modelled area keep their head (the regional water table beyond it). Face transmissivity is the harmonic mean of the two
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
    final Parallel parallel;
    private static final int SOR_BLOCK = 2048;

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
        this.parallel = new Parallel(config);
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

    // Cached column topology (rebuilt when columns appear or disappear)
    private long topologyVersion = Long.MIN_VALUE;
    private SolverChunk[] chunkOf = new SolverChunk[0];
    private int[] colOf = new int[0];
    private int[] east = new int[0];
    private int[] south = new int[0];
    private int[] west = new int[0];
    private int[] north = new int[0];
    private boolean[] red = new boolean[0];

    /** Flattens the existing columns (key order) and their neighbour links when the grid changed. */
    private void refreshTopology() {
        if (topologyVersion == grid.structureVersion) return;
        topologyVersion = grid.structureVersion;
        List<SolverChunk> chunks = new ArrayList<>();
        List<Integer> cols = new ArrayList<>();
        for (SolverChunk ch : grid.chunks()) {
            for (int c = 0; c < SolverChunk.AREA; c++) {
                if (ch.exists[c]) {
                    chunks.add(ch);
                    cols.add(c);
                }
            }
        }
        int m = chunks.size();
        chunkOf = chunks.toArray(new SolverChunk[0]);
        colOf = new int[m];
        for (int i = 0; i < m; i++) colOf[i] = cols.get(i);
        java.util.HashMap<Long, Integer> index = new java.util.HashMap<>(m * 2);
        for (int i = 0; i < m; i++) index.put(SubsurfaceGrid.key(chunkOf[i].gx(colOf[i]), chunkOf[i].gz(colOf[i])), i);
        east = new int[m];
        south = new int[m];
        west = new int[m];
        north = new int[m];
        red = new boolean[m];
        java.util.Arrays.fill(west, -1);
        java.util.Arrays.fill(north, -1);
        for (int i = 0; i < m; i++) {
            int gx = chunkOf[i].gx(colOf[i]);
            int gz = chunkOf[i].gz(colOf[i]);
            Integer e = index.get(SubsurfaceGrid.key(gx + 1, gz));
            Integer so = index.get(SubsurfaceGrid.key(gx, gz + 1));
            east[i] = e == null ? -1 : e;
            south[i] = so == null ? -1 : so;
            red[i] = ((gx + gz) & 1) == 0;
        }
        for (int i = 0; i < m; i++) {
            if (east[i] >= 0) west[east[i]] = i;
            if (south[i] >= 0) north[south[i]] = i;
        }
    }

    void step(double dt, Map<SolverChunk, double[]> boiled, SpringSink springs) {
        step(dt, 1, boiled, springs, false);
    }

    /**
     * One implicit step of {@code dt}, taking {@code boiledShare} of the boiled volumes. With
     * {@code requireConvergence}, an SOR solve that hits the iteration cap changes nothing and
     * returns false (the caller retries with shorter steps): the conservative flux update of an
     * unconverged solve overshoots around strong sinks.
     */
    boolean step(double dt, double boiledShare, Map<SolverChunk, double[]> boiled, SpringSink springs,
            boolean requireConvergence) {
        rechargeVolume = 0;
        springVolume = 0;
        seaExchange = 0;
        boiledVolume = 0;
        deficitVolume = 0;

        refreshTopology();
        int m = chunkOf.length;
        if (m == 0) return true;
        int[] east = this.east;
        int[] south = this.south;
        boolean[] red = this.red;
        double[] h0 = new double[m];
        double[] storageCoef = new double[m];
        double[] source = new double[m]; // m³/s
        boolean[] fixed = new boolean[m];
        double[] keff = new double[m];
        double[] sat = new double[m];
        double area = grid.area();
        double sea = grid.world().spec().seaLevelZ();
        double drainFactor = 1 - Math.exp(-dt / config.vadoseLagSeconds);
        double[] drained = new double[m];
        for (int i = 0; i < m; i++) {
            SolverChunk ch = chunkOf[i];
            int c = colOf[i];
            fixed[i] = ch.sea[c] || (config.regionalBoundary && edge(i));
            if (ch.sea[c]) ch.head[c] = sea;
            h0[i] = ch.head[c];
            storageCoef[i] = config.specificYield * area / dt;
            double drain = ch.vadose[c] * drainFactor;
            ch.vadose[c] -= drain;
            drained[i] = drain;
            double recharge = drain * area;
            rechargeVolume += recharge;
            double[] b = boiled.get(ch);
            double boil = b == null ? 0 : b[c] * boiledShare;
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
        int[] west = this.west;
        int[] north = this.north;

        double[] h = h0.clone();
        double omega = config.sorOmega;
        int iterations = 0;
        boolean converged = false;
        // Red-black SOR: within one colour every update reads only the other colour, so blocks of
        // columns are updated in parallel with results independent of the thread count.
        int blocks = (m + SOR_BLOCK - 1) / SOR_BLOCK;
        List<Integer> blockList = new ArrayList<>(blocks);
        for (int b = 0; b < blocks; b++) blockList.add(b);
        double[] blockChange = new double[blocks];
        for (; iterations < config.groundwaterIterations; iterations++) {
            java.util.Arrays.fill(blockChange, 0);
            for (int colour = 0; colour < 2; colour++) {
                boolean wantRed = colour == 0;
                parallel.forEach(blockList, (bi, b) -> {
                    int end = Math.min(m, (b + 1) * SOR_BLOCK);
                    double change = blockChange[b];
                    for (int i = b * SOR_BLOCK; i < end; i++) {
                        if (fixed[i] || red[i] != wantRed) continue;
                        double sum = storageCoef[i] * h0[i] + source[i];
                        if (east[i] >= 0) sum += tEast[i] * h[east[i]];
                        if (west[i] >= 0) sum += tEast[west[i]] * h[west[i]];
                        if (south[i] >= 0) sum += tSouth[i] * h[south[i]];
                        if (north[i] >= 0) sum += tSouth[north[i]] * h[north[i]];
                        double gs = sum / diag[i];
                        double next = h[i] + omega * (gs - h[i]);
                        change = Math.max(change, Math.abs(next - h[i]));
                        h[i] = next;
                    }
                    blockChange[b] = change;
                });
            }
            double maxChange = 0;
            for (double c : blockChange) maxChange = Math.max(maxChange, c);
            if (maxChange < 1e-6) { // micrometre head changes: converged
                iterations++;
                converged = true;
                break;
            }
        }
        lastIterations = iterations;
        if (requireConvergence && !converged) {
            for (int i = 0; i < m; i++) {
                SolverChunk ch = chunkOf[i];
                ch.vadose[colOf[i]] += drained[i];
                if (fixed[i]) ch.head[colOf[i]] = h0[i];
            }
            rechargeVolume = 0;
            boiledVolume = 0;
            return false;
        }

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
            SolverChunk ch = chunkOf[i];
            int c = colOf[i];
            if (fixed[i]) {
                seaExchange += net[i];
                ch.head[c] = ch.sea[c] ? sea : h0[i];
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
        return true;
    }

    /** True if the column lies on the edge of the modelled area (a neighbour is missing). */
    private boolean edge(int i) {
        return east[i] < 0 || west[i] < 0 || south[i] < 0 || north[i] < 0;
    }

    private static double face(double[] keff, double[] sat, int a, int b) {
        double k = SubsurfaceHeat.harmonic(keff[a], keff[b]);
        return k * 0.5 * (sat[a] + sat[b]);
    }
}
