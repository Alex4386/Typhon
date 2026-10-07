package me.alex4386.typhon.engine.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import me.alex4386.typhon.engine.save.FieldChunk;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;

/**
 * The simulator's world: what lies under every column, from the datum up to the ground surface, as
 * stratigraphic layers ({@link ColumnStacks}) with provenance ({@link UnitTable}) and physical
 * properties ({@link MaterialTable}).
 *
 * <p>This is the source of truth for "is there rock here?" — heat conduction, groundwater and
 * cross-sections read it, deposits append to it, erosion and digging remove from it, and lava
 * tubes and tunnels are cavities in it. Surface flows keep their own 2-D fields on top.
 *
 * <p>Iteration and persistence are ordered by tile key, so saves are deterministic.
 */
public final class WorldModel implements WorldQuery, WorldEdit {
    private static final int STACKS_SCHEMA = 1;

    /** Receives water added with {@link #addWater} (the surface-water model, once it exists). */
    @FunctionalInterface
    public interface WaterSink {
        void accept(int x, int z, double volumeM3);
    }

    private WorldSpec spec;
    private final ColumnStacks stacks;
    private final UnitTable units = new UnitTable();
    private final TreeMap<Long, Double> pendingWater = new TreeMap<>();
    private int basementUnit = -1;
    private int edificeUnit = -1;
    private final TreeMap<String, Integer> volcanoEdificeUnits = new TreeMap<>();
    private List<Edifice> edifices = List.of();
    private WaterSink waterSink;
    private java.util.function.DoubleBinaryOperator relief;
    private final List<DepositObserver> depositObservers = new ArrayList<>();
    /** Keeps loose deposits at or below their angle of repose (null until a host enables it). */
    private ReposeRelaxation repose;

    public WorldModel(WorldSpec spec) {
        this.spec = Objects.requireNonNull(spec);
        this.stacks = new ColumnStacks(spec.datumZ());
    }

    @Override
    public WorldSpec spec() {
        return spec;
    }

    /**
     * Changes the spec while the model is still empty (e.g. when the first volcano fixes the scale).
     *
     * @throws IllegalStateException if columns exist and the spec differs
     */
    public void setSpec(WorldSpec newSpec) {
        Objects.requireNonNull(newSpec);
        if (newSpec.equals(spec)) return;
        if (stacks.tileCount() > 0) {
            throw new IllegalStateException("World model already holds columns at " + spec.metersPerColumn()
                    + " m per column; cannot switch to " + newSpec.metersPerColumn());
        }
        if (newSpec.datumZ() != spec.datumZ()) throw new IllegalArgumentException("datum cannot change");
        this.spec = newSpec;
    }

    public ColumnStacks stacks() {
        return stacks;
    }

    public UnitTable units() {
        return units;
    }

    // ── Import ──

    /**
     * Volcano edifices used when columns are imported (not persisted: they come from the volcano
     * definitions). Columns imported earlier keep their layers.
     */
    public void setEdifices(List<Edifice> edifices) {
        this.edifices = List.copyOf(edifices);
    }

    public List<Edifice> edifices() {
        return edifices;
    }

    /**
     * The host's continuous description of the initial surface: elevation (m) at a point (m; column
     * {@code x} spans {@code [x·L, (x+1)·L)}), or {@code null}. Not persisted (it comes from the terrain
     * definition, like {@link #setEdifices}); {@link SurfaceDetail} takes the sub-column shape of
     * fresh detail cells from it.
     */
    public void setRelief(java.util.function.DoubleBinaryOperator relief) {
        this.relief = relief;
    }

    public java.util.function.DoubleBinaryOperator relief() {
        return relief;
    }

    /**
     * Builds a fresh column reaching up to {@code surfaceZ}: the basement layer cake, the country rock
     * (or the edifice of the volcano whose {@link Edifice} covers the column) up to the cover, and a
     * cover of {@code coverMaterial} (or the spec's surface material when {@code null}). Replaces the
     * column if it existed.
     */
    public void importColumn(int x, int z, double surfaceZ, Material coverMaterial) {
        stacks.setLayers(x, z, importLayers(x, z, surfaceZ, coverMaterial));
        relaxAround(x, z); // a new neighbour (e.g. the area growing) can undercut loose deposits at the old edge
    }

