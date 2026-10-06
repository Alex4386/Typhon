package me.alex4386.typhon.engine.expansion;

import java.util.Arrays;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.massflow.MassFlowField;
import me.alex4386.typhon.engine.subsurface.Subsurface;
import me.alex4386.typhon.engine.tephra.TephraSubsystem;

/** Connects a {@link WorldExpansion} to the subsystems whose activity drives it. */
public final class ExpansionWiring {
    private ExpansionWiring() {}

    /**
     * Registers what drives on-demand growth — lava, pyroclastic flows, lahars and debris avalanches,
     * rising dikes, slope failures, thick tephra and running water — and lays down tephra that fell on
     * unsimulated ground when it materialises. Volcanoes are wired in iteration order.
     */
    public static void wire(WorldExpansion expansion, LavaFlow lava, Subsurface subsurface,
            Iterable<VolcanoSystem> volcanoes) {
        if (lava != null) expansion.addSource(lava::reportActivity);
        if (subsurface != null) {
            expansion.addSource(sink -> subsurface.reportMovingWater(expansion.expansionConfig().waterThresholdM(), sink));
        }
        for (VolcanoSystem v : volcanoes) {
            for (MassFlowField f : Arrays.asList(v.pyroclasticFlows(), v.lahars(), v.debrisAvalanches())) {
                if (f != null) expansion.addSource(f::reportActivity);
            }
            if (v.dikes() != null) expansion.addSource(v.dikes()::reportActivity);
            if (v.geomorphology() != null) expansion.addSource(v.geomorphology()::reportActivity);
            TephraSubsystem t = v.tephra();
            if (t != null) {
                expansion.addSource(sink -> t.reportDeposits(expansion.expansionConfig().ashThresholdM(), sink));
                expansion.addListener(t::backfill);
            }
        }
    }
}
