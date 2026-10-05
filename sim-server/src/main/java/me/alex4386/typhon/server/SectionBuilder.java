package me.alex4386.typhon.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.geothermal.Geothermal;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialClass;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.SectionRaster;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.server.protocol.Codecs;
import me.alex4386.typhon.simulator.scenario.Scenario;

/**
 * Builds a section frame (docs/protocol.md §6) from {@link WorldModel#section}. Runs on the engine
 * thread.
 *
 * <p>Temperature is a placeholder until the subsurface heat model (M4): a conductive geotherm below
 * the surface, warmed by the geothermal grid near the surface, molten lava on top and the chamber's
 * temperature inside its overlay. Saturation, steam and the water table are not modelled yet (0 /
 * NaN).
 */
final class SectionBuilder {
    static final double GEOTHERM_C_PER_M = 0.03;
    static final int FLAG_VOID = 1;
    static final int FLAG_WATER = 2;
    static final int FLAG_MAGMA = 4;
    static final int FLAG_AIR = 8;

    private SectionBuilder() {}

    record Request(long requestId, double[][] polyline, double zMin, double zMax, int nu, int nz) {}

    static byte[] build(Scenario s, GridMapping map, Request req, Map<String, List<double[]>> dikePaths,
            Set<String> activeDikes, double time) {
        WorldModel world = s.terrain().world();
        LavaFlow lava = s.lava();
        int nu = req.nu();
        int nz = req.nz();

        double[] poly = new double[req.polyline().length * 2];
        double[] cum = new double[req.polyline().length];
        for (int i = 0; i < req.polyline().length; i++) {
            poly[2 * i] = map.fracX(req.polyline()[i][0]);
            poly[2 * i + 1] = map.fracZ(req.polyline()[i][1]);
            if (i > 0) {
                double dx = req.polyline()[i][0] - req.polyline()[i - 1][0];
                double dy = req.polyline()[i][1] - req.polyline()[i - 1][1];
                cum[i] = cum[i - 1] + Math.hypot(dx, dy);
            }
        }
        double length = cum[cum.length - 1];
        // Engine rows run top-down; ask for the same z range and flip into protocol rows (bottom-up).
        SectionRaster raster = world.section(poly, req.zMin(), req.zMax(), nu, nz);

        float[] surface = new float[nu];
        float[] waterTable = new float[nu];
        byte[] material = new byte[nu * nz];
        int[] unit = new int[nu * nz];
        float[] temperature = new float[nu * nz];
        float[] saturation = new float[nu * nz];
        float[] steam = new float[nu * nz];
        byte[] flags = new byte[nu * nz];

        Set<Integer> unitIds = new LinkedHashSet<>();
        double dzRow = (req.zMax() - req.zMin()) / nz;
        for (int i = 0; i < nu; i++) {
            double along = (i + 0.5) / nu * length;
            double[] p = pointAlong(req.polyline(), cum, along);
            int cx = map.columnAtX(p[0]);
            int cz = map.columnAtY(p[1]);
            double ground = raster.surfaceZ()[i];
            double uplift = world.uplift(cx, cz);
            double lavaH = lava.thickness(cx, cz);
            double lavaT = lavaH > 0 ? lava.temperatureC(cx, cz) : 0;
            double surfaceT = FieldSampler.AMBIENT_C;
            for (VolcanoSystem v : s.volcanoes()) {
                Geothermal g = v.geothermal();
                if (g != null) surfaceT = Math.max(surfaceT, g.temperatureAt(cx, cz));
            }
            double waterZ = world.waterZ(cx, cz);
            surface[i] = Double.isFinite(ground) ? (float) (ground + uplift + lavaH) : Float.NaN;
            waterTable[i] = Float.NaN;
            for (int k = 0; k < nz; k++) {
                double z = req.zMin() + (k + 0.5) * dzRow;
                int src = (nz - 1 - k) * nu + i; // engine row 0 = top
                int dst = k * nu + i;
                Material m = MaterialTable.get(raster.material()[src]);
                int u = raster.unit()[src];
                int f = 0;
                double t = 0;
                if (m.materialClass() == MaterialClass.AIR && Double.isFinite(ground) && z < world.spec().datumZ()
                        && world.layerCount(cx, cz) > 0) {
                    // Below the model datum: extend the deepest layer (basement) downwards.
                    var bottom = world.layer(cx, cz, 0);
                    m = MaterialTable.get(bottom.material());
                    u = bottom.unit();
                }
                if (m.materialClass() == MaterialClass.AIR) {
                    if (Double.isFinite(ground) && z <= ground + uplift + lavaH && lavaH > 0 && z > ground + uplift) {
                        f |= FLAG_MAGMA;
                        t = lavaT;
                    } else if (Double.isFinite(waterZ) && Double.isFinite(ground) && z <= waterZ && z > ground) {
                        m = MaterialTable.WATER;
                        f |= FLAG_WATER;
                        t = FieldSampler.AMBIENT_C;
                    } else {
                        f |= FLAG_AIR;
                    }
                } else {
                    double depth = Math.max(0, ground - z);
                    double warm = (surfaceT - FieldSampler.AMBIENT_C) * Math.exp(-depth / 50);
                    t = FieldSampler.AMBIENT_C + warm + GEOTHERM_C_PER_M * depth;
                    if (m.materialClass() == MaterialClass.VOID || Byte.toUnsignedInt(raster.voidFraction()[src]) > 127) {
                        f |= FLAG_VOID;
                    }
                    if (m.materialClass() == MaterialClass.WATER) f |= FLAG_WATER;
                }
                material[dst] = (byte) m.id();
                unit[dst] = u < 0 ? 0 : u + 1;
                if (u >= 0) unitIds.add(u);
                temperature[dst] = (float) t;
                flags[dst] = (byte) f;
            }
        }

        JsonArray overlays = overlays(s, map, req, cum, length, dikePaths, activeDikes);
        // Chamber interiors are melt: flag and colour them by the chamber temperature.
        for (int idx = 0; idx < overlays.size(); idx++) {
            JsonObject o = overlays.get(idx).getAsJsonObject();
            if (!o.get("kind").getAsString().equals("chamber")) continue;
            double uc = o.get("u").getAsDouble();
            double zc = o.get("z").getAsDouble();
            double rx = o.get("rx").getAsDouble();
            double rz = o.get("rz").getAsDouble();
            double tc = o.get("temperatureC").getAsDouble();
            for (int i = 0; i < nu; i++) {
                double du = ((i + 0.5) / nu * length - uc) / rx;
                if (Math.abs(du) > 1) continue;
                for (int k = 0; k < nz; k++) {
                    double dz = (req.zMin() + (k + 0.5) * dzRow - zc) / rz;
                    if (du * du + dz * dz > 1) continue;
                    int dst = k * nu + i;
                    flags[dst] |= FLAG_MAGMA;
                    temperature[dst] = (float) tc;
                }
            }
        }

        JsonObject meta = new JsonObject();
        meta.addProperty("requestId", req.requestId());
        meta.add("length", Json.num(length));
        meta.add("zMin", Json.num(req.zMin()));
        meta.add("zMax", Json.num(req.zMax()));
        JsonArray units = new JsonArray();
        for (int id : unitIds) units.add(Probe.unit(id, world.unit(id)));
        meta.add("units", units);
        meta.add("overlays", overlays);

        Codecs.SectionBody body = new Codecs.SectionBody(Json.GSON.toJson(meta), surface, waterTable, material, unit,
                temperature, saturation, steam, flags);
        return Codecs.sectionFrame(req.requestId(), nu, nz, time, body);
    }