    /** A column to import: surface elevation and cover material ({@code null} = spec default). */
    public record ColumnImport(int x, int z, double surfaceZ, Material coverMaterial) {}

    /** {@link #importColumn} for many columns, one pass per tile (fast bulk import). */
    public void importColumns(List<ColumnImport> columns) {
        Map<Long, Map<Integer, List<ColumnStacks.LayerSpec>>> byTile = new TreeMap<>();
        for (ColumnImport column : columns) {
            long tile = packColumn(ColumnStacks.tileOf(column.x()), ColumnStacks.tileOf(column.z()));
            byTile.computeIfAbsent(tile, k -> new TreeMap<>())
                    .put(ColumnStacks.localIndex(column.x(), column.z()),
                            importLayers(column.x(), column.z(), column.surfaceZ(), column.coverMaterial()));
        }
        for (Map.Entry<Long, Map<Integer, List<ColumnStacks.LayerSpec>>> e : byTile.entrySet()) {
            stacks.setLayersBatch(unpackX(e.getKey()), unpackZ(e.getKey()), e.getValue());
        }
        if (repose != null) for (ColumnImport column : columns) repose.touched(column.x(), column.z());
    }

    private List<ColumnStacks.LayerSpec> importLayers(int x, int z, double surfaceZ, Material coverMaterial) {
        Material cover = coverMaterial != null ? coverMaterial : MaterialTable.require(spec.surfaceMaterial());
        Material country = MaterialTable.require(spec.edificeMaterial());
        Edifice zone = edifices.isEmpty() ? null : Edifice.at(edifices, x, z, spec.metersPerColumn());
        double coverBottom = Math.max(spec.datumZ(), surfaceZ - spec.surfaceThickness());
        List<ColumnStacks.LayerSpec> layers = new ArrayList<>();
        double previous = spec.datumZ();
        for (WorldSpec.GeologyLayer layer : spec.basement()) {
            double top = Math.min(layer.topZ(), coverBottom);
            if (top <= previous) break;
            Material m = MaterialTable.require(layer.material());
            layers.add(new ColumnStacks.LayerSpec(top, m.id(), basementUnit(), layer.porosity(), 1.0,
                    LayerFlags.defaults(m)));
            previous = top;
        }
        int coverUnit = edificeUnit();
        if (zone != null) {
            // country rock up to the edifice base, the volcano's own edifice above it
            double base = Double.isNaN(zone.baseZ()) ? previous : Math.min(zone.baseZ(), coverBottom);
            if (base > previous) {
                layers.add(new ColumnStacks.LayerSpec(base, country.id(), edificeUnit(), country.porosity(), 1.0,
                        LayerFlags.defaults(country)));
                previous = base;
            }
            Material own = MaterialTable.require(zone.material());
            coverUnit = edificeUnit(zone.volcanoId());
            if (coverBottom > previous) {
                layers.add(new ColumnStacks.LayerSpec(coverBottom, own.id(), coverUnit, own.porosity(), 1.0,
                        LayerFlags.defaults(own)));
                previous = coverBottom;
            }
        } else if (coverBottom > previous) {
            layers.add(new ColumnStacks.LayerSpec(coverBottom, country.id(), edificeUnit(), country.porosity(), 1.0,
                    LayerFlags.defaults(country)));
            previous = coverBottom;
        }
        if (surfaceZ > previous) {
            layers.add(new ColumnStacks.LayerSpec(surfaceZ, cover.id(), coverUnit, cover.porosity(),
                    cover.loose() ? 0 : 1.0, LayerFlags.defaults(cover)));
        }
        if (layers.isEmpty()) {
            Material bottom = spec.basement().isEmpty() ? country : MaterialTable.require(spec.basement().get(0).material());
            layers.add(new ColumnStacks.LayerSpec(Math.max(surfaceZ, spec.datumZ() + ColumnStacks.MIN_BOTTOM),
                    bottom.id(), basementUnit(), bottom.porosity(), 1.0, 0));
        }
        return layers;
    }

