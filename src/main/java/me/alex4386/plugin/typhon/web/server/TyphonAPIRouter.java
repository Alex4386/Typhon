package me.alex4386.plugin.typhon.web.server;

import me.alex4386.plugin.typhon.TyphonPlugin;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class TyphonAPIRouter {
    private final List<Route> routes = new ArrayList<>();
    private final TyphonAPIAuth auth;

    public TyphonAPIRouter(TyphonAPIAuth auth) {
        this.auth = auth;
        registerDefaultRoutes();
    }

    private void registerDefaultRoutes() {
        register("GET", "/api/status", request -> {
            JSONObject json = new JSONObject();
            json.put("status", "ok");
            json.put("plugin", "Typhon");
            json.put("version", TyphonPlugin.version);
            json.put("volcanoCount", TyphonPlugin.listVolcanoes.size());
            return new TyphonAPIResponse().json(json);
        });
    }

    public void register(String method, String path, TyphonAPIRequestHandler handler) {
        routes.add(new Route(method.toUpperCase(), path, handler));
    }

    public TyphonAPIResponse dispatch(TyphonAPIRequest request) {
        // Authentication check
        if (!auth.authenticate(request)) {
            JSONObject errorJson = new JSONObject();
            errorJson.put("error", "Unauthorized");
            return new TyphonAPIResponse().status(401).json(errorJson);
        }

        String method = request.getMethod().toUpperCase();
        String path = request.getPath();

        for (Route route : routes) {
            if (route.matches(method, path)) {
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
        final String path;
        final TyphonAPIRequestHandler handler;

        Route(String method, String path, TyphonAPIRequestHandler handler) {
            this.method = method;
            this.path = path;
            this.handler = handler;
        }

        boolean matches(String method, String requestPath) {
            return this.method.equals(method) && this.path.equals(requestPath);
        }
    }
}
