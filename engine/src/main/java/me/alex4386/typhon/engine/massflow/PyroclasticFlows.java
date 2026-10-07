package me.alex4386.typhon.engine.massflow;

import java.util.ArrayList;
import java.util.List;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.FlowCell;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.Trigger;
import me.alex4386.typhon.engine.math.ColumnIndex;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.Outbox;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.LayerFlags;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialTable;

/**
 * Pyroclastic density currents: the dense basal part of a column-collapse or dome-collapse flow,
 * modelled as a hot Voellmy granular current (see {@link MassFlowField}).
 *
 * <p>Processes per column, per sub-step {@code dt}:
 *
 * <ul>
 *   <li>Cooling by air entrainment: {@code T ← T_a + (T − T_a)·e^{−dt/τ_cool}}.
 *   <li>Sedimentation: a fraction {@code 1 − e^{−r·dt}} settles, with
 *       {@code r = 1/τ_sed + max(0, 1 − u/u_stop)/τ_stop} — slow background sedimentation plus
 *       en-masse freezing as the flow decelerates. Deposit thickness is the settled flow thickness ×
 *       {@link MassFlowConfig#depositThicknessFactor}; deposits hotter than the welding temperature
 *       become welded ignimbrite.
 *   <li>Over water the flow flashes it to steam and loses mass at rate {@code 1/τ_water}.
 * </ul>
 *
 * <p>Sources: {@link #columnCollapse} (sustained feed from a collapsing eruption column — see
 * {@link ColumnCollapse} for the criterion) and {@link #domeCollapse} (instantaneous release).
 */
public final class PyroclasticFlows extends MassFlowField {
    /** Default subsystem id for a volcano's PDC field. */
    public static String defaultId(String volcanoId) {
        return "pdc:" + volcanoId;
    }

    /** Bulk density of the dense basal flow used to turn collapsing mass flux into flow volume (kg/m³). */
    public static final double DENSE_FLOW_DENSITY = 1000;

    private int steamEvents;
    private double steamStamp = Double.NaN;

    public PyroclasticFlows(String id, TerrainModel terrain, MassFlowConfig config) {
        super(id, MassFlowKind.PDC, terrain, config);
    }

    public PyroclasticFlows(String id, TerrainModel terrain) {
        this(id, terrain, MassFlowConfig.pdc());
    }

    /**
     * Feeds a PDC from a collapsing eruption column: {@code collapseFraction} of the mass eruption
     * rate falls back around the vent (on the columns within {@code radiusM} metres of it) as dense flow.
     *
     * @return the source id ({@code "collapse:" + sourceId}), for {@link #setRate}/{@link #removeSource}
     */
    public String columnCollapse(String sourceId, Point3 vent, double radiusM, double massEruptionRateKgS,
            double collapseFraction, double temperatureC) {
        List<ColumnIndex> cells = new ArrayList<>();
        ColumnIndex center = vent.column(dx);
        int r = (int) Math.ceil(Math.max(0, radiusM) / dx);
        for (int dz = -r; dz <= r; dz++) {
            for (int ddx = -r; ddx <= r; ddx++) {
                ColumnIndex c = center.offset(ddx, dz);
                double d = Math.hypot((c.x() + 0.5) * dx - vent.x(), (c.z() + 0.5) * dx - vent.z());
                if ((ddx == 0 && dz == 0) || d <= radiusM) cells.add(c);
            }
        }
        String fullId = "collapse:" + sourceId;
        double rate = massEruptionRateKgS * Math.max(0, Math.min(1, collapseFraction)) / DENSE_FLOW_DENSITY;
        addSource(new FlowSource(fullId, cells, rate, temperatureC, 0), Trigger.COLUMN_COLLAPSE);
        return fullId;
    }

    /** Collapse of a lava dome or crater wall: {@code volumeM3} of hot block-and-ash flow released at rest. */
    public boolean domeCollapse(Point3 center, double radiusM, double volumeM3, double temperatureC) {
        return release(center, radiusM, volumeM3, temperatureC, 0, Trigger.DOME_COLLAPSE);
    }

    @Override
    protected double friction(MassFlowChunk c, int i) {
        return config.frictionCoefficient;
    }

    @Override
    protected void process(MassFlowChunk c, int i, double dt, Outbox outbox, double time) {
        double ambient = config.ambientC;
        c.temperature[i] = ambient + (c.temperature[i] - ambient) * StrictMath.exp(-dt / config.coolingTimescale);

        if (submerged(c, i)) {
            double loss = c.depth[i] * (1 - StrictMath.exp(-dt / config.waterLossTimescale));
            if (steamStamp != time) {
                steamStamp = time;
                steamEvents = 0;
            }
            if (loss > 0 && steamEvents < config.maxSteamEventsPerStep) {
                outbox.emit(new MassFlowEvents.PdcSteam(time, id,
                        Point3.columnCentre(c.worldX(i), c.worldZ(i), c.waterLevel[i], dx), loss * cellArea));
                steamEvents++;
            }
            loseFlow(c, i, loss);
            if (c.depth[i] <= 0) return;
        }

        double u = speed(c, i);
        double rate = 1 / config.sedimentationTimescale;
        if (u < config.stopSpeed) rate += (1 - u / config.stopSpeed) / config.stopTimescale;
        double settled = c.depth[i] * (1 - StrictMath.exp(-rate * dt));
        if (c.depth[i] - settled < config.minDepth) settled = c.depth[i];
        depositFlow(c, i, settled, settled * config.depositThicknessFactor, outbox);
    }

    @Override
    protected DepositType depositType() {
        return DepositType.PDC;
    }

    /** Ignimbrite: tuff (welded or not; welded at or above the welding temperature). */
    @Override
    protected Material depositMaterial(double temperatureC, double speed) {
        return MaterialTable.TUFF;
    }

    /** Non-welded ignimbrite is loose: rain and lahars rework it. */
    @Override
    protected int depositFlags(double temperatureC) {
        return temperatureC >= config.weldingTemperatureC ? 0 : LayerFlags.LOOSE;
    }

    /** Welding grows linearly from 0 at {@code 0.6·T_weld} to 1 at the welding temperature. */
    @Override
    protected double depositWelding(double temperatureC) {
        double lo = 0.6 * config.weldingTemperatureC;
        return Math.max(0, Math.min(1, (temperatureC - lo) / (config.weldingTemperatureC - lo)));
    }

    @Override
    protected EngineEvent startedEvent(double time, PendingStart start) {
        return new MassFlowEvents.PdcStarted(time, id, start.trigger(), start.position(), start.volumeM3(),
                start.rateM3PerS(), start.temperatureC());
    }

    @Override
    protected EngineEvent frontEvent(double time, Point3 front, double runoutM, int cells, double volume,
            double maxSpeed, double tracer, List<FlowCell> reported) {
        return new MassFlowEvents.PdcFront(time, id, front, runoutM, cells, volume, maxSpeed, tracer, reported);
    }

    @Override
    protected EngineEvent depositEvent(double time, int cells, double volume) {
        return new MassFlowEvents.PdcDeposit(time, id, cells, volume);
    }
}
