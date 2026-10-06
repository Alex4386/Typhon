package me.alex4386.typhon.engine.geomorph;

import me.alex4386.typhon.engine.config.ConfigCopy;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import me.alex4386.typhon.engine.geomorph.GeomorphEvents.FailureStyle;
import me.alex4386.typhon.engine.geomorph.GeomorphEvents.Trigger;
import me.alex4386.typhon.engine.massflow.DebrisAvalanches;
import me.alex4386.typhon.engine.massflow.Lahars;
import me.alex4386.typhon.engine.massflow.MassFlowEvents;
import me.alex4386.typhon.engine.massflow.PyroclasticFlows;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.Outbox;
import me.alex4386.typhon.engine.save.FieldChunk;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.terrain.TerrainColumn;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.volcano.VentKind;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.BlockState;
import me.alex4386.typhon.engine.world.ColumnStacks;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.LayerFlags;
import me.alex4386.typhon.engine.world.LayerView;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialClass;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.UnitSource;
import me.alex4386.typhon.engine.world.WorldModel;

/**
 * Hillslope and crater geomorphology of one volcano: slope failure and mass wasting, hydrothermal
 * alteration, explosion craters, vent clearing and piston (caldera / pit) collapse. Everything acts
 * on the stratigraphic world model and is mirrored into blocks.
 *
 * <h2>Slope stability</h2>
 *
 * Every examined column is tested against its steepest downslope neighbour with two
 * limit-equilibrium models (see {@link SlopeStability}): the infinite-slope slab on every layer
 * boundary down to {@link GeomorphConfig#maxSlabDepthM} (and never deeper than the slope's relief),
 * and a Culmann planar wedge through the toe of the whole slope (relief measured by walking
 * downhill). Strength comes from the layers themselves ({@link RockStrength}: loose / fractured /
 * welded, hydrothermal alteration, temperature), weight from their density and pore water, pore
 * pressure from the water table and from rain-fed wetting fronts ({@link GroundState}), and seismic
 * shaking adds a pseudo-static load ({@link GroundMotion}). Nothing chooses an angle of repose: a dry
 * scoria slope stands at 34° because that is where {@code FS = tanφ/tanβ} reaches 1, a wet or altered
 * one at less.
 *
 * <h2>Mass wasting</h2>
 *
 * Failing columns of one relaxation pass are evaluated together (Jacobi) and grouped into connected
 * clusters. A small cluster settles beside its scar: each failed column sheds just enough of its slab
 * (at most the slab) to bring its slope back to the critical angle, onto its downslope neighbours,
 * as loose talus (a {@link DepositType#LANDSLIDE} unit; solid volume conserved). A cluster of at least
 * {@link GeomorphConfig#avalancheMinVolumeM3} is mobilised as a flow on the Voellmy solver: dry → a
 * debris avalanche, saturated → a lahar (debris flow), hot → a pyroclastic block-and-ash flow. Each
 * change re-queues the neighbourhood, so failures retrogress upslope and cascade downslope over the
 * next passes and steps.
 *
 * <h2>Alteration</h2>
 *
 * Each column carries an alteration intensity for its older body and for any cover laid on it since
 * (the cover starts fresh). It grows as {@code dA/dt = (1 − A) w / τ · exp(−E_a/R (1/T − 1/T_ref))}
 * where hot fluids are present ({@code w}: steam, or water above the sample depth), so long-lived
 * hydrothermal systems weaken their edifice (Reid 2004; Watters et al. 2000). Layers flagged
 * {@link LayerFlags#ALTERED} and clay count as altered regardless.
 *
 * <h2>Craters</h2>
 *
 * <ul>
 *   <li>Explosions (bursts, jets) excavate a paraboloid crater of the Sato &amp; Taniguchi (1997)
 *       size into the surface around the vent ({@link CraterScaling}) unless the existing crater
 *       already contains it; the excavated rock lands as an ejecta blanket thinning as
 *       {@code (r/R)^{-3}}.
 *   <li>Vent clearing: while the volcano erupts, the conduit is open, so loose material that falls or
 *       slides into it is swallowed (re-ejected or engulfed) rather than piling up; lava standing in
 *       the vent stays. Proximal fallout builds a
 *       rim, the inner walls fail back to their angle of repose into the conduit, and a cinder-cone
 *       crater forms by itself. After the eruption, wall collapse fills it in.
 *   <li>Piston collapse: a chamber in underpressure whose roof is coherent enough to subside (roof
 *       aspect ratio ≤ {@link GeomorphConfig#maxPistonAspectRatio}; Roche &amp; Druitt 2001, EPSL 191)
 *       drops when the underpressure exceeds the ring-fault resistance {@code 4 τ H / D}; the
 *       subsided volume recompresses the chamber.
 * </ul>
 */