    private int basementUnit() {
        if (basementUnit < 0) basementUnit = units.add(UnitRecord.of(DepositType.BASEMENT));
        return basementUnit;
    }

    private int edificeUnit() {
        if (edificeUnit < 0) edificeUnit = units.add(UnitRecord.of(DepositType.EDIFICE));
        return edificeUnit;
    }

    /** The edifice unit of one volcano (created on first use). */
    private int edificeUnit(String volcanoId) {
        return volcanoEdificeUnits.computeIfAbsent(volcanoId,
                id -> units.add(new UnitRecord(id, -1, DepositType.EDIFICE, 0, Double.NaN, Double.NaN)));
    }

    // ── WorldQuery ──

    @Override
    public boolean isKnown(int x, int z) {
        return stacks.known(x, z);
    }

    @Override
    public double surfaceZ(int x, int z) {
        return stacks.surface(x, z);
    }

    @Override
    public double uplift(int x, int z) {
        return stacks.uplift(x, z);
    }

    public void setUplift(int x, int z, double meters) {
        stacks.setUplift(x, z, meters);
        relaxAround(x, z);
    }

    @Override
    public double waterZ(int x, int z) {
        return stacks.water(x, z);
    }

    public void setWaterZ(int x, int z, double elevation) {
        double before = stacks.water(x, z);
        stacks.setWater(x, z, elevation);
        // water rising over loose deposits lowers their angle of repose
        if (repose != null && Double.compare(before, elevation) != 0 && Double.isFinite(elevation)
                && elevation > stacks.surface(x, z)) {
            repose.touched(x, z);
        }
    }

    @Override
    public int layerCount(int x, int z) {
        return stacks.count(x, z);
    }

    @Override
    public LayerView layer(int x, int z, int i) {
        return stacks.layer(x, z, i);
    }

    @Override
    public Material materialAt(int x, int z, double elevation) {
        int i = stacks.layerIndexAt(x, z, elevation);
        return i < 0 ? MaterialTable.AIR : MaterialTable.get(stacks.layer(x, z, i).material());
    }

    @Override
    public int unitAt(int x, int z, double elevation) {
        int i = stacks.layerIndexAt(x, z, elevation);
        return i < 0 ? -1 : stacks.layer(x, z, i).unit();
    }

    @Override
    public boolean isSolid(int x, int z, double elevation) {
        int i = stacks.layerIndexAt(x, z, elevation);
        if (i < 0) return false;
        LayerView layer = stacks.layer(x, z, i);
        return MaterialTable.get(layer.material()).solid() && layer.voidFraction() < 0.5;
    }

    @Override
    public ColumnProfile column(int x, int z) {
        return new ColumnProfile(x, z, stacks.surface(x, z), stacks.water(x, z), stacks.uplift(x, z),
                stacks.layers(x, z));
    }

