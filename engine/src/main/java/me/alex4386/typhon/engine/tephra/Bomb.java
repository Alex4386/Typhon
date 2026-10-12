package me.alex4386.typhon.engine.tephra;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** An airborne volcanic bomb. State vector {@code s = {x, y, z, vx, vy, vz}}. */
final class Bomb {
    final long id;
    final double[] s;
    final double diameter;
    final double dragFactor;
    final double silicaWt;
    /** Ground elevation (m) assumed where the ground is not known (the launch point's ground). */
    final double fallbackGroundZ;
    /** Simulation time (s) of launch. */
    final double launchTime;
    /**
     * Real bombs of this size the tracked one stands for: its landing lays {@code weight} times its own
     * volume. 0 for a bomb that only shows ejecta whose mass is laid down elsewhere (proximal fallout).
     */
    final double weight;
    /**
     * Its trajectory, integrated once at launch: the time (s) it lands, NaN until known (a bomb restored from a
     * save made before trajectories were kept, integrated step by step), +∞ if it does not land within the
     * longest flight; and where and how fast it lands.
     */
    double landTime = Double.NaN;
    double[] landing;

    Bomb(long id, double[] s, double diameter, double dragFactor, double silicaWt, double fallbackGroundZ, double launchTime,
            double weight) {
        this.id = id;
        this.s = s;
        this.diameter = diameter;
        this.dragFactor = dragFactor;
        this.silicaWt = silicaWt;
        this.fallbackGroundZ = fallbackGroundZ;
        this.launchTime = launchTime;
        this.weight = weight;
    }

    Bomb copy() {
        Bomb b = new Bomb(id, s.clone(), diameter, dragFactor, silicaWt, fallbackGroundZ, launchTime, weight);
        b.landTime = landTime;
        b.landing = landing == null ? null : landing.clone();
        return b;
    }

    Vec3d position() {
        return new Vec3d(s[0], s[1], s[2]);
    }

    Vec3d velocity() {
        return new Vec3d(s[3], s[4], s[5]);
    }

    JsonObject save() {
        JsonObject out = new JsonObject();
        out.addProperty("id", id);
        JsonArray state = new JsonArray();
        for (double v : s) state.add(v);
        out.add("s", state);
        out.addProperty("diameter", diameter);
        out.addProperty("dragFactor", dragFactor);
        out.addProperty("silica", silicaWt);
        out.addProperty("fallbackGroundZ", fallbackGroundZ);
        out.addProperty("launchTime", launchTime);
        out.addProperty("weight", weight);
        if (!Double.isNaN(landTime)) {
            out.addProperty("landTime", Double.isFinite(landTime) ? landTime : -1);
            if (landing != null) {
                JsonArray l = new JsonArray();
                for (double v : landing) l.add(v);
                out.add("landing", l);
            }
        }
        return out;
    }

    static Bomb load(JsonObject in) {
        JsonArray state = in.getAsJsonArray("s");
        double[] s = new double[6];
        for (int i = 0; i < 6; i++) s[i] = state.get(i).getAsDouble();
        Bomb b = new Bomb(
                in.get("id").getAsLong(),
                s,
                in.get("diameter").getAsDouble(),
                in.get("dragFactor").getAsDouble(),
                in.get("silica").getAsDouble(),
                in.get("fallbackGroundZ").getAsDouble(),
                in.get("launchTime").getAsDouble(),
                in.get("weight").getAsDouble());
        if (in.has("landTime")) {
            double t = in.get("landTime").getAsDouble();
            b.landTime = t < 0 ? Double.POSITIVE_INFINITY : t;
            if (in.has("landing")) {
                JsonArray l = in.getAsJsonArray("landing");
                b.landing = new double[l.size()];
                for (int i = 0; i < b.landing.length; i++) b.landing[i] = l.get(i).getAsDouble();
            }
        }
        return b;
    }
}
