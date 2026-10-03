package me.alex4386.typhon.engine.lava;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.lava.LavaEvents.ChunkCoord;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.Outbox;
import me.alex4386.typhon.engine.random.SimRandom;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.world.BlockState;

/**
 * Lava flow as a 2.5D cellular automaton over surface columns (after MAGFLOW / SCIARA).
 *
 * <p>Every column holds a lava thickness {@code h} (m), temperature, SiO₂ and H₂O content on top of
 * the {@link TerrainModel} ground plus any partially solidified rock. Each step:
 *
 * <ol>
 *   <li><b>Effusion</b>: sources add lava, mixing heat and composition by volume.
 *   <li><b>Flux</b>: for each neighbour (4-connected) with a lower free surface, a Bingham fluid
 *       flows only if {@code h > h_cr = τ_y / (ρ g sinθ)}; the discharge per unit width is
 *       {@code q = ρ g sinθ h³ / (3η) · (1 − 3/2·(h_cr/h) + 1/2·(h_cr/h)³)} (MAGFLOW). The volume
 *       moved is capped by {@code relaxation · Δhead} per neighbour and scaled so the total never
 *       exceeds {@code h}.
 *   <li><b>Update</b>: each cell gathers its inflows — a pure gather, so the result does not depend
 *       on iteration order and volume is conserved.
 *   <li><b>Cooling</b>: radiation {@code εσ(T⁴ − T_a⁴)} (or convective/boiling loss {@code h_w(T −
 *       T_w)} when submerged) plus basal conduction, over {@code ρ c_eff h} where {@code c_eff}
 *       includes latent heat between liquidus and solidus. Below the solidus the column solidifies:
 *       whole blocks raise the terrain and are placed via {@link LavaPalette}.
 *   <li><b>Rendering</b>: molten columns are shown as lava (or a magma-block crust once cooled past
 *       the crust temperature); only columns whose visible state changed emit block changes.
 * </ol>
 *
 * <p>Columns in chunks the host has not sent terrain for act as walls and are requested with a
 * {@link LavaEvents.TerrainNeeded} event.
 *
 * <p>TODO lava tubes: track a crust layer per column (crust forms when the surface cools below the
 * crust temperature while the core still flows); when a crusted column later drains below the crust
 * base, keep the crust as solid blocks and emit air beneath it instead of collapsing the column.
 */
public final class LavaFlow implements Subsystem {
    public static final String ID = "lava";

    private static final double SIGMA = 5.670374419e-8;
    private static final double G = 9.81;
    private static final double KELVIN = 273.15;
    private static final int[] DX = {-1, 1, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1};
    private static final int AREA = LavaChunk.AREA;
    private static final int UNKNOWN = LavaChunk.UNKNOWN;
    private static final Comparator<LavaChunk> BY_KEY = Comparator.comparingLong(c -> c.key);

    private final TerrainModel terrain;
    private final LavaConfig config;
    private final LavaRheology rheology;

    private final Map<Long, LavaChunk> chunks = new HashMap<>();
    private final Map<String, LavaSource> sources = new LinkedHashMap<>();
    private final List<BlockPos> origins = new ArrayList<>();
    private final TreeSet<Long> requestedTerrain = new TreeSet<>();
    private double emittedVolume;
    private double solidifiedVolume;

    // per-step scratch
    private final double[] flux = new double[4];
    private final TreeSet<Long> neededTerrain = new TreeSet<>();
    private long stamp = Long.MIN_VALUE + 1;

    public LavaFlow(TerrainModel terrain) {
        this(terrain, LavaConfig.defaults(), MagflowRheology.INSTANCE);
    }

    public LavaFlow(TerrainModel terrain, LavaConfig config) {
        this(terrain, config, MagflowRheology.INSTANCE);
    }

