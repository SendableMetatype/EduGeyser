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
import org.cloudburstmc.protocol.bedrock.data.auth.AuthPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.data.auth.TokenPayload;
import org.geysermc.geyser.GeyserLogger;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * Handles cryptographic verification of Education Edition EduTokenChain JWTs
 * against known MESS (Minecraft Education Server Services) public keys.
 * Also provides diagnostic chain dumping for development and debugging.
 */
public final class EducationChainVerifier {

    /**
     * Known MESS public keys used to sign EduTokenChain JWTs.
     * These are EC P-384 keys. Microsoft rotates these periodically.
     * We try each key until one verifies, to handle key rotation gracefully.
     */
    private static final String[] MESS_PUBLIC_KEYS = {
            // Current key (as of March 2026)
            "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE0mYk5OWVJ/Fi3KVH35wJBQKxWVzhR9fHBD4+STlMPS3OcaqavMsVxuO8cPRPzpGuXdGD6AlD8YVQBOvuw+yHm+0vMSiJo8hCDAkOA767dsdmXNWYdpXHvCW1kBR2sKgQ",
            // Previous key
            "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEWQV0SMQIW5FvnAKe2ibSoqPBDI9iaxqbiBKCIKGu2YKAhksJp+nZEQ1bUlTzUsR9yjauLswIo5Q8NdwRgybb1VbVrX1xVIZGXZP4b8IpNS908UA646GIFatYZFWKVY61",
    };

    private EducationChainVerifier() {
    }

    /**
     * Verifies the EduTokenChain JWT signature against the known MESS public keys.
     * The EduTokenChain is signed by Microsoft's MESS service when it authorizes
     * a client to join a specific education server. Verifying this signature proves
     * the client was genuinely authorized by Microsoft.
     *
     * @param logger the logger instance
     * @param eduTokenChain the raw JWT string from the client data
     * @return true if the signature is valid, false otherwise
     */
    public static boolean verifyEduTokenChain(GeyserLogger logger, String eduTokenChain) {
        try {
            String[] parts = eduTokenChain.split("\\.");
            if (parts.length != 3) {
                logger.warning("[EduAuth] EduTokenChain has " + parts.length + " parts, expected 3.");
                return false;
            }

            String headerJson = new String(Base64.getUrlDecoder().decode(EducationAuthManager.padBase64(parts[0])));
            logger.debug("[EduAuth] EduTokenChain header: %s", headerJson);

            JsonObject header = JsonParser.parseString(headerJson).getAsJsonObject();
            String x5u = header.has("x5u") ? header.get("x5u").getAsString() : null;
            logger.debug("[EduAuth] EduTokenChain x5u: %s", x5u);

            byte[] signedData = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8);
            byte[] signatureBytes = Base64.getUrlDecoder().decode(EducationAuthManager.padBase64(parts[2]));
            byte[] derSignature = rawToDer(signatureBytes);

            // Try each known MESS public key until one verifies
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
                        logger.debug("[EduAuth] EduTokenChain verified with MESS key: %s...", messKeyBase64.substring(0, 20));
                        String payloadJson = new String(Base64.getUrlDecoder().decode(EducationAuthManager.padBase64(parts[1])));
                        logger.debug("[EduAuth] EduTokenChain payload: %s", payloadJson);
                        return true;
                    }
                } catch (Exception e) {
                    logger.debug("[EduAuth] Key %s... failed: %s", messKeyBase64.substring(0, 20), e.getMessage());
                }
            }

            logger.warning("[EduAuth] EduTokenChain did not verify against any known MESS key.");
            if (x5u != null) {
                logger.warning("[EduAuth] JWT x5u key: " + x5u);
                logger.warning("[EduAuth] If this is a legitimate edu client, the MESS key may have rotated. Update MESS_PUBLIC_KEYS in EducationChainVerifier.java.");
            }
            return false;
        } catch (Exception e) {
            logger.warning("[EduAuth] EduTokenChain verification error: " + e.getMessage());
            return false;
        }
    }

    /**
     * Dumps the full JWT chain and client data JWT for an education client.
     * This logs every JWT's header and payload (base64-decoded) for debugging
     * the token structure used by Education Edition clients.
     *
     * @param logger the logger instance
     * @param authPayload the authentication payload from the login packet
     * @param clientDataJwt the client data JWT string
     */
    public static void dumpEduChain(GeyserLogger logger, AuthPayload authPayload, String clientDataJwt) {
        try {
            logger.debug("[EduChainDump] ========== EDUCATION CLIENT JWT CHAIN DUMP ==========");

            if (authPayload instanceof CertificateChainPayload certChain) {
                List<String> chain = certChain.getChain();
                logger.debug("[EduChainDump] Chain length: %s", chain.size());

                for (int i = 0; i < chain.size(); i++) {
                    String jwtToken = chain.get(i);
                    String[] parts = jwtToken.split("\\.");
                    logger.debug("[EduChainDump] --- Chain JWT #%s (parts: %s) ---", i, parts.length);

                    if (parts.length >= 2) {
                        String header = new String(Base64.getUrlDecoder().decode(EducationAuthManager.padBase64(parts[0])));
                        String payload = new String(Base64.getUrlDecoder().decode(EducationAuthManager.padBase64(parts[1])));
                        logger.debug("[EduChainDump]   Header:  %s", header);
                        logger.debug("[EduChainDump]   Payload: %s", payload);
                    } else {
                        logger.debug("[EduChainDump]   Raw: %s", jwtToken);
                    }
                }
            } else if (authPayload instanceof TokenPayload tokenPayload) {
                String token = tokenPayload.getToken();
                logger.debug("[EduChainDump] Auth payload is TokenPayload (single token).");
                String[] parts = token.split("\\.");
                if (parts.length >= 2) {
                    String header = new String(Base64.getUrlDecoder().decode(EducationAuthManager.padBase64(parts[0])));
                    String payload = new String(Base64.getUrlDecoder().decode(EducationAuthManager.padBase64(parts[1])));
                    logger.debug("[EduChainDump]   Header:  %s", header);
                    logger.debug("[EduChainDump]   Payload: %s", payload);
                }
            } else {
                logger.debug("[EduChainDump] Unknown auth payload type: %s", authPayload.getClass().getName());
            }

            // Also dump the client data JWT
            if (clientDataJwt != null) {
                String[] parts = clientDataJwt.split("\\.");
                logger.debug("[EduChainDump] --- Client Data JWT (parts: %s) ---", parts.length);
                if (parts.length >= 2) {
                    String header = new String(Base64.getUrlDecoder().decode(EducationAuthManager.padBase64(parts[0])));
                    String payload = new String(Base64.getUrlDecoder().decode(EducationAuthManager.padBase64(parts[1])));
                    logger.debug("[EduChainDump]   Header:  %s", header);
                    // Client data payloads can exceed 10KB (skin data); truncate to keep logs readable
                    if (payload.length() > 2000) {
                        logger.debug("[EduChainDump]   Payload (truncated): %s...", payload.substring(0, 2000));
                    } else {
                        logger.debug("[EduChainDump]   Payload: %s", payload);
                    }
                }
            }

            logger.debug("[EduChainDump] ========== END CHAIN DUMP ==========");
        } catch (Exception e) {
            logger.warning("[EduChainDump] Failed to dump education chain: " + e.getMessage());
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
}
