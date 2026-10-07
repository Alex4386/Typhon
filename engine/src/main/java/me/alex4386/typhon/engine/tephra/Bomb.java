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
        return new Bomb(id, s.clone(), diameter, dragFactor, silicaWt, fallbackGroundZ, launchTime, weight);
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
        return out;
    }

    static Bomb load(JsonObject in) {
        JsonArray state = in.getAsJsonArray("s");
        double[] s = new double[6];
        for (int i = 0; i < 6; i++) s[i] = state.get(i).getAsDouble();
        return new Bomb(
                in.get("id").getAsLong(),
                s,
                in.get("diameter").getAsDouble(),
                in.get("dragFactor").getAsDouble(),
                in.get("silica").getAsDouble(),
                in.get("fallbackGroundZ").getAsDouble(),
                in.get("launchTime").getAsDouble(),
                in.get("weight").getAsDouble());
    }
}
