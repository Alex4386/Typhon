package me.alex4386.typhon.engine.assembly;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.TreeSet;
import me.alex4386.typhon.engine.alert.AlertLevelEstimator;
import me.alex4386.typhon.engine.alert.EruptionStyle;
import me.alex4386.typhon.engine.geothermal.Geothermal;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.lava.LavaSource;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.tephra.ExplosivePhase;
import me.alex4386.typhon.engine.tephra.TephraSubsystem;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;

/**
 * Turns the magma chamber's eruption into surface activity: lava effusion at the vents and/or an
 * explosive phase, chosen from the eruption style the alert estimator derives from the magma.
 *
 * <p>The erupted volume is real (m³/s DRE); it reaches the model world through
 * {@link VolcanoScaling#volumeScale()}. Register after the chamber and alert estimator and before
 * the lava and tephra subsystems so changes apply in the same tick.
 */
public final class VolcanoCoupler implements Subsystem {
    /** Share of the erupted volume that leaves explosively when a style mixes both modes. */
    static final double MIXED_EXPLOSIVE_SHARE = 0.15;
    /** Relative change in explosive rate that restarts the explosive phase with new parameters. */
    static final double PHASE_UPDATE_THRESHOLD = 0.25;

    private final String volcanoId;
    private final MagmaChamber chamber;
    private final AlertLevelEstimator alert;
    private final List<VentSite> vents;
    private final LavaFlow lava;
    private final TephraSubsystem tephra;
    private final Geothermal geothermal;
    private final VolcanoScaling scaling;
    private final double ballisticFraction;

    private final TreeSet<String> activeLavaSources = new TreeSet<>();
    private double explosiveRate;

    public VolcanoCoupler(String volcanoId, MagmaChamber chamber, AlertLevelEstimator alert, List<VentSite> vents,
            LavaFlow lava, TephraSubsystem tephra, Geothermal geothermal, VolcanoScaling scaling,
            double ballisticFraction) {
        if (vents.isEmpty()) throw new IllegalArgumentException("A volcano needs at least one vent");
        this.volcanoId = volcanoId;
        this.chamber = chamber;
        this.alert = alert;
        this.vents = List.copyOf(vents);
        this.lava = lava;
        this.tephra = tephra;
        this.geothermal = geothermal;
        this.scaling = scaling;
        this.ballisticFraction = ballisticFraction;
    }

    @Override
    public String id() {
        return "coupler:" + volcanoId;
    }

    @Override
    public int interval() {
        return 20;
    }

    @Override
    public void step(StepContext context) {
        double rate = chamber.eruptionRate();
        if (rate <= 0) {
            stopLava();
            stopExplosive();
            return;
        }

        EruptionStyle style = alert.suggestedStyle();
        boolean explosive = chamber.fragmented() || isExplosive(style);
        boolean effusive = !chamber.fragmented() && isEffusive(style);
        if (!explosive && !effusive) effusive = true;

        double explosiveShare = explosive ? (effusive ? MIXED_EXPLOSIVE_SHARE : 1.0) : 0.0;

        if (effusive) {
            updateLava(rate * (1 - explosiveShare));
        } else {
            stopLava();
        }

        if (explosive) {
            updateExplosive(rate * explosiveShare);
        } else {
            stopExplosive();
        }
    }

    static boolean isEffusive(EruptionStyle style) {
        return switch (style) {
            case HAWAIIAN, STROMBOLIAN, LAVA_DOME, PELEAN -> true;
            case VULCANIAN, PLINIAN -> false;
        };
    }

    static boolean isExplosive(EruptionStyle style) {
        return switch (style) {
            case STROMBOLIAN, VULCANIAN, PELEAN, PLINIAN -> true;
            case HAWAIIAN, LAVA_DOME -> false;
        };
    }

    private void updateLava(double realRate) {
        double perVent = realRate * scaling.volumeScale() / vents.size();
        for (VentSite vent : vents) {
            String sourceId = sourceId(vent);
            if (activeLavaSources.add(sourceId)) {
                lava.addSource(LavaSource.atVent(vent, perVent, chamber.temperatureC(), chamber.silicaWt(), chamber.waterWt())
                        .withId(sourceId));
            } else {
                lava.setRate(sourceId, perVent);
            }
            if (geothermal != null) {
                int x = vent.position().x();
                int z = vent.position().z();
                geothermal.addLavaHeat(x, z, chamber.temperatureC(), Math.max(1.0, lava.thickness(x, z)));
            }
        }
    }

    private void stopLava() {
        for (String sourceId : activeLavaSources) lava.removeSource(sourceId);
        activeLavaSources.clear();
    }

    private void updateExplosive(double realRate) {
        boolean restart = explosiveRate <= 0
                || Math.abs(realRate - explosiveRate) > PHASE_UPDATE_THRESHOLD * explosiveRate;
        if (!restart) return;
        ExplosivePhase base = ExplosivePhase.fromMagma(vents.get(0), chamber, ballisticFraction);
        tephra.startPhase(new ExplosivePhase(base.vent(), realRate * ExplosivePhase.DRE_DENSITY, base.gasFraction(),
                base.overpressureMPa(), base.temperatureC(), base.silicaWt(), base.ballisticFraction(), base.grainSize()));
        explosiveRate = realRate;
    }

    private void stopExplosive() {
        if (explosiveRate > 0) tephra.stopPhase();
        explosiveRate = 0;
    }

    private String sourceId(VentSite vent) {
        return volcanoId + "/" + vent.id();
    }

    public boolean effusing() {
        return !activeLavaSources.isEmpty();
    }

    public boolean explosive() {
        return explosiveRate > 0;
    }

    @Override
    public void saveState(JsonObject out) {
        JsonArray sources = new JsonArray();
        activeLavaSources.forEach(sources::add);
        out.add("lavaSources", sources);
        out.addProperty("explosiveRate", explosiveRate);
    }

    @Override
    public void loadState(JsonObject in) {
        activeLavaSources.clear();
        for (JsonElement e : in.getAsJsonArray("lavaSources")) activeLavaSources.add(e.getAsString());
        explosiveRate = in.get("explosiveRate").getAsDouble();
    }
}
