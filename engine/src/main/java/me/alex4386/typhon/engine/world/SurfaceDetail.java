package me.alex4386.typhon.engine.world;

import java.util.Arrays;
import java.util.function.DoubleBinaryOperator;

/**
 * A finer surface nested in the world model's columns: each column of a square region is split into
 * {@code r × r} detail cells of {@code L / r} metres, so craters, rims and gullies narrower than a
 * column stay visible.
 *
 * <p><b>Conservation.</b> The columns are the truth: every physical subsystem (lava, mass flows,
 * tephra, subsurface) works on them. The detail cells of a column always average to the column's
 * surface, so the fine level adds shape but no volume, and there are no seams with the coarse level.
 *
 * <p><b>Shape.</b> A cell's elevation is a smooth <em>base</em> surface through the column surfaces plus
 * a stored <em>residual</em>. The base interpolates the column centres bilinearly and is shifted per
 * column to average exactly to the column's surface; it follows every column change at once and
 * carries the curvature between columns (a cone flank stays a smooth cone). The residual holds what the
 * columns cannot show — a crater, a rim, a gully — and averages to zero over each column. When a column
 * rises, the deposit first fills the residual's hollows (lava ponds in a crater, ash fills a gully);
 * when it falls, erosion first cuts the residual's highs. Elsewhere the change is the base's, so small
 * repeated deposits drape the ground instead of flattening each column into a level or tilted facet.
 *
 * <p><b>Edits.</b> Sub-column edits ({@link #lower}, {@link #raise}) change one cell and apply the
 * matching {@code volume / column area} to the column through {@link WorldEdit}, so the columns stay
 * the truth and the detail stays consistent with them.
 *
 * <p>Coordinates: detail cell {@code (fx, fz)} lies in column {@code (floorDiv(fx, r), floorDiv(fz, r))}
 * and covers metres {@code [fx·L/r, (fx+1)·L/r)}. Elevations exclude ground deformation (add
 * {@link WorldModel#uplift} of the column, as for the columns themselves).
 */
public final class SurfaceDetail {
    /** Below this a column change is not redistributed (m). */
    static final double EPS = 1e-6;

    private final WorldModel world;
    private final int x0;
    private final int z0;
    private final int columns;
    private final int r;
    private final int fineWidth;
    /** Detail cell residuals (m) over the base surface, row-major from (x0·r, z0·r); zero mean per column. */
    private final float[] residual;
    /** Column surface the residual was last reconciled with (m), NaN = not yet initialised. */
    private final double[] seen;
    /** Column version at the last reconcile ({@link WorldModel#version}). */
    private final int[] seenVersion;
    private final DoubleBinaryOperator relief;
    private final double[] scratch;
    private final double[] base;

    /**
     * @param x0 first column of the region (west)
     * @param z0 first column of the region (north, smallest z)
     * @param columns region width and depth in columns
     * @param refinement detail cells per column edge ({@code r ≥ 1})
     * @param relief initial shape, elevation (m) at a point (m) in world coordinates, or {@code null}
     *     for the smooth base surface alone; only its shape inside each column matters, the column's own
     *     surface sets the mean
     */
    public SurfaceDetail(WorldModel world, int x0, int z0, int columns, int refinement, DoubleBinaryOperator relief) {
        if (columns < 1) throw new IllegalArgumentException("columns must be >= 1");
        if (refinement < 1 || refinement > 16) throw new IllegalArgumentException("refinement must be in [1, 16]");
        this.world = world;
        this.x0 = x0;
        this.z0 = z0;
        this.columns = columns;
        this.r = refinement;
        this.fineWidth = columns * refinement;
        this.residual = new float[fineWidth * fineWidth];
        this.seen = new double[columns * columns];
        this.seenVersion = new int[columns * columns];
        this.relief = relief;
        this.scratch = new double[r * r];
        this.base = new double[r * r];
        Arrays.fill(seen, Double.NaN);
    }

    /** Region of {@code radiusColumns} around column ({@code cx}, {@code cz}). */
    public static SurfaceDetail around(WorldModel world, int cx, int cz, int radiusColumns, int refinement,
            DoubleBinaryOperator relief) {
        return new SurfaceDetail(world, cx - radiusColumns, cz - radiusColumns, 2 * radiusColumns + 1, refinement, relief);
    }

    // ── Geometry ──

    public int refinement() {
        return r;
    }

    /** Detail cell size (m). */
    public double cellMeters() {
        return world.spec().metersPerColumn() / r;
    }

    public int minColumnX() {
        return x0;
    }

    public int minColumnZ() {
        return z0;
    }

    public int columns() {
        return columns;
    }