    private static JsonArray overlays(Scenario s, GridMapping map, Request req, double[] cum, double length,
            Map<String, List<double[]>> dikePaths, Set<String> activeDikes) {
        JsonArray out = new JsonArray();
        WorldModel world = s.terrain().world();
        for (VolcanoSystem v : s.volcanoes()) {
            double[] c = map.point(v.chamber().chamberCenter());
            double r = Probe.displayChamberRadius(v, map, world);
            double[] proj = project(req.polyline(), cum, c[0], c[1]);
            if (proj[1] <= 2 * r) {
                JsonObject o = new JsonObject();
                o.addProperty("kind", "chamber");
                o.add("u", Json.num(proj[0]));
                o.add("z", Json.num(c[2]));
                o.add("rx", Json.num(r));
                o.add("rz", Json.num(r * 0.55));
                o.add("temperatureC", Json.num(v.chamber().temperatureC()));
                out.add(o);
            }
            boolean erupting = v.chamber().erupting();
            List<VentSite> active = erupting ? v.coupler().activeVents() : List.of();
            for (VentSite vent : v.coupler().allVents()) {
                BlockPos p = vent.position();
                double[] vp = project(req.polyline(), cum, map.x(p.x()), map.y(p.z()));
                if (vp[1] > Math.max(500, 4 * map.cell)) continue;
                JsonObject o = new JsonObject();
                o.addProperty("kind", "conduit");
                o.add("u", Json.num(vp[0]));
                o.add("zTop", Json.num((p.y() + 1) * map.cell));
                o.add("zBottom", Json.num(c[2] + r * 0.55));
                o.add("width", Json.num(Math.max(2 * map.cell, 2 * v.chamber().config().conduitRadius())));
                o.addProperty("active", active.contains(vent));
                out.add(o);
            }
        }
        for (Map.Entry<String, List<double[]>> e : dikePaths.entrySet()) {
            JsonArray points = new JsonArray();
            boolean near = false;
            for (double[] p : e.getValue()) {
                double[] proj = project(req.polyline(), cum, p[0], p[1]);
                if (proj[1] <= 1500) near = true;
                points.add(Json.xy(proj[0], p[2]));
            }
            if (!near || points.size() < 2) continue;
            JsonObject o = new JsonObject();
            o.addProperty("kind", "dike");
            o.add("points", points);
            o.addProperty("active", activeDikes.contains(e.getKey()));
            out.add(o);
        }
        return out;
    }

    /** Point at distance {@code along} on the polyline. */
    static double[] pointAlong(double[][] poly, double[] cum, double along) {
        int seg = 0;
        while (seg < poly.length - 2 && cum[seg + 1] < along) seg++;
        double len = cum[seg + 1] - cum[seg];
        double f = len > 0 ? (along - cum[seg]) / len : 0;
        return new double[] {poly[seg][0] + f * (poly[seg + 1][0] - poly[seg][0]),
                poly[seg][1] + f * (poly[seg + 1][1] - poly[seg][1])};
    }

    /** {distance along the polyline of the nearest point, horizontal distance to it}. */
    static double[] project(double[][] poly, double[] cum, double x, double y) {
        double bestU = 0;
        double bestD = Double.POSITIVE_INFINITY;
        for (int s = 0; s < poly.length - 1; s++) {
            double ax = poly[s][0];
            double ay = poly[s][1];
            double dx = poly[s + 1][0] - ax;
            double dy = poly[s + 1][1] - ay;
            double len2 = dx * dx + dy * dy;
            double f = len2 > 0 ? Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / len2)) : 0;
            double px = ax + f * dx;
            double py = ay + f * dy;
            double d = Math.hypot(x - px, y - py);
            if (d < bestD) {
                bestD = d;
                bestU = cum[s] + f * Math.sqrt(len2);
            }
        }
        return new double[] {bestU, bestD};
    }
}
