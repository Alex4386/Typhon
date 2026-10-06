package me.alex4386.typhon.engine.alert;

import com.google.gson.JsonObject;
import java.util.EnumMap;
import java.util.Map;
import me.alex4386.typhon.engine.assembly.VentPartition;
import me.alex4386.typhon.engine.assembly.VolcanoCoupler;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.magma.conduit.ConduitSolution;
import me.alex4386.typhon.engine.save.StateReader;
import me.alex4386.typhon.engine.save.StateWriter;
import me.alex4386.typhon.engine.sim.StepContext;
import me.alex4386.typhon.engine.sim.Subsystem;

/**
 * Estimates the eruption style and explosivity from what the simulated eruption does. It is output
 * only: nothing in the simulation reads it.
 *
 * <p>Observables are averaged over a rolling window of {@link #WINDOW_SECONDS} physical seconds: the
 * share of the magma leaving as lava, as a column, as fall-back and fragmented by water; the column's
 * height (Mastin et al. 2009, {@code H = 2.0 Q^0.241} km for DRE flux {@code Q} in m³/s) and the share
 * of it collapsing; the viscosity of the lava at the vent; and the rate of discrete slug bursts and
 * plug failures. Each style gets a soft membership in [0, 1] from these (see {@code
 * docs/eruption-dynamics.md}, "Style estimate"); normalised, they are the style probabilities. The
 * top style is reported, or {@link EruptionStyle#MIXED} when it falls below {@link #MIXED_BELOW}.
 * The VEI follows Newhall &amp; Self (1982) from the eruption's bulk tephra volume so far and its
 * column height.
 *
 * <p>Before an eruption, the same memberships are evaluated on the flow the conduit would carry if
 * it failed now ({@link MagmaChamber#forecastFlow()}): a forecast of the next eruption's style.
 */
public final class EruptionClassifier implements Subsystem {
    /** Physical averaging window of the observables (s). */
    static final double WINDOW_SECONDS = 3600;
    /** Top probability below which the style is reported as {@link EruptionStyle#MIXED}. */
    public static final double MIXED_BELOW = 0.45;
    /** Probability margin a new top style needs over the current one to replace it. */
    static final double SWITCH_MARGIN = 0.1;
    /** Bulk density of fresh tephra (kg/m³) for the VEI volume. */
    static final double TEPHRA_BULK_DENSITY = 1000;
    static final double DRE_DENSITY = 2500;

    private final String volcanoId;
    private final MagmaChamber chamber;
    private final VolcanoCoupler coupler;

    // Rolling (exponentially weighted) observables, physical units.
    private double magmaRate;
    private double lavaRate;
    private double columnRate;
    private double collapseRate;
    private double ballisticRate;
    private double wetRate;
    private double viscosityLog10 = Double.NaN;
    private double slugPerHour;
    private double plugPerHour;
    private double submerged;
    /** Weight the flow window has accumulated this eruption (bias correction of the averages). */
    private double windowWeight;
    private long seenSlugs;
    private long seenPlugs;
    // This eruption.
    private int eruption;
    private double tephraMassKg;
    private double maxColumnKm;
    // Current estimate.
    private EruptionStyle style;
    private int vei = -1;
    private boolean forecast = true;
    private final EnumMap<EruptionStyle, Double> probabilities = new EnumMap<>(EruptionStyle.class);

    public EruptionClassifier(String volcanoId, MagmaChamber chamber, VolcanoCoupler coupler) {
        this.volcanoId = volcanoId;
        this.chamber = chamber;
        this.coupler = coupler;
    }

    @Override
    public String id() {
        return "style:" + volcanoId;
    }

    @Override
    public double periodSeconds() {
        return 1.0;
    }

    /** Current estimate for dashboards. */
    public record Snapshot(EruptionStyle style, Map<EruptionStyle, Double> probabilities, int vei, boolean forecast) {}

    @Override
    public Snapshot snapshot() {
        return new Snapshot(style, Map.copyOf(probabilities), vei, forecast);
    }

    /** Most probable style now, or {@code null} before the first estimate. */
    public EruptionStyle style() {
        return style;
    }

    public int vei() {
        return vei;
    }

    /** True while the estimate is a forecast of the next eruption rather than of an ongoing one. */
    public boolean isForecast() {
        return forecast;
    }

    public Map<EruptionStyle, Double> probabilities() {
        return Map.copyOf(probabilities);
    }

