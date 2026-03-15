/*
 * Copyright (c) 2019-2024 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.util.LoginEncryptionUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages the Education Edition dedicated server authentication lifecycle:
 * device code flow, token refresh, server registration, health updates, and shutdown.
 */
public class EducationAuthManager {

    private static final String CLIENT_ID = "b36b1432-1a1c-4c82-9b76-24de1cab42f2";
    private static final String RESOURCE = "16556bfc-5102-43c9-a82a-3ea5e4810689";
    private static final String ENTRA_BASE = "https://login.microsoftonline.com/common/oauth2";
    private static final String MESS_BASE = "https://dedicatedserver.minecrafteduservices.com";
    private static final String TOOLING_BASE = "https://teachertools.minecrafteduservices.com/website/dedicatedserver";
    private static final String SESSION_FILE = "edu_session.json";
    private static final String LOG_PREFIX = "[EduGeyser] ";
    private static final int HTTP_TIMEOUT = 15000;

    private GeyserImpl geyser;
    private GeyserLogger logger;
    private String serverId;
    private String serverIp;
    private String serverName;
    private int maxPlayers;
    private Path sessionFilePath;

    // Token state — accessed from scheduled threads
    private volatile String refreshToken;
    private volatile String accessToken;
    private volatile long accessTokenExpires;
    private volatile String serverToken;      // Inner payload token (for handshake signedToken)
    private volatile String serverTokenJwt;    // Full JWT string (for API Authorization: Bearer header)
    private volatile long serverTokenExpires;

    private ScheduledFuture<?> updateTask;
    private ScheduledFuture<?> tokenRefreshTask;

    /**
     * Initialize the education auth system. Call from GeyserImpl.startInstance().
     * This method blocks during the device code flow if no saved session exists.
     */
    public void initialize(GeyserImpl geyser) {
        this.geyser = geyser;
        this.logger = geyser.getLogger();
        this.serverId = geyser.config().eduServerId();
        this.serverName = geyser.config().eduServerName();
        this.maxPlayers = geyser.config().eduMaxPlayers();

        boolean hasServerId = serverId != null && !serverId.isEmpty();
        boolean hasServerName = serverName != null && !serverName.isEmpty();

        logger.debug(LOG_PREFIX + "Config check: eduServerId='" + serverId
                + "', eduServerName='" + serverName
                + "', eduMaxPlayers=" + maxPlayers
                + ", eduServerIp='" + geyser.config().eduServerIp() + "'");

        if (!hasServerId && !hasServerName) {
            // Neither server ID nor server name configured — education auth manager inactive
            logger.debug(LOG_PREFIX + "No edu-server-id or edu-server-name configured. Education auth manager inactive.");
            return;
        }

        if (hasServerId) {
            logger.info(LOG_PREFIX + "Server ID configured: " + serverId);
        } else {
            logger.info(LOG_PREFIX + "No server ID configured. Will register new server: " + serverName);
        }

        this.serverIp = resolveServerIp();
        this.sessionFilePath = geyser.configDirectory().resolve(SESSION_FILE);
        logger.debug(LOG_PREFIX + "Session file path: " + sessionFilePath);

        // Run auth flow on the scheduled thread to avoid blocking startup
        logger.debug(LOG_PREFIX + "Submitting auth flow to scheduled thread...");
        geyser.getScheduledThread().execute(this::runAuthFlow);
    }

    private String resolveServerIp() {
        String configIp = geyser.config().eduServerIp();
        if (configIp != null && !configIp.isEmpty()) {
            logger.debug(LOG_PREFIX + "Using configured server IP: " + configIp);
            return configIp;
        }

        int port = geyser.config().bedrock().port();
        logger.debug(LOG_PREFIX + "No edu-server-ip configured. Attempting auto-detection (bedrock port: " + port + ")...");
        String detectedIp = detectPublicIp();
        if (detectedIp != null) {
            String result = detectedIp + ":" + port;
            logger.info(LOG_PREFIX + "Auto-detected public IP: " + result);
            return result;
        }

        // Fallback to bind address
        String address = geyser.config().bedrock().address();
        logger.debug(LOG_PREFIX + "Auto-detection failed. Bind address from config: " + address);
        if ("0.0.0.0".equals(address)) {
            address = "127.0.0.1";
        }
        String result = address + ":" + port;
        logger.warning(LOG_PREFIX + "Could not auto-detect public IP. Using bind address: " + result);
        logger.warning(LOG_PREFIX + "Set 'edu-server-ip' in config.yml to your public IP:port for external access.");
        return result;
    }

