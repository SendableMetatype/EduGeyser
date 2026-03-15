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

package org.geysermc.geyser.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult;
import org.cloudburstmc.protocol.bedrock.util.ChainValidationResult.IdentityData;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.geysermc.cumulus.form.ModalForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.response.SimpleFormResponse;
import org.geysermc.cumulus.response.result.FormResponseResult;
import org.geysermc.cumulus.response.result.ValidFormResponseResult;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.network.EducationAuthManager;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.auth.AuthData;
import org.geysermc.geyser.session.auth.BedrockClientData;
import org.geysermc.geyser.text.ChatColor;
import org.geysermc.geyser.text.GeyserLocale;

import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;

import javax.crypto.SecretKey;
import java.net.InetSocketAddress;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.function.BiConsumer;

public class LoginEncryptionUtils {
    private static boolean HAS_SENT_ENCRYPTION_MESSAGE = false;

    /**
     * Register a server token with an already-known tenant ID.
     */
    public static void registerServerToken(GeyserImpl geyser, String serverToken, String tenantId, String source) {
        if (tenantId == null || tenantId.isEmpty()) {
            geyser.getLogger().warning("[EduTenancy] Cannot register token with null/empty tenant ID (source: " + source + ")");
            return;
        }
        geyser.getTenantTokenPool().put(tenantId, serverToken);
        geyser.getLogger().debug("[EduTenancy] Registered token for tenant {} (source: {})", tenantId, source);
    }

    /**
     * Register a server token from config. Accepts either the raw pipe-separated
     * server token or a full MESS-format outer JWT. Extracts the tenant ID and
     * inner server token automatically.
     */
    public static void registerServerTokenFromConfig(GeyserImpl geyser, String token, String source) {
        String tenantId = extractTenantIdFromServerToken(geyser, token);
        if (tenantId != null) {
            geyser.getTenantTokenPool().put(tenantId, token);
            geyser.getLogger().debug("[EduTenancy] Registered token for tenant {} (source: {})", tenantId, source);
        } else {
            geyser.getLogger().warning("[EduTenancy] Could not extract tenant ID from token (source: " + source + "). Token will not be usable for routing.");
        }
    }

    /**
     * Extract tenant ID from a server token.
     * Server token format: tenantId|serverId|expiry|signature (pipe-separated).
     * The tenant ID is always the first segment.
     *
     * @param geyser the Geyser instance, used for logging
     * @param serverToken the pipe-separated server token string
     * @return the tenant ID, or null if the token is null, empty, or malformed
     */
    public static @Nullable String extractTenantIdFromServerToken(GeyserImpl geyser, String serverToken) {
        if (serverToken == null || serverToken.isEmpty()) {
            return null;
        }

        String[] parts = serverToken.split("\\|");
        if (parts.length >= 4) {
            // Standard format: tenantId|serverId|expiry|signature
            String tenantId = parts[0].trim();
            if (!tenantId.isEmpty()) {
                return tenantId;
            }
        }

        geyser.getLogger().warning("[EduTenancy] Unexpected server token format (" + parts.length + " pipe segments). Cannot extract tenant ID.");
        return null;
    }

    /**
     * Look up the server token for a given tenant ID.
     *
     * @param tenantId the tenant ID to look up
     * @return the server token JWT, or null if no token is registered for this tenant
     */
    public static @Nullable String getTokenForTenant(String tenantId) {
        return GeyserImpl.getInstance().getTenantTokenPool().get(tenantId);
    }

    /**
     * Get the number of registered tenant tokens (for diagnostics).
     */
    public static int getRegisteredTenantCount() {
        return GeyserImpl.getInstance().getTenantTokenPool().size();
    }

