package me.alex4386.plugin.typhon.web.server.controller;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.TyphonScheduler;
import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVentConfigNodeManager;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.web.server.TyphonAPIRequest;
import me.alex4386.plugin.typhon.web.server.TyphonAPIResponse;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class VentConfigController {

    public static final VentConfigController INSTANCE = new VentConfigController();

    private VentConfigController() {}

    public TyphonAPIResponse getConfig(TyphonAPIRequest request) {
        String name = request.getPathParam("name");
        String ventName = request.getPathParam("vent");

        Volcano volcano = TyphonPlugin.listVolcanoes.get(name);
        if (volcano == null) return ResponseHelper.notFound("Volcano '" + name + "' not found");

        VolcanoVent vent = ResponseHelper.resolveVent(volcano, ventName);
        if (vent == null) return ResponseHelper.notFound("Vent '" + ventName + "' not found");

        return new TyphonAPIResponse().json(VolcanoVentConfigNodeManager.allToJson(vent));
    }

    /**
     * PATCH /config — partial update, body is {"key": "value", ...}
     * Returns the full config after applying changes.
     */
    @SuppressWarnings("unchecked")
    public TyphonAPIResponse patchConfig(TyphonAPIRequest request) {
        String name = request.getPathParam("name");
        String ventName = request.getPathParam("vent");

        Volcano volcano = TyphonPlugin.listVolcanoes.get(name);
        if (volcano == null) return ResponseHelper.notFound("Volcano '" + name + "' not found");

        VolcanoVent vent = ResponseHelper.resolveVent(volcano, ventName);
        if (vent == null) return ResponseHelper.notFound("Vent '" + ventName + "' not found");

        JSONObject body = ResponseHelper.parseJsonBody(request);
        if (body == null) return ResponseHelper.badRequest("Invalid JSON body");

        // Validate all keys before applying any changes
        List<String> keys = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (Object entryObj : body.entrySet()) {
            Map.Entry<String, Object> entry = (Map.Entry<String, Object>) entryObj;
            String nodeKey = entry.getKey();
            if (VolcanoVentConfigNodeManager.getInfo(nodeKey) == null) {
                return ResponseHelper.badRequest("Unknown config node: " + nodeKey);
            }
            if (entry.getValue() == null) {
                return ResponseHelper.badRequest("Null value for node: " + nodeKey);
            }
            keys.add(nodeKey);
            values.add(entry.getValue().toString());
        }

        // Apply all changes on the main thread
        try {
            CompletableFuture<String> future = new CompletableFuture<>();
            TyphonScheduler.run(null, () -> {
                try {
                    for (int i = 0; i < keys.size(); i++) {
                        String err = VolcanoVentConfigNodeManager.setValue(vent, keys.get(i), values.get(i));
                        if (err != null) {
                            future.complete(err);
                            return;
                        }
                    }
                    volcano.trySave(true);
                    future.complete(null);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
            String err = future.get(5, TimeUnit.SECONDS);
            if (err != null) return ResponseHelper.badRequest(err);
        } catch (Exception e) {
            return ResponseHelper.serverError("Failed to set config: " + e.getMessage());
        }

        return new TyphonAPIResponse().json(VolcanoVentConfigNodeManager.allToJson(vent));
    }
}
