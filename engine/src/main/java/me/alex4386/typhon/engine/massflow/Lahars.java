package me.alex4386.typhon.engine.massflow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.FlowCell;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.Trigger;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.Outbox;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.world.BlockState;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.LayerFlags;
import me.alex4386.typhon.engine.world.LayerView;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.Provenance;
import me.alex4386.typhon.engine.world.WorldModel;

/**
 * Lahars: water–sediment flows (hyperconcentrated flow to debris flow) on the shared Voellmy solver
 * (see {@link MassFlowField}), tracking sediment volume fraction {@code c}.
 *
 * <ul>
 *   <li>Friction rises with sediment: {@code μ = μ₀ + (μ_max − μ₀)·min(1, c/c_max)}, so dilute floods
 *       run farthest and debris flows stop sooner.
 *   <li>Bulking: faster than {@link MassFlowConfig#erosionSpeed}, the flow entrains loose volcanic
 *       deposit (unconsolidated tephra-fall, ignimbrite and lahar layers in the world model) at
 *       {@code E = k·u·dt·(1 − c/c_max)} m per step, eroding it out of the stacks; the eroded bulk
 *       joins the flow with solids fraction {@code 1 − porosity}.
 *   <li>Deposition: {@code r = 1/τ_sed + max(0, 1 − u/u_stop)/τ_stop (+ 1/τ_water in standing
 *       water)}; settled flow leaves sediment {@code c·Δh/(1 − porosity)} thick and its water
 *       drains. Fast deposits are coarse (gravel), slow ones fine (mud, compacting to packed mud when
 *       buried). Deposits raise the bed and can dam channels.
 * </ul>
 *
 * <p>Triggers: rain saturating loose deposit until it fails ({@link #setRainfall}), meltwater
 * ({@link #meltwater}), crater lake breakout ({@link #lakeBreakout}) and plain releases or sustained
 * sources.
 */
public final class Lahars extends MassFlowField {
    /** Default subsystem id for a volcano's lahar field. */
    public static String defaultId(String volcanoId) {
        return "lahar:" + volcanoId;
    }

    private double rainfallMmPerHour;
    private boolean rainLaharActive;
    /** Columns where loose volcanic material was deposited (packed x/z), oldest first; may hold stale entries. */
    private final TreeSet<Long> erodibleColumns = new TreeSet<>();

    public Lahars(String id, TerrainModel terrain, MassFlowConfig config) {
        super(id, MassFlowKind.LAHAR, terrain, config);
        terrain.world().addDepositObserver((x, z, thickness, unit, flags) -> {
            if ((flags & LayerFlags.LOOSE) != 0 && ERODIBLE.contains(terrain.world().unit(unit).type())) {
                erodibleColumns.add(columnKey(x, z));
            }
        });
    }

    public Lahars(String id, TerrainModel terrain) {
        this(id, terrain, MassFlowConfig.lahar());
    }

    @Override
    public void registerCommands(CommandBus bus) {
        super.registerCommands(bus);
        bus.register(MassFlowCommands.SetRainfall.class, c -> {
            if (c.target().equals(id)) setRainfall(c.mmPerHour());
        });
    }

    // ── Triggers ──

    /** Deposit types whose loose layers rain and lahars rework: fresh tephra, ignimbrite, lahar deposits. */
    static final Set<DepositType> ERODIBLE = EnumSet.of(DepositType.FALL, DepositType.PDC, DepositType.LAHAR);

    /**
     * Lays {@code thicknessM} of loose ash (an unattributed {@link DepositType#FALL} unit) on a known
     * column of the world model, e.g. to script a deposit that rain can later mobilise. Normally
     * deposits come from tephra fall and pyroclastic flows by themselves.
     */
    public void addErodibleDeposit(int x, int z, double thicknessM) {
        if (thicknessM <= 0) return;
        WorldModel world = terrain.world();
        int unit = Provenance.unitFor(world, null, -1, DepositType.FALL, 0, Double.NaN, Double.NaN);
        world.deposit(x, z, thicknessM, MaterialTable.ASH, unit);
    }