public final class Geomorphology implements Subsystem {
    /** Default subsystem id for a volcano's geomorphology. */
    public static String defaultId(String volcanoId) {
        return "geomorph:" + volcanoId;
    }

    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};
    private static final double SQRT2 = Math.sqrt(2);
    private static final double G = SlopeStability.GRAVITY;
    private static final double RHO_W = SlopeStability.WATER_DENSITY;
    private static final double GAS_CONSTANT = 8.314;
    private static final double MIN_SLAB = 0.02;
    private static final double MIN_MOVE = 1e-3;
    private static final BlockId WATER = BlockId.minecraft("water");

    private final String id;
    private final String volcanoId;
    private final TerrainModel terrain;
    private final WorldModel world;
    private final GeomorphConfig config;

    // wiring (transient)
    private UnitSource units;
    private GroundState ground = GroundState.DRY;
    private DebrisAvalanches avalanches;
    private Lahars lahars;
    private PyroclasticFlows pdc;
    private ChamberRoof roof;
    private DoubleSupplier timeScale = () -> 1.0;
    private BooleanSupplier ventsOpen = () -> false;
    private List<VentSite> vents = List.of();

    // persistent state
    private final TreeSet<Long> active = new TreeSet<>();
    private final List<double[]> pendingExplosions = new ArrayList<>(); // {x, y, z, energyJ}
    private final List<double[]> pendingQuakes = new ArrayList<>(); // {x, y, z, magnitude}
    private final TreeMap<Long, AlterationTile> alteration = new TreeMap<>();
    /** Real-clock time each world tile was last swept (alteration integrates from there). */
    private final TreeMap<Long, Double> tileClock = new TreeMap<>();
    private long sweepCursor = Long.MIN_VALUE;
    /**
     * Elevation of each vent's conduit mouth (m), by vent id: it starts at the vent's ground and rises
     * with whatever fills the vent between eruptions (fall-back, slumped tephra, lava), so a growing
     * cone's vent rises with it. An open conduit only swallows what falls in above it.
     */
    private final TreeMap<String, Double> ventFloors = new TreeMap<>();
    private double realClock;
    private double calderaSubsidenceM;
    private final Stats stats = new Stats();

    // per-step scratch
    private final TreeMap<Long, Double> shaking = new TreeMap<>();
    private final TreeSet<Long> changed = new TreeSet<>();
    /**
     * Crater and caldera centres of the latest step, kept until the next step (world expansion). Slope
     * failures are not listed: talus settles next to its source, and mobilised ones become debris
     * avalanches, which report themselves.
     */
    private final TreeSet<Long> moved = new TreeSet<>();
    private double now;
    private double massWastingStep;
    private int massWastingColumns;

    /** Per-tile alteration state: body intensity, cover intensity, top of the altered body. */
    static final class AlterationTile {
        final double[] body = new double[ColumnStacks.TILE_AREA];
        final double[] cover = new double[ColumnStacks.TILE_AREA];
        final double[] top = new double[ColumnStacks.TILE_AREA];
        AlterationTile() {
            java.util.Arrays.fill(top, Double.NaN);
        }
    }

    static final class Stats {
        long failures;
        double failedM3;
        long avalanches;
        long craters;
        double excavatedM3;
        double recycledM3;
        double maxCraterRadiusM;
        long calderaSteps;

        void save(JsonObject o) {
            o.addProperty("failures", failures);
            o.addProperty("failedM3", failedM3);
            o.addProperty("avalanches", avalanches);
            o.addProperty("craters", craters);
            o.addProperty("excavatedM3", excavatedM3);
            o.addProperty("recycledM3", recycledM3);
            o.addProperty("maxCraterRadiusM", maxCraterRadiusM);
            o.addProperty("calderaSteps", calderaSteps);
        }

        void load(JsonObject o) {
            failures = o.get("failures").getAsLong();
            failedM3 = o.get("failedM3").getAsDouble();
            avalanches = o.get("avalanches").getAsLong();
            craters = o.get("craters").getAsLong();
            excavatedM3 = o.get("excavatedM3").getAsDouble();
            recycledM3 = o.get("recycledM3").getAsDouble();
            maxCraterRadiusM = o.get("maxCraterRadiusM").getAsDouble();
            calderaSteps = o.get("calderaSteps").getAsLong();
        }
    }

    public Geomorphology(String id, String volcanoId, TerrainModel terrain, GeomorphConfig config) {
        this.id = Objects.requireNonNull(id, "id");
        this.volcanoId = volcanoId;
        this.terrain = Objects.requireNonNull(terrain, "terrain");
        this.world = terrain.world();
        config.validate();
        this.config = config.copy();
        this.units = UnitSource.typed(world);
        // fresh deposits may overload or oversteepen a slope: re-examine where anything lands
        world.addDepositObserver((x, z, thickness, unit, flags) -> active.add(key(x, z)));
    }

    public Geomorphology(String id, String volcanoId, TerrainModel terrain) {
        this(id, volcanoId, terrain, new GeomorphConfig());
    }

    // ── Wiring ──

    public void setUnits(UnitSource units) {
        this.units = Objects.requireNonNull(units);
    }

    public void setGround(GroundState ground) {
        this.ground = ground == null ? GroundState.DRY : ground;
    }

    /** Where large failures go: dry, saturated and hot respectively (any may be null → talus). */
    public void setFlows(DebrisAvalanches avalanches, Lahars lahars, PyroclasticFlows pdc) {
        this.avalanches = avalanches;
        this.lahars = lahars;
        this.pdc = pdc;
    }

    public void setChamber(ChamberRoof roof) {
        this.roof = roof;
    }

    /** Real seconds per simulated second (the volcano's clock), for alteration. */
    public void setTimeScale(DoubleSupplier timeScale) {
        this.timeScale = Objects.requireNonNull(timeScale);
    }

    /** The vents, and whether their conduits are open (erupting). */
    public void setVents(List<VentSite> vents, BooleanSupplier open) {
        this.vents = List.copyOf(vents);
        this.ventsOpen = Objects.requireNonNull(open);
    }

    // ── Inputs ──

    /** An explosion of kinetic energy {@code energyJ} at a vent; excavated on the next step. */
    public void queueExplosion(BlockPos center, double energyJ) {
        if (energyJ > 0) pendingExplosions.add(new double[] {center.x(), center.y(), center.z(), energyJ});
    }

    /** An earthquake; its shaking loads slopes (pseudo-statically) on the next step. */
    public void queueQuake(BlockPos hypocenter, double magnitude) {
        pendingQuakes.add(new double[] {hypocenter.x(), hypocenter.y(), hypocenter.z(), magnitude});
    }

    /** Queues a column for a stability check. */
    public void activate(int x, int z) {
        active.add(key(x, z));
    }

    /** Queues every column of a rectangle (inclusive). */
    public void activateArea(int x0, int z0, int x1, int z1) {
        for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
            for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) active.add(key(x, z));
        }
    }

    /** Alteration intensity (0–1) of a column's older body. */
    public double alteration(int x, int z) {
        AlterationTile t = alteration.get(tileKey(x, z));
        return t == null ? 0 : t.body[ColumnStacks.localIndex(x, z)];
    }

    /** Sets the alteration of a column's current material (e.g. a mapped altered zone). */
    public void setAlteration(int x, int z, double intensity) {
        AlterationTile t = alteration.computeIfAbsent(tileKey(x, z), k -> new AlterationTile());
        int i = ColumnStacks.localIndex(x, z);
        t.body[i] = RockStrength.clamp01(intensity);
        t.cover[i] = 0;
        t.top[i] = world.isKnown(x, z) ? world.surfaceZ(x, z) : Double.NaN;
        active.add(key(x, z));
    }

    // ── Subsystem ──

    @Override
    public boolean reconfigure(Object c) {
        if (!(c instanceof GeomorphConfig n)) return false;
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

    @Override
    public Object config() {
        return config;
    }

    public GeomorphConfig configuration() {
        return config.copy();
    }

    @Override
    public void step(StepContext context) {
        now = context.time();
        double dtReal = context.dtSeconds() * timeScale.getAsDouble();
        realClock += dtReal;
        Outbox outbox = context.outbox();
        changed.clear();
        moved.clear();
        shaking.clear();
        massWastingStep = 0;
        massWastingColumns = 0;

        for (double[] e : pendingExplosions) excavate(e, outbox);
        pendingExplosions.clear();
        if (ventsOpen.getAsBoolean()) clearVents();
        else settleVentFloors();
        if (roof != null) pistonCollapse(outbox);
        shake(pendingQuakes);
        pendingQuakes.clear();
        sweep();
        flushBlocks(outbox);

        relax(outbox);
        if (massWastingStep > 0) {
            outbox.emit(new GeomorphEvents.MassWasting(now, volcanoId, massWastingStep, massWastingColumns));
        }
    }

    // ── Background sweep: alteration and slow drivers (rain, groundwater, heating) ──

    private void sweep() {
        if (config.sweepTilesPerStep <= 0) return;
        long[] keys = world.stacks().tileKeys();
        if (keys.length == 0) return;
        java.util.Arrays.sort(keys);
        int start = 0;
        while (start < keys.length && keys[start] <= sweepCursor) start++;
        int n = Math.min(config.sweepTilesPerStep, keys.length);
        for (int k = 0; k < n; k++) {
            long key = keys[(start + k) % keys.length];
            sweepCursor = key;
            int tx = ColumnStacks.keyTileX(key);
            int tz = ColumnStacks.keyTileZ(key);
            sweepTile(tx, tz);
        }
    }

    private void sweepTile(int tx, int tz) {
        int x0 = tx * ColumnStacks.TILE;
        int z0 = tz * ColumnStacks.TILE;
        long tk = key(tx, tz);
        Double last = tileClock.put(tk, realClock);
        double elapsed = last == null ? 0 : realClock - last;
        boolean alter = ground != GroundState.DRY && elapsed > 0;
        for (int lz = 0; lz < ColumnStacks.TILE; lz++) {
            for (int lx = 0; lx < ColumnStacks.TILE; lx++) {
                int x = x0 + lx;
                int z = z0 + lz;
                if (!world.isKnown(x, z)) continue;
                if (alter) alter(x, z, elapsed);
                if (steepestTan(x, z) >= config.minSlope) active.add(key(x, z));
            }
        }
    }

    /** Advances one column's alteration by {@code dt} real seconds. */
    private void alter(int x, int z, double dt) {
        AlterationTile t = alteration.get(tileKey(x, z));
        int i = ColumnStacks.localIndex(x, z);
        double s = world.surfaceZ(x, z);
        double top = t == null || Double.isNaN(t.top[i]) ? s : t.top[i];
        double body = t == null ? 0 : t.body[i];
        double cover = t == null ? 0 : t.cover[i];
        if (s < top) {
            top = s; // eroded into the body: the cover is gone
            cover = 0;
        }
        double coverDepth = s - top;
        double newBody = grow(body, x, z, coverDepth + config.alterationSampleDepthM, dt);
        double newCover = coverDepth > 0
                ? grow(cover, x, z, Math.max(0.5 * coverDepth, Math.min(coverDepth, config.alterationSampleDepthM)), dt)
                : 0;
        if (newCover > 0 && newCover >= newBody) {
            newBody = newCover; // the cover has caught up: one altered body again
            top = s;
            newCover = 0;
        }
        if (t == null) {
            if (newBody <= 0 && newCover <= 0) return;
            t = new AlterationTile();
            alteration.put(tileKey(x, z), t);
        }
        t.body[i] = newBody;
        t.cover[i] = newCover;
        t.top[i] = top;
    }

    private double grow(double a, int x, int z, double depth, double dt) {
        double temperature = ground.temperatureC(x, z, depth);
        if (!(temperature > 0)) return a;
        double wet = fluidAt(x, z, depth);
        if (wet <= 0) return a;
        double tRef = config.alterationReferenceC + 273.15;
        double arrhenius = Math.exp(-config.alterationActivationJPerMol / GAS_CONSTANT * (1 / (temperature + 273.15) - 1 / tRef));
        double rate = wet * arrhenius / config.alterationTimescaleSeconds;
        return 1 - (1 - a) * Math.exp(-rate * dt);
    }

    /** Hot-fluid availability at depth: steam, or liquid water (below the water table or in a wetted top). */
    private double fluidAt(int x, int z, double depth) {
        if (ground.steamFraction(x, z, depth) > 0) return 1;
        if (ground.waterTableDepthM(x, z) <= depth) return 1;
        double n = topPorosity(x, z);
        return RockStrength.clamp01(ground.vadoseM(x, z) / (n * depth));
    }

    private double topPorosity(int x, int z) {
        int n = world.layerCount(x, z);
        if (n == 0) return 0.1;
        return Math.max(0.05, world.layer(x, z, n - 1).porosity());
    }

    /** Alteration of the material at {@code elevation} in a column. */
    double alterationAt(int x, int z, double elevation) {
        AlterationTile t = alteration.get(tileKey(x, z));
        if (t == null) return 0;
        int i = ColumnStacks.localIndex(x, z);
        if (Double.isNaN(t.top[i]) || elevation <= t.top[i]) return t.body[i];
        return t.cover[i];
    }

    // ── Seismic shaking ──

    /**
     * Applies this step's earthquakes: each known world tile gets the largest pseudo-static coefficient
     * any quake produces at its nearest point (PGA varies little across a tile), and its steep columns
     * are queued for a check under that load.
     */
    private void shake(List<double[]> quakes) {
        if (quakes.isEmpty()) return;
        double l = world.spec().metersPerColumn();
        double minKh = GroundMotion.pseudoStatic(config.minPgaG);
        long[] keys = world.stacks().tileKeys();
        java.util.Arrays.sort(keys);
        for (long key : keys) {
            int tx = ColumnStacks.keyTileX(key);
            int tz = ColumnStacks.keyTileZ(key);
            double x0 = tx * ColumnStacks.TILE;
            double z0 = tz * ColumnStacks.TILE;
            double x1 = x0 + ColumnStacks.TILE - 1;
            double z1 = z0 + ColumnStacks.TILE - 1;
            double kh = 0;
            for (double[] q : quakes) {
                double dx = Math.max(0, Math.max(x0 - q[0], q[0] - x1)) * l;
                double dz = Math.max(0, Math.max(z0 - q[2], q[2] - z1)) * l;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > config.maxShakingRadiusM) continue;
                kh = Math.max(kh, GroundMotion.pseudoStatic(GroundMotion.pga(q[3], d / 1000)));
            }
            if (kh < minKh) continue;
            shaking.put(key, kh);
            for (int lz = 0; lz < ColumnStacks.TILE; lz++) {
                for (int lx = 0; lx < ColumnStacks.TILE; lx++) {
                    int x = (int) x0 + lx;
                    int z = (int) z0 + lz;
                    if (steepestTan(x, z) >= config.minSlope) active.add(key(x, z));
                }
            }
        }
    }

    private double khAt(int x, int z) {
        if (shaking.isEmpty()) return 0;
        Double kh = shaking.get(key(Math.floorDiv(x, ColumnStacks.TILE), Math.floorDiv(z, ColumnStacks.TILE)));
        return kh == null ? 0 : kh;
    }

    // ── Stability assessment ──

    /**
     * Result of a stability check of one column.
     *
     * @param factorOfSafety lowest factor of safety found (∞ when not examined: flat or unknown)
     * @param slipDepthM depth of the critical slip plane (m)
     * @param deep whether the critical mechanism is the Culmann wedge of the whole slope
     * @param slopeDeg steepest local slope (°)
     * @param reliefM height of the slope below this column (m)
     * @param criticalSlopeDeg steepest slope this slab would hold (°)
     */
    public record Assessment(int x, int z, double factorOfSafety, double slipDepthM, boolean deep, double slopeDeg,
            double reliefM, double criticalSlopeDeg, double cohesionPa, double frictionDeg, double ru, double kh,
            double alteration, double temperatureC, double saturation, short material) {
        public boolean fails() {
            return factorOfSafety < 1;
        }
    }

    /** Internal result: the assessment plus what to move. */
    private record Failure(Assessment a, long key, double thickness, long[] receivers, double[] weights,
            LayerView plane, double unitWeight) {}

    /** Stability of one column right now (static; no shaking). */
    public Assessment assess(int x, int z) {
        Evaluation e = evaluate(x, z, 0);
        return e == null ? null : e.assessment;
    }

    private record Evaluation(Assessment assessment, List<Failure> failures) {}

    /** Steepest downhill gradient (tan β) to an 8-neighbour, 0 if none or unknown. */
    private double steepestTan(int x, int z) {
        if (!world.isKnown(x, z)) return 0;
        double s = world.surfaceZ(x, z);
        double l = world.spec().metersPerColumn();
        double best = 0;
        for (int d = 0; d < 8; d++) {
            int nx = x + DX[d];
            int nz = z + DZ[d];
            if (!world.isKnown(nx, nz)) continue;
            double t = (s - world.surfaceZ(nx, nz)) / (l * (d < 4 ? 1 : SQRT2));
            if (t > best) best = t;
        }
        return best;
    }

    private Evaluation evaluate(int x, int z, double kh) {
        if (!world.isKnown(x, z)) return null;
        double l = world.spec().metersPerColumn();
        double s = world.surfaceZ(x, z);
        double[] drop = new double[8];
        double[] dist = new double[8];
        double bestTan = 0;
        int bestDir = -1;
        for (int d = 0; d < 8; d++) {
            int nx = x + DX[d];
            int nz = z + DZ[d];
            dist[d] = l * (d < 4 ? 1 : SQRT2);
            if (!world.isKnown(nx, nz)) {
                drop[d] = Double.NaN;
                continue;
            }
            drop[d] = s - world.surfaceZ(nx, nz);
            double t = drop[d] / dist[d];
            if (t > bestTan) {
                bestTan = t;
                bestDir = d;
            }
        }
        if (bestDir < 0 || bestTan < config.minSlope) return null;
        double beta = Math.atan(bestTan);

        // relief: walk the steepest-descent path down to the slope's foot
        List<long[]> path = new ArrayList<>(); // {x, z}
        List<double[]> pathData = new ArrayList<>(); // {surface, cumulative distance}
        path.add(new long[] {x, z});
        pathData.add(new double[] {s, 0});
        int cx = x;
        int cz = z;
        double cs = s;
        double along = 0;
        for (int step = 0; step < config.reliefSteps; step++) {
            int nd = -1;
            double nt = config.minSlope;
            for (int d = 0; d < 8; d++) {
                int nx = cx + DX[d];
                int nz = cz + DZ[d];
                if (!world.isKnown(nx, nz)) continue;
                double t = (cs - world.surfaceZ(nx, nz)) / dist[d];
                if (t > nt) {
                    nt = t;
                    nd = d;
                }
            }
            if (nd < 0) break;
            cx += DX[nd];
            cz += DZ[nd];
            cs = world.surfaceZ(cx, cz);
            along += dist[nd];
            path.add(new long[] {cx, cz});
            pathData.add(new double[] {cs, along});
        }
        double relief = s - cs;
        double faceTan = along > 0 ? relief / along : bestTan;

        // water
        double wtDepth = ground.waterTableDepthM(x, z);
        double pond = ground.surfaceWaterDepthM(x, z);
        double waterZ = world.waterZ(x, z);
        boolean submerged = pond > 0.01 || (!Double.isNaN(waterZ) && waterZ > s + 0.01);
        double wetFront = ground.vadoseM(x, z) / topPorosity(x, z);

        double zMax = Math.min(config.maxSlabDepthM, Math.max(relief, drop[bestDir]));
        double reach = Math.max(zMax, relief);
        int n = world.layerCount(x, z);
        double depthTop = 0;
        double weight = 0;
        double cSum = 0;
        double tanSum = 0;
        double satSum = 0;
        double minFs = Double.POSITIVE_INFINITY;
        Assessment shallowA = null;
        LayerView critPlane = null;
        double critGamma = 0;
        for (int k = n - 1; k >= 0 && depthTop < reach; k--) {
            LayerView layer = world.layer(x, z, k);
            Material m = layer.materialInfo();
            if (m.materialClass() == MaterialClass.VOID) break; // nothing slides on a cavity
            if (!m.solid()) {
                depthTop += layer.thickness();
                continue;
            }
            double t = layer.thickness();
            double zBot = depthTop + t;
            double zEnd = Math.min(zBot, reach);
            double seg = zEnd - depthTop;
            double sat = saturation(depthTop, zEnd, wtDepth, wetFront, submerged);
            double rho = m.densityKgM3() + RHO_W * layer.porosity() * sat;
            weight += rho * G * seg;
            double midDepth = 0.5 * (depthTop + zEnd);
            double tMid = ground.temperatureC(x, z, midDepth);
            RockStrength.Strength mid = RockStrength.of(layer, alterationAt(x, z, s - midDepth), tMid);
            cSum += mid.cohesionPa() * seg;
            tanSum += mid.tanPhi() * seg;
            satSum += sat * seg;

            double zp = Math.min(zEnd, zMax);
            if (zp > depthTop + 1e-9 && zp >= MIN_SLAB) {
                double partialWeight = weight - rho * G * (zEnd - zp);
                double gammaBar = partialWeight / zp;
                double temperature = ground.temperatureC(x, z, zp);
                double a = alterationAt(x, z, s - zp);
                RockStrength.Strength st = RockStrength.of(layer, a, temperature);
                LayerView planeLayer = layer;
                if (zp == zBot && k > 0) {
                    LayerView below = world.layer(x, z, k - 1);
                    if (below.materialInfo().solid()) {
                        RockStrength.Strength sb = RockStrength.of(below, alterationAt(x, z, s - zp - 1e-6), temperature);
                        if (sb.cohesionPa() < st.cohesionPa() || sb.tanPhi() < st.tanPhi()) {
                            // the interface is as weak as the weaker side
                            st = new RockStrength.Strength(Math.min(st.cohesionPa(), sb.cohesionPa()),
                                    Math.min(st.frictionRad(), sb.frictionRad()), st.dryDensity(), st.porosity());
                            if (sb.cohesionPa() < st.cohesionPa() + 1) planeLayer = below;
                        }
                    }
                }
                double hw = Math.max(0, zp - Math.max(0, wtDepth));
                if (wetFront >= zp) hw = zp;
                double gammaEff = submerged ? Math.max(0.2 * gammaBar, gammaBar - RHO_W * G) : gammaBar;
                double ru = submerged ? 0 : Math.min(1, RHO_W * G * hw / (gammaBar * zp));
                double fs = SlopeStability.infiniteSlope(st.cohesionPa(), st.tanPhi(), gammaEff, zp, beta, ru, kh);
                if (fs < minFs) {
                    minFs = fs;
                    shallowA = new Assessment(x, z, fs, zp, false, Math.toDegrees(beta), relief, Double.NaN,
                            st.cohesionPa(), Math.toDegrees(st.frictionRad()), ru, kh, a, temperature,
                            satSum / zEnd, planeLayer.material());
                    critPlane = planeLayer;
                    critGamma = gammaEff;
                }
            }
            depthTop = zBot;
        }
        if (shallowA == null) return null;
        // the critical angle (bisection) only where the slab actually fails
        Failure shallow = null;
        if (shallowA.factorOfSafety() < 1) {
            double crit = SlopeStability.criticalAngle(shallowA.cohesionPa(), Math.tan(Math.toRadians(shallowA.frictionDeg())),
                    critGamma, shallowA.slipDepthM(), shallowA.ru(), kh);
            shallowA = new Assessment(x, z, shallowA.factorOfSafety(), shallowA.slipDepthM(), false, shallowA.slopeDeg(),
                    relief, Math.toDegrees(crit), shallowA.cohesionPa(), shallowA.frictionDeg(), shallowA.ru(), kh,
                    shallowA.alteration(), shallowA.temperatureC(), shallowA.saturation(), shallowA.material());
            shallow = shallowMove(x, z, drop, dist, crit, shallowA.slipDepthM(), shallowA, critPlane, critGamma);
        }

        // Culmann wedge of the whole slope (only where the face is steeper than friction alone holds)
        Assessment best = shallowA;
        List<Failure> failures = new ArrayList<>();
        double depthUsed = Math.min(depthTop, reach);
        if (relief > MIN_SLAB && depthUsed > 0 && path.size() > 1) {
            double cBar = cSum / depthUsed;
            double tanBar = tanSum / depthUsed;
            double gammaBar = weight / depthUsed;
            double satBar = satSum / depthUsed;
            double faceBeta = Math.atan(faceTan);
            double hw = Math.max(0, relief - Math.max(0, wtDepth));
            if (wetFront >= relief) hw = relief;
            double ru = submerged ? 0 : Math.min(1, 0.5 * RHO_W * G * hw / (gammaBar * relief));
            double gammaEff = submerged ? Math.max(0.2 * gammaBar, gammaBar - RHO_W * G) : gammaBar;
            if (faceTan * (1 + kh) > tanBar * (1 - ru)) {
                double[] cul = SlopeStability.culmannMinimum(cBar, tanBar, gammaEff, relief, faceBeta, ru, kh);
                if (cul[0] < best.factorOfSafety()) {
                    best = new Assessment(x, z, cul[0], relief, true, Math.toDegrees(faceBeta), relief,
                            Math.toDegrees(cul[1]), cBar, Math.toDegrees(Math.atan(tanBar)), ru, kh,
                            alterationAt(x, z, s - 0.5 * relief), ground.temperatureC(x, z, 0.5 * relief), satBar,
                            shallowA.material());
                    if (cul[0] < 1) failures.addAll(wedgeMoves(path, pathData, Math.tan(cul[1]), best, gammaEff));
                }
            }
        }
        if (shallow != null && (failures.isEmpty())) failures.add(shallow);
        return new Evaluation(best, failures);
    }

    /** Saturated fraction of the depth interval [a, b]. */
    private static double saturation(double a, double b, double wtDepth, double wetFront, boolean submerged) {
        if (submerged) return 1;
        if (!(b > a)) return 0;
        double wetTop = Math.max(0, Math.min(b, wetFront) - a);
        double wtTop = Math.max(a, Math.max(0, wtDepth));
        double wetBottom = Math.max(0, b - wtTop);
        return Math.min(1, (wetTop + wetBottom) / (b - a));
    }

    /** Slab shedding toward the downslope neighbours steeper than the critical angle. */
    private Failure shallowMove(int x, int z, double[] drop, double[] dist, double critRad, double slab, Assessment a,
            LayerView plane, double unitWeight) {
        double tanCrit = Math.tan(critRad);
        double[] excess = new double[8];
        double maxExcess = 0;
        double sum = 0;
        for (int d = 0; d < 8; d++) {
            if (Double.isNaN(drop[d])) continue;
            double e = drop[d] - dist[d] * tanCrit;
            if (e > 0) {
                excess[d] = e;
                sum += e;
                maxExcess = Math.max(maxExcess, e);
            }
        }
        if (sum <= 0) return null;
        // shed at most the slab, and no more than levels the steepest excess (half goes each way)
        double t = Math.min(slab, 0.5 * maxExcess);
        if (t < MIN_MOVE) return null;
        int count = 0;
        for (double e : excess) if (e > 0) count++;
        long[] receivers = new long[count];
        double[] weights = new double[count];
        int j = 0;
        for (int d = 0; d < 8; d++) {
            if (excess[d] <= 0) continue;
            receivers[j] = key(x + DX[d], z + DZ[d]);
            weights[j] = excess[d] / sum;
            j++;
        }
        return new Failure(a, key(x, z), t, receivers, weights, plane, unitWeight);
    }

    /** The Culmann wedge cut along the steepest-descent path, each column shedding to the next. */
    private List<Failure> wedgeMoves(List<long[]> path, List<double[]> data, double tanTheta, Assessment a,
            double unitWeight) {
        List<Failure> list = new ArrayList<>();
        int last = path.size() - 1;
        double toeZ = data.get(last)[0];
        double toeAlong = data.get(last)[1];
        for (int j = 0; j < last; j++) {
            double plane = toeZ + (toeAlong - data.get(j)[1]) * tanTheta;
            double cut = data.get(j)[0] - plane;
            if (cut < MIN_MOVE) continue;
            long[] p = path.get(j);
            long[] next = path.get(j + 1);
            list.add(new Failure(a, key((int) p[0], (int) p[1]), cut, new long[] {key((int) next[0], (int) next[1])},
                    new double[] {1}, null, unitWeight));
        }
        return list;
    }

    // ── Relaxation ──

    private void relax(Outbox outbox) {
        int budget = config.maxColumnsPerStep;
        for (int pass = 0; pass < config.maxIterations && !active.isEmpty() && budget > 0; pass++) {
            List<Long> batch = new ArrayList<>();
            while (!active.isEmpty() && batch.size() < budget) batch.add(active.pollFirst());
            budget -= batch.size();
            // Jacobi: evaluate everything on the same surface, then apply
            TreeMap<Long, Failure> moves = new TreeMap<>();
            for (long k : batch) {
                Evaluation e = evaluate(keyX(k), keyZ(k), khAt(keyX(k), keyZ(k)));
                if (e == null) continue;
                for (Failure f : e.failures()) {
                    Failure prev = moves.get(f.key());
                    if (prev == null || f.thickness() > prev.thickness()) moves.put(f.key(), f);
                }
            }
            if (moves.isEmpty()) continue;
            for (List<Failure> cluster : clusters(moves)) apply(cluster, outbox);
            flushBlocks(outbox);
        }
    }

    /** Groups failures into 8-connected clusters, in key order. */
    private static List<List<Failure>> clusters(TreeMap<Long, Failure> moves) {
        List<List<Failure>> out = new ArrayList<>();
        TreeSet<Long> left = new TreeSet<>(moves.keySet());
        while (!left.isEmpty()) {
            long seed = left.pollFirst();
            List<Failure> cluster = new ArrayList<>();
            ArrayList<Long> queue = new ArrayList<>();
            queue.add(seed);
            for (int q = 0; q < queue.size(); q++) {
                long k = queue.get(q);
                cluster.add(moves.get(k));
                int x = keyX(k);
                int z = keyZ(k);
                for (int d = 0; d < 8; d++) {
                    long nk = key(x + DX[d], z + DZ[d]);
                    if (left.remove(nk)) queue.add(nk);
                }
            }
            cluster.sort((a, b) -> Long.compare(a.key(), b.key()));
            out.add(cluster);
        }
        return out;
    }

    /** Material removed from the top of a column: solid thickness by material, pore water, mean temperature. */
    private record Strip(TreeMap<Short, Double> solids, double solidM, double waterM, double temperatureC, double removedM) {}

    private Strip strip(int x, int z, double thickness, double saturation) {
        double s = world.surfaceZ(x, z);
        double bottom = s - thickness;
        TreeMap<Short, Double> solids = new TreeMap<>();
        double solid = 0;
        double water = 0;
        for (int k = world.layerCount(x, z) - 1; k >= 0; k--) {
            LayerView layer = world.layer(x, z, k);
            if (layer.top() <= bottom) break;
            double seg = Math.min(layer.top(), s) - Math.max(layer.bottom(), bottom);
            if (seg <= 0) continue;
            Material m = layer.materialInfo();
            if (!m.solid()) continue;
            double sv = seg * (1 - layer.porosity()) * (1 - layer.voidFraction());
            solids.merge(layer.material(), sv, Double::sum);
            solid += sv;
            water += seg * layer.porosity() * saturation;
        }
        double temperature = ground.temperatureC(x, z, 0.5 * thickness);
        double removed = world.erode(x, z, thickness, false).removedM();
        markChanged(x, z);
        return new Strip(solids, solid, water, temperature, removed);
    }

    private void apply(List<Failure> cluster, Outbox outbox) {
        double l = world.spec().metersPerColumn();
        double area = l * l;
        double volume = 0;
        double minFs = Double.POSITIVE_INFINITY;
        Failure worst = null;
        double drop = 0;
        double sx = 0;
        double sz = 0;
        double depthSum = 0;
        double altSum = 0;
        double satSum = 0;
        for (Failure f : cluster) {
            volume += f.thickness() * area;
            drop = Math.max(drop, f.thickness());
            sx += keyX(f.key()) * f.thickness();
            sz += keyZ(f.key()) * f.thickness();
            depthSum += f.a().slipDepthM() * f.thickness();
            altSum += f.a().alteration() * f.thickness();
            satSum += f.a().saturation() * f.thickness();
            if (f.a().factorOfSafety() < minFs) {
                minFs = f.a().factorOfSafety();
                worst = f;
            }
        }
        double tSum = volume / area;
        int cx = (int) Math.round(sx / tSum);
        int cz = (int) Math.round(sz / tSum);
        double saturation = satSum / tSum;

        boolean mobilise = volume >= config.avalancheMinVolumeM3;
        List<double[]> flowCells = new ArrayList<>();
        double solids = 0;
        double water = 0;
        double heat = 0;
        double runout = 0;
        double mass = 0;
        TreeMap<Long, TreeMap<Short, Double>> deposits = new TreeMap<>();
        for (Failure f : cluster) {
            int x = keyX(f.key());
            int z = keyZ(f.key());
            Strip st = strip(x, z, f.thickness(), f.a().saturation());
            solids += st.solidM();
            water += st.waterM();
            heat += st.temperatureC() * st.solidM();
            for (Map.Entry<Short, Double> e : st.solids().entrySet()) {
                mass += e.getValue() * area * MaterialTable.get(e.getKey()).densityKgM3()
                        / Math.max(0.05, 1 - MaterialTable.get(e.getKey()).porosity());
            }
            if (mobilise) {
                flowCells.add(new double[] {x, z, st.solidM() * area});
            } else {
                for (int r = 0; r < f.receivers().length; r++) {
                    long rk = f.receivers()[r];
                    double w = f.weights()[r];
                    TreeMap<Short, Double> into = deposits.computeIfAbsent(rk, k -> new TreeMap<>());
                    for (Map.Entry<Short, Double> e : st.solids().entrySet()) into.merge(e.getKey(), e.getValue() * w, Double::sum);
                    double dx = (keyX(rk) - x) * l;
                    double dz = (keyZ(rk) - z) * l;
                    runout = Math.max(runout, Math.sqrt(dx * dx + dz * dz));
                }
            }
        }
        double temperature = solids > 0 ? heat / solids : 15;

        FailureStyle style = FailureStyle.TALUS;
        if (mobilise) {
            style = release(flowCells, solids, water, temperature, saturation, new BlockPos(cx, terrain.groundY(cx, cz, 0), cz));
            if (style == FailureStyle.TALUS) {
                // no flow field: settle on the receivers after all
                for (Failure f : cluster) {
                    for (int r = 0; r < f.receivers().length; r++) {
                        // proportional share of this column's material
                        double frac = f.weights()[r] * f.thickness() / tSum;
                        TreeMap<Short, Double> into = deposits.computeIfAbsent(f.receivers()[r], k -> new TreeMap<>());
                        into.merge(MaterialTable.DEBRIS.id(), solids * frac, Double::sum);
                    }
                }
            }
        }
        if (!deposits.isEmpty()) {
            int unit = units.unit(DepositType.LANDSLIDE, now, Double.NaN);
            double n = storedPorosity(config.debrisPorosity);
            for (Map.Entry<Long, TreeMap<Short, Double>> e : deposits.entrySet()) {
                int x = keyX(e.getKey());
                int z = keyZ(e.getKey());
                if (!world.isKnown(x, z)) continue;
                for (Map.Entry<Short, Double> me : e.getValue().entrySet()) {
                    double thick = me.getValue() / (1 - n);
                    if (thick <= 0) continue;
                    world.deposit(x, z, thick, MaterialTable.get(me.getKey()), unit, LayerFlags.LOOSE, n, 0);
                }
                markChanged(x, z);
            }
        }

        stats.failures++;
        stats.failedM3 += volume;
        if (style != FailureStyle.TALUS) stats.avalanches++;
        if (volume >= config.reportMinVolumeM3 || style != FailureStyle.TALUS) {
            Trigger trigger = classify(worst);
            double unloading = 0;
            if (roof != null && roof.depthM() > 0) {
                unloading = 3 * mass * G / (2 * Math.PI * roof.depthM() * roof.depthM()) / 1e6;
            }
            outbox.emit(new GeomorphEvents.SlopeFailure(now, volcanoId, new BlockPos(cx, terrain.groundY(cx, cz, 0), cz),
                    volume, cluster.size(), drop, runout, style, trigger, minFs, depthSum / tSum, altSum / tSum,
                    saturation, temperature, unloading));
        } else {
            massWastingStep += volume;
            massWastingColumns += cluster.size();
        }
    }

    /** Sends a mobilised failure down the matching flow field; returns the style used (TALUS = none). */
    private FailureStyle release(List<double[]> cells, double solidsM, double waterM, double temperature,
            double saturation, BlockPos origin) {
        if (temperature >= config.hotCollapseTemperatureC && pdc != null) {
            List<double[]> bulk = scale(cells, 1 / (1 - config.debrisPorosity));
            if (pdc.releaseCells(origin, bulk, temperature, 0, MassFlowEvents.Trigger.DOME_COLLAPSE) > 0) {
                return FailureStyle.BLOCK_AND_ASH_FLOW;
            }
        }
        if (saturation >= config.debrisFlowSaturation && lahars != null && waterM > 0) {
            double c = Math.min(lahars.config().maxSedimentFraction, solidsM / (solidsM + waterM));
            // flow volume carrying the solids at concentration c
            List<double[]> mix = scale(cells, 1 / c);
            if (lahars.releaseCells(origin, mix, lahars.config().ambientC, c, MassFlowEvents.Trigger.SLOPE_FAILURE) > 0) {
                return FailureStyle.DEBRIS_FLOW;
            }
        }
        if (avalanches != null) {
            List<double[]> bulk = scale(cells, 1 / (1 - storedPorosity(MaterialTable.DEBRIS.porosity())));
            if (avalanches.releaseCells(origin, bulk, avalanches.config().ambientC, 0,
                    MassFlowEvents.Trigger.SLOPE_FAILURE) > 0) {
                return FailureStyle.DEBRIS_AVALANCHE;
            }
        }
        return FailureStyle.TALUS;
    }

    private static List<double[]> scale(List<double[]> cells, double factor) {
        List<double[]> out = new ArrayList<>(cells.size());
        for (double[] c : cells) out.add(new double[] {c[0], c[1], c[2] * factor});
        return out;
    }

    /** Which factor tipped the slope (output only). */
    private Trigger classify(Failure f) {
        if (f == null) return Trigger.OVERSTEEPENING;
        Assessment a = f.a();
        if (a.deep() || f.plane() == null) {
            if (a.kh() > 0) return Trigger.SEISMIC;
            if (a.ru() > 0) return Trigger.PORE_PRESSURE;
            if (a.alteration() > 0.2) return Trigger.ALTERATION;
            return Trigger.OVERSTEEPENING;
        }
        double beta = Math.toRadians(a.slopeDeg());
        double z = a.slipDepthM();
        double gamma = f.unitWeight();
        RockStrength.Strength base = RockStrength.of(f.plane(), 0, 15);
        double fsBase = SlopeStability.infiniteSlope(base.cohesionPa(), base.tanPhi(), gamma, z, beta, 0, 0);
        if (fsBase < 1) return Trigger.OVERSTEEPENING;
        Trigger best = Trigger.OVERSTEEPENING;
        double lowest = Double.POSITIVE_INFINITY;
        RockStrength.Strength altered = RockStrength.of(f.plane(), a.alteration(), 15);
        RockStrength.Strength hot = RockStrength.of(f.plane(), 0, a.temperatureC());
        double[] candidates = {
            SlopeStability.infiniteSlope(altered.cohesionPa(), altered.tanPhi(), gamma, z, beta, 0, 0),
            SlopeStability.infiniteSlope(hot.cohesionPa(), hot.tanPhi(), gamma, z, beta, 0, 0),
            SlopeStability.infiniteSlope(base.cohesionPa(), base.tanPhi(), gamma, z, beta, a.ru(), 0),
            SlopeStability.infiniteSlope(base.cohesionPa(), base.tanPhi(), gamma, z, beta, 0, a.kh())};
        Trigger[] names = {Trigger.ALTERATION, Trigger.THERMAL, Trigger.PORE_PRESSURE, Trigger.SEISMIC};
        for (int i = 0; i < candidates.length; i++) {
            if (candidates[i] < fsBase - 1e-9 && candidates[i] < lowest) {
                lowest = candidates[i];
                best = names[i];
            }
        }
        return best;
    }

    // ── Craters ──

    private void excavate(double[] e, Outbox outbox) {
        double energy = e[3];
        double radius = 0.5 * CraterScaling.diameter(energy) * config.craterDiameterScale;
        if (radius < config.minCraterRadiusM) return;
        radius = Math.min(radius, config.maxCraterRadiusM);
        double depth = CraterScaling.DEPTH_TO_DIAMETER * 2 * radius;
        double l = world.spec().metersPerColumn();
        int cx = (int) e[0];
        int cz = (int) e[2];
        int r = (int) Math.ceil(radius / l);
        // rim level: the mean surface just outside the crater
        double rimSum = 0;
        int rimCount = 0;
        for (int dz = -r - 1; dz <= r + 1; dz++) {
            for (int dx = -r - 1; dx <= r + 1; dx++) {
                double d = Math.sqrt((double) dx * dx + (double) dz * dz) * l;
                if (d < radius || d > radius + 1.5 * l) continue;
                if (!world.isKnown(cx + dx, cz + dz)) continue;
                rimSum += world.surfaceZ(cx + dx, cz + dz);
                rimCount++;
            }
        }
        if (rimCount == 0) return;
        double rim = rimSum / rimCount;
        TreeMap<Short, Double> solids = new TreeMap<>();
        double solid = 0;
        double removed = 0;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                double d = Math.sqrt((double) dx * dx + (double) dz * dz) * l;
                if (d >= radius) continue;
                int x = cx + dx;
                int z = cz + dz;
                if (!world.isKnown(x, z)) continue;
                double target = rim - CraterScaling.profileDepth(d, radius, depth);
                double cut = world.surfaceZ(x, z) - target;
                if (cut < MIN_MOVE) continue;
                Strip st = strip(x, z, cut, 0);
                for (Map.Entry<Short, Double> me : st.solids().entrySet()) solids.merge(me.getKey(), me.getValue(), Double::sum);
                solid += st.solidM();
                removed += st.removedM();
            }
        }
        if (removed <= 0) return;
        // ejecta blanket, t ∝ (r/R)^-3 out to EJECTA_OUTER_RADII radii, conserving the solid volume
        double outer = radius * CraterScaling.EJECTA_OUTER_RADII;
        int ro = (int) Math.ceil(outer / l);
        List<long[]> ring = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double wSum = 0;
        for (int dz = -ro; dz <= ro; dz++) {
            for (int dx = -ro; dx <= ro; dx++) {
                double d = Math.sqrt((double) dx * dx + (double) dz * dz) * l;
                if (d < radius || d > outer) continue;
                if (!world.isKnown(cx + dx, cz + dz)) continue;
                double w = CraterScaling.ejectaWeight(d, radius);
                ring.add(new long[] {cx + dx, cz + dz});
                weights.add(w);
                wSum += w;
            }
        }
        if (wSum > 0 && solid > 0) {
            int unit = units.unit(DepositType.EJECTA, now, Double.NaN);
            double n = storedPorosity(config.debrisPorosity);
            for (int i = 0; i < ring.size(); i++) {
                int x = (int) ring.get(i)[0];
                int z = (int) ring.get(i)[1];
                double share = weights.get(i) / wSum;
                for (Map.Entry<Short, Double> me : solids.entrySet()) {
                    double thick = me.getValue() * share / (1 - n);
                    if (thick > 0) world.deposit(x, z, thick, MaterialTable.get(me.getKey()), unit, LayerFlags.LOOSE, n, 0);
                }
                markChanged(x, z);
            }
        }
        stats.craters++;
        stats.excavatedM3 += removed * l * l;
        stats.maxCraterRadiusM = Math.max(stats.maxCraterRadiusM, radius);
        activateDisc(cx, cz, ro + 1);
        moved.add(key(cx, cz));
        outbox.emit(new GeomorphEvents.CraterExcavated(now, volcanoId, new BlockPos(cx, terrain.blockForSurface(rim - depth), cz),
                radius, depth, energy, removed * l * l));
    }

    private void activateDisc(int cx, int cz, int r) {
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz <= r * r) active.add(key(cx + dx, cz + dz));
            }
        }
    }

    /** The elevation of a vent's conduit mouth (m). */
    public double ventFloorZ(VentSite vent) {
        Double f = ventFloors.get(vent.id());
        return f != null ? f : world.spec().blockTop(vent.position().y());
    }

    /** Between eruptions the vent's fill becomes its new floor: the next eruption starts from there. */
    private void settleVentFloors() {
        for (VentSite vent : vents) {
            int x = vent.position().x();
            int z = vent.position().z();
            if (!world.isKnown(x, z)) continue;
            double surface = world.surfaceZ(x, z);
            if (surface > ventFloorZ(vent) + MIN_MOVE) ventFloors.put(vent.id(), surface);
        }
    }

    /** Open conduits swallow whatever falls or slides into them, down to the conduit mouth. */
    private void clearVents() {
        double l = world.spec().metersPerColumn();
        for (VentSite vent : vents) {
            double floor = ventFloorZ(vent);
            for (long[] c : conduitColumns(vent)) {
                int x = (int) c[0];
                int z = (int) c[1];
                if (!world.isKnown(x, z)) continue;
                // a flooded vent belongs to the magma–water interaction (jets, tuff ring) in the coupler
                TerrainColumn column = terrain.column(x, z);
                if (column != null && column.submerged()) continue;
                // only fallen, loose material: lava standing in the vent is the eruption itself
                double excess = Math.min(world.surfaceZ(x, z) - floor, looseTop(x, z));
                if (excess < MIN_MOVE) continue;
                Strip st = strip(x, z, excess, 0);
                stats.recycledM3 += st.removedM() * l * l;
                activateDisc(x, z, 2);
            }
        }
    }

    /** Thickness of the loose layers at the top of a column (m). */
    private double looseTop(int x, int z) {
        double sum = 0;
        for (int k = world.layerCount(x, z) - 1; k > 0; k--) {
            LayerView layer = world.layer(x, z, k);
            if (!layer.loose() || !layer.materialInfo().solid()) break;
            sum += layer.thickness();
        }
        return sum;
    }

    /** Columns of a vent's conduit mouth: a disc of its crater radius, or the fissure line. */
    private static List<long[]> conduitColumns(VentSite vent) {
        List<long[]> out = new ArrayList<>();
        int cx = vent.position().x();
        int cz = vent.position().z();
        if (vent.kind() == VentKind.FISSURE) {
            int half = Math.max(0, vent.fissureLength() / 2);
            double ax = Math.cos(vent.fissureAngleRad());
            double az = Math.sin(vent.fissureAngleRad());
            TreeSet<Long> seen = new TreeSet<>();
            for (int s = -half; s <= half; s++) {
                int x = cx + (int) Math.round(s * ax);
                int z = cz + (int) Math.round(s * az);
                if (seen.add(key(x, z))) out.add(new long[] {x, z});
            }
            return out;
        }
        int r = Math.max(0, vent.craterRadius());
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz <= r * r) out.add(new long[] {cx + dx, cz + dz});
            }
        }
        return out;
    }

    /** Shape of the crater around a vent, measured from the surface: rim radius and depth below the rim. */
    public record CraterShape(double radiusM, double depthM, double rimZ, double floorZ) {}

    public CraterShape crater(BlockPos center, double maxRadiusM) {
        double l = world.spec().metersPerColumn();
        int cx = center.x();
        int cz = center.z();
        if (!world.isKnown(cx, cz)) return new CraterShape(0, 0, Double.NaN, Double.NaN);
        double floor = world.surfaceZ(cx, cz);
        int steps = (int) Math.ceil(maxRadiusM / l);
        double radiusSum = 0;
        double rimSum = 0;
        int n = 0;
        for (int d = 0; d < 8; d++) {
            double best = floor;
            double bestR = 0;
            for (int k = 1; k <= steps; k++) {
                int x = cx + DX[d] * k;
                int z = cz + DZ[d] * k;
                if (!world.isKnown(x, z)) break;
                double s = world.surfaceZ(x, z);
                if (s > best) {
                    best = s;
                    bestR = k * l * (d < 4 ? 1 : SQRT2);
                }
            }
            radiusSum += bestR;
            rimSum += best;
            n++;
        }
        double rim = rimSum / n;
        double depth = rim - floor;
        if (depth < 0.5 * l) return new CraterShape(0, 0, rim, floor);
        return new CraterShape(radiusSum / n, depth, rim, floor);
    }

    // ── Piston collapse ──

    private void pistonCollapse(Outbox outbox) {
        double under = -roof.overpressureMPa();
        if (!(under > 0)) return;
        double v = roof.volumeM3();
        double depth = roof.depthM();
        double diameter = Math.cbrt(6 * v / Math.PI);
        if (!(diameter > 0) || depth / diameter > config.maxPistonAspectRatio) return;
        double sigmaN = config.ringFaultStressRatio * 2500 * G * depth / 2;
        double tau = config.ringFaultCohesionPa + sigmaN * Math.tan(Math.toRadians(config.ringFaultFrictionDeg));
        double critical = 4 * tau * depth / diameter / 1e6; // MPa
        if (under <= critical) return;
        double beta = roof.compressibilityPerMPa();
        double volume = (under - critical) * beta * v;
        double radius = diameter / 2;
        double l = world.spec().metersPerColumn();
        int r = (int) Math.ceil(radius / l);
        BlockPos c = roof.center();
        List<long[]> cols = new ArrayList<>();
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (Math.sqrt((double) dx * dx + (double) dz * dz) * l > radius) continue;
                if (world.isKnown(c.x() + dx, c.z() + dz)) cols.add(new long[] {c.x() + dx, c.z() + dz});
            }
        }
        if (cols.isEmpty()) return;
        double area = cols.size() * l * l;
        double drop = volume / area;
        for (long[] col : cols) {
            // the stratigraphy above the ring fault sinks; the world model shows it as lowered ground
            strip((int) col[0], (int) col[1], drop, 0);
        }
        roof.subside(volume);
        calderaSubsidenceM += drop;
        stats.calderaSteps++;
        activateDisc(c.x(), c.z(), r + 2);
        moved.add(key(c.x(), c.z()));
        outbox.emit(new GeomorphEvents.CalderaCollapse(now, volcanoId, c, radius, drop, calderaSubsidenceM, volume, under,
                critical));
    }

    // ── Blocks ──

    private void markChanged(int x, int z) {
        long k = key(x, z);
        changed.add(k);
        for (int d = 0; d < 8; d++) active.add(key(x + DX[d], z + DZ[d]));
        active.add(k);
    }

    /** Mirrors changed world-model columns into blocks (whole blocks once more than half filled). */
    private void flushBlocks(Outbox outbox) {
        for (long k : changed) {
            int x = keyX(k);
            int z = keyZ(k);
            TerrainColumn column = terrain.column(x, z);
            if (column == null || !world.isKnown(x, z)) continue;
            double s = world.surfaceZ(x, z);
            int newY = terrain.blockForSurface(s);
            int oldY = column.groundY();
            if (newY == oldY) continue;
            BlockId top = blockAt(x, z, newY);
            if (newY < oldY) {
                for (int y = oldY; y > newY; y--) {
                    boolean wet = column.waterY() != TerrainColumn.NO_WATER && y <= column.waterY();
                    BlockId expected = y == oldY ? column.surface() : null;
                    outbox.setBlock(new BlockChange(new BlockPos(x, y, z), expected,
                            BlockState.of(wet ? WATER : BlockId.AIR)));
                }
                outbox.setBlock(BlockChange.set(new BlockPos(x, newY, z), top));
            } else {
                for (int y = oldY + 1; y <= newY; y++) {
                    boolean wet = column.waterY() != TerrainColumn.NO_WATER && y <= column.waterY();
                    outbox.setBlock(BlockChange.replace(new BlockPos(x, y, z), wet ? WATER : BlockId.AIR, blockAt(x, z, y)));
                }
            }
            terrain.updateBlockCache(x, z, newY, top);
        }
        changed.clear();
    }

    private BlockId blockAt(int x, int z, int y) {
        Material m = world.materialAt(x, z, world.spec().blockBottom(y) + 0.5 * world.spec().metersPerColumn());
        if (m == null || !m.solid()) m = MaterialTable.DEBRIS;
        return terrain.palette().block(m);
    }

    // ── Keys ──

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    static int keyX(long key) {
        return (int) (key >> 32);
    }

    static int keyZ(long key) {
        return (int) key;
    }

    /**
     * The porosity a layer actually gets: the world model stores it in 8 bits, so solid volumes are
     * converted with the stored value to conserve mass exactly.
     */
    private static double storedPorosity(double n) {
        return Math.round(RockStrength.clamp01(n) * 255) / 255.0;
    }

    private static long tileKey(int x, int z) {
        return key(Math.floorDiv(x, ColumnStacks.TILE), Math.floorDiv(z, ColumnStacks.TILE));
    }

    /** World expansion activity: craters excavated and caldera collapse in the latest step. */
    public void reportActivity(me.alex4386.typhon.engine.expansion.ExpansionActivity.Sink sink) {
        for (long k : moved) sink.active(keyX(k), keyZ(k));
    }

    // ── Persistence ──

    @Override
    public void saveState(StateWriter out) {
        JsonObject o = out.json();
        JsonArray act = new JsonArray();
        for (long k : active) act.add(k);
        o.add("active", act);
        JsonArray mv = new JsonArray();
        for (long k : moved) mv.add(k);
        o.add("moved", mv);
        o.add("explosions", arrays(pendingExplosions));
        o.add("quakes", arrays(pendingQuakes));
        o.addProperty("sweepCursor", sweepCursor);
        o.addProperty("realClock", realClock);
        o.addProperty("calderaSubsidenceM", calderaSubsidenceM);
        JsonObject floors = new JsonObject();
        for (Map.Entry<String, Double> e : ventFloors.entrySet()) floors.addProperty(e.getKey(), e.getValue());
        o.add("ventFloors", floors);
        JsonObject s = new JsonObject();
        stats.save(s);
        o.add("stats", s);
        StateWriter.Field field = out.field("alteration", 1);
        for (Map.Entry<Long, AlterationTile> e : alteration.entrySet()) {
            AlterationTile t = e.getValue();
            field.put(keyX(e.getKey()), keyZ(e.getKey()), new FieldChunk().doubles("body", t.body.clone())
                    .doubles("cover", t.cover.clone()).doubles("top", t.top.clone()));
        }
        JsonArray clock = new JsonArray();
        for (Map.Entry<Long, Double> e : tileClock.entrySet()) {
            JsonArray row = new JsonArray();
            row.add(e.getKey());
            row.add(e.getValue());
            clock.add(row);
        }
        o.add("tileClock", clock);
    }

    @Override
    public void loadState(StateReader in) {
        JsonObject o = in.json();
        active.clear();
        for (JsonElement e : o.getAsJsonArray("active")) active.add(e.getAsLong());
        moved.clear();
        if (o.has("moved")) for (JsonElement e : o.getAsJsonArray("moved")) moved.add(e.getAsLong());
        pendingExplosions.clear();
        readArrays(o.getAsJsonArray("explosions"), pendingExplosions);
        pendingQuakes.clear();
        readArrays(o.getAsJsonArray("quakes"), pendingQuakes);
        sweepCursor = o.get("sweepCursor").getAsLong();
        realClock = o.get("realClock").getAsDouble();
        calderaSubsidenceM = o.get("calderaSubsidenceM").getAsDouble();
        ventFloors.clear();
        if (o.has("ventFloors")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("ventFloors").entrySet()) {
                ventFloors.put(e.getKey(), e.getValue().getAsDouble());
            }
        }
        stats.load(o.getAsJsonObject("stats"));
        tileClock.clear();
        for (JsonElement e : o.getAsJsonArray("tileClock")) {
            JsonArray row = e.getAsJsonArray();
            tileClock.put(row.get(0).getAsLong(), row.get(1).getAsDouble());
        }
        alteration.clear();
        StateReader.Field field = in.field("alteration");
        if (field != null) {
            for (StateReader.Entry e : field.chunks()) {
                AlterationTile t = new AlterationTile();
                System.arraycopy(e.data().doubles("body"), 0, t.body, 0, t.body.length);
                System.arraycopy(e.data().doubles("cover"), 0, t.cover, 0, t.cover.length);
                System.arraycopy(e.data().doubles("top"), 0, t.top, 0, t.top.length);
                alteration.put(key(e.chunkX(), e.chunkZ()), t);
            }
        }
    }

    private static JsonArray arrays(List<double[]> list) {
        JsonArray a = new JsonArray();
        for (double[] v : list) {
            JsonArray row = new JsonArray();
            for (double d : v) row.add(d);
            a.add(row);
        }
        return a;
    }

    private static void readArrays(JsonArray a, List<double[]> into) {
        for (JsonElement e : a) {
            JsonArray row = e.getAsJsonArray();
            double[] v = new double[row.size()];
            for (int i = 0; i < v.length; i++) v[i] = row.get(i).getAsDouble();
            into.add(v);
        }
    }

    // ── Snapshot ──

    /** UI summary. */
    public record Snapshot(long failures, double failedM3, long avalanches, long craters, double excavatedM3,
            double recycledM3, double maxCraterRadiusM, double calderaSubsidenceM, int queuedColumns, int alteredTiles,
            Map<String, CraterShape> vents) {}

    @Override
    public Snapshot snapshot() {
        Map<String, CraterShape> shapes = new TreeMap<>();
        for (VentSite v : vents) shapes.put(v.id(), crater(v.position(), 300 * world.spec().metersPerColumn()));
        return new Snapshot(stats.failures, stats.failedM3, stats.avalanches, stats.craters, stats.excavatedM3,
                stats.recycledM3, stats.maxCraterRadiusM, calderaSubsidenceM, active.size(), alteration.size(),
                Map.copyOf(shapes));
    }

    public long failureCount() {
        return stats.failures;
    }

    public double failedVolumeM3() {
        return stats.failedM3;
    }

    public double recycledM3() {
        return stats.recycledM3;
    }

    public double calderaSubsidenceM() {
        return calderaSubsidenceM;
    }

    public int queuedColumns() {
        return active.size();
    }

}
