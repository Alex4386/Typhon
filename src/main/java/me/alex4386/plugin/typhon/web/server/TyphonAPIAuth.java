package me.alex4386.plugin.typhon.web.server;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TyphonAPIAuth {
    private final List<String> tokens = new ArrayList<>();
    private final List<String[]> basicCredentials = new ArrayList<>();
    private final Map<String, Long> tempTokens = new ConcurrentHashMap<>();
    private boolean issueTempTokenEnabled = true;

    public void setIssueTempTokenEnabled(boolean enabled) {
        this.issueTempTokenEnabled = enabled;
    }

    public boolean isIssueTempTokenEnabled() {
        return issueTempTokenEnabled;
    }

    public void addToken(String token) {
        tokens.add(token);
    }

    public void addBasicCredentials(String username, String password) {
        basicCredentials.add(new String[]{username, password});
    }

    public void clear() {
        tokens.clear();
        basicCredentials.clear();
        // Note: temp tokens are NOT cleared - they expire on their own
    }

    public boolean isConfigured() {
        return !tokens.isEmpty() || !basicCredentials.isEmpty() || !tempTokens.isEmpty();
    }

    public String createTempToken(long durationMs) {
        cleanExpiredTokens();
        String token = UUID.randomUUID().toString();
        tempTokens.put(token, System.currentTimeMillis() + durationMs);
        return token;
    }

    private void cleanExpiredTokens() {
        long now = System.currentTimeMillis();
        tempTokens.entrySet().removeIf(entry -> entry.getValue() < now);
    }

    public boolean authenticate(TyphonAPIRequest request) {
        cleanExpiredTokens();

        if (!isConfigured()) {
            return true;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || authHeader.isEmpty()) {
            return false;
        }

        if (authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            if (tokens.contains(token)) {
                return true;
            }
            // Check temp tokens
            Long expiry = tempTokens.get(token);
            if (expiry != null && expiry > System.currentTimeMillis()) {
                return true;
            }
            return false;
        }

        if (authHeader.startsWith("Basic ")) {
            String encoded = authHeader.substring(6).trim();
            try {
                String decoded = new String(Base64.getDecoder().decode(encoded));
                String[] parts = decoded.split(":", 2);
                if (parts.length != 2) return false;
                String username = parts[0];
                String password = parts[1];
                for (String[] cred : basicCredentials) {
                    if (cred[0].equals(username) && cred[1].equals(password)) {
                        return true;
                    }
                }
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    public void parseAuthConfig(List<?> authList) {
        clear();
        if (authList == null) return;

        for (Object entry : authList) {
            if (entry instanceof String) {
                addToken((String) entry);
            } else if (entry instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) entry;
                Object username = map.get("username");
                Object password = map.get("password");
                if (username != null && password != null) {
                    addBasicCredentials(username.toString(), password.toString());
                }
            }
        }
    }
}
