# EduGeyser Skin System — Final Implementation Plan

## Current State

Education Edition players connecting through EduGeyser appear as default Steve/Alex skins to all other players (Java, Bedrock, and other Education players). Regular Bedrock players' skins work correctly through the existing Geyser pipeline.

---

## Problem 1: Local Bedrock Skin Cache Not Populated

### Root Cause

`JavaLoginFinishedTranslator.java` line 60:

```java
if (remoteAuthType == AuthType.OFFLINE || playerEntity.uuid().getMostSignificantBits() == 0) {
    SkinManager.handleBedrockSkin(playerEntity, session.getClientData());
}
```

Education UUIDs have MSB `0x0000000100000001`. This check only passes for regular Bedrock UUIDs (MSB `0x0000000000000000`). `handleBedrockSkin` never runs for education players, so their raw skin bytes from `BedrockClientData` (skin data, cape data, geometry) are never stored in `SkinProvider`'s local caches.

### Impact

Other Bedrock clients on the same Geyser instance cannot see education player skins. Falls through to default Steve/Alex.

### Fix

```java
if (remoteAuthType == AuthType.OFFLINE
        || playerEntity.uuid().getMostSignificantBits() == 0
        || playerEntity.uuid().getMostSignificantBits() == 0x0000000100000001L) {
    SkinManager.handleBedrockSkin(playerEntity, session.getClientData());
}
```

One additional condition in one if-statement. No chain validation is involved in this path — `handleBedrockSkin` caches the raw skin bytes directly from `BedrockClientData` into `SkinProvider`'s in-memory maps. The local Bedrock cache trusts whatever Geyser already accepted at login.

This single fix resolves Bedrock→Bedrock and Education→Education skin visibility independently of everything else.

---

## Problem 2: Java Players Cannot See Education Skins

### How Normal Bedrock→Java Skins Work

1. `FloodgateSkinUploader` connects to GeyserMC's global API via WebSocket at `wss://api.geysermc.org/ws`
2. On every Bedrock player join, `uploadSkin()` sends the player's `chain_data` + `client_data` JWT
3. The global API's Rust NIF (`chain_validator.rs`) validates the chain starting from Mojang's hardcoded public key (`MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE...`)
4. After validation, the skin bytes are extracted, converted to standard 64×64 RGBA, and hashed (`SHA256` of the converted RGBA bytes)
5. The hash is checked against the global API's database for deduplication (in-memory cache → database → MineSkin upload as last resort)
6. The signed textures are broadcast back via WebSocket as a `SKIN_UPLOADED` event containing the xuid, hash, value, and signature
7. Geyser matches the event to the player session by xuid and sends the signed textures to the backend via Floodgate plugin message

### Why Education Skins Fail

Education players' JWT chains are self-signed with ephemeral client-generated ECDSA keys. There is no Mojang root key in the chain. The global API's `validate_chain` function starts verification from `MOJANG_PUBLIC_KEY` — the first JWT fails signature verification, and the function returns `None`. The WebSocket handler receives `:invalid_data` and closes the connection with "invalid chain and/or client data."

There is also a newer `validate_token` path (for Geyser 1.21.120+) that validates against Microsoft's JWKS endpoint with audience `api://auth-minecraft-services/multiplayer`. Education tokens do not match this audience either.

### Why This Cannot Be Fixed Locally

Java Edition requires skins signed by Mojang's texture servers. Getting a skin signed requires a Mojang account — that's why MineSkin exists. The only way to make it fully local per-instance would be requiring each server operator to configure their own Xbox/Mojang account credentials, which is terrible UX and a security concern. Modifying the GeyserMC global API to accept education chains is not something we control.

---

## Solution: Signing Relay API

### Architecture

```
Education client → EduGeyser → Signing Relay (POST /sign)
                                     ↓
                              Re-signed JWT + chain + hash
                                     ↓
                  EduGeyser → Global API WebSocket (existing flow)
                                     ↓
                              SKIN_UPLOADED event (matched by hash)
```

A lightweight Rust HTTP service that re-signs education skin data using a legitimate Xbox/Mojang-authenticated key pair. EduGeyser submits education client data to this API and receives back a validly signed JWT that the existing global API will accept.

### Why Rust

The global API's skin conversion pipeline is written in Rust (`global_api/native/skins/src/`). The signing relay reuses this code directly — no porting to another language, no risk of hash mismatch. The refactor is stripping `rustler` (Erlang NIF) bindings and wiring the logic to an HTTP endpoint.

### Signing Relay

**Single endpoint:** `POST /sign`

- **Input:** education `client_data` JWT
- **Output:** `chain_data` (Mojang-signed chain), `client_data` (re-signed JWT), `hash` (hex SHA256 of converted RGBA), `is_steve` (boolean)

