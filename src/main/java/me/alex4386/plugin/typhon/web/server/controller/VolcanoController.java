package me.alex4386.plugin.typhon.web.server.controller;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonScheduler;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.log.VolcanoVentEjectaTimeData;
import me.alex4386.plugin.typhon.volcano.log.VolcanoVentRecord;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentBuilder;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentBuilderType;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentStatus;
import me.alex4386.plugin.typhon.web.server.TyphonAPIRequest;
import me.alex4386.plugin.typhon.web.server.TyphonAPIResponse;
import org.bukkit.Location;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class VolcanoController {

    public static final VolcanoController INSTANCE = new VolcanoController();

    private VolcanoController() {}

    // ── Volcano routes ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public TyphonAPIResponse listVolcanoes(TyphonAPIRequest request) {
        JSONArray arr = new JSONArray();
        for (Volcano volcano : TyphonPlugin.listVolcanoes.values()) {
            arr.add(volcanoToJson(volcano, false));
        }
        return new TyphonAPIResponse().json(arr);
    }

    public TyphonAPIResponse getVolcano(TyphonAPIRequest request) {
        String name = request.getPathParam("name");
        Volcano volcano = TyphonPlugin.listVolcanoes.get(name);
        if (volcano == null) return ResponseHelper.notFound("Volcano '" + name + "' not found");
        return new TyphonAPIResponse().json(volcanoToJson(volcano, true));
    }

    // ── Vent routes ──────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public TyphonAPIResponse listVents(TyphonAPIRequest request) {
        String name = request.getPathParam("name");
        Volcano volcano = TyphonPlugin.listVolcanoes.get(name);
        if (volcano == null) return ResponseHelper.notFound("Volcano '" + name + "' not found");

        JSONArray arr = new JSONArray();
        for (VolcanoVent vent : volcano.manager.getVents()) {
            arr.add(ventToJson(vent, true));
        }
        return new TyphonAPIResponse().json(arr);
    }

    public TyphonAPIResponse getVent(TyphonAPIRequest request) {
        String name = request.getPathParam("name");
        String ventName = request.getPathParam("vent");

        Volcano volcano = TyphonPlugin.listVolcanoes.get(name);
        if (volcano == null) return ResponseHelper.notFound("Volcano '" + name + "' not found");

        VolcanoVent vent = ResponseHelper.resolveVent(volcano, ventName);
        if (vent == null) return ResponseHelper.notFound("Vent '" + ventName + "' not found in volcano '" + name + "'");

        return new TyphonAPIResponse().json(ventToJson(vent, true));
    }

    // ── Vent metrics (lightweight, poll-friendly) ─────────────────────────

    public TyphonAPIResponse getVentMetrics(TyphonAPIRequest request) {
        String name = request.getPathParam("name");
        String ventName = request.getPathParam("vent");

        Volcano volcano = TyphonPlugin.listVolcanoes.get(name);
        if (volcano == null) return ResponseHelper.notFound("Volcano '" + name + "' not found");

        VolcanoVent vent = ResponseHelper.resolveVent(volcano, ventName);
        if (vent == null) return ResponseHelper.notFound("Vent '" + ventName + "' not found");

        return new TyphonAPIResponse().json(ventMetricsToJson(vent));
    }

    // ── Vent actions ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public TyphonAPIResponse startVent(TyphonAPIRequest request) {
        String name = request.getPathParam("name");
        String ventName = request.getPathParam("vent");

        Volcano volcano = TyphonPlugin.listVolcanoes.get(name);
        if (volcano == null) return ResponseHelper.notFound("Volcano '" + name + "' not found");

        VolcanoVent vent = ResponseHelper.resolveVent(volcano, ventName);
        if (vent == null) return ResponseHelper.notFound("Vent '" + ventName + "' not found");

        try {
            CompletableFuture<Void> future = new CompletableFuture<>();
            TyphonScheduler.run(null, () -> {
                try {
                    vent.start();
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return ResponseHelper.serverError("Failed to start vent: " + e.getMessage());
        }

        JSONObject json = new JSONObject();
        json.put("ok", true);
        json.put("message", "Vent '" + (ventName) + "' started");
        return new TyphonAPIResponse().json(json);
    }

    @SuppressWarnings("unchecked")
    public TyphonAPIResponse stopVent(TyphonAPIRequest request) {
        String name = request.getPathParam("name");
        String ventName = request.getPathParam("vent");

        Volcano volcano = TyphonPlugin.listVolcanoes.get(name);
        if (volcano == null) return ResponseHelper.notFound("Volcano '" + name + "' not found");

        VolcanoVent vent = ResponseHelper.resolveVent(volcano, ventName);
        if (vent == null) return ResponseHelper.notFound("Vent '" + ventName + "' not found");

        try {
            CompletableFuture<Void> future = new CompletableFuture<>();
            TyphonScheduler.run(null, () -> {
                try {
                    vent.stop();
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return ResponseHelper.serverError("Failed to stop vent: " + e.getMessage());
        }

        JSONObject json = new JSONObject();
        json.put("ok", true);
        json.put("message", "Vent '" + (ventName) + "' stopped");
        return new TyphonAPIResponse().json(json);
    }

    @SuppressWarnings("unchecked")
    public TyphonAPIResponse setVentStatus(TyphonAPIRequest request) {
        String name = request.getPathParam("name");
        String ventName = request.getPathParam("vent");

        Volcano volcano = TyphonPlugin.listVolcanoes.get(name);
        if (volcano == null) return ResponseHelper.notFound("Volcano '" + name + "' not found");

        VolcanoVent vent = ResponseHelper.resolveVent(volcano, ventName);
        if (vent == null) return ResponseHelper.notFound("Vent '" + ventName + "' not found");

        JSONObject body = ResponseHelper.parseJsonBody(request);
        if (body == null || !body.containsKey("status"))
            return ResponseHelper.badRequest("Missing 'status' field");

        String statusStr = (String) body.get("status");
        VolcanoVentStatus newStatus = VolcanoVentStatus.getStatus(statusStr);
        if (newStatus == null)
            return ResponseHelper.badRequest("Invalid status: " + statusStr);

        try {
            CompletableFuture<Void> future = new CompletableFuture<>();
            TyphonScheduler.run(null, () -> {
                try {
                    vent.setStatus(newStatus);
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return ResponseHelper.serverError("Failed to set status: " + e.getMessage());
        }

        JSONObject json = new JSONObject();
        json.put("ok", true);
        json.put("status", newStatus.toString());
        return new TyphonAPIResponse().json(json);
    }

    // ── Builder ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public TyphonAPIResponse getBuilder(TyphonAPIRequest request) {
        String name = request.getPathParam("name");
        String ventName = request.getPathParam("vent");

        Volcano volcano = TyphonPlugin.listVolcanoes.get(name);
        if (volcano == null) return ResponseHelper.notFound("Volcano '" + name + "' not found");

        VolcanoVent vent = ResponseHelper.resolveVent(volcano, ventName);
        if (vent == null) return ResponseHelper.notFound("Vent '" + ventName + "' not found");

        return new TyphonAPIResponse().json(builderToJson(vent.builder));
    }

    @SuppressWarnings("unchecked")
    public TyphonAPIResponse configureBuilder(TyphonAPIRequest request) {
        String name = request.getPathParam("name");
        String ventName = request.getPathParam("vent");

        Volcano volcano = TyphonPlugin.listVolcanoes.get(name);
        if (volcano == null) return ResponseHelper.notFound("Volcano '" + name + "' not found");

        VolcanoVent vent = ResponseHelper.resolveVent(volcano, ventName);
        if (vent == null) return ResponseHelper.notFound("Vent '" + ventName + "' not found");

        JSONObject body = ResponseHelper.parseJsonBody(request);
        if (body == null) return ResponseHelper.badRequest("Invalid JSON body");

        try {
            CompletableFuture<String> future = new CompletableFuture<>();
            TyphonScheduler.run(null, () -> {
                try {
                    // Handle enabled field
                    if (body.containsKey("enabled")) {
                        boolean enabled = (Boolean) body.get("enabled");
                        vent.builder.setEnabled(enabled);
                    }

                    // Handle type field
                    if (body.containsKey("type")) {
                        Object typeVal = body.get("type");
                        if (typeVal == null) {
                            vent.builder.setType(null);
                            vent.builder.setEnabled(false);
                        } else {
                            VolcanoVentBuilderType type = VolcanoVentBuilderType.fromName((String) typeVal);
                            if (type == null) {
                                future.complete("Invalid builder type: " + typeVal);
                                return;
                            }
                            vent.builder.setType(type);
                        }
                    }

                    // Handle arguments
                    if (body.containsKey("args") && vent.builder.getType() != null) {
                        JSONObject argsObj = (JSONObject) body.get("args");
                        if (argsObj != null) {
                            // For Y_THRESHOLD, extract y_threshold arg
                            if (vent.builder.getType() == VolcanoVentBuilderType.Y_THRESHOLD) {
                                if (argsObj.containsKey("y_threshold")) {
                                    String[] builderArgs = new String[]{ String.valueOf(argsObj.get("y_threshold")) };
                                    vent.builder.setArguments(builderArgs);
                                }
                            }
                        }
                    }

                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });

            String error = future.get(5, TimeUnit.SECONDS);
            if (error != null) return ResponseHelper.badRequest(error);
        } catch (Exception e) {
            return ResponseHelper.serverError("Failed to configure builder: " + e.getMessage());
        }

        JSONObject json = new JSONObject();
        json.put("ok", true);
        json.put("builder", builderToJson(vent.builder));
        return new TyphonAPIResponse().json(json);
    }

    // ── JSON serialization ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public static JSONObject builderToJson(VolcanoVentBuilder builder) {
        JSONObject json = new JSONObject();
        json.put("enabled", builder.isRunning());
        json.put("type", builder.getType() != null ? builder.getType().getName() : null);
        json.put("args", builder.getArgumentMap());
        return json;
    }

    @SuppressWarnings("unchecked")
    public static JSONObject locationToJson(Location loc) {
        JSONObject json = new JSONObject();
        if (loc == null) return json;
        json.put("world", loc.getWorld() != null ? loc.getWorld().getName() : null);
        json.put("x", loc.getBlockX());
        json.put("y", loc.getBlockY());
        json.put("z", loc.getBlockZ());
        return json;
    }

    @SuppressWarnings("unchecked")
    public static JSONObject ventToJson(VolcanoVent vent, boolean detailed) {
        JSONObject json = new JSONObject();
        try {
            json.put("name", vent.name != null ? vent.name : "main");
            json.put("type", vent.getType() != null ? vent.getType().toString() : "UNKNOWN");
            json.put("status", vent.getStatus() != null ? vent.getStatus().toString() : "UNKNOWN");
            json.put("style", vent.erupt != null && vent.erupt.getStyle() != null ? vent.erupt.getStyle().toString() : "UNKNOWN");
            json.put("enabled", vent.enabled);

            if (detailed) {
                json.put("location", locationToJson(vent.location));
                json.put("craterRadius", vent.craterRadius);
                json.put("longestFlowLength", vent.longestFlowLength);
                json.put("silicateLevel", vent.lavaFlow != null ? vent.lavaFlow.settings.silicateLevel : 0);
                json.put("fissureAngle", vent.fissureAngle);
                json.put("calderaRadius", vent.calderaRadius);
                json.put("isCaldera", vent.isCaldera());

                int baseY = vent.location != null ? vent.location.getBlockY() : 0;
                json.put("baseY", baseY);

                int summitY = baseY;
                try {
                    org.bukkit.block.Block summit = vent.isCacheInitialized() ? vent.getSummitBlock() : null;
                    if (summit != null) {
                        summitY = summit.getY();
                        json.put("summitBlock", locationToJson(summit.getLocation()));
                    }
                } catch (Exception ignored) {}
                json.put("summitY", summitY);

                try {
                    json.put("seaLevel", vent.location != null && vent.location.getWorld() != null ? vent.location.getWorld().getSeaLevel() : 63);
                } catch (Exception ignored) { json.put("seaLevel", 63); }

                try {
                    json.put("averageVentHeight", vent.isCacheInitialized() ? vent.averageVentHeight() : baseY);
                } catch (Exception ignored) { json.put("averageVentHeight", baseY); }

                try {
                    json.put("bombMaxDistance", vent.bombs != null ? vent.bombs.maxDistance : 0);
                } catch (Exception ignored) { json.put("bombMaxDistance", 0); }

                json.put("statusScaleFactor", vent.getStatus() != null ? vent.getStatus().getScaleFactor() : 0);

                try { json.put("isErupting", vent.erupt != null && vent.erupt.isErupting()); }
                catch (Exception ignored) { json.put("isErupting", false); }

                try { json.put("isFlowingLava", vent.isFlowingLava()); }
                catch (Exception ignored) { json.put("isFlowingLava", false); }

                try { json.put("isExploding", vent.isExploding()); }
                catch (Exception ignored) { json.put("isExploding", false); }

                try {
                    json.put("longestNormalLavaFlowLength", vent.longestNormalLavaFlowLength);
                    json.put("currentNormalLavaFlowLength", vent.currentNormalLavaFlowLength);
                    json.put("currentFlowLength", vent.currentFlowLength);
                    json.put("longestFlowLength", vent.longestFlowLength);
                } catch (Exception ignored) {
                    json.put("longestNormalLavaFlowLength", 0);
                    json.put("currentNormalLavaFlowLength", 0);
                    json.put("currentFlowLength", 0);
                    json.put("longestFlowLength", 0);
                }

                try {
                    json.put("longestAshFlowLength", vent.longestAshNormalFlowLength);
                    json.put("currentAshFlowLength", vent.currentAshNormalFlowLength);
                } catch (Exception ignored) {
                    json.put("longestAshFlowLength", 0);
                    json.put("currentAshFlowLength", 0);
                }

                try {
                    json.put("currentEjecta", vent.record.currentEjectaVolume);
                    json.put("totalEjecta", vent.record.getTotalEjecta());
                } catch (Exception ignored) {
                    json.put("currentEjecta", 0);
                    json.put("totalEjecta", 0);
                }

                try {
                    json.put("plumbedBlocksPerSecond", vent.lavaFlow != null ? vent.lavaFlow.getPlumbedBlocksPerSecond() : 0);
                    json.put("successfulPlumbsPerSecond", vent.lavaFlow != null ? vent.lavaFlow.getSuccessfulPlumbsPerSecond() : 0);
                    json.put("normalFlowEndBlocks", vent.lavaFlow != null ? vent.lavaFlow.getNormalFlowEndBlockCount() : 0);
                    json.put("pillowFlowEndBlocks", vent.lavaFlow != null ? vent.lavaFlow.getPillowFlowEndBlockCount() : 0);
                    json.put("underfillTargets", vent.lavaFlow != null ? vent.lavaFlow.underfillTargets.size() : 0);
                    json.put("underfillLavaBlocks", vent.lavaFlow != null ? vent.lavaFlow.getUnderfillLavaCount() : 0);
                } catch (Exception ignored) {
                    json.put("plumbedBlocksPerSecond", 0);
                    json.put("successfulPlumbsPerSecond", 0);
                    json.put("normalFlowEndBlocks", 0);
                    json.put("pillowFlowEndBlocks", 0);
                    json.put("underfillTargets", 0);
                    json.put("underfillLavaBlocks", 0);
                }

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return json;
    }

    @SuppressWarnings("unchecked")
    public static JSONObject ventMetricsToJson(VolcanoVent vent) {
        JSONObject json = new JSONObject();
        try {
            json.put("status", vent.getStatus() != null ? vent.getStatus().toString() : "UNKNOWN");
            json.put("statusScaleFactor", vent.getStatus() != null ? vent.getStatus().getScaleFactor() : 0);

            try { json.put("isErupting", vent.erupt != null && vent.erupt.isErupting()); }
            catch (Exception ignored) { json.put("isErupting", false); }

            try { json.put("isFlowingLava", vent.isFlowingLava()); }
            catch (Exception ignored) { json.put("isFlowingLava", false); }

            try { json.put("isExploding", vent.isExploding()); }
            catch (Exception ignored) { json.put("isExploding", false); }

            try {
                json.put("currentNormalLavaFlowLength", vent.currentNormalLavaFlowLength);
                json.put("longestNormalLavaFlowLength", vent.longestNormalLavaFlowLength);
                json.put("currentFlowLength", vent.currentFlowLength);
                json.put("longestFlowLength", vent.longestFlowLength);
            } catch (Exception ignored) {}

            try {
                json.put("currentAshFlowLength", vent.currentAshNormalFlowLength);
                json.put("longestAshFlowLength", vent.longestAshNormalFlowLength);
            } catch (Exception ignored) {}

            try {
                json.put("currentEjecta", vent.record.currentEjectaVolume);
                json.put("totalEjecta", vent.record.getTotalEjecta());
            } catch (Exception ignored) {}

            try { json.put("ejectaPerSecond", vent.lavaFlow != null ? vent.lavaFlow.getProcessedBlocksPerSecond() : 0); }
            catch (Exception ignored) { json.put("ejectaPerSecond", 0); }

            try { json.put("lavaFlowsPerSecond", vent.lavaFlow != null ? vent.lavaFlow.getFlowedBlocksPerSecond() : 0); }
            catch (Exception ignored) { json.put("lavaFlowsPerSecond", 0); }

            try { json.put("activeLavaBlocks", vent.lavaFlow != null ? vent.lavaFlow.getActiveLavaCount() : 0); }
            catch (Exception ignored) { json.put("activeLavaBlocks", 0); }

            try { json.put("terminalLavaBlocks", vent.lavaFlow != null ? vent.lavaFlow.getTerminalBlockCount() : 0); }
            catch (Exception ignored) { json.put("terminalLavaBlocks", 0); }

            try {
                json.put("plumbedBlocksPerSecond", vent.lavaFlow != null ? vent.lavaFlow.getPlumbedBlocksPerSecond() : 0);
                json.put("successfulPlumbsPerSecond", vent.lavaFlow != null ? vent.lavaFlow.getSuccessfulPlumbsPerSecond() : 0);
                json.put("normalFlowEndBlocks", vent.lavaFlow != null ? vent.lavaFlow.getNormalFlowEndBlockCount() : 0);
                json.put("pillowFlowEndBlocks", vent.lavaFlow != null ? vent.lavaFlow.getPillowFlowEndBlockCount() : 0);
                json.put("underfillTargets", vent.lavaFlow != null ? vent.lavaFlow.underfillTargets.size() : 0);
                json.put("underfillLavaBlocks", vent.lavaFlow != null ? vent.lavaFlow.getUnderfillLavaCount() : 0);
            } catch (Exception ignored) {
                json.put("plumbedBlocksPerSecond", 0);
                json.put("successfulPlumbsPerSecond", 0);
                json.put("normalFlowEndBlocks", 0);
                json.put("pillowFlowEndBlocks", 0);
                json.put("underfillTargets", 0);
                json.put("underfillLavaBlocks", 0);
            }

            try { json.put("bombsPerSecond", vent.bombs != null ? vent.bombs.getLaunchRate() : 0); }
            catch (Exception ignored) { json.put("bombsPerSecond", 0); }

            try { json.put("activeBombs", vent.bombs != null ? vent.bombs.bombMap.size() : 0); }
            catch (Exception ignored) { json.put("activeBombs", 0); }

            try { json.put("maxActiveBombs", vent.bombs != null ? vent.bombs.maximumFallingBlocks : 0); }
            catch (Exception ignored) { json.put("maxActiveBombs", 0); }

            try { json.put("bombMaxDistance", vent.bombs != null ? vent.bombs.maxDistance : 0); }
            catch (Exception ignored) { json.put("bombMaxDistance", 0); }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return json;
    }

    // ── Vent record (eruption history) ─────────────────────────────────

    public TyphonAPIResponse getVentRecord(TyphonAPIRequest request) {
        String name = request.getPathParam("name");
        String ventName = request.getPathParam("vent");

        Volcano volcano = TyphonPlugin.listVolcanoes.get(name);
        if (volcano == null) return ResponseHelper.notFound("Volcano '" + name + "' not found");

        VolcanoVent vent = ResponseHelper.resolveVent(volcano, ventName);
        if (vent == null) return ResponseHelper.notFound("Vent '" + ventName + "' not found");

        return new TyphonAPIResponse().json(recordToJson(vent.record));
    }

    @SuppressWarnings("unchecked")
    public static JSONObject recordToJson(VolcanoVentRecord record) {
        JSONObject json = new JSONObject();
        json.put("currentEjecta", record.currentEjectaVolume);
        json.put("totalEjecta", record.getTotalEjecta());
        json.put("startEjectaTracking", record.startEjectaTracking);

        JSONArray records = new JSONArray();
        for (VolcanoVentEjectaTimeData entry : record.ejectaVolumeList) {
            JSONObject rec = new JSONObject();
            rec.put("startTime", entry.startTime);
            rec.put("endTime", entry.endTime);
            rec.put("ejectaVolume", entry.ejectaVolume);

            if (entry.hasMetadata()) {
                JSONObject meta = new JSONObject();
                JSONObject summit = new JSONObject();
                summit.put("x", entry.summitX);
                summit.put("y", entry.summitY);
                summit.put("z", entry.summitZ);
                meta.put("summit", summit);
                meta.put("baseY", entry.baseY);
                meta.put("silicateLevel", entry.silicateLevel);
                meta.put("gasContent", entry.gasContent);
                meta.put("eruptionStyle", entry.eruptionStyle);
                meta.put("ventType", entry.ventType);
                meta.put("craterRadius", entry.craterRadius);
                meta.put("longestFlowLength", entry.longestFlowLength);
                meta.put("longestNormalLavaFlowLength", entry.longestNormalLavaFlowLength);
                meta.put("currentFlowLength", entry.currentFlowLength);
                meta.put("currentNormalLavaFlowLength", entry.currentNormalLavaFlowLength);
                rec.put("metadata", meta);
            }

            records.add(rec);
        }
        json.put("records", records);

        return json;
    }

    @SuppressWarnings("unchecked")
    private static JSONObject volcanoToJson(Volcano volcano, boolean detailed) {
        JSONObject json = new JSONObject();
        json.put("name", volcano.name);
        json.put("location", locationToJson(volcano.location));
        json.put("status", volcano.mainVent.getStatus().toString());
        json.put("style", volcano.mainVent.erupt.getStyle().toString());
        json.put("ventCount", 1 + (volcano.subVents != null ? volcano.subVents.size() : 0));

        if (detailed) {
            json.put("silicateLevel", volcano.silicateLevel);

            JSONArray vents = new JSONArray();
            for (VolcanoVent vent : volcano.manager.getVents()) {
                vents.add(ventToJson(vent, false));
            }
            json.put("vents", vents);
        }
        return json;
    }
}
