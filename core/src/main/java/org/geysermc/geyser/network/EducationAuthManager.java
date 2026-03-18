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
import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.GeyserLogger;
import org.geysermc.geyser.session.GeyserSession;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fully-automated Education Edition authentication manager using Microsoft's admin tooling API.
 *
 * <p>Uses the admin tooling app ID ({@code 1c91b067-...}) with OAuth v2.0 endpoints and
 * PascalCase field conventions from the official MESS tooling notebook. This enables
 * complete server automation in a single device code login:
 * <ol>
 *   <li>Enable tenant settings (dedicated servers, teacher access)</li>
 *   <li>Register or fetch the server</li>
 *   <li>Host the server (register IP with MESS)</li>
 *   <li>Configure, enable, and broadcast the server</li>
 * </ol>
 *
 */
public class EducationAuthManager {

    // Admin Tooling app ID from Microsoft's official MESS tooling notebook.
    // Used for tooling/* endpoints (edit_tenant_settings, edit_server_info, etc.)
    private static final String TOOLING_CLIENT_ID = "1c91b067-6806-44a5-8d2d-3137e625f5b8";

    // Education Client app ID - required for server/* endpoints (register, fetch_token).
    // The tooling app ID is NOT accepted by server/* endpoints (returns 401).
    private static final String EDU_CLIENT_ID = "b36b1432-1a1c-4c82-9b76-24de1cab42f2";

    // MESS resource ID with /.default scope suffix (v2.0 OAuth format)
    private static final String SCOPE = "16556bfc-5102-43c9-a82a-3ea5e4810689/.default offline_access";

    // OAuth v2.0 endpoints under /organizations (restricts to work/school accounts)
    private static final String ENTRA_BASE = "https://login.microsoftonline.com/organizations/oauth2/v2.0";
    private static final String MESS_BASE = "https://dedicatedserver.minecrafteduservices.com";

    /**
     * Server health status for MESS /server/update heartbeats.
     * MESS accepts values -1 to 2 (Microsoft docs incorrectly claim 0-3; 3 is rejected).
     * 2 = optimal/healthy.
     */
    private static final int MESS_HEALTH_OPTIMAL = 2;
    private static final String SESSION_FILE = "edu_tooling_session.json";
    private static final String LOG_PREFIX = "[EduTooling] ";
    private static final int HTTP_TIMEOUT = 15000;
    private static final long TOKEN_EXPIRY_BUFFER_SECONDS = 60;

    private @Nullable GeyserImpl geyser;
    private @Nullable GeyserLogger logger;
    private @Nullable String serverId;
    private @Nullable String serverIp;
    private @Nullable String serverName;
    private int maxPlayers;
    private @Nullable Path sessionFilePath;

    // Tooling token state (tooling app ID - for tooling/* endpoints)
    private volatile @Nullable String refreshToken;
    private volatile @Nullable String accessToken;
    private volatile long accessTokenExpires;

    // Edu client token state (edu client app ID - for server/register, server/fetch_token)
    private volatile @Nullable String eduRefreshToken;
    private volatile @Nullable String eduAccessToken;
    private volatile long eduAccessTokenExpires;

    // MESS server token (obtained via server/register or server/fetch_token)
    private volatile @Nullable String serverToken;      // Inner payload token (for handshake signedToken)
    private volatile @Nullable String serverTokenJwt;    // Full JWT string (kept for session persistence)
    private volatile long serverTokenExpires;

    // Multi-tenancy: maps tenant ID to server token for routing
    private final ConcurrentHashMap<String, String> tenantTokenPool = new ConcurrentHashMap<>();

    private volatile ScheduledFuture<?> updateTask;
    private volatile ScheduledFuture<?> tokenRefreshTask;
    private volatile boolean shutdownRequested;

    /**
     * Initializes the auth manager, starting the device code login flow
     * and registering the server with MESS.
     */
    public void initialize(GeyserImpl geyser) {
        this.geyser = geyser;
        this.logger = geyser.getLogger();
        this.serverId = geyser.config().education().serverId();
        this.serverName = geyser.config().education().serverName();
        this.maxPlayers = geyser.config().education().maxPlayers();

        boolean hasServerId = serverId != null && !serverId.isEmpty();
        boolean hasServerName = serverName != null && !serverName.isEmpty();

        if (!hasServerId && !hasServerName) {
            logger.debug(LOG_PREFIX + "No edu-server-id or edu-server-name configured. Education tooling auth manager inactive.");
            return;
        }

        logger.debug(LOG_PREFIX + "Initializing: serverId=%s, serverName=%s, maxPlayers=%s",
                serverId, serverName, maxPlayers);

        this.serverIp = resolveServerIp();
        this.sessionFilePath = geyser.configDirectory().resolve(SESSION_FILE);

        // Run auth flow on the scheduled thread to avoid blocking startup
        geyser.getScheduledThread().execute(this::runAuthFlow);
    }

