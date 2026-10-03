package me.alex4386.typhon.engine.assembly;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import me.alex4386.typhon.engine.alert.AlertLevelEstimator;
import me.alex4386.typhon.engine.alert.EruptionStyle;
import me.alex4386.typhon.engine.dike.DikePropagation;
import me.alex4386.typhon.engine.geothermal.Geothermal;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.lava.LavaSource;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.massflow.ColumnCollapse;
import me.alex4386.typhon.engine.massflow.PyroclasticFlows;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;
import me.alex4386.typhon.engine.tephra.Ballistics;
import me.alex4386.typhon.engine.tephra.ExplosivePhase;
import me.alex4386.typhon.engine.tephra.TephraSubsystem;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.volcano.VolcanoScaling;

/**
 * Turns the magma chamber's eruption into surface activity.
 *
 * <ul>
 *   <li>Chooses the erupting vents: the summit vents, or the flank fissures a dike opened (a fissure
 *       opening while the chamber is quiet starts a flank eruption at the current pressure).
 *   <li>Splits the erupted volume into lava effusion and/or an explosive phase according to the
 *       eruption style derived from the magma.
 *   <li>Checks the explosive column for collapse (Woods 1988) and feeds the collapsing share into
 *       pyroclastic density currents.
 *   <li>Feeds heat from effusing vents into the geothermal field.
 * </ul>
 *
 * <p>Physics stays in real units: lava, tephra and mass flows all take real rates (m³/s, kg/s) and
 * map them onto the block world themselves (the lava grid is {@link VolcanoScaling#metersPerBlock()}
 * wide per column, set by {@link VolcanoSystem}). Register after the chamber, dikes and alert estimator and before the lava,
 * tephra and mass-flow subsystems so changes apply in the same tick.
 */
public final class VolcanoCoupler implements Subsystem {
    /** Share of the erupted volume that leaves explosively when a style mixes both modes. */
    static final double MIXED_EXPLOSIVE_SHARE = 0.15;
    /** Relative change in explosive rate that restarts the explosive phase with new parameters. */
    static final double PHASE_UPDATE_THRESHOLD = 0.25;
    /** Share of a collapsing column's mass that falls back as pyroclastic flows. */
    static final double COLLAPSE_SHARE = 0.5;

    private final String volcanoId;
    private final MagmaChamber chamber;
    private final AlertLevelEstimator alert;
    private final List<VentSite> baseVents;
    private final DikePropagation dikes;
    private final LavaFlow lava;
    private final TephraSubsystem tephra;
    private final PyroclasticFlows pdc;
    private final Geothermal geothermal;
    private final VolcanoScaling scaling;
    private final double ballisticFraction;

    private final TreeSet<String> activeLavaSources = new TreeSet<>();
    private final Set<String> eruptionVents = new LinkedHashSet<>();
    private int knownFissures;
    private boolean flankPending;
    private double explosiveRate;
    private String collapseSource;

    public VolcanoCoupler(String volcanoId, MagmaChamber chamber, AlertLevelEstimator alert, List<VentSite> vents,
            DikePropagation dikes, LavaFlow lava, TephraSubsystem tephra, PyroclasticFlows pdc, Geothermal geothermal,
            VolcanoScaling scaling, double ballisticFraction) {
        if (vents.isEmpty()) throw new IllegalArgumentException("A volcano needs at least one vent");
        this.volcanoId = volcanoId;
        this.chamber = chamber;
        this.alert = alert;
        this.baseVents = List.copyOf(vents);
        this.dikes = dikes;
        this.lava = lava;
        this.tephra = tephra;
        this.pdc = pdc;
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
        watchFissures();

        double rate = chamber.eruptionRate();
        if (rate <= 0) {
            stopLava();
            stopExplosive();
            if (!flankPending) eruptionVents.clear();
            return;
        }
        flankPending = false;
        if (eruptionVents.isEmpty()) {
            for (VentSite vent : baseVents) eruptionVents.add(vent.id());
        }
        List<VentSite> vents = activeVents();

        EruptionStyle style = alert.suggestedStyle();
        boolean explosive = chamber.fragmented() || isExplosive(style);
        boolean effusive = !chamber.fragmented() && isEffusive(style);
        if (!explosive && !effusive) effusive = true;

        double explosiveShare = explosive ? (effusive ? MIXED_EXPLOSIVE_SHARE : 1.0) : 0.0;

        if (effusive) {
            updateLava(vents, rate * (1 - explosiveShare));
        } else {
            stopLava();
        }

        if (explosive) {
            updateExplosive(vents.get(0), rate * explosiveShare);
        } else {
            stopExplosive();
        }
    }

    /** Picks up fissures opened by dikes since the last step. */
    private void watchFissures() {
        if (dikes == null) return;
        List<VentSite> opened = dikes.openedVents();
        if (opened.size() <= knownFissures) return;
        for (VentSite vent : opened.subList(knownFissures, opened.size())) {
            if (!chamber.erupting()) {
                // A fresh flank eruption is fed through the new fissures, not the summit.
                if (!flankPending) eruptionVents.clear();
                flankPending = true;
                chamber.requestFlankEruption();
            }
            eruptionVents.add(vent.id());
        }
        knownFissures = opened.size();
    }