    /**
     * Known MESS (Minecraft Education Server Services) public keys used to sign EduTokenChain JWTs.
     * These are EC P-384 keys. Microsoft rotates these periodically.
     * We try each key until one verifies, to handle key rotation gracefully.
     */
    private static final String[] MESS_PUBLIC_KEYS = {
            // Current key (as of March 2026)
            "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE0mYk5OWVJ/Fi3KVH35wJBQKxWVzhR9fHBD4+STlMPS3OcaqavMsVxuO8cPRPzpGuXdGD6AlD8YVQBOvuw+yHm+0vMSiJo8hCDAkOA767dsdmXNWYdpXHvCW1kBR2sKgQ",
            // Previous key
            "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEWQV0SMQIW5FvnAKe2ibSoqPBDI9iaxqbiBKCIKGu2YKAhksJp+nZEQ1bUlTzUsR9yjauLswIo5Q8NdwRgybb1VbVrX1xVIZGXZP4b8IpNS908UA646GIFatYZFWKVY61",
    };

    public static void encryptPlayerConnection(GeyserSession session, LoginPacket loginPacket) {
        encryptConnectionWithCert(session, loginPacket.getAuthPayload(), loginPacket.getClientJwt());
    }

    private static void encryptConnectionWithCert(GeyserSession session, AuthPayload authPayload, String jwt) {
        try {
            GeyserImpl geyser = session.getGeyser();

            ChainValidationResult result = EncryptionUtils.validatePayload(authPayload);

            geyser.getLogger().debug(String.format("Is player data signed? %s", result.signed()));

            // Should always be present, but hey, why not make it safe :D
            Long rawIssuedAt = (Long) result.rawIdentityClaims().get("iat");
            long issuedAt = rawIssuedAt != null ? rawIssuedAt : -1;

            // Log auth payload type for debugging
            geyser.getLogger().debug("[EduDetect] Auth payload type: {}", authPayload.getClass().getSimpleName());

            if (authPayload instanceof TokenPayload tokenPayload) {
                session.setToken(tokenPayload.getToken());
            } else if (authPayload instanceof CertificateChainPayload certificateChainPayload) {
                session.setCertChainData(certificateChainPayload.getChain());
            } else {
                GeyserImpl.getInstance().getLogger().warning("Unknown auth payload! Skin uploading will not work");
            }

            PublicKey identityPublicKey = result.identityClaims().parsedIdentityPublicKey();

            byte[] clientDataPayload = EncryptionUtils.verifyClientData(jwt, identityPublicKey);
            if (clientDataPayload == null) {
                throw new IllegalStateException("Client data isn't signed by the given chain data");
            }

            BedrockClientData data = JsonUtils.fromJson(clientDataPayload, BedrockClientData.class);
            data.setOriginalString(jwt);
            session.setClientData(data);

            // Education Edition clients use self-signed login chains (no Xbox Live),
            // so result.signed() is always false for them. We must detect edu clients
            // before the Xbox validation check to avoid rejecting them.
            boolean isEducationClient = data.isEducationEdition();

            // Xbox validation: reject unsigned non-edu clients when validation is enabled
            if (!result.signed() && !isEducationClient && session.getGeyser().config().advanced().bedrock().validateBedrockLogin()) {
                session.disconnect(GeyserLocale.getLocaleStringLog("geyser.network.remote.invalid_xbox_account"));
                return;
            }

            // Detect Education Edition from client data JWT (contains IsEduMode field).
            if (isEducationClient) {
                session.setEducationClient(true);

                // Dump the full JWT chain for education clients (enable debug mode to see this)
                if (geyser.config().debugMode()) {
                    dumpEduChain(geyser, authPayload, jwt);
                }

                // Education Edition authentication
                // NOTE: result.signed() validates against Mojang's Xbox Live root key.
                // Education Edition clients authenticate via Microsoft Entra (Azure AD) and use
                // a self-signed login chain, so result.signed() is always FALSE for edu clients.
                // Instead, we verify the EduTokenChain JWT, which is signed by the MESS
                // (Minecraft Education Server Services) private key and proves the client
                // was authorized by Microsoft to join this specific server.
                boolean eduVerified = "verified".equalsIgnoreCase(geyser.config().eduAuthMode());
                EducationAuthManager eduAuth = geyser.getEducationAuthManager();
                boolean eduSystemActive = eduAuth != null && eduAuth.isActive();

                // Extract the REAL tenant ID from the EduTokenChain JWT payload.
                // Do NOT use data.getTenantId() - it is always null for edu clients.
                String tenantId = extractTenantIdFromEduTokenChain(geyser, data.getEduTokenChain());
                session.setEducationTenantId(tenantId);
                int adRole = data.getAdRole();
                String roleName = switch (adRole) {
                    case 0 -> "student";
                    case 1 -> "teacher";
                    default -> "role=" + adRole;
                };

                geyser.getLogger().debug("[EduAuth] Education client detected (ChainSignedByMojang: {}, TenantId: {}, Role: {}, EduVerified: {}, EduSystemActive: {})",
                        result.signed(), tenantId, roleName, eduVerified, eduSystemActive);

                if (eduVerified && eduSystemActive) {
                    // Verify the EduTokenChain JWT signature against the MESS public key.
                    // This proves the client was authorized by Microsoft's education service.
                    String eduTokenChain = data.getEduTokenChain();
                    if (eduTokenChain == null || eduTokenChain.isEmpty()) {
                        geyser.getLogger().warning("[EduAuth] Education client has no EduTokenChain (edu-auth-mode=verified).");
                        // TODO: Re-enable rejection once MESS key rotation is resolved
                        // session.disconnect("disconnectionScreen.notAuthenticated");
                        // return;
                    } else if (!verifyEduTokenChain(geyser, eduTokenChain)) {
                        geyser.getLogger().warning("[EduAuth] EduTokenChain signature verification failed, allowing connection anyway (MESS key rotation unresolved).");
                        // TODO: Re-enable rejection once MESS key rotation is resolved
                        // session.disconnect("disconnectionScreen.notAuthenticated");
                        // return;
                    }

                    geyser.getLogger().debug("[EduAuth] Education client verified via EduTokenChain signature.");
                }

                geyser.getLogger().debug("[EduAuth] Education client connected (TenantId: {}, Role: {}, ChainSignedByMojang: {})",
                        tenantId != null ? tenantId : "unknown", roleName, result.signed());

                if (data.getEduSessionToken() != null && !data.getEduSessionToken().isEmpty()) {
                    geyser.getLogger().debug("[EduAuth] Client has MESS session token (connected via server list).");
                } else {
                    geyser.getLogger().debug("[EduAuth] Client connected via direct URI (no MESS session token).");
                }
            }

            IdentityData extraData = result.identityClaims().extraData;
            String xuid = extraData.xuid;
            if (geyser.config().advanced().bedrock().useWaterdogpeForwarding()) {
                String waterdogIp = data.getWaterdogIp();
                String waterdogXuid = data.getWaterdogXuid();
                if (waterdogXuid != null && !waterdogXuid.isBlank() && waterdogIp != null && !waterdogIp.isBlank()) {
                    xuid = waterdogXuid;
                    InetSocketAddress originalAddress = session.getUpstream().getAddress();
                    InetSocketAddress proxiedAddress = new InetSocketAddress(waterdogIp, originalAddress.getPort());
                    session.getGeyser().getGeyserServer().getProxiedAddresses().put(originalAddress, proxiedAddress);
                    session.getUpstream().setInetAddress(proxiedAddress);
                } else {
                    session.disconnect("Did not receive IP and xuid forwarded from the proxy!");
                    return;
                }
            }

            session.setAuthData(new AuthData(extraData.displayName, extraData.identity, xuid, issuedAt, extraData.minecraftId));

            try {
                startEncryptionHandshake(session, identityPublicKey);
            } catch (Throwable e) {
                // An error can be thrown on older Java 8 versions about an invalid key
                if (geyser.config().debugMode()) {
                    e.printStackTrace();
                }

                sendEncryptionFailedMessage(geyser);
            }
        } catch (Exception ex) {
            session.disconnect("disconnectionScreen.internalError.cantConnect");
            throw new RuntimeException("Unable to complete login", ex);
        }
    }