    private String resolveServerIp() {
        String configIp = geyser.config().education().serverIp();
        if (configIp != null && !configIp.isEmpty()) {
            return configIp;
        }

        int port = geyser.config().bedrock().port();
        String detectedIp = detectPublicIp();
        if (detectedIp != null) {
            return detectedIp + ":" + port;
        }

        // Fallback to bind address
        String address = geyser.config().bedrock().address();
        if ("0.0.0.0".equals(address)) {
            address = "127.0.0.1";
        }
        String result = address + ":" + port;
        logger.warning(LOG_PREFIX + "Could not auto-detect public IP. Using bind address: " + result);
        logger.warning(LOG_PREFIX + "Set 'edu-server-ip' in config.yml to your public IP:port for external access.");
        return result;
    }

    // ---- Main Auth Flow ----

    private void runAuthFlow() {
        try {
            loadSession();
            restoreOrAuthenticate();

            // Auto-enable tenant settings (dedicated servers, teacher access, cross-tenant)
            // This is best-effort: may fail if user is not a tenant admin
            tryEditTenantSettings();

            hostServer();

            // Configure server via tooling API (PascalCase, api-version 2.0)
            tryEditServerInfo();

            saveSession();

            logger.info(LOG_PREFIX + "Server hosted at " + serverIp);
            logger.info(LOG_PREFIX + "Server is fully configured and broadcasted.");
            logger.info(LOG_PREFIX + "Students can now connect from the server list.");

            if (shutdownRequested) {
                logger.debug(LOG_PREFIX + "Shutdown requested during auth flow, skipping scheduled tasks.");
                return;
            }
            scheduleServerUpdates();
            scheduleTokenRefresh();
        } catch (Exception e) {
            logger.error(LOG_PREFIX + "Authentication flow failed: " + e.getMessage(), e);
        }
    }

    private void restoreOrAuthenticate() throws IOException, InterruptedException {
        boolean hasToolingSession = refreshToken != null && !refreshToken.isEmpty();
        boolean hasEduSession = eduRefreshToken != null && !eduRefreshToken.isEmpty();

        if (hasToolingSession && hasEduSession) {
            logger.debug(LOG_PREFIX + "Session restored (serverId=%s).", serverId);
            if (serverId == null || serverId.isEmpty()) {
                logger.warning(LOG_PREFIX + "Session has no serverId. Clearing session and re-authenticating...");
                deleteSession();
            } else {
                ensureValidAccessToken(true);
                ensureValidEduAccessToken(true);
                fetchServerToken();
                return;
            }
        } else if (hasToolingSession || hasEduSession) {
            logger.warning(LOG_PREFIX + "Partial session (missing " +
                    (hasToolingSession ? "edu client token" : "tooling token") + "). Re-authenticating...");
            deleteSession();
        }

        // No session found, need to authenticate with both app IDs
        doDeviceCodeFlows();

        if (serverId != null && !serverId.isEmpty()) {
            fetchServerToken();
        } else {
            registerNewServer();
            logger.info(LOG_PREFIX + "Server registered with ID: " + serverId);
        }
    }

    // ---- Public API ----

    /**
     * Returns the current MESS server token, or null if not yet authenticated.
     */
    public @Nullable String getServerToken() {
        return serverToken;
    }

    /**
     * Returns whether the auth manager is operational — either via MESS registration
     * (server token present) or standalone mode (tenant token pool populated).
     */
    public boolean isActive() {
        return (serverToken != null && !serverToken.isEmpty()) || !tenantTokenPool.isEmpty();
    }

    /**
     * Returns the MESS server ID, or null if not configured.
     */
    public @Nullable String getServerId() {
        return serverId;
    }

    /**
     * Returns the public IP registered with MESS, or null if not yet detected.
     */
    public @Nullable String getServerIp() {
        return serverIp;
    }

    /**
     * Returns the epoch second at which the current server token expires.
     */
    public long getServerTokenExpires() {
        return serverTokenExpires;
    }


    /**
     * Deletes the current session and starts a fresh device code authentication flow.
     * @return true if the reset was initiated, false if education is not configured
     */
    public boolean resetAndReauthenticate() {
        if (sessionFilePath == null) {
            // Education not configured — initialize() returned early or was never called
            return false;
        }
        logger.info(LOG_PREFIX + "Resetting session and starting re-authentication...");
        if (updateTask != null) {
            updateTask.cancel(false);
            updateTask = null;
        }
        if (tokenRefreshTask != null) {
            tokenRefreshTask.cancel(false);
            tokenRefreshTask = null;
        }
        deleteSession();
        geyser.getScheduledThread().execute(this::runAuthFlow);
        return true;
    }

