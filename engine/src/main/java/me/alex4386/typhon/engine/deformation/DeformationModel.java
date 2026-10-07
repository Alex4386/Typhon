package me.alex4386.typhon.engine.deformation;

import me.alex4386.typhon.engine.config.ConfigCopy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.world.ColumnStacks;
import me.alex4386.typhon.engine.volcano.MagmaState;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;

/**
 * Elastic ground deformation: a {@link Mogi} source for the pressurised chamber plus one
 * {@link DikeDislocation} per intruded dike.
 *
 * <p>The displacement field is relative to an unstressed reference (zero chamber overpressure, no
 * dikes) and is evaluated on demand. Virtual {@link GeodeticStation}s are sampled periodically as
 * {@link DeformationEvents.DeformationSample}s for monitoring dashboards. Optionally the uplift is
 * written into the world model's uplift field (metres per column).
 */
public final class DeformationModel implements Subsystem {
    static final double STEP_SECONDS = 1.0;

    private final DeformationConfig config;
    private final MagmaState magma;
    private final Supplier<List<DikeGeometry>> dikes;
    private final TerrainModel terrain;

    private double lastTime;
    /** Largest radius (m) the uplift has been written out to: columns there are reset when it shrinks. */
    private double appliedRadiusM;

    /**
     * @param dikes current dike sources (e.g. {@code DikePropagation::geometries}); may be {@code null}
     * @param terrain terrain to deform; may be {@code null} to only compute the field
     */
    public DeformationModel(DeformationConfig config, MagmaState magma, Supplier<List<DikeGeometry>> dikes,
            TerrainModel terrain) {
        config.validate();
        this.config = config;
        this.magma = Objects.requireNonNull(magma, "magma");
        this.dikes = dikes;
        this.terrain = terrain;
    }

    /** Live retune; the source position is refused. */
    @Override
    public boolean reconfigure(Object c) {
        if (!(c instanceof DeformationConfig n) || !ConfigCopy.same(n, config, "volcanoId", "centerX", "centerZ")) {
            return false;
        }
        n.validate();
        ConfigCopy.into(n, config);
        return true;
    }

    @Override
    public String id() {
        return "deformation:" + config.volcanoId;
    }

    @Override
    public double periodSeconds() {
        return STEP_SECONDS;
    }

    @Override
    public DeformationEvents.DeformationSample snapshot() {
        return sample(lastTime);
    }

    @Override
    public void step(StepContext context) {
        lastTime = context.time();
        if (context.crossed(config.samplePeriodSeconds)) {
            context.outbox().emit(sample(context.time()));
        }
        if (config.applyToTerrain && terrain != null && context.crossed(config.terrainPeriodSeconds)) {
            adjustTerrain(context);
        }
    }

    // ── Field ──

    /** Chamber (Mogi) volume change relative to zero overpressure (m³). */
    public double chamberVolumeChange() {
        return Mogi.volumeChange(config.chamberVolume, magma.overpressureMPa(), config.shearModulusPa)
                + magma.inelasticVolumeChangeM3();
    }

    /**
     * A further pressure source of the plumbing (a deeper or side chamber), as a Mogi point source: its
     * volume change (m³), depth (m) and horizontal position (m).
     */
    public record Source(double volumeChangeM3, double depthM, double centerX, double centerZ) {}

    private Supplier<List<Source>> extraSources = List::of;

    /** The plumbing's further chambers; their Mogi fields add to the main chamber's (superposition). */
    public void setExtraSources(Supplier<List<Source>> sources) {
        this.extraSources = java.util.Objects.requireNonNull(sources);
    }

    /** Surface displacement (m) at horizontal position ({@code x}, {@code z}) (m). */
    public Displacement displacementAt(double x, double z) {
        Displacement total = Mogi.displacement(chamberVolumeChange(), config.sourceDepth,
                x - config.centerX, -(z - config.centerZ), config.poissonRatio);
        for (Source s : extraSources.get()) {
            total = total.plus(Mogi.displacement(s.volumeChangeM3(), s.depthM(), x - s.centerX(), -(z - s.centerZ()),
                    config.poissonRatio));
        }
        if (dikes != null) {
            for (DikeGeometry dike : dikes.get()) {
                total = total.plus(DikeDislocation.displacement(dike, x - dike.centerX(), -(z - dike.centerZ())));
            }
        }
        return total;
    }

