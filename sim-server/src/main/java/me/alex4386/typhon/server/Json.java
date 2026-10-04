package me.alex4386.typhon.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** Small JSON helpers. Non-finite numbers become {@code null} (JSON has no NaN). */
final class Json {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {}

    static JsonElement num(double v) {
        return Double.isFinite(v) ? new JsonPrimitive(round(v)) : JsonNull.INSTANCE;
    }

    /** Trims doubles to ~7 significant digits so messages stay small. */
    static double round(double v) {
        if (v == 0 || !Double.isFinite(v)) return v;
        double scale = Math.pow(10, 6 - Math.floor(Math.log10(Math.abs(v))));
        return Math.round(v * scale) / scale;
    }

    static JsonArray xy(double x, double y) {
        JsonArray a = new JsonArray(2);
        a.add(num(x));
        a.add(num(y));
        return a;
    }

    static JsonArray xyz(double[] p) {
        JsonArray a = new JsonArray(3);
        for (double v : p) a.add(num(v));
        return a;
    }

    static JsonObject obj(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return o;
    }

    static JsonObject error(String code, String message, Long requestId) {
        JsonObject o = obj("error");
        o.addProperty("code", code);
        o.addProperty("message", message);
        if (requestId != null) o.addProperty("requestId", requestId);
        return o;
    }

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    static Double dbl(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsDouble();
    }

    static Long lng(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsLong();
    }
}