    @Override
    public void step(StepContext context) {
        boolean erupting = chamber.erupting() && chamber.conduitFlow() != null;
        double physicalDt = context.dtSeconds()
                * (erupting ? chamber.config().eruptiveTimeScale() : chamber.config().dormantTimeScale());
        // Slug bursts are surface activity in volcano time (MagmaChamber bursts only the eruptive-time share
        // of percolating gas), so their rate is per hour at the eruptive scale.
        observeBursts(physicalDt, context.dtSeconds() * chamber.config().eruptiveTimeScale());
        // Explosions from an open vent between eruptions are activity in their own right (Stromboli).
        boolean active = erupting || slugPerHour + plugPerHour >= 0.5;
        EnumMap<EruptionStyle, Double> memberships;
        int nextVei;
        if (active) {
            if (erupting) observe(physicalDt);
            else resetFlowWindow();
            double k = windowWeight > 0 ? 1 / windowWeight : 0;
            memberships = memberships(magmaRate * k, lavaRate * k, columnRate * k, collapseRate * k, ballisticRate * k,
                    wetRate * k, viscosityLog10, slugPerHour, plugPerHour, submerged * k);
            nextVei = Math.max(volumeVei(tephraMassKg / TEPHRA_BULK_DENSITY), heightVei(maxColumnKm));
        } else {
            resetFlowWindow();
            ConduitSolution next = chamber.forecastFlow();
            if (next == null) {
                memberships = new EnumMap<>(EruptionStyle.class);
                nextVei = 0;
            } else {
                VentPartition.Result p = VentPartition.partition(next, chamber.ventAmbientPressurePa(),
                        chamber.config().conduitRadius(), chamber.silicaWt(), VentPartition.Water.DRY, null);
                double m = p.magmaMassFlux();
                // Without observed bursts, the tendency to burst follows from the flow: segregating
                // slugs, or gas the plug would trap.
                double slugs = next.segregatedGasFraction() > 0.3 && !next.fragmented() ? 4 : 0;
                double plugs = !next.fragmented() && next.exitMeltViscosityLog10() >= chamber.config().conduit().plugViscosityLog10()
                        ? 0.5 : 0;
                memberships = memberships(m, p.lavaMassFlux(), p.columnMassFlux(), p.columnMassFlux() * p.collapseFraction(),
                        p.ballisticMassFlux(), p.waterFragmentedMassFlux(), next.exitMeltViscosityLog10(), slugs, plugs, 0);
                nextVei = heightVei(columnHeightKm(p.columnMassFlux()));
            }
        }
        publish(context, memberships, nextVei, !active);
    }

    private void observe(double physicalDt) {
        if (chamber.eruptionCount() != eruption) {
            eruption = chamber.eruptionCount();
            tephraMassKg = 0;
            maxColumnKm = 0;
            resetFlowWindow();
        }
        VentPartition.Result p = coupler.partition();
        double w = 1 - Math.exp(-physicalDt / WINDOW_SECONDS);
        double actual = chamber.physicalEruptionRate() * DRE_DENSITY;
        double scale = p != null && p.magmaMassFlux() > 0 ? actual / p.magmaMassFlux() : 0;
        double lava = p == null ? actual : p.lavaMassFlux() * scale;
        double column = p == null ? 0 : p.columnMassFlux() * scale;
        double collapse = p == null ? 0 : column * p.collapseFraction();
        double ballistic = p == null ? 0 : (p.ballisticMassFlux() + p.jetMassFlux()) * scale;
        double wet = p == null ? 0 : p.waterFragmentedMassFlux() * scale;
        windowWeight += w * (1 - windowWeight);
        magmaRate += w * (actual - magmaRate);
        lavaRate += w * (lava - lavaRate);
        columnRate += w * (column - columnRate);
        collapseRate += w * (collapse - collapseRate);
        ballisticRate += w * (ballistic - ballisticRate);
        wetRate += w * (wet - wetRate);
        submerged += w * ((coupler.waterDepthM() > 0 ? 1 : 0) - submerged);
        ConduitSolution flow = chamber.conduitFlow();
        if (flow != null) {
            double eta = flow.exitMeltViscosityLog10();
            viscosityLog10 = Double.isNaN(viscosityLog10) ? eta : viscosityLog10 + w * (eta - viscosityLog10);
        }
        double tephra = column + ballistic + (p == null ? 0 : (p.wetFalloutMassFlux()) * scale);
        tephraMassKg += tephra * physicalDt;
        maxColumnKm = Math.max(maxColumnKm, columnHeightKm(column));
    }

    /** Rates of discrete explosions (per physical hour), averaged over the window. */
    private void observeBursts(double physicalDt, double surfaceDt) {
        long slugs = coupler.slugBursts();
        long plugs = coupler.plugBursts();
        double ws = 1 - Math.exp(-surfaceDt / WINDOW_SECONDS);
        slugPerHour += ws * ((slugs - seenSlugs) / Math.max(surfaceDt / 3600, 1e-9) - slugPerHour);
        double w = 1 - Math.exp(-physicalDt / WINDOW_SECONDS);
        plugPerHour += w * ((plugs - seenPlugs) / Math.max(physicalDt / 3600, 1e-9) - plugPerHour);
        seenSlugs = slugs;
        seenPlugs = plugs;
    }

