package me.alex4386.typhon.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import me.alex4386.typhon.engine.alert.AlertEvents;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.dike.DikeEvents;
import me.alex4386.typhon.engine.geomorph.GeomorphEvents;
import me.alex4386.typhon.engine.geothermal.GeyserFormed;
import me.alex4386.typhon.engine.geothermal.HydrothermalFeature;
import me.alex4386.typhon.engine.geothermal.HydrothermalFeatureFormed;
import me.alex4386.typhon.engine.lava.LavaEvents;
import me.alex4386.typhon.engine.magma.MagmaEvents;
import me.alex4386.typhon.engine.massflow.MassFlowEvents;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.seismic.SeismicEvent;
import me.alex4386.typhon.engine.tephra.TephraEvents;
import me.alex4386.typhon.engine.tephra.Vec3d;
import me.alex4386.typhon.engine.volcano.EruptiveRegime;
import me.alex4386.typhon.engine.volcano.VentKind;
import me.alex4386.typhon.engine.volcano.VentEvents;
import me.alex4386.typhon.engine.volcano.VentSite;

/**
 * Translates engine events into protocol {@code SimEvent}s (docs/protocol.md §4.5). Positions go
 * from engine block coordinates to world metres. Events the protocol has no kind for return
 * {@code null}. Keeps the state needed across events (dike paths so far).
 */
final class EventTranslator {
    private final GridMapping map;
    private final List<VolcanoSystem> volcanoes;
    private final Function<String, List<String>> activeVents;
    private final Map<String, List<double[]>> dikePaths = new HashMap<>();
    private final Set<String> finishedDikes = new HashSet<>();

    EventTranslator(GridMapping map, List<VolcanoSystem> volcanoes, Function<String, List<String>> activeVents) {
        this.map = map;
        this.volcanoes = volcanoes;
        this.activeVents = activeVents;
    }

    /** Dike paths so far in world metres, keyed {@code volcanoId#dikeId}. */
    Map<String, List<double[]>> dikePaths() {
        return dikePaths;
    }

    boolean dikeActive(String key) {
        return !finishedDikes.contains(key);
    }

