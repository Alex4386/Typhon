package me.alex4386.plugin.typhon.web.server;

import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.web.server.controller.VentConfigController;
import me.alex4386.plugin.typhon.web.server.controller.VolcanoController;
import org.bukkit.Bukkit;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TyphonAPIRouter {
    private final List<Route> routes = new ArrayList<>();
    private final TyphonAPIAuth auth;

    public TyphonAPIRouter(TyphonAPIAuth auth) {
        this.auth = auth;
        registerV1Routes();
    }

    // ── Route registration ──────────────────────────────────────────────

    private void registerV1Routes() {
        // Public (no auth required)
        register("GET", "/v1/version", this::handleVersion, true);
        register("GET", "/v1/health", this::handleHealth, true);
        register("GET", "/v1/auth", this::handleAuth, true);
        register("GET", "/v1/tps", this::handleTps, true);

        // Authenticated
        register("GET", "/v1/settings", this::handleSettings);

        // Volcanoes
        register("GET", "/v1/volcanoes", VolcanoController.INSTANCE::listVolcanoes);
        register("GET", "/v1/volcanoes/{name}", VolcanoController.INSTANCE::getVolcano);

        // Vents
        register("GET", "/v1/volcanoes/{name}/vents", VolcanoController.INSTANCE::listVents);
        register("GET", "/v1/volcanoes/{name}/vents/{vent}", VolcanoController.INSTANCE::getVent);

        // Vent metrics (lightweight, for polling)
        register("GET", "/v1/volcanoes/{name}/vents/{vent}/metrics", VolcanoController.INSTANCE::getVentMetrics);

        // Vent actions
        register("POST", "/v1/volcanoes/{name}/vents/{vent}/start", VolcanoController.INSTANCE::startVent);
        register("POST", "/v1/volcanoes/{name}/vents/{vent}/stop", VolcanoController.INSTANCE::stopVent);
        register("POST", "/v1/volcanoes/{name}/vents/{vent}/status", VolcanoController.INSTANCE::setVentStatus);

        // Vent configuration
        register("GET", "/v1/volcanoes/{name}/vents/{vent}/config", VentConfigController.INSTANCE::getConfig);
        register("PATCH", "/v1/volcanoes/{name}/vents/{vent}/config", VentConfigController.INSTANCE::patchConfig);

        // Vent builder
        register("GET", "/v1/volcanoes/{name}/vents/{vent}/builder", VolcanoController.INSTANCE::getBuilder);
        register("POST", "/v1/volcanoes/{name}/vents/{vent}/builder", VolcanoController.INSTANCE::configureBuilder);

        // Vent record (eruption history)
        register("GET", "/v1/volcanoes/{name}/vents/{vent}/record", VolcanoController.INSTANCE::getVentRecord);
    }

    // ── System handlers (small, kept inline) ─────────────────────────────

    @SuppressWarnings("unchecked")
    private TyphonAPIResponse handleVersion(TyphonAPIRequest request) {
        JSONObject json = new JSONObject();
        json.put("plugin", "Typhon");
        json.put("version", TyphonPlugin.version);
        return new TyphonAPIResponse().json(json);
    }

    @SuppressWarnings("unchecked")
    private TyphonAPIResponse handleHealth(TyphonAPIRequest request) {
        JSONObject json = new JSONObject();
        json.put("status", "ok");
        return new TyphonAPIResponse().json(json);
    }

    @SuppressWarnings("unchecked")
    private TyphonAPIResponse handleAuth(TyphonAPIRequest request) {
        JSONObject json = new JSONObject();
        json.put("authenticated", auth.authenticate(request));
        json.put("authConfigured", auth.isConfigured());
        return new TyphonAPIResponse().json(json);
    }

    @SuppressWarnings("unchecked")
    private TyphonAPIResponse handleTps(TyphonAPIRequest request) {
        JSONObject json = new JSONObject();
        try {
            double[] tps = Bukkit.getServer().getTPS();
            json.put("tps1m", Math.min(tps[0], 20.0));
            json.put("tps5m", Math.min(tps[1], 20.0));
            json.put("tps15m", Math.min(tps[2], 20.0));
        } catch (Exception e) {
            json.put("tps1m", 20.0);
            json.put("tps5m", 20.0);
            json.put("tps15m", 20.0);
        }
        try {
            json.put("mspt", Bukkit.getServer().getAverageTickTime());
        } catch (Exception e) {
            json.put("mspt", 0.0);
        }
        return new TyphonAPIResponse().json(json);
    }

    @SuppressWarnings("unchecked")
    private TyphonAPIResponse handleSettings(TyphonAPIRequest request) {
        JSONObject json = new JSONObject();

        JSONObject blueMap = new JSONObject();
        blueMap.put("publicUrl", TyphonPlugin.blueMapPublicUrl);
        json.put("blueMap", blueMap);

        return new TyphonAPIResponse().json(json);
    }

    // ── Routing infrastructure ──────────────────────────────────────────

    public void register(String method, String path, TyphonAPIRequestHandler handler) {
        register(method, path, handler, false);
    }

    public void register(String method, String path, TyphonAPIRequestHandler handler, boolean isPublic) {
        routes.add(new Route(method.toUpperCase(), path, handler, isPublic));
    }

    @SuppressWarnings("unchecked")
    public TyphonAPIResponse dispatch(TyphonAPIRequest request) {
        String method = request.getMethod().toUpperCase();
        String path = request.getPath();

        for (Route route : routes) {
            Map<String, String> params = route.match(method, path);
            if (params != null) {
                // Auth check for non-public routes
                if (!route.isPublic && !auth.authenticate(request)) {
                    JSONObject errorJson = new JSONObject();
                    errorJson.put("error", "Unauthorized");
                    return new TyphonAPIResponse().status(401).json(errorJson);
                }

                request.setPathParams(params);
                try {
                    return route.handler.handle(request);
                } catch (Exception e) {
                    JSONObject errorJson = new JSONObject();
                    errorJson.put("error", "Internal Server Error");
                    errorJson.put("message", e.getMessage());
                    return new TyphonAPIResponse().status(500).json(errorJson);
                }
            }
        }

        JSONObject errorJson = new JSONObject();
        errorJson.put("error", "Not Found");
        errorJson.put("path", path);
        return new TyphonAPIResponse().status(404).json(errorJson);
    }

    private static class Route {
        final String method;
        final String pattern;
        final String[] patternParts;
        final TyphonAPIRequestHandler handler;
        final boolean isPublic;

        Route(String method, String pattern, TyphonAPIRequestHandler handler, boolean isPublic) {
            this.method = method;
            this.pattern = pattern;
            this.patternParts = pattern.split("/");
            this.handler = handler;
            this.isPublic = isPublic;
        }

        Map<String, String> match(String method, String requestPath) {
            if (!this.method.equals(method)) return null;

            String[] requestParts = requestPath.split("/");
            if (patternParts.length != requestParts.length) return null;

            Map<String, String> params = new HashMap<>();
            for (int i = 0; i < patternParts.length; i++) {
                String pp = patternParts[i];
                if (pp.startsWith("{") && pp.endsWith("}")) {
                    params.put(pp.substring(1, pp.length() - 1), requestParts[i]);
                } else if (!pp.equals(requestParts[i])) {
                    return null;
                }
            }
            return params;
        }
    }
}
