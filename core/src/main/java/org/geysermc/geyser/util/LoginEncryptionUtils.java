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
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.session.auth.AuthData;
import org.geysermc.geyser.session.auth.BedrockClientData;
import org.geysermc.geyser.text.ChatColor;
import org.geysermc.geyser.text.GeyserLocale;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;

import javax.crypto.SecretKey;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;
import java.util.function.BiConsumer;

public class LoginEncryptionUtils {
    private static boolean HAS_SENT_ENCRYPTION_MESSAGE = false;

    /**
     * MESS (Minecraft Education Server Services) RSA-1024 public key for verifying server tokens.
     * Retrieved from https://dedicatedserver.minecrafteduservices.com/public_keys/signing
     * Used to verify the signature on pipe-separated server tokens: tenantId|oid|expiry|signature
     * <p>
     * Hardcoded as a trust anchor, mirroring how CloudburstMC's {@link EncryptionUtils} handles
     * Mojang's root key. If Microsoft rotates the MESS key, edu auth will fail until this value
     * is updated and a new release is shipped.
     */
    private static final String MESS_SIGNING_KEY_BASE64 =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDsFCr3nD8N3TJxJZ7Y4g1Z20Son+fUWTSd2f" +
            "/XyIil2mGGGx/yjRj6l0ntbROsec8MZoaLsBG0nWm9/WhJcdXvJewbdd+mCyy7WXyYQgJcJPZP" +
            "3kgBDySZMUnaowlUmR9gxRr+LevCafZKQwb19nwJB0EUt+nQsWBbTe2SuIdCqQIDAQAB";