**Account management** via CLI:
```
./edugeyser-signing-relay add-account       # Device code flow, saves to accounts.json
./edugeyser-signing-relay list-accounts     # Show configured accounts
./edugeyser-signing-relay remove-account 0  # Remove by index
./edugeyser-signing-relay serve             # Start the HTTP server
```

One account is sufficient. The signing is local P-384 ECDSA crypto — no Microsoft API call per request. The only external call is the chain refresh every 30 minutes. A second account can be added as a backup; the pool automatically skips unhealthy accounts.

**Health endpoint:** `GET /health` returns account pool status (`ok`/`degraded`/`unhealthy`). Point any uptime monitor at it for alerting.

**On each request:**
1. Decode the education `client_data` JWT payload (base64, ignore signature — it's self-signed)
2. Run the skin conversion pipeline (ported from global API):
   - `collect_skin_info()` — extract SkinData, dimensions, geometry
   - `convert_skin()` — geometry remapping, scaling to 64×64 if needed
   - `clear_unused_pixels()` — zero out unused regions (Steve vs Alex model)
   - `encode_image()` — PNG encode + `SHA256(raw_rgba_bytes)` = hash
3. Sign a new `client_data` JWT with the Xbox account's P-384 private key (ES384)
4. Return the signed JWT, the account's Mojang-signed chain, and the hash

**The hash computed by the relay is byte-identical to the global API's hash** because it runs the same Rust code. This is the critical property that makes the `SKIN_UPLOADED` callback matching work.

### Xbox Authentication Flow (per account)

Same flow Geyser uses for online-mode auth:
1. Generate ECDSA P-384 key pair
2. OAuth2 device code flow → Microsoft access token
3. Exchange for Xbox Live user token via `user.auth.xboxlive.com`
4. Exchange for XSTS token via `xsts.auth.xboxlive.com`
5. POST public key to Mojang auth endpoint → Mojang-signed chain

The chain terminates at this account's public key. The corresponding private key signs `client_data` JWTs. The global API validates: Mojang root → chain → account public key → client_data signature ✓

### Xbox Account Requirements

Any free Microsoft account that has logged into Minecraft once. The account needs a valid Bedrock/Xbox profile to obtain a Mojang-signed chain. One account handles all education skins across all EduGeyser instances.

---

## EduGeyser Changes

### File 1: `JavaLoginFinishedTranslator.java` — one line

Add education UUID check to the MSB condition on line 60:

```java
if (remoteAuthType == AuthType.OFFLINE
        || playerEntity.uuid().getMostSignificantBits() == 0
        || playerEntity.uuid().getMostSignificantBits() == 0x0000000100000001L) {
    SkinManager.handleBedrockSkin(playerEntity, session.getClientData());
}
```

### File 2: `GeyserSession.java` — one field

New field: `educationSkinHash` (String, nullable). Stores the SHA256 hash returned by the signing relay. Used for matching in the `SKIN_UPLOADED` callback. Lives and dies on `GeyserSession` — never crosses into the Floodgate `BedrockData` pipeline. `GeyserSession` already has 153 fields; this is invisible.

### File 3: `FloodgateSkinUploader.java` — two changes

**`uploadSkin()` method — education branch:**

Before the existing WebSocket send, check if the session is an education client. If so:
1. Copy `clientData.getOriginalString()` (must happen before line 75 nulls it)
2. POST `client_data` to the signing relay
3. Receive back: signed `client_data`, `chain_data`, `hash`
4. Store `hash` on `session.setEducationSkinHash(hash)`
5. Build the `chain_data` + `client_data` JSON using the relay's signed versions
6. Feed it into the existing WebSocket send path

Non-education sessions are completely untouched — the education branch is a prefix check that short-circuits.

**`onMessage()` SKIN_UPLOADED handler — hash fallback:**

After the existing `connectionByXuid(xuid)` lookup (line 131-132), add a fallback:

```java
case SKIN_UPLOADED:
    if (subscribersCount != 1) {
        break;
    }

    String xuid = node.get("xuid").getAsString();
    GeyserSession session = geyser.connectionByXuid(xuid);

    // Normal Bedrock path — xuid match, O(1)
    if (session != null) {
        // existing logic unchanged
        break;
    }

    // Education fallback — hash scan across education sessions only
    if (node.get("success").getAsBoolean()) {
        JsonObject data = node.getAsJsonObject("data");
        String skinHash = data.get("hash").getAsString();

        for (GeyserSession eduSession : geyser.onlineConnections()) {
            if (eduSession.isEducationClient()
                    && skinHash.equals(eduSession.getEducationSkinHash())) {
                String value = data.get("value").getAsString();
                String signature = data.get("signature").getAsString();
                byte[] bytes = (value + '\0' + signature)
                        .getBytes(StandardCharsets.UTF_8);
                PluginMessageUtils.sendMessage(eduSession,
                        PluginMessageChannels.SKIN, bytes);
            }
        }
    }
    break;
```

The xuid lookup runs first and short-circuits for all normal Bedrock players. The hash scan only triggers when xuid lookup returns null, which only happens for education players (since the xuid in the response belongs to the relay's donor account, not the education player).

### File 4: Config addition

New config field: `education.skin-relay-url` (String), defaulting to the hosted relay instance. Allows self-hosting.

### Timing

Line 73-75 in `JavaLoginFinishedTranslator` clears `certChainData`, `token`, and `clientData.originalString` after `uploadSkin()` returns. The education branch in `uploadSkin()` needs `clientData.originalString` to send to the relay. Since `uploadSkin()` is called before the cleanup, the relay call just needs to capture the original string before returning. For async relay calls, copy the string into a local variable first.

---

## Edge Cases

**Two education players with the same skin join simultaneously:** Both sessions have the same hash. The `SKIN_UPLOADED` response matches both. The loop applies signed textures to all matching sessions. The global API deduplicates, so only one MineSkin upload happens.

**Education player disconnects before `SKIN_UPLOADED` returns:** `geyser.onlineConnections()` no longer includes that session. The hash scan doesn't find it. The signed textures are dropped silently — same behavior as normal Bedrock when a player disconnects mid-upload. The skin is cached in the global API by hash, so the next join with the same skin is an instant cache hit.

**Global API queue delay:** Education players see default Steve until their skin is processed. After the initial priming across all EduGeyser servers, most joins are instant cache hits since education skins are a limited set (preset skins are likely already in the system from regular Bedrock players).

**Signing relay downtime:** Education skins fall back to default Steve/Alex for Java players. No crash, no error propagation. The MSB fix (Problem 1) works independently, so Bedrock→Bedrock education skin visibility is unaffected. The `/health` endpoint returns `unhealthy` for alerting.

**Server restart:** Session hashes are in-memory only, lost on restart. Next education player joins trigger fresh signing relay calls and global API submissions. If the skin was previously uploaded, it's an instant cache hit — no queue delay.

---

## Hash Matching — How It Works End-to-End

The hash in the `SKIN_UPLOADED` WebSocket response (`data.hash`) is `Utils.hash_string(rgba_hash)` — a hex string of `SHA256(converted 64×64 RGBA bytes)`.

The conversion pipeline in the global API:
1. `skin_codec::collect_skin_info()` — extract skin bytes, dimensions, geometry
2. `converter::convert_skin()` — geometry remapping, scaling
3. `pixel_cleaner::clear_unused_pixels()` — zero unused regions
4. `skin_codec::encode_image()` → `hash = SHA256(raw_rgba_bytes after conversion and cleaning)`

The signing relay runs this identical Rust code (ported from `global_api/native/skins/src/`). The hash it returns to EduGeyser is byte-identical to what the global API computes from the re-signed JWT. EduGeyser stores it on `GeyserSession.educationSkinHash`. When `SKIN_UPLOADED` arrives, the hashes match.

For standard 64×64 skins (most education skins): no geometry conversion, just pixel cleaning, then `SHA256(cleaned_rgba)`. For non-standard skins: full bone-by-bone geometry remapping runs identically in both the relay and the global API.

---

## No Caching in the Relay

The global API already deduplicates by skin hash at three levels (in-memory cache, database, MineSkin upload). Adding a cache in the relay would be a redundant second layer solving a problem that doesn't exist. The relay is stateless per-request.

---

## Summary of Changes

**EduGeyser (your fork):**
1. MSB check fix in `JavaLoginFinishedTranslator.java` — one line
2. New field on `GeyserSession`: `educationSkinHash`
3. Modified `FloodgateSkinUploader.uploadSkin()` — education branch sends to signing relay first, stores hash
4. Modified `FloodgateSkinUploader.onMessage()` — hash-based fallback after xuid lookup fails
5. Config field for relay URL

**Signing Relay (new Rust service):**
1. Skin conversion + hashing (ported from global API's public Rust source)
2. Xbox Live authentication + chain maintenance (CLI-based account management)
3. JWT re-signing with Xbox account's P-384 key
4. Single stateless HTTP POST endpoint + health check

**Nothing changes in:**
- EduFloodgate
- Geyser's global API
- MineSkin
- Normal Bedrock skin flow
- Server operator configuration (beyond setting the relay URL)
