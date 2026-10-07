package me.alex4386.typhon.engine.deformation;

import me.alex4386.typhon.engine.config.ConfigCopy;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.MagmaState;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.BlockState;
import me.alex4386.typhon.engine.save.FieldChunk;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;

/**
 * Elastic ground deformation: a {@link Mogi} source for the pressurised chamber plus one
 * {@link DikeDislocation} per intruded dike.
 *
 * <p>The displacement field is relative to an unstressed reference (zero chamber overpressure, no
 * dikes) and is evaluated on demand. Virtual {@link GeodeticStation}s are sampled periodically as
 * {@link DeformationEvents.DeformationSample}s for monitoring dashboards. Optionally, uplift or
 * subsidence that accumulates past whole blocks (after {@code 1/metersPerBlock} scaling) raises or
 * lowers terrain columns — rate-limited and compare-and-set against the surface.
 */
public final class DeformationModel implements Subsystem {
    static final double STEP_SECONDS = 1.0;
    private static final BlockId WATER = BlockId.minecraft("water");

    private final DeformationConfig config;
    private final MagmaState magma;
    private final Supplier<List<DikeGeometry>> dikes;
    private final TerrainModel terrain;

    private double lastTime;

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

    /** Live retune; the source position and block size are refused. */
    @Override
    public boolean reconfigure(Object c) {
        if (!(c instanceof DeformationConfig n) || !ConfigCopy.same(n, config, "volcanoId", "centerX", "centerZ", "metersPerBlock")) {
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
     * volume change (m³), physical depth (m) and centre column.
     */
    public record Source(double volumeChangeM3, double depthM, double centerX, double centerZ) {}

    private Supplier<List<Source>> extraSources = List::of;

    /** The plumbing's further chambers; their Mogi fields add to the main chamber's (superposition). */
    public void setExtraSources(Supplier<List<Source>> sources) {
        this.extraSources = java.util.Objects.requireNonNull(sources);
    }

    /** Surface displacement (real metres) at world position ({@code x}, {@code z}). */
    public Displacement displacementAt(double x, double z) {
        double l = config.metersPerBlock;
        Displacement total = Mogi.displacement(chamberVolumeChange(), config.sourceDepth,
                (x - (config.centerX + 0.5)) * l, -(z - (config.centerZ + 0.5)) * l, config.poissonRatio);
        for (Source s : extraSources.get()) {
            total = total.plus(Mogi.displacement(s.volumeChangeM3(), s.depthM(), (x - (s.centerX() + 0.5)) * l,
                    -(z - (s.centerZ() + 0.5)) * l, config.poissonRatio));
        }
        if (dikes != null) {
            for (DikeGeometry dike : dikes.get()) {
                total = total.plus(DikeDislocation.displacement(dike, (x - dike.centerX()) * l, -(z - dike.centerZ()) * l));
            }
        }
        return total;
    }

    /** Vertical displacement (m) at the centre of world column ({@code x}, {@code z}). */
    public double upliftAt(int x, int z) {
        return displacementAt(x + 0.5, z + 0.5).up();
    }

    public StationReading read(GeodeticStation station) {
        double x = station.x() + 0.5;
        double z = station.z() + 0.5;
        double h = 0.5;
        double baseline = 2 * h * config.metersPerBlock;
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
     * Writes the modelled uplift into the world model's continuous uplift field (metres per column, the
     * displayed and physical ground is surface + uplift). Never moves ground in whole blocks: a host that
     * shows blocks quantises the uplifted surface itself (mc-projection).
     */
    private void adjustTerrain(StepContext context) {
        var world = terrain.world();
        int radius = config.terrainRadiusBlocks;
        int raised = 0;
        int lowered = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                int x = config.centerX + dx;
                int z = config.centerZ + dz;
                if (!world.isKnown(x, z)) continue;
                double before = world.uplift(x, z);
                double after = upliftAt(x, z);
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

    /** Uplift changes smaller than this (m) are not written or reported. */
    private static final double UPLIFT_REPORT_M = 0.005;

    @Override
    public DeformationConfig config() {
        return config;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    // ── Persistence ──

    @Override
    public void saveState(StateWriter writer) {
        writer.json().addProperty("lastTime", lastTime);
        // the uplift itself lives in the world model (saved with it)
    }

    @Override
    public void loadState(StateReader reader) {
        lastTime = reader.json().get("lastTime").getAsDouble();
        // saves from before the continuous uplift carried whole applied blocks ("appliedUplift"); the blocks
        // they raised are already part of those worlds' ground, so the field is ignored
    }
}
