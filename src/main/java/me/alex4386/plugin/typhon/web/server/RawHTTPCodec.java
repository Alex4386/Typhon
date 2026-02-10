package me.alex4386.plugin.typhon.web.server;

import java.util.*;

public class RawHTTPCodec {

    private static final Map<Integer, String> STATUS_TEXTS = new HashMap<>();

    static {
        STATUS_TEXTS.put(200, "OK");
        STATUS_TEXTS.put(201, "Created");
        STATUS_TEXTS.put(204, "No Content");
        STATUS_TEXTS.put(400, "Bad Request");
        STATUS_TEXTS.put(401, "Unauthorized");
        STATUS_TEXTS.put(403, "Forbidden");
        STATUS_TEXTS.put(404, "Not Found");
        STATUS_TEXTS.put(405, "Method Not Allowed");
        STATUS_TEXTS.put(500, "Internal Server Error");
    }

    public static TyphonAPIRequest parseRequest(String raw) {
        String[] parts = raw.split("\r\n\r\n", 2);
        String headerSection = parts[0];
        String body = parts.length > 1 ? parts[1] : "";

        String[] lines = headerSection.split("\r\n");
        if (lines.length == 0) {
            throw new IllegalArgumentException("Invalid HTTP request: empty");
        }

        // Parse request line: METHOD /path HTTP/1.1
        String[] requestLine = lines[0].split(" ", 3);
        if (requestLine.length < 2) {
            throw new IllegalArgumentException("Invalid HTTP request line: " + lines[0]);
        }
        String method = requestLine[0];
        String fullPath = requestLine[1];

        // Split path and query string
        String path;
        Map<String, List<String>> queryParams = new LinkedHashMap<>();
        int queryIndex = fullPath.indexOf('?');
        if (queryIndex >= 0) {
            path = fullPath.substring(0, queryIndex);
            String queryString = fullPath.substring(queryIndex + 1);
            parseQueryString(queryString, queryParams);
        } else {
            path = fullPath;
        }

        // Parse headers
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colonIndex = lines[i].indexOf(':');
            if (colonIndex > 0) {
                String name = lines[i].substring(0, colonIndex).trim();
                String value = lines[i].substring(colonIndex + 1).trim();
                headers.put(name, value);
            }
        }

        return new TyphonAPIRequest(method, path, headers, queryParams, body);
    }

    public static String encodeResponse(TyphonAPIResponse response) {
        StringBuilder sb = new StringBuilder();

        String statusText = STATUS_TEXTS.getOrDefault(response.getStatusCode(), "Unknown");
        sb.append("HTTP/1.1 ").append(response.getStatusCode()).append(" ").append(statusText).append("\r\n");

        sb.append("Content-Type: ").append(response.getContentType()).append("\r\n");

        for (Map.Entry<String, String> entry : response.getHeaders().entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
        }

        String body = response.getBody();
        sb.append("Content-Length: ").append(body != null ? body.length() : 0).append("\r\n");

        sb.append("\r\n");

        if (body != null && !body.isEmpty()) {
            sb.append(body);
        }

        return sb.toString();
    }

    public static void parseQueryStringPublic(String queryString, Map<String, List<String>> params) {
        parseQueryString(queryString, params);
    }

    private static void parseQueryString(String queryString, Map<String, List<String>> params) {
        if (queryString == null || queryString.isEmpty()) return;

        for (String param : queryString.split("&")) {
            String[] keyValue = param.split("=", 2);
            String key = decodeURIComponent(keyValue[0]);
            String value = keyValue.length > 1 ? decodeURIComponent(keyValue[1]) : "";
            params.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }
    }

    private static String decodeURIComponent(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
