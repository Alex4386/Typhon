package me.alex4386.plugin.typhon.web.server.controller;

import me.alex4386.plugin.typhon.volcano.Volcano;
import me.alex4386.plugin.typhon.volcano.vent.VolcanoVent;
import me.alex4386.plugin.typhon.web.server.TyphonAPIRequest;
import me.alex4386.plugin.typhon.web.server.TyphonAPIResponse;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

@SuppressWarnings("unchecked")
public class ResponseHelper {

    public static TyphonAPIResponse notFound(String message) {
        JSONObject json = new JSONObject();
        json.put("error", "Not Found");
        json.put("message", message);
        return new TyphonAPIResponse().status(404).json(json);
    }

    public static TyphonAPIResponse badRequest(String message) {
        JSONObject json = new JSONObject();
        json.put("error", "Bad Request");
        json.put("message", message);
        return new TyphonAPIResponse().status(400).json(json);
    }

    public static TyphonAPIResponse serverError(String message) {
        JSONObject json = new JSONObject();
        json.put("error", "Internal Server Error");
        json.put("message", message);
        return new TyphonAPIResponse().status(500).json(json);
    }

    public static JSONObject parseJsonBody(TyphonAPIRequest request) {
        try {
            return (JSONObject) new JSONParser().parse(request.getBody());
        } catch (Exception e) {
            return null;
        }
    }

    public static VolcanoVent resolveVent(Volcano volcano, String ventName) {
        if ("main".equalsIgnoreCase(ventName)) {
            return volcano.mainVent;
        }
        if (volcano.subVents != null) {
            return volcano.subVents.get(ventName);
        }
        return null;
    }
}
