package me.alex4386.typhon.engine.expansion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.terrain.TerrainChunk;
import me.alex4386.typhon.engine.terrain.TerrainChunkView;
import me.alex4386.typhon.engine.terrain.TerrainGenerator;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.terrain.TerrainSnapshot;

/**
 * Grows the simulated area on demand, like a Minecraft server loading chunks — but driven by physics,
 * not by players or the camera.
 *
 * <p><b>Generation vs simulation.</b> The landscape is unbounded: the host's {@link TerrainGenerator}
 * gives the column at any (x, z) as a pure function of the coordinates and the world definition. Only
 * the columns in the {@link TerrainModel} are simulated. Every {@link ExpansionConfig#periodSeconds()},
 * this subsystem asks the registered {@link ExpansionActivity} sources where something is happening
 * (molten lava, moving pyroclastic flows and lahars, rising dike tips, slope failures, thick tephra,
 * running water, flows blocked by unknown ground). Every expansion tile within
 * {@link ExpansionConfig#marginTiles()} of an active one that is not yet fully simulated is
 * materialised: its unknown 16×16 chunks are generated and imported through the terrain model exactly
 * as the initial terrain was (stratigraphy from the world geology and edifices, surface at the
 * generator's continuous relief). The subsurface solver initialises the new columns on its next step,
 * and lava and mass flows see them as ordinary ground. Listeners hand over state kept for unsimulated
 * ground (tephra that already fell there).
 *
 * <p><b>Determinism.</b> Activity is read from simulation state inside the engine step, tiles are
 * materialised in sorted order and the generator is pure, so a run is identical for any thread count
 * and when restored from a save. Materialised columns are saved with the terrain like any other;
 * unmaterialised ones are never saved (they are regenerated from the definition). What a viewer looks
 * at never changes the simulation.
 *
 * <p><b>Bounds.</b> Tiles stay inside a square {@link ExpansionConfig#maxExtentM()} wide around the
 * world origin, and at most {@link ExpansionConfig#maxTiles()} are added, which bounds memory.
 */
public final class WorldExpansion implements Subsystem {
    public static final String ID = "expansion";

    private final TerrainModel terrain;
    private ExpansionConfig config;
    private TerrainGenerator generator;
    private final List<ExpansionActivity> sources = new ArrayList<>();
    private final List<ExpansionActivity.Listener> listeners = new ArrayList<>();

    /** Tiles added by expansion (packed tile keys), in order of their keys. */
    private final TreeSet<Long> added = new TreeSet<>();
    /** Number of steps that materialised tiles. */
    private volatile long revision;
    /** Derived (not saved): tiles known to be fully simulated. */
    private final Set<Long> complete = new HashSet<>();

    public WorldExpansion(TerrainModel terrain, ExpansionConfig config) {
        this.terrain = Objects.requireNonNull(terrain);
        this.config = Objects.requireNonNull(config);
    }