    /** All vents of this volcano: the configured ones plus fissures opened by dikes. */
    public List<VentSite> allVents() {
        List<VentSite> all = new ArrayList<>(baseVents);
        if (dikes != null) all.addAll(dikes.openedVents());
        return all;
    }

    /** Vents currently erupting (or about to). */
    public List<VentSite> activeVents() {
        List<VentSite> active = new ArrayList<>();
        for (VentSite vent : allVents()) {
            if (eruptionVents.contains(vent.id())) active.add(vent);
        }
        return active;
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

    private void updateLava(List<VentSite> vents, double realRate) {
        double perVent = realRate / vents.size();
        Set<String> wanted = new TreeSet<>();
        for (VentSite vent : vents) {
            String sourceId = sourceId(vent);
            wanted.add(sourceId);
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
        for (String sourceId : new ArrayList<>(activeLavaSources)) {
            if (!wanted.contains(sourceId)) {
                lava.removeSource(sourceId);
                activeLavaSources.remove(sourceId);
            }
        }
    }

    private void stopLava() {
        for (String sourceId : activeLavaSources) lava.removeSource(sourceId);
        activeLavaSources.clear();
    }

    private void updateExplosive(VentSite vent, double realRate) {
        boolean restart = explosiveRate <= 0
                || Math.abs(realRate - explosiveRate) > PHASE_UPDATE_THRESHOLD * explosiveRate;
        if (!restart) return;
        explosiveRate = realRate;

        ExplosivePhase base = ExplosivePhase.fromMagma(vent, chamber, ballisticFraction);
        double mass = realRate * ExplosivePhase.DRE_DENSITY;
        double collapsing = collapseShare(base, mass);
        tephra.startPhase(new ExplosivePhase(base.vent(), mass * (1 - collapsing), base.gasFraction(),
                base.overpressureMPa(), base.temperatureC(), base.silicaWt(), base.ballisticFraction(), base.grainSize()));
        updateCollapse(vent, mass * collapsing, base.temperatureC());
    }

    /** Share of the column falling back as PDCs: {@link #COLLAPSE_SHARE} if the column is unstable. */
    private double collapseShare(ExplosivePhase phase, double massRate) {
        if (pdc == null || massRate <= 0) return 0;
        double gas = phase.gasFraction();
        if (!(gas > 0 && gas < 1)) return COLLAPSE_SHARE; // no gas thrust: the jet cannot become buoyant
        double exitSpeed = Ballistics.gasThrustExitSpeed(gas, phase.temperatureC(), phase.overpressureMPa());
        if (!(exitSpeed > 0)) return COLLAPSE_SHARE;
        return ColumnCollapse.analyze(massRate, exitSpeed, gas, phase.temperatureC()).collapses() ? COLLAPSE_SHARE : 0;
    }

    private void updateCollapse(VentSite vent, double massRate, double temperatureC) {
        if (pdc == null) return;
        if (collapseSource != null) {
            pdc.removeSource(collapseSource);
            collapseSource = null;
        }
        if (massRate > 0) {
            collapseSource = pdc.columnCollapse(volcanoId, vent.position(), Math.max(1, vent.craterRadius()), massRate,
                    1.0, temperatureC);
        }
    }

    private void stopExplosive() {
        if (explosiveRate > 0) tephra.stopPhase();
        explosiveRate = 0;
        if (collapseSource != null && pdc != null) pdc.removeSource(collapseSource);
        collapseSource = null;
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

    public boolean columnCollapsing() {
        return collapseSource != null;
    }

    @Override
    public void saveState(JsonObject out) {
        JsonArray sources = new JsonArray();
        activeLavaSources.forEach(sources::add);
        out.add("lavaSources", sources);
        JsonArray vents = new JsonArray();
        eruptionVents.forEach(vents::add);
        out.add("eruptionVents", vents);
        out.addProperty("knownFissures", knownFissures);
        out.addProperty("flankPending", flankPending);
        out.addProperty("explosiveRate", explosiveRate);
        if (collapseSource != null) out.addProperty("collapseSource", collapseSource);
    }

    @Override
    public void loadState(JsonObject in) {
        activeLavaSources.clear();
        for (JsonElement e : in.getAsJsonArray("lavaSources")) activeLavaSources.add(e.getAsString());
        eruptionVents.clear();
        if (in.has("eruptionVents")) {
            for (JsonElement e : in.getAsJsonArray("eruptionVents")) eruptionVents.add(e.getAsString());
        }
        knownFissures = in.has("knownFissures") ? in.get("knownFissures").getAsInt() : 0;
        flankPending = in.has("flankPending") && in.get("flankPending").getAsBoolean();
        explosiveRate = in.get("explosiveRate").getAsDouble();
        collapseSource = in.has("collapseSource") ? in.get("collapseSource").getAsString() : null;
    }
}
