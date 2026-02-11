package me.alex4386.plugin.typhon.web.server;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.util.*;

public class TyphonAPIRequest {
    private final String method;
    private final String path;
    private final Map<String, String> headers;
    private final Map<String, List<String>> queryParams;
    private final String body;
    private Map<String, String> pathParams;

    public TyphonAPIRequest(String method, String path, Map<String, String> headers, Map<String, List<String>> queryParams, String body) {
        this.method = method;
        this.path = path;
        this.headers = headers;
        this.queryParams = queryParams;
        this.body = body;
        this.pathParams = Collections.emptyMap();
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getHeader(String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public Map<String, List<String>> getQueryParams() {
        return queryParams;
    }

    public String getQueryParam(String name) {
        List<String> values = queryParams.get(name);
        if (values != null && !values.isEmpty()) {
            return values.get(0);
        }
        return null;
    }

    public String getBody() {
        return body;
    }

    public Map<String, String> getPathParams() {
        return pathParams;
    }

    public String getPathParam(String name) {
        return pathParams.get(name);
    }

    public void setPathParams(Map<String, String> pathParams) {
        this.pathParams = pathParams;
    }

    public static TyphonAPIRequest fromJavalinContext(io.javalin.http.Context ctx) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String headerName : ctx.headerMap().keySet()) {
            headers.put(headerName, ctx.header(headerName));
        }

        Map<String, List<String>> queryParams = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : ctx.queryParamMap().entrySet()) {
            queryParams.put(entry.getKey(), entry.getValue());
        }

        return new TyphonAPIRequest(
                ctx.method().name(),
                ctx.path(),
                headers,
                queryParams,
                ctx.body()
        );
    }

    public static TyphonAPIRequest fromRawHTTP(String rawHTTP) {
        return RawHTTPCodec.parseRequest(rawHTTP);
    }

    /**
     * Parse a peerfetch-style JSON request: {"url": "/api/status", "method": "GET", "json": null, "data": null}
     */
    @SuppressWarnings("unchecked")
    public static TyphonAPIRequest fromPeerfetchJSON(String jsonString) {
        try {
            JSONParser parser = new JSONParser();
            JSONObject json = (JSONObject) parser.parse(jsonString);

            String url = (String) json.get("url");
            Object methodObj = json.get("method");
            String method = methodObj != null ? (String) methodObj : "GET";

            if (url == null) {
                throw new IllegalArgumentException("Missing 'url' field");
            }

            // Split path and query string
            String path;
            Map<String, List<String>> queryParams = new LinkedHashMap<>();
            int queryIndex = url.indexOf('?');
            if (queryIndex >= 0) {
                path = url.substring(0, queryIndex);
                String queryString = url.substring(queryIndex + 1);
                RawHTTPCodec.parseQueryStringPublic(queryString, queryParams);
            } else {
                path = url;
            }

            Map<String, String> headers = new LinkedHashMap<>();

            // Build body from json or data field
            String body = null;
            Object jsonBody = json.get("json");
            Object dataBody = json.get("data");
            if (jsonBody != null) {
                if (jsonBody instanceof JSONObject) {
                    body = ((JSONObject) jsonBody).toJSONString();
                } else {
                    body = jsonBody.toString();
                }
                headers.put("Content-Type", "application/json");
            } else if (dataBody != null) {
                body = dataBody.toString();
            }

            return new TyphonAPIRequest(method.toUpperCase(), path, headers, queryParams, body != null ? body : "");
        } catch (org.json.simple.parser.ParseException e) {
            throw new IllegalArgumentException("Invalid JSON request: " + e.getMessage());
        }
    }
}
