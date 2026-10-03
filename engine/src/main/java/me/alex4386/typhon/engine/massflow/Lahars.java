package me.alex4386.typhon.engine.massflow;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.command.CommandBus;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.FlowCell;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.Trigger;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.BlockChange;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.Outbox;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.world.BlockId;
import me.alex4386.typhon.engine.world.BlockState;

/**
 * Lahars: water–sediment flows (hyperconcentrated flow to debris flow) on the shared Voellmy solver
 * (see {@link MassFlowField}), tracking sediment volume fraction {@code c}.
 *
 * <ul>
 *   <li>Friction rises with sediment: {@code μ = μ₀ + (μ_max − μ₀)·min(1, c/c_max)}, so dilute floods
 *       run farthest and debris flows stop sooner.
 *   <li>Bulking: faster than {@link MassFlowConfig#erosionSpeed}, the flow entrains loose deposit
 *       ({@link #addErodibleDeposit}) at {@code E = k·u·dt·(1 − c/c_max)} m per step; the eroded bulk
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

    public Lahars(String id, TerrainModel terrain, MassFlowConfig config) {
        super(id, MassFlowKind.LAHAR, terrain, config);
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

    /**
     * Marks loose material (fresh tephra or PDC deposit) that rain and passing lahars can mobilise.
     * It describes the existing surface (already part of the terrain), so it does not raise the bed.
     */
    public void addErodibleDeposit(int x, int z, double thicknessM) {
        if (thicknessM <= 0) return;
        MassFlowChunk c = chunkFor(x >> 4, z >> 4);
        if (c == null) return;
        int i = index(x, z);
        if (c.erodible[i] <= 0) c.erodibleCells++;
        c.erodible[i] += thicknessM;
    }

    public double erodibleThickness(int x, int z) {
        MassFlowChunk c = chunkAt(x, z);
        return c == null ? 0 : c.erodible[index(x, z)];
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
     * {@code rainFailureWaterRatio · e}. Gentle slopes just stay saturated.
     */
    @Override
    protected void beforeTransport(double dt, long tick, Outbox outbox) {
        if (rainfallMmPerHour <= 0) return;
        double rain = rainfallMmPerHour / 1000 / 3600 * dt; // m of water this step
        double solids = 1 - config.porosity;
        double mobilised = 0;
        BlockPos strongest = null;
        double strongestVolume = 0;

        for (MassFlowChunk c : new ArrayList<>(chunks())) {
            if (c.erodibleCells == 0) continue;
            ensureFresh(c);
            for (int i = 0; i < AREA; i++) {
                double e = c.erodible[i];
                if (e <= 0 || c.ground[i] == UNKNOWN) continue;
                c.soak[i] = Math.min(c.soak[i] + rain, config.porosity * e);
                if (e < config.rainMinErodible || c.soak[i] < config.porosity * e) continue;
                if (steepestBedSlope(c, i) < config.rainMinSlope) continue;

                double runoff = config.rainFailureWaterRatio * e;
                c.erodible[i] = 0;
                c.soak[i] = 0;
                c.erodibleCells--;
                int x = c.worldX(i);
                int z = c.worldZ(i);
                if (runoff > 0) {
                    inject(x, z, runoff * cellArea, config.ambientC, 0);
                    released += runoff * cellArea;
                }
                entrain(c, i, e, solids);
                double volume = (runoff + e) * cellArea;
                mobilised += volume;
                if (volume > strongestVolume) {
                    strongestVolume = volume;
                    strongest = new BlockPos(x, c.ground[i], z);
                }
            }
        }
        if (mobilised > 0 && !rainLaharActive) {
            rainLaharActive = true;
            outbox.emit(new MassFlowEvents.LaharStarted(tick, id, Trigger.RAIN, strongest, mobilised, mobilised / dt));
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
    protected void process(MassFlowChunk c, int i, double dt, Outbox outbox, long tick) {
        double u = speed(c, i);
        double sediment = c.sediment[i];
        double solids = 1 - config.porosity;

        if (u > config.erosionSpeed && c.erodible[i] > 0) {
            double capacity = Math.max(0, 1 - sediment / config.maxSedimentFraction);
            double eroded = Math.min(c.erodible[i], config.erosionCoefficient * u * dt * capacity);
            if (eroded > 0) {
                c.erodible[i] -= eroded;
                if (c.erodible[i] <= 0) c.erodible[i] = 0;
                c.soak[i] = Math.min(c.soak[i], config.porosity * c.erodible[i]);
                entrain(c, i, eroded, solids);
                sediment = c.sediment[i];
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
    protected BlockState depositBlock(MassFlowChunk c, int i, double meanTemperatureC, double meanSpeed) {
        return MassFlowPalette.laharBlock(meanSpeed, config.coarseSpeed);
    }

    @Override
    protected void afterBlockPlaced(MassFlowChunk c, int i, BlockId previousSurface, int y, Outbox outbox) {
        if (previousSurface.equals(MassFlowPalette.MUD.id())) {
            outbox.setBlock(BlockChange.replace(new BlockPos(c.worldX(i), y - 1, c.worldZ(i)), MassFlowPalette.MUD.id(),
                    MassFlowPalette.PACKED_MUD));
        }
    }

    @Override
    protected BlockState veneer(int tier) {
        return tier >= 2 ? MassFlowPalette.LAHAR_VENEER : MassFlowPalette.LAHAR_THIN_VENEER;
    }

    @Override
    protected EngineEvent startedEvent(long tick, PendingStart start) {
        return new MassFlowEvents.LaharStarted(tick, id, start.trigger(), start.position(), start.volumeM3(),
                start.rateM3PerS());
    }

    @Override
    protected EngineEvent frontEvent(long tick, BlockPos front, double runoutM, int cells, double volume,
            double maxSpeed, double tracer, List<FlowCell> reported) {
        return new MassFlowEvents.LaharFront(tick, id, front, runoutM, cells, volume, maxSpeed, tracer, reported);
    }

    @Override
    protected EngineEvent depositEvent(long tick, int cells, double volume, int blocks) {
        return new MassFlowEvents.LaharDeposit(tick, id, cells, volume, blocks);
    }

    @Override
    protected void saveExtra(JsonObject out) {
        out.addProperty("rainfall", rainfallMmPerHour);
        out.addProperty("rainLaharActive", rainLaharActive);
    }

    @Override
    protected void loadExtra(JsonObject in) {
        rainfallMmPerHour = in.get("rainfall").getAsDouble();
        rainLaharActive = in.get("rainLaharActive").getAsBoolean();
    }
}