    @Override
    public SectionRaster section(double[] polylineXZ, double zMin, double zMax, int nu, int nz) {
        if (polylineXZ.length < 4 || polylineXZ.length % 2 != 0) {
            throw new IllegalArgumentException("polyline needs at least two (x, z) points");
        }
        if (nu < 1 || nz < 1 || !(zMax > zMin)) throw new IllegalArgumentException("bad section raster size");
        int segments = polylineXZ.length / 2 - 1;
        double[] cumulative = new double[segments + 1];
        for (int s = 0; s < segments; s++) {
            double dx = polylineXZ[2 * s + 2] - polylineXZ[2 * s];
            double dz = polylineXZ[2 * s + 3] - polylineXZ[2 * s + 1];
            cumulative[s + 1] = cumulative[s] + Math.sqrt(dx * dx + dz * dz);
        }
        double length = cumulative[segments];
        double[] u = new double[nu];
        float[] surface = new float[nu];
        short[] material = new short[nu * nz];
        int[] unit = new int[nu * nz];
        byte[] voids = new byte[nu * nz];
        int segment = 0;
        for (int iu = 0; iu < nu; iu++) {
            double along = (iu + 0.5) / nu * length;
            while (segment < segments - 1 && cumulative[segment + 1] < along) segment++;
            double segLength = cumulative[segment + 1] - cumulative[segment];
            double f = segLength > 0 ? (along - cumulative[segment]) / segLength : 0;
            double px = polylineXZ[2 * segment] + f * (polylineXZ[2 * segment + 2] - polylineXZ[2 * segment]);
            double pz = polylineXZ[2 * segment + 1] + f * (polylineXZ[2 * segment + 3] - polylineXZ[2 * segment + 1]);
            int cx = (int) Math.floor(px / spec.metersPerColumn());
            int cz = (int) Math.floor(pz / spec.metersPerColumn());
            u[iu] = along;
            surface[iu] = (float) stacks.surface(cx, cz);
            for (int iz = 0; iz < nz; iz++) {
                double elevation = zMax - (iz + 0.5) * (zMax - zMin) / nz;
                int index = iz * nu + iu;
                int layer = stacks.layerIndexAt(cx, cz, elevation);
                if (layer < 0) {
                    material[index] = MaterialTable.AIR.id();
                    unit[index] = -1;
                } else {
                    LayerView view = stacks.layer(cx, cz, layer);
                    material[index] = view.material();
                    unit[index] = view.unit();
                    voids[index] = ColumnStacks.quantize(view.voidFraction());
                }
            }
        }
        return new SectionRaster(nu, nz, zMin, zMax, u, surface, material, unit, voids);
    }

    /**
     * The unit record of {@code id}. An id the table does not know (layers saved by an interrupted save, newer
     * than the table that came with them) reads as {@link UnitTable#UNATTRIBUTED}: the deposit keeps its
     * material and thickness and only loses its provenance.
     */
    @Override
    public UnitRecord unit(int id) {
        return id >= 0 && id < units.size() ? units.get(id) : units.get(UnitTable.UNATTRIBUTED);
    }

    @Override
    public int version(int x, int z) {
        return stacks.version(x, z);
    }

    // ── WorldEdit ──

    @Override
    public int newUnit(UnitRecord unit) {
        return units.add(unit);
    }

    @Override
    public boolean deposit(int x, int z, double thickness, Material material, int unit) {
        return deposit(x, z, thickness, material, unit, LayerFlags.defaults(material), material.porosity(),
                material.loose() ? 0 : 1);
    }

    @Override
    public boolean deposit(int x, int z, double thickness, Material material, int unit, int flags, double porosity,
            double welding) {
        boolean done = stacks.deposit(x, z, thickness, material.id(), unit, flags, porosity, welding);
        if (done && !depositObservers.isEmpty()) {
            for (DepositObserver o : depositObservers) o.deposited(x, z, thickness, unit, flags);
        }
        // any deposit can change loose slopes: a loose one directly, a consolidated one by being merged into
        // the loose layers around it or by changing what a neighbour leans on
        if (done && repose != null) repose.touched(x, z);
        return done;
    }

    /**
     * Turns on the {@link ReposeRelaxation} of loose deposits (idempotent): from now on every loose deposit
     * cascades to its angle of repose at once. Volcano assemblies enable it; bare world models used by
     * tests that build steep piles on purpose do not.
     */
    public ReposeRelaxation enableReposeRelaxation() {
        if (repose == null) repose = new ReposeRelaxation(this);
        return repose;
    }

    /**
     * Runs a sweep of edits with repose relaxation deferred to its end ({@link ReposeRelaxation#hold()}): every
     * column touched is relaxed in one drain afterwards. The edits must not depend on the relaxed surface.
     */
    public void withRelaxationDeferred(Runnable edits) {
        if (repose == null) {
            edits.run();
            return;
        }
        repose.hold();
        try {
            edits.run();
        } finally {
            repose.release();
        }
    }

