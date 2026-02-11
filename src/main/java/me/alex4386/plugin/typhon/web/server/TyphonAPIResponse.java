package me.alex4386.plugin.typhon.web.server;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

public class TyphonAPIResponse {
    private int statusCode;
    private Map<String, String> headers;
    private String body;
    private String contentType;

    public TyphonAPIResponse() {
        this.statusCode = 200;
        this.headers = new LinkedHashMap<>();
        this.body = "";
        this.contentType = "text/plain";
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public String getBody() {
        return body;
    }

    public String getContentType() {
        return contentType;
    }

    public TyphonAPIResponse status(int code) {
        this.statusCode = code;
        return this;
    }

    public TyphonAPIResponse header(String name, String value) {
        this.headers.put(name, value);
        return this;
    }

    public TyphonAPIResponse text(String text) {
        this.body = text;
        this.contentType = "text/plain";
        return this;
    }

    @SuppressWarnings("unchecked")
    public TyphonAPIResponse json(JSONObject obj) {
        this.body = obj.toJSONString();
        this.contentType = "application/json";
        return this;
    }

    @SuppressWarnings("unchecked")
    public TyphonAPIResponse json(JSONArray arr) {
        this.body = arr.toJSONString();
        this.contentType = "application/json";
        return this;
    }

    public void applyToJavalinContext(io.javalin.http.Context ctx) {
        ctx.status(this.statusCode);
        ctx.contentType(this.contentType);
        for (Map.Entry<String, String> entry : this.headers.entrySet()) {
            ctx.header(entry.getKey(), entry.getValue());
        }
        ctx.result(this.body);
    }

    public String toRawHTTP() {
        return RawHTTPCodec.encodeResponse(this);
    }

    /**
     * Encode as peerfetch-style header JSON: {"status": 200, "content-type": "...", "content-length": N}
     */
    @SuppressWarnings("unchecked")
    public String toPeerfetchHeaderJSON() {
        JSONObject header = new JSONObject();
        header.put("status", this.statusCode);
        header.put("content-type", this.contentType);
        header.put("content-length", this.body != null ? this.body.length() : 0);
        for (Map.Entry<String, String> entry : this.headers.entrySet()) {
            header.put(entry.getKey().toLowerCase(), entry.getValue());
        }
        return header.toJSONString();
    }

    /**
     * Get the body for peerfetch-style response (second message).
     * Returns null if status 204 (no content).
     */
    public String toPeerfetchBody() {
        if (this.statusCode == 204) return null;
        return this.body;
    }
}