    /** Live retune; the tile size is refused. */
    @Override
    public boolean reconfigure(Object c) {
        if (!(c instanceof ExpansionConfig n) || n.tileColumns() != config.tileColumns()) return false;
        config = n;
        return true;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public double periodSeconds() {
        return config.periodSeconds();
    }

    @Override
    public Object config() {
        return config;
    }

    public ExpansionConfig expansionConfig() {
        return config;
    }

    /** The host's generator; without one the area never grows. Set before stepping (not persisted). */
    public void setGenerator(TerrainGenerator generator) {
        this.generator = generator;
    }

    public TerrainGenerator generator() {
        return generator;
    }

    public void addSource(ExpansionActivity source) {
        sources.add(Objects.requireNonNull(source));
    }

    public void addListener(ExpansionActivity.Listener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /** Increases whenever tiles were materialised (readable from any thread). */
    public long revision() {
        return revision;
    }

    /** Tiles added by expansion so far. */
    public int addedTiles() {
        return added.size();
    }

    /** The tiles added by expansion, sorted by key. */
    public List<ExpansionEvents.TileCoord> addedTileCoords() {
        List<ExpansionEvents.TileCoord> out = new ArrayList<>();
        for (long k : added) out.add(new ExpansionEvents.TileCoord(keyX(k), keyZ(k)));
        return out;
    }

    // ── Step ──

    @Override
    public void step(StepContext context) {
        if (!config.enabled() || generator == null || added.size() >= config.maxTiles()) return;
        int t = config.tileColumns();
        TreeSet<Long> activeTiles = new TreeSet<>();
        ExpansionActivity.Sink sink = (x, z) -> activeTiles.add(key(Math.floorDiv(x, t), Math.floorDiv(z, t)));
        for (ExpansionActivity source : sources) source.report(sink);
        if (activeTiles.isEmpty()) return;

        int m = config.marginTiles();
        TreeSet<Long> wanted = new TreeSet<>();
        for (long a : activeTiles) {
            int ax = keyX(a);
            int az = keyZ(a);
            for (int dz = -m; dz <= m; dz++) {
                for (int dx = -m; dx <= m; dx++) {
                    long k = key(ax + dx, az + dz);
                    if (!wanted.contains(k) && withinCap(ax + dx, az + dz) && !complete(ax + dx, az + dz)) wanted.add(k);
                }
            }
        }
        if (wanted.isEmpty()) return;

        List<ExpansionEvents.TileCoord> grown = new ArrayList<>();
        for (long k : wanted) {
            if (added.size() >= config.maxTiles()) break;
            materialize(context.time(), keyX(k), keyZ(k));
            added.add(k);
            grown.add(new ExpansionEvents.TileCoord(keyX(k), keyZ(k)));
        }
        if (grown.isEmpty()) return;
        revision++;
        double l = terrain.world().spec().metersPerColumn();
        double areaKm2 = terrain.chunkCount() * 256.0 * l * l / 1e6;
        context.outbox().emit(new ExpansionEvents.AreaExpanded(context.time(), grown, t, added.size(), areaKm2));
    }

    /** Whether tile (tx, tz) lies inside the cap square around the world origin. */
    boolean withinCap(int tx, int tz) {
        int t = config.tileColumns();
        double half = config.maxExtentM() / 2 / terrain.world().spec().metersPerColumn();
        return Math.abs((tx + 0.5) * t) <= half && Math.abs((tz + 0.5) * t) <= half;
    }

    /** Whether every chunk of tile (tx, tz) is simulated (cached once true). */
    boolean complete(int tx, int tz) {
        long k = key(tx, tz);
        if (complete.contains(k)) return true;
        int n = config.tileColumns() / 16;
        for (int cz = tz * n; cz < tz * n + n; cz++) {
            for (int cx = tx * n; cx < tx * n + n; cx++) {
                if (!terrain.isKnown(cx << 4, cz << 4)) return false;
            }
        }
        complete.add(k);
        return true;
    }

    private void materialize(double time, int tx, int tz) {
        int t = config.tileColumns();
        int n = t / 16;
        List<TerrainChunk> chunks = new ArrayList<>();
        for (int cz = tz * n; cz < tz * n + n; cz++) {
            for (int cx = tx * n; cx < tx * n + n; cx++) {
                if (terrain.isKnown(cx << 4, cz << 4)) continue;
                TerrainChunk chunk = new TerrainChunk(cx, cz);
                for (int z = cz << 4; z < (cz << 4) + 16; z++) {
                    for (int x = cx << 4; x < (cx << 4) + 16; x++) chunk.set(x, z, generator.column(x, z));
                }
                chunks.add(chunk);
            }
        }
        if (!chunks.isEmpty()) terrain.apply(new TerrainSnapshot(chunks));
        complete.add(key(tx, tz));
        for (ExpansionActivity.Listener listener : listeners) listener.materialized(time, tx * t, tz * t, t);
    }

    // ── Bounds of the simulated area ──

    /** Bounding box {minX, minZ, maxX, maxZ} (columns) of all simulated chunks, or {@code null} if none. */
    public int[] simulatedBounds() {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (TerrainChunkView c : terrain.chunks()) {
            minX = Math.min(minX, c.chunkX() << 4);
            minZ = Math.min(minZ, c.chunkZ() << 4);
            maxX = Math.max(maxX, (c.chunkX() << 4) + 15);
            maxZ = Math.max(maxZ, (c.chunkZ() << 4) + 15);
        }
        return minX > maxX ? null : new int[] {minX, minZ, maxX, maxZ};
    }

    // ── Keys ──

    static long key(int tx, int tz) {
        return ((long) tx << 32) | (tz & 0xffffffffL);
    }

    static int keyX(long key) {
        return (int) (key >> 32);
    }

    static int keyZ(long key) {
        return (int) key;
    }

    // ── Persistence ──

    @Override
    public void saveState(StateWriter out) {
        JsonObject o = out.json();
        JsonArray tiles = new JsonArray();
        for (long k : added) {
            JsonArray c = new JsonArray();
            c.add(keyX(k));
            c.add(keyZ(k));
            tiles.add(c);
        }
        o.add("added", tiles);
        o.addProperty("revision", revision);
    }

    @Override
    public void loadState(StateReader in) {
        JsonObject o = in.json();
        added.clear();
        complete.clear();
        for (JsonElement e : o.getAsJsonArray("added")) {
            JsonArray c = e.getAsJsonArray();
            added.add(key(c.get(0).getAsInt(), c.get(1).getAsInt()));
        }
        revision = o.has("revision") ? o.get("revision").getAsLong() : 0;
    }

    /** UI summary. */
    public record Snapshot(boolean enabled, boolean generating, int addedTiles, int maxTiles, long revision) {}

    @Override
    public Snapshot snapshot() {
        return new Snapshot(config.enabled(), generator != null, added.size(), config.maxTiles(), revision);
    }
}