    /**
     * Cancels scheduled tasks, dehosts the server, and cleans up resources.
     */
    public void shutdown() {
        shutdownRequested = true;
        if (logger == null) {
            return;
        }
        if (updateTask != null) {
            updateTask.cancel(false);
        }
        if (tokenRefreshTask != null) {
            tokenRefreshTask.cancel(false);
        }

        if (serverToken != null) {
            try {
                dehostServer();
            } catch (Exception e) {
                logger.error(LOG_PREFIX + "Failed to dehost server: " + e.getMessage(), e);
            }
        }

        saveSession();
    }

    // ---- Device Code Flow (OAuth v2.0 with scope parameter) ----

    /**
     * Runs both device code flows: first for the tooling app ID (tooling/* endpoints),
     * then for the edu client app ID (server/* endpoints). The user must approve both.
     */
    private void doDeviceCodeFlows() throws IOException, InterruptedException {
        logger.info(LOG_PREFIX + "Two sign-ins are required: one for server management, one for server registration.");

        // Flow 1: Tooling app ID (for tooling/* endpoints)
        logger.info(LOG_PREFIX + "Step 1/2: Sign in for server management (tooling)...");
        JsonObject toolingTokens = doDeviceCodeFlow(TOOLING_CLIENT_ID, "tooling");
        this.accessToken = toolingTokens.get("access_token").getAsString();
        this.refreshToken = toolingTokens.has("refresh_token")
                ? toolingTokens.get("refresh_token").getAsString() : null;
        this.accessTokenExpires = parseTokenExpiry(toolingTokens);

        // Flow 2: Edu client app ID (for server/register, server/fetch_token)
        logger.info(LOG_PREFIX + "Step 2/2: Sign in for server registration...");
        JsonObject eduTokens = doDeviceCodeFlow(EDU_CLIENT_ID, "server");
        this.eduAccessToken = eduTokens.get("access_token").getAsString();
        this.eduRefreshToken = eduTokens.has("refresh_token")
                ? eduTokens.get("refresh_token").getAsString() : null;
        this.eduAccessTokenExpires = parseTokenExpiry(eduTokens);

        logger.info(LOG_PREFIX + "Both authentications successful!");
    }

    /**
     * Runs a single device code flow for the given client ID.
     * Returns the raw token response JSON (caller extracts access_token, refresh_token, etc.).
     */
    private JsonObject doDeviceCodeFlow(String clientId, String label) throws IOException, InterruptedException {
        String body = "client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);
        JsonObject deviceCodeResponse = postForm(ENTRA_BASE + "/devicecode", body);

        String deviceCode = deviceCodeResponse.get("device_code").getAsString();
        String userCode = deviceCodeResponse.get("user_code").getAsString();
        String verificationUri = deviceCodeResponse.has("verification_uri")
                ? deviceCodeResponse.get("verification_uri").getAsString()
                : deviceCodeResponse.get("verification_url").getAsString();
        int expiresIn = deviceCodeResponse.get("expires_in").getAsInt();
        int interval = deviceCodeResponse.get("interval").getAsInt();

        logger.info(LOG_PREFIX + "============================================");
        logger.info(LOG_PREFIX + "  Go to: " + verificationUri);
        logger.info(LOG_PREFIX + "  Enter code: " + userCode);
        logger.info(LOG_PREFIX + "  (" + label + " authentication)");
        logger.info(LOG_PREFIX + "============================================");
        logger.info(LOG_PREFIX + "Waiting for sign-in...");

        // Poll for token using v2.0 token endpoint
        String pollBody = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:device_code", StandardCharsets.UTF_8)
                + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                + "&device_code=" + URLEncoder.encode(deviceCode, StandardCharsets.UTF_8);

        long deadline = System.currentTimeMillis() + (expiresIn * 1000L);
        int pollCount = 0;
        while (System.currentTimeMillis() < deadline && !shutdownRequested) {
            Thread.sleep(interval * 1000L);
            if (shutdownRequested) {
                throw new IOException("Device code flow interrupted by shutdown");
            }
            pollCount++;

            try {
                JsonObject tokenResponse = postForm(ENTRA_BASE + "/token", pollBody);
                if (tokenResponse.has("access_token")) {
                    logger.info(LOG_PREFIX + "Authentication successful (" + label + ")!");
                    return tokenResponse;
                }
            } catch (IOException e) {
                String message = e.getMessage();
                if (message != null && message.contains("authorization_pending")) {
                    continue;
                }
                if (message != null && message.contains("slow_down")) {
                    interval += 5;
                    continue;
                }
                if (message != null && message.contains("expired_token")) {
                    throw new IOException("Device code expired before user completed sign-in");
                }
                logger.debug(LOG_PREFIX + "Unexpected error during polling: %s", message);
                throw e;
            }
        }
        throw new IOException("Device code flow timed out after " + expiresIn + " seconds (" + pollCount + " polls)");
    }

