package me.alex4386.typhon.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import me.alex4386.typhon.engine.assembly.VolcanoSystem;
import me.alex4386.typhon.engine.deformation.GeodeticStation;
import me.alex4386.typhon.engine.dike.Dike;
import me.alex4386.typhon.engine.dike.DikePropagation;
import me.alex4386.typhon.engine.geothermal.Geothermal;
import me.alex4386.typhon.engine.geothermal.HydrothermalFeature;
import me.alex4386.typhon.engine.geothermal.PlacedFeature;
import me.alex4386.typhon.engine.lava.LavaEvents;
import me.alex4386.typhon.engine.lava.LavaFlow;
import me.alex4386.typhon.engine.magma.MagmaChamber;
import me.alex4386.typhon.engine.massflow.MassFlowEvents;
import me.alex4386.typhon.engine.math.BlockPos;
import me.alex4386.typhon.engine.output.EngineEvent;
import me.alex4386.typhon.engine.seismic.SeismicEvent;
import me.alex4386.typhon.engine.tephra.ExplosivePhase;
import me.alex4386.typhon.engine.tephra.TephraSubsystem;
import me.alex4386.typhon.engine.volcano.VentKind;
import me.alex4386.typhon.engine.volcano.VentSite;
import me.alex4386.typhon.engine.world.WorldModel;
import me.alex4386.typhon.simulator.scenario.Scenario;

/**
 * Registry of the spatial things a client can see, select and inspect (§4.8): vents and fissures,
 * dikes, hydrothermal features, magma chambers, eruption columns, active lava and mass flows,
 * deformation stations and notable earthquakes.
 *
 * <p>Persistent entities come from an engine snapshot taken on the engine thread; short-lived ones
 * (flow fronts, earthquakes) from the event stream, and expire after a while of simulated time.
 * {@link #delta} diffs the result against what clients already have and stamps creation, update
 * and removal times, so a client can show where something formed and when it went away.
 */
final class EntityTracker {
    /** Earthquakes at or above this magnitude become entities. */
    static final double QUAKE_MIN_MAGNITUDE = 2.0;
    /** Simulated seconds a quake stays listed. */
    static final double QUAKE_LIFETIME = 1800;
    static final int QUAKE_CAP = 100;
    /** A flow front not reported for this long (simulated s) is considered to have stopped. */
    static final double FRONT_LIFETIME = 120;
    /** Upper bound on entities streamed per session (diffuse features beyond it are skipped). */
    static final int ENTITY_CAP = 3000;

    private final GridMapping map;
    private final List<String> volcanoIds;
    /** What clients currently hold: id → entity without the time stamps. */
    private final Map<String, JsonObject> sent = new TreeMap<>();
    private final Map<String, Double> createdAt = new HashMap<>();
    private final Map<String, Double> updatedAt = new HashMap<>();
    /** Event-fed entities and the simulated time they expire. */
    private final Map<String, JsonObject> transients = new LinkedHashMap<>();
    private final Map<String, Double> expires = new HashMap<>();

    EntityTracker(GridMapping map, List<String> volcanoIds) {
        this.map = map;
        this.volcanoIds = List.copyOf(volcanoIds);
    }

    // ── Event-fed entities (pump thread) ──

    synchronized void observe(EngineEvent e) {
        switch (e) {
            case SeismicEvent q when q.magnitude() >= QUAKE_MIN_MAGNITUDE -> {
                String id = "quake:" + q.volcanoId() + ":" + Math.round(q.time() * 1000) + ":" + q.hypocenter().pack();
                JsonObject o = entity(id, "quake", q.volcanoId(),
                        String.format("M%.1f %s quake", q.magnitude(), quakeType(q.type().name())), map.point(q.hypocenter()));
                JsonObject p = o.getAsJsonObject("props");
                p.add("magnitude", Json.num(q.magnitude()));
                p.addProperty("type", q.type().name());
                p.add("time", Json.num(q.time()));
                p.add("durationSeconds", Json.num(q.durationSeconds()));
                p.addProperty("swarm", q.swarm());
                putTransient(id, o, q.time() + QUAKE_LIFETIME);
                trimQuakes();
            }
            case LavaEvents.LavaFlowFront f -> {
                JsonObject o = entity("lava:front", "lavaFront", null, "Lava flow front", map.point(f.front()));
                JsonObject p = o.getAsJsonObject("props");
                p.add("lengthM", Json.num(f.lengthM()));
                p.addProperty("activeCells", f.activeCells());
                p.add("moltenVolumeM3", Json.num(f.volumeM3()));
                putTransient("lava:front", o, f.time() + FRONT_LIFETIME);
            }
            case MassFlowEvents.PdcFront f -> {
                String id = "pdc:" + f.flowId();
                JsonObject o = entity(id, "pdc", volcanoOf(f.flowId()), "Pyroclastic flow front", map.point(f.front()));
                JsonObject p = o.getAsJsonObject("props");
                p.add("runoutM", Json.num(f.runoutM()));
                p.add("volumeM3", Json.num(f.volumeM3()));
                p.add("maxSpeedMPerS", Json.num(f.maxSpeed()));
                p.add("maxTemperatureC", Json.num(f.maxTemperatureC()));
                p.addProperty("activeCells", f.activeCells());
                putTransient(id, o, f.time() + FRONT_LIFETIME);
            }
            case MassFlowEvents.LaharFront f -> {
                String id = "lahar:" + f.flowId();
                JsonObject o = entity(id, "lahar", volcanoOf(f.flowId()), "Lahar front", map.point(f.front()));
                JsonObject p = o.getAsJsonObject("props");
                p.add("runoutM", Json.num(f.runoutM()));
                p.add("volumeM3", Json.num(f.volumeM3()));
                p.add("maxSpeedMPerS", Json.num(f.maxSpeed()));
                p.add("sedimentFraction", Json.num(f.meanSedimentFraction()));
                p.addProperty("activeCells", f.activeCells());
                putTransient(id, o, f.time() + FRONT_LIFETIME);
            }
            default -> {}
        }
    }

