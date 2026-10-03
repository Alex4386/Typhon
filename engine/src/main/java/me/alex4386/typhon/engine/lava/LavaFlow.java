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
import me.alex4386.typhon.engine.terrain.TerrainChunkView;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.world.BlockState;

/**
 * Lava flow as a 2.5D cellular automaton over surface columns (after MAGFLOW / SCIARA).
 *
 * <p>Every column holds a molten core of thickness {@code h} (m) with temperature, SiO₂ and H₂O on
 * top of the {@link TerrainModel} ground plus any partially solidified rock, and optionally a rigid
 * crust above the core. Each step:
 *
 * <ol>
 *   <li><b>Effusion</b>: sources add lava, mixing heat and composition by volume.
 *   <li><b>Flux</b>: for each neighbour (4-connected) with a lower melt surface, a Bingham fluid
 *       flows only if {@code h > h_cr = τ_y / (ρ g sinθ)}; the discharge per unit width is
 *       {@code q = ρ g sinθ h³ / (3η) · (1 − 3/2·(h_cr/h) + 1/2·(h_cr/h)³)} (MAGFLOW). The volume
 *       moved is capped by {@code relaxation · Δhead} per neighbour and scaled so the total never
 *       exceeds {@code h}. A crust does not change the hydraulic head: melt keeps flowing beneath
 *       it (tube flow).
 *   <li><b>Update</b>: each cell gathers its inflows — a pure gather, so the result does not depend
 *       on iteration order and volume is conserved.
 *   <li><b>Cooling</b>:
 *       <ul>
 *         <li><i>Open or submerged columns</i> (thin, fast, at a vent, or under water) cool as a
 *             whole: radiation {@code εσ(T⁴ − T_a⁴)} or, submerged, {@code h_w(T − T_w)}, plus
 *             basal conduction, over {@code ρ c_eff h} with latent heat between liquidus and
 *             solidus.
 *         <li><i>Quiet columns</i> (at least {@link LavaConfig#crustMinThickness()} thick and slower
 *             than {@link LavaConfig#crustDisruptionVelocity()}) grow a crust instead (Stefan
 *             problem): the surface loss {@code (T − T_a) / (h_c/k_c + 1/h_rad)} freezes melt onto
 *             the crust base at {@code ρ (L + c (T − T_sol))} per m³, while the insulated core only
 *             loses heat through its base. A quiet pond's crust grows as ≈ √t (≈0.45 m after a day
 *             at k_c = 1 W/m·K), and insulated flows stay hot and travel farther.
 *         <li>Melt pushing up into its crust lifts it (inflation); a faster flow, a vent or water
 *             tears the crust up and re-mixes it.
 *       </ul>
 *       Below the solidus the column solidifies: whole blocks raise the terrain and are placed via
 *       {@link LavaPalette}. Lava quenched in water sheds part of its volume as hyaloclastite onto
 *       the steepest lower submerged neighbour, so sustained ocean entry builds a delta seaward.
 *   <li><b>Tubes</b>: when the melt under a crust drains away (supply stopped, flow moved on), a
 *       roof at least {@link LavaConfig#tubeMinRoofThickness()} thick over at least one block of
 *       void stays standing: the roof becomes the new surface and the void is recorded as a
 *       {@link LavaTube}. Thinner roofs collapse into the column.
 *   <li><b>Rendering</b>: molten columns are shown as lava (or a magma-block skin once crusted or
 *       cooled past the crust temperature), drained voids as air and thick crust as roof rock; only
 *       columns whose visible state changed emit block changes.
 * </ol>
 *
 * <p>Columns in chunks the host has not sent terrain for act as walls and are requested with a
 * {@link LavaEvents.TerrainNeeded} event.
 *
 * <p>Hosts must not report engine-rendered lava, magma-block skin or roof rock above
 * {@link TerrainModel} ground as ground in later terrain snapshots, or the melt would be counted
 * twice; tube voids are listed by {@link #tubes()}.
 */
