package me.alex4386.typhon.engine.assembly;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.alex4386.typhon.engine.random.SimRandom;

/**
 * Thermal life of a dike-fed fissure's feeder, after Bruce &amp; Huppert (1989, 1990) and Wylie et
 * al. (1999).
 *
 * <p>The fissure is split into segments along strike, each a planar slot of width {@code w}. The
 * erupted flux shares out by the slots' hydraulic conductance (∝ w³, same driving gradient), so wider
 * segments carry disproportionately more magma. Per unit wall area each segment gains the heat its
 * flow advects past the walls over the dike height {@code H},
 *
 * <pre>  F_in = ρ c q (T_m − T_s) / (2H),   q = flux per unit strike length,</pre>
 *
 * and loses heat into the cold wall rock by conduction into a half-space,
 *
 * <pre>  F_out = k (T_s − T_r) / √(π κ t),   t = time since the fissure opened.</pre>
 *
 * When {@code F_out > F_in} magma freezes onto both walls ({@code dw/dt = −2 (F_out − F_in)/(ρ L)});
 * when {@code F_in > F_out} the flow melts the walls back ({@code dw/dt = 2 (F_in − F_out)/(ρ (L +
 * c (T_s − T_r)))}). Because the share of flux grows as w³, narrow segments starve and freeze while
 * the wide ones hold open: the eruption localises to a few vents along the fissure, and once the
 * total flux falls the last segments freeze too. A segment narrower than {@link #FREEZE_WIDTH_M} is
 * solid. Bruce &amp; Huppert give the regimes (blocked, localised, widening); the conductance-weighted
 * sharing is the mechanism Wylie et al. identify for flow localisation.
 *
 * <p>References: Bruce, P. M. &amp; Huppert, H. E. (1989) Thermal control of basaltic fissure
 * eruptions. Nature 342, 665–667. Bruce &amp; Huppert (1990) Solidification and melting along dykes
 * by the laminar flow of basaltic magma, in Magma Transport and Storage (Ryan, ed.), Wiley.
 * Wylie, J. J., Helfrich, K. R., Dade, B., Lister, J. R. &amp; Salzig, J. F. (1999) Flow localization
 * in fissure eruptions. Bull. Volcanol. 60, 432–440. Delaney, P. T. &amp; Pollard, D. D. (1982)
 * Solidification of basaltic magma during flow in a dike. Am. J. Sci. 282, 856–885.
 */
final class FissureFeeder {
    /** Magma density (kg/m³), specific heat (J/kg/K) and latent heat of crystallisation (J/kg). */
    static final double RHO = 2700;
    static final double HEAT_CAPACITY = 1200;
    static final double LATENT_HEAT = 4.0e5;
    /** Wall rock conductivity (W/m/K) and diffusivity (m²/s). */
    static final double ROCK_CONDUCTIVITY = 2.5;
    static final double ROCK_DIFFUSIVITY = 1.0e-6;
    /** Host-rock temperature (°C) of the shallow crust a fissure feeder cuts through. */
    static final double WALL_ROCK_C = 200;
    /** Below this width (m) a segment is solid. */
    static final double FREEZE_WIDTH_M = 0.01;
    /** Widening is bounded: thermal erosion of a dike rarely exceeds a few times its initial width. */
    static final double MAX_WIDENING = 4;
    /** Relative spread of initial segment widths (fissures open unevenly along strike). */
    static final double WIDTH_SPREAD = 0.2;
    /** Largest relative width change per integration sub-step. */
    static final double MAX_STEP_CHANGE = 0.05;
    /** A feeder narrowed below this share of its initial conductance, and still narrowing, is waning. */
    static final double WANING_CONDUCTANCE = 0.5;

    final double[] width;
    final double initialWidth;
    final double segmentLengthM;
    final double heightM;
    /** Conductance when the fissure opened. */
    double initialConductance;
    /** Physical seconds since the fissure opened. */
    double age;
    /** Physical flux through this fissure during the last update (m³/s). */
    double flux;
    /** Rate of change (m/s) of the widest open segment during the last update. */
    double widestRate;

    private FissureFeeder(double[] width, double initialWidth, double segmentLengthM, double heightM) {
        this.width = width;
        this.initialWidth = initialWidth;
        this.segmentLengthM = segmentLengthM;
        this.heightM = heightM;
        this.initialConductance = conductance();
    }

    /**
     * A freshly opened feeder.
     *
     * @param openingM dike opening at the surface (m)
     * @param lengthM fissure length (m)
     * @param heightM dike height from the chamber to the surface (m)
     */
    static FissureFeeder open(double openingM, double lengthM, double heightM, int segments, SimRandom random) {
        double w0 = Math.max(4 * FREEZE_WIDTH_M, openingM);
        double[] width = new double[Math.max(1, segments)];
        for (int i = 0; i < width.length; i++) {
            width[i] = w0 * Math.max(0.3, 1 + WIDTH_SPREAD * random.nextGaussian());
        }
        return new FissureFeeder(width, w0, Math.max(1, lengthM) / width.length, Math.max(100, heightM));
    }

