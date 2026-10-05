package me.alex4386.typhon.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Locale;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.geothermal.Geothermal;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.massflow.MassFlowField;
import me.alex4386.typhon.engine.subsurface.Subsurface;
import me.alex4386.typhon.engine.world.LayerView;
import me.alex4386.typhon.engine.world.UnitRecord;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.simulator.scenario.Scenario;

/**
 * Point queries for the client's inspector (§3.7): everything the simulation knows about one
 * column of the world — surface, stratigraphy, subsurface temperature and water, lava and mass
 * flows on top. Runs on the engine thread.
 */
final class Inspector {
    /** Layers listed per column, top down (the rest are summarised as "deeper"). */
    static final int MAX_LAYERS = 24;

    private Inspector() {}

    static JsonObject inspect(Scenario s, GridMapping map, double x, double y) {
        JsonObject o = Json.obj("inspection");
        int cx = map.columnAtX(x);
        int cz = map.columnAtY(y);
        o.add("at", Json.xy(map.x(cx), map.y(cz)));
        o.addProperty("column", cx + "," + cz);
        if (!map.inside(cx, cz)) {
            o.addProperty("inside", false);
            return o;
        }
        o.addProperty("inside", true);
        WorldModel world = s.terrain().world();
        double surface = world.surfaceZ(cx, cz);
        o.add("surfaceZ", Json.num(surface));

        JsonArray layers = new JsonArray();
        int n = world.layerCount(cx, cz);
        for (int i = n - 1, listed = 0; i >= 0 && listed < MAX_LAYERS; i--, listed++) {
            LayerView l = world.layer(cx, cz, i);
            JsonObject j = new JsonObject();
            j.add("top", Json.num(l.top()));
            j.add("bottom", Json.num(l.bottom()));
            j.addProperty("material", l.materialInfo().name());
            j.addProperty("unit", l.unit() + 1);
            if (l.unit() >= 0) {
                UnitRecord u = world.units().get(l.unit());
                if (u != null) {
                    j.addProperty("depositType", u.type().name().toLowerCase(Locale.ROOT));
                    j.addProperty("label", Probe.label(u));
                }
            }
            j.add("porosity", Json.num(l.porosity()));
            if (l.voidFraction() > 0) j.add("voidFraction", Json.num(l.voidFraction()));
            if (l.loose()) j.addProperty("loose", true);
            layers.add(j);
        }
        o.add("layers", layers);
        o.addProperty("layerCount", n);
        if (n > 0) o.addProperty("surfaceMaterial", world.layer(cx, cz, n - 1).materialInfo().name());

        Subsurface sub = null;
        Geothermal geo = null;
        MassFlowField pdc = null;
        MassFlowField lahar = null;
        for (VolcanoSystem v : s.volcanoes()) {
            if (sub == null) sub = v.subsurface();
            if (geo == null && v.geothermal() != null) geo = v.geothermal();
            if (pdc == null && v.pyroclasticFlows() != null && v.pyroclasticFlows().depth(cx, cz) > 0) pdc = v.pyroclasticFlows();
            if (lahar == null && v.lahars() != null && v.lahars().depth(cx, cz) > 0) lahar = v.lahars();
        }
        if (sub != null && sub.known(cx, cz)) {
            JsonObject w = new JsonObject();
            w.add("tableZ", Json.num(sub.waterTableZ(cx, cz)));
            w.add("tableDepthM", Json.num(sub.waterTableDepthM(cx, cz)));
            w.add("surfaceWaterDepthM", Json.num(sub.surfaceWaterDepthM(cx, cz)));
            w.add("vadoseM", Json.num(sub.vadoseM(cx, cz)));
            w.add("steamFluxKgPerSm2", Json.num(sub.steamFluxKgPerSm2(cx, cz)));
            o.add("water", w);
            JsonArray profile = new JsonArray();
            for (int k = 0; k < sub.levels(); k++) {
                double d = sub.levelCenterDepth(k);
                JsonObject p = new JsonObject();
                p.add("depthM", Json.num(d));
                p.add("temperatureC", Json.num(sub.temperatureC(cx, cz, d)));
                double steam = sub.steamFraction(cx, cz, d);
                if (steam > 0) p.add("steam", Json.num(steam));
                profile.add(p);
            }
            o.add("temperatureProfile", profile);
        }
        if (geo != null) o.add("groundTemperatureC", Json.num(geo.temperatureAt(cx, cz)));

        LavaFlow lava = s.lava();
        if (lava != null && lava.thickness(cx, cz) > 0) {
            JsonObject l = new JsonObject();
            l.add("thicknessM", Json.num(lava.thickness(cx, cz)));
            l.add("temperatureC", Json.num(lava.temperatureC(cx, cz)));
            l.add("crustM", Json.num(lava.crustThickness(cx, cz)));
            o.add("lava", l);
        }
        if (pdc != null) o.add("pdc", flow(pdc, cx, cz));
        if (lahar != null) o.add("lahar", flow(lahar, cx, cz));
        return o;
    }

    private static JsonObject flow(MassFlowField f, int cx, int cz) {
        JsonObject j = new JsonObject();
        j.add("depthM", Json.num(f.depth(cx, cz)));
        j.add("speedMPerS", Json.num(f.speed(cx, cz)));
        j.add("temperatureC", Json.num(f.temperatureC(cx, cz)));
        return j;
    }
}
