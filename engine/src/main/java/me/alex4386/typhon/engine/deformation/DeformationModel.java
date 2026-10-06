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
    static final double STEP_SECONDS = 20;
    private static final BlockId WATER = BlockId.minecraft("water");

    private final DeformationConfig config;
    private final MagmaState magma;
    private final Supplier<List<DikeGeometry>> dikes;
    private final TerrainModel terrain;

    /** Whole blocks of uplift already applied per column (packed x/z key); absent = 0. */
    private final Map<Long, Integer> applied = new HashMap<>();
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

    private void adjustTerrain(StepContext context) {
        int radius = config.terrainRadiusBlocks;
        int changes = 0;
        int raised = 0;
        int lowered = 0;
        outer:
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                int x = config.centerX + dx;
                int z = config.centerZ + dz;
                TerrainColumn column = terrain.column(x, z);
                if (column == null) continue;

                long key = key(x, z);
                int already = applied.getOrDefault(key, 0);
                int target = (int) (upliftAt(x, z) / config.metersPerBlock); // truncate toward zero
                if (target == already) continue;

                int g = column.groundY();
                boolean wet = column.waterY() != TerrainColumn.NO_WATER;
                if (target > already) {
                    BlockId expected = wet && column.waterY() > g ? WATER : BlockId.AIR;
                    context.outbox().setBlock(BlockChange.replace(new BlockPos(x, g + 1, z), expected,
                            BlockState.of(column.surface())));
                    terrain.setGround(x, z, g + 1, column.surface());
                    already++;
                    raised++;
                } else {
                    BlockState fill = wet && column.waterY() >= g ? BlockState.of(WATER) : BlockState.AIR;
                    context.outbox().setBlock(BlockChange.replace(new BlockPos(x, g, z), column.surface(), fill));
                    terrain.setGround(x, z, g - 1, column.surface());
                    already--;
                    lowered++;
                }
                if (already == 0) applied.remove(key);
                else applied.put(key, already);

                if (++changes >= config.maxTerrainChangesPerCheck) break outer;
            }
        }
        if (changes > 0) {
            context.outbox().emit(new DeformationEvents.GroundDeformed(context.time(), config.volcanoId, raised, lowered));
        }
    }

    /** Whole blocks of deformation applied to a column so far (positive = raised). */
    public int appliedBlocks(int x, int z) {
        return applied.getOrDefault(key(x, z), 0);
    }

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
        // Applied whole-block uplift per column, one 16×16 int array per chunk.
        Map<Long, int[]> chunks = new TreeMap<>();
        for (Map.Entry<Long, Integer> e : applied.entrySet()) {
            int x = (int) (e.getKey() >> 32);
            int z = (int) (long) e.getKey();
            long chunkKey = ((long) (x >> 4) << 32) | ((z >> 4) & 0xffffffffL);
            chunks.computeIfAbsent(chunkKey, k -> new int[256])[((z & 15) << 4) | (x & 15)] = e.getValue();
        }
        StateWriter.Field field = writer.field("appliedUplift", 1);
        for (Map.Entry<Long, int[]> e : chunks.entrySet()) {
            field.put((int) (e.getKey() >> 32), (int) (long) e.getKey(), new FieldChunk().ints("blocks", e.getValue()));
        }
    }

    @Override
    public void loadState(StateReader reader) {
        lastTime = reader.json().get("lastTime").getAsDouble();
        applied.clear();
        StateReader.Field field = reader.field("appliedUplift");
        if (field == null) return;
        for (StateReader.Entry entry : field.chunks()) {
            int[] blocks = entry.data().ints("blocks");
            for (int i = 0; i < blocks.length; i++) {
                if (blocks[i] == 0) continue;
                int x = (entry.chunkX() << 4) | (i & 15);
                int z = (entry.chunkZ() << 4) | (i >> 4);
                applied.put(((long) x << 32) | (z & 0xffffffffL), blocks[i]);
            }
        }
    }
}