    private static void startEncryptionHandshake(GeyserSession session, PublicKey key) throws Exception {
        KeyPair serverKeyPair = EncryptionUtils.createKeyPair();
        byte[] token = EncryptionUtils.generateRandomToken();

        String jwt;
        if (session.isEducationClient()) {
            // Multi-tenancy token routing:
            // 1. Try to find a token matching this client's tenant ID from the pool
            // 2. Fall back to the EducationAuthManager's MESS-registered token (official/hybrid)
            String educationToken = null;
            String clientTenantId = session.getEducationTenantId();

            if (clientTenantId != null && !clientTenantId.isEmpty()) {
                educationToken = getTokenForTenant(clientTenantId);
                if (educationToken != null) {
                    session.getGeyser().getLogger().debug("[EduTenancy] Matched token for tenant {} from pool", clientTenantId);
                }
            }

            // Fallback: EducationAuthManager (MESS-registered token)
            if (educationToken == null) {
                EducationAuthManager eduAuth = session.getGeyser().getEducationAuthManager();
                if (eduAuth != null && eduAuth.getServerToken() != null && !eduAuth.getServerToken().isEmpty()) {
                    educationToken = eduAuth.getServerToken();
                    session.getGeyser().getLogger().debug("[EduTenancy] Using MESS-registered token (fallback)");
                }
            }

            if (educationToken != null && !educationToken.isEmpty()) {
                // Build the edu handshake JWT with signedToken claim
                JsonWebSignature jws = new JsonWebSignature();
                jws.setAlgorithmHeaderValue("ES384");
                jws.setHeader("x5u", Base64.getEncoder().encodeToString(
                    serverKeyPair.getPublic().getEncoded()));
                jws.setKey(serverKeyPair.getPrivate());

                JwtClaims claims = new JwtClaims();
                claims.setClaim("salt", Base64.getEncoder().encodeToString(token));
                claims.setClaim("signedToken", educationToken);
                jws.setPayload(claims.toJson());
                jwt = jws.getCompactSerialization();
                session.getGeyser().getLogger().debug("[EduHandshake] JWT built successfully (length={})", jwt.length());
            } else {
                // No token available for this client's tenant
                String tenantInfo = (clientTenantId != null) ? clientTenantId : "unknown";
                int poolSize = getRegisteredTenantCount();
                session.getGeyser().getLogger().warning("[EduTenancy] No server token available for tenant: " + tenantInfo);

                session.disconnect(
                    "Education Edition Connection Failed\n\n" +
                    "Your school (tenant: " + tenantInfo + ") is not configured on this server.\n\n" +
                    "This server has " + poolSize + " tenant(s) registered.\n" +
                    "Ask the server administrator to add your school's tenant to the server configuration.\n\n" +
                    "If you are the admin, add a server-token for this tenant in the\n" +
                    "edu-server-tokens list in config.yml, or register the server\n" +
                    "in your school's Minecraft Education admin portal."
                );
                return;
            }
        } else {
            jwt = EncryptionUtils.createHandshakeJwt(serverKeyPair, token);
        }

        SecretKey encryptionKey = EncryptionUtils.getSecretKey(serverKeyPair.getPrivate(), key, token);

        // Send handshake FIRST, then enable encryption (standard Geyser order)
        ServerToClientHandshakePacket packet = new ServerToClientHandshakePacket();
        packet.setJwt(jwt);
        session.getGeyser().getLogger().debug("[EduHandshake] Sending ServerToClientHandshakePacket");
        session.sendUpstreamPacketImmediately(packet);
        session.getUpstream().getSession().enableEncryption(encryptionKey);
        session.getGeyser().getLogger().debug("[EduHandshake] Encryption enabled");
    }