    private void resetFlowWindow() {
        windowWeight = 0;
        magmaRate = 0;
        lavaRate = 0;
        columnRate = 0;
        collapseRate = 0;
        ballisticRate = 0;
        wetRate = 0;
        viscosityLog10 = Double.NaN;
        submerged = 0;
    }

    /** Column height (km) from its DRE flux (Mastin et al. 2009). */
    static double columnHeightKm(double massFluxKgPerS) {
        double q = massFluxKgPerS / DRE_DENSITY;
        return q > 0 ? 2.0 * Math.pow(q, 0.241) : 0;
    }

    /** Newhall &amp; Self (1982) VEI from bulk tephra volume (m³). */
    static int volumeVei(double bulkM3) {
        if (!(bulkM3 >= 1e4)) return 0;
        if (bulkM3 < 1e6) return 1;
        return (int) Math.min(8, Math.floor(Math.log10(bulkM3)) - 4);
    }

    /** Newhall &amp; Self (1982) VEI lower bound from column height (km). */
    static int heightVei(double km) {
        if (km < 0.1) return 0;
        if (km < 1) return 1;
        if (km < 5) return 2;
        if (km < 15) return 3;
        if (km < 25) return 4;
        return 5;
    }

    private static double logistic(double x) {
        return 1 / (1 + Math.exp(-x));
    }

    /** Membership that rises from 0 to 1 as {@code value} passes {@code centre} (log10 width {@code w}). */
    private static double above(double value, double centre, double w) {
        if (!(value > 0)) return 0;
        return logistic(Math.log10(value / centre) / w);
    }

    /**
     * Soft memberships of each style. All rates are physical (kg/s; bursts per hour).
     *
     * @param viscosityLog10 log10 viscosity (Pa·s) of the magma at the vent
     */
    static EnumMap<EruptionStyle, Double> memberships(double magma, double lava, double column, double collapse,
            double ballistic, double wet, double viscosityLog10, double slugPerHour, double plugPerHour,
            double submerged) {
        EnumMap<EruptionStyle, Double> m = new EnumMap<>(EruptionStyle.class);
        if (!(magma > 0) && slugPerHour <= 0 && plugPerHour <= 0) return m;
        double total = Math.max(magma, 1e-9);
        double lavaShare = Math.min(1, lava / total);
        double columnShare = Math.min(1, column / total);
        double wetShare = Math.min(1, wet / total);
        double eta = Double.isNaN(viscosityLog10) ? 6 : viscosityLog10;
        double fluid = logistic((5.5 - eta) / 0.6);
        double viscous = 1 - fluid;
        double stiff = logistic((eta - 8.5) / 0.5);
        // A sustained column: a large share of the magma lofted at a substantial rate.
        double sustained = above(column, 3e4, 0.3) * logistic((columnShare - 0.4) / 0.08);
        double height = columnHeightKm(column);
        double low = 1 - logistic((height - 9) / 1.5);
        double sub = logistic((height - 9) / 1.5) * (1 - logistic((height - 20) / 2));
        double plinian = logistic((height - 20) / 2);
        double collapsing = column > 0 ? Math.min(1, collapse / column) : 0;
        double pdc = above(collapse, 1e4, 0.3) * logistic((collapsing - 0.4) / 0.1);
        double slugs = 1 - Math.exp(-slugPerHour / 3);
        double plugs = 1 - Math.exp(-plugPerHour * 4);
        double water = logistic((wetShare - 0.25) / 0.08);
        double dry = 1 - 0.85 * water;

        m.put(EruptionStyle.HAWAIIAN, dry * fluid * lavaShare * (1 - sustained) * (1 - 0.7 * slugs));
        m.put(EruptionStyle.STROMBOLIAN, dry * (slugs * (1 - sustained) * logistic((8 - eta) / 0.6)
                + sustained * low * fluid));
        m.put(EruptionStyle.VULCANIAN, dry * (plugs * (1 - sustained) + sustained * low * viscous) * (1 - pdc));
        m.put(EruptionStyle.LAVA_DOME, dry * stiff * lavaShare * (1 - sustained) * (1 - plugs) * (1 - pdc));
        m.put(EruptionStyle.PELEAN, dry * pdc * (1 - plinian));
        m.put(EruptionStyle.SUBPLINIAN, dry * sustained * sub * (1 - 0.5 * pdc));
        m.put(EruptionStyle.PLINIAN, dry * sustained * plinian);
        m.put(EruptionStyle.SURTSEYAN, water * (0.6 + 0.4 * submerged));
        m.put(EruptionStyle.PHREATIC, 0.0);
        return m;
    }