    /**
     * Parse token expiry from OAuth response, handling both v1.0 (expires_on as epoch)
     * and v2.0 (expires_in as seconds from now) formats.
     */
    private long parseTokenExpiry(JsonObject tokenResponse) {
        if (tokenResponse.has("expires_on")) {
            return tokenResponse.get("expires_on").getAsLong();
        }
        if (tokenResponse.has("expires_in")) {
            long expiresIn = tokenResponse.get("expires_in").getAsLong();
            return Instant.now().getEpochSecond() + expiresIn;
        }
        // Fallback: assume 1 hour
        logger.warning(LOG_PREFIX + "Token response missing both expires_on and expires_in. Assuming 1 hour expiry.");
        return Instant.now().getEpochSecond() + 3600;
    }

    // ---- Entra Token Refresh (v2.0) ----

    private boolean refreshAccessToken() {
        if (refreshToken == null) {
            return false;
        }
        try {
            String body = "grant_type=refresh_token"
                    + "&client_id=" + URLEncoder.encode(TOOLING_CLIENT_ID, StandardCharsets.UTF_8)
                    + "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
                    + "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);
            JsonObject response = postForm(ENTRA_BASE + "/token", body);

            if (response.has("access_token")) {
                this.accessToken = response.get("access_token").getAsString();
                this.refreshToken = response.has("refresh_token")
                        ? response.get("refresh_token").getAsString() : this.refreshToken;
                this.accessTokenExpires = parseTokenExpiry(response);
                logger.debug(LOG_PREFIX + "Tooling token refreshed, expires %s.", formatExpiry(accessTokenExpires));
                saveSession();
                return true;
            }
            return false;
        } catch (Exception e) {
            logger.error(LOG_PREFIX + "Failed to refresh tooling token: " + e.getMessage(), e);
            return false;
        }
    }

    private void ensureValidAccessToken(boolean allowReauth) throws IOException, InterruptedException {
        if (!isAccessTokenExpired()) {
            return;
        }
        if (!refreshAccessToken()) {
            if (allowReauth) {
                logger.warning(LOG_PREFIX + "Tooling token refresh failed. Re-authenticating...");
                deleteSession();
                doDeviceCodeFlows();
            } else {
                logger.error(LOG_PREFIX + "Tooling token refresh failed. Cannot re-authenticate from a scheduled task. Use '/geyser edu reset' to manually re-authenticate.");
            }
        }
    }

    // ---- Edu Client Token Refresh ----

    private boolean refreshEduAccessToken() {
        if (eduRefreshToken == null) {
            return false;
        }
        try {
            String body = "grant_type=refresh_token"
                    + "&client_id=" + URLEncoder.encode(EDU_CLIENT_ID, StandardCharsets.UTF_8)
                    + "&refresh_token=" + URLEncoder.encode(eduRefreshToken, StandardCharsets.UTF_8)
                    + "&scope=" + URLEncoder.encode(SCOPE, StandardCharsets.UTF_8);
            JsonObject response = postForm(ENTRA_BASE + "/token", body);

            if (response.has("access_token")) {
                this.eduAccessToken = response.get("access_token").getAsString();
                this.eduRefreshToken = response.has("refresh_token")
                        ? response.get("refresh_token").getAsString() : this.eduRefreshToken;
                this.eduAccessTokenExpires = parseTokenExpiry(response);
                logger.debug(LOG_PREFIX + "Edu client token refreshed, expires %s.", formatExpiry(eduAccessTokenExpires));
                saveSession();
                return true;
            }
            return false;
        } catch (Exception e) {
            logger.error(LOG_PREFIX + "Failed to refresh edu client token: " + e.getMessage(), e);
            return false;
        }
    }

    private void ensureValidEduAccessToken(boolean allowReauth) throws IOException, InterruptedException {
        if (eduAccessTokenExpires > Instant.now().getEpochSecond() + TOKEN_EXPIRY_BUFFER_SECONDS) {
            return;
        }
        if (!refreshEduAccessToken()) {
            if (allowReauth) {
                logger.warning(LOG_PREFIX + "Edu client token refresh failed. Re-authenticating...");
                deleteSession();
                doDeviceCodeFlows();
            } else {
                logger.error(LOG_PREFIX + "Edu client token refresh failed. Cannot re-authenticate from a scheduled task. Use '/geyser edu reset' to manually re-authenticate.");
            }
        }
    }

    // ---- Tenant Settings (auto-enable dedicated servers) ----

