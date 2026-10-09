package me.alex4386.typhon.engine.assembly;

import me.alex4386.typhon.engine.magma.MeltDensity;
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
    /** Specific heat (J/kg/K) and latent heat of crystallisation (J/kg) of the magma. */
    static final double HEAT_CAPACITY = 1200;
    static final double LATENT_HEAT = 4.0e5;
    /** Wall rock conductivity (W/m/K) and diffusivity (m²/s). */
    static final double ROCK_CONDUCTIVITY = 2.5;
    static final double ROCK_DIFFUSIVITY = 1.0e-6;
    /**
     * Numerical: a segment this narrow (m) is counted solid. Its walls close the last millimetre within
     * minutes, so the value does not change when a segment freezes.
     */
    static final double FREEZE_WIDTH_M = 1e-3;
    /**
     * Log-normal spread of the segments' opening about the elastic profile: the crust's stiffness and the
     * stress on the dike vary along strike, so real dikes open unevenly (their opening varies by tens of per
     * cent between outcrops; Delaney &amp; Pollard 1981, USGS PP 1202). An initial condition, not a switch.
     */
    static final double WIDTH_SPREAD = 0.2;
    /** Largest relative width change per integration sub-step. */
    static final double MAX_STEP_CHANGE = 0.05;

    final double[] width;
    final double segmentLengthM;
    final double heightM;
    /** Mean temperature (°C) of the host rock along the dike's height, from the geotherm. */
    final double wallRockC;
    /** Conductance when the fissure opened. */
    double initialConductance;
    /** Physical seconds since the fissure opened. */
    double age;
    /** Physical flux through this fissure during the last update (m³/s). */
    double flux;
    /** Rate of change (m/s) of the widest open segment during the last update. */
    double widestRate;
    /** Flux through this fissure during the update before the last (m³/s). */
    double previousFlux;

    private FissureFeeder(double[] width, double segmentLengthM, double heightM, double wallRockC) {
        this.width = width;
        this.segmentLengthM = segmentLengthM;
        this.heightM = heightM;
        this.wallRockC = wallRockC;
        this.initialConductance = conductance();
    }

    /**
     * A freshly opened feeder. A dike opens as an elastic crack, widest at its centre and closing to its tips,
     * {@code w(x) = w₀ √(1 − (2x/L)²)} (Pollard 1987), each segment around it by {@link #WIDTH_SPREAD}: the
     * tips freeze first and the eruption draws in toward the centre, as fissure eruptions do (Holuhraun
     * 2014, Krafla 1975–84).
     *
     * @param openingM the dike's opening at the surface, at the fissure's centre (m)
     * @param lengthM fissure length (m)
     * @param heightM dike height from the chamber to the surface (m)
     * @param wallRockC mean host-rock temperature along the dike (°C)
     */
    static FissureFeeder open(double openingM, double lengthM, double heightM, double wallRockC, int segments,
            SimRandom random) {
        double[] width = new double[Math.max(1, segments)];
        for (int i = 0; i < width.length; i++) {
            double x = width.length == 1 ? 0 : 2 * (i + 0.5) / width.length - 1;
            double profile = Math.sqrt(1 - x * x);
            double spread = Math.exp(WIDTH_SPREAD * random.nextGaussian() - WIDTH_SPREAD * WIDTH_SPREAD / 2);
            width[i] = Math.max(0, openingM) * profile * spread;
        }
        return new FissureFeeder(width, Math.max(1, lengthM) / width.length, Math.max(1, heightM), wallRockC);
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

    /**
     * Still open, but its flow is falling and even its widest segment is narrowing: the eruption through it is
     * dying down. (Narrow segments freezing while the widest holds is localisation; every segment narrowing
     * at the start, as chilled margins grow, is not waning while the flow still rises.)
     */
    boolean waning() {
        return !frozen() && widestRate < 0 && flux < previousFlux;
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
        this.previousFlux = this.flux;
        this.flux = flux;
        double solidus = solidusC(silicaWt);
        // the melt degassed on its way up the fissure: its anhydrous density
        double rho = MeltDensity.anhydrousKgPerM3(silicaWt);
        double superheat = Math.max(0, magmaC - solidus);
        double wallGap = Math.max(1, solidus - wallRockC);
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
                double gain = rho * HEAT_CAPACITY * perLength * superheat / (2 * heightM);
                double net = gain - loss;
                rate[i] = net >= 0
                        ? 2 * net / (rho * (LATENT_HEAT + HEAT_CAPACITY * wallGap))
                        : 2 * net / (rho * LATENT_HEAT);
                if (rate[i] != 0) step = Math.min(step, MAX_STEP_CHANGE * w / Math.abs(rate[i]));
            }
            step = Math.max(step, Math.min(remaining, 1e-3));
            for (int i = 0; i < width.length; i++) {
                if (width[i] <= FREEZE_WIDTH_M) continue;
                double w = width[i] + rate[i] * step;
                width[i] = w <= FREEZE_WIDTH_M ? 0 : w;
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
        o.addProperty("initialConductance", initialConductance);
        o.addProperty("segmentLengthM", segmentLengthM);
        o.addProperty("heightM", heightM);
        o.addProperty("wallRockC", wallRockC);
        o.addProperty("age", age);
        o.addProperty("flux", flux);
        o.addProperty("widestRate", widestRate);
        o.addProperty("previousFlux", previousFlux);
        return o;
    }

    static FissureFeeder load(JsonObject o) {
        JsonArray a = o.getAsJsonArray("width");
        double[] width = new double[a.size()];
        for (int i = 0; i < width.length; i++) width[i] = a.get(i).getAsDouble();
        FissureFeeder f = new FissureFeeder(width, o.get("segmentLengthM").getAsDouble(), o.get("heightM").getAsDouble(), o.get("wallRockC").getAsDouble());
        f.initialConductance = o.get("initialConductance").getAsDouble();
        f.age = o.get("age").getAsDouble();
        f.flux = o.get("flux").getAsDouble();
        f.widestRate = o.get("widestRate").getAsDouble();
        f.previousFlux = o.has("previousFlux") ? o.get("previousFlux").getAsDouble() : f.flux;
        return f;
    }
}