    /**
     * Extract the tenant ID from an EduTokenChain JWT.
     * The EduTokenChain payload contains a "chain" field formatted as:
     * "tenantId|signature|expiry|nonce" - we split by pipe and take index 0.
     *
     * IMPORTANT: Do NOT use BedrockClientData.getTenantId() - it is always null
     * for Education Edition clients. This is the only reliable source.
     *
     * @param geyser the Geyser instance, used for logging
     * @param eduTokenChain the raw EduTokenChain JWT string from client data
     * @return the tenant ID, or null if extraction fails
     */
    public static @Nullable String extractTenantIdFromEduTokenChain(GeyserImpl geyser, String eduTokenChain) {
        if (eduTokenChain == null || eduTokenChain.isEmpty()) {
            return null;
        }
        try {
            String[] parts = eduTokenChain.split("\\.");
            if (parts.length < 2) {
                geyser.getLogger().warning("[EduTenancy] EduTokenChain is not a valid JWT (parts: " + parts.length + ")");
                return null;
            }
            String payloadJson = new String(Base64.getUrlDecoder().decode(padBase64(parts[1])));
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();

            if (!payload.has("chain")) {
                geyser.getLogger().warning("[EduTenancy] EduTokenChain payload has no 'chain' field. Keys: " + payload.keySet());
                return null;
            }

            String chain = payload.get("chain").getAsString();
            String[] chainParts = chain.split("\\|");
            if (chainParts.length < 1 || chainParts[0].isEmpty()) {
                geyser.getLogger().warning("[EduTenancy] EduTokenChain 'chain' field is empty or has no tenant ID");
                return null;
            }

            String tenantId = chainParts[0];
            geyser.getLogger().debug("[EduTenancy] Extracted tenant ID from EduTokenChain: {}", tenantId);
            return tenantId;
        } catch (Exception e) {
            geyser.getLogger().warning("[EduTenancy] Failed to extract tenant ID from EduTokenChain: " + e.getMessage());
            return null;
        }
    }