    public boolean containsColumn(int x, int z) {
        return x >= x0 && x < x0 + columns && z >= z0 && z < z0 + columns;
    }

    public boolean contains(int fx, int fz) {
        return containsColumn(Math.floorDiv(fx, r), Math.floorDiv(fz, r));
    }

    private int fineIndex(int fx, int fz) {
        return (fz - z0 * r) * fineWidth + (fx - x0 * r);
    }

    private int columnIndex(int x, int z) {
        return (z - z0) * columns + (x - x0);
    }

    // ── Reading ──

    /**
     * Elevation (m, without uplift) of detail cell ({@code fx}, {@code fz}): the base surface through the
     * current column surfaces plus the cell's residual. {@code NaN} outside the region or over unknown
     * columns. Does not modify state (safe from readers between steps).
     */
    public double elevation(int fx, int fz) {
        if (!contains(fx, fz)) return Double.NaN;
        int x = Math.floorDiv(fx, r);
        int z = Math.floorDiv(fz, r);
        if (Double.isNaN(world.surfaceZ(x, z))) return Double.NaN;
        double[] b = new double[r * r];
        baseCells(x, z, b);
        int k = (fz - z * r) * r + (fx - x * r);
        return b[k] + residualOf(x, z, k);
    }

    /**
     * Elevations (m, without uplift) of the {@code r × r} cells of column ({@code x}, {@code z}) into
     * {@code out}, row-major from its north-west cell ({@code out[j·r + i]} is cell
     * {@code (x·r + i, z·r + j)}); NaN outside the region or over an unknown column. Like
     * {@link #elevation} for every cell, with one column lookup.
     */
    public void columnCells(int x, int z, float[] out) {
        double surface = containsColumn(x, z) ? world.surfaceZ(x, z) : Double.NaN;
        if (Double.isNaN(surface)) {
            Arrays.fill(out, 0, r * r, Float.NaN);
            return;
        }
        double[] b = new double[r * r];
        baseCells(x, z, b);
        if (Double.isNaN(seen[columnIndex(x, z)])) {
            double[] offsets = new double[r * r];
            initialResidual(x, z, b, offsets);
            for (int k = 0; k < r * r; k++) out[k] = (float) (b[k] + offsets[k]);
            return;
        }
        for (int j = 0; j < r; j++) {
            int row = fineIndex(x * r, z * r + j);
            for (int i = 0; i < r; i++) out[j * r + i] = (float) (b[j * r + i] + residual[row + i]);
        }
    }

    /** Residual of cell {@code k} of column (x, z): stored, or the initial one before the first reconcile. */
    private double residualOf(int x, int z, int k) {
        if (Double.isNaN(seen[columnIndex(x, z)])) {
            double[] b = new double[r * r];
            double[] offsets = new double[r * r];
            baseCells(x, z, b);
            initialResidual(x, z, b, offsets);
            return offsets[k];
        }
        return residual[fineIndex(x * r + k % r, z * r + k / r)];
    }

    /** Mean of the detail cells of column ({@code x}, {@code z}) — equal to its surface (m). */
    public double columnMean(int x, int z) {
        double sum = 0;
        for (int j = 0; j < r; j++) {
            for (int i = 0; i < r; i++) sum += elevation(x * r + i, z * r + j);
        }
        return sum / (r * r);
    }

    // ── The base surface ──

    /**
     * The base surface over the cells of column (x, z) into {@code out}: bilinear between the column
     * centres (a missing neighbour repeats this column), shifted to average exactly to the column's surface.
     */
    private void baseCells(int x, int z, double[] out) {
        double mean = 0;
        for (int j = 0; j < r; j++) {
            for (int i = 0; i < r; i++) {
                double v = bilinear(x, z, i, j);
                out[j * r + i] = v;
                mean += v;
            }
        }
        double shift = world.surfaceZ(x, z) - mean / (r * r);
        for (int k = 0; k < r * r; k++) out[k] += shift;
    }

    /** Bilinear interpolation between column-centre surfaces at cell (i, j) of column (x, z). */
    private double bilinear(int x, int z, int i, int j) {
        double u = (i + 0.5) / r - 0.5; // offset from the column centre, in columns
        double v = (j + 0.5) / r - 0.5;
        int nx = u < 0 ? x - 1 : x + 1;
        int nz = v < 0 ? z - 1 : z + 1;
        double s00 = world.surfaceZ(x, z);
        double s10 = orSelf(world.surfaceZ(nx, z), s00);
        double s01 = orSelf(world.surfaceZ(x, nz), s00);
        double s11 = orSelf(world.surfaceZ(nx, nz), s00);
        double a = Math.abs(u);
        double b = Math.abs(v);
        return (1 - a) * (1 - b) * s00 + a * (1 - b) * s10 + (1 - a) * b * s01 + a * b * s11;
    }

