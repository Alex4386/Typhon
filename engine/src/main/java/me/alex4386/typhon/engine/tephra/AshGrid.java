package me.alex4386.typhon.engine.tephra;

import me.alex4386.typhon.engine.sim.Parallel;
import com.google.gson.JsonObject;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.save.FieldChunk;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.WorldModel;

/**
 * Vertically integrated 2D tephra transport on a coarse square grid, in SI units.
 *
 * <p>The grid is aligned with the surface columns: cell {@code (i, j)} covers columns
 * {@code [originX + i·cellColumns, originX + (i+1)·cellColumns)} (and likewise in z), so a cell is
 * {@code cellColumns · L} metres across. For each grain class, {@code airborne[c][cell]} holds the suspended
 * mass (kg) above the cell. Each step applies, in order:
 *
 * <ol>
 *   <li>advection by the wind (m/s; donor-cell upwind) and horizontal eddy diffusion (m²/s), both in
 *       conservative flux form and sub-stepped so that the total outflow fraction per sub-step stays below
 *       0.9 (positive and mass-conserving); mass crossing the domain edge is counted as exported;
 *   <li>settling as first-order fallout with rate v_s / H, where v_s is the class settling velocity
 *       (m/s) and H the plume height (m): deposited = m · (1 − e^(−v_s·dt/H)).
 * </ol>
 *
 * Deposited mass becomes thickness {@code mass / (ρ_deposit · cellArea)} (m), which {@link #applyDeposits}
 * lays on the ground of every column of the cell.
 */
final class AshGrid {
    /** Grid origin, in column indices. */
    final int originX;
    final int originZ;
    /** Cell edge, in columns. */
    final int cellColumns;
    /** Column width L (m). */
    final double metersPerColumn;
    final int cells;
    final double[][] airborne;
    /** Deposited mass per cell (kg). */
    final double[] deposit;
    /** Thickness (m) already laid on the ground, per cell. */
    final double[] applied;
    /** Deposition rate during the last step (kg/s), per cell. */
    final double[] depositionRate;

    /** Height of the plume the airborne ash settles from (m above the vent). */
    double plumeHeight;
    double emitted;
    double deposited;
    double exported;
    double discarded;

    private final double[] scratch;
    private final double[] rowScratch;
    /** Executor for the per-row transport/settling loops (set by the owning subsystem each step). */
    Parallel parallel = Parallel.sequential();

    AshGrid(int originX, int originZ, int cellColumns, double metersPerColumn, int cells) {
        this.originX = originX;
        this.originZ = originZ;
        this.cellColumns = cellColumns;
        this.metersPerColumn = metersPerColumn;
        this.cells = cells;
        int area = cells * cells;
        this.airborne = new double[GrainClass.COUNT][area];
        this.deposit = new double[area];
        this.applied = new double[area];
        this.depositionRate = new double[area];
        this.scratch = new double[area];
        this.rowScratch = new double[cells];
    }

    /** A grid of {@code cells}² cells of {@code cellColumns} columns, its middle cell containing {@code center}. */
    static AshGrid centeredOn(Point3 center, int cellColumns, double metersPerColumn, int cells) {
        int cx = (int) Math.floor(center.x() / metersPerColumn);
        int cz = (int) Math.floor(center.z() / metersPerColumn);
        int half = cells / 2 * cellColumns + cellColumns / 2;
        return new AshGrid(cx - half, cz - half, cellColumns, metersPerColumn, cells);
    }

    int index(int i, int j) {
        return j * cells + i;
    }

    /** Cell edge (m). */
    double cellMeters() {
        return cellColumns * metersPerColumn;
    }

    /** Cell index containing column ({@code x}, {@code z}), or -1 outside the grid. */
    int cellAt(int x, int z) {
        int i = Math.floorDiv(x - originX, cellColumns);
        int j = Math.floorDiv(z - originZ, cellColumns);
        if (i < 0 || j < 0 || i >= cells || j >= cells) return -1;
        return index(i, j);
    }

    /** Cell index containing the point ({@code x}, {@code z}) (m), or -1 outside the grid. */
    int cellAtPoint(double x, double z) {
        return cellAt((int) Math.floor(x / metersPerColumn), (int) Math.floor(z / metersPerColumn));
    }

