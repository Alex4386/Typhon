package me.alex4386.plugin.typhon.web.server;

import io.javalin.Javalin;
import io.javalin.http.UnauthorizedResponse;
import io.javalin.http.staticfiles.Location;
import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

public class TyphonAPIServer {
    private Javalin app;
    private TyphonAPIRouter router;
    private TyphonAPIAuth auth;
    private TyphonWebRTCTransport webrtcTransport;

    private boolean enabled = false;
    private String host = "0.0.0.0";
    private int port = 18080;
    private boolean webrtcEnabled = false;
    private String stunServer = "stun.l.google.com:19302";
    private boolean serveBundled = false;
    private String publicUrl = null; // optional override for public-facing URL
    private boolean issueTempToken = true;

    public void loadConfig(FileConfiguration config) {
        // web.connect.listen section: presence = enabled, absence = disabled
        this.enabled = config.isConfigurationSection("web.connect.listen");
        this.host = config.getString("web.connect.listen.host", "0.0.0.0");
        this.port = config.getInt("web.connect.listen.port", 18080);

        // WebRTC
        this.webrtcEnabled = config.getBoolean("web.connect.webrtc.enable", false);
        this.stunServer = config.getString("web.connect.webrtc.stunServer", "stun.l.google.com:19302");

        // Public URL override (e.g. CF Argo Tunnel)
        this.publicUrl = config.getString("web.connect.listen.publicUrl", null);

        // UI
        this.serveBundled = config.getBoolean("web.ui.serveBundled", false);

        // Auth
        this.issueTempToken = config.getBoolean("web.api.auth.issueTempToken", true);

        if (auth == null) {
            auth = new TyphonAPIAuth();
        }

        List<?> authList = config.getList("web.api.auth.tokens");
        auth.parseAuthConfig(authList);
        auth.setIssueTempTokenEnabled(issueTempToken);
    }

    public void start() {
        if (!enabled) return;

        auth = auth != null ? auth : new TyphonAPIAuth();
        router = new TyphonAPIRouter(auth);

        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            if (serveBundled) {
                config.staticFiles.add("/web", Location.CLASSPATH);
            }
            config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
        });

        // Root redirect to web dashboard (only if serving bundled UI)
        if (serveBundled) {
            app.get("/", ctx -> ctx.redirect("/web/"));
        }

        // Before handler for auth
        app.before("/api/*", ctx -> {
            TyphonAPIRequest request = TyphonAPIRequest.fromJavalinContext(ctx);
            if (!auth.authenticate(request)) {
                throw new UnauthorizedResponse("Unauthorized");
            }
        });

        // Register Javalin routes that delegate to the router
        app.get("/api/*", ctx -> {
            TyphonAPIRequest request = TyphonAPIRequest.fromJavalinContext(ctx);
            TyphonAPIResponse response = router.dispatch(request);
            response.applyToJavalinContext(ctx);
        });

        app.post("/api/*", ctx -> {
            TyphonAPIRequest request = TyphonAPIRequest.fromJavalinContext(ctx);
            TyphonAPIResponse response = router.dispatch(request);
            response.applyToJavalinContext(ctx);
        });

        app.put("/api/*", ctx -> {
            TyphonAPIRequest request = TyphonAPIRequest.fromJavalinContext(ctx);
            TyphonAPIResponse response = router.dispatch(request);
            response.applyToJavalinContext(ctx);
        });

        app.delete("/api/*", ctx -> {
            TyphonAPIRequest request = TyphonAPIRequest.fromJavalinContext(ctx);
            TyphonAPIResponse response = router.dispatch(request);
            response.applyToJavalinContext(ctx);
        });

        try {
            app.start(host, port);
            TyphonPlugin.logger.log(VolcanoLogClass.WEB,
                    "API server started on " + host + ":" + port);
        } catch (Exception e) {
            TyphonPlugin.logger.error(VolcanoLogClass.WEB,
                    "Failed to start API server: " + e.getMessage());
            return;
        }

        // Start WebRTC transport if enabled
        if (webrtcEnabled) {
            try {
                webrtcTransport = new TyphonWebRTCTransport(stunServer);
                if (webrtcTransport.isAvailable()) {
                    webrtcTransport.start(app, router);
                } else {
                    TyphonPlugin.logger.warn(VolcanoLogClass.WEB,
                            "WebRTC enabled in config but native libraries not available. Skipping.");
                }
            } catch (NoClassDefFoundError e) {
                TyphonPlugin.logger.warn(VolcanoLogClass.WEB,
                        "WebRTC classes not found. WebRTC transport disabled.");
            }
        }
    }

    public void stop() {
        if (webrtcTransport != null && webrtcTransport.isRunning()) {
            webrtcTransport.stop();
            webrtcTransport = null;
        }

        if (app != null) {
            try {
                app.stop();
                TyphonPlugin.logger.log(VolcanoLogClass.WEB, "API server stopped.");
            } catch (Exception e) {
                TyphonPlugin.logger.error(VolcanoLogClass.WEB,
                        "Error stopping API server: " + e.getMessage());
            }
            app = null;
        }

        router = null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isRunning() {
        return app != null;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public boolean isWebrtcEnabled() {
        return webrtcEnabled;
    }

    public String getStunServer() {
        return stunServer;
    }

    public boolean isServeBundled() {
        return serveBundled;
    }

    /**
     * Get the public-facing URL for this server.
     * If publicUrl is configured, use that. Otherwise derive from host:port.
     */
    public String getPublicUrl() {
        if (publicUrl != null) return publicUrl;
        String displayHost = "0.0.0.0".equals(host) ? "<your-server-ip>" : host;
        return "http://" + displayHost + ":" + port;
    }

    public boolean isIssueTempTokenEnabled() {
        return issueTempToken;
    }

    public TyphonWebRTCTransport getWebrtcTransport() {
        return webrtcTransport;
    }

    public TyphonAPIAuth getAuth() {
        return auth;
    }

    public TyphonAPIRouter getRouter() {
        return router;
    }
}
