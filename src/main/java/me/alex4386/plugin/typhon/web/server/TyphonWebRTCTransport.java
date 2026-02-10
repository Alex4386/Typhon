package me.alex4386.plugin.typhon.web.server;

import io.javalin.Javalin;
import me.alex4386.plugin.typhon.TyphonPlugin;
import me.alex4386.plugin.typhon.volcano.log.VolcanoLogClass;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import dev.onvoid.webrtc.*;

public class TyphonWebRTCTransport {
    private TyphonAPIRouter router;
    private boolean running = false;
    private boolean available = false;
    private String stunServer;
    private PeerConnectionFactory factory;
    private final Set<RTCPeerConnection> activePeers = ConcurrentHashMap.newKeySet();

    private static final long SESSION_TIMEOUT_MS = 5 * 60 * 1000; // 5 minutes
    private final Map<UUID, PendingSession> pendingSessions = new ConcurrentHashMap<>();

    private static class PendingSession {
        final RTCPeerConnection pc;
        final long createdAt;

        PendingSession(RTCPeerConnection pc) {
            this.pc = pc;
            this.createdAt = System.currentTimeMillis();
        }
    }

    public TyphonWebRTCTransport(String stunServer) {
        this.stunServer = stunServer;
        try {
            Class.forName("dev.onvoid.webrtc.PeerConnectionFactory");
            available = true;
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            available = false;
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public String getStunServer() {
        return stunServer;
    }

    /**
     * Start the WebRTC transport.
     * @param app Javalin instance for HTTP signaling endpoint, or null if HTTP listener is disabled.
     * @param router API router for handling DataChannel requests.
     */
    public void start(Javalin app, TyphonAPIRouter router) {
        this.router = router;

        if (!available) {
            TyphonPlugin.logger.warn(VolcanoLogClass.WEB,
                    "WebRTC native libraries not found. WebRTC transport is disabled.");
            return;
        }

        try {
            factory = new PeerConnectionFactory();
            if (app != null) {
                registerSignalingEndpoint(app);
            }
            running = true;
            TyphonPlugin.logger.log(VolcanoLogClass.WEB,
                    "WebRTC transport started (STUN: " + stunServer + ").");
        } catch (NoClassDefFoundError | Exception e) {
            TyphonPlugin.logger.warn(VolcanoLogClass.WEB,
                    "Failed to start WebRTC transport: " + e.getMessage());
            available = false;
        }
    }

    private void registerSignalingEndpoint(Javalin app) {
        app.post("/api/webrtc/offer", ctx -> {
            if (!available) {
                ctx.status(503).result("{\"error\":\"WebRTC not available\"}");
                return;
            }

            try {
                String body = ctx.body();
                // The HTTP endpoint receives JSON: {"sdp": "...", "type": "offer"}
                JSONParser parser = new JSONParser();
                JSONObject json = (JSONObject) parser.parse(body);
                String offerSdp = (String) json.get("sdp");

                String answerSdp = handleOffer(offerSdp);

                // Return JSON answer
                JSONObject answerJson = new JSONObject();
                answerJson.put("sdp", answerSdp);
                answerJson.put("type", "answer");

                ctx.contentType("application/json");
                ctx.result(answerJson.toJSONString());
            } catch (Exception e) {
                ctx.status(500).result("{\"error\":\"" + e.getMessage() + "\"}");
            }
        });
    }

    /**
     * Process a WebRTC offer SDP and return an answer SDP.
     * This is used by both the HTTP signaling endpoint and the manual /typhon web offer command.
     */
    @SuppressWarnings("unchecked")
    public String handleOffer(String offerSdp) throws Exception {
        if (!available || factory == null) {
            throw new IllegalStateException("WebRTC is not available");
        }

        RTCConfiguration config = new RTCConfiguration();
        RTCIceServer iceServer = new RTCIceServer();
        iceServer.urls = List.of("stun:" + stunServer);
        config.iceServers = List.of(iceServer);

        CountDownLatch iceLatch = new CountDownLatch(1);
        CountDownLatch answerLatch = new CountDownLatch(1);
        final String[] answerSdpHolder = new String[1];
        final Exception[] errorHolder = new Exception[1];

        RTCPeerConnection pc = factory.createPeerConnection(config, new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                // Candidates are gathered into the local description
            }

            @Override
            public void onIceGatheringChange(RTCIceGatheringState state) {
                if (state == RTCIceGatheringState.COMPLETE) {
                    iceLatch.countDown();
                }
            }

            @Override
            public void onDataChannel(RTCDataChannel dc) {
                setupDataChannel(dc);
            }

            @Override
            public void onConnectionChange(RTCPeerConnectionState state) {
                if (state == RTCPeerConnectionState.FAILED ||
                    state == RTCPeerConnectionState.CLOSED ||
                    state == RTCPeerConnectionState.DISCONNECTED) {
                    // Peer will be cleaned up
                }
            }

            @Override
            public void onSignalingChange(RTCSignalingState state) {}
            @Override
            public void onIceConnectionChange(RTCIceConnectionState state) {}
            @Override
            public void onRenegotiationNeeded() {}
            @Override
            public void onTrack(RTCRtpTransceiver transceiver) {}
        });

        activePeers.add(pc);

        // Set remote description (the offer)
        RTCSessionDescription offer = new RTCSessionDescription(RTCSdpType.OFFER, offerSdp);
        pc.setRemoteDescription(offer, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                // Create answer
                pc.createAnswer(new RTCAnswerOptions(), new CreateSessionDescriptionObserver() {
                    @Override
                    public void onSuccess(RTCSessionDescription answer) {
                        pc.setLocalDescription(answer, new SetSessionDescriptionObserver() {
                            @Override
                            public void onSuccess() {
                                answerLatch.countDown();
                            }

                            @Override
                            public void onFailure(String error) {
                                errorHolder[0] = new Exception("Failed to set local description: " + error);
                                answerLatch.countDown();
                                iceLatch.countDown();
                            }
                        });
                    }

                    @Override
                    public void onFailure(String error) {
                        errorHolder[0] = new Exception("Failed to create answer: " + error);
                        answerLatch.countDown();
                        iceLatch.countDown();
                    }
                });
            }

            @Override
            public void onFailure(String error) {
                errorHolder[0] = new Exception("Failed to set remote description: " + error);
                answerLatch.countDown();
                iceLatch.countDown();
            }
        });

        // Wait for answer to be created
        if (!answerLatch.await(10, TimeUnit.SECONDS)) {
            activePeers.remove(pc);
            pc.close();
            throw new Exception("Timeout waiting for answer creation");
        }

        if (errorHolder[0] != null) {
            activePeers.remove(pc);
            pc.close();
            throw errorHolder[0];
        }

        // Wait for ICE gathering to complete
        if (!iceLatch.await(10, TimeUnit.SECONDS)) {
            // Timeout is OK — use whatever candidates we have
            TyphonPlugin.logger.warn(VolcanoLogClass.WEB, "ICE gathering timed out, using partial candidates");
        }

        RTCSessionDescription localDesc = pc.getLocalDescription();
        if (localDesc == null) {
            activePeers.remove(pc);
            pc.close();
            throw new Exception("No local description available");
        }

        return localDesc.sdp;
    }

    /**
     * Server-offers-first: create PeerConnection + DataChannel + offer.
     * The offer SDP is returned (raw, not compressed). Caller should compress it.
     * The PeerConnection is stored as a pending session keyed by player UUID.
     */
    @SuppressWarnings("unchecked")
    public String createOffer(UUID playerUuid) throws Exception {
        if (!available || factory == null) {
            throw new IllegalStateException("WebRTC is not available");
        }

        // Clean up any existing pending session for this player
        PendingSession existing = pendingSessions.remove(playerUuid);
        if (existing != null) {
            try { existing.pc.close(); } catch (Exception e) { /* ignore */ }
            activePeers.remove(existing.pc);
        }
        cleanExpiredSessions();

        RTCConfiguration config = new RTCConfiguration();
        RTCIceServer iceServer = new RTCIceServer();
        iceServer.urls = List.of("stun:" + stunServer);
        config.iceServers = List.of(iceServer);

        CountDownLatch iceLatch = new CountDownLatch(1);
        CountDownLatch offerLatch = new CountDownLatch(1);
        final Exception[] errorHolder = new Exception[1];

        RTCPeerConnection pc = factory.createPeerConnection(config, new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {}

            @Override
            public void onIceGatheringChange(RTCIceGatheringState state) {
                if (state == RTCIceGatheringState.COMPLETE) {
                    iceLatch.countDown();
                }
            }

            @Override
            public void onDataChannel(RTCDataChannel dc) {}

            @Override
            public void onConnectionChange(RTCPeerConnectionState state) {}
            @Override
            public void onSignalingChange(RTCSignalingState state) {}
            @Override
            public void onIceConnectionChange(RTCIceConnectionState state) {}
            @Override
            public void onRenegotiationNeeded() {}
            @Override
            public void onTrack(RTCRtpTransceiver transceiver) {}
        });

        activePeers.add(pc);

        // Create DataChannel on the server side — browser will receive it via ondatachannel
        RTCDataChannelInit dcInit = new RTCDataChannelInit();
        dcInit.ordered = true;
        RTCDataChannel dc = pc.createDataChannel("typhon", dcInit);
        setupDataChannel(dc);

        // Create offer
        pc.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription offer) {
                pc.setLocalDescription(offer, new SetSessionDescriptionObserver() {
                    @Override
                    public void onSuccess() {
                        offerLatch.countDown();
                    }
                    @Override
                    public void onFailure(String error) {
                        errorHolder[0] = new Exception("Failed to set local description: " + error);
                        offerLatch.countDown();
                        iceLatch.countDown();
                    }
                });
            }
            @Override
            public void onFailure(String error) {
                errorHolder[0] = new Exception("Failed to create offer: " + error);
                offerLatch.countDown();
                iceLatch.countDown();
            }
        });

        // Wait for offer creation
        if (!offerLatch.await(10, TimeUnit.SECONDS)) {
            activePeers.remove(pc);
            pc.close();
            throw new Exception("Timeout waiting for offer creation");
        }
        if (errorHolder[0] != null) {
            activePeers.remove(pc);
            pc.close();
            throw errorHolder[0];
        }

        // Wait for ICE gathering
        if (!iceLatch.await(10, TimeUnit.SECONDS)) {
            TyphonPlugin.logger.warn(VolcanoLogClass.WEB, "ICE gathering timed out, using partial candidates");
        }

        RTCSessionDescription localDesc = pc.getLocalDescription();
        if (localDesc == null) {
            activePeers.remove(pc);
            pc.close();
            throw new Exception("No local description available");
        }

        // Store as pending session
        pendingSessions.put(playerUuid, new PendingSession(pc));

        return localDesc.sdp;
    }

    /**
     * Server-offers-first: accept the browser's answer for a pending session.
     */
    public void acceptAnswer(UUID playerUuid, String answerSdp) throws Exception {
        PendingSession session = pendingSessions.remove(playerUuid);
        if (session == null) {
            throw new IllegalStateException("No pending WebRTC session. Run /typhon web first.");
        }

        if (System.currentTimeMillis() - session.createdAt > SESSION_TIMEOUT_MS) {
            try { session.pc.close(); } catch (Exception e) { /* ignore */ }
            activePeers.remove(session.pc);
            throw new IllegalStateException("WebRTC session expired. Run /typhon web again.");
        }

        CountDownLatch latch = new CountDownLatch(1);
        final Exception[] errorHolder = new Exception[1];

        RTCSessionDescription answer = new RTCSessionDescription(RTCSdpType.ANSWER, answerSdp);
        session.pc.setRemoteDescription(answer, new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                latch.countDown();
            }
            @Override
            public void onFailure(String error) {
                errorHolder[0] = new Exception("Failed to set remote description: " + error);
                latch.countDown();
            }
        });

        if (!latch.await(10, TimeUnit.SECONDS)) {
            throw new Exception("Timeout waiting to set answer");
        }

        if (errorHolder[0] != null) {
            activePeers.remove(session.pc);
            session.pc.close();
            throw errorHolder[0];
        }
    }

    private void cleanExpiredSessions() {
        long now = System.currentTimeMillis();
        pendingSessions.entrySet().removeIf(entry -> {
            if (now - entry.getValue().createdAt > SESSION_TIMEOUT_MS) {
                try { entry.getValue().pc.close(); } catch (Exception e) { /* ignore */ }
                activePeers.remove(entry.getValue().pc);
                return true;
            }
            return false;
        });
    }

    private void setupDataChannel(RTCDataChannel dc) {
        dc.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {}

            @Override
            public void onStateChange() {
                if (dc.getState() == RTCDataChannelState.OPEN) {
                    TyphonPlugin.logger.log(VolcanoLogClass.WEB, "WebRTC DataChannel opened");
                } else if (dc.getState() == RTCDataChannelState.CLOSED) {
                    TyphonPlugin.logger.log(VolcanoLogClass.WEB, "WebRTC DataChannel closed");
                }
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                byte[] data = new byte[buffer.data.remaining()];
                buffer.data.get(data);
                String message = new String(data, java.nio.charset.StandardCharsets.UTF_8);

                List<String> responses = handleDataChannelMessage(message);
                for (String response : responses) {
                    try {
                        byte[] responseBytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        java.nio.ByteBuffer responseBuffer = java.nio.ByteBuffer.wrap(responseBytes);
                        dc.send(new RTCDataChannelBuffer(responseBuffer, false));
                    } catch (Exception e) {
                        TyphonPlugin.logger.error(VolcanoLogClass.WEB,
                                "Failed to send DataChannel response: " + e.getMessage());
                    }
                }
            }
        });
    }

    /**
     * Handle a peerfetch-style JSON message from the DataChannel.
     *
     * Request format: {"url": "/api/status", "method": "GET", "json": null, "data": null}
     * Ping format: {"url": "ping"}
     *
     * Returns a list of response messages to send back:
     * - For ping: [headerJSON, "pong"]
     * - For normal requests: [headerJSON, bodyString] (body omitted if status 204)
     */
    @SuppressWarnings("unchecked")
    public List<String> handleDataChannelMessage(String message) {
        List<String> responses = new ArrayList<>();

        if (router == null) {
            JSONObject errorHeader = new JSONObject();
            errorHeader.put("status", 503);
            errorHeader.put("content-type", "text/plain");
            errorHeader.put("content-length", 20);
            responses.add(errorHeader.toJSONString());
            responses.add("Router not available");
            return responses;
        }

        try {
            JSONParser parser = new JSONParser();
            JSONObject json = (JSONObject) parser.parse(message);
            String url = (String) json.get("url");

            // Handle ping keepalive
            if ("ping".equals(url)) {
                JSONObject pingHeader = new JSONObject();
                pingHeader.put("status", 200);
                pingHeader.put("content-type", "text/plain");
                pingHeader.put("content-length", 4);
                responses.add(pingHeader.toJSONString());
                responses.add("pong");
                return responses;
            }

            // Parse as peerfetch request and dispatch
            TyphonAPIRequest request = TyphonAPIRequest.fromPeerfetchJSON(message);
            TyphonAPIResponse response = router.dispatch(request);

            // Send header JSON
            responses.add(response.toPeerfetchHeaderJSON());

            // Send body (omit if 204)
            String body = response.toPeerfetchBody();
            if (body != null) {
                responses.add(body);
            }

        } catch (Exception e) {
            JSONObject errorHeader = new JSONObject();
            errorHeader.put("status", 500);
            errorHeader.put("content-type", "application/json");
            String errorBody = "{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
            errorHeader.put("content-length", errorBody.length());
            responses.add(errorHeader.toJSONString());
            responses.add(errorBody);
        }

        return responses;
    }

    public void stop() {
        if (!running) return;

        // Close pending sessions
        for (PendingSession session : pendingSessions.values()) {
            try { session.pc.close(); } catch (Exception e) { /* ignore */ }
        }
        pendingSessions.clear();

        // Close all active peer connections
        for (RTCPeerConnection pc : activePeers) {
            try {
                pc.close();
            } catch (Exception e) {
                // ignore cleanup errors
            }
        }
        activePeers.clear();

        if (factory != null) {
            try {
                factory.dispose();
            } catch (Exception e) {
                // ignore
            }
            factory = null;
        }

        running = false;
        TyphonPlugin.logger.log(VolcanoLogClass.WEB, "WebRTC transport stopped.");
    }

    public boolean isRunning() {
        return running;
    }
}