    /** The repose relaxation, or {@code null} when not enabled. */
    public ReposeRelaxation reposeRelaxation() {
        return repose;
    }

    /** Re-examines the loose deposits around a column after it was cut into (no-op when not enabled). */
    public void relaxAround(int x, int z) {
        if (repose != null) repose.touched(x, z);
    }

    /** Notified after every successful {@link #deposit}. */
    @FunctionalInterface
    public interface DepositObserver {
        void deposited(int x, int z, double thickness, int unit, int flags);
    }

    /**
     * Registers an observer of deposits (e.g. lahars tracking where fresh loose material lies).
     * Observers are not persisted: subsystems register when the engine is built and persist whatever
     * they derive.
     */
    public void addDepositObserver(DepositObserver observer) {
        depositObservers.add(Objects.requireNonNull(observer));
    }

    @Override
    public ErodeResult erode(int x, int z, double thickness, boolean looseOnly) {
        ErodeResult r = stacks.erode(x, z, thickness, looseOnly);
        if (r.removedM() > 0) relaxAround(x, z); // the loose slopes around a cut slump to repose
        return r;
    }

    @Override
    public ErodeResult carve(int x, int z, double zLo, double zHi, int unit) {
        double surface = stacks.surface(x, z);
        if (Double.isNaN(surface)) return ErodeResult.NONE;
        zLo = Math.max(zLo, spec.datumZ() + ColumnStacks.MIN_BOTTOM);
        if (zLo >= surface) return ErodeResult.NONE;
        if (zHi >= surface - ColumnStacks.EPS) return erode(x, z, surface - zLo, false);
        return stacks.replaceRange(x, z, zLo, zHi, MaterialTable.VOID.id(), unit, 0, 1, 0);
    }

    @Override
    public ErodeResult fill(int x, int z, double zLo, double zHi, Material material, int unit) {
        double surface = stacks.surface(x, z);
        if (Double.isNaN(surface) || !(zHi > zLo)) return ErodeResult.NONE;
        int flags = LayerFlags.defaults(material);
        double welding = material.loose() ? 0 : 1;
        ErodeResult replaced = ErodeResult.NONE;
        if (zLo < surface) {
            replaced = stacks.replaceRange(x, z, zLo, Math.min(zHi, surface), material.id(), unit, flags,
                    material.porosity(), welding);
        }
        if (zHi > surface) stacks.deposit(x, z, zHi - surface, material.id(), unit, flags, material.porosity(), welding);
        relaxAround(x, z);
        return replaced;
    }

    @Override
    public void addWater(int x, int z, double volumeM3) {
        if (!(volumeM3 > 0)) return;
        if (waterSink != null) {
            waterSink.accept(x, z, volumeM3);
        } else {
            pendingWater.merge(packColumn(x, z), volumeM3, Double::sum);
        }
    }

    /**
     * Connects the surface-water model. Water added while no sink was connected is delivered first,
     * in column order.
     */
    public void setWaterSink(WaterSink sink) {
        this.waterSink = sink;
        if (sink == null) return;
        for (Map.Entry<Long, Double> e : pendingWater.entrySet()) {
            sink.accept(unpackX(e.getKey()), unpackZ(e.getKey()), e.getValue());
        }
        pendingWater.clear();
    }

    /** Water added but not yet consumed by a sink (m³ per column key). */
    public double pendingWater() {
        double sum = 0;
        for (double v : pendingWater.values()) sum += v;
        return sum;
    }

