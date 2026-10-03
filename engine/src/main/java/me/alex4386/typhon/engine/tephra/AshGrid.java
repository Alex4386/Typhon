package me.alex4386.typhon.engine.tephra;

import com.google.gson.JsonObject;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.Outbox;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
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
    }

    static AshGrid centeredOn(BlockPos center, int cellSize, int cells) {
        int half = cells * cellSize / 2;
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
        for (double[] layer : airborne) for (double m : layer) total += m;
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
        double fullWeight = 0;
        int fi0 = (int) Math.floor((cx - reach - originX) / cellSize);
        int fi1 = (int) Math.floor((cx + reach - originX) / cellSize);
        int fj0 = (int) Math.floor((cz - reach - originZ) / cellSize);
        int fj1 = (int) Math.floor((cz + reach - originZ) / cellSize);
        for (int j = fj0; j <= fj1; j++) {
            for (int i = fi0; i <= fi1; i++) fullWeight += kernel(i, j, cx, cz, sigma);
        }
        if (fullWeight <= 0) {
            exported += mass;
            return;
        }

        double placed = 0;
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                double w = kernel(i, j, cx, cz, sigma) / fullWeight;
                if (w <= 0) continue;
                int cell = index(i, j);
                for (int c = 0; c < GrainClass.COUNT; c++) {
                    double m = mass * w * fractions[c];
                    airborne[c][cell] += m;
                    placed += m;
                }
            }
        }
        exported += Math.max(0, mass - placed);
    }

    private double kernel(int i, int j, double cx, double cz, double sigma) {
        double x = originX + (i + 0.5) * cellSize - cx;
        double z = originZ + (j + 0.5) * cellSize - cz;
        double r2 = x * x + z * z;
        if (r2 > 9 * sigma * sigma) return 0;
        return StrictMath.exp(-r2 / (2 * sigma * sigma));
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

    private void transportLayer(double[] layer, double cx, double cz, double d) {
        double[] next = scratch;
        System.arraycopy(layer, 0, next, 0, layer.length);
        double ax = Math.abs(cx), az = Math.abs(cz);
        int stepX = cx >= 0 ? 1 : -1;
        int stepZ = cz >= 0 ? 1 : -1;

        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                int cell = index(i, j);
                double m = layer[cell];
                if (m == 0) continue;
                double toX = m * ax, toZ = m * az, toEach = m * d;
                next[cell] -= toX + toZ + 4 * toEach;
                give(next, i + stepX, j, toX);
                give(next, i, j + stepZ, toZ);
                give(next, i + 1, j, toEach);
                give(next, i - 1, j, toEach);
                give(next, i, j + 1, toEach);
                give(next, i, j - 1, toEach);
            }
        }
        System.arraycopy(next, 0, layer, 0, layer.length);
    }

    private void give(double[] next, int i, int j, double m) {
        if (m == 0) return;
        if (i < 0 || j < 0 || i >= cells || j >= cells) {
            exported += m;
        } else {
            next[index(i, j)] += m;
        }
    }

    /** First-order fallout for {@code dt} seconds from a plume of height {@code height} blocks. */
    void settle(double dt, double[] settlingVelocities, double height) {
        double h = Math.max(1, height);
        java.util.Arrays.fill(depositionRate, 0);
        for (int c = 0; c < GrainClass.COUNT; c++) {
            double fraction = 1 - StrictMath.exp(-settlingVelocities[c] * dt / h);
            double[] layer = airborne[c];
            for (int cell = 0; cell < layer.length; cell++) {
                double m = layer[cell];
                if (m == 0) continue;
                double fall = m * fraction;
                layer[cell] = m - fall;
                deposit[cell] += fall;
                depositionRate[cell] += fall / dt;
                deposited += fall;
            }
        }
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
        AshPalette palette = config.palette;
        double minVisible = palette.minimumVisibleThickness();
        double jitter = config.depositJitter;

        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                int cell = index(i, j);
                double t1 = thickness(cell, config.depositBulkDensity);
                double t0 = applied[cell];
                if (t1 - t0 < config.depositUpdateThickness) continue;
                if (t1 * (1 + jitter) < minVisible) continue;

                int x0 = originX + i * cellSize;
                int z0 = originZ + j * cellSize;
                for (int z = z0; z < z0 + cellSize; z++) {
                    for (int x = x0; x < x0 + cellSize; x++) {
                        double f = 1 + jitter * columnNoise(x, z);
                        applyColumn(terrain, outbox, palette, x, z, t0 * f, t1 * f);
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
                terrain.setGround(x, z, y, palette.wholeBlock());
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
        terrain.setGround(x, z, column.groundY(), cover);
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
        for (GrainClass c : GrainClass.values()) {
            out.addProperty("airborne." + c.name().toLowerCase(), StateCodec.encodeSparse(airborne[c.ordinal()]));
        }
        out.addProperty("deposit", StateCodec.encodeSparse(deposit));
        out.addProperty("applied", StateCodec.encodeSparse(applied));
        out.addProperty("depositionRate", StateCodec.encodeSparse(depositionRate));
    }

    static AshGrid load(JsonObject in) {
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
            StateCodec.decodeSparse(in.get("airborne." + c.name().toLowerCase()).getAsString(), grid.airborne[c.ordinal()]);
        }
        StateCodec.decodeSparse(in.get("deposit").getAsString(), grid.deposit);
        StateCodec.decodeSparse(in.get("applied").getAsString(), grid.applied);
        StateCodec.decodeSparse(in.get("depositionRate").getAsString(), grid.depositionRate);
        return grid;
    }
}