    private void runAuthFlow() {
        logger.debug(LOG_PREFIX + "Auth flow started.");
        try {
            loadSession();

            if (refreshToken != null && !refreshToken.isEmpty()) {
                // Existing session found
                logger.info(LOG_PREFIX + "Session restored.");
                logger.debug(LOG_PREFIX + "Session state: serverId=" + serverId
                        + ", hasRefreshToken=" + (refreshToken != null)
                        + ", hasAccessToken=" + (accessToken != null)
                        + ", accessTokenExpires=" + formatExpiry(accessTokenExpires)
                        + ", hasServerToken=" + (serverToken != null)
                        + ", serverTokenExpires=" + formatExpiry(serverTokenExpires));

                if (serverId == null || serverId.isEmpty()) {
                    // Session exists but has no serverId — corrupted or incomplete.
                    // Clear session and treat as fresh start.
                    logger.warning(LOG_PREFIX + "Session has no serverId. Clearing session and re-authenticating...");
                    deleteSession();
                    // Fall through to the no-session branch below
                } else {
                    // Refresh via Entra access token + fetch_token
                    ensureValidAccessToken();
                    fetchServerToken();
                    logger.info(LOG_PREFIX + "Token refreshed silently.");
                }
            }

            if (refreshToken == null || refreshToken.isEmpty()) {
                // No session — need to authenticate
                if (serverId != null && !serverId.isEmpty()) {
                    // Server ID from config or previous session — just need auth
                    logger.info(LOG_PREFIX + "No saved session. Starting authentication...");
                    doDeviceCodeFlow();
                    fetchServerToken();
                } else {
                    // No server ID — register a new server
                    logger.info(LOG_PREFIX + "No server configured. Registering new server...");
                    doDeviceCodeFlow();
                    registerNewServer();
                    logger.info(LOG_PREFIX + "Server registered with ID: " + serverId);
                    logger.info(LOG_PREFIX + "============================================");
                    logger.info(LOG_PREFIX + "  IMPORTANT: Complete setup in the admin portal:");
                    logger.info(LOG_PREFIX + "  1. Go to: https://education.minecraft.net/teachertools/en_US/dedicatedservers/");
                    logger.info(LOG_PREFIX + "  2. Enable 'Dedicated Servers', 'Cross-Tenancy', and 'Teacher Permissions'");
                    logger.info(LOG_PREFIX + "  3. Click on server " + serverId);
                    logger.info(LOG_PREFIX + "  4. Enable the server, turn on 'Broadcast', and set the server name");
                    logger.info(LOG_PREFIX + "============================================");
                }
            }

            // Register IP with MESS
            logger.debug(LOG_PREFIX + "Hosting server at " + serverIp + "...");
            hostServer();

            // After hosting, try to enable and configure the server via tooling API
            // This must happen AFTER host so the server is fully initialized
            tryEditServerInfo();

            saveSession();

            String expDate = formatExpiry(serverTokenExpires);
            logger.info(LOG_PREFIX + "Server hosted at " + serverIp);
            logger.debug(LOG_PREFIX + "Server token expires: " + expDate);
            logger.info(LOG_PREFIX + "Students can now connect from the server list.");

            logger.debug(LOG_PREFIX + "Scheduling server updates (every 10s) and token refresh (every 30min)...");
            scheduleServerUpdates();
            scheduleTokenRefresh();
            logger.debug(LOG_PREFIX + "Auth flow completed successfully.");
        } catch (Exception e) {
            logger.error(LOG_PREFIX + "Authentication flow failed: " + e.getMessage());
            if (geyser.config().debugMode()) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Returns the current server token for use in the handshake JWT signedToken claim.
     */
    public String getServerToken() {
        return serverToken;
    }

    /**
     * Whether the education auth system is actively running (has a server token and scheduled tasks).
     */
    public boolean isActive() {
        return serverToken != null && !serverToken.isEmpty();
    }

    /**
     * Returns the server ID for display purposes.
     */
    public String getServerId() {
        return serverId;
    }

    /**
     * Returns the server token expiration time (epoch seconds) for display purposes.
     */
    public long getServerTokenExpires() {
        return serverTokenExpires;
    }

    /**
     * Returns the server IP:port for display purposes.
     */
    public String getServerIp() {
        return serverIp;
    }

    /**
     * Format an epoch-second timestamp for external use.
     */
    public String formatExpiryPublic(long epochSeconds) {
        return formatExpiry(epochSeconds);
    }

    /**
     * Force a full re-authentication: delete session, restart device code flow.
     * Must be called from the scheduled thread.
     */
    public void resetAndReauthenticate() {
        logger.info(LOG_PREFIX + "Resetting session and starting re-authentication...");
        deleteSession();
        geyser.getScheduledThread().execute(this::runAuthFlow);
    }

    /**
     * Shutdown: dehost the server and cancel scheduled tasks.
     */
    public void shutdown() {
        logger.debug(LOG_PREFIX + "Shutdown initiated.");

        if (updateTask != null) {
            updateTask.cancel(false);
            logger.debug(LOG_PREFIX + "Server update task cancelled.");
        }
        if (tokenRefreshTask != null) {
            tokenRefreshTask.cancel(false);
            logger.debug(LOG_PREFIX + "Token refresh task cancelled.");
        }

        // Dehost so MESS stops routing clients immediately
        if (serverToken != null) {
            try {
                logger.debug(LOG_PREFIX + "Sending dehost request...");
                dehostServer();
                logger.info(LOG_PREFIX + "Server dehosted.");
            } catch (Exception e) {
                logger.warning(LOG_PREFIX + "Failed to dehost server: " + e.getMessage());
                if (geyser.config().debugMode()) {
                    e.printStackTrace();
                }
            }
        } else {
            logger.debug(LOG_PREFIX + "No server token — skipping dehost.");
        }

        saveSession();
        logger.debug(LOG_PREFIX + "Shutdown complete.");
    }

    // ---- Device Code Flow (Steps 1-2) ----

    private void doDeviceCodeFlow() throws IOException, InterruptedException {
        logger.debug(LOG_PREFIX + "Starting device code flow...");
        logger.debug(LOG_PREFIX + "POST " + ENTRA_BASE + "/devicecode (client_id=" + CLIENT_ID + ", resource=" + RESOURCE + ")");

        String body = "client_id=" + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8)
                + "&resource=" + URLEncoder.encode(RESOURCE, StandardCharsets.UTF_8);
        JsonObject deviceCodeResponse = postForm(ENTRA_BASE + "/devicecode", body);

        String deviceCode = deviceCodeResponse.get("device_code").getAsString();
        String userCode = deviceCodeResponse.get("user_code").getAsString();
        String verificationUrl = deviceCodeResponse.get("verification_url").getAsString();
        int expiresIn = deviceCodeResponse.get("expires_in").getAsInt();
        int interval = deviceCodeResponse.get("interval").getAsInt();

        logger.debug(LOG_PREFIX + "Device code obtained. user_code=" + userCode
                + ", expires_in=" + expiresIn + "s, poll_interval=" + interval + "s");

        logger.info(LOG_PREFIX + "============================================");
        logger.info(LOG_PREFIX + "  Go to: " + verificationUrl);
        logger.info(LOG_PREFIX + "  Enter code: " + userCode);
        logger.info(LOG_PREFIX + "============================================");
        logger.info(LOG_PREFIX + "Waiting for sign-in...");

        // Poll for token
        String pollBody = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", StandardCharsets.UTF_8)
                + "&client_id=" + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8)
                + "&code=" + URLEncoder.encode(deviceCode, StandardCharsets.UTF_8);

        long deadline = System.currentTimeMillis() + (expiresIn * 1000L);
        int pollCount = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);
            pollCount++;

