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
 * When a column's surface changes, the change is redistributed over its cells by
 * {@link #reconcile}: a deposit fills the lowest cells first (lava ponds flat, ash fills hollows),
 * erosion cuts the highest first. Both are "water-filling" operations, so several small changes
 * give the same result as one large one — the outcome does not depend on how often the detail is
 * reconciled.
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
    /** Detail cell elevations (m), row-major from (x0·r, z0·r). */
    private final float[] cells;
    /** Column surface the cells were last reconciled with (m), NaN = not yet initialised. */
    private final double[] seen;
    /** Column version at the last reconcile ({@link WorldModel#version}). */
    private final int[] seenVersion;
    private final DoubleBinaryOperator relief;
    private final double[] scratch;

    /**
     * @param x0 first column of the region (west)
     * @param z0 first column of the region (north, smallest z)
     * @param columns region width and depth in columns
     * @param refinement detail cells per column edge ({@code r ≥ 1})
     * @param relief initial shape, elevation (m) at a point (m) in world coordinates, or {@code null}
     *     for a smooth (bilinear) surface through the column centres; only its shape inside each
     *     column matters, the column's own surface sets the mean
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
        this.cells = new float[fineWidth * fineWidth];
        this.seen = new double[columns * columns];
        this.seenVersion = new int[columns * columns];
        this.relief = relief;
        this.scratch = new double[r * r];
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
     * Elevation (m, without uplift) of detail cell ({@code fx}, {@code fz}): the reconciled cell plus
     * any change of its column since, spread evenly. {@code NaN} outside the region or over unknown
     * columns. Does not modify state (safe from readers between steps).
     */
    public double elevation(int fx, int fz) {
        if (!contains(fx, fz)) return Double.NaN;
        int x = Math.floorDiv(fx, r);
        int z = Math.floorDiv(fz, r);
        double surface = world.surfaceZ(x, z);
        if (Double.isNaN(surface)) return Double.NaN;
        int c = columnIndex(x, z);
        if (Double.isNaN(seen[c])) {
            double[] offsets = new double[r * r];
            initialOffsets(x, z, offsets);
            return surface + offsets[(fz - z * r) * r + (fx - x * r)];
        }
        return cells[fineIndex(fx, fz)] + (surface - seen[c]);
    }

    /** Mean of the detail cells of column ({@code x}, {@code z}) — equal to its surface (m). */
    public double columnMean(int x, int z) {
        double sum = 0;
        for (int j = 0; j < r; j++) {
            for (int i = 0; i < r; i++) sum += elevation(x * r + i, z * r + j);
        }
        return sum / (r * r);
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

    /** Redistributes the change of column ({@code x}, {@code z}) since the last call over its cells. */
    public boolean reconcile(int x, int z) {
        if (!containsColumn(x, z)) return false;
        int c = columnIndex(x, z);
        int version = world.version(x, z);
        if (!Double.isNaN(seen[c]) && version == seenVersion[c]) return false;
        double surface = world.surfaceZ(x, z);
        if (Double.isNaN(surface)) return false;
        seenVersion[c] = version;
        if (Double.isNaN(seen[c])) {
            initialOffsets(x, z, scratch);
            for (int j = 0; j < r; j++) {
                for (int i = 0; i < r; i++) {
                    cells[fineIndex(x * r + i, z * r + j)] = (float) (surface + scratch[j * r + i]);
                }
            }
            seen[c] = surface;
            return true;
        }
        double delta = surface - seen[c];
        if (Math.abs(delta) < EPS) return false;
        load(x, z);
        if (delta > 0) fillToLevel(delta * r * r);
        else cutToLevel(-delta * r * r);
        store(x, z, surface);
        return true;
    }

    private void load(int x, int z) {
        for (int j = 0; j < r; j++) {
            for (int i = 0; i < r; i++) scratch[j * r + i] = cells[fineIndex(x * r + i, z * r + j)];
        }
    }

    /** Writes the scratch cells back, shifted so that they average exactly to {@code surface}. */
    private void store(int x, int z, double surface) {
        double mean = 0;
        for (double v : scratch) mean += v;
        mean /= scratch.length;
        double shift = surface - mean;
        for (int j = 0; j < r; j++) {
            for (int i = 0; i < r; i++) cells[fineIndex(x * r + i, z * r + j)] = (float) (scratch[j * r + i] + shift);
        }
        seen[columnIndex(x, z)] = surface;
    }

    /** Raises the lowest cells to a common level holding {@code volume} (m × cells). */
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

    /** Lowers the highest cells to a common level removing {@code volume} (m × cells). */
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

    /**
     * Shape of the initial surface inside column ({@code x}, {@code z}), zero mean, into {@code out}
     * (row-major {@code r × r}): the relief (this detail's, else the world's) at the cell centres, or
     * the bilinear surface through neighbouring column centres.
     */
    private void initialOffsets(int x, int z, double[] out) {
        double size = world.spec().metersPerColumn();
        double cell = size / r;
        DoubleBinaryOperator shape = relief != null ? relief : world.relief();
        double mean = 0;
        for (int j = 0; j < r; j++) {
            for (int i = 0; i < r; i++) {
                double v = shape != null
                        ? shape.applyAsDouble(x * size + (i + 0.5) * cell, z * size + (j + 0.5) * cell)
                        : bilinear(x, z, i, j);
                if (Double.isNaN(v)) v = bilinear(x, z, i, j);
                out[j * r + i] = v;
                mean += v;
            }
        }
        mean /= r * r;
        for (int k = 0; k < r * r; k++) out[k] -= mean;
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
        ErodeResult removed = world.erode(x, z, depth / (r * r), false);
        if (removed.removedM() <= 0) return removed;
        int f = fineIndex(fx, fz);
        cells[f] -= (float) (removed.removedM() * r * r);
        settle(x, z);
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
        if (!world.deposit(x, z, thickness / (r * r), material, unit)) return false;
        cells[fineIndex(fx, fz)] += (float) thickness;
        settle(x, z);
        return true;
    }

    /** Marks column (x, z) reconciled after a sub-column edit already placed its volume. */
    private void settle(int x, int z) {
        int c = columnIndex(x, z);
        load(x, z);
        store(x, z, world.surfaceZ(x, z));
        seenVersion[c] = world.version(x, z);
    }

    // ── Persistence ──

    /** Detail cells, reconciled column surfaces and versions, for saving. */
    public float[] cells() {
        return cells;
    }

    public double[] seenSurfaces() {
        return seen;
    }

    public int[] seenVersions() {
        return seenVersion;
    }

    /** Restores state saved from {@link #cells}, {@link #seenSurfaces} and {@link #seenVersions}. */
    public void restore(float[] savedCells, double[] savedSeen, int[] savedVersions) {
        if (savedCells.length != cells.length || savedSeen.length != seen.length || savedVersions.length != seenVersion.length) {
            throw new IllegalStateException("surface detail region size changed");
        }
        System.arraycopy(savedCells, 0, cells, 0, cells.length);
        System.arraycopy(savedSeen, 0, seen, 0, seen.length);
        System.arraycopy(savedVersions, 0, seenVersion, 0, seenVersion.length);
    }
}