public final class LavaFlow implements Subsystem {
    public static final String ID = "lava";

    private static final int STATE_FORMAT = 2;
    private static final double SIGMA = 5.670374419e-8;
    private static final double G = 9.81;
    private static final double KELVIN = 273.15;
    private static final double GAP_EPS = 1e-6;
    private static final double WATER_HEAT_CAPACITY = 4186;
    private static final double WATER_LATENT_HEAT = 2.26e6;
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
    private final List<LavaTube> tubes = new ArrayList<>();
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

    /** Molten core thickness (m). */
    public double thickness(int x, int z) {
        LavaChunk c = chunks.get(LavaChunk.key(x >> 4, z >> 4));
        return c == null ? 0 : c.thickness[index(x, z)];
    }

    public double temperatureC(int x, int z) {
        LavaChunk c = chunks.get(LavaChunk.key(x >> 4, z >> 4));
        return c == null ? 0 : c.temperature[index(x, z)];
    }

    /** Rigid crust above the molten core (m). */
    public double crustThickness(int x, int z) {
        LavaChunk c = chunks.get(LavaChunk.key(x >> 4, z >> 4));
        return c == null ? 0 : c.crust[index(x, z)];
    }

    /** Solidified rock in the column that does not yet amount to a whole block (m). */
    public double partialSolid(int x, int z) {
        LavaChunk c = chunks.get(LavaChunk.key(x >> 4, z >> 4));
        return c == null ? 0 : c.solid[index(x, z)];
    }

    /** Molten volume (m³). Emitted = molten + crust + solidified. */
    public double totalLavaVolume() {
        double sum = 0;
        for (LavaChunk c : sortedChunks(chunks.values())) {
            for (int i = 0; i < AREA; i++) sum += c.thickness[i];
        }
        return sum;
    }

    /** Volume currently held in crusts over molten cores (m³). */
    public double crustVolume() {
        double sum = 0;
        for (LavaChunk c : sortedChunks(chunks.values())) {
            for (int i = 0; i < AREA; i++) sum += c.crust[i];
        }
        return sum;
    }

    public int activeCellCount() {
        int n = 0;
        for (LavaChunk c : chunks.values()) n += c.lavaCells;
        return n;
    }

    public int crustedCellCount() {
        int n = 0;
        for (LavaChunk c : chunks.values()) n += c.crustCells;
        return n;
    }

    public double emittedVolume() {
        return emittedVolume;
    }

    public double solidifiedVolume() {
        return solidifiedVolume;
    }

    /** Hollow lava-tube voids left by drained flows, in formation order. */
    public List<LavaTube> tubes() {
        return Collections.unmodifiableList(tubes);
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
                    continue;
                }
                chunks.get(LavaChunk.key(cell.x() >> 4, cell.z() >> 4)).sourceStamp[index(cell.x(), cell.z())] = stamp;
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
        for (LavaChunk c : chunks.values()) if (c.isActive() || c.touchedStamp == stamp) update.add(c);
        update.sort(BY_KEY);
        int waterEvents = 0;
        for (LavaChunk c : update) {
            ensureFresh(c);
            waterEvents = gather(c, tick, dt, outbox, waterEvents);
        }
        for (LavaChunk c : update) {
            c.swapBuffers();
            c.recount();
        }

        // 3. cooling, crust, tubes & solidification
        SolidStats stats = new SolidStats();
        List<LavaTube> formed = new ArrayList<>();
        for (LavaChunk c : update) {
            if (c.isActive()) cool(c, dt, context.random(), outbox, stats, formed);
        }
        for (LavaChunk c : update) c.recount();

        // 4. rendering
        for (LavaChunk c : update) render(c, outbox);

