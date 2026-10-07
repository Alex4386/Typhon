package me.alex4386.typhon.engine.massflow;

import me.alex4386.typhon.engine.config.ConfigCopy;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.ChunkCoord;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.FlowCell;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.Trigger;
import me.alex4386.typhon.engine.math.ColumnIndex;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.Outbox;
import me.alex4386.typhon.engine.sim.Parallel;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.GroundCoupling;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.LayerFlags;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.UnitSource;
import me.alex4386.typhon.engine.world.BlockState;
import me.alex4386.typhon.engine.save.FieldChunk;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;

/**
 * Depth-averaged gravity current over the {@link TerrainModel} columns, shared by
 * {@link PyroclasticFlows} and {@link Lahars}.
 *
 * <p>Each column carries flow depth {@code h}, a depth-averaged velocity {@code (vx, vz)} and a
 * tracer (temperature or sediment fraction). One sub-step:
 *
 * <ol>
 *   <li><b>Forcing</b> (per face, "virtual pipe" form of the shallow-water momentum equation): the
 *       face speed starts from the cell velocity component toward that face and is accelerated by
 *       the free-surface gradient {@code g Δη / Δx}, decelerated by Coulomb friction
 *       {@code μ g cosθ} and by turbulent drag {@code g u² / (ξ h)} (implicit, so it is unconditionally
 *       stable). This is Voellmy–Salm rheology: the flow starts only where the surface slope exceeds
 *       μ and comes to rest on gentler ground.
 *   <li><b>Run-up limit</b>: a face is closed when the neighbour's bed rises above the cell's energy
 *       line {@code η + u²/(2g)}, so a flow overtops an obstacle only with enough kinetic energy.
 *   <li><b>Transport</b>: face volume {@code u · h · Δx · dt}, scaled so a cell never sends more
 *       than it holds; each cell then gathers inflows (order-independent, mass-conserving) and
 *       mixes momentum and tracers by volume, so flows carry their inertia downstream.
 *   <li><b>Processes</b> (kind-specific): cooling, sedimentation, erosion, losses to water. Material
 *       thinner than {@link MassFlowConfig#minDepth} comes to rest. Deposit is kept per column and
 *       every whole block raises the terrain (compare-and-set), with veneer blocks for thin cover.
 * </ol>
 *
 * <p>Sub-steps follow a CFL limit {@code dt ≤ cfl · Δx / max(|u| + √(g h))}. Columns in chunks the
 * host has not sent act as walls and are requested via {@link MassFlowEvents.TerrainNeeded}.
 *
 * <p>Budget (real m³ of flow): {@code released + entrained = flowing + deposited + lost}.
 */
public abstract class MassFlowField implements Subsystem {
    static final int[] DX = {-1, 1, 0, 0};
    static final int[] DZ = {0, 0, -1, 1};
    static final int AREA = MassFlowChunk.AREA;
    static final int UNKNOWN = MassFlowChunk.UNKNOWN;
    private static final double EPS = 1e-12;

    /** Mass accounting in real m³ of flowing material. */
    public record MassBudget(double released, double entrained, double flowing, double deposited, double lost) {
        /** {@code released + entrained − flowing − deposited − lost}; ≈ 0 up to rounding. */
        public double imbalance() {
            return released + entrained - flowing - deposited - lost;
        }
    }

    /** A flow start waiting to be announced at the next step. */
    record PendingStart(Trigger trigger, BlockPos position, double volumeM3, double rateM3PerS, double temperatureC) {}

    protected final String id;
    protected final MassFlowKind kind;
    protected final TerrainModel terrain;
    protected final MassFlowConfig config;
    protected final double dx;
    protected final double cellArea;

    private final TreeMap<Long, MassFlowChunk> chunks = new TreeMap<>();
    private final Map<String, FlowSource> sources = new LinkedHashMap<>();
    private final List<BlockPos> origins = new ArrayList<>();
    private final TreeSet<Long> requestedTerrain = new TreeSet<>();
    private final List<PendingStart> pendingStarts = new ArrayList<>();
    protected double released;
    protected double entrained;
    protected double deposited;
    protected double lost;
    protected UnitSource units;
    protected GroundCoupling ground = GroundCoupling.NONE; // transient
    private double currentTime;

    // per-step / per-substep scratch
    private final TreeSet<Long> neededTerrain = new TreeSet<>();
    private long stamp = Long.MIN_VALUE + 1;
    private long epoch = Long.MIN_VALUE + 1;
    private Parallel parallel = Parallel.sequential();
    private int lastSubsteps;
    protected final StepStats stats = new StepStats();

