package me.alex4386.typhon.engine.assembly;

import com.google.gson.JsonObject;
import me.alex4386.typhon.engine.magma.CylinderConduction;
import me.alex4386.typhon.engine.magma.MeltDensity;

/**
 * The cylindrical throat of a vent a fissure's flow localised into: the same heat balance as the fissure's
 * segments ({@link FissureFeeder}), on a pipe. Magma flowing up at {@code Q} brings its superheat to the wall,
 * {@code F_in = ρ c Q (T_m − T_s) / (2π a H)} per unit wall area over the throat's height {@code H}; the wall
 * rock conducts {@code F_out = (k (T_s − T_r) / a) f(κ t / a²)} away, the flux out of a cylinder held at the
 * solidus in an infinite medium ({@link CylinderConduction#flux}; Carslaw &amp; Jaeger 1959, §13.5): a narrow throat
 * loses heat far faster than a planar wall, as {@code f} grows with the curvature. A throat losing more than it gains freezes
 * inward ({@code da/dt = −(F_out − F_in)/(ρ L)}), one gaining more melts its walls back ({@code da/dt =
 * (F_in − F_out)/(ρ (L + c (T_s − T_r)))}); the gain per area falls as the throat widens, so it settles where
 * the two balance, or freezes shut once the flow wanes (Bruce &amp; Huppert 1989). Its hydraulic conductance is a
 * pipe's, {@code π a⁴ / 8}.
 */
final class VentThroat {
    /** Numerical: below this radius (m) the throat is counted solid (as a fissure segment below {@link FissureFeeder#FREEZE_WIDTH_M}). */
    static final double FREEZE_RADIUS_M = FissureFeeder.FREEZE_WIDTH_M / 2;

    double radius;
    final double heightM;
    final double wallRockC;
    /** Physical seconds since its fissure opened (the wall rock's conductive age). */
    double age;

    VentThroat(double radius, double heightM, double wallRockC, double age) {
        this.radius = radius;
        this.heightM = heightM;
        this.wallRockC = wallRockC;
        this.age = age;
    }

    /** The throat of the same hydraulic conductance as a fissure segment, {@code π a⁴ / 8 = C}. */
    static VentThroat ofConductance(double conductance, FissureFeeder feeder) {
        double radius = Math.pow(8 * conductance / Math.PI, 0.25);
        return new VentThroat(radius, feeder.heightM, feeder.wallRockC, feeder.age);
    }

    double conductance() {
        return frozen() ? 0 : Math.PI * radius * radius * radius * radius / 8;
    }

    boolean frozen() {
        return radius <= FREEZE_RADIUS_M;
    }

    /** Advances by {@code dt} physical seconds carrying {@code flux} m³/s of magma at {@code magmaC} °C. */
    void advance(double dt, double flux, double magmaC, double silicaWt) {
        double solidus = FissureFeeder.solidusC(silicaWt);
        double superheat = Math.max(0, magmaC - solidus);
        double wallGap = Math.max(1, solidus - wallRockC);
        double rho = MeltDensity.anhydrousKgPerM3(silicaWt);
        double remaining = dt;
        while (remaining > 0 && !frozen()) {
            double tau = FissureFeeder.ROCK_DIFFUSIVITY * Math.max(1, age) / (radius * radius);
            double loss = FissureFeeder.ROCK_CONDUCTIVITY * wallGap / radius * cylinderFlux(tau);
            double gain = rho * FissureFeeder.HEAT_CAPACITY * Math.max(0, flux) * superheat / (2 * Math.PI * radius * heightM);
            double net = gain - loss;
            double rate = net >= 0
                    ? net / (rho * (FissureFeeder.LATENT_HEAT + FissureFeeder.HEAT_CAPACITY * wallGap))
                    : net / (rho * FissureFeeder.LATENT_HEAT);
            // no sub-step changes the radius by more than MAX_STEP_CHANGE of itself (numerical)
            double step = rate != 0 ? Math.min(remaining, FissureFeeder.MAX_STEP_CHANGE * radius / Math.abs(rate)) : remaining;
            step = Math.max(step, Math.min(remaining, 1e-3));
            radius += rate * step;
            if (radius <= FREEZE_RADIUS_M) radius = 0;
            age += step;
            remaining -= step;
        }
        if (frozen()) age += remaining;
    }

    /** {@link CylinderConduction#flux}. */
    static double cylinderFlux(double tau) {
        return CylinderConduction.flux(tau);
    }

    JsonObject save() {
        JsonObject o = new JsonObject();
        o.addProperty("radius", radius);
        o.addProperty("heightM", heightM);
        o.addProperty("wallRockC", wallRockC);
        o.addProperty("age", age);
        return o;
    }

    static VentThroat load(JsonObject o) {
        return new VentThroat(o.get("radius").getAsDouble(), o.get("heightM").getAsDouble(),
                o.get("wallRockC").getAsDouble(), o.get("age").getAsDouble());
    }
}