    private void putTransient(String id, JsonObject o, double until) {
        transients.remove(id);
        transients.put(id, o);
        expires.put(id, until);
    }

    private void trimQuakes() {
        List<String> quakes = new ArrayList<>();
        for (String id : transients.keySet()) if (id.startsWith("quake:")) quakes.add(id);
        for (int i = 0; i < quakes.size() - QUAKE_CAP; i++) {
            transients.remove(quakes.get(i));
            expires.remove(quakes.get(i));
        }
    }

    private String volcanoOf(String flowId) {
        for (String v : volcanoIds) if (flowId.contains(v)) return v;
        return volcanoIds.size() == 1 ? volcanoIds.get(0) : null;
    }

    static String quakeType(String t) {
        return switch (t) {
            case "VT" -> "rock-breaking";
            case "LP" -> "fluid (long-period)";
            case "TREMOR" -> "tremor";
            case "EXPLOSION" -> "explosion";
            default -> t.toLowerCase();
        };
    }

    // ── Engine snapshot (engine thread) ──

    /** Persistent entities read from the engine; call on the engine thread. */
    static Map<String, JsonObject> snapshot(Scenario s, GridMapping map, Map<String, List<String>> activeVents) {
        Map<String, JsonObject> out = new LinkedHashMap<>();
        WorldModel world = s.terrain().world();
        for (VolcanoSystem v : s.volcanoes()) {
            String vid = v.volcanoId();
            MagmaChamber ch = v.chamber();
            JsonObject chamber = entity("chamber:" + vid, "chamber", vid, Probe.displayName(vid) + " magma chamber",
                    Probe.chamberCenter(v, map, world));
            JsonObject cp = chamber.getAsJsonObject("props");
            cp.add("overpressureMPa", Json.num(ch.overpressureMPa()));
            cp.add("tensileStrengthMPa", Json.num(ch.config().tensileStrengthMPa()));
            cp.add("temperatureC", Json.num(ch.temperatureC()));
            cp.add("silicaWt", Json.num(ch.silicaWt()));
            cp.add("waterWt", Json.num(ch.waterWt()));
            cp.add("crystalFraction", Json.num(ch.crystalFraction()));
            cp.add("volumeM3", Json.num(ch.volumeM3()));
            cp.add("depthM", Json.num(ch.physicalDepthM()));
            cp.add("eruptionRateM3PerS", Json.num(ch.eruptionRate()));
            cp.addProperty("regime", EventTranslator.regime(ch.eruptiveRegime()));
            var style = v.classifier().style();
            if (style != null) cp.addProperty("styleEstimate", style.name());
            cp.addProperty("vei", v.classifier().vei());
            cp.add("radiusM", Json.num(Probe.displayChamberRadius(v, map, world)));
            out.put(chamber.get("id").getAsString(), chamber);

            List<String> active = activeVents.getOrDefault(vid, List.of());
            boolean erupting = ch.erupting();
            for (VentSite vent : v.coupler().allVents()) {
                boolean fissure = vent.kind() == VentKind.FISSURE;
                JsonObject o = entity("vent:" + vid + ":" + vent.id(), fissure ? "fissure" : "vent", vid,
                        fissure ? fissureLabel(vent.id()) : "Vent " + vent.id(), map.point(vent.position()));
                JsonObject p = o.getAsJsonObject("props");
                p.addProperty("ventId", vent.id());
                p.addProperty("shape", vent.kind().name());
                p.add("craterRadiusM", Json.num(vent.craterRadius() * map.cell));
                if (fissure) {
                    p.add("lengthM", Json.num(vent.fissureLength() * map.cell));
                    p.add("strikeDeg", Json.num(Math.toDegrees(vent.fissureAngleRad())));
                }
                p.addProperty("erupting", erupting && active.contains(vent.id()));
                out.put(o.get("id").getAsString(), o);
            }

            DikePropagation dikes = v.dikes();
            if (dikes != null) {
                for (Dike d : dikes.dikes()) {
                    JsonObject o = entity("dike:" + vid + ":" + d.id(), "dike", vid, "Dike " + d.id(), map.point(d.tip()));
                    JsonArray path = new JsonArray();
                    path.add(Json.xyz(map.point(d.origin())));
                    path.add(Json.xyz(map.point(d.tip())));
                    o.add("path", path);
                    JsonObject p = o.getAsJsonObject("props");
                    p.addProperty("status", d.status().name());
                    p.add("startedAt", Json.num(d.startTime()));
                    p.add("tipDepthM", Json.num(d.depthM()));
                    p.add("heightM", Json.num(d.heightM()));
                    p.add("openingM", Json.num(d.openingM()));
                    p.add("strikeLengthM", Json.num(d.strikeLengthM()));
                    p.add("speedMPerS", Json.num(d.speedMPerS()));
                    p.add("volumeM3", Json.num(d.volumeM3()));
                    if (d.fissure() != null) p.addProperty("fissure", d.fissure().id());
                    out.put(o.get("id").getAsString(), o);
                }
            }

            Geothermal g = v.geothermal();
            if (g != null) {
                for (HydrothermalFeature kind : HydrothermalFeature.values()) {
                    for (PlacedFeature f : g.features(kind)) {
                        if (out.size() >= ENTITY_CAP) break;
                        String id = "feature:" + vid + ":" + kind.name() + ":" + f.x() + ":" + f.z();
                        JsonObject o = entity(id, "feature", vid, featureLabel(kind),
                                map.point(new BlockPos(f.x(), f.y(), f.z())));
                        JsonObject p = o.getAsJsonObject("props");
                        p.addProperty("feature", kind.name());
                        // Whole degrees: finer changes every step would re-send thousands of features.
                        p.add("groundTemperatureC", Json.num(Math.round(g.temperatureAt(f.x(), f.z()))));
                        if (f.level() != 0) p.addProperty("level", f.level());
                        out.put(id, o);
                    }
                }
            }

            TephraSubsystem tephra = v.tephra();
            ExplosivePhase phase = tephra.activePhase();
            if (phase != null && tephra.plumeHeight() > 0) {
                double[] base = map.point(phase.vent().position());
                JsonObject o = entity("plume:" + vid, "plume", vid, "Eruption column", base);
                JsonObject p = o.getAsJsonObject("props");
                double ventZ = (phase.vent().position().y() + 1) * map.cell;
                p.add("topZ", Json.num(ventZ + tephra.plumeHeight() * map.cell));
                p.add("heightM", Json.num(tephra.plumeHeight() * map.cell));
                p.add("massRateKgS", Json.num(phase.massEruptionRate()));
                out.put(o.get("id").getAsString(), o);
            }

            if (v.deformation() != null) {
                for (GeodeticStation st : v.deformation().config().stations) {
                    JsonObject o = entity("station:" + vid + ":" + st.name(), "station", vid, "GNSS station " + st.name(),
                            new double[] {map.x(st.x()), map.y(st.z()),
                                    world.surfaceZ(st.x(), st.z())});
                    o.getAsJsonObject("props").addProperty("station", st.name());
                    out.put(o.get("id").getAsString(), o);
                }
            }
        }
        LavaFlow lava = s.lava();
        if (lava != null && lava.activeCellCount() > 0) {
            JsonObject o = entity("lava:field", "lavaField", null, "Molten lava", new double[] {0, 0, 0});
            JsonObject p = o.getAsJsonObject("props");
            p.addProperty("activeCells", lava.activeCellCount());
            p.add("moltenVolumeM3", Json.num(lava.totalLavaVolume()));
            p.add("emittedM3", Json.num(lava.emittedVolume()));
            p.add("solidifiedM3", Json.num(lava.solidifiedVolume()));
            o.addProperty("hidden", true); // statistics only; the front entity carries the position
            out.put("lava:field", o);
        }
        return out;
    }