    /** Solidus (°C) of magma with {@code silicaWt}, as in the lava rheology. */
    static double solidusC(double silicaWt) {
        double s = Math.max(45, Math.min(77, silicaWt)) - 50;
        return 1000 - 13.6 * s;
    }

    /** Hydraulic conductance of the open segments relative to a slot: Σ w³ ℓ / 12 (m⁴). */
    double conductance() {
        double c = 0;
        for (double w : width) if (w > FREEZE_WIDTH_M) c += w * w * w * segmentLengthM / 12;
        return c;
    }

    int openSegments() {
        int n = 0;
        for (double w : width) if (w > FREEZE_WIDTH_M) n++;
        return n;
    }

    boolean frozen() {
        return openSegments() == 0;
    }

    /** Still open, but narrowed well below its initial conductance and narrowing further. */
    boolean waning() {
        return !frozen() && widestRate < 0 && conductance() < WANING_CONDUCTANCE * initialConductance;
    }

    double widestWidth() {
        double max = 0;
        for (double w : width) if (w > FREEZE_WIDTH_M) max = Math.max(max, w);
        return max;
    }

    /**
     * Advances the feeder by {@code dt} physical seconds carrying {@code flux} m³/s of magma at
     * {@code magmaC} °C.
     */
    void advance(double dt, double flux, double magmaC, double silicaWt) {
        this.flux = flux;
        double solidus = solidusC(silicaWt);
        double superheat = Math.max(0, magmaC - solidus);
        double wallGap = Math.max(1, solidus - WALL_ROCK_C);
        double before = widestWidth();
        double remaining = dt;
        while (remaining > 0 && !frozen()) {
            double sum = 0;
            for (double w : width) if (w > FREEZE_WIDTH_M) sum += w * w * w;
            double tAge = Math.max(1, age);
            double loss = ROCK_CONDUCTIVITY * wallGap / Math.sqrt(Math.PI * ROCK_DIFFUSIVITY * tAge);
            // Largest sub-step that changes no open segment by more than MAX_STEP_CHANGE of its width.
            double step = remaining;
            double[] rate = new double[width.length];
            for (int i = 0; i < width.length; i++) {
                double w = width[i];
                if (w <= FREEZE_WIDTH_M) continue;
                double share = sum > 0 ? w * w * w / sum : 0;
                double perLength = flux * share / segmentLengthM;
                double gain = RHO * HEAT_CAPACITY * perLength * superheat / (2 * heightM);
                double net = gain - loss;
                rate[i] = net >= 0
                        ? 2 * net / (RHO * (LATENT_HEAT + HEAT_CAPACITY * wallGap))
                        : 2 * net / (RHO * LATENT_HEAT);
                if (rate[i] != 0) step = Math.min(step, MAX_STEP_CHANGE * w / Math.abs(rate[i]));
            }
            step = Math.max(step, Math.min(remaining, 1e-3));
            for (int i = 0; i < width.length; i++) {
                if (width[i] <= FREEZE_WIDTH_M) continue;
                double w = width[i] + rate[i] * step;
                width[i] = w <= FREEZE_WIDTH_M ? 0 : Math.min(w, MAX_WIDENING * initialWidth);
            }
            age += step;
            remaining -= step;
        }
        if (frozen()) age += remaining;
        widestRate = dt > 0 ? (widestWidth() - before) / dt : 0;
    }

    JsonObject save() {
        JsonObject o = new JsonObject();
        JsonArray w = new JsonArray();
        for (double v : width) w.add(v);
        o.add("width", w);
        o.addProperty("initialWidth", initialWidth);
        o.addProperty("initialConductance", initialConductance);
        o.addProperty("segmentLengthM", segmentLengthM);
        o.addProperty("heightM", heightM);
        o.addProperty("age", age);
        o.addProperty("flux", flux);
        o.addProperty("widestRate", widestRate);
        return o;
    }

    static FissureFeeder load(JsonObject o) {
        JsonArray a = o.getAsJsonArray("width");
        double[] width = new double[a.size()];
        for (int i = 0; i < width.length; i++) width[i] = a.get(i).getAsDouble();
        FissureFeeder f = new FissureFeeder(width, o.get("initialWidth").getAsDouble(),
                o.get("segmentLengthM").getAsDouble(), o.get("heightM").getAsDouble());
        f.initialConductance = o.get("initialConductance").getAsDouble();
        f.age = o.get("age").getAsDouble();
        f.flux = o.get("flux").getAsDouble();
        f.widestRate = o.get("widestRate").getAsDouble();
        return f;
    }
}
