package me.alex4386.typhon.engine.massflow;

import java.util.List;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.FlowCell;
import me.alex4386.typhon.engine.massflow.MassFlowEvents.Trigger;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.math.ColumnIndex;
import me.alex4386.typhon.engine.math.Point3;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.output.Outbox;
import me.alex4386.typhon.engine.terrain.TerrainModel;
import me.alex4386.typhon.engine.world.BlockState;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.LayerFlags;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialTable;

/**
 * Debris avalanches: dry rock debris released by a large slope failure (flank or crater-wall
 * collapse), run on the shared Voellmy solver (see {@link MassFlowField}).
 *
 * <p>The flow is the fragmented failed mass at its bulk (deposit) density. It settles with
 * {@code r = 1/τ_sed + max(0, 1 − u/u_stop)/τ_stop}, i.e. it freezes en masse as it decelerates, and
 * leaves a loose {@link MaterialTable#DEBRIS} deposit of the same thickness. Saturated failures run
 * as lahars and hot ones (a collapsing lava dome) as pyroclastic flows instead; the slope model
 * decides which (see {@code geomorph.Geomorphology}).
 */
public final class DebrisAvalanches extends MassFlowField {
    /** Default subsystem id for a volcano's debris-avalanche field. */
    public static String defaultId(String volcanoId) {
        return "avalanche:" + volcanoId;
    }

    public DebrisAvalanches(String id, TerrainModel terrain, MassFlowConfig config) {
        super(id, MassFlowKind.DEBRIS_AVALANCHE, terrain, config);
    }

    public DebrisAvalanches(String id, TerrainModel terrain) {
        this(id, terrain, MassFlowConfig.debrisAvalanche());
    }

    /** Releases {@code volumeM3} of failed debris at rest within {@code radius} blocks of {@code center}. */
    public boolean collapse(ColumnIndex center, int radius, double volumeM3) {
        return release(center, radius, volumeM3, config.ambientC, 0, Trigger.SLOPE_FAILURE);
    }

    @Override
    protected double friction(MassFlowChunk c, int i) {
        return config.frictionCoefficient;
    }

    @Override
    protected void process(MassFlowChunk c, int i, double dt, Outbox outbox, double time) {
        double u = speed(c, i);
        double rate = 1 / config.sedimentationTimescale;
        if (u < config.stopSpeed) rate += (1 - u / config.stopSpeed) / config.stopTimescale;
        double settled = c.depth[i] * (1 - StrictMath.exp(-rate * dt));
        if (c.depth[i] - settled < config.minDepth) settled = c.depth[i];
        depositFlow(c, i, settled, settled * config.depositThicknessFactor, outbox);
    }

    @Override
    protected BlockState depositBlock(MassFlowChunk c, int i, double meanTemperatureC, double meanSpeed) {
        return MassFlowPalette.DEBRIS;
    }

    @Override
    protected BlockState veneer(int tier) {
        return MassFlowPalette.DEBRIS_VENEER;
    }

    @Override
    protected DepositType depositType() {
        return DepositType.DEBRIS_AVALANCHE;
    }

    @Override
    protected Material depositMaterial(double temperatureC, double speed) {
        return MaterialTable.DEBRIS;
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
    protected EngineEvent startedEvent(double time, PendingStart start) {
        return new MassFlowEvents.AvalancheStarted(time, id, start.trigger(), Point3.ofBlock(start.position(), dx), start.volumeM3());
    }

    @Override
    protected EngineEvent frontEvent(double time, Point3 front, double runoutM, int cells, double volume,
            double maxSpeed, double tracer, List<FlowCell> reported) {
        return new MassFlowEvents.AvalancheFront(time, id, front, runoutM, cells, volume, maxSpeed, reported);
    }

    @Override
    protected EngineEvent depositEvent(double time, int cells, double volume, int blocks) {
        return new MassFlowEvents.AvalancheDeposit(time, id, cells, volume, blocks);
    }
}