            try {
                logger.debug(LOG_PREFIX + "Polling for token (attempt " + pollCount + ")...");
                JsonObject tokenResponse = postForm(ENTRA_BASE + "/token", pollBody);
                if (tokenResponse.has("access_token")) {
                    this.accessToken = tokenResponse.get("access_token").getAsString();
                    this.refreshToken = tokenResponse.get("refresh_token").getAsString();
                    this.accessTokenExpires = tokenResponse.get("expires_on").getAsLong();
                    logger.info(LOG_PREFIX + "Authentication successful!");
                    logger.debug(LOG_PREFIX + "Access token obtained. Expires: " + formatExpiry(accessTokenExpires)
                            + " (in " + (accessTokenExpires - Instant.now().getEpochSecond()) + "s)");
                    return;
                }
            } catch (IOException e) {
                String message = e.getMessage();
                if (message != null && message.contains("authorization_pending")) {
                    // Expected — user hasn't completed sign-in yet
                    continue;
                }
                logger.debug(LOG_PREFIX + "Unexpected error during polling: " + message);
                throw e;
            }
        }
        throw new IOException("Device code flow timed out after " + expiresIn + " seconds (" + pollCount + " polls)");
    }

    // ---- Entra Token Refresh ----

    private boolean refreshAccessToken() {
        try {
            logger.debug(LOG_PREFIX + "Refreshing Entra access token...");
            String body = "grant_type=refresh_token"
                    + "&client_id=" + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8)
                    + "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8);
            JsonObject response = postForm(ENTRA_BASE + "/token", body);

            if (response.has("access_token")) {
                this.accessToken = response.get("access_token").getAsString();
                this.refreshToken = response.get("refresh_token").getAsString();
                this.accessTokenExpires = response.get("expires_on").getAsLong();
                logger.debug(LOG_PREFIX + "Entra token refreshed. New expiry: " + formatExpiry(accessTokenExpires)
                        + " (in " + (accessTokenExpires - Instant.now().getEpochSecond()) + "s)");
                logger.debug(LOG_PREFIX + "Refresh token rotated (this is expected — Entra rotates refresh tokens).");
                saveSession();
                return true;
            }
            logger.debug(LOG_PREFIX + "Entra token refresh response did not contain access_token.");
            return false;
        } catch (Exception e) {
            logger.warning(LOG_PREFIX + "Failed to refresh Entra token: " + e.getMessage());
            if (geyser.config().debugMode()) {
                e.printStackTrace();
            }
            return false;
        }
    }

    /**
     * Ensure we have a valid access token, refreshing or re-authenticating as needed.
     */
    private void ensureValidAccessToken() throws IOException, InterruptedException {
        if (!isAccessTokenExpired()) {
            logger.debug(LOG_PREFIX + "Access token still valid (expires " + formatExpiry(accessTokenExpires) + ").");
            return;
        }
        logger.debug(LOG_PREFIX + "Access token expired (" + formatExpiry(accessTokenExpires) + "). Attempting refresh...");
        if (!refreshAccessToken()) {
            logger.warning(LOG_PREFIX + "Token refresh failed. Re-authenticating...");
            deleteSession();
            doDeviceCodeFlow();
        }
    }

    // ---- Server Registration (new server, no existing ID) ----

    private void registerNewServer() throws IOException {
        logger.debug(LOG_PREFIX + "POST " + MESS_BASE + "/server/register (auth: Entra access token)");
        String jwtResponse = postEmptyWithAuth(MESS_BASE + "/server/register", accessToken);
        logger.debug(LOG_PREFIX + "Register response JWT length: " + jwtResponse.length());
        parseServerTokenJwt(jwtResponse);
        logger.debug(LOG_PREFIX + "Registered new server. ServerId=" + serverId);
    }

    // ---- Edit Server Info (enable, name, broadcast — best-effort after host) ----

    /**
     * Best-effort: configure server info via the teachertools API.
     * Must be called AFTER hostServer() so the server is fully initialized.
     * Uses the Entra access token for authorization.
     */
    private void tryEditServerInfo() {
        if (serverId == null || serverId.isEmpty()) return;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("serverId", serverId);
            if (serverName != null && !serverName.isEmpty()) {
                body.addProperty("serverName", serverName);
            }
            body.addProperty("enabled", true);
            body.addProperty("isBroadcasted", true);
            logger.debug(LOG_PREFIX + "POST " + MESS_BASE + "/tooling/edit_server_info: " + body);
            postJsonWithAuth(MESS_BASE + "/tooling/edit_server_info", accessToken, body.toString());
            logger.info(LOG_PREFIX + "Server configured: enabled, broadcasted" +
                    (serverName != null && !serverName.isEmpty() ? ", name='" + serverName + "'" : "") + ".");
        } catch (IOException e) {
            logger.warning(LOG_PREFIX + "Could not update server info: " + e.getMessage());
            logger.warning(LOG_PREFIX + "You may need to enable the server manually in the admin portal:");
            logger.warning(LOG_PREFIX + "  https://education.minecraft.net/teachertools/en_US/dedicatedservers/");
        }
    }

    // ---- Fetch Server Token (via Entra access token) ----

    private void fetchServerToken() throws IOException {
        if (serverId == null || serverId.isEmpty()) {
            throw new IOException("Cannot fetch server token — no serverId available. Delete edu_session.json and restart to re-register.");
        }
        String url = MESS_BASE + "/server/fetch_token?serverId="
                + URLEncoder.encode(serverId, StandardCharsets.UTF_8);
        logger.debug(LOG_PREFIX + "GET " + url + " (auth: Entra access token)");
        String jwtResponse = getWithAuth(url, accessToken);
        logger.debug(LOG_PREFIX + "fetch_token response JWT length: " + jwtResponse.length());
        parseServerTokenJwt(jwtResponse);
        logger.debug(LOG_PREFIX + "Server token fetched. Expires: " + formatExpiry(serverTokenExpires));
    }

    // ---- Parse JWT response to extract ServerToken and expiration ----

    private void parseServerTokenJwt(String jwtResponse) throws IOException {
        this.serverTokenJwt = jwtResponse.trim();
        String[] parts = serverTokenJwt.split("\\.");
        if (parts.length < 2) {
            logger.debug(LOG_PREFIX + "JWT parse failed. Raw response (first 200 chars): "
                    + jwtResponse.substring(0, Math.min(200, jwtResponse.length())));
            throw new IOException("Invalid JWT response (got " + parts.length + " parts, expected 3)");
        }
        logger.debug(LOG_PREFIX + "JWT has " + parts.length + " parts. Decoding header + payload...");

        // Log the header so we can see the MESS signing key (x5u)
        String headerJson = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        logger.info(LOG_PREFIX + "Server token JWT header: " + headerJson);

        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        logger.debug(LOG_PREFIX + "JWT payload: " + payloadJson);

        JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();

        this.serverTokenExpires = payload.get("exp").getAsLong();
        JsonObject innerPayload = payload.getAsJsonObject("payload");

        // The API uses camelCase (serverToken/serverId) in JWT payloads
        // but some docs show PascalCase — handle both
        if (innerPayload.has("serverToken")) {
            this.serverToken = innerPayload.get("serverToken").getAsString();
        } else if (innerPayload.has("ServerToken")) {
            this.serverToken = innerPayload.get("ServerToken").getAsString();
        } else {
            throw new IOException("JWT payload missing serverToken field. Keys present: " + innerPayload.keySet());
        }

        logger.debug(LOG_PREFIX + "Parsed JWT: exp=" + serverTokenExpires
                + " (" + formatExpiry(serverTokenExpires) + ")"
                + ", serverToken length=" + serverToken.length());

        // Extract tenant ID and register in multi-tenancy pool
        String tenantId = LoginEncryptionUtils.extractTenantIdFromServerToken(geyser, serverToken);
        if (tenantId != null && !tenantId.isEmpty()) {
            LoginEncryptionUtils.registerServerToken(geyser, serverToken, tenantId, "MESS registration");
        } else {
            logger.warning(LOG_PREFIX + "Could not extract tenantId from server token. MESS token will still work via fallback.");
        }

        // Extract serverId if we don't have one yet (from registration)
        if (serverId == null || serverId.isEmpty()) {
            if (innerPayload.has("serverId")) {
                this.serverId = innerPayload.get("serverId").getAsString();
                logger.debug(LOG_PREFIX + "Extracted serverId from JWT: " + serverId);
            } else if (innerPayload.has("ServerId")) {
                this.serverId = innerPayload.get("ServerId").getAsString();
                logger.debug(LOG_PREFIX + "Extracted ServerId from JWT: " + serverId);
            }
        }
    }

    // ---- Host Server (register IP with MESS) ----

    private void hostServer() throws IOException {
        String json = "{\"connectionInfo\":{\"transportType\":0,\"transportInfo\":{\"ip\":\"" + serverIp + "\"}}}";
        logger.debug(LOG_PREFIX + "POST " + MESS_BASE + "/server/host: " + json);
        postJsonWithAuth(MESS_BASE + "/server/host", serverToken, json);
        logger.debug(LOG_PREFIX + "Host request successful.");
    }

    // ---- Dehost Server (unregister on shutdown) ----

    private void dehostServer() throws IOException {
        logger.debug(LOG_PREFIX + "POST " + MESS_BASE + "/server/dehost (auth: server token)");
        postEmptyWithAuth(MESS_BASE + "/server/dehost", serverToken);
    }

    // ---- Server Update (heartbeat with player count) ----

    private void sendServerUpdate() {
        try {
            int eduPlayerCount = countEducationPlayers();
            String json = "{\"playerCount\":" + eduPlayerCount
                    + ",\"maxPlayers\":" + maxPlayers
                    + ",\"health\":2}";
            logger.debug(LOG_PREFIX + "POST " + MESS_BASE + "/server/update: " + json);
            postJsonWithAuth(MESS_BASE + "/server/update", serverToken, json);
        } catch (Exception e) {
            logger.warning(LOG_PREFIX + "Server update failed: " + e.getMessage());
            if (geyser.config().debugMode()) {
                e.printStackTrace();
            }
        }
    }

    private int countEducationPlayers() {
        int count = 0;
        for (GeyserSession session : geyser.onlineConnections()) {
            if (session.isEducationClient()) {
                count++;
            }
        }
        return count;
    }

    // ---- Scheduling ----

    private void scheduleServerUpdates() {
        ScheduledExecutorService thread = geyser.getScheduledThread();
        updateTask = thread.scheduleAtFixedRate(this::sendServerUpdate, 10, 10, TimeUnit.SECONDS);
        logger.debug(LOG_PREFIX + "Server update task scheduled: every 10 seconds.");
    }

    private void scheduleTokenRefresh() {
        ScheduledExecutorService thread = geyser.getScheduledThread();
        tokenRefreshTask = thread.scheduleAtFixedRate(() -> {
            try {
                logger.debug(LOG_PREFIX + "Scheduled token refresh starting...");
                ensureValidAccessToken();
                fetchServerToken();
                saveSession();
                logger.info(LOG_PREFIX + "Tokens refreshed successfully.");
                logger.debug(LOG_PREFIX + "Next server token expiry: " + formatExpiry(serverTokenExpires));
            } catch (Exception e) {
                logger.warning(LOG_PREFIX + "Scheduled token refresh error: " + e.getMessage());
                if (geyser.config().debugMode()) {
                    e.printStackTrace();
                }
            }
        }, 30, 30, TimeUnit.MINUTES);
        logger.debug(LOG_PREFIX + "Token refresh task scheduled: every 30 minutes.");
    }

    // ---- Session Persistence ----

    private void loadSession() {
        if (!Files.exists(sessionFilePath)) {
            logger.debug(LOG_PREFIX + "No session file found at " + sessionFilePath);
            return;
        }
        logger.debug(LOG_PREFIX + "Loading session from " + sessionFilePath + "...");
        try (Reader reader = new FileReader(sessionFilePath.toFile())) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            this.refreshToken = getStringOrNull(obj, "refresh_token");
            this.accessToken = getStringOrNull(obj, "access_token");
            this.serverToken = getStringOrNull(obj, "server_token");
            this.serverTokenJwt = getStringOrNull(obj, "server_token_jwt");
            this.accessTokenExpires = obj.has("access_token_expires") ? obj.get("access_token_expires").getAsLong() : 0;
            this.serverTokenExpires = obj.has("server_token_expires") ? obj.get("server_token_expires").getAsLong() : 0;
            // Restore server ID from session if not in config
            if ((serverId == null || serverId.isEmpty()) && obj.has("server_id")) {
                this.serverId = getStringOrNull(obj, "server_id");
                logger.debug(LOG_PREFIX + "Restored serverId from session: " + serverId);
            }
            logger.debug(LOG_PREFIX + "Session loaded: hasRefreshToken=" + (refreshToken != null)
                    + ", hasAccessToken=" + (accessToken != null)
                    + ", accessTokenExpires=" + formatExpiry(accessTokenExpires)
                    + ", hasServerTokenJwt=" + (serverTokenJwt != null)
                    + ", serverTokenExpires=" + formatExpiry(serverTokenExpires));
        } catch (Exception e) {
            logger.warning(LOG_PREFIX + "Failed to load session file: " + e.getMessage());
            if (geyser.config().debugMode()) {
                e.printStackTrace();
            }
        }
    }

    private void saveSession() {
        if (sessionFilePath == null) {
            logger.debug(LOG_PREFIX + "Cannot save session — sessionFilePath is null.");
            return;
        }
        try {
            JsonObject obj = new JsonObject();
            // Only persist serverId if we actually have one — avoids saving "" which causes issues on restore
            if (serverId != null && !serverId.isEmpty()) {
                obj.addProperty("server_id", serverId);
            }
            obj.addProperty("refresh_token", refreshToken);
            obj.addProperty("access_token", accessToken);
            obj.addProperty("access_token_expires", accessTokenExpires);
            obj.addProperty("server_token", serverToken);
            obj.addProperty("server_token_jwt", serverTokenJwt);
            obj.addProperty("server_token_expires", serverTokenExpires);
            try (Writer writer = new FileWriter(sessionFilePath.toFile())) {
                GeyserImpl.GSON.toJson(obj, writer);
            }
            logger.debug(LOG_PREFIX + "Session saved to " + sessionFilePath);
        } catch (Exception e) {
            logger.warning(LOG_PREFIX + "Failed to save session file: " + e.getMessage());
            if (geyser.config().debugMode()) {
                e.printStackTrace();
            }
        }
    }

    private void deleteSession() {
        logger.debug(LOG_PREFIX + "Deleting session file and clearing all token state...");
        try {
            Files.deleteIfExists(sessionFilePath);
            logger.debug(LOG_PREFIX + "Session file deleted.");
        } catch (IOException e) {
            logger.warning(LOG_PREFIX + "Failed to delete session file: " + e.getMessage());
        }
        this.refreshToken = null;
        this.accessToken = null;
        this.serverToken = null;
        this.serverTokenJwt = null;
        this.accessTokenExpires = 0;
        this.serverTokenExpires = 0;
    }

    private boolean isAccessTokenExpired() {
        boolean expired = accessTokenExpires <= Instant.now().getEpochSecond();
        if (expired) {
            logger.debug(LOG_PREFIX + "Access token is expired (expired at " + formatExpiry(accessTokenExpires) + ").");
        }
        return expired;
    }

    private boolean isServerTokenExpired() {
        boolean expired = serverTokenExpires <= Instant.now().getEpochSecond();
        if (expired) {
            logger.debug(LOG_PREFIX + "Server token is expired (expired at " + formatExpiry(serverTokenExpires) + ").");
        }
        return expired;
    }

    private String detectPublicIp() {
        String[] services = {
                "https://checkip.amazonaws.com",
                "https://api.ipify.org",
                "https://icanhazip.com"
        };
        for (String service : services) {
            try {
                logger.debug(LOG_PREFIX + "Trying IP detection service: " + service);
                HttpURLConnection con = (HttpURLConnection) new URL(service).openConnection();
                con.setConnectTimeout(5000);
                con.setReadTimeout(5000);
                con.setRequestMethod("GET");
                int code = con.getResponseCode();
                if (code == 200) {
                    String ip = readStream(con.getInputStream()).trim();
                    if (!ip.isEmpty() && ip.length() < 46) { // sanity check (max IPv6 length)
                        logger.debug(LOG_PREFIX + "IP detection successful via " + service + ": " + ip);
                        return ip;
                    }
                    logger.debug(LOG_PREFIX + "IP detection via " + service + " returned invalid result: '" + ip + "'");
                } else {
                    logger.debug(LOG_PREFIX + "IP detection via " + service + " returned HTTP " + code);
                }
            } catch (Exception e) {
                logger.debug(LOG_PREFIX + "IP detection via " + service + " failed: " + e.getMessage());
            }
        }
        logger.debug(LOG_PREFIX + "All IP detection services failed.");
        return null;
    }

    // ---- HTTP Helpers ----

    private JsonObject postForm(String urlStr, String formBody) throws IOException {
        logger.debug(LOG_PREFIX + "HTTP POST (form) " + urlStr);
        HttpURLConnection con = (HttpURLConnection) new URL(urlStr).openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        con.setConnectTimeout(HTTP_TIMEOUT);
        con.setReadTimeout(HTTP_TIMEOUT);
        con.setDoOutput(true);

        try (OutputStream os = con.getOutputStream()) {
            os.write(formBody.getBytes(StandardCharsets.UTF_8));
        }

        int code = con.getResponseCode();
        logger.debug(LOG_PREFIX + "HTTP response: " + code + " from " + urlStr);
        if (code >= 400) {
            String errorBody = readStream(con.getErrorStream());
            logger.debug(LOG_PREFIX + "HTTP error body: " + errorBody);
            if (errorBody.contains("authorization_pending")) {
                throw new IOException("authorization_pending");
            }
            throw new IOException("HTTP " + code + ": " + errorBody);
        }

        try (InputStreamReader isr = new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(isr).getAsJsonObject();
        }
    }

    private String getWithAuth(String urlStr, String bearerToken) throws IOException {
        logger.debug(LOG_PREFIX + "HTTP GET " + urlStr);
        HttpURLConnection con = (HttpURLConnection) new URL(urlStr).openConnection();
        con.setRequestMethod("GET");
        con.setRequestProperty("Authorization", "Bearer " + bearerToken);
        con.setRequestProperty("x-request-id", UUID.randomUUID().toString());
        con.setConnectTimeout(HTTP_TIMEOUT);
        con.setReadTimeout(HTTP_TIMEOUT);

        int code = con.getResponseCode();
        logger.debug(LOG_PREFIX + "HTTP response: " + code + " from " + urlStr);
        if (code >= 400) {
            String errorBody = readStream(con.getErrorStream());
            logger.debug(LOG_PREFIX + "HTTP error body: " + errorBody);
            throw new IOException("HTTP " + code + ": " + errorBody);
        }

        return readStream(con.getInputStream());
    }

    private void postJsonWithAuth(String urlStr, String bearerToken, String jsonBody) throws IOException {
        logger.debug(LOG_PREFIX + "HTTP POST (json) " + urlStr);
        HttpURLConnection con = (HttpURLConnection) new URL(urlStr).openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Authorization", "Bearer " + bearerToken);
        con.setRequestProperty("x-request-id", UUID.randomUUID().toString());
        con.setConnectTimeout(HTTP_TIMEOUT);
        con.setReadTimeout(HTTP_TIMEOUT);
        con.setDoOutput(true);

        try (OutputStream os = con.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }

        int code = con.getResponseCode();
        logger.debug(LOG_PREFIX + "HTTP response: " + code + " from " + urlStr);
        if (code >= 400) {
            String errorBody = readStream(con.getErrorStream());
            logger.debug(LOG_PREFIX + "HTTP error body: " + errorBody);
            throw new IOException("HTTP " + code + ": " + errorBody);
        }
    }

    private String postEmptyWithAuth(String urlStr, String bearerToken) throws IOException {
        logger.debug(LOG_PREFIX + "HTTP POST (empty) " + urlStr);
        HttpURLConnection con = (HttpURLConnection) new URL(urlStr).openConnection();
        con.setRequestMethod("POST");
        con.setRequestProperty("Authorization", "Bearer " + bearerToken);
        con.setRequestProperty("x-request-id", UUID.randomUUID().toString());
        con.setConnectTimeout(HTTP_TIMEOUT);
        con.setReadTimeout(HTTP_TIMEOUT);
        con.setDoOutput(true);

        // Send empty body
        try (OutputStream os = con.getOutputStream()) {
            os.write(new byte[0]);
        }

        int code = con.getResponseCode();
        logger.debug(LOG_PREFIX + "HTTP response: " + code + " from " + urlStr);
        if (code >= 400) {
            String errorBody = readStream(con.getErrorStream());
            logger.debug(LOG_PREFIX + "HTTP error body: " + errorBody);
            throw new IOException("HTTP " + code + ": " + errorBody);
        }

        return readStream(con.getInputStream());
    }

    private String readStream(InputStream stream) throws IOException {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }

    private static String getStringOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        String value = obj.get(key).getAsString();
        return value.isEmpty() ? null : value;
    }

    /**
     * Format an epoch-second timestamp as a human-readable date/time string for logging.
     */
    private String formatExpiry(long epochSeconds) {
        if (epochSeconds <= 0) return "never/unset";
        return Instant.ofEpochSecond(epochSeconds)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
    }
}