    JsonObject translate(EngineEvent event) {
        return switch (event) {
            case SeismicEvent e -> {
                JsonObject o = base("seismic", e.time(), e.volcanoId());
                o.addProperty("type", e.type().name());
                o.add("magnitude", Json.num(e.magnitude()));
                o.add("hypocenter", Json.xyz(map.point(e.hypocenter())));
                o.add("durationSeconds", Json.num(e.durationSeconds()));
                o.addProperty("swarm", e.swarm());
                yield o;
            }
            case MagmaEvents.EruptionStarted e -> {
                JsonObject o = base("eruptionStarted", e.time(), e.volcanoId());
                o.addProperty("cause", e.cause().name());
                JsonArray vents = new JsonArray();
                for (String id : activeVents.apply(e.volcanoId())) vents.add(id);
                o.add("ventIds", vents);
                yield o;
            }
            case MagmaEvents.EruptionEnded e -> {
                JsonObject o = base("eruptionEnded", e.time(), e.volcanoId());
                o.add("eruptedVolumeM3", Json.num(e.eruptedVolume()));
                o.addProperty("cause", e.cause().name());
                yield o;
            }
            case MagmaEvents.EruptiveRegimeChanged e -> {
                JsonObject o = base("regimeChanged", e.time(), e.volcanoId());
                o.addProperty("regime", regime(e.current()));
                yield o;
            }
            case AlertEvents.EruptionStyleEstimated e -> {
                JsonObject o = base("styleEstimated", e.time(), e.volcanoId());
                o.addProperty("previous", e.previous() == null ? null : e.previous().name());
                o.addProperty("current", e.current().name());
                o.addProperty("vei", e.vei());
                o.addProperty("forecast", e.forecast());
                JsonObject probabilities = new JsonObject();
                e.probabilities().forEach((k, v) -> probabilities.add(k.name(), Json.num(v)));
                o.add("probabilities", probabilities);
                yield o;
            }
            case AlertEvents.AlertLevelChanged e -> {
                JsonObject o = base("alertChanged", e.time(), e.volcanoId());
                o.addProperty("previous", e.previous() == null ? null : e.previous().name());
                o.addProperty("current", e.current().name());
                yield o;
            }
            case DikeEvents.DikeStarted e -> {
                String key = e.volcanoId() + "#" + e.dikeId();
                List<double[]> path = new ArrayList<>();
                path.add(map.point(e.origin()));
                dikePaths.put(key, path);
                yield null;
            }
            case DikeEvents.DikeAdvanced e -> {
                String key = e.volcanoId() + "#" + e.dikeId();
                List<double[]> path = dikePaths.computeIfAbsent(key, k -> new ArrayList<>());
                path.add(map.point(e.tip()));
                JsonObject o = base("dikeAdvanced", e.time(), e.volcanoId());
                o.addProperty("dikeId", e.dikeId());
                JsonArray points = new JsonArray();
                for (double[] p : path) points.add(Json.xyz(p));
                o.add("path", points);
                yield o;
            }
            case DikeEvents.DikeStalled e -> {
                finishedDikes.add(e.volcanoId() + "#" + e.dikeId());
                yield message(e.time(), "Dike " + e.dikeId() + " of " + e.volcanoId() + " stalled ("
                        + e.reason().name().toLowerCase() + ")");
            }
            case DikeEvents.FissureOpened e -> {
                finishedDikes.add(e.volcanoId() + "#" + e.dikeId());
                JsonObject o = base("fissureOpened", e.time(), e.volcanoId());
                o.add("vent", vent(e.vent()));
                yield o;
            }
            case VentEvents.VentStateChanged e -> {
                JsonObject o = base("ventState", e.time(), e.volcanoId());
                o.addProperty("ventId", e.ventId());
                o.addProperty("previous", e.previous().name().toLowerCase(Locale.ROOT));
                o.addProperty("state", e.current().name().toLowerCase(Locale.ROOT));
                if (!Double.isNaN(e.feederWidthM())) o.add("feederWidthM", Json.num(e.feederWidthM()));
                yield o;
            }
            case TephraEvents.BombLaunched e -> {
                JsonObject o = base("bombLaunched", e.time(), strip(e.source()));
                o.addProperty("id", e.bombId());
                o.add("start", Json.xyz(point(e.start())));
                o.add("velocity", Json.xyz(velocity(e.velocity())));
                o.add("dragK", Json.num(e.dragFactor() / map.cell));
                o.add("flightSeconds", Json.num(e.expectedFlightSeconds()));
                o.add("landing", Json.xyz(point(e.predictedLanding())));
                yield o;
            }
            case TephraEvents.PlumeColumn e -> {
                JsonObject o = base("plume", e.time(), strip(e.source()));
                double[] base = map.point(e.base());
                o.add("base", Json.xyz(base));
                o.add("topZ", Json.num(e.topY() * map.cell));
                o.add("radius", Json.num(e.radius() * map.cell));
                o.add("massRateKgS", Json.num(e.massEruptionRate()));
                yield o;
            }
            case TephraEvents.VolcanicLightning e -> {
                JsonObject o = base("lightning", e.time(), strip(e.source()));
                o.add("at", Json.xyz(map.point(e.position())));
                yield o;
            }
            case MassFlowEvents.PdcFront e -> front("PDC", e.time(), e.flowId(), e.cells(), e.maxSpeed(),
                    e.maxTemperatureC());
            case MassFlowEvents.LaharFront e -> front("LAHAR", e.time(), e.flowId(), e.cells(), e.maxSpeed(), 15);
            case MassFlowEvents.AvalancheFront e -> front("DEBRIS_AVALANCHE", e.time(), e.flowId(), e.cells(),
                    e.maxSpeed(), 15);
            case GeomorphEvents.SlopeFailure e -> {
                JsonObject o = base("slopeFailure", e.time(), e.volcanoId());
                o.add("at", Json.xyz(map.point(e.position())));
                o.add("volumeM3", Json.num(e.volumeM3()));
                o.addProperty("style", e.style().name());
                o.addProperty("trigger", e.trigger().name());
                o.add("factorOfSafety", Json.num(e.minFactorOfSafety()));
                yield o;
            }
            case GeomorphEvents.CraterExcavated e -> {
                JsonObject o = base("craterExcavated", e.time(), e.volcanoId());
                o.add("at", Json.xyz(map.point(e.center())));
                o.add("radiusM", Json.num(e.radiusM()));
                o.add("depthM", Json.num(e.depthM()));
                yield o;
            }
            case GeomorphEvents.CalderaCollapse e -> {
                JsonObject o = base("calderaCollapse", e.time(), e.volcanoId());
                o.add("at", Json.xyz(map.point(e.center())));
                o.add("radiusM", Json.num(e.radiusM()));
                o.add("subsidenceM", Json.num(e.totalSubsidenceM()));
                yield o;
            }
            // Alteration, sinter and cinnabar are diffuse surface changes (hundreds of blocks), not point
            // features: they show up through the TopUnit field instead of as markers.
            case HydrothermalFeatureFormed e -> switch (e.feature()) {
                case ACID_ALTERATION, SINTER, CINNABAR -> null;
                default -> feature(e.time(), e.feature().name(), e.pos());
            };
            case GeyserFormed e -> feature(e.time(), HydrothermalFeature.GEYSER.name(), e.potentSulfur());
            case LavaEvents.LavaOceanEntry e -> {
                JsonObject o = new JsonObject();
                o.addProperty("kind", "oceanEntry");
                o.add("time", Json.num(e.time()));
                o.add("at", Json.xy(map.x(e.pos().x()), map.y(e.pos().z())));
                o.add("powerMW", Json.num(e.powerMW()));
                o.addProperty("littoralExplosion", e.littoralExplosion());
                yield o;
            }
            default -> null;
        };
    }