    /** Cell area (m²). */
    double cellArea() {
        double s = cellMeters();
        return s * s;
    }

    /** Deposit thickness (m) of a cell at bulk density {@code bulkDensity} (kg/m³). */
    double thickness(int cell, double bulkDensity) {
        return deposit[cell] / (bulkDensity * cellArea());
    }

    /**
     * Bilinear interpolation of a per-cell field {@code value} between cell centres at column ({@code x},
     * {@code z}) (a missing neighbour repeats the column's own cell); NaN outside the grid. The cells hold
     * averages of a smoothly varying deposit: read at a column, the deposit varies across the cell instead of
     * stepping at its edges.
     */
    double interpolate(int x, int z, java.util.function.IntToDoubleFunction value) {
        int own = cellAt(x, z);
        if (own < 0) return Double.NaN;
        double fx = (x + 0.5 - originX) / cellColumns - 0.5; // cell coordinates of the column centre
        double fz = (z + 0.5 - originZ) / cellColumns - 0.5;
        int i0 = (int) Math.floor(fx);
        int j0 = (int) Math.floor(fz);
        double u = fx - i0;
        double v = fz - j0;
        double ownValue = value.applyAsDouble(own);
        double v00 = cellValue(i0, j0, value, ownValue);
        double v10 = cellValue(i0 + 1, j0, value, ownValue);
        double v01 = cellValue(i0, j0 + 1, value, ownValue);
        double v11 = cellValue(i0 + 1, j0 + 1, value, ownValue);
        return (1 - u) * (1 - v) * v00 + u * (1 - v) * v10 + (1 - u) * v * v01 + u * v * v11;
    }

    private double cellValue(int i, int j, java.util.function.IntToDoubleFunction value, double fallback) {
        return i < 0 || j < 0 || i >= cells || j >= cells ? fallback : value.applyAsDouble(index(i, j));
    }

    /** Deposit thickness (m) at column ({@code x}, {@code z}): smooth between cell centres (see {@link #interpolate}). */
    double thicknessAt(int x, int z, double bulkDensity) {
        double t = interpolate(x, z, cell -> thickness(cell, bulkDensity));
        return Double.isNaN(t) ? 0 : Math.max(0, t);
    }

    double airborneTotal() {
        double total = 0;
        for (double[] layer : airborne) {
            total += parallel.sum(cells, j -> {
                double s = 0;
                for (int cell = j * cells, end = cell + cells; cell < end; cell++) s += layer[cell];
                return s;
            });
        }
        return total;
    }

    double airborneAt(int cell) {
        double total = 0;
        for (double[] layer : airborne) total += layer[cell];
        return total;
    }

    /** x of the west edge of cell column {@code i} (m). */
    private double cellX0(int i) {
        return (originX + (double) i * cellColumns) * metersPerColumn;
    }

    private double cellZ0(int j) {
        return (originZ + (double) j * cellColumns) * metersPerColumn;
    }