    /**
     * Loose volcanic material at the top of a column (m): the unconsolidated {@link #ERODIBLE} layers
     * down to the first consolidated layer, cavity or non-volcanic layer.
     */
    public double erodibleThickness(int x, int z) {
        WorldModel world = terrain.world();
        double sum = 0;
        for (int k = world.layerCount(x, z) - 1; k > 0; k--) {
            LayerView layer = world.layer(x, z, k);
            if (!layer.loose() || layer.material() == MaterialTable.VOID.id()) break;
            if (!ERODIBLE.contains(world.unit(layer.unit()).type())) break;
            sum += layer.thickness();
        }
        return sum;
    }

    /** Removes up to {@code thicknessM} of loose material from the world model; returns what was removed. */
    private double erodeLoose(int x, int z, double thicknessM) {
        if (thicknessM <= 0) return 0;
        return terrain.world().erode(x, z, thicknessM, true).removedM();
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    /** Rainfall intensity over the field (mm/h); 0 ends the rain. */
    public void setRainfall(double mmPerHour) {
        rainfallMmPerHour = Math.max(0, mmPerHour);
        if (rainfallMmPerHour == 0) rainLaharActive = false;
    }

    public double rainfall() {
        return rainfallMmPerHour;
    }

    /** Snow/ice melted by an eruption or hot deposits: dilute water picking up sediment as it runs. */
    public boolean meltwater(BlockPos center, int radius, double volumeM3) {
        return release(center, radius, volumeM3, config.ambientC, 0.05, Trigger.MELTWATER);
    }

    /** Sudden release of a crater lake's water. */
    public boolean lakeBreakout(BlockPos center, int radius, double volumeM3) {
        return release(center, radius, volumeM3, config.ambientC, 0.02, Trigger.LAKE_BREAKOUT);
    }

    // ── Kind hooks ──

    @Override
    protected double friction(MassFlowChunk c, int i) {
        double ratio = Math.min(1, c.sediment[i] / config.maxSedimentFraction);
        return config.frictionCoefficient + (config.frictionAtMaxSediment - config.frictionCoefficient) * ratio;
    }

    /**
     * Rain soaks into loose deposit; once its pores are full ({@code soaked ≥ porosity · e}) a deposit
     * on a slope steeper than {@link MassFlowConfig#rainMinSlope} fails and flows off as a slug: the
     * saturated bulk {@code e} (solids fraction {@code 1 − porosity}) plus concentrated runoff of
     * {@code rainFailureWaterRatio · e}. Gentle slopes just stay saturated. The loose layers are read
     * from (and eroded out of) the world model.
     */
    @Override
    protected void beforeTransport(double dt, double time, Outbox outbox) {
        if (rainfallMmPerHour <= 0) return;
        double rain = rainfallMmPerHour / 1000 / 3600 * dt; // m of water this step
        double solids = 1 - config.porosity;
        double mobilised = 0;
        BlockPos strongest = null;
        double strongestVolume = 0;

        for (long key : new ArrayList<>(erodibleColumns)) {
            int x = (int) (key >> 32);
            int z = (int) key;
            double e = erodibleThickness(x, z);
            if (e <= 0) {
                erodibleColumns.remove(key);
                continue;
            }
            MassFlowChunk c = chunkFor(x >> 4, z >> 4);
            if (c == null) continue;
            ensureFresh(c);
            int i = index(x, z);
            if (c.ground[i] == UNKNOWN) continue;
            c.soak[i] = Math.min(c.soak[i] + rain, config.porosity * e);
            if (c.soak[i] > 0 && c.soakedCells == 0) c.soakedCells = 1;
            if (e < config.rainMinErodible || c.soak[i] < config.porosity * e) continue;
            if (steepestBedSlope(c, i) < config.rainMinSlope) continue;

            double removed = erodeLoose(x, z, e);
            c.soak[i] = 0;
            if (removed <= 0) continue;
            double runoff = config.rainFailureWaterRatio * removed;
            if (runoff > 0) {
                inject(x, z, runoff * cellArea, config.ambientC, 0);
                released += runoff * cellArea;
            }
            entrain(c, i, removed, solids);
            double volume = (runoff + removed) * cellArea;
            mobilised += volume;
            if (volume > strongestVolume) {
                strongestVolume = volume;
                strongest = new BlockPos(x, c.ground[i], z);
            }
        }
        if (mobilised > 0 && !rainLaharActive) {
            rainLaharActive = true;
            outbox.emit(new MassFlowEvents.LaharStarted(time, id, Trigger.RAIN, strongest, mobilised, mobilised / dt));
        }
    }

    /**
     * Steepest downhill ground slope (tan θ) measured over {@link #SLOPE_WINDOW} blocks: block terrain
     * is stepped, so the immediate neighbour of a cell on a gentle slope is often level with it.
     */
    private double steepestBedSlope(MassFlowChunk c, int i) {
        int x = c.worldX(i);
        int z = c.worldZ(i);
        int g = c.ground[i];
        double best = 0;
        for (int d = 0; d < 4; d++) {
            int gj = terrain.groundY(x + DX[d] * SLOPE_WINDOW, z + DZ[d] * SLOPE_WINDOW, g);
            best = Math.max(best, (double) (g - gj) / SLOPE_WINDOW);
        }
        return best;
    }

    private static final int SLOPE_WINDOW = 4;

    @Override
    protected void process(MassFlowChunk c, int i, double dt, Outbox outbox, double time) {
        double u = speed(c, i);
        double sediment = c.sediment[i];
        double solids = 1 - config.porosity;

        if (u > config.erosionSpeed) {
            int x = c.worldX(i);
            int z = c.worldZ(i);
            if (erodibleColumns.contains(columnKey(x, z))) {
                double e = erodibleThickness(x, z);
                double capacity = Math.max(0, 1 - sediment / config.maxSedimentFraction);
                double eroded = erodeLoose(x, z, Math.min(e, config.erosionCoefficient * u * dt * capacity));
                if (eroded > 0) {
                    c.soak[i] = Math.min(c.soak[i], config.porosity * Math.max(0, e - eroded));
                    entrain(c, i, eroded, solids);
                    sediment = c.sediment[i];
                }
            }
        }

        double rate = 1 / config.sedimentationTimescale;
        if (u < config.stopSpeed) rate += (1 - u / config.stopSpeed) / config.stopTimescale;
        if (submerged(c, i)) rate += 1 / config.waterDepositionTimescale;
        double settled = c.depth[i] * (1 - StrictMath.exp(-rate * dt));
        if (c.depth[i] - settled < config.minDepth) settled = c.depth[i];
        depositFlow(c, i, settled, settled * sediment / solids, outbox);
    }

    @Override
    protected DepositType depositType() {
        return DepositType.LAHAR;
    }

    @Override
    protected Material depositMaterial(double temperatureC, double speed) {
        return MaterialTable.LAHAR_DEPOSIT;
    }

    @Override
    protected int depositFlags(double temperatureC) {
        return LayerFlags.LOOSE;
    }

    @Override
    protected double depositWelding(double temperatureC) {
        return 0;
    }

    @Override
    protected BlockState depositBlock(MassFlowChunk c, int i, double meanTemperatureC, double meanSpeed) {
        return MassFlowPalette.laharBlock(meanSpeed, config.coarseSpeed);
    }

    @Override
    protected BlockState veneer(int tier) {
        return tier >= 2 ? MassFlowPalette.LAHAR_VENEER : MassFlowPalette.LAHAR_THIN_VENEER;
    }

    @Override
    protected EngineEvent startedEvent(double time, PendingStart start) {
        return new MassFlowEvents.LaharStarted(time, id, start.trigger(), start.position(), start.volumeM3(),
                start.rateM3PerS());
    }

    @Override
    protected EngineEvent frontEvent(double time, BlockPos front, double runoutM, int cells, double volume,
            double maxSpeed, double tracer, List<FlowCell> reported) {
        return new MassFlowEvents.LaharFront(time, id, front, runoutM, cells, volume, maxSpeed, tracer, reported);
    }

    @Override
    protected EngineEvent depositEvent(double time, int cells, double volume, int blocks) {
        return new MassFlowEvents.LaharDeposit(time, id, cells, volume, blocks);
    }

    @Override
    protected void saveExtra(JsonObject out) {
        out.addProperty("rainfall", rainfallMmPerHour);
        out.addProperty("rainLaharActive", rainLaharActive);
        JsonArray columns = new JsonArray();
        for (long key : erodibleColumns) columns.add(key);
        out.add("erodibleColumns", columns);
    }

    @Override
    protected void loadExtra(JsonObject in) {
        rainfallMmPerHour = in.get("rainfall").getAsDouble();
        rainLaharActive = in.get("rainLaharActive").getAsBoolean();
        erodibleColumns.clear();
        for (JsonElement e : in.getAsJsonArray("erodibleColumns")) erodibleColumns.add(e.getAsLong());
    }
}