    JsonObject message(double time, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "message");
        o.add("time", Json.num(time));
        o.addProperty("text", text);
        return o;
    }

    static String regime(EruptiveRegime r) {
        return switch (r) {
            case UNKNOWN, QUIESCENT -> "NONE";
            default -> r.name();
        };
    }

    JsonObject vent(VentSite v) {
        JsonObject o = new JsonObject();
        o.addProperty("id", v.id());
        o.addProperty("kind", v.kind() == VentKind.FISSURE ? "fissure" : "crater");
        BlockPos p = v.position();
        o.add("at", Json.xy(map.x(p.x()), map.y(p.z())));
        o.add("z", Json.num((p.y() + 1) * map.cell));
        o.add("radius", Json.num(Math.max(1, v.craterRadius()) * map.cell));
        if (v.kind() == VentKind.FISSURE) {
            double half = v.fissureLength() / 2.0;
            double dx = Math.cos(v.fissureAngleRad()) * half;
            double dz = Math.sin(v.fissureAngleRad()) * half;
            JsonArray line = new JsonArray();
            line.add(Json.xy(map.x(p.x() - dx), map.y(p.z() - dz)));
            line.add(Json.xy(map.x(p.x() + dx), map.y(p.z() + dz)));
            o.add("line", line);
        }
        return o;
    }

    private JsonObject front(String flow, double time, String flowId, List<MassFlowEvents.FlowCell> cells, double speed,
            double temperature) {
        JsonObject o = base("massFlowFront", time, volcanoOfFlow(flowId));
        o.addProperty("flow", flow);
        JsonArray xy = new JsonArray();
        int n = 0;
        for (MassFlowEvents.FlowCell c : cells) {
            if (n++ >= 256) break;
            xy.add(Json.xy(map.x(c.pos().x()), map.y(c.pos().z())));
        }
        o.add("cells", xy);
        o.add("speed", Json.num(speed));
        o.add("temperatureC", Json.num(temperature));
        return o;
    }

    private JsonObject feature(double time, String feature, BlockPos pos) {
        JsonObject o = base("geothermalFeature", time, nearestVolcano(pos));
        o.addProperty("feature", feature);
        o.add("at", Json.xyz(map.point(pos)));
        return o;
    }

    private JsonObject base(String kind, double time, String volcanoId) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.add("time", Json.num(time));
        o.addProperty("volcanoId", volcanoId);
        return o;
    }

    private double[] point(Vec3d v) {
        return map.point(v.x(), v.y(), v.z());
    }

    /** Engine velocities are in blocks/s; the protocol uses m/s in the (east, north, up) frame. */
    private double[] velocity(Vec3d v) {
        return new double[] {v.x() * map.cell, -v.z() * map.cell, v.y() * map.cell};
    }

    /** Subsystem ids look like {@code tephra:<volcanoId>}. */
    private static String strip(String source) {
        int colon = source.indexOf(':');
        return colon < 0 ? source : source.substring(colon + 1);
    }

    private String volcanoOfFlow(String flowId) {
        for (VolcanoSystem v : volcanoes) {
            if (v.pyroclasticFlows() != null && v.pyroclasticFlows().id().equals(flowId)) return v.volcanoId();
            if (v.lahars() != null && v.lahars().id().equals(flowId)) return v.volcanoId();
            if (v.debrisAvalanches() != null && v.debrisAvalanches().id().equals(flowId)) return v.volcanoId();
        }
        return strip(flowId);
    }

    private String nearestVolcano(BlockPos pos) {
        String best = volcanoes.isEmpty() ? null : volcanoes.get(0).volcanoId();
        double bestD = Double.POSITIVE_INFINITY;
        for (VolcanoSystem v : volcanoes) {
            for (VentSite vent : v.vents()) {
                double d = vent.position().horizontalDistance(pos);
                if (d < bestD) {
                    bestD = d;
                    best = v.volcanoId();
                }
            }
        }
        return best;
    }
}
