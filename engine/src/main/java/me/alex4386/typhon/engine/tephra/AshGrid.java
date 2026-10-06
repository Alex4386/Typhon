package me.alex4386.typhon.engine.tephra;

import me.alex4386.typhon.engine.sim.Parallel;
import com.google.gson.JsonObject;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.Outbox;
import me.alex4386.typhon.engine.save.FieldChunk;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.engine.world.BlockId;

/**
 * Vertically integrated 2D tephra transport on a coarse square grid.
 *
 * <p>For each grain class, {@code airborne[c][cell]} holds the suspended mass (kg) above the cell.
 * Each step applies, in order:
 *
 * <ol>
 *   <li>advection by the wind (donor-cell upwind) and horizontal eddy diffusion, both in conservative
 *       flux form and sub-stepped so that the total outflow fraction per sub-step stays below 0.9
 *       (positive and mass-conserving); mass crossing the domain edge is counted as exported;
 *   <li>settling as first-order fallout with rate v_s / H, where v_s is the class settling velocity
 *       and H the plume height: deposited = m · (1 − e^(−v_s·dt/H)).
 * </ol>
 *
 * Deposited mass becomes thickness {@code mass / (ρ_deposit · cellArea)}, which
 * {@link #applyDeposits} turns into blocks.
 */
final class AshGrid {
    private static final BlockId WATER = BlockId.minecraft("water");
    private static final java.util.Set<BlockId> NOT_COVERABLE = java.util.Set.of(
            BlockId.AIR,
            BlockId.minecraft("cave_air"),
            BlockId.minecraft("void_air"),
            WATER,
            BlockId.minecraft("lava"),
            BlockId.minecraft("magma_block"),
            BlockId.minecraft("bedrock"));

    final int originX;
    final int originZ;
    final int cellSize;
    final int cells;
    final double[][] airborne;
    final double[] deposit;
    /** Thickness (m) already converted to blocks, per cell. */
    final double[] applied;
    /** Deposition rate during the last step (kg/s), per cell. */
    final double[] depositionRate;

    double plumeHeight;
    double emitted;
    double deposited;
    double exported;
    double discarded;

    private final double[] scratch;
    private final double[] rowScratch;
    /** Executor for the per-row transport/settling loops (set by the owning subsystem each step). */
    Parallel parallel = Parallel.sequential();

    AshGrid(int originX, int originZ, int cellSize, int cells) {
        this.originX = originX;
        this.originZ = originZ;
        this.cellSize = cellSize;
        this.cells = cells;
        int area = cells * cells;
        this.airborne = new double[GrainClass.COUNT][area];
        this.deposit = new double[area];
        this.applied = new double[area];
        this.depositionRate = new double[area];
        this.scratch = new double[area];
        this.rowScratch = new double[cells];
    }

    static AshGrid centeredOn(BlockPos center, int cellSize, int cells) {
        // The vent column sits in the middle of a cell (not on the corner of four), so a source at the
        // vent spreads symmetrically over the cells around it.
        int half = cells * cellSize / 2 + cellSize / 2;
        return new AshGrid(center.x() - half, center.z() - half, cellSize, cells);
    }

    int index(int i, int j) {
        return j * cells + i;
    }

    /** Cell index containing the column, or -1 outside the grid. */
    int cellAt(int x, int z) {
        int i = Math.floorDiv(x - originX, cellSize);
        int j = Math.floorDiv(z - originZ, cellSize);
        if (i < 0 || j < 0 || i >= cells || j >= cells) return -1;
        return index(i, j);
    }

    double cellArea() {
        return (double) cellSize * cellSize;
    }