    /**
     * Parsed MESS public key, cached once at class load. The key is hardcoded and
     * never changes at runtime; reconstructing it per-verification is wasted work
     * and inflates the CPU cost of rejecting flooded/invalid login attempts.
     */
    private static final PublicKey MESS_SIGNING_KEY;
    static {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(MESS_SIGNING_KEY_BASE64);
            MESS_SIGNING_KEY = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Outcome of {@link #verifyEducationServerToken(String)}.
     */
    private enum TokenVerifyResult {
        /** Signature is valid and the token has not expired. */
        VALID,
        /** Signature is valid but the MESS-signed expiry timestamp is in the past. */
        EXPIRED,
        /** Token is null, malformed, or the signature did not verify. */
        INVALID
    }

    public static void encryptPlayerConnection(GeyserSession session, LoginPacket loginPacket) {
        encryptConnectionWithCert(session, loginPacket.getAuthPayload(), loginPacket.getClientJwt());
    }

    private static void encryptConnectionWithCert(GeyserSession session, AuthPayload authPayload, String jwt) {
        try {
            GeyserImpl geyser = session.getGeyser();

            ChainValidationResult result = EncryptionUtils.validatePayload(authPayload);

            geyser.getLogger().debug("Is player data signed? %s", result.signed());

            IdentityData extraIdentityData = result.identityClaims().extraData;
            if (extraIdentityData == null || extraIdentityData.xuid == null || extraIdentityData.displayName == null) {
                GeyserImpl.getInstance().getLogger().debug("Missing identity data in login identity claims! " + extraIdentityData);
                session.disconnect(GeyserLocale.getLocaleStringLog("geyser.network.remote.invalid_xbox_account"));
                return;
            }

            // Should always be present, but hey, why not make it safe :D
            Long rawIssuedAt = (Long) result.rawIdentityClaims().get("iat");
            long issuedAt = rawIssuedAt != null ? rawIssuedAt : -1;

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

            // Handle Education Edition: extract and verify the MESS-signed server token
            if (isEducationClient) {
                session.setEducationClient(true);

                // Extract the server token from the EduTokenChain JWT
                String eduTokenChain = data.getEduTokenChain();
                String serverToken = extractServerTokenFromEduTokenChain(eduTokenChain);

                TokenVerifyResult verifyResult = verifyEducationServerToken(serverToken);
                if (verifyResult == TokenVerifyResult.EXPIRED) {
                    // 10-day tokens commonly expire while the client is still running
                    // (e.g. a student leaves their Chromebook logged in for 2+ weeks).
                    // Tell them exactly what to do instead of a generic auth-failure.
                    geyser.getLogger().debug("MESS token expired for " + session.bedrockUsername());
                    session.disconnect("Your Education Edition session has expired.\n\nPlease fully restart Minecraft Education Edition and try again.");
                    return;
                }
                if (verifyResult != TokenVerifyResult.VALID) {
                    geyser.getLogger().debug("MESS token verification failed for " + session.bedrockUsername());
                    session.disconnect("Education Edition Connection Failed\n\nYour education token could not be verified.");
                    return;
                }

                // Split is safe here: verifyEducationServerToken guarantees exactly 4 fields on VALID.
                String[] tokenParts = serverToken.split("\\|", -1);
                session.setEducationTenantId(tokenParts[0]);
                session.setEducationServerToken(serverToken);
            }

            IdentityData extraData = result.identityClaims().extraData;
            // For education clients, use the MESS-verified oid as xuid
            String xuid = isEducationClient && session.getEducationServerToken() != null
                    ? session.getEducationServerToken().split("\\|")[1]
                    : extraData.xuid;
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
        if (session.isEducationClient() && session.getEducationServerToken() != null) {
            // Education: echo the client's MESS-signed server token back in the handshake JWT
            JsonWebSignature jws = new JsonWebSignature();
            jws.setAlgorithmHeaderValue("ES384");
            jws.setHeader("x5u", Base64.getEncoder().encodeToString(
                serverKeyPair.getPublic().getEncoded()));
            jws.setKey(serverKeyPair.getPrivate());

            JwtClaims claims = new JwtClaims();
            claims.setClaim("salt", Base64.getEncoder().encodeToString(token));
            claims.setClaim("signedToken", session.getEducationServerToken());
            jws.setPayload(claims.toJson());
            jwt = jws.getCompactSerialization();
        } else {
            jwt = EncryptionUtils.createHandshakeJwt(serverKeyPair, token);
        }

        SecretKey encryptionKey = EncryptionUtils.getSecretKey(serverKeyPair.getPrivate(), key, token);

        ServerToClientHandshakePacket packet = new ServerToClientHandshakePacket();
        packet.setJwt(jwt);
        session.sendUpstreamPacketImmediately(packet);
        session.getUpstream().getSession().enableEncryption(encryptionKey);
    }

    /**
     * Extract the pipe-separated server token from the client's EduTokenChain JWT.
     * <p>
     * The outer JWT's signature is intentionally NOT verified here. Education login chains
     * are self-signed with an ephemeral client key, so verifying the outer signature proves
     * only that the client signed its own JWT. It proves nothing about identity. Only the inner
     * {@code chain} field is load-bearing, and it carries its own MESS RSA signature which
     * is verified separately by {@link #verifyEducationServerToken}. Do not extend this
     * method to read any other field from the outer JWT without adding dedicated verification.
     */
    private static String extractServerTokenFromEduTokenChain(String eduTokenChain) {
        if (eduTokenChain == null || eduTokenChain.isEmpty()) {
            return null;
        }
        try {
            String[] jwtParts = eduTokenChain.split("\\.");
            if (jwtParts.length < 2) {
                return null;
            }
            // Base64.getUrlDecoder() accepts unpadded input; JWT payloads are always unpadded per RFC 7515.
            String payloadJson = new String(Base64.getUrlDecoder().decode(jwtParts[1]), StandardCharsets.UTF_8);
            JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();
            if (!payload.has("chain")) {
                return null;
            }
            String chain = payload.get("chain").getAsString();
            String[] parts = chain.split("\\|");
            if (parts.length < 4) {
                return null;
            }
            return chain;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Verify the MESS signature on an education server token and enforce its expiry.
     * <p>
     * Token format: {@code tenantId|oid|expiry|hexSignature} where expiry is an
     * ISO 8601 UTC timestamp and signature is RSA PKCS#1 v1.5 with SHA-256 over
     * {@code tenantId|oid|expiry} using the MESS signing key.
     * <p>
     * The expiry check mirrors the Education client exactly: {@code parsed_expiry < now}
     * is expired, and a timestamp that fails to parse is treated as expired (the client
     * forces it to epoch 0 on parse failure).
     */
    private static TokenVerifyResult verifyEducationServerToken(String serverToken) {
        if (serverToken == null) {
            return TokenVerifyResult.INVALID;
        }
        String[] parts = serverToken.split("\\|", -1);
        if (parts.length != 4) {
            return TokenVerifyResult.INVALID;
        }
        try {
            String signedData = parts[0] + "|" + parts[1] + "|" + parts[2];
            byte[] signatureBytes = hexToBytes(parts[3]);

            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(MESS_SIGNING_KEY);
            sig.update(signedData.getBytes(StandardCharsets.UTF_8));
            if (!sig.verify(signatureBytes)) {
                return TokenVerifyResult.INVALID;
            }
        } catch (Exception e) {
            return TokenVerifyResult.INVALID;
        }

        // Matches the client's check. Expired if parsed_expiry is before now.
        // Failed-to-parse also treated as expired (client forces value to 0 on parse failure).
        try {
            Instant expiry = Instant.parse(parts[2]);
            if (Instant.now().isAfter(expiry)) {
                return TokenVerifyResult.EXPIRED;
            }
        } catch (DateTimeParseException e) {
            return TokenVerifyResult.EXPIRED;
        }

        // Enforce UUID shape on tenantId and oid at the verification boundary so any
        // unexpected MESS output fails here rather than at UUID generation mid-session.
        try {
            UUID.fromString(parts[0]);
            UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            return TokenVerifyResult.INVALID;
        }
        return TokenVerifyResult.VALID;
    }

    private static byte[] hexToBytes(String hex) {
        if ((hex.length() & 1) != 0) {
            throw new IllegalArgumentException("odd-length hex string");
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
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
