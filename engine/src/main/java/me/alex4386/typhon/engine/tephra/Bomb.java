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
    /** Ground y assumed where the terrain model has no data (the launch column's ground). */
    final int fallbackGroundY;
    final long launchTick;

    Bomb(long id, double[] s, double diameter, double dragFactor, double silicaWt, int fallbackGroundY, long launchTick) {
        this.id = id;
        this.s = s;
        this.diameter = diameter;
        this.dragFactor = dragFactor;
        this.silicaWt = silicaWt;
        this.fallbackGroundY = fallbackGroundY;
        this.launchTick = launchTick;
    }

    Bomb copy() {
        return new Bomb(id, s.clone(), diameter, dragFactor, silicaWt, fallbackGroundY, launchTick);
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
        out.addProperty("fallbackGroundY", fallbackGroundY);
        out.addProperty("launchTick", launchTick);
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
                in.get("fallbackGroundY").getAsInt(),
                in.get("launchTick").getAsLong());
    }
}