    double thickness(int cell, double bulkDensity) {
        return deposit[cell] / (bulkDensity * cellArea());
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

    /**
     * Adds {@code mass} kg split by {@code fractions}, spread as a Gaussian of {@code sigma} blocks
     * around {@code (cx, cz)} (the umbrella cloud). Mass that would land outside the grid is exported.
     */
    void inject(double mass, double[] fractions, double cx, double cz, double sigma) {
        if (mass <= 0) return;
        emitted += mass;
        double reach = 3 * sigma;
        int i0 = Math.max(0, (int) Math.floor((cx - reach - originX) / cellSize));
        int i1 = Math.min(cells - 1, (int) Math.floor((cx + reach - originX) / cellSize));
        int j0 = Math.max(0, (int) Math.floor((cz - reach - originZ) / cellSize));
        int j1 = Math.min(cells - 1, (int) Math.floor((cz + reach - originZ) / cellSize));

        // Normalise against the full (unclipped) kernel so mass outside the grid counts as exported.
        // The Gaussian is separable: exp(-(x²+z²)/2σ²) = ex(i)·ez(j), so one exp per row and column.
        int fi0 = (int) Math.floor((cx - reach - originX) / cellSize);
        int fi1 = (int) Math.floor((cx + reach - originX) / cellSize);
        int fj0 = (int) Math.floor((cz - reach - originZ) / cellSize);
        int fj1 = (int) Math.floor((cz + reach - originZ) / cellSize);
        double[] ex = new double[fi1 - fi0 + 1];
        double[] dxs = new double[ex.length];
        double[] ez = new double[fj1 - fj0 + 1];
        double[] dzs = new double[ez.length];
        double twoSigma2 = 2 * sigma * sigma;
        for (int i = fi0; i <= fi1; i++) {
            double x = originX + (i + 0.5) * cellSize - cx;
            dxs[i - fi0] = x * x;
            ex[i - fi0] = StrictMath.exp(-x * x / twoSigma2);
        }
        for (int j = fj0; j <= fj1; j++) {
            double z = originZ + (j + 0.5) * cellSize - cz;
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
        double placed = rows <= 0 ? 0 : parallel.sum(rows, r -> {
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

    /** Advection + diffusion for {@code dt} seconds. */
    void transport(double dt, Vec3d wind, double diffusivity) {
        double dx = cellSize;
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

    /** First-order fallout for {@code dt} seconds from a plume of height {@code height} blocks. */
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

    /** Adds deposit directly (kg) to a cell. Package-private for tests and scripted events. */
    void addDeposit(int cell, double mass) {
        deposit[cell] += mass;
        emitted += mass;
        deposited += mass;
    }

    /**
     * Converts new deposit thickness into block changes.
     *
     * <p>Per column the cell thickness is multiplied by a fixed pseudo-random factor
     * {@code 1 ± jitter} so cover boundaries do not follow the cell grid. Thin deposits replace the
     * surface (compare-and-set against the terrain model's surface id, skipping water, lava and air);
     * whole-block deposits stack {@link AshPalette#wholeBlock()} on the column and raise the ground.
     * Columns the terrain model does not know are skipped.
     */
    void applyDeposits(TerrainModel terrain, Outbox outbox, TephraConfig config) {
        applyDeposits(terrain, outbox, config, me.alex4386.typhon.engine.world.UnitTable.UNATTRIBUTED);
    }

    void applyDeposits(TerrainModel terrain, Outbox outbox, TephraConfig config, int unit) {
        applyDeposits(terrain, outbox, config, unit, (x, z) -> false);
    }

    /**
     * @param molten columns currently covered by molten lava: ash falling there is taken up by the
     *     flow (it becomes part of the lava's surface) instead of forming a layer beneath it
     */
    void applyDeposits(TerrainModel terrain, Outbox outbox, TephraConfig config, int unit,
            java.util.function.BiPredicate<Integer, Integer> molten) {
        AshPalette palette = config.palette;
        double minVisible = palette.minimumVisibleThickness();
        double jitter = config.depositJitter;
        WorldModel world = terrain.world();
        double metersPerBlock = world.spec().metersPerColumn();

        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                int cell = index(i, j);
                double t1 = thickness(cell, config.depositBulkDensity);
                double t0 = applied[cell];
                if (t1 - t0 < config.depositUpdateThickness) continue;
                boolean visible = t1 * (1 + jitter) >= minVisible;

                int x0 = originX + i * cellSize;
                int z0 = originZ + j * cellSize;
                for (int z = z0; z < z0 + cellSize; z++) {
                    for (int x = x0; x < x0 + cellSize; x++) {
                        double f = 1 + jitter * columnNoise(x, z);
                        if (molten.test(x, z)) continue;
                        // the world model records every millimetre as a loose fall layer (real m)
                        world.deposit(x, z, (t1 - t0) * f * metersPerBlock, MaterialTable.ASH, unit);
                        if (visible) applyColumn(terrain, outbox, palette, x, z, t0 * f, t1 * f);
                    }
                }
                applied[cell] = t1;
            }
        }
    }

    private static void applyColumn(
            TerrainModel terrain, Outbox outbox, AshPalette palette, int x, int z, double before, double after) {
        TerrainColumn column = terrain.column(x, z);
        if (column == null) return;

        int whole0 = palette.wholeBlocks(before);
        int whole1 = palette.wholeBlocks(after);
        if (whole1 > whole0) {
            int y = column.groundY();
            for (int b = whole0; b < whole1; b++) {
                y++;
                if (y > BlockPos.MAX_Y) break;
                BlockId expected = column.waterY() != TerrainColumn.NO_WATER && y <= column.waterY() ? WATER : BlockId.AIR;
                outbox.setBlock(BlockChange.replace(new BlockPos(x, y, z), expected, palette.wholeBlock()));
                terrain.updateBlockCache(x, z, y, palette.wholeBlock());
            }
            return;
        }
        if (whole1 > 0) return;

        int stage0 = palette.coverStage(before);
        int stage1 = palette.coverStage(after);
        if (stage1 <= stage0 || column.submerged() || NOT_COVERABLE.contains(column.surface())) return;
        BlockId cover = palette.covers().get(stage1).block();
        if (cover.equals(column.surface())) return;
        outbox.setBlock(BlockChange.replace(new BlockPos(x, column.groundY(), z), column.surface(), cover));
        terrain.updateBlockCache(x, z, column.groundY(), cover);
    }

    /**
     * Columns {@code [x0, x0+size) × [z0, z0+size)} just became simulated: lays down the fall deposit
     * already converted for the rest of their cells ({@code applied}), so the ground gets the ash that fell
     * on it before it was simulated (the cell grid kept it). Later increments arrive through
     * {@link #applyDeposits} like everywhere else.
     */
    void backfill(WorldModel world, int unit, double jitter, int x0, int z0, int size) {
        double metersPerBlock = world.spec().metersPerColumn();
        for (int z = z0; z < z0 + size; z++) {
            for (int x = x0; x < x0 + size; x++) {
                int cell = cellAt(x, z);
                if (cell < 0 || !(applied[cell] > 0) || !world.isKnown(x, z)) continue;
                double f = 1 + jitter * columnNoise(x, z);
                world.deposit(x, z, applied[cell] * f * metersPerBlock, MaterialTable.ASH, unit);
            }
        }
    }

    /**
     * Reports the cells whose deposit is at least {@code minBlocks} thick: one sample column every
     * {@code stride} columns across each such cell.
     */
    void reportDeposits(double minBlocks, double bulkDensity, int stride,
            me.alex4386.typhon.engine.expansion.ExpansionActivity.Sink sink) {
        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                int cell = index(i, j);
                if (!(deposit[cell] > 0) || thickness(cell, bulkDensity) < minBlocks) continue;
                int x0 = originX + i * cellSize;
                int z0 = originZ + j * cellSize;
                for (int z = z0; z < z0 + cellSize; z += stride) {
                    for (int x = x0; x < x0 + cellSize; x += stride) sink.active(x, z);
                }
                sink.active(x0 + cellSize - 1, z0 + cellSize - 1);
            }
        }
    }

    /** Deterministic per-column noise in [-1, 1). */
    static double columnNoise(int x, int z) {
        long h = x * 0x9e3779b97f4a7c15L ^ z * 0xc2b2ae3d27d4eb4fL;
        h = (h ^ (h >>> 30)) * 0xbf58476d1ce4e5b9L;
        h = (h ^ (h >>> 27)) * 0x94d049bb133111ebL;
        h ^= h >>> 31;
        return ((h >>> 11) * 0x1.0p-53) * 2 - 1;
    }

    /** World-space centre of a cell (y = 0). */
    BlockPos cellCenter(int i, int j) {
        return new BlockPos(originX + i * cellSize + cellSize / 2, 0, originZ + j * cellSize + cellSize / 2);
    }

    void save(JsonObject out) {
        out.addProperty("originX", originX);
        out.addProperty("originZ", originZ);
        out.addProperty("cellSize", cellSize);
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
                in.get("cellSize").getAsInt(),
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