    /**
     * Adds {@code mass} kg split by {@code fractions}, spread as a Gaussian of {@code sigma} metres around
     * {@code (cx, cz)} (m; the umbrella cloud). Mass that would land outside the grid is exported.
     */
    void inject(double mass, double[] fractions, double cx, double cz, double sigma) {
        if (mass <= 0) return;
        emitted += mass;
        double s = cellMeters();
        double ox = cellX0(0);
        double oz = cellZ0(0);
        double reach = 3 * sigma;
        int fi0 = (int) Math.floor((cx - reach - ox) / s);
        int fi1 = (int) Math.floor((cx + reach - ox) / s);
        int fj0 = (int) Math.floor((cz - reach - oz) / s);
        int fj1 = (int) Math.floor((cz + reach - oz) / s);
        int i0 = Math.max(0, fi0);
        int i1 = Math.min(cells - 1, fi1);
        int j0 = Math.max(0, fj0);
        int j1 = Math.min(cells - 1, fj1);

        // Normalise against the full (unclipped) kernel so mass outside the grid counts as exported.
        // The Gaussian is separable: exp(-(x²+z²)/2σ²) = ex(i)·ez(j), so one exp per row and column.
        double[] ex = new double[fi1 - fi0 + 1];
        double[] dxs = new double[ex.length];
        double[] ez = new double[fj1 - fj0 + 1];
        double[] dzs = new double[ez.length];
        double twoSigma2 = 2 * sigma * sigma;
        for (int i = fi0; i <= fi1; i++) {
            double x = ox + (i + 0.5) * s - cx;
            dxs[i - fi0] = x * x;
            ex[i - fi0] = StrictMath.exp(-x * x / twoSigma2);
        }
        for (int j = fj0; j <= fj1; j++) {
            double z = oz + (j + 0.5) * s - cz;
            dzs[j - fj0] = z * z;
            ez[j - fj0] = StrictMath.exp(-z * z / twoSigma2);
        }
        double cutoff = 9 * sigma * sigma;
        double fullWeight = 0;
        for (int j = fj0; j <= fj1; j++) {
            for (int i = fi0; i <= fi1; i++) {
                if (dxs[i - fi0] + dzs[j - fj0] <= cutoff) fullWeight += ex[i - fi0] * ez[j - fj0];
            }
        }
        if (fullWeight <= 0) {
            exported += mass;
            return;
        }

        double norm = fullWeight;
        int rows = j1 - j0 + 1;
        double placed = rows <= 0 || i1 < i0 ? 0 : parallel.sum(rows, r -> {
            int j = j0 + r;
            double rowPlaced = 0;
            for (int i = i0; i <= i1; i++) {
                if (dxs[i - fi0] + dzs[j - fj0] > cutoff) continue;
                double w = ex[i - fi0] * ez[j - fj0] / norm;
                if (w <= 0) continue;
                int cell = index(i, j);
                for (int c = 0; c < GrainClass.COUNT; c++) {
                    double m = mass * w * fractions[c];
                    airborne[c][cell] += m;
                    rowPlaced += m;
                }
            }
            return rowPlaced;
        });
        exported += Math.max(0, mass - placed);
    }

    /** Advection by {@code wind} (m/s) + diffusion ({@code diffusivity} m²/s) for {@code dt} seconds. */
    void transport(double dt, Vec3d wind, double diffusivity) {
        double dx = cellMeters();
        double cx = wind.x() * dt / dx;
        double cz = wind.z() * dt / dx;
        double d = diffusivity * dt / (dx * dx);
        double outflow = Math.abs(cx) + Math.abs(cz) + 4 * d;
        int substeps = Math.max(1, (int) Math.ceil(outflow / 0.9));
        cx /= substeps;
        cz /= substeps;
        d /= substeps;

        for (int s = 0; s < substeps; s++) {
            for (double[] layer : airborne) transportLayer(layer, cx, cz, d);
        }
    }

    /**
     * One conservative upwind + diffusion sub-step, written as a gather (each cell sums what it keeps
     * and what its neighbours send it) so rows can be computed in parallel; mass leaving the grid is
     * summed per row and added in row order.
     */
    private void transportLayer(double[] layer, double cx, double cz, double d) {
        double[] next = scratch;
        double[] rowExport = rowScratch;
        double ax = Math.abs(cx), az = Math.abs(cz);
        int stepX = cx >= 0 ? 1 : -1;
        int stepZ = cz >= 0 ? 1 : -1;
        int n = cells;

        parallel.forEach(n, 4, j -> {
            double export = 0;
            int row = j * n;
            for (int i = 0; i < n; i++) {
                int cell = row + i;
                double m = layer[cell];
                double v = m;
                if (m != 0) {
                    double toX = m * ax, toZ = m * az, toEach = m * d;
                    v -= toX + toZ + 4 * toEach;
                    int tx = i + stepX;
                    int tz = j + stepZ;
                    if (tx < 0 || tx >= n) export += toX;
                    if (tz < 0 || tz >= n) export += toZ;
                    if (i == n - 1) export += toEach;
                    if (i == 0) export += toEach;
                    if (j == n - 1) export += toEach;
                    if (j == 0) export += toEach;
                }
                int ux = i - stepX; // upwind neighbours send their advected share here
                if (ux >= 0 && ux < n) v += layer[row + ux] * ax;
                int uz = j - stepZ;
                if (uz >= 0 && uz < n) v += layer[uz * n + i] * az;
                if (i + 1 < n) v += layer[cell + 1] * d;
                if (i > 0) v += layer[cell - 1] * d;
                if (j + 1 < n) v += layer[cell + n] * d;
                if (j > 0) v += layer[cell - n] * d;
                next[cell] = v;
            }
            rowExport[j] = export;
        });
        for (int j = 0; j < n; j++) exported += rowExport[j];
        System.arraycopy(next, 0, layer, 0, layer.length);
    }