        // 5. events & cleanup
        if (stats.cells > 0) {
            outbox.emit(new LavaEvents.LavaSolidified(tick, stats.cells, stats.volume, stats.blocks));
        }
        if (!formed.isEmpty()) {
            tubes.addAll(formed);
            outbox.emit(new LavaEvents.LavaTubesFormed(tick, formed));
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
        Arrays.fill(c.speed, 0);
        double rhoG = config.densityKgM3() * G;
        double minFlow = config.minFlowThickness();
        double relaxation = config.relaxation();

        for (int i = 0; i < AREA; i++) {
            double h = c.thickness[i];
            if (h < minFlow || c.ground[i] == UNKNOWN) continue;
            double head = c.ground[i] + 1 + c.solid[i] + h;
            int lx = i & 15;
            int lz = i >> 4;

            double eta = Double.NaN;
            double tau = 0;
            double total = 0;
            double discharge = 0; // physical q summed over directions (m²/s), before numerical caps
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
                if (eta != eta) { // rheology only for cells that have somewhere to flow
                    double t = c.temperature[i];
                    eta = rheology.viscosityPaS(t, c.silica[i], c.water[i]);
                    tau = rheology.yieldStrengthPa(t, c.silica[i]);
                }
                double sin = dh / Math.sqrt(dh * dh + 1);
                double drive = rhoG * sin;
                double ratio = tau / (drive * h); // h_cr / h
                if (ratio >= 1) continue;
                double q = drive * h * h * h / (3 * eta) * (1 - 1.5 * ratio + 0.5 * ratio * ratio * ratio);
                discharge += q;
                double v = Math.min(q * dt, dh * relaxation);
                if (v > 0) {
                    flux[d] = v;
                    total += v;
                }
            }
            c.speed[i] = discharge / h;
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

    private int gather(LavaChunk c, long tick, double dt, Outbox outbox, int waterEvents) {
        boolean ownFlux = c.fluxStamp == stamp;
        for (int i = 0; i < AREA; i++) {
            double h = c.thickness[i];
            double out = ownFlux ? outflowOf(c, i) : 0;
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
                double t = heat / volume;
                c.nextTemperature[i] = t;
                c.nextSilica[i] = silica / volume;
                c.nextWater[i] = water / volume;
                if (h <= 0 && submerged(c, i) && waterEvents < config.maxWaterEventsPerStep()) {
                    outbox.emit(waterEntry(tick, c, i, volume, t, dt));
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

    private LavaEvents.LavaEnteredWater waterEntry(long tick, LavaChunk c, int i, double volume, double t, double dt) {
        double fluxM3s = dt > 0 ? volume / dt : 0;
        double heatPerM3 = config.densityKgM3()
                * (config.specificHeatJKgK() * Math.max(0, t - config.waterC()) + config.latentHeatJKg());
        double powerW = fluxM3s * heatPerM3;
        double steam = powerW / (WATER_HEAT_CAPACITY * Math.max(0, 100 - config.waterC()) + WATER_LATENT_HEAT);
        return new LavaEvents.LavaEnteredWater(tick, new BlockPos(c.worldX(i), c.ground[i] + 1, c.worldZ(i)), volume,
                powerW / 1e6, steam, fluxM3s >= config.littoralExplosionFluxM3s());
    }

    private static double outflowOf(LavaChunk c, int i) {
        return c.outflow[i] + c.outflow[AREA + i] + c.outflow[2 * AREA + i] + c.outflow[3 * AREA + i];
    }

    private void cool(LavaChunk c, double dt, SimRandom random, Outbox outbox, SolidStats stats, List<LavaTube> formed) {
        double rho = config.densityKgM3();
        double cs = config.coolingScale();
        double ambient = config.ambientC();
        double ambientK4 = Math.pow(ambient + KELVIN, 4);
        boolean ownFlux = c.fluxStamp == stamp;
        for (int i = 0; i < AREA; i++) {
            double h = c.thickness[i];
            double hc = c.crust[i];
            if (h <= 0 && hc <= 0) continue;
            boolean submerged = submerged(c, i);

            if (hc > 0) {
                double meltTop = c.ground[i] + 1 + c.solid[i] + h;
                double crustBase = c.roofTop[i] - hc;
                if (meltTop > crustBase) c.roofTop[i] += meltTop - crustBase; // inflation lifts the roof
                // A young crust is torn up by fast flow; a roof thick enough to span the flow is
                // anchored to its levees and lets the melt run beneath it (tube flow).
                boolean anchored = hc >= config.tubeMinRoofThickness();
                boolean torn = submerged || isSource(c, i)
                        || (!anchored && localSpeed(c, i) > config.crustDisruptionVelocity());
                if (torn && h > 0) {
                    remelt(c, i);
                    h = c.thickness[i];
                    hc = 0;
                }
            }
            if (h <= 0) { // the melt flowed away from under its crust
                resolveDrained(c, i, random, outbox, stats, formed);
                continue;
            }

            double t = c.temperature[i];
            double si = c.silica[i];
            double liquidus = rheology.liquidusC(si);
            double solidus = rheology.solidusC(si);
            double meltTop = c.ground[i] + 1 + c.solid[i] + h;
            double gap = hc > 0 ? (c.roofTop[i] - hc) - meltTop : 0;
            boolean underVoid = hc > 0 && gap > GAP_EPS;

            double tK = t + KELVIN;
            double qTop;
            if (submerged) {
                qTop = config.waterHeatTransferWM2K() * (t - config.waterC());
            } else if (underVoid) {
                qTop = 0; // melt under a drained roof faces a hot void, not the sky
            } else {
                double radiative = config.emissivity() * SIGMA * (tK * tK * tK * tK - ambientK4);
                qTop = hc > 0 && t > ambient
                        ? (t - ambient) / (hc / config.crustConductivityWMK() + (t - ambient) / radiative)
                        : radiative;
            }
            double qBase = config.groundConductivityWMK() * (t - ambient) / config.groundBoundaryLayerM();
            double cEff = config.specificHeatJKgK();
            if (t < liquidus && t > solidus) cEff += config.latentHeatJKg() / (liquidus - solidus);

            boolean anchored = hc >= config.tubeMinRoofThickness();
            boolean quiet = config.crustEnabled() && !submerged && !underVoid && h + hc >= config.crustMinThickness()
                    && !isSource(c, i) && (anchored || localSpeed(c, i) <= config.crustDisruptionVelocity());

            double rate; // physical core cooling rate, K/s
            if (quiet && t > solidus && qTop > 0) {
                // Stefan: the surface loss freezes melt onto the crust base; the core cools through its base.
                double freezeHeat = rho * (config.latentHeatJKg() + config.specificHeatJKgK() * (t - solidus));
                double grow = Math.min(h, qTop * cs * dt / freezeHeat);
                if (hc <= 0) {
                    c.roofTop[i] = meltTop;
                    c.crustKind[i] = LavaPalette.crustKind(si);
                }
                h -= grow;
                hc += grow;
                c.thickness[i] = h;
                c.crust[i] = hc;
                rate = qBase / (rho * cEff * Math.max(h, 0.01));
            } else {
                rate = (qTop + qBase) / (rho * cEff * Math.max(h, 0.01));
            }
            double floor = submerged ? config.waterC() : ambient;
            c.temperature[i] = Math.max(t - rate * cs * dt, floor);

            if (h <= 0) {
                // the whole melt froze into the crust
                solidifyColumn(c, i, si, submerged, false, h + hc >= config.columnarMinThickness(), random, outbox, stats);
            } else if (underVoid && (h < config.tubeDrainThickness() || c.temperature[i] <= solidus)) {
                resolveDrained(c, i, random, outbox, stats, formed);
            } else if (c.temperature[i] <= solidus) {
                boolean quenched = submerged || rate > config.quenchRateKPerS();
                boolean columnar = !quenched && h + hc >= config.columnarMinThickness();
                solidifyColumn(c, i, si, submerged, quenched, columnar, random, outbox, stats);
            }
        }
    }

    /**
     * Fastest physical flow speed in the cell and its 4 neighbours this step: a crust plate is
     * sheared apart by fast melt beside it, not only beneath it.
     */
    private double localSpeed(LavaChunk c, int i) {
        double v = c.fluxStamp == stamp ? c.speed[i] : 0;
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
            v = Math.max(v, nc.speed[((nz & 15) << 4) | (nx & 15)]);
        }
        return v;
    }

    /** Tears the crust up and mixes it back into the melt (crust taken at the solidus). */
    private void remelt(LavaChunk c, int i) {
        double h = c.thickness[i];
        double hc = c.crust[i];
        double total = h + hc;
        double crustT = rheology.solidusC(c.silica[i]);
        c.temperature[i] = (c.temperature[i] * h + crustT * hc) / total;
        c.thickness[i] = total;
        c.crust[i] = 0;
        c.roofTop[i] = 0;
    }

    /**
     * The melt under a crust is gone (or a film): the film freezes onto the floor, then a thick enough
     * roof over a whole-block void stays as a tube, otherwise the roof collapses into the column.
     */
    private void resolveDrained(LavaChunk c, int i, SimRandom random, Outbox outbox, SolidStats stats,
            List<LavaTube> formed) {
        double film = c.thickness[i];
        double hc = c.crust[i];
        byte kind = c.crustKind[i];
        double silica = film > 0 ? c.silica[i] : LavaPalette.crustSilica(kind);
        boolean submerged = submerged(c, i);
        clearMelt(c, i);
        stats.volume += film;
        solidifiedVolume += film;
        c.solid[i] += film;
        raiseGround(c, i, silica, submerged, false, false, null, random, outbox, stats);

        // A roof that is strong enough to stand is shown as at least one block.
        int roofEnd = (int) Math.ceil(c.roofTop[i] - 0.5);
        int roofBottom = Math.min((int) Math.ceil(c.roofTop[i] - hc - 0.5), roofEnd - 1);
        int voidBottom = c.ground[i] + 1;
        if (hc >= config.tubeMinRoofThickness() && roofBottom > voidBottom) {
            formTube(c, i, kind, voidBottom, roofBottom, roofEnd, outbox, formed);
        } else {
            // roof too thin or no void: it caves in onto the floor
            c.crust[i] = 0;
            c.roofTop[i] = 0;
            solidifiedVolume += hc;
            stats.volume += hc;
            c.solid[i] += hc;
            raiseGround(c, i, LavaPalette.crustSilica(kind), submerged, false, false, null, random, outbox, stats);
        }
        stats.cells++;
    }

    /** Converts a drained column into a roofed void; the roof top becomes the surface. */
    private void formTube(LavaChunk c, int i, byte kind, int voidBottom, int roofBottom, int roofEnd, Outbox outbox,
            List<LavaTube> formed) {
        int x = c.worldX(i);
        int z = c.worldZ(i);
        BlockState roof = LavaPalette.roof(kind);
        int oldBottom = c.renderBottom[i];
        int oldEnd = oldBottom + c.renderCount(i);
        for (int y = Math.min(voidBottom, oldBottom); y < Math.max(roofEnd, oldEnd); y++) {
            if (y < voidBottom) continue;
            BlockState old = renderedState(c, i, y);
            BlockState next;
            if (y < roofBottom) {
                next = BlockState.AIR;
            } else if (y < roofEnd) {
                next = roof;
            } else {
                next = c.waterY[i] != TerrainColumn.NO_WATER && y <= c.waterY[i] ? LavaPalette.WATER : LavaPalette.AIR;
            }
            BlockPos pos = new BlockPos(x, y, z);
            if (old == null) {
                if (!next.equals(BlockState.AIR)) outbox.setBlock(BlockChange.set(pos, next));
            } else if (!old.equals(next)) {
                outbox.setBlock(BlockChange.replace(pos, old.id(), next));
            }
        }

        double hc = c.crust[i];
        solidifiedVolume += hc;
        c.crust[i] = 0;
        c.roofTop[i] = 0;
        c.solid[i] = 0;
        int surface = roofEnd - 1;
        c.ground[i] = surface;
        terrain.setGround(x, z, surface, roof.id());
        setRender(c, i, roofEnd, 0, 0, 0, (byte) 0, (byte) 0);
        formed.add(new LavaTube(x, z, voidBottom, roofBottom - 1));
    }

    private void clearMelt(LavaChunk c, int i) {
        c.thickness[i] = 0;
        c.temperature[i] = 0;
        c.silica[i] = 0;
        c.water[i] = 0;
    }

    /** Freezes melt and crust of a column into rock. */
    private void solidifyColumn(LavaChunk c, int i, double silica, boolean submerged, boolean quenched,
            boolean columnar, SimRandom random, Outbox outbox, SolidStats stats) {
        double total = c.thickness[i] + c.crust[i];
        clearMelt(c, i);
        c.crust[i] = 0;
        c.roofTop[i] = 0;
        solidifiedVolume += total;
        stats.cells++;
        stats.volume += total;

        if (submerged && config.hyaloclastiteFraction() > 0) {
            total -= shedHyaloclastite(c, i, total * config.hyaloclastiteFraction(), random, outbox, stats);
        }
        c.solid[i] += total;
        raiseGround(c, i, silica, submerged, quenched, columnar, null, random, outbox, stats);
    }

    /**
     * Quench-shattered fragments slide onto the lowest submerged neighbour below this column's
     * surface (delta foreset). Returns the volume shed.
     */
    private double shedHyaloclastite(LavaChunk c, int i, double volume, SimRandom random, Outbox outbox,
            SolidStats stats) {
        double surface = c.ground[i] + 1 + c.solid[i];
        int lx = i & 15;
        int lz = i >> 4;
        LavaChunk best = null;
        int bestIndex = -1;
        double bestTop = surface - 0.5;
        for (int d = 0; d < 4; d++) {
            int nx = lx + DX[d];
            int nz = lz + DZ[d];
            LavaChunk nc = c;
            if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                nc = neighbour(c, d, true);
                if (nc == null) continue;
            }
            int j = ((nz & 15) << 4) | (nx & 15);
            if (nc.ground[j] == UNKNOWN || !submerged(nc, j)) continue;
            double top = nc.ground[j] + 1 + nc.solid[j] + nc.thickness[j];
            if (top < bestTop) {
                bestTop = top;
                best = nc;
                bestIndex = j;
            }
        }
        if (best == null) return 0;
        best.solid[bestIndex] += volume;
        raiseGround(best, bestIndex, 0, true, true, false, LavaPalette.HYALOCLASTITE, random, outbox, stats);
        return volume;
    }

    /** Turns whole blocks of a column's partial solid into rock, raising the terrain. */
    private void raiseGround(LavaChunk c, int i, double silica, boolean submerged, boolean quenched, boolean columnar,
            BlockState forced, SimRandom random, Outbox outbox, SolidStats stats) {
        int x = c.worldX(i);
        int z = c.worldZ(i);
        double s = c.solid[i];
        while (s >= 1 - 1e-9) {
            s -= 1;
            int y = c.ground[i] + 1;
            c.ground[i] = y;
            BlockState rock = forced != null ? forced : LavaPalette.rock(silica, submerged, quenched, columnar, random);
            terrain.setGround(x, z, y, rock.id());
            outbox.setBlock(BlockChange.set(new BlockPos(x, y, z), rock));
            stats.blocks++;
        }
        c.solid[i] = Math.max(0, s);
    }

    // ── Rendering ──

    private void render(LavaChunk c, Outbox outbox) {
        double minThickness = config.renderMinThickness();
        for (int i = 0; i < AREA; i++) {
            int g = c.ground[i];
            if (g == UNKNOWN) continue;
            double h = c.thickness[i];
            double hc = c.crust[i];
            int bottom = g + 1;
            int melt = 0;
            int gap = 0;
            int roof = 0;
            byte top = 0;
            byte roofKind = 0;
            if (h >= minThickness) {
                melt = Math.max(1, (int) Math.ceil(c.solid[i] + h - 1e-9));
            }
            if (hc > 0) {
                roofKind = c.crustKind[i];
                int roofBottom = (int) Math.ceil(c.roofTop[i] - hc - 0.5);
                int roofEnd = (int) Math.ceil(c.roofTop[i] - 0.5);
                if (roofEnd > roofBottom) {
                    roofBottom = Math.max(roofBottom, bottom);
                    melt = Math.min(melt, roofBottom - bottom);
                    gap = roofBottom - bottom - melt;
                    roof = Math.max(0, roofEnd - roofBottom);
                }
            }
            if (melt > 0) {
                if (gap > 0 || roof > 0) {
                    top = (byte) (1 << 3); // full lava block under a gap or roof
                } else {
                    double total = c.solid[i] + h;
                    double frac = total - (melt - 1);
                    double si = c.silica[i];
                    double crustT = rheology.solidusC(si) + 0.3 * (rheology.liquidusC(si) - rheology.solidusC(si));
                    if (c.temperature[i] >= crustT && hc < config.crustRenderThickness()) {
                        int level = (int) Math.max(0, Math.min(7, Math.round((1 - frac) * 7)));
                        top = (byte) ((1 << 3) | level);
                    } else {
                        top = (byte) (2 << 3);
                    }
                }
            }

            int oldBottom = c.renderBottom[i];
            int oldCount = c.renderCount(i);
            if (oldBottom == bottom && c.renderMelt[i] == melt && c.renderGap[i] == gap && c.renderRoof[i] == roof
                    && c.renderTop[i] == top && c.renderRoofKind[i] == roofKind) {
                continue;
            }
            int newCount = melt + gap + roof;
            if (oldCount == 0 && newCount == 0) {
                c.renderBottom[i] = bottom;
                continue;
            }

            int x = c.worldX(i);
            int z = c.worldZ(i);
            int oldEnd = oldBottom + oldCount;
            int newEnd = bottom + newCount;
            for (int y = oldBottom; y < oldEnd; y++) {
                if (y <= g) continue; // overwritten by rock
                BlockState old = renderedState(c, i, y);
                BlockState next = y >= bottom && y < newEnd ? layoutState(bottom, melt, gap, top, roofKind, y) : null;
                if (next == null) {
                    next = c.waterY[i] != TerrainColumn.NO_WATER && y <= c.waterY[i] ? LavaPalette.WATER : LavaPalette.AIR;
                }
                if (!next.equals(old)) outbox.setBlock(BlockChange.replace(new BlockPos(x, y, z), old.id(), next));
            }
            for (int y = bottom; y < newEnd; y++) {
                if (y >= oldBottom && y < oldEnd) continue;
                BlockState next = layoutState(bottom, melt, gap, top, roofKind, y);
                if (next.equals(BlockState.AIR)) continue;
                outbox.setBlock(BlockChange.set(new BlockPos(x, y, z), next));
            }
            setRender(c, i, bottom, melt, gap, roof, top, roofKind);
        }
    }

    private static void setRender(LavaChunk c, int i, int bottom, int melt, int gap, int roof, byte top, byte roofKind) {
        c.renderBottom[i] = bottom;
        c.renderMelt[i] = (short) melt;
        c.renderGap[i] = (short) gap;
        c.renderRoof[i] = (short) roof;
        c.renderTop[i] = top;
        c.renderRoofKind[i] = roofKind;
    }

    /** What the last render placed at {@code y}, or {@code null} outside the rendered range. */
    private static BlockState renderedState(LavaChunk c, int i, int y) {
        int bottom = c.renderBottom[i];
        if (y < bottom || y >= bottom + c.renderCount(i)) return null;
        return layoutState(bottom, c.renderMelt[i], c.renderGap[i], c.renderTop[i], c.renderRoofKind[i], y);
    }

    private static BlockState layoutState(int bottom, int melt, int gap, byte top, byte roofKind, int y) {
        int k = y - bottom;
        if (k < melt) {
            if (k != melt - 1) return LavaPalette.lava(0);
            return (top >> 3) == 2 ? LavaPalette.MAGMA_CRUST : LavaPalette.lava(top & 7);
        }
        if (k < melt + gap) return BlockState.AIR;
        return LavaPalette.roof(roofKind);
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

    /** Re-reads the chunk's terrain, but only if the terrain chunk was replaced or modified. */
    private void refresh(LavaChunk c) {
        c.freshStamp = stamp;
        TerrainChunkView view = terrain.chunkView(c.cx, c.cz);
        if (view == null) {
            if (c.terrainView != null || c.ground[0] != UNKNOWN) {
                Arrays.fill(c.ground, UNKNOWN);
                Arrays.fill(c.waterY, TerrainColumn.NO_WATER);
            }
            c.terrainView = null;
            return;
        }
        if (view == c.terrainView && view.version() == c.terrainVersion) return;
        view.copyGroundY(c.ground, 0);
        view.copyWaterY(c.waterY, 0);
        c.terrainView = view;
        c.terrainVersion = view.version();
    }

    /** Fed by an effusive source this step: vents stay open (no crust). */
    private boolean isSource(LavaChunk c, int i) {
        return c.sourceStamp[i] == stamp;
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

    private static final int CELL_BYTES_V1 = 5 * Double.BYTES + Integer.BYTES + Short.BYTES + Byte.BYTES;
    private static final int CELL_BYTES_V2 = 7 * Double.BYTES + Integer.BYTES + 3 * Short.BYTES + 3 * Byte.BYTES;

    @Override
    public void saveState(JsonObject out) {
        out.addProperty("format", STATE_FORMAT);
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

        JsonArray tubeArray = new JsonArray();
        for (LavaTube t : tubes) {
            JsonArray a = new JsonArray();
            a.add(t.x());
            a.add(t.z());
            a.add(t.bottomY());
            a.add(t.topY());
            tubeArray.add(a);
        }
        out.add("tubes", tubeArray);

        JsonArray chunkArray = new JsonArray();
        for (LavaChunk c : sortedChunks(chunks.values())) {
            if (!c.hasPersistentState()) continue;
            ByteBuffer buf = ByteBuffer.allocate(AREA * CELL_BYTES_V2);
            for (int i = 0; i < AREA; i++) {
                buf.putDouble(c.thickness[i]).putDouble(c.temperature[i]).putDouble(c.silica[i])
                        .putDouble(c.water[i]).putDouble(c.solid[i]).putDouble(c.crust[i]).putDouble(c.roofTop[i])
                        .putInt(c.renderBottom[i])
                        .putShort(c.renderMelt[i]).putShort(c.renderGap[i]).putShort(c.renderRoof[i])
                        .put(c.renderTop[i]).put(c.renderRoofKind[i]).put(c.crustKind[i]);
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
        tubes.clear();
        int format = in.has("format") ? in.get("format").getAsInt() : 1;
        if (format < 1 || format > STATE_FORMAT) {
            throw new IllegalArgumentException("Unsupported lava state format: " + format);
        }
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
        if (in.has("tubes")) {
            for (JsonElement e : in.getAsJsonArray("tubes")) {
                JsonArray a = e.getAsJsonArray();
                tubes.add(new LavaTube(a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt()));
            }
        }

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
                if (format >= 2) {
                    c.crust[i] = buf.getDouble();
                    c.roofTop[i] = buf.getDouble();
                    c.renderBottom[i] = buf.getInt();
                    c.renderMelt[i] = buf.getShort();
                    c.renderGap[i] = buf.getShort();
                    c.renderRoof[i] = buf.getShort();
                    c.renderTop[i] = buf.get();
                    c.renderRoofKind[i] = buf.get();
                    c.crustKind[i] = buf.get();
                } else {
                    // v1: a uniform lava column; no crust yet
                    c.renderBottom[i] = buf.getInt();
                    c.renderMelt[i] = buf.getShort();
                    c.renderTop[i] = buf.get();
                }
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