    protected MassFlowField(String id, MassFlowKind kind, TerrainModel terrain, MassFlowConfig config) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.terrain = Objects.requireNonNull(terrain, "terrain");
        config.validate();
        this.config = config.copy();
        this.dx = config.metersPerBlock;
        this.cellArea = dx * dx;
        this.units = UnitSource.typed(terrain.world());
    }

    /** Live retune; the cell size ({@code metersPerBlock}) is refused. */
    @Override
    public boolean reconfigure(Object c) {
        if (!(c instanceof MassFlowConfig n) || !ConfigCopy.same(n, config, "metersPerBlock")) return false;
        n.validate();
        ConfigCopy.into(n, config);
        return true;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public double periodSeconds() {
        return config.stepPeriodSeconds;
    }

    /** While a flow moves, one field step per engine step (the field sub-steps by CFL within it). */
    @Override
    public double maxStepSeconds() {
        return activeCellCount() > 0 ? config.stepPeriodSeconds : Double.POSITIVE_INFINITY;
    }

    @Override
    public void registerCommands(CommandBus bus) {
        bus.register(MassFlowCommands.ReleaseFlow.class, c -> {
            if (c.target().equals(id)) release(c.center().column(dx), c.radius(), c.volumeM3(), c.temperatureC(), c.sedimentFraction(), c.trigger());
        });
        bus.register(MassFlowCommands.StartFlowSource.class, c -> {
            if (c.target().equals(id)) addSource(c.source(), c.trigger());
        });
        bus.register(MassFlowCommands.StopFlowSource.class, c -> {
            if (c.target().equals(id)) removeSource(c.sourceId());
        });
        bus.register(MassFlowCommands.SetFlowSourceRate.class, c -> {
            if (c.target().equals(id)) setRate(c.sourceId(), c.rateM3PerS());
        });
    }

    // ── Public API ──

    public MassFlowKind kind() {
        return kind;
    }

    @Override
    public MassFlowConfig config() {
        return config.copy();
    }

    /**
     * Attributes deposits to the producing volcano's current eruption. Deposits go into the world
     * model as layers of that unit; loose ones (non-welded ignimbrite, lahar deposits) are what rain
     * and later lahars entrain.
     */
    public void setUnits(UnitSource units) {
        this.units = units;
    }

    /**
     * The ground model hot deposits hand their heat to (transient: re-attach when the engine is
     * built). Default {@link GroundCoupling#NONE}.
     */
    public void setGround(GroundCoupling ground) {
        this.ground = ground == null ? GroundCoupling.NONE : ground;
    }

    /**
     * Releases {@code volumeM3} of flow at rest, spread evenly over the known columns within
     * {@code radius} columns of {@code center}. Returns false (and requests terrain) if none is known.
     */
    public boolean release(ColumnIndex center, int radius, double volumeM3, double temperatureC, double sedimentFraction,
            Trigger trigger) {
        if (!(volumeM3 > 0)) return false;
        List<int[]> cells = new ArrayList<>();
        int r = Math.max(0, radius);
        for (int dz = -r; dz <= r; dz++) {
            for (int ddx = -r; ddx <= r; ddx++) {
                if (ddx * ddx + dz * dz > r * r) continue;
                int x = center.x() + ddx;
                int z = center.z() + dz;
                if (knownColumn(x, z)) {
                    cells.add(new int[] {x, z});
                } else {
                    neededTerrain.add(MassFlowChunk.key(x >> 4, z >> 4));
                }
            }
        }
        if (cells.isEmpty()) return false;
        double per = volumeM3 / cells.size();
        for (int[] cell : cells) inject(cell[0], cell[1], per, temperatureC, sedimentFraction);
        released += volumeM3;
        BlockPos at = ground(center);
        addOrigin(at);
        pendingStarts.add(new PendingStart(trigger, at, volumeM3, 0, temperatureC));
        return true;
    }

    /**
     * Releases flow at rest on exactly the listed columns (each {@code {x, z, volumeM3}}), e.g. the
     * cells of a failed slope. Columns the field does not know are skipped (and requested). Returns the
     * volume injected.
     */
    public double releaseCells(BlockPos origin, List<double[]> cells, double temperatureC, double sedimentFraction,
            Trigger trigger) {
        double total = 0;
        for (double[] cell : cells) {
            int x = (int) cell[0];
            int z = (int) cell[1];
            if (!(cell[2] > 0)) continue;
            if (!knownColumn(x, z)) {
                neededTerrain.add(MassFlowChunk.key(x >> 4, z >> 4));
                continue;
            }
            inject(x, z, cell[2], temperatureC, sedimentFraction);
            total += cell[2];
        }
        if (total > 0) {
            released += total;
            addOrigin(origin);
            pendingStarts.add(new PendingStart(trigger, origin, total, 0, temperatureC));
        }
        return total;
    }

    public void addSource(FlowSource source, Trigger trigger) {
        boolean fresh = !sources.containsKey(source.id());
        sources.put(source.id(), source);
        for (ColumnIndex cell : source.cells()) addOrigin(ground(cell));
        if (fresh) {
            pendingStarts.add(new PendingStart(trigger, ground(source.cells().get(0)), 0, source.rateM3PerS(), source.temperatureC()));
        }
    }

    public void removeSource(String sourceId) {
        sources.remove(sourceId);
    }

    public void setRate(String sourceId, double rateM3PerS) {
        FlowSource source = sources.get(sourceId);
        if (source != null) sources.put(sourceId, source.withRate(rateM3PerS));
    }

    public Collection<FlowSource> sources() {
        return Collections.unmodifiableCollection(sources.values());
    }

    /** Flow depth (real m) at a column. */
    public double depth(int x, int z) {
        MassFlowChunk c = chunks.get(MassFlowChunk.key(x >> 4, z >> 4));
        return c == null ? 0 : c.depth[index(x, z)];
    }

    public double speed(int x, int z) {
        MassFlowChunk c = chunks.get(MassFlowChunk.key(x >> 4, z >> 4));
        if (c == null) return 0;
        int i = index(x, z);
        return StrictMath.hypot(c.vx[i], c.vz[i]);
    }

    public double temperatureC(int x, int z) {
        MassFlowChunk c = chunks.get(MassFlowChunk.key(x >> 4, z >> 4));
        return c == null ? 0 : c.temperature[index(x, z)];
    }

    public double sedimentFraction(int x, int z) {
        MassFlowChunk c = chunks.get(MassFlowChunk.key(x >> 4, z >> 4));
        return c == null ? 0 : c.sediment[index(x, z)];
    }

    /** Total deposit laid down in the column so far, including whole blocks (real m). */
    public double depositThickness(int x, int z) {
        MassFlowChunk c = chunks.get(MassFlowChunk.key(x >> 4, z >> 4));
        return c == null ? 0 : c.depositTotal[index(x, z)];
    }

    public double flowingVolume() {
        double sum = 0;
        for (MassFlowChunk c : chunks.values()) {
            if (c.flowCells == 0) continue;
            for (int i = 0; i < AREA; i++) sum += c.depth[i];
        }
        return sum * cellArea;
    }

    /** World expansion activity: chunks with a moving flow, and chunks a flow is waiting for. */
    public void reportActivity(me.alex4386.typhon.engine.expansion.ExpansionActivity.Sink sink) {
        for (MassFlowChunk c : chunks.values()) {
            if (c.flowCells > 0) sink.active((c.cx << 4) + 8, (c.cz << 4) + 8);
        }
        for (long key : requestedTerrain) sink.active(((int) (key >> 32) << 4) + 8, ((int) key << 4) + 8);
    }

    public int activeCellCount() {
        int n = 0;
        for (MassFlowChunk c : chunks.values()) n += c.flowCells;
        return n;
    }

    /** CFL sub-steps used by the most recent step (telemetry). */
    public int lastSubsteps() {
        return lastSubsteps;
    }

    public MassBudget massBudget() {
        return new MassBudget(released, entrained, flowingVolume(), deposited, lost);
    }

    // ── Kind-specific hooks ──

    /** Coulomb friction coefficient for the column (may depend on the tracer). */
    protected abstract double friction(MassFlowChunk c, int i);

    /** Cooling, sedimentation, erosion, losses for one column after transport. */
    protected abstract void process(MassFlowChunk c, int i, double dt, Outbox outbox, double time);

    /** Block laid down for one whole block of deposit, from the partial deposit's averages. */
    protected abstract BlockState depositBlock(MassFlowChunk c, int i, double meanTemperatureC, double meanSpeed);

    /** Stratigraphic deposit type of this kind's deposits. */
    protected abstract DepositType depositType();

    /** World-model material laid down at a deposition temperature and flow speed. */
    protected abstract Material depositMaterial(double temperatureC, double speed);

    /** Layer flags of the deposit (e.g. {@link LayerFlags#LOOSE} for non-welded material). */
    protected abstract int depositFlags(double temperatureC);

    /** Welding (0 loose – 1 fully welded) of the deposit. */
    protected abstract double depositWelding(double temperatureC);

    /** Veneer for thin deposit (tier 1 thin, 2 thick). */
    protected abstract BlockState veneer(int tier);

    protected abstract EngineEvent startedEvent(double time, PendingStart start);

    protected abstract EngineEvent frontEvent(double time, Point3 front, double runoutM, int cells, double volume,
            double maxSpeed, double tracer, List<FlowCell> reported);

    protected abstract EngineEvent depositEvent(double time, int cells, double volume, int blocks);

    /** Called once per step before transport (e.g. rain mobilising loose deposit). */
    protected void beforeTransport(double dt, double time, Outbox outbox) {}

    protected void saveExtra(JsonObject out) {}

    protected void loadExtra(JsonObject in) {}

    // ── Step ──

    @Override
    public void step(StepContext context) {
        parallel = context.parallel();
        double time = context.time();
        currentTime = time;
        stamp = context.step();
        double dt = context.dtSeconds();
        Outbox outbox = context.outbox();
        stats.reset();

        for (FlowSource source : sources.values()) {
            if (source.rateM3PerS() <= 0) continue;
            double per = source.rateM3PerS() * dt / source.cells().size();
            for (ColumnIndex cell : source.cells()) {
                if (knownColumn(cell.x(), cell.z())) {
                    inject(cell.x(), cell.z(), per, source.temperatureC(), source.sedimentFraction());
                    released += per;
                } else {
                    neededTerrain.add(MassFlowChunk.key(cell.x() >> 4, cell.z() >> 4));
                }
            }
        }
        beforeTransport(dt, time, outbox);

        for (PendingStart start : pendingStarts) outbox.emit(startedEvent(time, start));
        pendingStarts.clear();

        int substeps = substeps(dt);
        lastSubsteps = substeps;
        double h = dt / substeps;
        for (int s = 0; s < substeps; s++) {
            if (!anyFlow()) break;
            transport(h);
            for (MassFlowChunk c : chunks.values()) {
                if (c.flowCells == 0) continue;
                for (int i = 0; i < AREA; i++) {
                    if (c.depth[i] > 0) process(c, i, h, outbox, time);
                }
                c.recount();
            }
        }

        renderVeneers(outbox);

        if (stats.cells() > 0) outbox.emit(depositEvent(time, stats.cells(), stats.volume, stats.blocks));
        if (context.crossed(config.frontEventPeriodSeconds)) emitFront(time, outbox);
        if (!neededTerrain.isEmpty()) {
            List<ChunkCoord> fresh = new ArrayList<>();
            for (long key : neededTerrain) {
                if (requestedTerrain.add(key)) fresh.add(new ChunkCoord((int) (key >> 32), (int) key));
            }
            if (!fresh.isEmpty()) outbox.emit(new MassFlowEvents.TerrainNeeded(time, id, fresh));
            neededTerrain.clear();
        }
        chunks.values().removeIf(c -> !c.hasPersistentState());
    }

    private boolean anyFlow() {
        for (MassFlowChunk c : chunks.values()) if (c.flowCells > 0) return true;
        return false;
    }

    private int substeps(double dt) {
        double g = config.gravity;
        double maxSignal = 0;
        for (MassFlowChunk c : chunks.values()) {
            if (c.flowCells == 0) continue;
            for (int i = 0; i < AREA; i++) {
                double d = c.depth[i];
                if (d <= 0) continue;
                double signal = StrictMath.hypot(c.vx[i], c.vz[i]) + Math.sqrt(g * d);
                if (signal > maxSignal) maxSignal = signal;
            }
        }
        if (maxSignal <= 0) return 1;
        double limit = config.cfl * dx / maxSignal;
        int n = (int) Math.ceil(dt / limit);
        return Math.max(1, Math.min(config.maxSubsteps, n));
    }

    // ── Transport ──

    /**
     * One transport sub-step. Neighbour chunks are resolved (and created) sequentially; faces and the
     * gather then run per chunk in parallel, each writing only its own chunk; touched flags and the
     * rounding residue are folded back in chunk-key order.
     */
    private void transport(double dt) {
        epoch++;
        List<MassFlowChunk> active = new ArrayList<>();
        for (MassFlowChunk c : chunks.values()) if (c.flowCells > 0) active.add(c);
        for (MassFlowChunk c : active) {
            ensureFresh(c);
            prepareFaces(c);
        }
        parallel.forEach(active, 2, c -> computeFaces(c, dt));
        for (MassFlowChunk c : active) {
            for (int d = 0; d < 4; d++) if ((c.touchOut & (1 << d)) != 0) c.neighbours[d].touchedStamp = epoch;
        }
        List<MassFlowChunk> update = new ArrayList<>();
        for (MassFlowChunk c : chunks.values()) {
            if (c.flowCells > 0 || c.touchedStamp == epoch) update.add(c);
        }
        for (MassFlowChunk c : update) {
            ensureFresh(c);
            for (int d = 0; d < 4; d++) neighbour(c, d, false);
        }
        parallel.forEach(update, 2, this::gather); // reads neighbours' current buffers: swap only afterwards
        parallel.forEach(update, 4, c -> {
            c.swapBuffers();
            c.recount();
        });
        for (MassFlowChunk c : update) {
            lost += c.lostResidue;
            c.lostResidue = 0;
        }
    }

    /** Sequential: resolves/creates the neighbour chunks an edge cell of {@code c} may flow into. */
    private void prepareFaces(MassFlowChunk c) {
        c.touchOut = 0;
        int needed = 0;
        for (int i = 0; i < AREA; i++) {
            int lx = i & 15;
            int lz = i >> 4;
            if (lx != 0 && lx != 15 && lz != 0 && lz != 15) continue;
            if (c.depth[i] <= 0 || c.ground[i] == UNKNOWN) continue;
            for (int d = 0; d < 4; d++) {
                int nx = lx + DX[d];
                int nz = lz + DZ[d];
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15) needed |= 1 << d;
            }
        }
        for (int d = 0; d < 4; d++) {
            if ((needed & (1 << d)) != 0) neighbour(c, d, true);
        }
    }

    /** Parallel-safe neighbour lookup: only what a prepare pass resolved this step. */
    private MassFlowChunk peekNeighbour(MassFlowChunk c, int d) {
        if (c.neighbourStamp != stamp) throw new IllegalStateException("neighbours of chunk not prepared this step");
        return c.neighbours[d];
    }

    private void computeFaces(MassFlowChunk c, double dt) {
        double[] faceSpeed = new double[4];
        double[] faceVolume = new double[4];
        c.fluxStamp = epoch;
        Arrays.fill(c.outVolume, 0);
        Arrays.fill(c.outSpeed, 0);
        double g = config.gravity;
        double xi = config.turbulenceCoefficient;
        double maxSpeed = config.maxSpeed;

        for (int i = 0; i < AREA; i++) {
            double h = c.depth[i];
            if (h <= 0 || c.ground[i] == UNKNOWN) continue;
            double bed = bed(c, i);
            double eta = bed + h;
            double vx = c.vx[i];
            double vz = c.vz[i];
            double mu = friction(c, i);
            int lx = i & 15;
            int lz = i >> 4;

            double total = 0;
            for (int d = 0; d < 4; d++) {
                faceSpeed[d] = 0;
                faceVolume[d] = 0;
                int nx = lx + DX[d];
                int nz = lz + DZ[d];
                MassFlowChunk nc = c;
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                    nc = peekNeighbour(c, d);
                    if (nc == null) continue;
                }
                int j = ((nz & 15) << 4) | (nx & 15);
                if (nc.ground[j] == UNKNOWN) continue;
                double bedJ = bed(nc, j);
                double u = switch (d) {
                    case 0 -> Math.max(-vx, 0);
                    case 1 -> Math.max(vx, 0);
                    case 2 -> Math.max(-vz, 0);
                    default -> Math.max(vz, 0);
                };
                if (bedJ > eta + u * u / (2 * g)) continue; // cannot reach the neighbour's bed
                double etaJ = bedJ + nc.depth[j];
                double bedSlope = (bed - bedJ) / dx;
                double cos = 1 / Math.sqrt(1 + bedSlope * bedSlope);
                u += dt * g * (eta - etaJ) / dx;
                u -= dt * mu * g * cos;
                if (u <= 0) continue;
                u = u / (1 + dt * g * u / (xi * Math.max(h, config.minDepth)));
                if (u > maxSpeed) u = maxSpeed;
                double volume = u * h * dx * dt;
                faceSpeed[d] = u;
                faceVolume[d] = volume;
                total += volume;
            }
            if (total <= 0) continue;
            double available = h * cellArea;
            double scale = total > available ? available / total : 1;
            for (int d = 0; d < 4; d++) {
                if (faceVolume[d] <= 0) continue;
                c.outVolume[d * AREA + i] = faceVolume[d] * scale;
                c.outSpeed[d * AREA + i] = faceSpeed[d] * scale;
                int nx = lx + DX[d];
                int nz = lz + DZ[d];
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15) c.touchOut |= 1 << d;
            }
        }
    }

    private void gather(MassFlowChunk c) {
        boolean own = c.fluxStamp == epoch;
        for (int i = 0; i < AREA; i++) {
            double h = c.depth[i];
            double volume = 0;
            double px = 0;
            double pz = 0;
            double heat = 0;
            double sed = 0;
            if (h > 0) {
                double out = 0;
                double sx = 0;
                double sz = 0;
                if (own) {
                    out = c.outVolume[i] + c.outVolume[AREA + i] + c.outVolume[2 * AREA + i] + c.outVolume[3 * AREA + i];
                    sx = c.outSpeed[AREA + i] - c.outSpeed[i];
                    sz = c.outSpeed[3 * AREA + i] - c.outSpeed[2 * AREA + i];
                }
                double keep = h * cellArea - out;
                if (keep < EPS) keep = 0;
                volume = keep;
                px = keep * sx;
                pz = keep * sz;
                heat = keep * c.temperature[i];
                sed = keep * c.sediment[i];
            }

            int lx = i & 15;
            int lz = i >> 4;
            for (int d = 0; d < 4; d++) {
                int nx = lx + DX[d];
                int nz = lz + DZ[d];
                MassFlowChunk nc = c;
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                    nc = peekNeighbour(c, d);
                    if (nc == null) continue;
                }
                if (nc.fluxStamp != epoch) continue;
                int k = ((nz & 15) << 4) | (nx & 15);
                int back = d ^ 1; // the neighbour's face pointing at us
                double in = nc.outVolume[back * AREA + k];
                if (in <= 0) continue;
                double s = nc.outSpeed[back * AREA + k];
                volume += in;
                px += in * s * DX[back];
                pz += in * s * DZ[back];
                heat += in * nc.temperature[k];
                sed += in * nc.sediment[k];
            }

            if (volume > EPS) {
                c.nextDepth[i] = volume / cellArea;
                c.nextVx[i] = px / volume;
                c.nextVz[i] = pz / volume;
                c.nextTemperature[i] = heat / volume;
                c.nextSediment[i] = sed / volume;
            } else {
                if (volume > 0) c.lostResidue += volume; // rounding residue (folded in chunk order)
                c.nextDepth[i] = 0;
                c.nextVx[i] = 0;
                c.nextVz[i] = 0;
                c.nextTemperature[i] = 0;
                c.nextSediment[i] = 0;
            }
        }
    }

    // ── Shared helpers for kinds ──

    /** Bed elevation (real m): top of the ground block plus partial deposit. */
    protected final double bed(MassFlowChunk c, int i) {
        return (c.ground[i] + 1) * dx + c.deposit[i];
    }

    protected static boolean submerged(MassFlowChunk c, int i) {
        int w = c.waterY[i];
        return w != TerrainColumn.NO_WATER && w > c.ground[i];
    }

    protected static double speed(MassFlowChunk c, int i) {
        return StrictMath.hypot(c.vx[i], c.vz[i]);
    }

    /** Adds flow at rest to a known column, mixing tracers by volume (momentum is conserved). */
    protected final void inject(int x, int z, double volumeM3, double temperatureC, double sedimentFraction) {
        if (volumeM3 <= 0) return;
        MassFlowChunk c = chunkFor(x >> 4, z >> 4);
        if (c == null) return;
        ensureFresh(c);
        int i = index(x, z);
        double v0 = c.depth[i] * cellArea;
        double v = v0 + volumeM3;
        c.temperature[i] = (c.temperature[i] * v0 + temperatureC * volumeM3) / v;
        c.sediment[i] = (c.sediment[i] * v0 + sedimentFraction * volumeM3) / v;
        c.vx[i] *= v0 / v;
        c.vz[i] *= v0 / v;
        if (c.depth[i] <= 0) c.flowCells++;
        c.depth[i] = v / cellArea;
    }

    /** Adds entrained bed material to a flowing column (counted as entrained, not released). */
    protected final void entrain(MassFlowChunk c, int i, double thicknessM, double sedimentFraction) {
        if (thicknessM <= 0) return;
        double v0 = c.depth[i] * cellArea;
        double add = thicknessM * cellArea;
        double v = v0 + add;
        c.sediment[i] = (c.sediment[i] * v0 + sedimentFraction * add) / v;
        c.temperature[i] = (c.temperature[i] * v0 + config.ambientC * add) / v;
        c.vx[i] *= v0 / v;
        c.vz[i] *= v0 / v;
        if (c.depth[i] <= 0) c.flowCells++;
        c.depth[i] = v / cellArea;
        entrained += add;
    }

    /** Removes flow lost to the environment (steam, infiltration). */
    protected final void loseFlow(MassFlowChunk c, int i, double thicknessM) {
        double removed = Math.min(thicknessM, c.depth[i]);
        if (removed <= 0) return;
        c.depth[i] -= removed;
        lost += removed * cellArea;
        if (c.depth[i] <= EPS) clearCell(c, i);
    }

    /**
     * Moves {@code flowThicknessM} of flow out of the column into deposit of {@code depositThicknessM}
     * (real m), laying whole blocks as they accumulate.
     */
    protected final void depositFlow(MassFlowChunk c, int i, double flowThicknessM, double depositThicknessM,
            Outbox outbox) {
        double removed = Math.min(flowThicknessM, c.depth[i]);
        if (removed <= 0) return;
        double temperature = c.temperature[i];
        double speed = speed(c, i);
        c.depth[i] -= removed;
        deposited += removed * cellArea;
        if (c.depth[i] <= EPS) {
            if (c.depth[i] > 0) deposited += c.depth[i] * cellArea;
            clearCell(c, i);
        }
        stats.volume += removed * cellArea;
        stats.columns.add(BlockPos.pack(c.worldX(i), 0, c.worldZ(i)));
        c.depositStamp = stamp;

        if (depositThicknessM <= 0) return;
        c.deposit[i] += depositThicknessM;
        c.depositHeat[i] += depositThicknessM * temperature;
        c.depositSpeed[i] += depositThicknessM * speed;
        c.depositTotal[i] += depositThicknessM;
        // The stacks store elevations as floats (≈8 µm resolution near 100 m): sub-millimetre
        // increments are pooled per column and written once they reach WORLD_COMMIT_M.
        c.worldPending[i] += depositThicknessM;
        c.worldPendingHeat[i] += depositThicknessM * temperature;
        if (c.worldPending[i] >= WORLD_COMMIT_M) commitToWorld(c, i);
        while (c.deposit[i] >= dx - 1e-9) placeBlock(c, i, outbox);
    }

    /** Smallest deposit increment written to the world model at once (m). */
    static final double WORLD_COMMIT_M = 1e-3;

    /** Writes a column's pooled deposit into the world model as a layer of the current unit. */
    private void commitToWorld(MassFlowChunk c, int i) {
        double thickness = c.worldPending[i];
        if (!(thickness > 0)) return;
        double temperature = c.worldPendingHeat[i] / thickness;
        Material material = depositMaterial(temperature, 0);
        int unit = units.unit(depositType(), currentTime, temperature);
        terrain.world().deposit(c.worldX(i), c.worldZ(i), thickness, material, unit, depositFlags(temperature),
                material.porosity(), depositWelding(temperature));
        // The deposit's sensible heat above ambient becomes ground heat (it is now part of the ground).
        double excess = temperature - config.ambientC;
        if (excess > 0) {
            double bulkDensity = material.densityKgM3() * (1 - material.porosity());
            ground.addGroundHeat(c.worldX(i), c.worldZ(i),
                    bulkDensity * material.heatCapacityJkgK() * excess * thickness * cellArea);
        }
        c.worldPending[i] = 0;
        c.worldPendingHeat[i] = 0;
    }

    /** Deposit laid down but not yet written to the world model (m), e.g. for exact accounting. */
    public double pendingWorldDeposit(int x, int z) {
        MassFlowChunk c = chunks.get(MassFlowChunk.key(x >> 4, z >> 4));
        return c == null ? 0 : c.worldPending[index(x, z)];
    }

    private void placeBlock(MassFlowChunk c, int i, Outbox outbox) {
        double partial = c.deposit[i];
        double meanT = c.depositHeat[i] / partial;
        double meanU = c.depositSpeed[i] / partial;
        double remainFraction = Math.max(0, (partial - dx) / partial);
        c.deposit[i] = Math.max(0, partial - dx);
        c.depositHeat[i] *= remainFraction;
        c.depositSpeed[i] *= remainFraction;

        int x = c.worldX(i);
        int z = c.worldZ(i);
        int y = c.ground[i] + 1;
        BlockState block = depositBlock(c, i, meanT, meanU);
        terrain.updateBlockCache(x, z, y, block.id()); // the world model already holds the deposit
        c.ground[i] = y;
        c.veneer[i] = 0;
        stats.blocks++;
    }

    private void renderVeneers(Outbox outbox) {
        for (MassFlowChunk c : chunks.values()) {
            if (c.depositStamp != stamp) continue;
            for (int i = 0; i < AREA; i++) {
                if (c.ground[i] == UNKNOWN) continue;
                double blocks = c.deposit[i] / dx;
                int tier = blocks >= config.veneerBlocks ? 2 : blocks >= config.thinVeneerBlocks ? 1 : 0;
                if (tier <= c.veneer[i]) continue;
                int x = c.worldX(i);
                int z = c.worldZ(i);
                TerrainColumn column = terrain.column(x, z);
                if (column == null) continue;
                BlockState state = veneer(tier);
                if (!state.id().equals(column.surface())) {
                    terrain.updateBlockCache(x, z, c.ground[i], state.id());
                }
                c.veneer[i] = (byte) tier;
            }
        }
    }

    protected static void clearCell(MassFlowChunk c, int i) {
        c.depth[i] = 0;
        c.vx[i] = 0;
        c.vz[i] = 0;
        c.temperature[i] = 0;
        c.sediment[i] = 0;
    }

    // ── Telemetry ──

    private void emitFront(double time, Outbox outbox) {
        if (!anyFlow()) return;
        record Candidate(FlowCell cell, double distance, long key) {}
        List<Candidate> candidates = new ArrayList<>();
        int cells = 0;
        double volume = 0;
        double maxSpeed = 0;
        double tracerMax = 0;
        double sedimentSum = 0;
        for (MassFlowChunk c : chunks.values()) {
            if (c.flowCells == 0) continue;
            for (int i = 0; i < AREA; i++) {
                double h = c.depth[i];
                if (h <= 0) continue;
                cells++;
                volume += h * cellArea;
                double u = speed(c, i);
                maxSpeed = Math.max(maxSpeed, u);
                tracerMax = Math.max(tracerMax, c.temperature[i]);
                sedimentSum += c.sediment[i] * h * cellArea;
                int x = c.worldX(i);
                int z = c.worldZ(i);
                double nearest = origins.isEmpty() ? 0 : Double.MAX_VALUE;
                for (BlockPos o : origins) {
                    double ddx = x - o.x();
                    double ddz = z - o.z();
                    nearest = Math.min(nearest, ddx * ddx + ddz * ddz);
                }
                FlowCell cell = new FlowCell(Point3.ofBlock(new BlockPos(x, c.ground[i], z), dx), h, u,
                        kind == MassFlowKind.PDC ? c.temperature[i] : config.ambientC, c.sediment[i]);
                candidates.add(new Candidate(cell, nearest, BlockPos.pack(x, 0, z)));
            }
        }
        candidates.sort(Comparator.comparingDouble((Candidate k) -> -k.distance).thenComparingLong(Candidate::key));
        List<FlowCell> reported = new ArrayList<>();
        for (int n = 0; n < Math.min(config.maxReportedCells, candidates.size()); n++) reported.add(candidates.get(n).cell);
        Candidate front = candidates.get(0);
        double tracer = kind == MassFlowKind.PDC ? tracerMax : (volume > 0 ? sedimentSum / volume : 0);
        outbox.emit(frontEvent(time, front.cell.pos(), Math.sqrt(front.distance) * dx, cells, volume, maxSpeed, tracer,
                reported));
    }

    /** The ground block of a column as the terrain shows it (y 0 where unknown). */
    private BlockPos ground(ColumnIndex c) {
        return new BlockPos(c.x(), terrain.groundY(c.x(), c.z(), 0), c.z());
    }

    private void addOrigin(BlockPos p) {
        BlockPos origin = new BlockPos(p.x(), 0, p.z());
        if (!origins.contains(origin)) origins.add(origin);
    }

    // ── Chunk management ──

    /** Chunks in key order (deterministic iteration) for kinds that scan the field. */
    protected final Collection<MassFlowChunk> chunks() {
        return chunks.values();
    }

    protected final MassFlowChunk chunkAt(int x, int z) {
        return chunks.get(MassFlowChunk.key(x >> 4, z >> 4));
    }

    /** Chunk holding the column, created on demand when its terrain is known; null otherwise. */
    protected final MassFlowChunk chunkFor(int cx, int cz) {
        long key = MassFlowChunk.key(cx, cz);
        MassFlowChunk c = chunks.get(key);
        if (c != null) return c;
        if (!terrain.isKnown(cx << 4, cz << 4)) return null;
        requestedTerrain.remove(key);
        c = new MassFlowChunk(cx, cz);
        chunks.put(key, c);
        refresh(c);
        return c;
    }

    protected final boolean knownColumn(int x, int z) {
        return terrain.isKnown(x, z);
    }

    /** Neighbouring chunk in direction {@code d}; with {@code create}, makes it (or requests terrain). */
    protected final MassFlowChunk neighbour(MassFlowChunk c, int d, boolean create) {
        if (c.neighbourStamp != stamp) {
            Arrays.fill(c.neighbours, null);
            c.neighbourStamp = stamp;
        }
        MassFlowChunk n = c.neighbours[d];
        if (n != null) return n;
        int ncx = c.cx + DX[d];
        int ncz = c.cz + DZ[d];
        if (create) {
            n = chunkFor(ncx, ncz);
            if (n == null) {
                neededTerrain.add(MassFlowChunk.key(ncx, ncz));
                return null;
            }
        } else {
            n = chunks.get(MassFlowChunk.key(ncx, ncz));
            if (n == null) return null;
        }
        ensureFresh(n);
        c.neighbours[d] = n;
        return n;
    }

    protected final void ensureFresh(MassFlowChunk c) {
        if (c.freshStamp != stamp) refresh(c);
    }

    private void refresh(MassFlowChunk c) {
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

    protected static int index(int x, int z) {
        return ((z & 15) << 4) | (x & 15);
    }

    /** Per-step deposit tallies. */
    protected static final class StepStats {
        final java.util.HashSet<Long> columns = new java.util.HashSet<>();
        int blocks;
        double volume;

        void reset() {
            columns.clear();
            blocks = 0;
            volume = 0;
        }

        int cells() {
            return columns.size();
        }
    }

    // ── Persistence ──

    /** Schema of the per-chunk {@code cells} field. */
    private static final int CELLS_SCHEMA = 2;

    @Override
    public void saveState(StateWriter writer) {
        JsonObject out = writer.json();
        out.addProperty("released", released);
        out.addProperty("entrained", entrained);
        out.addProperty("deposited", deposited);
        out.addProperty("lost", lost);

        JsonArray sourceArray = new JsonArray();
        for (FlowSource s : sources.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", s.id());
            o.add("cells", columns(s.cells()));
            o.addProperty("rate", s.rateM3PerS());
            o.addProperty("temperature", s.temperatureC());
            o.addProperty("sediment", s.sedimentFraction());
            sourceArray.add(o);
        }
        out.add("sources", sourceArray);
        out.add("origins", positions(origins));

        JsonArray pending = new JsonArray();
        for (PendingStart p : pendingStarts) {
            JsonObject o = new JsonObject();
            o.addProperty("trigger", p.trigger().name());
            o.add("position", positions(List.of(p.position())));
            o.addProperty("volume", p.volumeM3());
            o.addProperty("rate", p.rateM3PerS());
            o.addProperty("temperature", p.temperatureC());
            pending.add(o);
        }
        out.add("pendingStarts", pending);

        JsonArray requested = new JsonArray();
        for (long key : requestedTerrain) requested.add(key);
        out.add("requestedTerrain", requested);

        StateWriter.Field cells = writer.field("cells", CELLS_SCHEMA);
        for (MassFlowChunk c : chunks.values()) {
            if (!c.hasPersistentState()) continue;
            cells.put(c.cx, c.cz, new FieldChunk()
                    .doubles("depth", c.depth.clone())
                    .doubles("vx", c.vx.clone())
                    .doubles("vz", c.vz.clone())
                    .doubles("temperature", c.temperature.clone())
                    .doubles("sediment", c.sediment.clone())
                    .doubles("deposit", c.deposit.clone())
                    .doubles("depositHeat", c.depositHeat.clone())
                    .doubles("depositSpeed", c.depositSpeed.clone())
                    .doubles("depositTotal", c.depositTotal.clone())
                    .doubles("soak", c.soak.clone())
                    .doubles("worldPending", c.worldPending.clone())
                    .doubles("worldPendingHeat", c.worldPendingHeat.clone())
                    .bytes("veneer", c.veneer.clone()));
        }
        saveExtra(out);
    }

    @Override
    public void loadState(StateReader reader) {
        JsonObject in = reader.json();
        chunks.clear();
        sources.clear();
        origins.clear();
        pendingStarts.clear();
        requestedTerrain.clear();
        released = in.get("released").getAsDouble();
        entrained = in.get("entrained").getAsDouble();
        deposited = in.get("deposited").getAsDouble();
        lost = in.get("lost").getAsDouble();

        for (JsonElement e : in.getAsJsonArray("sources")) {
            JsonObject o = e.getAsJsonObject();
            FlowSource s = new FlowSource(o.get("id").getAsString(), readColumns(o.getAsJsonArray("cells")),
                    o.get("rate").getAsDouble(), o.get("temperature").getAsDouble(), o.get("sediment").getAsDouble());
            sources.put(s.id(), s);
        }
        origins.addAll(readPositions(in.getAsJsonArray("origins")));
        for (JsonElement e : in.getAsJsonArray("pendingStarts")) {
            JsonObject o = e.getAsJsonObject();
            pendingStarts.add(new PendingStart(Trigger.valueOf(o.get("trigger").getAsString()),
                    readPositions(o.getAsJsonArray("position")).get(0), o.get("volume").getAsDouble(),
                    o.get("rate").getAsDouble(), o.get("temperature").getAsDouble()));
        }
        for (JsonElement e : in.getAsJsonArray("requestedTerrain")) requestedTerrain.add(e.getAsLong());

        StateReader.Field cells = reader.field("cells");
        if (cells != null) {
            if (cells.schemaVersion() != CELLS_SCHEMA) {
                throw new IllegalArgumentException("Unsupported mass-flow cell schema: " + cells.schemaVersion());
            }
            for (StateReader.Entry entry : cells.chunks()) {
                FieldChunk f = entry.data();
                MassFlowChunk c = new MassFlowChunk(entry.chunkX(), entry.chunkZ());
                System.arraycopy(f.doubles("depth"), 0, c.depth, 0, AREA);
                System.arraycopy(f.doubles("vx"), 0, c.vx, 0, AREA);
                System.arraycopy(f.doubles("vz"), 0, c.vz, 0, AREA);
                System.arraycopy(f.doubles("temperature"), 0, c.temperature, 0, AREA);
                System.arraycopy(f.doubles("sediment"), 0, c.sediment, 0, AREA);
                System.arraycopy(f.doubles("deposit"), 0, c.deposit, 0, AREA);
                System.arraycopy(f.doubles("depositHeat"), 0, c.depositHeat, 0, AREA);
                System.arraycopy(f.doubles("depositSpeed"), 0, c.depositSpeed, 0, AREA);
                System.arraycopy(f.doubles("depositTotal"), 0, c.depositTotal, 0, AREA);
                System.arraycopy(f.doubles("soak"), 0, c.soak, 0, AREA);
                System.arraycopy(f.doubles("worldPending"), 0, c.worldPending, 0, AREA);
                System.arraycopy(f.doubles("worldPendingHeat"), 0, c.worldPendingHeat, 0, AREA);
                System.arraycopy(f.bytes("veneer"), 0, c.veneer, 0, AREA);
                c.recount();
                chunks.put(c.key, c);
            }
        }
        loadExtra(in);
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

    /** Grid columns as {@code [x, 0, z]} triples (the layout block positions used, so older saves read back). */
    private static JsonArray columns(List<ColumnIndex> list) {
        JsonArray array = new JsonArray();
        for (ColumnIndex c : list) {
            JsonArray a = new JsonArray();
            a.add(c.x());
            a.add(0);
            a.add(c.z());
            array.add(a);
        }
        return array;
    }

    private static List<ColumnIndex> readColumns(JsonArray array) {
        List<ColumnIndex> list = new ArrayList<>();
        for (JsonElement e : array) {
            JsonArray a = e.getAsJsonArray();
            list.add(new ColumnIndex(a.get(0).getAsInt(), a.get(2).getAsInt()));
        }
        return list;
    }
}