    static long packColumn(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    static int unpackX(long key) {
        return (int) (key >> 32);
    }

    static int unpackZ(long key) {
        return (int) key;
    }

    // ── Persistence ──

    /**
     * Writes the stacks as field {@code stacks} (one chunk per 32×32 tile) and units/pending water as
     * {@code json.world}.
     */
    public void save(StateWriter out) {
        StateWriter.Field field = out.field("stacks", STACKS_SCHEMA);
        for (ColumnStacks.Tile t : stacks.sortedTiles()) {
            int n = t.size();
            int[] material = new int[n];
            int[] unit = new int[n];
            for (int i = 0; i < n; i++) {
                material[i] = t.material[i];
                unit[i] = t.unit[i];
            }
            field.put(t.tx, t.tz, new FieldChunk()
                    .ints("start", t.start.clone())
                    .floats("top", java.util.Arrays.copyOf(t.top, n))
                    .ints("material", material)
                    .ints("unit", unit)
                    .bytes("porosity", java.util.Arrays.copyOf(t.porosity, n))
                    .bytes("voidFrac", java.util.Arrays.copyOf(t.voidFrac, n))
                    .bytes("welding", java.util.Arrays.copyOf(t.welding, n))
                    .bytes("flags", java.util.Arrays.copyOf(t.flags, n))
                    .floats("uplift", t.uplift.clone())
                    .floats("water", t.water.clone())
                    .ints("version", t.version.clone()));
        }
        JsonObject world = new JsonObject();
        world.add("units", units.toJson());
        world.addProperty("basementUnit", basementUnit);
        world.addProperty("edificeUnit", edificeUnit);
        if (!volcanoEdificeUnits.isEmpty()) {
            JsonObject perVolcano = new JsonObject();
            volcanoEdificeUnits.forEach(perVolcano::addProperty);
            world.add("volcanoEdificeUnits", perVolcano);
        }
        JsonArray water = new JsonArray();
        for (Map.Entry<Long, Double> e : pendingWater.entrySet()) {
            JsonArray entry = new JsonArray();
            entry.add(e.getKey());
            entry.add(e.getValue());
            water.add(entry);
        }
        world.add("pendingWater", water);
        out.json().add("world", world);
    }

    public void load(StateReader in) {
        stacks.clear();
        pendingWater.clear();
        volcanoEdificeUnits.clear();
        JsonObject world = in.json().getAsJsonObject("world");
        units.load(world.getAsJsonArray("units"));
        basementUnit = world.get("basementUnit").getAsInt();
        edificeUnit = world.get("edificeUnit").getAsInt();
        if (world.has("volcanoEdificeUnits")) {
            for (Map.Entry<String, JsonElement> e : world.getAsJsonObject("volcanoEdificeUnits").entrySet()) {
                volcanoEdificeUnits.put(e.getKey(), e.getValue().getAsInt());
            }
        }
        for (JsonElement e : world.getAsJsonArray("pendingWater")) {
            JsonArray entry = e.getAsJsonArray();
            pendingWater.put(entry.get(0).getAsLong(), entry.get(1).getAsDouble());
        }
        StateReader.Field field = in.field("stacks");
        if (field.schemaVersion() != STACKS_SCHEMA) {
            throw new IllegalStateException("Unsupported world stacks schema " + field.schemaVersion());
        }
        for (StateReader.Entry entry : field.chunks()) {
            FieldChunk data = entry.data();
            ColumnStacks.Tile t = new ColumnStacks.Tile(entry.chunkX(), entry.chunkZ());
            System.arraycopy(data.ints("start"), 0, t.start, 0, t.start.length);
            float[] top = data.floats("top");
            int n = top.length;
            t.ensure(n);
            System.arraycopy(top, 0, t.top, 0, n);
            int[] material = data.ints("material");
            int[] unit = data.ints("unit");
            for (int i = 0; i < n; i++) {
                t.material[i] = (short) material[i];
                t.unit[i] = unit[i];
            }
            System.arraycopy(data.bytes("porosity"), 0, t.porosity, 0, n);
            System.arraycopy(data.bytes("voidFrac"), 0, t.voidFrac, 0, n);
            System.arraycopy(data.bytes("welding"), 0, t.welding, 0, n);
            System.arraycopy(data.bytes("flags"), 0, t.flags, 0, n);
            System.arraycopy(data.floats("uplift"), 0, t.uplift, 0, t.uplift.length);
            System.arraycopy(data.floats("water"), 0, t.water, 0, t.water.length);
            System.arraycopy(data.ints("version"), 0, t.version, 0, t.version.length);
            stacks.putTile(t);
        }
    }
}