    /** First-order fallout for {@code dt} seconds from a plume {@code height} metres high. */
    void settle(double dt, double[] settlingVelocities, double height) {
        double h = Math.max(1, height);
        double[] fraction = new double[GrainClass.COUNT];
        for (int c = 0; c < GrainClass.COUNT; c++) fraction[c] = 1 - StrictMath.exp(-settlingVelocities[c] * dt / h);
        double[] rowFall = rowScratch;
        int n = cells;
        parallel.forEach(n, 4, j -> {
            double rowSum = 0;
            for (int cell = j * n, end = cell + n; cell < end; cell++) {
                double rate = 0;
                for (int c = 0; c < GrainClass.COUNT; c++) {
                    double m = airborne[c][cell];
                    if (m == 0) continue;
                    double fall = m * fraction[c];
                    airborne[c][cell] = m - fall;
                    deposit[cell] += fall;
                    rate += fall / dt;
                    rowSum += fall;
                }
                depositionRate[cell] = rate;
            }
            rowFall[j] = rowSum;
        });
        for (int j = 0; j < n; j++) deposited += rowFall[j];
    }

    /** Drops all suspended mass (used once a finished phase has thinned out). */
    void discardAirborne() {
        for (double[] layer : airborne) {
            for (int cell = 0; cell < layer.length; cell++) {
                discarded += layer[cell];
                layer[cell] = 0;
            }
        }
        java.util.Arrays.fill(depositionRate, 0);
    }

    /**
     * Records deposit (kg) that was already laid down on the ground column by column (proximal fallout): it
     * counts in the cell's mass and thickness record but is not applied a second time.
     */
    void addAppliedDeposit(int cell, double mass, double bulkDensity) {
        deposit[cell] += mass;
        emitted += mass;
        deposited += mass;
        applied[cell] += mass / (bulkDensity * cellArea());
    }

    /** Adds deposit directly (kg) to a cell. Package-private for tests and scripted events. */
    void addDeposit(int cell, double mass) {
        deposit[cell] += mass;
        emitted += mass;
        deposited += mass;
    }

    /**
     * Lays the deposit each cell gained since the last call (once it reaches {@code updateThicknessM}) on the
     * ground of every known column of the cell, as loose ash of unit {@code unit}. Columns covered by molten
     * lava ({@code molten}) take the ash into the flow instead: it forms no layer under the lava. The whole
     * step's fall lands first; the touched columns relax to their angle of repose together afterwards.
     */
    void applyDeposits(WorldModel world, double bulkDensity, double updateThicknessM, int unit,
            java.util.function.BiPredicate<Integer, Integer> molten) {
        // what each cell has gained since it was last laid down: the shape the new layer follows across cells
        java.util.function.IntToDoubleFunction pending = c -> Math.max(0, thickness(c, bulkDensity) - applied[c]);
        int n = cellColumns;
        double[] w = new double[n * n];
        world.withRelaxationDeferred(() -> {
            for (int j = 0; j < cells; j++) {
                for (int i = 0; i < cells; i++) {
                    int cell = index(i, j);
                    double t1 = thickness(cell, bulkDensity);
                    double dt = t1 - applied[cell];
                    if (dt < updateThicknessM) continue;
                    int x0 = originX + i * cellColumns;
                    int z0 = originZ + j * cellColumns;
                    // The cell's increment, spread over its columns along the smooth field between the cell
                    // centres and scaled so the cell lays exactly its own volume.
                    double sum = 0;
                    for (int dz = 0; dz < n; dz++) {
                        for (int dx = 0; dx < n; dx++) {
                            double v = Math.max(0, interpolate(x0 + dx, z0 + dz, pending));
                            w[dz * n + dx] = v;
                            sum += v;
                        }
                    }
                    double scale = sum > 0 ? dt * n * n / sum : 0;
                    for (int dz = 0; dz < n; dz++) {
                        for (int dx = 0; dx < n; dx++) {
                            int x = x0 + dx;
                            int z = z0 + dz;
                            if (molten.test(x, z)) continue;
                            double thickness = sum > 0 ? w[dz * n + dx] * scale : dt;
                            if (thickness > 0) world.deposit(x, z, thickness, MaterialTable.ASH, unit);
                        }
                    }
                    applied[cell] = t1;
                }
            }
        });
    }

