package me.alex4386.typhon.engine.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Append-only registry of {@link UnitRecord}s; layers refer to units by index. Unit 0 is the
 * unattributed {@link DepositType#FILL} unit.
 */
public final class UnitTable {
    public static final int UNATTRIBUTED = 0;

    private final List<UnitRecord> units = new ArrayList<>();

    public UnitTable() {
        units.add(UnitRecord.of(DepositType.FILL));
    }

    public int add(UnitRecord unit) {
        units.add(unit);
        return units.size() - 1;
    }

    public UnitRecord get(int id) {
        if (id < 0 || id >= units.size()) throw new IllegalArgumentException("Unknown unit id " + id);
        return units.get(id);
    }

    public int size() {
        return units.size();
    }

    public List<UnitRecord> all() {
        return Collections.unmodifiableList(units);
    }

    JsonArray toJson() {
        JsonArray array = new JsonArray();
        for (UnitRecord u : units) {
            JsonObject o = new JsonObject();
            if (u.volcanoId() != null) o.addProperty("volcano", u.volcanoId());
            o.addProperty("eruption", u.eruptionId());
            o.addProperty("type", u.type().name());
            o.addProperty("time", u.timeSeconds());
            o.add("emplacementC", number(u.emplacementC()));
            o.add("silicaWt", number(u.silicaWt()));
            array.add(o);
        }
        return array;
    }

    void load(JsonArray array) {
        units.clear();
        for (JsonElement e : array) {
            JsonObject o = e.getAsJsonObject();
            units.add(new UnitRecord(
                    o.has("volcano") ? o.get("volcano").getAsString() : null,
                    o.get("eruption").getAsInt(),
                    DepositType.valueOf(o.get("type").getAsString()),
                    o.get("time").getAsDouble(),
                    read(o.get("emplacementC")),
                    read(o.get("silicaWt"))));
        }
        if (units.isEmpty()) units.add(UnitRecord.of(DepositType.FILL));
    }

    private static JsonElement number(double v) {
        return Double.isNaN(v) ? JsonNull.INSTANCE : new JsonPrimitive(v);
    }

    private static double read(JsonElement e) {
        return e == null || e.isJsonNull() ? Double.NaN : e.getAsDouble();
    }
}