    private static double orSelf(double v, double self) {
        return Double.isNaN(v) ? self : v;
    }

    /**
     * The initial residual of column ({@code x}, {@code z}) into {@code out}: the relief (this detail's,
     * else the world's) at the cell centres minus the base {@code b}, zero mean. Without a relief, zero.
     */
    private void initialResidual(int x, int z, double[] b, double[] out) {
        DoubleBinaryOperator shape = relief != null ? relief : world.relief();
        if (shape == null) {
            Arrays.fill(out, 0, r * r, 0);
            return;
        }
        double size = world.spec().metersPerColumn();
        double cell = size / r;
        double mean = 0;
        for (int j = 0; j < r; j++) {
            for (int i = 0; i < r; i++) {
                double v = shape.applyAsDouble(x * size + (i + 0.5) * cell, z * size + (j + 0.5) * cell);
                double d = Double.isNaN(v) ? 0 : v - b[j * r + i];
                out[j * r + i] = d;
                mean += d;
            }
        }
        mean /= r * r;
        for (int k = 0; k < r * r; k++) out[k] -= mean;
    }

    // ── Reconciling with the columns ──

    /** Brings every column of the region up to date; returns how many changed. */
    public int reconcileAll() {
        int changed = 0;
        for (int z = z0; z < z0 + columns; z++) {
            for (int x = x0; x < x0 + columns; x++) {
                if (reconcile(x, z)) changed++;
            }
        }
        return changed;
    }

    /**
     * Takes the change of column ({@code x}, {@code z}) since the last call into its residual: a rise
     * fills the residual's hollows first, a fall cuts its highs first (the base itself already follows
     * the column).
     */
    public boolean reconcile(int x, int z) {
        if (!containsColumn(x, z)) return false;
        int c = columnIndex(x, z);
        int version = world.version(x, z);
        if (!Double.isNaN(seen[c]) && version == seenVersion[c]) return false;
        double surface = world.surfaceZ(x, z);
        if (Double.isNaN(surface)) return false;
        seenVersion[c] = version;
        if (Double.isNaN(seen[c])) {
            baseCells(x, z, base);
            initialResidual(x, z, base, scratch);
            store(x, z);
            seen[c] = surface;
            return true;
        }
        double delta = surface - seen[c];
        seen[c] = surface;
        if (Math.abs(delta) < EPS) return false;
        load(x, z);
        // Fill the residual's hollows with the deposit (cut its highs with the erosion), then take the
        // amount back out evenly: the base already carries the column's change, so the net effect is that
        // the change goes into the hollows (highs) first and the residual keeps its zero mean.
        double volume = Math.abs(delta) * r * r;
        if (delta > 0) fillToLevel(volume);
        else cutToLevel(volume);
        double mean = 0;
        for (double v : scratch) mean += v;
        mean /= scratch.length;
        for (int k = 0; k < scratch.length; k++) scratch[k] -= mean;
        store(x, z);
        return true;
    }

    private void load(int x, int z) {
        for (int j = 0; j < r; j++) {
            for (int i = 0; i < r; i++) scratch[j * r + i] = residual[fineIndex(x * r + i, z * r + j)];
        }
    }

    private void store(int x, int z) {
        for (int j = 0; j < r; j++) {
            for (int i = 0; i < r; i++) residual[fineIndex(x * r + i, z * r + j)] = (float) scratch[j * r + i];
        }
    }

    /** Raises the lowest values to a common level holding {@code volume} (m × cells). */
    private void fillToLevel(double volume) {
        double[] s = scratch.clone();
        Arrays.sort(s);
        int n = s.length;
        double level = s[n - 1] + (volume - excessBelow(s, s[n - 1])) / n;
        for (int k = 1; k < n; k++) {
            // filling the k lowest cells up to s[k] takes Σ (s[k] − s[i]) for i < k
            double needed = excessBelow(s, s[k]);
            if (needed >= volume) {
                level = s[k - 1] + (volume - excessBelow(s, s[k - 1])) / k;
                break;
            }
        }
        for (int i = 0; i < n; i++) scratch[i] = Math.max(scratch[i], level);
    }

    /** Lowers the highest values to a common level removing {@code volume} (m × cells). */
    private void cutToLevel(double volume) {
        for (int i = 0; i < scratch.length; i++) scratch[i] = -scratch[i];
        fillToLevel(volume);
        for (int i = 0; i < scratch.length; i++) scratch[i] = -scratch[i];
    }

    private static double excessBelow(double[] sorted, double level) {
        double sum = 0;
        for (double v : sorted) {
            if (v >= level) break;
            sum += level - v;
        }
        return sum;
    }

