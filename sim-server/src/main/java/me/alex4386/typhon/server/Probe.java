package me.alex4386.typhon.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.deformation.DeformationEvents;
import me.alex4386.typhon.engine.deformation.DeformationModel;
import me.alex4386.typhon.engine.deformation.GeodeticStation;
import me.alex4386.typhon.engine.deformation.StationReading;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.seismic.SeismicityModel;
import me.alex4386.typhon.engine.tephra.ExplosivePhase;
import me.alex4386.typhon.engine.tephra.TephraSubsystem;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.DepositType;
import me.alex4386.typhon.engine.world.Material;
import me.alex4386.typhon.engine.world.MaterialClass;
import me.alex4386.typhon.engine.world.MaterialTable;
import me.alex4386.typhon.engine.world.UnitRecord;
import me.alex4386.typhon.engine.world.UnitTable;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.simulator.scenario.Scenario;

/**
 * Read-only queries against a scenario that build protocol JSON (world info, state, units). Must run
 * on the engine thread or on a scenario nobody is stepping.
 */
final class Probe {
    private Probe() {}

    // ── World info (§4.1) ──

    static JsonObject worldInfo(Scenario s, GridMapping map, String name) {
        WorldModel world = s.terrain().world();
        JsonObject w = new JsonObject();
        w.addProperty("name", name);
        w.add("origin", Json.xy(map.originX(), map.originY()));
        w.add("cellSize", Json.num(map.cell));
        w.addProperty("tileSize", map.tileSize);
        JsonObject tiles = new JsonObject();
        tiles.addProperty("minTx", 0);
        tiles.addProperty("minTy", 0);
        tiles.addProperty("maxTx", map.tilesX - 1);
        tiles.addProperty("maxTy", map.tilesY - 1);
        w.add("tiles", tiles);

        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        double sea = Double.NaN;
        for (int x = map.minX; x <= map.maxX; x++) {
            for (int z = map.minZ; z <= map.maxZ; z++) {
                double e = world.surfaceZ(x, z);
                if (!Double.isFinite(e)) continue;
                lo = Math.min(lo, e);
                hi = Math.max(hi, e);
                double wz = world.waterZ(x, z);
                if (Double.isFinite(wz) && wz > e && !(wz <= sea)) sea = Double.isNaN(sea) ? wz : Math.max(sea, wz);
            }
        }
        if (!Double.isFinite(lo)) {
            lo = 0;
            hi = 1;
        }
        double specSea = world.spec().seaLevelZ();
        if (Double.isFinite(specSea)) sea = specSea;
        w.add("seaLevel", Json.num(Double.isFinite(sea) ? sea : lo));
        w.add("elevationRange", Json.xy(lo, hi));

        JsonArray volcanoes = new JsonArray();
        for (VolcanoSystem v : s.volcanoes()) volcanoes.add(volcanoInfo(v, map, world));
        w.add("volcanoes", volcanoes);
        w.add("materials", materials());
        w.add("depositTypes", depositTypes());
        return w;
    }

    static JsonObject volcanoInfo(VolcanoSystem v, GridMapping map, WorldModel world) {
        JsonObject o = new JsonObject();
        o.addProperty("id", v.volcanoId());
        o.addProperty("name", displayName(v.volcanoId()));
        JsonArray vents = new JsonArray();
        EventTranslator vt = new EventTranslator(map, List.of(), id -> List.of());
        for (VentSite vent : v.coupler().allVents()) vents.add(vt.vent(vent));
        o.add("vents", vents);
        JsonObject chamber = new JsonObject();
        double[] c = chamberCenter(v, map, world);
        chamber.add("center", Json.xyz(c));
        chamber.add("radius", Json.num(displayChamberRadius(v, map, world)));
        o.add("chamber", chamber);
        return o;
    }

    /** Elevation (m) of the ground above the chamber, falling back to the primary vent's floor. */
    static double groundAboveChamber(VolcanoSystem v, GridMapping map, WorldModel world) {
        BlockPos c = v.chamber().chamberCenter();
        double s = world.surfaceZ(c.x(), c.z());
        return Double.isFinite(s) ? s : (v.vents().get(0).position().y() + 1) * map.cell;
    }

    /**
     * Chamber centre in protocol coordinates at its <em>physical</em> depth below the ground
     * ({@code MagmaState.physicalDepthM()}). The engine compresses chamber depth into the block
     * world; the visualizer shows the real geometry. Falls back to the block position when the
     * physical depth is unknown.
     */
    static double[] chamberCenter(VolcanoSystem v, GridMapping map, WorldModel world) {
        BlockPos b = v.chamber().chamberCenter();
        double[] c = {map.x(b.x()), map.y(b.z()), (b.y() + 0.5) * map.cell};
        double depth = v.chamber().physicalDepthM();
        if (Double.isFinite(depth) && depth > 0) c[2] = groundAboveChamber(v, map, world) - depth;
        return c;
    }

