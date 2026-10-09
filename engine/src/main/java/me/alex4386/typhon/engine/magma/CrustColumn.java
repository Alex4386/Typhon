package me.alex4386.typhon.engine.magma;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Arrays;

/**
 * The crust above a magma chamber as layers of bulk density, from the ground down: what sets its lithostatic
 * load and where magma is buoyant. Porous scoria, ash and fractured lava near the surface are far lighter than
 * the rock at depth, so magma that is buoyant at depth can reach a level of neutral buoyancy below the surface
 * (Ryan 1987). The deepest layer extends without limit.
 *
 * @param bottomDepthM depth (m below the ground) of each layer's bottom, increasing
 * @param densityKgPerM3 each layer's bulk density (pores filled with water below the water table)
 */
public record CrustColumn(double[] bottomDepthM, double[] densityKgPerM3) {
    static final double GRAVITY = 9.81;

    public CrustColumn {
        if (bottomDepthM.length == 0 || bottomDepthM.length != densityKgPerM3.length) {
            throw new IllegalArgumentException("a crust column needs one density per layer");
        }
        for (int i = 0; i < densityKgPerM3.length; i++) {
            if (!(densityKgPerM3[i] > 0)) throw new IllegalArgumentException("densities must be positive");
            if (i > 0 && !(bottomDepthM[i] >= bottomDepthM[i - 1])) {
                throw new IllegalArgumentException("layer bottoms must increase with depth");
            }
        }
    }

    /** A crust of one density throughout. */
    public static CrustColumn uniform(double densityKgPerM3) {
        return new CrustColumn(new double[] {Double.POSITIVE_INFINITY}, new double[] {densityKgPerM3});
    }

    /** Bulk density (kg/m³) at {@code depthM} below the ground. */
    public double densityAt(double depthM) {
        for (int i = 0; i < bottomDepthM.length - 1; i++) if (depthM < bottomDepthM[i]) return densityKgPerM3[i];
        return densityKgPerM3[densityKgPerM3.length - 1];
    }

    /** Weight of the rock above {@code depthM} (MPa): {@code ∫₀^z ρ g dz}. */
    public double pressureMPa(double depthM) {
        if (!(depthM > 0)) return 0;
        double p = 0;
        double top = 0;
        for (int i = 0; i < bottomDepthM.length; i++) {
            double bottom = i == bottomDepthM.length - 1 ? Double.POSITIVE_INFINITY : bottomDepthM[i];
            double thick = Math.min(depthM, bottom) - top;
            if (thick > 0) p += densityKgPerM3[i] * GRAVITY * thick;
            if (bottom >= depthM) break;
            top = bottom;
        }
        return p / 1e6;
    }

    /** Depth (m) at which the rock above weighs {@code pressureMPa} (inverse of {@link #pressureMPa}). */
    public double depthAtPressure(double pressureMPa) {
        if (!(pressureMPa > 0)) return 0;
        double p = 0;
        double top = 0;
        for (int i = 0; i < bottomDepthM.length; i++) {
            double bottom = i == bottomDepthM.length - 1 ? Double.POSITIVE_INFINITY : bottomDepthM[i];
            double layer = densityKgPerM3[i] * GRAVITY * (bottom - top) / 1e6;
            if (p + layer >= pressureMPa) return top + (pressureMPa - p) * 1e6 / (densityKgPerM3[i] * GRAVITY);
            p += layer;
            top = bottom;
        }
        return top;
    }

    /** Mean bulk density (kg/m³) of the rock above {@code depthM}. */
    public double meanDensity(double depthM) {
        if (!(depthM > 0)) return densityAt(0);
        return pressureMPa(depthM) * 1e6 / (GRAVITY * depthM);
    }

    JsonObject save() {
        JsonObject o = new JsonObject();
        JsonArray b = new JsonArray();
        JsonArray d = new JsonArray();
        for (int i = 0; i < bottomDepthM.length; i++) {
            // the unbounded deepest layer is saved as -1 (JSON has no infinity)
            b.add(Double.isFinite(bottomDepthM[i]) ? bottomDepthM[i] : -1);
            d.add(densityKgPerM3[i]);
        }
        o.add("bottomDepthM", b);
        o.add("densityKgPerM3", d);
        return o;
    }

    static CrustColumn load(JsonObject o) {
        JsonArray b = o.getAsJsonArray("bottomDepthM");
        JsonArray d = o.getAsJsonArray("densityKgPerM3");
        double[] bottom = new double[b.size()];
        double[] density = new double[d.size()];
        for (int i = 0; i < bottom.length; i++) {
            double v = b.get(i).getAsDouble();
            bottom[i] = v < 0 ? Double.POSITIVE_INFINITY : v;
            density[i] = d.get(i).getAsDouble();
        }
        return new CrustColumn(bottom, density);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CrustColumn c && Arrays.equals(bottomDepthM, c.bottomDepthM)
                && Arrays.equals(densityKgPerM3, c.densityKgPerM3);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(bottomDepthM) + Arrays.hashCode(densityKgPerM3);
    }
}
