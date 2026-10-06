package me.alex4386.typhon.engine.world;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Loose granular deposits never stand steeper than their angle of repose: wherever loose material lands
 * (tephra, lapilli, bombs, wet Surtseyan tephra, debris), the column's loose top cascades downhill until
 * every local slope is within {@code L·tan φ} of its neighbours, the way grains avalanche off a growing
 * sand pile. It runs at deposition time, so a deposit of any size in a single step ends up relaxed
 * whatever the time compression or deposition rate.
 *
 * <p>The cascade is a continuous sand pile: a column whose loose top exceeds the repose limit towards its
 * steepest neighbour (8-connected, diagonals at {@code √2 L}) moves half the excess there, then it and the
 * neighbourhood are re-examined. Each move lowers the pile's potential energy by a finite amount, so it
 * terminates; moves below {@link #MIN_MOVE_M} are ignored. Only loose layers move, with their material,
 * unit, porosity and flags (volume and provenance are conserved). Consolidated rock (lava, welded tuff)
 * keeps its strength-based failure in geomorphology.
 *
 * <p>Repose angles come from {@link MaterialTable#reposeAngleDeg}. Under water the stable angle of fresh
 * volcaniclastic slopes is lower: wave and current agitation and pore-pressure transients during
 * deposition hold submarine tephra aprons at ~20–25° (Surtsey's submarine flanks; Moore 1985, Bull.
 * Volcanol. 47; Kokelaar &amp; Durant 1983), so {@code tan φ} is scaled by {@link #submergedFactor()}.
 */
public final class ReposeRelaxation {
    /** Moves thinner than this are ignored (m). */
    public static final double MIN_MOVE_M = 1e-3;
    /**
     * Slopes may exceed {@code tan φ} by this much before material moves (≈ 0.5° at φ = 33°): a pile
     * kept at repose would otherwise answer every millimetre of ash dusting with a cascade of
     * millimetre moves across its whole flank.
     */
    public static final double SLOPE_TOLERANCE_TAN = 0.01;
    /**
     * Safety bound on moves per drain (every move lowers the pile's potential energy by a finite amount, so a
     * drain ends; this only guards against a bug). Anything left stays queued for the next deposit.
     */
    static final int MAX_MOVES = 100_000_000;
    /** {@code tan φ} under water over {@code tan φ} in air (≈ 24° for 33°; see the class note). */
    public static final double DEFAULT_SUBMERGED_FACTOR = 0.7;

    private static final int[] DX = {1, 1, 0, -1, -1, -1, 0, 1};
    private static final int[] DZ = {0, 1, 1, 1, 0, -1, -1, -1};

    private final WorldModel world;
    private final TreeSet<Long> queue = new TreeSet<>();
    private boolean running;
    private double submergedFactor = DEFAULT_SUBMERGED_FACTOR;
    private long moves;
    private long boundHits;

    ReposeRelaxation(WorldModel world) {
        this.world = world;
    }

    public double submergedFactor() {
        return submergedFactor;
    }

    public void setSubmergedFactor(double factor) {
        if (!(factor > 0 && factor <= 1)) throw new IllegalArgumentException("submerged factor must be in (0, 1]");
        this.submergedFactor = factor;
    }

    /** Excess (m) over the repose limit the relaxation leaves at a neighbour {@code distance} metres away. */
    public static double toleranceM(double distance) {
        return Math.max(2 * MIN_MOVE_M, distance * SLOPE_TOLERANCE_TAN);
    }

    /** Moves made so far (diagnostics). */
    public long moves() {
        return moves;
    }

    /** Drains that stopped at {@link #MAX_MOVES} with columns still queued (should stay 0). */
    public long boundHits() {
        return boundHits;
    }

    /** Columns waiting to be examined (non-zero only if a drain hit its bound). */
    public int pending() {
        return queue.size();
    }

    /** Something changed at a column (a deposit, an excavation): relax it and its neighbourhood now. */
    public void touched(int x, int z) {
        enqueueAround(x, z);
        if (!running) drain();
    }

    /** Queues a column for examination at the next {@link #drain()} (bulk sweeps). */
    void enqueue(int x, int z) {
        queue.add(pack(x, z));
    }

    /** Relaxes everything queued. */
    public void drain() {
        if (running) return;
        running = true;
        try {
            int n = 0;
            while (!queue.isEmpty() && n < MAX_MOVES) {
                long k = queue.pollFirst();
                if (relax(unpackX(k), unpackZ(k))) n++;
            }
            moves += n;
            if (!queue.isEmpty()) boundHits++;
        } finally {
            running = false;
        }
    }

    /**
     * Worst excess (m) of a loose-topped column over a neighbour beyond its repose limit plus the relaxation's
     * tolerance ({@link #toleranceM}), over the rectangle (inclusive); ≤ 0 when every loose slope stands within
     * {@code φ +} {@link #SLOPE_TOLERANCE_TAN}.
     */
    public double worstExcessM(int x0, int z0, int x1, int z1) {
        double l = world.spec().metersPerColumn();
        double worst = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!world.isKnown(x, z) || looseTop(x, z) < MIN_MOVE_M) continue;
                double s = world.surfaceZ(x, z);
                for (int d = 0; d < 8; d++) {
                    int nx = x + DX[d];
                    int nz = z + DZ[d];
                    if (!world.isKnown(nx, nz)) continue;
                    double distance = (d & 1) == 1 ? l * Math.sqrt(2) : l;
                    worst = Math.max(worst, s - world.surfaceZ(nx, nz) - limit(x, z, distance) - toleranceM(distance));
                }
            }
        }
        return worst;
    }

    /** Where {@link #worstExcessM} finds its worst column, described for diagnostics (or "none"). */
    public String worstExcessWhere(int x0, int z0, int x1, int z1) {
        double l = world.spec().metersPerColumn();
        double worst = 0;
        String where = "none";
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!world.isKnown(x, z) || looseTop(x, z) < MIN_MOVE_M) continue;
                double s = world.surfaceZ(x, z);
                for (int d = 0; d < 8; d++) {
                    int nx = x + DX[d];
                    int nz = z + DZ[d];
                    if (!world.isKnown(nx, nz)) continue;
                    double distance = (d & 1) == 1 ? l * Math.sqrt(2) : l;
                    double e = s - world.surfaceZ(nx, nz) - limit(x, z, distance) - toleranceM(distance);
                    if (e > worst) {
                        worst = e;
                        LayerView top = world.layer(x, z, world.layerCount(x, z) - 1);
                        LayerView ntop = world.layer(nx, nz, world.layerCount(nx, nz) - 1);
                        where = String.format(java.util.Locale.ROOT,
                                "(%d,%d) %.2f m %s loose %.2f m water %.2f -> (%d,%d) %.2f m %s%s water %.2f; excess %.3f m",
                                x, z, s, top.materialInfo().name(), looseTop(x, z), world.waterZ(x, z), nx, nz,
                                world.surfaceZ(nx, nz), ntop.materialInfo().name(), ntop.loose() ? " loose" : "",
                                world.waterZ(nx, nz), e);
                    }
                }
            }
        }
        return where;
    }

    /** The steepest stable surface drop to a neighbour {@code distance} metres away from column (x, z). */
    double limit(int x, int z, double distance) {
        return distance * reposeTan(x, z);
    }

    /** {@code tan φ} of the loose top of a column (reduced under water); infinite for an empty column. */
    double reposeTan(int x, int z) {
        int n = world.layerCount(x, z);
        if (n == 0) return Double.POSITIVE_INFINITY;
        Material top = world.layer(x, z, n - 1).materialInfo();
        double tan = Math.tan(Math.toRadians(MaterialTable.reposeAngleDeg(top)));
        double water = world.waterZ(x, z);
        if (Double.isFinite(water) && water > world.surfaceZ(x, z)) tan *= submergedFactor;
        return tan;
    }

    private boolean relax(int x, int z) {
        if (!world.isKnown(x, z)) return false;
        double l = world.spec().metersPerColumn();
        double tan = reposeTan(x, z);
        if (!Double.isFinite(tan)) return false;
        double s = world.surfaceZ(x, z);
        int best = -1;
        double bestExcess = 0;
        double bestTolerance = 0;
        for (int d = 0; d < 8; d++) {
            int nx = x + DX[d];
            int nz = z + DZ[d];
            if (!world.isKnown(nx, nz)) continue;
            double distance = (d & 1) == 1 ? l * Math.sqrt(2) : l;
            double excess = s - world.surfaceZ(nx, nz) - distance * tan;
            double tolerance = Math.max(2 * MIN_MOVE_M, distance * SLOPE_TOLERANCE_TAN);
            if (excess - tolerance > bestExcess - bestTolerance) {
                bestExcess = excess;
                bestTolerance = tolerance;
                best = d;
            }
        }
        if (best < 0 || bestExcess <= bestTolerance) return false;
        double loose = looseTop(x, z);
        if (loose < MIN_MOVE_M) return false;
        double amount = Math.min(loose, bestExcess / 2);
        if (amount < MIN_MOVE_M) return false;
        move(x, z, x + DX[best], z + DZ[best], amount);
        enqueueAround(x, z);
        enqueueAround(x + DX[best], z + DZ[best]);
        return true;
    }

    /** One loose layer segment carried downhill. */
    private record Segment(double thickness, Material material, int unit, int flags, double porosity, double welding) {}

    /** Takes {@code amount} m of loose material off the top of (x, z) and lays it on (rx, rz), keeping its layers. */
    private void move(int x, int z, int rx, int rz, double amount) {
        List<Segment> taken = new ArrayList<>();
        double remaining = amount;
        for (int k = world.layerCount(x, z) - 1; k > 0 && remaining > 0; k--) {
            LayerView layer = world.layer(x, z, k);
            if (!layer.loose() || !layer.materialInfo().solid()) break;
            double seg = Math.min(remaining, layer.thickness());
            if (seg > 0) {
                taken.add(new Segment(seg, layer.materialInfo(), layer.unit(), layer.flags() | LayerFlags.LOOSE,
                        layer.porosity(), layer.welding()));
                remaining -= seg;
            }
        }
        double removed = world.erode(x, z, amount - remaining, true).removedM();
        double scale = amount - remaining > 0 ? removed / (amount - remaining) : 0;
        for (int i = taken.size() - 1; i >= 0; i--) {
            Segment s = taken.get(i);
            world.deposit(rx, rz, s.thickness() * scale, s.material(), s.unit(), s.flags(), s.porosity(), s.welding());
        }
    }

    /** Thickness of the loose solid layers at the top of a column (m). */
    double looseTop(int x, int z) {
        double sum = 0;
        for (int k = world.layerCount(x, z) - 1; k > 0; k--) {
            LayerView layer = world.layer(x, z, k);
            if (!layer.loose() || !layer.materialInfo().solid()) break;
            sum += layer.thickness();
        }
        return sum;
    }

    private void enqueueAround(int x, int z) {
        queue.add(pack(x, z));
        for (int d = 0; d < 8; d++) queue.add(pack(x + DX[d], z + DZ[d]));
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static int unpackX(long k) {
        return (int) (k >> 32);
    }

    private static int unpackZ(long k) {
        return (int) k;
    }
}