    /**
     * Radius drawn for the chamber: the real equivalent-sphere radius, capped so the chamber stays
     * below the ground above it.
     */
    static double displayChamberRadius(VolcanoSystem v, GridMapping map, WorldModel world) {
        double real = Math.cbrt(3 * v.chamber().config().volume() / (4 * Math.PI));
        double depth = groundAboveChamber(v, map, world) - chamberCenter(v, map, world)[2];
        return Math.max(map.cell * 2, Math.min(real, 0.6 * depth));
    }

    static String displayName(String id) {
        if (id.isEmpty()) return id;
        String[] parts = id.split("[-_]");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    static JsonArray materials() {
        JsonArray out = new JsonArray();
        for (Material m : MaterialTable.all()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", m.id());
            o.addProperty("name", m.name());
            o.addProperty("color", materialColor(m));
            o.addProperty("kind", materialKind(m.materialClass()));
            out.add(o);
        }
        return out;
    }

    static String materialKind(MaterialClass c) {
        return switch (c) {
            case AIR, VOID -> "void";
            case WATER -> "water";
            case ICE -> "ice";
            case ROCK -> "rock";
            case TEPHRA -> "tephra";
            case SOIL -> "soil";
        };
    }

    static String materialColor(Material m) {
        return switch (m.name()) {
            case "air" -> "#cfe8ff";
            case "void" -> "#101014";
            case "water" -> "#2f6fb3";
            case "ice" -> "#d8f0ff";
            case "basalt" -> "#3b3a3a";
            case "andesite" -> "#6b6662";
            case "dacite" -> "#8d857c";
            case "rhyolite" -> "#b8aa9a";
            case "obsidian" -> "#141018";
            case "scoria" -> "#6e2e22";
            case "tuff" -> "#b9a27f";
            case "ash" -> "#9a968f";
            case "pumice" -> "#e2dccb";
            case "lahar_deposit" -> "#7d6a4f";
            case "hyaloclastite" -> "#4f5a4a";
            case "granite" -> "#c9a9a0";
            case "gabbro" -> "#2f3a33";
            case "sediment" -> "#a08c6a";
            case "soil" -> "#5b4a32";
            case "clay" -> "#b07d5a";
            case "sulfur" -> "#e6d23a";
            default -> switch (m.materialClass()) {
                case ROCK -> "#6a6a6a";
                case TEPHRA -> "#a0907a";
                case SOIL -> "#6b5638";
                case WATER -> "#2f6fb3";
                case ICE -> "#d8f0ff";
                case AIR, VOID -> "#101014";
            };
        };
    }

    static JsonArray depositTypes() {
        JsonArray out = new JsonArray();
        for (DepositType t : DepositType.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", t.ordinal());
            o.addProperty("name", t.name());
            o.addProperty("color", switch (t) {
                case FILL -> "#8a7f6e";
                case BASEMENT -> "#9b8579";
                case EDIFICE -> "#6d6660";
                case LAVA -> "#3a3434";
                case TUBE_ROOF -> "#55403a";
                case PDC -> "#c2a77d";
                case FALL -> "#a29d93";
                case LAHAR -> "#7d6a4f";
                case HYALOCLASTITE -> "#4f5a4a";
                case INTRUSION -> "#8b2f1e";
                case CAVITY -> "#101014";
            });
            out.add(o);
        }
        return out;
    }

    // ── Units (§4.2) ──

    /** Protocol unit ids are engine unit ids + 1 (0 = not applicable). */
    static JsonArray units(WorldModel world, int fromEngineId) {
        UnitTable table = world.units();
        JsonArray out = new JsonArray();
        List<UnitRecord> all = table.all();
        for (int id = Math.max(0, fromEngineId); id < all.size(); id++) out.add(unit(id, all.get(id)));
        return out;
    }

    static JsonObject unit(int engineId, UnitRecord u) {
        JsonObject o = new JsonObject();
        o.addProperty("id", engineId + 1);
        o.addProperty("volcanoId", u.volcanoId());
        o.addProperty("depositType", u.type().ordinal());
        boolean geology = u.volcanoId() == null && u.eruptionId() < 0;
        if (u.eruptionId() >= 0) o.addProperty("eruption", u.eruptionId());
        else o.add("eruption", com.google.gson.JsonNull.INSTANCE);
        if (geology) o.add("time", com.google.gson.JsonNull.INSTANCE);
        else o.add("time", Json.num(u.timeSeconds()));
        o.addProperty("label", label(u));
        return o;
    }

    static String label(UnitRecord u) {
        String type = u.type().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (u.volcanoId() == null) return type;
        return u.volcanoId() + (u.eruptionId() >= 0 ? " #" + u.eruptionId() : "") + " " + type;
    }

    // ── State (§4.4) ──

    /** Per-volcano 0D state; also fills {@code activeVents} with the vents erupting now. */
    static JsonObject volcanoStates(Scenario s, GridMapping map, Map<String, List<String>> activeVents) {
        JsonObject out = new JsonObject();
        for (VolcanoSystem v : s.volcanoes()) {
            JsonObject o = new JsonObject();
            MagmaChamber ch = v.chamber();
            JsonObject chamber = new JsonObject();
            chamber.add("overpressureMPa", Json.num(ch.overpressureMPa()));
            chamber.add("tensileStrengthMPa", Json.num(ch.config().tensileStrengthMPa()));
            chamber.add("temperatureC", Json.num(ch.temperatureC()));
            chamber.add("silicaWt", Json.num(ch.silicaWt()));
            chamber.add("waterWt", Json.num(ch.waterWt()));
            chamber.add("crystalFraction", Json.num(ch.crystalFraction()));
            chamber.add("eruptionRate", Json.num(ch.eruptionRate()));
            chamber.add("volumeM3", Json.num(ch.volumeM3()));
            String regime = v.coupler().phreatomagmatic() ? "SURTSEYAN" : EventTranslator.regime(ch.eruptiveRegime());
            chamber.addProperty("regime", regime);
            o.add("chamber", chamber);

            SeismicityModel sm = v.seismicity();
            JsonObject seismic = new JsonObject();
            seismic.add("rsam", Json.num(sm.rsam()));
            seismic.add("vtPerMinute", Json.num(sm.vtRatePerMinute()));
            seismic.add("lpPerMinute", Json.num(sm.lpRatePerMinute()));
            seismic.addProperty("tremor", sm.tremorActive());
            seismic.addProperty("swarm", sm.swarmActive());
            o.add("seismic", seismic);

            JsonObject alert = new JsonObject();
            var level = v.alert().level(); // null until the estimator's first sample (fresh or reset volcano)
            alert.addProperty("level", level == null ? "DORMANT" : level.name());
            var style = v.alert().suggestedStyle();
            alert.addProperty("style", style == null ? "HAWAIIAN" : style.name()); // only shown while erupting
            o.add("alert", alert);

            o.add("deformation", deformation(v.deformation(), map));

            TephraSubsystem tephra = v.tephra();
            ExplosivePhase phase = tephra.activePhase();
            if (phase != null && tephra.plumeHeight() > 0) {
                JsonObject plume = new JsonObject();
                double ventZ = (phase.vent().position().y() + 1) * map.cell;
                plume.add("topZ", Json.num(ventZ + tephra.plumeHeight() * map.cell));
                plume.add("massRateKgS", Json.num(phase.massEruptionRate()));
                o.add("plume", plume);
            }
            out.add(v.volcanoId(), o);

            List<String> ids = new ArrayList<>();
            if (ch.erupting()) for (VentSite vent : v.coupler().activeVents()) ids.add(vent.id());
            if (ids.isEmpty()) for (VentSite vent : v.vents()) ids.add(vent.id());
            activeVents.put(v.volcanoId(), ids);
        }
        return out;
    }

    private static JsonObject deformation(DeformationModel model, GridMapping map) {
        JsonObject o = new JsonObject();
        JsonArray stations = new JsonArray();
        double maxUp = 0;
        if (model != null) {
            DeformationEvents.DeformationSample sample = model.snapshot();
            if (sample != null) {
                maxUp = sample.summitUpliftM();
                List<GeodeticStation> configured = model.config().stations;
                for (StationReading r : sample.stations()) {
                    JsonObject st = new JsonObject();
                    st.addProperty("id", r.name());
                    GeodeticStation g = null;
                    for (GeodeticStation c : configured) if (c.name().equals(r.name())) g = c;
                    st.add("at", g == null ? Json.xy(0, 0) : Json.xy(map.x(g.x()), map.y(g.z())));
                    st.add("east", Json.num(r.displacement().east()));
                    st.add("north", Json.num(r.displacement().north()));
                    st.add("up", Json.num(r.displacement().up()));
                    st.add("tiltX", Json.num(r.tiltEastMicroRad()));
                    st.add("tiltY", Json.num(r.tiltNorthMicroRad()));
                    stations.add(st);
                    maxUp = Math.max(maxUp, r.displacement().up());
                }
            }
        }
        o.add("maxUpliftM", Json.num(maxUp));
        o.add("stations", stations);
        return o;
    }
}