    /** "Fissure from dike 2" for fissures the engine names after their dike, else "Fissure <id>". */
    static String fissureLabel(String ventId) {
        int k = ventId.lastIndexOf("-dike-");
        if (k >= 0 && k + 6 < ventId.length() && ventId.substring(k + 6).chars().allMatch(Character::isDigit)) {
            return "Fissure from dike " + ventId.substring(k + 6);
        }
        return "Fissure " + ventId;
    }

    static String featureLabel(HydrothermalFeature kind) {
        return switch (kind) {
            case FUMAROLE -> "Fumarole";
            case GEYSER -> "Geyser";
            case HOT_SPRING -> "Hot spring";
            case SULFUR_SPRING -> "Sulfur spring";
            case MUD_POT -> "Mud pot";
            case SUBMARINE_VENT -> "Submarine vent";
            case SULFUR_DEPOSIT -> "Sulfur deposit";
            case ACID_ALTERATION -> "Altered ground";
            case SINTER -> "Sinter terrace";
            default -> kind.name().charAt(0) + kind.name().substring(1).toLowerCase().replace('_', ' ');
        };
    }

    static JsonObject entity(String id, String kind, String volcanoId, String label, double[] at) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("kind", kind);
        if (volcanoId != null) o.addProperty("volcanoId", volcanoId);
        o.addProperty("label", label);
        o.add("at", Json.xyz(at));
        o.add("props", new JsonObject());
        return o;
    }

    // ── Diff (pump thread) ──

    /**
     * The {@code entities} message bringing a client from what was sent last up to {@code snapshot}
     * at simulated {@code time}, or {@code null} if nothing changed. With {@code full} every current
     * entity is sent with {@code replace: true} (attach, replay jump); this does not change what
     * other clients are assumed to hold.
     */
    synchronized JsonObject delta(Map<String, JsonObject> snapshot, double time, boolean full) {
        expire(time);
        Map<String, JsonObject> now = new TreeMap<>(snapshot);
        now.putAll(transients);
        if (full) return message(now, time, now.keySet(), List.of(), true);

        List<String> upserts = new ArrayList<>();
        for (Map.Entry<String, JsonObject> e : now.entrySet()) {
            JsonObject before = sent.get(e.getKey());
            if (before == null || !before.equals(e.getValue())) upserts.add(e.getKey());
        }
        List<String> removed = new ArrayList<>();
        for (String id : sent.keySet()) if (!now.containsKey(id)) removed.add(id);
        if (upserts.isEmpty() && removed.isEmpty()) return null;
        for (String id : upserts) {
            createdAt.putIfAbsent(id, time);
            updatedAt.put(id, time);
            sent.put(id, now.get(id).deepCopy());
        }
        for (String id : removed) {
            sent.remove(id);
            createdAt.remove(id);
            updatedAt.remove(id);
        }
        return message(now, time, upserts, removed, false);
    }

    /**
     * Full state for an attaching client. Entities other clients have not been sent yet get their
     * creation time now; the next {@link #delta} still announces them to everyone.
     */
    synchronized JsonObject full(Map<String, JsonObject> snapshot, double time) {
        expire(time);
        for (String id : snapshot.keySet()) createdAt.putIfAbsent(id, time);
        for (String id : transients.keySet()) createdAt.putIfAbsent(id, time);
        return delta(snapshot, time, true);
    }

    /** Forget everything (world switched, replay jump): the next delta re-creates all entities. */
    synchronized void reset() {
        sent.clear();
        createdAt.clear();
        updatedAt.clear();
        transients.clear();
        expires.clear();
    }

    private void expire(double time) {
        transients.entrySet().removeIf(e -> {
            Double until = expires.get(e.getKey());
            boolean gone = until != null && (until < time || until - time > 10 * QUAKE_LIFETIME);
            if (gone) expires.remove(e.getKey());
            return gone;
        });
    }

    private JsonObject message(Map<String, JsonObject> now, double time, Iterable<String> upserts, List<String> removed,
            boolean replace) {
        JsonObject msg = Json.obj("entities");
        msg.add("time", Json.num(time));
        msg.addProperty("replace", replace);
        JsonArray up = new JsonArray();
        for (String id : upserts) {
            JsonObject e = now.get(id).deepCopy();
            Double c = createdAt.get(id);
            Double u = updatedAt.get(id);
            e.add("createdAt", Json.num(c == null ? time : c));
            e.add("updatedAt", Json.num(u == null ? time : u));
            up.add(e);
        }
        msg.add("upsert", up);
        JsonArray rm = new JsonArray();
        for (String id : removed) rm.add(id);
        msg.add("remove", rm);
        return msg;
    }

    /** Ids currently held by clients (tests). */
    synchronized List<String> ids() {
        return new ArrayList<>(sent.keySet());
    }
}