    /** Vertical displacement (m) at horizontal position ({@code x}, {@code z}) (m). */
    public double upliftAt(double x, double z) {
        return upliftField().at(x, z);
    }

    /**
     * The sources as they stand now, resolved once to evaluate the uplift at many points (the values of
     * {@link #displacementAt}{@code .up()}, the dikes' part to {@link #DIKE_QUANTUM_M}).
     */
    UpliftField upliftField() {
        List<Source> extra = extraSources.get();
        List<DikeGeometry> geometries = dikes != null ? dikes.get() : List.of();
        int n = 1 + extra.size();
        double[] mogi = new double[4 * n];
        mogi[0] = chamberVolumeChange();
        mogi[1] = config.sourceDepth;
        mogi[2] = config.centerX;
        mogi[3] = config.centerZ;
        for (int i = 1; i < n; i++) {
            Source src = extra.get(i - 1);
            mogi[4 * i] = src.volumeChangeM3();
            mogi[4 * i + 1] = src.depthM();
            mogi[4 * i + 2] = src.centerX();
            mogi[4 * i + 3] = src.centerZ();
        }
        DikeDislocation.Resolved[] resolved = new DikeDislocation.Resolved[geometries.size()];
        for (int i = 0; i < resolved.length; i++) resolved[i] = DikeDislocation.Resolved.of(geometries.get(i));
        return new UpliftField(mogi, config.poissonRatio, geometries, resolved);
    }

    /** Numerical: the dikes' uplift is summed in whole multiples of this (m), exactly, in any order. */
    static final double DIKE_QUANTUM_M = 1e-9;

    /**
     * See {@link #upliftField()}: Mogi sources as (ΔV, depth, x, z) quadruples, then the dikes ({@code null}
     * where closed). Each dike's uplift is rounded to {@link #DIKE_QUANTUM_M} and the dikes are summed as
     * integers, so any grouping of them (a cached part plus the rest) gives exactly the same value.
     */
    record UpliftField(double[] mogi, double poissonRatio, List<DikeGeometry> geometries,
            DikeDislocation.Resolved[] dikes) {
        double at(double x, double z) {
            return mogiAt(x, z) + dikesAt(x, z);
        }

        double mogiAt(double x, double z) {
            double up = 0;
            for (int i = 0; i < mogi.length; i += 4) {
                up += Mogi.upliftScale(mogi[i], mogi[i + 1], poissonRatio)
                        * Mogi.geometry(mogi[i + 1], x - mogi[i + 2], -(z - mogi[i + 3]));
            }
            return up;
        }

        double dikesAt(double x, double z) {
            return dikeQuantaSum(x, z) * DIKE_QUANTUM_M;
        }

        /** All dikes' uplift at (x, z), in {@link #DIKE_QUANTUM_M}. */
        long dikeQuantaSum(double x, double z) {
            long q = 0;
            for (int i = 0; i < dikes.length; i++) q += dikeQuanta(i, x, z);
            return q;
        }

        /** Dike {@code i}'s uplift at (x, z), in {@link #DIKE_QUANTUM_M}. */
        long dikeQuanta(int i, double x, double z) {
            DikeDislocation.Resolved d = dikes[i];
            if (d == null) return 0;
            DikeGeometry g = geometries.get(i);
            return Math.round(d.uplift(x - g.centerX(), -(z - g.centerZ())) / DIKE_QUANTUM_M);
        }
    }

    /**
     * The summed uplift (in {@link #DIKE_QUANTUM_M}) of the dikes that did not change, per column of a box
     * around the disk the uplift is written to ({@link Long#MIN_VALUE} where not worked out yet). Dikes change only while they propagate: those are
     * evaluated afresh each pass, the others once, when they come to rest. Integer sums make the result
     * independent of what happens to be cached, so a reloaded run computes the same values.
     */
    private static final class DikeUplift {
        final double columnM;
        final int tiles, x0, z0, width, height;
        final long[] quanta;
        /** The dikes summed in {@link #quanta} (as a multiset). */
        final List<DikeGeometry> cached = new ArrayList<>();

