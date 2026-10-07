package me.alex4386.typhon.engine.deformation;

import me.alex4386.typhon.engine.config.ConfigCopy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.terrain.TerrainModel;
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
        return displacementAt(x, z).up();
    }

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
        appliedRadiusM = Math.min(config.terrainRadiusM, Math.max(appliedRadiusM, upliftRadiusM()));
        int radius = (int) Math.ceil(appliedRadiusM / l);
        int raised = 0;
        int lowered = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                int x = cx + dx;
                int z = cz + dz;
                if (!world.isKnown(x, z)) continue;
                double before = world.uplift(x, z);
                double after = upliftAt((x + 0.5) * l, (z + 0.5) * l);
                if (Math.abs(after - before) < UPLIFT_REPORT_M) continue;
                world.setUplift(x, z, after);
                if (after > before) raised++;
                else lowered++;
            }
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
        double d = config.sourceDepth;
        double peak = Math.abs(upliftAt(config.centerX, config.centerZ));
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