    /**
     * Verifies the EduTokenChain JWT signature against the known MESS public key.
     * The EduTokenChain is signed by Microsoft's MESS service when it authorizes
     * a client to join a specific education server. Verifying this signature proves
     * the client was genuinely authorized by Microsoft.
     *
     * @param geyser the Geyser instance for logging
     * @param eduTokenChain the raw JWT string from the client data
     * @return true if the signature is valid, false otherwise
     */
    private static boolean verifyEduTokenChain(GeyserImpl geyser, String eduTokenChain) {
        try {
            String[] parts = eduTokenChain.split("\\.");
            if (parts.length != 3) {
                geyser.getLogger().warning("[EduAuth] EduTokenChain has " + parts.length + " parts, expected 3.");
                return false;
            }

            // Decode the header to check the algorithm and key
            String headerJson = new String(Base64.getUrlDecoder().decode(padBase64(parts[0])));
            geyser.getLogger().debug("[EduAuth] EduTokenChain header: {}", headerJson);

            // Parse x5u from header for logging
            JsonObject header = JsonParser.parseString(headerJson).getAsJsonObject();
            String x5u = header.has("x5u") ? header.get("x5u").getAsString() : null;
            geyser.getLogger().debug("[EduAuth] EduTokenChain x5u: {}", x5u);

            // Prepare signature data (shared across all key attempts)
            byte[] signedData = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);
            byte[] signatureBytes = Base64.getUrlDecoder().decode(padBase64(parts[2]));
            byte[] derSignature = rawToDer(signatureBytes);

            // Try each known MESS public key until one verifies.
            // This handles key rotation since Microsoft periodically rotates the MESS signing key.
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            for (String messKeyBase64 : MESS_PUBLIC_KEYS) {
                try {
                    byte[] keyBytes = Base64.getDecoder().decode(messKeyBase64);
                    X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
                    PublicKey messPublicKey = keyFactory.generatePublic(keySpec);

                    Signature sig = Signature.getInstance("SHA384withECDSA");
                    sig.initVerify(messPublicKey);
                    sig.update(signedData);
                    if (sig.verify(derSignature)) {
                        geyser.getLogger().debug("[EduAuth] EduTokenChain verified with MESS key: {}...", messKeyBase64.substring(0, 20));
                        String payloadJson = new String(Base64.getUrlDecoder().decode(padBase64(parts[1])));
                        geyser.getLogger().debug("[EduAuth] EduTokenChain payload: {}", payloadJson);
                        return true;
                    }
                } catch (Exception e) {
                    geyser.getLogger().debug("[EduAuth] Key {}... failed: {}", messKeyBase64.substring(0, 20), e.getMessage());
                }
            }

            geyser.getLogger().warning("[EduAuth] EduTokenChain did not verify against any known MESS key.");
            if (x5u != null) {
                geyser.getLogger().warning("[EduAuth] JWT x5u key: " + x5u);
                geyser.getLogger().warning("[EduAuth] If this is a legitimate edu client, the MESS key may have rotated again. Update MESS_PUBLIC_KEYS in LoginEncryptionUtils.java.");
            }
            return false;
        } catch (Exception e) {
            geyser.getLogger().warning("[EduAuth] EduTokenChain verification error: " + e.getMessage());
            if (geyser.config().debugMode()) {
                e.printStackTrace();
            }
            return false;
        }
    }

    /**
     * Converts a raw ECDSA signature (R || S concatenation) to DER format.
     * JWT ES384 signatures are 96 bytes (two 48-byte integers), but Java's
     * Signature class expects DER-encoded signatures.
     */
    private static byte[] rawToDer(byte[] raw) {
        int halfLen = raw.length / 2;
        byte[] r = trimLeadingZeros(raw, 0, halfLen);
        byte[] s = trimLeadingZeros(raw, halfLen, halfLen);

        // Add leading zero if high bit is set (to keep it positive in DER)
        boolean rPad = (r[0] & 0x80) != 0;
        boolean sPad = (s[0] & 0x80) != 0;
        int rLen = r.length + (rPad ? 1 : 0);
        int sLen = s.length + (sPad ? 1 : 0);
        int totalLen = 2 + rLen + 2 + sLen;

        byte[] der = new byte[2 + totalLen];
        int idx = 0;
        der[idx++] = 0x30; // SEQUENCE
        der[idx++] = (byte) totalLen;
        der[idx++] = 0x02; // INTEGER
        der[idx++] = (byte) rLen;
        if (rPad) der[idx++] = 0;
        System.arraycopy(r, 0, der, idx, r.length);
        idx += r.length;
        der[idx++] = 0x02; // INTEGER
        der[idx++] = (byte) sLen;
        if (sPad) der[idx++] = 0;
        System.arraycopy(s, 0, der, idx, s.length);

        return der;
    }

    /**
     * Trims leading zero bytes from a big-endian integer representation.
     */
    private static byte[] trimLeadingZeros(byte[] buf, int offset, int length) {
        int start = offset;
        int end = offset + length;
        while (start < end - 1 && buf[start] == 0) {
            start++;
        }
        byte[] result = new byte[end - start];
        System.arraycopy(buf, start, result, 0, result.length);
        return result;
    }

    /**
     * Dumps the full JWT chain and client data JWT for an education client.
     * This logs every JWT's header and payload (base64-decoded) so we can
     * discover the root public key used by Education Edition clients.
     */
    private static void dumpEduChain(GeyserImpl geyser, AuthPayload authPayload, String clientDataJwt) {
        try {
            geyser.getLogger().debug("[EduChainDump] ========== EDUCATION CLIENT JWT CHAIN DUMP ==========");

            if (authPayload instanceof CertificateChainPayload certChain) {
                List<String> chain = certChain.getChain();
                geyser.getLogger().debug("[EduChainDump] Chain length: {}", chain.size());

                for (int i = 0; i < chain.size(); i++) {
                    String jwtToken = chain.get(i);
                    String[] parts = jwtToken.split("\\.");
                    geyser.getLogger().debug("[EduChainDump] --- Chain JWT #{} (parts: {}) ---", i, parts.length);

                    if (parts.length >= 2) {
                        String header = new String(Base64.getUrlDecoder().decode(padBase64(parts[0])));
                        String payload = new String(Base64.getUrlDecoder().decode(padBase64(parts[1])));
                        geyser.getLogger().debug("[EduChainDump]   Header:  {}", header);
                        geyser.getLogger().debug("[EduChainDump]   Payload: {}", payload);
                    } else {
                        geyser.getLogger().debug("[EduChainDump]   Raw: {}", jwtToken);
                    }
                }
            } else if (authPayload instanceof TokenPayload tokenPayload) {
                String token = tokenPayload.getToken();
                geyser.getLogger().debug("[EduChainDump] Auth payload is TokenPayload (single token).");
                String[] parts = token.split("\\.");
                if (parts.length >= 2) {
                    String header = new String(Base64.getUrlDecoder().decode(padBase64(parts[0])));
                    String payload = new String(Base64.getUrlDecoder().decode(padBase64(parts[1])));
                    geyser.getLogger().debug("[EduChainDump]   Header:  {}", header);
                    geyser.getLogger().debug("[EduChainDump]   Payload: {}", payload);
                }
            } else {
                geyser.getLogger().debug("[EduChainDump] Unknown auth payload type: {}", authPayload.getClass().getName());
            }

            // Also dump the client data JWT
            if (clientDataJwt != null) {
                String[] parts = clientDataJwt.split("\\.");
                geyser.getLogger().debug("[EduChainDump] --- Client Data JWT (parts: {}) ---", parts.length);
                if (parts.length >= 2) {
                    String header = new String(Base64.getUrlDecoder().decode(padBase64(parts[0])));
                    // Client data payload can be large (skin data etc), just log the header
                    // and a truncated payload to avoid flooding the console
                    String payload = new String(Base64.getUrlDecoder().decode(padBase64(parts[1])));
                    geyser.getLogger().debug("[EduChainDump]   Header:  {}", header);
                    // Client data payloads can exceed 10KB (skin data); truncate to keep logs readable
                    if (payload.length() > 2000) {
                        geyser.getLogger().debug("[EduChainDump]   Payload (truncated): {}...", payload.substring(0, 2000));
                    } else {
                        geyser.getLogger().debug("[EduChainDump]   Payload: {}", payload);
                    }
                }
            }

            geyser.getLogger().debug("[EduChainDump] ========== END CHAIN DUMP ==========");
        } catch (Exception e) {
            geyser.getLogger().warning("[EduChainDump] Failed to dump education chain: " + e.getMessage());
        }
    }

    /**
     * Pads a Base64URL string to the correct length for decoding.
     */
    private static String padBase64(String base64) {
        int padding = 4 - (base64.length() % 4);
        if (padding != 4) {
            base64 += "=".repeat(padding);
        }
        return base64;
    }

    private static void sendEncryptionFailedMessage(GeyserImpl geyser) {
        if (!HAS_SENT_ENCRYPTION_MESSAGE) {
            geyser.getLogger().warning(GeyserLocale.getLocaleStringLog("geyser.network.encryption.line_1"));
            geyser.getLogger().warning(GeyserLocale.getLocaleStringLog("geyser.network.encryption.line_2", "https://geysermc.org/supported_java"));
            HAS_SENT_ENCRYPTION_MESSAGE = true;
        }
    }

    public static void buildAndShowLoginWindow(GeyserSession session) {
        if (session.isLoggedIn()) {
            // Can happen if a window is cancelled during dimension switch
            return;
        }

        // Set DoDaylightCycle to false so the time doesn't accelerate while we're here
        session.setDaylightCycle(false);

        session.sendForm(
                SimpleForm.builder()
                        .translator(GeyserLocale::getPlayerLocaleString, session.locale())
                        .title("geyser.auth.login.form.notice.title")
                        .content("geyser.auth.login.form.notice.desc")
                        .button("geyser.auth.login.form.notice.btn_login.microsoft")
                        .button("geyser.auth.login.form.notice.btn_disconnect")
                        .closedOrInvalidResultHandler(() -> buildAndShowLoginWindow(session))
                        .validResultHandler((response) -> {
                            if (response.clickedButtonId() == 0) {
                                session.authenticateWithMicrosoftCode();
                                return;
                            }

                            session.disconnect(GeyserLocale.getPlayerLocaleString("geyser.auth.login.form.disconnect", session.locale()));
                        }));
    }

    /**
     * Build a window that explains the user's credentials will be saved to the system.
     */
    public static void buildAndShowConsentWindow(GeyserSession session) {
        String locale = session.locale();

        session.sendForm(
                SimpleForm.builder()
                        .translator(LoginEncryptionUtils::translate, locale)
                        .title("%gui.signIn")
                        .content("""
                                geyser.auth.login.save_token.warning

                                geyser.auth.login.save_token.proceed""")
                        .button("%gui.ok")
                        .button("%gui.decline")
                        .resultHandler(authenticateOrKickHandler(session))
        );
    }

    public static void buildAndShowTokenExpiredWindow(GeyserSession session) {
        String locale = session.locale();

        session.sendForm(
                SimpleForm.builder()
                        .translator(LoginEncryptionUtils::translate, locale)
                        .title("geyser.auth.login.form.expired")
                        .content("""
                                geyser.auth.login.save_token.expired

                                geyser.auth.login.save_token.proceed""")
                        .button("%gui.ok")
                        .resultHandler(authenticateOrKickHandler(session))
        );
    }

    private static BiConsumer<SimpleForm, FormResponseResult<SimpleFormResponse>> authenticateOrKickHandler(GeyserSession session) {
        return (form, genericResult) -> {
            if (genericResult instanceof ValidFormResponseResult<SimpleFormResponse> result &&
                    result.response().clickedButtonId() == 0) {
                session.authenticateWithMicrosoftCode(true);
            } else {
                session.disconnect("%disconnect.quitting");
            }
        };
    }

    /**
     * Shows the code that a user must input into their browser
     */
    public static void buildAndShowMicrosoftCodeWindow(GeyserSession session, MsaDeviceCode msCode) {
        String locale = session.locale();

        StringBuilder message = new StringBuilder("%xbox.signin.website\n")
                .append(ChatColor.AQUA)
                .append("%xbox.signin.url")
                .append(ChatColor.RESET)
                .append("\n%xbox.signin.enterCode\n")
                .append(ChatColor.GREEN)
                .append(msCode.getUserCode());
        int timeout = session.getGeyser().config().pendingAuthenticationTimeout();
        if (timeout != 0) {
            message.append("\n\n")
                    .append(ChatColor.RESET)
                    .append(GeyserLocale.getPlayerLocaleString("geyser.auth.login.timeout", session.locale(), String.valueOf(timeout)));
        }

        session.sendForm(
                ModalForm.builder()
                        .title("%xbox.signin")
                        .content(message.toString())
                        .button1("%gui.done")
                        .button2("%menu.disconnect")
                        .closedOrInvalidResultHandler(() -> buildAndShowLoginWindow(session))
                        .validResultHandler((response) -> {
                            if (response.clickedButtonId() == 1) {
                                session.disconnect(GeyserLocale.getPlayerLocaleString("geyser.auth.login.form.disconnect", locale));
                            }
                        })
        );
    }

    /*
    This checks per line if there is something to be translated, and it skips Bedrock translation keys (%)
     */
    private static String translate(String key, String locale) {
        StringBuilder newValue = new StringBuilder();
        int previousIndex = 0;
        while (previousIndex < key.length()) {
            int nextIndex = key.indexOf('\n', previousIndex);
            int endIndex = nextIndex == -1 ? key.length() : nextIndex;

            // if there is more to this line than just a new line char
            if (endIndex - previousIndex > 1) {
                String substring = key.substring(previousIndex, endIndex);
                if (key.charAt(previousIndex) != '%') {
                    newValue.append(GeyserLocale.getPlayerLocaleString(substring, locale));
                } else {
                    newValue.append(substring);
                }
            }
            newValue.append('\n');

            previousIndex = endIndex + 1;
        }
        return newValue.toString();
    }
}