        DikeUplift(double columnM, int tiles, int x0, int z0, int width, int height, long[] quanta) {
            this.columnM = columnM;
            this.tiles = tiles;
            this.x0 = x0;
            this.z0 = z0;
            this.width = width;
            this.height = height;
            this.quanta = quanta;
        }

        boolean fits(double l, int t, int bx0, int bz0, int w, int h) {
            return columnM == l && tiles == t && x0 == bx0 && z0 == bz0 && width == w && height == h;
        }
    }

    /** The uplift of these dikes alone. */
    private UpliftField dikeField(List<DikeGeometry> geometries) {
        return new UpliftField(new double[0], config.poissonRatio, geometries,
                geometries.stream().map(DikeDislocation.Resolved::of).toArray(DikeDislocation.Resolved[]::new));
    }

    /**
     * {@link Mogi#geometry} of one source per column of a box (the same box as {@link DikeUplift}): it stays
     * as long as the source does not move, so a pass multiplies it by the source's current scale.
     */
    private record MogiGeometry(double depthM, double centerX, double centerZ, double columnM, int x0, int z0,
            int width, int height, double[] g) {
        static MogiGeometry of(double depthM, double centerX, double centerZ, double l, int x0, int z0, int width,
                int height) {
            double[] g = new double[width * height];
            for (int z = z0; z < z0 + height; z++) {
                for (int x = x0; x < x0 + width; x++) {
                    g[(z - z0) * width + (x - x0)] = Mogi.geometry(depthM, (x + 0.5) * l - centerX, -((z + 0.5) * l - centerZ));
                }
            }
            return new MogiGeometry(depthM, centerX, centerZ, l, x0, z0, width, height, g);
        }

        boolean fits(double d, double cx, double cz, double l, int bx0, int bz0, int w, int h) {
            return depthM == d && centerX == cx && centerZ == cz && columnM == l && x0 == bx0 && z0 == bz0 && width == w
                    && height == h;
        }
    }

    /** Not saved: per Mogi source of the last pass (main chamber first), rebuilt on demand. */
    private MogiGeometry[] mogiGeometry = new MogiGeometry[0];

    /** Scratch: the new uplift per column of the box (not saved). */
    private double[] after;

    /** Not saved: rebuilt on demand (see {@link DikeUplift}). */
    private DikeUplift dikeUplift;
    /** The dike geometries of the previous terrain pass (not saved: only decides what is cached). */
    private List<DikeGeometry> previousDikes = List.of();

    /** Numerical: half the baseline (m) of the finite difference that stands for a point tiltmeter. */
    private static final double TILT_HALF_BASELINE_M = 5;

    public StationReading read(GeodeticStation station) {
        double x = station.x();
        double z = station.z();
        double h = TILT_HALF_BASELINE_M;
        double baseline = 2 * h;
        double tiltEast = (displacementAt(x + h, z).up() - displacementAt(x - h, z).up()) / baseline;
        double tiltNorth = (displacementAt(x, z - h).up() - displacementAt(x, z + h).up()) / baseline;
        return new StationReading(station.name(), displacementAt(x, z), tiltEast * 1e6, tiltNorth * 1e6);
    }

    public DeformationEvents.DeformationSample sample(double time) {
        List<StationReading> readings = new ArrayList<>(config.stations.size());
        for (GeodeticStation station : config.stations) readings.add(read(station));
        return new DeformationEvents.DeformationSample(time, config.volcanoId, chamberVolumeChange(),
                upliftAt(config.centerX, config.centerZ), readings);
    }

    // ── Terrain ──