    private void tryEditTenantSettings() {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("DedicatedServerEnabled", true);
            body.addProperty("TeachersAllowed", true);
            body.addProperty("CrossTenantAllowed", true);
            postJsonWithAuth(MESS_BASE + "/tooling/edit_tenant_settings", accessToken, body.toString());
            logger.info(LOG_PREFIX + "Tenant settings configured: dedicated servers enabled, teacher access enabled, cross-tenant enabled.");
        } catch (IOException e) {
            logger.warning(LOG_PREFIX + "Could not update tenant settings (may require Global Admin): " + e.getMessage());
            logger.warning(LOG_PREFIX + "If this is the first setup, ensure dedicated servers are enabled in the admin portal:");
            logger.warning(LOG_PREFIX + "  https://education.minecraft.net/teachertools/en_US/dedicatedservers/");
        }
    }

    // ---- Server Registration ----

    private void registerNewServer() throws IOException {
        String jwtResponse = postEmptyWithAuth(MESS_BASE + "/server/register", eduAccessToken);
        parseServerTokenJwt(jwtResponse);
    }

    // ---- Edit Server Info (PascalCase, api-version 2.0) ----

    private void tryEditServerInfo() {
        if (serverId == null || serverId.isEmpty()) return;
        try {
            JsonObject body = new JsonObject();
            body.addProperty("ServerId", serverId);
            if (serverName != null && !serverName.isEmpty()) {
                body.addProperty("ServerName", serverName);
            }
            body.addProperty("Enabled", true);
            body.addProperty("IsBroadcasted", true);
            body.addProperty("SharingEnabled", true);
            body.addProperty("CrossTenantAllowed", true);
            postJsonWithAuth(MESS_BASE + "/tooling/edit_server_info", accessToken, body.toString(),
                    Map.of("api-version", "2.0"));
            String nameInfo = (serverName != null && !serverName.isEmpty()) ? ", name='" + serverName + "'" : "";
            logger.info(LOG_PREFIX + "Server configured: enabled, broadcasted, sharing on, cross-tenant on" + nameInfo + ".");
        } catch (IOException e) {
            logger.warning(LOG_PREFIX + "Could not update server info: " + e.getMessage());
            logger.warning(LOG_PREFIX + "You may need to enable the server manually in the admin portal:");
            logger.warning(LOG_PREFIX + "  https://education.minecraft.net/teachertools/en_US/dedicatedservers/");
        }
    }

    // ---- Fetch Server Token ----

    private void fetchServerToken() throws IOException {
        if (serverId == null || serverId.isEmpty()) {
            throw new IOException("Cannot fetch server token: no serverId available. Delete " + SESSION_FILE + " and restart to re-register.");
        }
        String url = MESS_BASE + "/server/fetch_token?serverId="
                + URLEncoder.encode(serverId, StandardCharsets.UTF_8);
        String jwtResponse = getWithAuth(url, eduAccessToken);
        parseServerTokenJwt(jwtResponse);
    }

    // ---- Parse JWT ----

    private void parseServerTokenJwt(String jwtResponse) throws IOException {
        this.serverTokenJwt = jwtResponse.trim();
        String[] parts = serverTokenJwt.split("\\.");
        if (parts.length < 2) {
            throw new IOException("Invalid JWT response (got " + parts.length + " parts, expected 3)");
        }

        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);

        JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();

        if (!payload.has("exp") || !payload.has("payload")) {
            throw new IOException("JWT payload missing required fields. Keys present: " + payload.keySet());
        }
        this.serverTokenExpires = payload.get("exp").getAsLong();
        JsonObject innerPayload = payload.getAsJsonObject("payload");

        // Handle both camelCase and PascalCase field names in JWT payloads
        if (innerPayload.has("serverToken")) {
            this.serverToken = innerPayload.get("serverToken").getAsString();
        } else if (innerPayload.has("ServerToken")) {
            this.serverToken = innerPayload.get("ServerToken").getAsString();
        } else {
            throw new IOException("JWT payload missing serverToken field. Keys present: " + innerPayload.keySet());
        }

        // Extract tenant ID and register in multi-tenancy pool
        String tenantId = extractTenantIdFromServerToken(serverToken);
        if (tenantId != null && !tenantId.isEmpty()) {
            registerServerToken(serverToken, tenantId, "MESS tooling registration");
        } else {
            logger.warning(LOG_PREFIX + "Could not extract tenantId from server token. MESS token will still work via fallback.");
        }

        // Extract serverId if we don't have one yet (from registration)
        if (serverId == null || serverId.isEmpty()) {
            if (innerPayload.has("serverId")) {
                this.serverId = innerPayload.get("serverId").getAsString();
                logger.debug(LOG_PREFIX + "Extracted serverId from JWT: %s", serverId);
            } else if (innerPayload.has("ServerId")) {
                this.serverId = innerPayload.get("ServerId").getAsString();
                logger.debug(LOG_PREFIX + "Extracted ServerId from JWT: %s", serverId);
            }
        }
    }

    // ---- Host / Dehost / Update ----

    private void hostServer() throws IOException {
        JsonObject transportInfo = new JsonObject();
        transportInfo.addProperty("ip", serverIp);
        JsonObject connectionInfo = new JsonObject();
        connectionInfo.addProperty("transportType", 0);
        connectionInfo.add("transportInfo", transportInfo);
        JsonObject body = new JsonObject();
        body.add("connectionInfo", connectionInfo);
        postJsonWithAuth(MESS_BASE + "/server/host", serverToken, body.toString());
    }

    private void dehostServer() throws IOException {
        postEmptyWithAuth(MESS_BASE + "/server/dehost", serverToken);
    }

    private void sendServerUpdate() {
        try {
            int eduPlayerCount = countEducationPlayers();
            String json = "{\"playerCount\":" + eduPlayerCount
                    + ",\"maxPlayers\":" + maxPlayers
                    + ",\"health\":" + MESS_HEALTH_OPTIMAL + "}";
            postJsonWithAuth(MESS_BASE + "/server/update", serverToken, json);
        } catch (Exception e) {
            logger.error(LOG_PREFIX + "Server update failed: " + e.getMessage(), e);
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
    }

    private void scheduleTokenRefresh() {
        ScheduledExecutorService thread = geyser.getScheduledThread();
        tokenRefreshTask = thread.scheduleAtFixedRate(() -> {
            try {
                ensureValidAccessToken(false);
                ensureValidEduAccessToken(false);
                fetchServerToken();
                saveSession();
            } catch (Exception e) {
                logger.error(LOG_PREFIX + "Scheduled token refresh error: " + e.getMessage(), e);
            }
        }, 30, 30, TimeUnit.MINUTES);
    }

    // ---- Session Persistence ----

    private void loadSession() {
        if (!Files.exists(sessionFilePath)) {
            return;
        }
        try (Reader reader = new FileReader(sessionFilePath.toFile())) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            this.refreshToken = getStringOrNull(obj, "refresh_token");
            this.accessToken = getStringOrNull(obj, "access_token");
            this.accessTokenExpires = obj.has("access_token_expires") ? obj.get("access_token_expires").getAsLong() : 0;
            this.eduRefreshToken = getStringOrNull(obj, "edu_refresh_token");
            this.eduAccessToken = getStringOrNull(obj, "edu_access_token");
            this.eduAccessTokenExpires = obj.has("edu_access_token_expires") ? obj.get("edu_access_token_expires").getAsLong() : 0;
            this.serverToken = getStringOrNull(obj, "server_token");
            this.serverTokenJwt = getStringOrNull(obj, "server_token_jwt");
            this.serverTokenExpires = obj.has("server_token_expires") ? obj.get("server_token_expires").getAsLong() : 0;
            if ((serverId == null || serverId.isEmpty()) && obj.has("server_id")) {
                this.serverId = getStringOrNull(obj, "server_id");
            }
        } catch (Exception e) {
            logger.error(LOG_PREFIX + "Failed to load session file: " + e.getMessage(), e);
        }
    }

    private void saveSession() {
        if (sessionFilePath == null) {
            return;
        }
        try {
            JsonObject obj = new JsonObject();
            if (serverId != null && !serverId.isEmpty()) {
                obj.addProperty("server_id", serverId);
            }
            obj.addProperty("refresh_token", refreshToken);
            obj.addProperty("access_token", accessToken);
            obj.addProperty("access_token_expires", accessTokenExpires);
            obj.addProperty("edu_refresh_token", eduRefreshToken);
            obj.addProperty("edu_access_token", eduAccessToken);
            obj.addProperty("edu_access_token_expires", eduAccessTokenExpires);
            obj.addProperty("server_token", serverToken);
            obj.addProperty("server_token_jwt", serverTokenJwt);
            obj.addProperty("server_token_expires", serverTokenExpires);
            try (Writer writer = new FileWriter(sessionFilePath.toFile())) {
                GeyserImpl.GSON.toJson(obj, writer);
            }
        } catch (Exception e) {
            logger.error(LOG_PREFIX + "Failed to save session file: " + e.getMessage(), e);
        }
    }

    private void deleteSession() {
        try {
            Files.deleteIfExists(sessionFilePath);
        } catch (IOException e) {
            logger.warning(LOG_PREFIX + "Failed to delete session file: " + e.getMessage());
        }
        this.refreshToken = null;
        this.accessToken = null;
        this.accessTokenExpires = 0;
        this.eduRefreshToken = null;
        this.eduAccessToken = null;
        this.eduAccessTokenExpires = 0;
        this.serverToken = null;
        this.serverTokenJwt = null;
        this.serverTokenExpires = 0;
    }

    private boolean isAccessTokenExpired() {
        return accessTokenExpires <= Instant.now().getEpochSecond() + TOKEN_EXPIRY_BUFFER_SECONDS;
    }

    // ---- IP Detection ----

    private @Nullable String detectPublicIp() {
        String[] services = {
                "https://checkip.amazonaws.com",
                "https://api.ipify.org",
                "https://icanhazip.com"
        };
        for (String service : services) {
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) URI.create(service).toURL().openConnection();
                con.setConnectTimeout(5000);
                con.setReadTimeout(5000);
                con.setRequestMethod("GET");
                int code = con.getResponseCode();
                if (code == 200) {
                    String ip = readStream(con.getInputStream()).trim();
                    if (!ip.isEmpty() && ip.length() < 46) {
                        return ip;
                    }
                }
            } catch (Exception ignored) {
                // Try next service
            } finally {
                if (con != null) con.disconnect();
            }
        }
        return null;
    }

    // ---- HTTP Helpers ----

    private JsonObject postForm(String urlStr, String formBody) throws IOException {
        HttpURLConnection con = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
        try {
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            con.setConnectTimeout(HTTP_TIMEOUT);
            con.setReadTimeout(HTTP_TIMEOUT);
            con.setDoOutput(true);

            try (OutputStream os = con.getOutputStream()) {
                os.write(formBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = con.getResponseCode();
            if (code >= 400) {
                String errorBody = readStream(con.getErrorStream());
                if (errorBody.contains("authorization_pending")) {
                    throw new IOException("authorization_pending");
                }
                throw new IOException("HTTP " + code + ": " + errorBody);
            }

            try (InputStreamReader isr = new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(isr).getAsJsonObject();
            }
        } finally {
            con.disconnect();
        }
    }

    private String getWithAuth(String urlStr, String bearerToken) throws IOException {
        HttpURLConnection con = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
        try {
            con.setRequestMethod("GET");
            con.setRequestProperty("Authorization", "Bearer " + bearerToken);
            con.setRequestProperty("x-request-id", UUID.randomUUID().toString());
            con.setConnectTimeout(HTTP_TIMEOUT);
            con.setReadTimeout(HTTP_TIMEOUT);

            int code = con.getResponseCode();
            if (code >= 400) {
                String errorBody = readStream(con.getErrorStream());
                throw new IOException("HTTP " + code + ": " + errorBody);
            }

            return readStream(con.getInputStream());
        } finally {
            con.disconnect();
        }
    }

    private void postJsonWithAuth(String urlStr, String bearerToken, String jsonBody) throws IOException {
        postJsonWithAuth(urlStr, bearerToken, jsonBody, Map.of());
    }

    private void postJsonWithAuth(String urlStr, String bearerToken, String jsonBody, Map<String, String> extraHeaders) throws IOException {
        HttpURLConnection con = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
        try {
            con.setRequestMethod("POST");
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Authorization", "Bearer " + bearerToken);
            con.setRequestProperty("x-request-id", UUID.randomUUID().toString());
            for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                con.setRequestProperty(header.getKey(), header.getValue());
            }
            con.setConnectTimeout(HTTP_TIMEOUT);
            con.setReadTimeout(HTTP_TIMEOUT);
            con.setDoOutput(true);

            try (OutputStream os = con.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = con.getResponseCode();
            if (code >= 400) {
                String errorBody = readStream(con.getErrorStream());
                throw new IOException("HTTP " + code + ": " + errorBody);
            }
        } finally {
            con.disconnect();
        }
    }

    private String postEmptyWithAuth(String urlStr, String bearerToken) throws IOException {
        HttpURLConnection con = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
        try {
            con.setRequestMethod("POST");
            con.setRequestProperty("Authorization", "Bearer " + bearerToken);
            con.setRequestProperty("x-request-id", UUID.randomUUID().toString());
            con.setConnectTimeout(HTTP_TIMEOUT);
            con.setReadTimeout(HTTP_TIMEOUT);
            con.setDoOutput(true);

            try (OutputStream os = con.getOutputStream()) {
                os.write(new byte[0]);
            }

            int code = con.getResponseCode();
            if (code >= 400) {
                String errorBody = readStream(con.getErrorStream());
                throw new IOException("HTTP " + code + ": " + errorBody);
            }

            return readStream(con.getInputStream());
        } finally {
            con.disconnect();
        }
    }

    private String readStream(@Nullable InputStream stream) throws IOException {
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

    private static @Nullable String getStringOrNull(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        String value = obj.get(key).getAsString();
        return value.isEmpty() ? null : value;
    }

    public String formatExpiry(long epochSeconds) {
        if (epochSeconds <= 0) return "never/unset";
        return Instant.ofEpochSecond(epochSeconds)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
    }

    // ---- Tenant Token Pool Management ----

    /**
     * Register a server token with an already-known tenant ID.
     *
     * @param serverToken the server token value
     * @param tenantId the tenant ID to associate with this token
     * @param source a description of where this token came from (for logging)
     */
    public void registerServerToken(String serverToken, String tenantId, String source) {
        if (tenantId == null || tenantId.isEmpty()) {
            if (logger != null) {
                logger.warning("[EduTenancy] Cannot register token with null/empty tenant ID (source: " + source + ")");
            }
            return;
        }
        tenantTokenPool.put(tenantId, serverToken);
        if (logger != null) {
            logger.debug("[EduTenancy] Registered token for tenant %s (source: %s)", tenantId, source);
        }
    }

    /**
     * Register a server token from config. Extracts the tenant ID from the
     * pipe-separated server token format automatically.
     *
     * @param token the raw token string from config
     * @param source a description of where this token came from (for logging)
     */
    public void registerServerTokenFromConfig(String token, String source) {
        String tenantId = extractTenantIdFromServerToken(token);
        if (tenantId != null) {
            tenantTokenPool.put(tenantId, token);
            if (logger != null) {
                logger.debug("[EduTenancy] Registered token for tenant %s (source: %s)", tenantId, source);
            }
        } else if (logger != null) {
            logger.warning("[EduTenancy] Could not extract tenant ID from token (source: " + source + "). Token will not be usable for routing.");
        }
    }

    /**
     * Load server tokens from config and register them in the tenant pool.
     * Called during initialization for hybrid and standalone tenancy modes.
     */
    public void loadConfigTokens(GeyserImpl geyser) {
        List<String> configTokens = geyser.config().education().serverTokens();
        if (configTokens != null && !configTokens.isEmpty()) {
            geyser.getLogger().debug("[EduTenancy] Loading %s server token(s) from config", configTokens.size());
            for (String token : configTokens) {
                if (token != null && !token.isBlank()) {
                    registerServerTokenFromConfig(token.trim(), "config edu-server-tokens");
                }
            }
        } else if ("standalone".equalsIgnoreCase(geyser.config().education().tenancyMode())) {
            geyser.getLogger().warning("[EduTenancy] Standalone mode but no edu-server-tokens configured. No tenants will be able to connect.");
        }
    }

    /**
     * Extract tenant ID from a pipe-separated server token.
     * Format: tenantId|serverId|expiry|signature.
     *
     * @param serverToken the pipe-separated server token string
     * @return the tenant ID, or null if malformed
     */
    public @Nullable String extractTenantIdFromServerToken(String serverToken) {
        if (serverToken == null || serverToken.isEmpty()) {
            return null;
        }
        String[] parts = serverToken.split("\\|");
        if (parts.length >= 4) {
            String tenantId = parts[0].trim();
            if (!tenantId.isEmpty()) {
                return tenantId;
            }
        }
        if (logger != null) {
            logger.warning("[EduTenancy] Unexpected server token format (" + parts.length + " pipe segments). Cannot extract tenant ID.");
        }
        return null;
    }

    /**
     * Extract the tenant ID from an EduTokenChain JWT.
     * The payload contains a "chain" field: "tenantId|signature|expiry|nonce".
     *
     * <p>IMPORTANT: Do NOT use BedrockClientData.getTenantId() -- it is always
     * null for Education Edition clients. This is the only reliable source.</p>
     *
     * @param eduTokenChain the raw EduTokenChain JWT from client data
     * @return the tenant ID, or null if extraction fails
     */
    public @Nullable String extractTenantIdFromEduTokenChain(String eduTokenChain) {
        if (eduTokenChain == null || eduTokenChain.isEmpty()) {
            return null;
        }
        try {
            String[] parts = eduTokenChain.split("\\.");
            if (parts.length < 2) {
                if (logger != null) {
                    logger.warning("[EduTenancy] EduTokenChain is not a valid JWT (parts: " + parts.length + ")");
                }
                return null;
            }
            String payloadJson = new String(java.util.Base64.getUrlDecoder().decode(EducationChainVerifier.padBase64(parts[1])));
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();

            if (!payload.has("chain")) {
                if (logger != null) {
                    logger.warning("[EduTenancy] EduTokenChain payload has no 'chain' field. Keys: " + payload.keySet());
                }
                return null;
            }

            String chain = payload.get("chain").getAsString();
            String[] chainParts = chain.split("\\|");
            if (chainParts.length < 1 || chainParts[0].isEmpty()) {
                if (logger != null) {
                    logger.warning("[EduTenancy] EduTokenChain 'chain' field is empty or has no tenant ID");
                }
                return null;
            }

            String tenantId = chainParts[0];
            if (logger != null) {
                logger.debug("[EduTenancy] Extracted tenant ID from EduTokenChain: %s", tenantId);
            }
            return tenantId;
        } catch (Exception e) {
            if (logger != null) {
                logger.warning("[EduTenancy] Failed to extract tenant ID from EduTokenChain: " + e.getMessage());
            }
            return null;
        }
    }

    /**
     * Look up the appropriate server token for an education session.
     * Checks the tenant token pool first (by client tenant ID), then falls
     * back to the MESS-registered token.
     *
     * @param session the education client's session
     * @return the server token, or null if none available
     */
    public @Nullable String getTokenForSession(GeyserSession session) {
        String clientTenantId = session.getEducationTenantId();

        // Try tenant-specific token from pool
        if (clientTenantId != null && !clientTenantId.isEmpty()) {
            String poolToken = tenantTokenPool.get(clientTenantId);
            if (poolToken != null) {
                return poolToken;
            }
        }

        // Fallback: MESS-registered token
        if (serverToken != null && !serverToken.isEmpty()) {
            return serverToken;
        }

        return null;
    }

    /**
     * Get the number of registered tenant tokens (for diagnostics).
     */
    public int getRegisteredTenantCount() {
        return tenantTokenPool.size();
    }

}
