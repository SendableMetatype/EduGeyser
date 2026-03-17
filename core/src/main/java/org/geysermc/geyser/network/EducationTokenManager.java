/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
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
import org.geysermc.geyser.GeyserLogger;

import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the tenant token pool for Education Edition multi-tenancy.
 * Handles registration, lookup, and tenant ID extraction from both
 * server tokens (pipe-separated format) and EduTokenChain JWTs.
 */
public final class EducationTokenManager {

    private final ConcurrentHashMap<String, String> tenantTokenPool = new ConcurrentHashMap<>();

    /**
     * Register a server token with an already-known tenant ID.
     *
     * @param logger the logger instance
     * @param serverToken the server token value
     * @param tenantId the tenant ID to associate with this token
     * @param source a description of where this token came from (for logging)
     */
    public void registerServerToken(GeyserLogger logger, String serverToken, String tenantId, String source) {
        if (tenantId == null || tenantId.isEmpty()) {
            logger.warning("[EduTenancy] Cannot register token with null/empty tenant ID (source: " + source + ")");
            return;
        }
        tenantTokenPool.put(tenantId, serverToken);
        logger.debug("[EduTenancy] Registered token for tenant %s (source: %s)", tenantId, source);
    }

    /**
     * Register a server token from config. Accepts either the raw pipe-separated
     * server token or a full MESS-format outer JWT. Extracts the tenant ID and
     * inner server token automatically.
     *
     * @param logger the logger instance
     * @param token the raw token string from config
     * @param source a description of where this token came from (for logging)
     */
    public void registerServerTokenFromConfig(GeyserLogger logger, String token, String source) {
        String tenantId = extractTenantIdFromServerToken(logger, token);
        if (tenantId != null) {
            tenantTokenPool.put(tenantId, token);
            logger.debug("[EduTenancy] Registered token for tenant %s (source: %s)", tenantId, source);
        } else {
            logger.warning("[EduTenancy] Could not extract tenant ID from token (source: " + source + "). Token will not be usable for routing.");
        }
    }

    /**
     * Extract tenant ID from a server token.
     * Server token format: tenantId|serverId|expiry|signature (pipe-separated).
     * The tenant ID is always the first segment.
     *
     * @param logger the logger instance for warnings
     * @param serverToken the pipe-separated server token string
     * @return the tenant ID, or null if the token is null, empty, or malformed
     */
    public @Nullable String extractTenantIdFromServerToken(GeyserLogger logger, String serverToken) {
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

        logger.warning("[EduTenancy] Unexpected server token format (" + parts.length + " pipe segments). Cannot extract tenant ID.");
        return null;
    }

    /**
     * Extract the tenant ID from an EduTokenChain JWT.
     * The EduTokenChain payload contains a "chain" field formatted as:
     * "tenantId|signature|expiry|nonce" -- we split by pipe and take index 0.
     *
     * <p>IMPORTANT: Do NOT use BedrockClientData.getTenantId() -- it is always null
     * for Education Edition clients. This is the only reliable source.</p>
     *
     * @param logger the logger instance
     * @param eduTokenChain the raw EduTokenChain JWT string from client data
     * @return the tenant ID, or null if extraction fails
     */
    public @Nullable String extractTenantIdFromEduTokenChain(GeyserLogger logger, String eduTokenChain) {
        if (eduTokenChain == null || eduTokenChain.isEmpty()) {
            return null;
        }
        try {
            String[] parts = eduTokenChain.split("\\.");
            if (parts.length < 2) {
                logger.warning("[EduTenancy] EduTokenChain is not a valid JWT (parts: " + parts.length + ")");
                return null;
            }
            String payloadJson = new String(Base64.getUrlDecoder().decode(padBase64(parts[1])));
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();

            if (!payload.has("chain")) {
                logger.warning("[EduTenancy] EduTokenChain payload has no 'chain' field. Keys: " + payload.keySet());
                return null;
            }

            String chain = payload.get("chain").getAsString();
            String[] chainParts = chain.split("\\|");
            if (chainParts.length < 1 || chainParts[0].isEmpty()) {
                logger.warning("[EduTenancy] EduTokenChain 'chain' field is empty or has no tenant ID");
                return null;
            }

            String tenantId = chainParts[0];
            logger.debug("[EduTenancy] Extracted tenant ID from EduTokenChain: %s", tenantId);
            return tenantId;
        } catch (Exception e) {
            logger.warning("[EduTenancy] Failed to extract tenant ID from EduTokenChain: " + e.getMessage());
            return null;
        }
    }

    /**
     * Look up the server token for a given tenant ID.
     *
     * @param tenantId the tenant ID to look up
     * @return the server token, or null if no token is registered for this tenant
     */
    public @Nullable String getTokenForTenant(String tenantId) {
        return tenantTokenPool.get(tenantId);
    }

    /**
     * Get the number of registered tenant tokens (for diagnostics).
     */
    public int getRegisteredTenantCount() {
        return tenantTokenPool.size();
    }

    /**
     * Pads a Base64URL string to the correct length for decoding.
     */
    static String padBase64(String base64) {
        int padding = 4 - (base64.length() % 4);
        if (padding != 4) {
            base64 += "=".repeat(padding);
        }
        return base64;
    }
}
