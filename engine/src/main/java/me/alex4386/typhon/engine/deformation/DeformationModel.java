package me.alex4386.typhon.engine.deformation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    static final int STEP_TICKS = 20;
    private static final BlockId WATER = BlockId.minecraft("water");

    private final DeformationConfig config;
    private final MagmaState magma;
    private final Supplier<List<DikeGeometry>> dikes;
    private final TerrainModel terrain;

    /** Whole blocks of uplift already applied per column (packed x/z key); absent = 0. */
    private final Map<Long, Integer> applied = new HashMap<>();

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

    @Override
    public String id() {
        return "deformation:" + config.volcanoId;
    }

    @Override
    public int interval() {
        return STEP_TICKS;
    }

    @Override
    public void step(StepContext context) {
        long tick = context.tick();
        if (Math.floorMod(tick, config.sampleIntervalTicks) == 0) {
            context.outbox().emit(sample(tick));
        }
        if (config.applyToTerrain && terrain != null && Math.floorMod(tick, config.terrainIntervalTicks) == 0) {
            adjustTerrain(context);
        }
    }

    // ── Field ──

    /** Chamber (Mogi) volume change relative to zero overpressure (m³). */
    public double chamberVolumeChange() {
        return Mogi.volumeChange(config.chamberVolume, magma.overpressureMPa(), config.shearModulusPa);
    }

    /** Surface displacement (real metres) at world position ({@code x}, {@code z}). */
    public Displacement displacementAt(double x, double z) {
        double l = config.metersPerBlock;
        Displacement total = Mogi.displacement(chamberVolumeChange(), config.sourceDepth,
                (x - (config.centerX + 0.5)) * l, -(z - (config.centerZ + 0.5)) * l, config.poissonRatio);
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

    public DeformationEvents.DeformationSample sample(long tick) {
        List<StationReading> readings = new ArrayList<>(config.stations.size());
        for (GeodeticStation station : config.stations) readings.add(read(station));
        return new DeformationEvents.DeformationSample(tick, config.volcanoId, chamberVolumeChange(),
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
            context.outbox().emit(new DeformationEvents.GroundDeformed(context.tick(), config.volcanoId, raised, lowered));
        }
    }

    /** Whole blocks of deformation applied to a column so far (positive = raised). */
    public int appliedBlocks(int x, int z) {
        return applied.getOrDefault(key(x, z), 0);
    }

    public DeformationConfig config() {
        return config;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    // ── Persistence ──

    @Override
    public void saveState(JsonObject out) {
        List<Long> keys = new ArrayList<>(applied.keySet());
        keys.sort(null);
        JsonArray array = new JsonArray();
        for (long k : keys) {
            JsonArray entry = new JsonArray();
            entry.add(k);
            entry.add(applied.get(k));
            array.add(entry);
        }
        out.add("applied", array);
    }

    @Override
    public void loadState(JsonObject in) {
        applied.clear();
        for (JsonElement e : in.getAsJsonArray("applied")) {
            JsonArray entry = e.getAsJsonArray();
            applied.put(entry.get(0).getAsLong(), entry.get(1).getAsInt());
        }
    }
}
