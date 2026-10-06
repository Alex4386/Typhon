package me.alex4386.typhon.engine.assembly;

import java.util.function.DoubleBinaryOperator;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.save.FieldChunk;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.world.SurfaceDetail;
import me.alex4386.typhon.engine.world.SurfaceDetailConfig;
import me.alex4386.typhon.engine.world.WorldModel;

/**
 * Keeps a volcano's {@link SurfaceDetail} (the crater-resolving fine surface around its primary
 * vent) in step with the world model: once a second it redistributes column changes over the
 * detail cells. Reconciling is path-independent, so the period only sets how stale readers' views
 * may be (they add any pending change evenly).
 */
public final class VolcanoDetail implements Subsystem {
    static final int SCHEMA = 1;

    private final String volcanoId;
    private final SurfaceDetailConfig config;
    private final SurfaceDetail detail;

    VolcanoDetail(String volcanoId, SurfaceDetailConfig config, WorldModel world, BlockPos vent,
            DoubleBinaryOperator relief) {
        this.volcanoId = volcanoId;
        this.config = config;
        double size = world.spec().metersPerColumn();
        this.detail = SurfaceDetail.around(world, vent.x(), vent.z(), config.radiusColumns(size), config.refinement(size),
                relief);
    }

    public SurfaceDetail detail() {
        return detail;
    }

    @Override
    public String id() {
        return "detail:" + volcanoId;
    }

    @Override
    public double periodSeconds() {
        return 1;
    }

    @Override
    public Object config() {
        return config;
    }

    @Override
    public void step(StepContext context) {
        detail.reconcileAll();
    }

    @Override
    public void saveState(StateWriter out) {
        out.field("detail", SCHEMA).put(0, 0, new FieldChunk()
                .floats("cells", detail.cells().clone())
                .doubles("seen", detail.seenSurfaces().clone())
                .ints("version", detail.seenVersions().clone()));
    }

    @Override
    public void loadState(StateReader in) {
        StateReader.Field field = in.field("detail");
        if (field == null) return;
        for (StateReader.Entry entry : field.chunks()) {
            FieldChunk data = entry.data();
            detail.restore(data.floats("cells"), data.doubles("seen"), data.ints("version"));
        }
    }
}