    public LavaFlow(TerrainModel terrain, LavaConfig config, LavaRheology rheology) {
        this.terrain = terrain;
        this.config = config;
        this.rheology = rheology;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void registerCommands(CommandBus bus) {
        bus.register(LavaCommands.StartEffusion.class, c -> addSource(c.source()));
        bus.register(LavaCommands.StopEffusion.class, c -> removeSource(c.sourceId()));
        bus.register(LavaCommands.SetEffusionRate.class, c -> setRate(c.sourceId(), c.rateM3PerS()));
    }

    // ── Public API ──

    public LavaConfig config() {
        return config;
    }

    public void addSource(LavaSource source) {
        sources.put(source.id(), source);
        for (BlockPos cell : source.cells()) {
            BlockPos origin = new BlockPos(cell.x(), 0, cell.z());
            if (!origins.contains(origin)) origins.add(origin);
        }
    }

    public void removeSource(String sourceId) {
        sources.remove(sourceId);
    }

    public void setRate(String sourceId, double rateM3PerS) {
        LavaSource source = sources.get(sourceId);
        if (source != null) sources.put(sourceId, source.withRate(rateM3PerS));
    }

    public Collection<LavaSource> sources() {
        return Collections.unmodifiableCollection(sources.values());
    }

    /**
     * Injects lava directly (e.g. a molten bomb or a lava-dome collapse). Returns false if the
     * column's terrain is unknown.
     */
    public boolean addLava(int x, int z, double volumeM3, double temperatureC, double silicaWt, double waterWt) {
        if (volumeM3 <= 0) return true;
        LavaChunk c = chunkFor(x >> 4, z >> 4);
        if (c == null) return false;
        if (c.freshStamp != stamp) refresh(c);
        int i = index(x, z);
        if (c.ground[i] == UNKNOWN) return false;
        double h0 = c.thickness[i];
        double h = h0 + volumeM3;
        c.temperature[i] = (c.temperature[i] * h0 + temperatureC * volumeM3) / h;
        c.silica[i] = (c.silica[i] * h0 + silicaWt * volumeM3) / h;
        c.water[i] = (c.water[i] * h0 + waterWt * volumeM3) / h;
        c.thickness[i] = h;
        if (h0 <= 0) c.lavaCells++;
        emittedVolume += volumeM3;
        return true;
    }

    public double thickness(int x, int z) {
        LavaChunk c = chunks.get(LavaChunk.key(x >> 4, z >> 4));
        return c == null ? 0 : c.thickness[index(x, z)];
    }

    public double temperatureC(int x, int z) {
        LavaChunk c = chunks.get(LavaChunk.key(x >> 4, z >> 4));
        return c == null ? 0 : c.temperature[index(x, z)];
    }

    /** Solidified rock in the column that does not yet amount to a whole block (m). */
    public double partialSolid(int x, int z) {
        LavaChunk c = chunks.get(LavaChunk.key(x >> 4, z >> 4));
        return c == null ? 0 : c.solid[index(x, z)];
    }

    public double totalLavaVolume() {
        double sum = 0;
        for (LavaChunk c : sortedChunks(chunks.values())) {
            for (int i = 0; i < AREA; i++) sum += c.thickness[i];
        }
        return sum;
    }

    public int activeCellCount() {
        int n = 0;
        for (LavaChunk c : chunks.values()) n += c.lavaCells;
        return n;
    }

    public double emittedVolume() {
        return emittedVolume;
    }

    public double solidifiedVolume() {
        return solidifiedVolume;
    }

    // ── Step ──

    @Override
    public void step(StepContext context) {
        long tick = context.tick();
        stamp = tick;
        double dt = context.dtSeconds() * config.timeScale();
        Outbox outbox = context.outbox();
        neededTerrain.clear();

        // 0. effusion
        for (LavaSource source : sources.values()) {
            if (source.rateM3PerS() <= 0) continue;
            double perCell = source.rateM3PerS() * dt / source.cells().size();
            for (BlockPos cell : source.cells()) {
                if (!addLava(cell.x(), cell.z(), perCell, source.temperatureC(), source.silicaWt(), source.waterWt())) {
                    neededTerrain.add(LavaChunk.key(cell.x() >> 4, cell.z() >> 4));
                }
            }
        }

        // 1. flux
        List<LavaChunk> active = new ArrayList<>();
        for (LavaChunk c : chunks.values()) if (c.lavaCells > 0) active.add(c);
        for (LavaChunk c : active) {
            ensureFresh(c);
            computeFlux(c, dt);
        }

        // 2. gather + swap
        List<LavaChunk> update = new ArrayList<>();
        for (LavaChunk c : chunks.values()) if (c.lavaCells > 0 || c.touchedStamp == stamp) update.add(c);
        update.sort(BY_KEY);
        int waterEvents = 0;
        for (LavaChunk c : update) {
            ensureFresh(c);
            waterEvents = gather(c, tick, outbox, waterEvents);
        }
        for (LavaChunk c : update) {
            c.swapBuffers();
            c.recount();
        }

        // 3. cooling & solidification
        SolidStats stats = new SolidStats();
        for (LavaChunk c : update) {
            if (c.lavaCells > 0) cool(c, dt, context.random(), outbox, stats);
        }

        // 4. rendering
        for (LavaChunk c : update) render(c, outbox);

        // 5. events & cleanup
        if (stats.cells > 0) {
            outbox.emit(new LavaEvents.LavaSolidified(tick, stats.cells, stats.volume, stats.blocks));
        }
        if (config.frontEventInterval() > 0 && tick % config.frontEventInterval() == 0) {
            emitFront(tick, update, outbox);
        }
        if (!neededTerrain.isEmpty()) {
            List<ChunkCoord> fresh = new ArrayList<>();
            for (long key : neededTerrain) {
                if (requestedTerrain.add(key)) fresh.add(new ChunkCoord((int) (key >> 32), (int) key));
            }
            if (!fresh.isEmpty()) outbox.emit(new LavaEvents.TerrainNeeded(tick, fresh));
        }
        for (LavaChunk c : update) {
            if (!c.hasPersistentState()) chunks.remove(c.key);
        }
    }

    private void computeFlux(LavaChunk c, double dt) {
        c.fluxStamp = stamp;
        Arrays.fill(c.outflow, 0);
        double rhoG = config.densityKgM3() * G;
        double minFlow = config.minFlowThickness();
        double relaxation = config.relaxation();

        for (int i = 0; i < AREA; i++) {
            double h = c.thickness[i];
            if (h < minFlow || c.ground[i] == UNKNOWN) continue;
            double head = c.ground[i] + 1 + c.solid[i] + h;
            double t = c.temperature[i];
            double eta = rheology.viscosityPaS(t, c.silica[i], c.water[i]);
            double tau = rheology.yieldStrengthPa(t, c.silica[i]);
            int lx = i & 15;
            int lz = i >> 4;

            double total = 0;
            for (int d = 0; d < 4; d++) {
                flux[d] = 0;
                int nx = lx + DX[d];
                int nz = lz + DZ[d];
                LavaChunk nc = c;
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                    nc = neighbour(c, d, true);
                    if (nc == null) continue;
                }
                int j = ((nz & 15) << 4) | (nx & 15);
                int gj = nc.ground[j];
                if (gj == UNKNOWN) continue;
                double dh = head - (gj + 1 + nc.solid[j] + nc.thickness[j]);
                if (dh <= 0) continue;
                double sin = dh / Math.sqrt(dh * dh + 1);
                double drive = rhoG * sin;
                double ratio = tau / (drive * h); // h_cr / h
                if (ratio >= 1) continue;
                double q = drive * h * h * h / (3 * eta) * (1 - 1.5 * ratio + 0.5 * ratio * ratio * ratio);
                double v = Math.min(q * dt, dh * relaxation);
                if (v > 0) {
                    flux[d] = v;
                    total += v;
                }
            }
            if (total <= 0) continue;
            double scale = total > h ? h / total : 1;
            for (int d = 0; d < 4; d++) {
                if (flux[d] <= 0) continue;
                c.outflow[d * AREA + i] = flux[d] * scale;
                int nx = lx + DX[d];
                int nz = lz + DZ[d];
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                    c.neighbours[d].touchedStamp = stamp;
                }
            }
        }
    }

    private int gather(LavaChunk c, long tick, Outbox outbox, int waterEvents) {
        boolean ownFlux = c.fluxStamp == stamp;
        for (int i = 0; i < AREA; i++) {
            double h = c.thickness[i];
            double out = 0;
            if (ownFlux) {
                out = c.outflow[i] + c.outflow[AREA + i] + c.outflow[2 * AREA + i] + c.outflow[3 * AREA + i];
            }
            double keep = h - out;
            if (keep < 1e-15) keep = 0;
            double volume = keep;
            double heat = keep * c.temperature[i];
            double silica = keep * c.silica[i];
            double water = keep * c.water[i];

            int lx = i & 15;
            int lz = i >> 4;
            for (int d = 0; d < 4; d++) {
                int nx = lx + DX[d];
                int nz = lz + DZ[d];
                LavaChunk nc = c;
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                    nc = neighbour(c, d, false);
                    if (nc == null) continue;
                }
                if (nc.fluxStamp != stamp) continue;
                int k = ((nz & 15) << 4) | (nx & 15);
                double in = nc.outflow[(d ^ 1) * AREA + k];
                if (in <= 0) continue;
                volume += in;
                heat += in * nc.temperature[k];
                silica += in * nc.silica[k];
                water += in * nc.water[k];
            }

            c.nextThickness[i] = volume;
            if (volume > 0) {
                c.nextTemperature[i] = heat / volume;
                c.nextSilica[i] = silica / volume;
                c.nextWater[i] = water / volume;
                if (h <= 0 && submerged(c, i) && waterEvents < config.maxWaterEventsPerStep()) {
                    outbox.emit(new LavaEvents.LavaEnteredWater(
                            tick, new BlockPos(c.worldX(i), c.ground[i] + 1, c.worldZ(i)), volume));
                    waterEvents++;
                }
            } else {
                c.nextTemperature[i] = 0;
                c.nextSilica[i] = 0;
                c.nextWater[i] = 0;
            }
        }
        return waterEvents;
    }

    private void cool(LavaChunk c, double dt, SimRandom random, Outbox outbox, SolidStats stats) {
        double rhoC = config.densityKgM3();
        double ambientK4 = Math.pow(config.ambientC() + KELVIN, 4);
        for (int i = 0; i < AREA; i++) {
            double h = c.thickness[i];
            if (h <= 0) continue;
            double t = c.temperature[i];
            double si = c.silica[i];
            double liquidus = rheology.liquidusC(si);
            double solidus = rheology.solidusC(si);
            boolean submerged = submerged(c, i);

            double tK = t + KELVIN;
            double qTop = submerged
                    ? config.waterHeatTransferWM2K() * (t - config.waterC())
                    : config.emissivity() * SIGMA * (tK * tK * tK * tK - ambientK4);
            double qBase = config.groundConductivityWMK() * (t - config.ambientC()) / config.groundBoundaryLayerM();
            double cEff = config.specificHeatJKgK();
            if (t < liquidus && t > solidus) cEff += config.latentHeatJKg() / (liquidus - solidus);
            double rate = (qTop + qBase) / (rhoC * cEff * Math.max(h, 0.01)); // K/s, physical
            double next = t - rate * config.coolingScale() * dt;
            double floor = submerged ? config.waterC() : config.ambientC();
            c.temperature[i] = Math.max(next, floor);

            if (c.temperature[i] <= solidus) {
                boolean quenched = submerged || rate > config.quenchRateKPerS();
                boolean columnar = !quenched && h >= config.columnarMinThickness();
                solidify(c, i, h, si, submerged, quenched, columnar, random, outbox, stats);
            }
        }
    }

    private void solidify(LavaChunk c, int i, double h, double silica, boolean submerged, boolean quenched,
            boolean columnar, SimRandom random, Outbox outbox, SolidStats stats) {
        c.thickness[i] = 0;
        c.temperature[i] = 0;
        c.silica[i] = 0;
        c.water[i] = 0;
        c.lavaCells--;
        solidifiedVolume += h;
        stats.cells++;
        stats.volume += h;

        int x = c.worldX(i);
        int z = c.worldZ(i);
        double s = c.solid[i] + h;
        while (s >= 1 - 1e-9) {
            s -= 1;
            int y = c.ground[i] + 1;
            c.ground[i] = y;
            BlockState rock = LavaPalette.rock(silica, submerged, quenched, columnar, random);
            terrain.setGround(x, z, y, rock.id());
            outbox.setBlock(BlockChange.set(new BlockPos(x, y, z), rock));
            stats.blocks++;
        }
        c.solid[i] = Math.max(0, s);
    }

    private void render(LavaChunk c, Outbox outbox) {
        double minThickness = config.renderMinThickness();
        for (int i = 0; i < AREA; i++) {
            int g = c.ground[i];
            if (g == UNKNOWN) continue;
            double h = c.thickness[i];
            int newBottom = g + 1;
            int newCount = 0;
            byte newTop = 0;
            if (h >= minThickness) {
                double total = c.solid[i] + h;
                newCount = Math.max(1, (int) Math.ceil(total - 1e-9));
                double frac = total - (newCount - 1);
                double si = c.silica[i];
                double crust = rheology.solidusC(si) + 0.3 * (rheology.liquidusC(si) - rheology.solidusC(si));
                if (c.temperature[i] >= crust) {
                    int level = (int) Math.max(0, Math.min(7, Math.round((1 - frac) * 7)));
                    newTop = (byte) ((1 << 3) | level);
                } else {
                    newTop = (byte) (2 << 3);
                }
            }

            int oldBottom = c.renderBottom[i];
            int oldCount = c.renderCount[i];
            byte oldTop = c.renderTop[i];
            if (oldBottom == newBottom && oldCount == newCount && oldTop == newTop) continue;
            if (oldCount == 0 && newCount == 0) {
                c.renderBottom[i] = newBottom;
                continue;
            }

            int x = c.worldX(i);
            int z = c.worldZ(i);
            int oldEnd = oldBottom + oldCount;
            int newEnd = newBottom + newCount;
            for (int y = oldBottom; y < oldEnd; y++) {
                if (y <= g || (y >= newBottom && y < newEnd)) continue;
                BlockState restore = c.waterY[i] != TerrainColumn.NO_WATER && y <= c.waterY[i]
                        ? LavaPalette.WATER : LavaPalette.AIR;
                BlockState old = renderedState(y, oldEnd, oldTop);
                outbox.setBlock(BlockChange.replace(new BlockPos(x, y, z), old.id(), restore));
            }
            for (int y = newBottom; y < newEnd; y++) {
                BlockState next = renderedState(y, newEnd, newTop);
                BlockPos pos = new BlockPos(x, y, z);
                if (y >= oldBottom && y < oldEnd) {
                    BlockState old = renderedState(y, oldEnd, oldTop);
                    if (old.equals(next)) continue;
                    outbox.setBlock(BlockChange.replace(pos, old.id(), next));
                } else {
                    outbox.setBlock(BlockChange.set(pos, next));
                }
            }
            c.renderBottom[i] = newBottom;
            c.renderCount[i] = (short) newCount;
            c.renderTop[i] = newTop;
        }
    }

    private static BlockState renderedState(int y, int end, byte top) {
        if (y != end - 1) return LavaPalette.lava(0);
        return (top >> 3) == 2 ? LavaPalette.MAGMA_CRUST : LavaPalette.lava(top & 7);
    }

    private void emitFront(long tick, List<LavaChunk> update, Outbox outbox) {
        if (origins.isEmpty()) return;
        double best = -1;
        BlockPos front = null;
        int cells = 0;
        double volume = 0;
        for (LavaChunk c : update) {
            if (c.lavaCells == 0) continue;
            for (int i = 0; i < AREA; i++) {
                double h = c.thickness[i];
                if (h <= 0) continue;
                cells++;
                volume += h;
                int x = c.worldX(i);
                int z = c.worldZ(i);
                double nearest = Double.MAX_VALUE;
                for (BlockPos o : origins) {
                    double dx = x - o.x();
                    double dz = z - o.z();
                    nearest = Math.min(nearest, dx * dx + dz * dz);
                }
                if (nearest > best) {
                    best = nearest;
                    front = new BlockPos(x, c.ground[i] + 1, z);
                }
            }
        }
        if (front != null) {
            outbox.emit(new LavaEvents.LavaFlowFront(tick, front, Math.sqrt(best), cells, volume));
        }
    }

    // ── Chunk management ──

    private LavaChunk chunkFor(int cx, int cz) {
        long key = LavaChunk.key(cx, cz);
        LavaChunk c = chunks.get(key);
        if (c != null) return c;
        if (!terrain.isKnown(cx << 4, cz << 4)) return null;
        requestedTerrain.remove(key);
        c = new LavaChunk(cx, cz);
        chunks.put(key, c);
        return c;
    }

    /** Neighbouring chunk in direction {@code d}; with {@code create}, makes it (or requests terrain). */
    private LavaChunk neighbour(LavaChunk c, int d, boolean create) {
        if (c.neighbourStamp != stamp) {
            Arrays.fill(c.neighbours, null);
            c.neighbourStamp = stamp;
        }
        LavaChunk n = c.neighbours[d];
        if (n != null) return n;
        int ncx = c.cx + DX[d];
        int ncz = c.cz + DZ[d];
        if (create) {
            n = chunkFor(ncx, ncz);
            if (n == null) {
                neededTerrain.add(LavaChunk.key(ncx, ncz));
                return null;
            }
        } else {
            n = chunks.get(LavaChunk.key(ncx, ncz));
            if (n == null) return null;
        }
        ensureFresh(n);
        c.neighbours[d] = n;
        return n;
    }

    private void ensureFresh(LavaChunk c) {
        if (c.freshStamp == stamp) return;
        refresh(c);
    }

    private void refresh(LavaChunk c) {
        c.freshStamp = stamp;
        int bx = c.cx << 4;
        int bz = c.cz << 4;
        if (!terrain.isKnown(bx, bz)) {
            Arrays.fill(c.ground, UNKNOWN);
            Arrays.fill(c.waterY, TerrainColumn.NO_WATER);
            return;
        }
        for (int i = 0; i < AREA; i++) {
            TerrainColumn column = terrain.column(bx | (i & 15), bz | (i >> 4));
            c.ground[i] = column.groundY();
            c.waterY[i] = column.waterY();
        }
    }

    private static boolean submerged(LavaChunk c, int i) {
        int w = c.waterY[i];
        return w != TerrainColumn.NO_WATER && w > c.ground[i];
    }

    private static int index(int x, int z) {
        return ((z & 15) << 4) | (x & 15);
    }

    private static List<LavaChunk> sortedChunks(Collection<LavaChunk> chunks) {
        List<LavaChunk> list = new ArrayList<>(chunks);
        list.sort(BY_KEY);
        return list;
    }

    private static final class SolidStats {
        int cells;
        int blocks;
        double volume;
    }

    // ── Persistence ──

    private static final int CELL_BYTES = 5 * Double.BYTES + Integer.BYTES + Short.BYTES + Byte.BYTES;

    @Override
    public void saveState(JsonObject out) {
        out.addProperty("emitted", emittedVolume);
        out.addProperty("solidified", solidifiedVolume);

        JsonArray sourceArray = new JsonArray();
        for (LavaSource s : sources.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", s.id());
            o.add("cells", positions(s.cells()));
            o.addProperty("rate", s.rateM3PerS());
            o.addProperty("temperature", s.temperatureC());
            o.addProperty("silica", s.silicaWt());
            o.addProperty("water", s.waterWt());
            sourceArray.add(o);
        }
        out.add("sources", sourceArray);
        out.add("origins", positions(origins));

        JsonArray requested = new JsonArray();
        for (long key : requestedTerrain) requested.add(key);
        out.add("requestedTerrain", requested);

        JsonArray chunkArray = new JsonArray();
        for (LavaChunk c : sortedChunks(chunks.values())) {
            if (!c.hasPersistentState()) continue;
            ByteBuffer buf = ByteBuffer.allocate(AREA * CELL_BYTES);
            for (int i = 0; i < AREA; i++) {
                buf.putDouble(c.thickness[i]).putDouble(c.temperature[i]).putDouble(c.silica[i])
                        .putDouble(c.water[i]).putDouble(c.solid[i])
                        .putInt(c.renderBottom[i]).putShort(c.renderCount[i]).put(c.renderTop[i]);
            }
            JsonObject o = new JsonObject();
            o.addProperty("x", c.cx);
            o.addProperty("z", c.cz);
            o.addProperty("data", Base64.getEncoder().encodeToString(buf.array()));
            chunkArray.add(o);
        }
        out.add("chunks", chunkArray);
    }

    @Override
    public void loadState(JsonObject in) {
        chunks.clear();
        sources.clear();
        origins.clear();
        requestedTerrain.clear();
        emittedVolume = in.get("emitted").getAsDouble();
        solidifiedVolume = in.get("solidified").getAsDouble();

        for (JsonElement e : in.getAsJsonArray("sources")) {
            JsonObject o = e.getAsJsonObject();
            LavaSource s = new LavaSource(o.get("id").getAsString(), readPositions(o.getAsJsonArray("cells")),
                    o.get("rate").getAsDouble(), o.get("temperature").getAsDouble(), o.get("silica").getAsDouble(),
                    o.get("water").getAsDouble());
            sources.put(s.id(), s);
        }
        origins.addAll(readPositions(in.getAsJsonArray("origins")));
        for (JsonElement e : in.getAsJsonArray("requestedTerrain")) requestedTerrain.add(e.getAsLong());

        for (JsonElement e : in.getAsJsonArray("chunks")) {
            JsonObject o = e.getAsJsonObject();
            LavaChunk c = new LavaChunk(o.get("x").getAsInt(), o.get("z").getAsInt());
            ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(o.get("data").getAsString()));
            for (int i = 0; i < AREA; i++) {
                c.thickness[i] = buf.getDouble();
                c.temperature[i] = buf.getDouble();
                c.silica[i] = buf.getDouble();
                c.water[i] = buf.getDouble();
                c.solid[i] = buf.getDouble();
                c.renderBottom[i] = buf.getInt();
                c.renderCount[i] = buf.getShort();
                c.renderTop[i] = buf.get();
            }
            c.recount();
            chunks.put(c.key, c);
        }
    }

    private static JsonArray positions(List<BlockPos> list) {
        JsonArray array = new JsonArray();
        for (BlockPos p : list) {
            JsonArray a = new JsonArray();
            a.add(p.x());
            a.add(p.y());
            a.add(p.z());
            array.add(a);
        }
        return array;
    }

    private static List<BlockPos> readPositions(JsonArray array) {
        List<BlockPos> list = new ArrayList<>();
        for (JsonElement e : array) {
            JsonArray a = e.getAsJsonArray();
            list.add(new BlockPos(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()));
        }
        return list;
    }
}