    /**
     * Columns {@code [x0, x0+size) × [z0, z0+size)} just became simulated: lays down the fall deposit already
     * applied to the rest of their cells, so the ground gets the ash that fell on it before it was simulated
     * (the cell grid kept it). Later increments arrive through {@link #applyDeposits} like everywhere else.
     */
    void backfill(WorldModel world, int unit, int x0, int z0, int size) {
        world.withRelaxationDeferred(() -> {
            for (int z = z0; z < z0 + size; z++) {
                for (int x = x0; x < x0 + size; x++) {
                    int cell = cellAt(x, z);
                    if (cell < 0 || !(applied[cell] > 0) || !world.isKnown(x, z)) continue;
                    world.deposit(x, z, applied[cell], MaterialTable.ASH, unit);
                }
            }
        });
    }

    /**
     * Reports the cells whose deposit is at least {@code minThicknessM} thick: one sample column every
     * {@code stride} columns across each such cell.
     */
    void reportDeposits(double minThicknessM, double bulkDensity, int stride,
            me.alex4386.typhon.engine.expansion.ExpansionActivity.Sink sink) {
        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                int cell = index(i, j);
                if (!(deposit[cell] > 0) || thickness(cell, bulkDensity) < minThicknessM) continue;
                int x0 = originX + i * cellColumns;
                int z0 = originZ + j * cellColumns;
                for (int z = z0; z < z0 + cellColumns; z += stride) {
                    for (int x = x0; x < x0 + cellColumns; x += stride) sink.active(x, z);
                }
                sink.active(x0 + cellColumns - 1, z0 + cellColumns - 1);
            }
        }
    }

    /** Centre of a cell (m; y = 0). */
    Point3 cellCenter(int i, int j) {
        return new Point3(cellX0(i) + cellMeters() / 2, 0, cellZ0(j) + cellMeters() / 2);
    }

    void save(JsonObject out) {
        out.addProperty("originX", originX);
        out.addProperty("originZ", originZ);
        out.addProperty("cellColumns", cellColumns);
        out.addProperty("metersPerColumn", metersPerColumn);
        out.addProperty("cells", cells);
        out.addProperty("plumeHeight", plumeHeight);
        out.addProperty("emitted", emitted);
        out.addProperty("deposited", deposited);
        out.addProperty("exported", exported);
        out.addProperty("discarded", discarded);
    }

    /** The grid's arrays, for a spatial save field. */
    FieldChunk arrays() {
        FieldChunk chunk = new FieldChunk();
        for (GrainClass c : GrainClass.values()) {
            chunk.doubles("airborne." + c.name().toLowerCase(), airborne[c.ordinal()].clone());
        }
        return chunk.doubles("deposit", deposit.clone())
                .doubles("applied", applied.clone())
                .doubles("depositionRate", depositionRate.clone());
    }

    static AshGrid load(JsonObject in, FieldChunk arrays) {
        AshGrid grid = new AshGrid(
                in.get("originX").getAsInt(),
                in.get("originZ").getAsInt(),
                in.get("cellColumns").getAsInt(),
                in.get("metersPerColumn").getAsDouble(),
                in.get("cells").getAsInt());
        grid.plumeHeight = in.get("plumeHeight").getAsDouble();
        grid.emitted = in.get("emitted").getAsDouble();
        grid.deposited = in.get("deposited").getAsDouble();
        grid.exported = in.get("exported").getAsDouble();
        grid.discarded = in.get("discarded").getAsDouble();
        for (GrainClass c : GrainClass.values()) {
            copy(arrays.doubles("airborne." + c.name().toLowerCase()), grid.airborne[c.ordinal()]);
        }
        copy(arrays.doubles("deposit"), grid.deposit);
        copy(arrays.doubles("applied"), grid.applied);
        copy(arrays.doubles("depositionRate"), grid.depositionRate);
        return grid;
    }

    private static void copy(double[] saved, double[] target) {
        if (saved == null || saved.length != target.length) {
            throw new IllegalArgumentException("Saved ash array has the wrong size");
        }
        System.arraycopy(saved, 0, target, 0, target.length);
    }
}