    /**
     * Writes the modelled uplift into the world model's uplift field (metres per column; the ground is
     * surface + uplift).
     */
    private void adjustTerrain(StepContext context) {
        var world = terrain.world();
        double l = world.spec().metersPerColumn();
        int cx = (int) Math.floor(config.centerX / l);
        int cz = (int) Math.floor(config.centerZ / l);
        UpliftField field = upliftField();
        appliedRadiusM = Math.min(config.terrainRadiusM, Math.max(appliedRadiusM, upliftRadiusM(field)));
        int radius = (int) Math.ceil(appliedRadiusM / l);
        int raised = 0;
        int lowered = 0;
        // the caches cover the known world's bounding box; the pass writes the part inside the disk
        long[] tiles = world.stacks().tileKeys();
        if (tiles.length == 0) return;
        int tx0 = Integer.MAX_VALUE, tx1 = Integer.MIN_VALUE, tz0 = Integer.MAX_VALUE, tz1 = Integer.MIN_VALUE;
        for (long key : tiles) {
            int tx = ColumnStacks.keyTileX(key);
            int tz = ColumnStacks.keyTileZ(key);
            tx0 = Math.min(tx0, tx);
            tx1 = Math.max(tx1, tx);
            tz0 = Math.min(tz0, tz);
            tz1 = Math.max(tz1, tz);
        }
        // ... cut to a square around the disk, its half-size rounded up to a power of two so that a growing disk
        // starts the caches over only a few times
        int half = radius <= 1 ? 1 : Integer.highestOneBit(radius - 1) << 1;
        int bx0 = Math.max(tx0 * ColumnStacks.TILE, cx - half);
        int bz0 = Math.max(tz0 * ColumnStacks.TILE, cz - half);
        int bx1 = Math.min((tx1 + 1) * ColumnStacks.TILE - 1, cx + half);
        int bz1 = Math.min((tz1 + 1) * ColumnStacks.TILE - 1, cz + half);
        if (bx1 < bx0 || bz1 < bz0) return;
        int width = bx1 - bx0 + 1;
        int height = bz1 - bz0 + 1;
        DikeUplift cache = dikeUplift;
        if (cache == null || !cache.fits(l, tiles.length, bx0, bz0, width, height)) {
            long[] quanta = new long[width * height];
            java.util.Arrays.fill(quanta, Long.MIN_VALUE);
            cache = new DikeUplift(l, tiles.length, bx0, bz0, width, height, quanta);
            dikeUplift = cache;
        }
        // which dikes enter and leave the cache (came to rest; changed or gone), and which are evaluated afresh
        List<DikeGeometry> current = field.geometries();
        List<DikeGeometry> leaving = new ArrayList<>(cache.cached);
        List<DikeGeometry> unmatched = new ArrayList<>(current.size());
        for (DikeGeometry g : current) {
            if (!leaving.remove(g)) unmatched.add(g);
        }
        List<DikeGeometry> previous = new ArrayList<>(previousDikes);
        List<Integer> entering = new ArrayList<>();
        List<Integer> live = new ArrayList<>();
        for (int i = 0; i < current.size(); i++) {
            DikeGeometry g = current.get(i);
            if (!unmatched.remove(g)) continue; // cached already
            if (previous.remove(g)) entering.add(i);
            else live.add(i);
        }
        previousDikes = current;
        UpliftField cachedField = dikeField(cache.cached); // before the changes: fills columns not worked out yet
        UpliftField leavingField = dikeField(leaving);

        // the Mogi sources' geometry and current scale
        double[] mogi = field.mogi();
        int sources = mogi.length / 4;
        MogiGeometry[] geometry = java.util.Arrays.copyOf(mogiGeometry, sources);
        double[] scale = new double[sources];
        for (int i = 0; i < sources; i++) {
            double d = mogi[4 * i + 1];
            if (geometry[i] == null || !geometry[i].fits(d, mogi[4 * i + 2], mogi[4 * i + 3], l, bx0, bz0, width, height)) {
                geometry[i] = MogiGeometry.of(d, mogi[4 * i + 2], mogi[4 * i + 3], l, bx0, bz0, width, height);
            }
            scale[i] = Mogi.upliftScale(mogi[4 * i], d, field.poissonRatio());
        }
        mogiGeometry = geometry;

        int x0 = Math.max(cx - radius, bx0);
        int x1 = Math.min(cx + radius, bx0 + width - 1);
        int z0 = Math.max(cz - radius, bz0);
        int z1 = Math.min(cz + radius, bz0 + height - 1);
        if (x1 >= x0 && z1 >= z0) {
            // the new uplift per column, in parallel (each row writes only its own cells), then written in order
            if (after == null || after.length < width * height) after = new double[width * height];
            double[] out = after;
            DikeUplift dikeCache = cache;
            int r2 = radius * radius;
            int[] enteringAt = entering.stream().mapToInt(Integer::intValue).toArray();
            int[] liveAt = live.stream().mapToInt(Integer::intValue).toArray();
            context.parallel().forEach(z1 - z0 + 1, 4, row -> {
                int z = z0 + row;
                for (int x = x0; x <= x1; x++) {
                    int c = (z - bz0) * width + (x - bx0);
                    out[c] = Double.NaN;
                    int dx = x - cx;
                    int dz = z - cz;
                    if (dx * dx + dz * dz > r2) continue;
                    long q = dikeCache.quanta[c];
                    double px = (x + 0.5) * l;
                    double pz = (z + 0.5) * l;
                    if (q == Long.MIN_VALUE) {
                        if (!world.isKnown(x, z)) continue;
                        q = cachedField.dikeQuantaSum(px, pz);
                    }
                    for (int i : enteringAt) q += field.dikeQuanta(i, px, pz);
                    q -= leavingField.dikeQuantaSum(px, pz);
                    dikeCache.quanta[c] = q;
                    for (int i : liveAt) q += field.dikeQuanta(i, px, pz);
                    double chamber = 0; // = field.mogiAt(px, pz)
                    for (int i = 0; i < sources; i++) chamber += scale[i] * geometry[i].g()[c];
                    out[c] = chamber + q * DIKE_QUANTUM_M;
                }
            });
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    double value = out[(z - bz0) * width + (x - bx0)];
                    if (Double.isNaN(value)) continue; // outside the disk, or unknown
                    double before = world.uplift(x, z);
                    if (Math.abs(value - before) < UPLIFT_REPORT_M) continue;
                    world.setUplift(x, z, value);
                    if (value > before) raised++;
                    else lowered++;
                }
            }
        }
        // columns outside the disk keep the old cached set's sums: forget them when the set changes
        if (!entering.isEmpty() || !leaving.isEmpty()) {
            for (int z = bz0; z < bz0 + height; z++) {
                for (int x = bx0; x < bx0 + width; x++) {
                    int dx = x - cx;
                    int dz = z - cz;
                    if (dx * dx + dz * dz > radius * radius) cache.quanta[(z - bz0) * width + (x - bx0)] = Long.MIN_VALUE;
                }
            }
            for (DikeGeometry g : leaving) cache.cached.remove(g);
            for (int i : entering) cache.cached.add(current.get(i));
        }
        if (raised + lowered > 0) {
            context.outbox().emit(new DeformationEvents.GroundDeformed(context.time(), config.volcanoId, raised, lowered));
        }
    }

    /**
     * Distance (m) from the main source within which its Mogi uplift reaches {@link #UPLIFT_REPORT_M}:
     * {@code u(r) = u₀ (d² / (d² + r²))^{3/2}}, so {@code r = d √((u₀ / u_min)^{2/3} − 1)}; plus the reach of
     * the dikes (their extent from the source and a few top depths around them).
     */
    double upliftRadiusM() {
        return upliftRadiusM(upliftField());
    }

    private double upliftRadiusM(UpliftField field) {
        double d = config.sourceDepth;
        double peak = Math.abs(field.at(config.centerX, config.centerZ));
        double r = peak > UPLIFT_REPORT_M ? d * Math.sqrt(Math.pow(peak / UPLIFT_REPORT_M, 2.0 / 3.0) - 1) : 0;
        if (dikes != null) {
            for (DikeGeometry dike : dikes.get()) {
                double reach = Math.hypot(dike.centerX() - config.centerX, dike.centerZ() - config.centerZ)
                        + dike.strikeLengthM() / 2 + 3 * Math.max(dike.topDepthM(), 1);
                r = Math.max(r, reach);
            }
        }
        return r;
    }

    /** Numerical: uplift changes smaller than this (m) are not written or reported. */
    private static final double UPLIFT_REPORT_M = 0.005;

    @Override
    public DeformationConfig config() {
        return config;
    }

    // ── Persistence ──

    @Override
    public void saveState(StateWriter writer) {
        writer.json().addProperty("lastTime", lastTime);
        writer.json().addProperty("appliedRadiusM", appliedRadiusM);
        // the uplift itself lives in the world model (saved with it)
    }

    @Override
    public void loadState(StateReader reader) {
        lastTime = reader.json().get("lastTime").getAsDouble();
        appliedRadiusM = reader.json().get("appliedRadiusM").getAsDouble();
    }
}