    // ── Sub-column edits ──

    /**
     * Digs {@code depth} metres out of detail cell ({@code fx}, {@code fz}) (crater excavation, a
     * landslide scar). The column loses {@code depth / r²} metres through {@link WorldEdit#erode};
     * returns what the column lost (multiply by the column area for the volume removed).
     */
    public ErodeResult lower(int fx, int fz, double depth) {
        if (!contains(fx, fz) || !(depth > 0)) return ErodeResult.NONE;
        int x = Math.floorDiv(fx, r);
        int z = Math.floorDiv(fz, r);
        reconcile(x, z);
        if (Double.isNaN(seen[columnIndex(x, z)])) return ErodeResult.NONE;
        float[] before = snapshot(x, z);
        ErodeResult removed = world.erode(x, z, depth / (r * r), false);
        if (removed.removedM() <= 0) return removed;
        settle(x, z, before, fx, fz, -removed.removedM() * r * r);
        return removed;
    }

    /**
     * Lays {@code thickness} metres of {@code material} on detail cell ({@code fx}, {@code fz}) (rim
     * building, a landslide deposit). The column gains {@code thickness / r²} metres through
     * {@link WorldEdit#deposit}. Returns false over unknown columns.
     */
    public boolean raise(int fx, int fz, double thickness, Material material, int unit) {
        if (!contains(fx, fz) || !(thickness > 0)) return false;
        int x = Math.floorDiv(fx, r);
        int z = Math.floorDiv(fz, r);
        reconcile(x, z);
        if (Double.isNaN(seen[columnIndex(x, z)])) return false;
        float[] before = snapshot(x, z);
        if (!world.deposit(x, z, thickness / (r * r), material, unit)) return false;
        settle(x, z, before, fx, fz, thickness);
        return true;
    }

    /** Cell elevations of the 3 × 3 columns around (x, z) (row-major, {@code 3r × 3r}; NaN where unknown). */
    private float[] snapshot(int x, int z) {
        float[] out = new float[9 * r * r];
        float[] column = new float[r * r];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                columnCells(x + dx, z + dz, column);
                for (int j = 0; j < r; j++) {
                    for (int i = 0; i < r; i++) {
                        out[((dz + 1) * r + j) * 3 * r + (dx + 1) * r + i] = column[j * r + i];
                    }
                }
            }
        }
        return out;
    }

    /**
     * After a sub-column edit of cell ({@code fx}, {@code fz}) by {@code cellChange} (m): every other cell of
     * the 3 × 3 columns around keeps the elevation it had ({@code before}) — the edit changed the column's
     * surface, which moves the base there too — and the edited cell moves by {@code cellChange}. The
     * residuals are set to match; each column's still average to zero (its cells average to its surface).
     */
    private void settle(int x, int z, float[] before, int fx, int fz, double cellChange) {
        double[] b = new double[r * r];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int cx = x + dx;
                int cz = z + dz;
                if (!containsColumn(cx, cz) || Double.isNaN(seen[columnIndex(cx, cz)])) continue;
                if (Double.isNaN(world.surfaceZ(cx, cz))) continue;
                baseCells(cx, cz, b);
                for (int j = 0; j < r; j++) {
                    for (int i = 0; i < r; i++) {
                        int gx = cx * r + i;
                        int gz = cz * r + j;
                        double target = before[((dz + 1) * r + j) * 3 * r + (dx + 1) * r + i];
                        if (gx == fx && gz == fz) target += cellChange;
                        residual[fineIndex(gx, gz)] = (float) (target - b[j * r + i]);
                    }
                }
            }
        }
        int c = columnIndex(x, z);
        seen[c] = world.surfaceZ(x, z);
        seenVersion[c] = world.version(x, z);
    }

    // ── Persistence ──

    /** Detail residuals, reconciled column surfaces and versions, for saving. */
    public float[] residuals() {
        return residual;
    }

    public double[] seenSurfaces() {
        return seen;
    }

    public int[] seenVersions() {
        return seenVersion;
    }

    /** Restores state saved from {@link #residuals}, {@link #seenSurfaces} and {@link #seenVersions}. */
    public void restore(float[] savedResiduals, double[] savedSeen, int[] savedVersions) {
        if (savedResiduals.length != residual.length || savedSeen.length != seen.length
                || savedVersions.length != seenVersion.length) {
            throw new IllegalStateException("surface detail region size changed");
        }
        System.arraycopy(savedResiduals, 0, residual, 0, residual.length);
        System.arraycopy(savedSeen, 0, seen, 0, seen.length);
        System.arraycopy(savedVersions, 0, seenVersion, 0, seenVersion.length);
    }
}