    private void publish(StepContext context, EnumMap<EruptionStyle, Double> memberships, int nextVei,
            boolean isForecast) {
        double sum = 0;
        for (double v : memberships.values()) sum += v;
        EnumMap<EruptionStyle, Double> next = new EnumMap<>(EruptionStyle.class);
        EruptionStyle top = EruptionStyle.MIXED;
        double best = 0;
        for (EruptionStyle s : EruptionStyle.values()) {
            if (s == EruptionStyle.MIXED) continue;
            double p = sum > 1e-12 ? memberships.getOrDefault(s, 0.0) / sum : 0;
            next.put(s, p);
            if (p > best) {
                best = p;
                top = s;
            }
        }
        if (best < MIXED_BELOW) top = EruptionStyle.MIXED;
        // Hysteresis: keep the current style unless the new one is clearly more probable.
        if (style != null && top != style && isForecast == forecast) {
            double current = style == EruptionStyle.MIXED ? MIXED_BELOW : next.getOrDefault(style, 0.0);
            double challenger = top == EruptionStyle.MIXED ? MIXED_BELOW : best;
            if (challenger < current + SWITCH_MARGIN) top = style;
        }
        probabilities.clear();
        probabilities.putAll(next);
        boolean changed = top != style || nextVei != vei || isForecast != forecast;
        if (changed) {
            context.outbox().emit(new AlertEvents.EruptionStyleEstimated(context.time(), volcanoId, style, top,
                    new EnumMap<>(next), nextVei, isForecast));
            style = top;
            vei = nextVei;
            forecast = isForecast;
        }
    }

    @Override
    public void saveState(StateWriter writer) {
        JsonObject out = writer.json();
        out.addProperty("magmaRate", magmaRate);
        out.addProperty("lavaRate", lavaRate);
        out.addProperty("columnRate", columnRate);
        out.addProperty("collapseRate", collapseRate);
        out.addProperty("ballisticRate", ballisticRate);
        out.addProperty("wetRate", wetRate);
        if (!Double.isNaN(viscosityLog10)) out.addProperty("viscosityLog10", viscosityLog10);
        out.addProperty("slugPerHour", slugPerHour);
        out.addProperty("plugPerHour", plugPerHour);
        out.addProperty("submerged", submerged);
        out.addProperty("windowWeight", windowWeight);
        out.addProperty("seenSlugs", seenSlugs);
        out.addProperty("seenPlugs", seenPlugs);
        out.addProperty("eruption", eruption);
        out.addProperty("tephraMassKg", tephraMassKg);
        out.addProperty("maxColumnKm", maxColumnKm);
        if (style != null) out.addProperty("style", style.name());
        out.addProperty("vei", vei);
        out.addProperty("forecast", forecast);
        JsonObject p = new JsonObject();
        for (Map.Entry<EruptionStyle, Double> e : probabilities.entrySet()) p.addProperty(e.getKey().name(), e.getValue());
        out.add("probabilities", p);
    }

    @Override
    public void loadState(StateReader reader) {
        JsonObject in = reader.json();
        magmaRate = in.get("magmaRate").getAsDouble();
        lavaRate = in.get("lavaRate").getAsDouble();
        columnRate = in.get("columnRate").getAsDouble();
        collapseRate = in.get("collapseRate").getAsDouble();
        ballisticRate = in.get("ballisticRate").getAsDouble();
        wetRate = in.get("wetRate").getAsDouble();
        viscosityLog10 = in.has("viscosityLog10") ? in.get("viscosityLog10").getAsDouble() : Double.NaN;
        slugPerHour = in.get("slugPerHour").getAsDouble();
        plugPerHour = in.get("plugPerHour").getAsDouble();
        submerged = in.get("submerged").getAsDouble();
        windowWeight = in.has("windowWeight") ? in.get("windowWeight").getAsDouble() : 1;
        seenSlugs = in.get("seenSlugs").getAsLong();
        seenPlugs = in.get("seenPlugs").getAsLong();
        eruption = in.get("eruption").getAsInt();
        tephraMassKg = in.get("tephraMassKg").getAsDouble();
        maxColumnKm = in.get("maxColumnKm").getAsDouble();
        style = in.has("style") ? EruptionStyle.valueOf(in.get("style").getAsString()) : null;
        vei = in.get("vei").getAsInt();
        forecast = in.get("forecast").getAsBoolean();
        probabilities.clear();
        for (Map.Entry<String, com.google.gson.JsonElement> e : in.getAsJsonObject("probabilities").entrySet()) {
            probabilities.put(EruptionStyle.valueOf(e.getKey()), e.getValue().getAsDouble());
        }
    }
}
