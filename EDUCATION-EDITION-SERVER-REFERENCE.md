# Minecraft Education Edition Server Infrastructure: Technical Reference

Reference document for anyone building server-side infrastructure that interacts with Minecraft Education Edition clients. Covers the MESS authentication system, Discovery API, Nethernet signaling, client verification behavior, and library quirks that are not documented elsewhere. Focused on protocol and runtime facts — not code review or changelogs.

All findings are from observation of real client/server traffic and reverse engineering of the Education Edition client binary (`libminecraftpe.so`, ARM64, via Ghidra).

---

## Table of Contents

1. [MESS Token Format](#1-mess-token-format)
2. [MESS Signing Keys](#2-mess-signing-keys)
3. [Client-Side Token Verification (Binary RE)](#3-client-side-token-verification-binary-re)
4. [Token Echo: How Servers Authenticate to the Client](#4-token-echo-how-servers-authenticate-to-the-client)
5. [Education Client Login Structure](#5-education-client-login-structure)
6. [Entra OID Properties](#6-entra-oid-properties)
7. [Discovery API](#7-discovery-api)
8. [Nethernet Signaling Protocol](#8-nethernet-signaling-protocol)
9. [PlayFab / MCToken Acquisition](#9-playfab--mctoken-acquisition)
10. [Observed Token Lifetimes](#10-observed-token-lifetimes)
11. [Connection Methods and What They Actually Do](#11-connection-methods-and-what-they-actually-do)
12. [Kastle Library Quirks (NetherNet / WebRTC)](#12-kastle-library-quirks-nethernet--webrtc)
13. [Netty Classpath in Geyser Extensions](#13-netty-classpath-in-geyser-extensions)
14. [Nethernet Long-Running Failure Modes](#14-nethernet-long-running-failure-modes)
15. [Education-Specific Protocol Fields](#15-education-specific-protocol-fields)
16. [Open Questions](#16-open-questions)

---

## 1. MESS Token Format

MESS-signed tokens are the root of trust for education authentication. They appear in two places with identical format:

- In the client's `EduTokenChain` (as the `chain` field of a JWT payload)
- In Discovery API `/host` responses (as the `serverToken` field)

### Format

Pipe-separated, ASCII:

```
<tenantId>|<oid>|<expiry>|<signatureHex>
```

| Field | Type | Notes |
|---|---|---|
| `tenantId` | UUID v4 string | The M365 Education tenant (school/district). |
| `oid` | UUID v4 string | Entra Object ID of the user. Unique per user per tenant. Immutable. |
| `expiry` | ISO 8601 UTC | E.g. `2026-04-24T15:47:30.452Z`. Subsecond precision (observed `.452Z`). |
| `signatureHex` | 256 hex characters | 128 bytes of RSA-1024 signature. |

### Signature scheme

- **Algorithm**: RSA PKCS#1 v1.5 with SHA-256
- **Key size**: 1024 bits
- **Signed data**: everything before the last `|`, i.e. `tenantId|oid|expiry` as raw UTF-8 bytes
- **Signature encoding**: lowercase hex, no padding, exactly 256 characters

Verification pseudocode:
```
lastPipe = token.lastIndexOf('|')
signedData = token[0 .. lastPipe]                  // "tenantId|oid|expiry"
sigBytes   = hex_decode(token[lastPipe+1 ..])     // 128 bytes
RSA_verify(MESS_SIGNING_KEY, SHA256, signedData.bytes(UTF-8), sigBytes)
```

### Real example

```
75535150-2dbb-4af5-9070-3fb6f6c8585c|bb1430cb-bdcf-48b0-bd66-4b58bbb0a9dd|2026-04-24T15:47:30.452Z|cdb6363df52a3fe85f71d179ca69f484d1b60471efe2513ca75040150f1453a5c5ea3f35f727fa8eaecf9f4e2d48992ff1d0d26aad75650df83be0c2b8480c332d77bdfcbe7dc436361a0d9bed3b3e08d9e356c9d78ca196747a3195b660b37b05d2a4e9aa1bb78f63218f2f551ae7a781788dcd87ff85bc224cb05e610b3209
```

### Properties

- **Valid signatures imply well-formed structure**: MESS only signs the `tenantId|oid|expiry` triple, so a valid signature cryptographically guarantees the token has at least 4 pipe-separated segments. An explicit segment count check before signature verification is redundant.
- **Only MESS can mint tokens**. The private key is not publicly available. We cannot forge tokens.
- **Tokens are tied to a specific user in a specific tenant**. There is no "wildcard" or cross-tenant token.

---

## 2. MESS Signing Keys

Microsoft publishes the MESS public key at:

```
https://dedicatedserver.minecrafteduservices.com/public_keys/signing
```

Returns an X.509-encoded RSA-1024 public key in base64. Current value (as of 2026-04):

```
MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDsFCr3nD8N3TJxJZ7Y4g1Z20Son+fUWTSd2f/XyIil2mGGGx/yjRj6l0ntbROsec8MZoaLsBG0nWm9/WhJcdXvJewbdd+mCyy7WXyYQgJcJPZP3kgBDySZMUnaowlUmR9gxRr+LevCafZKQwb19nwJB0EUt+nQsWBbTe2SuIdCqQIDAQAB
```

Implementations should either fetch this at startup (if you want to survive key rotation automatically) or hardcode it (simpler, but requires redeployment if Microsoft rotates). The Education client itself fetches it at startup via `MessPublicKeyManager::requestPublicKeys(PublicKeysEndpoint::SIGNING)`.

### Separate ENCRYPTION endpoint

The client also fetches a second key from:

```
https://dedicatedserver.minecrafteduservices.com/public_keys/encryption
```

This is a different key from the signing one. Purpose is unclear — it appears to be used in encryption paths (possibly key exchange with MESS itself), not for verifying servertokens. No server-side implementation we've built needs to interact with this endpoint.

---

## 3. Client-Side Token Verification (Binary RE)

The Education client verifies MESS-signed tokens during its initial handshake with the server. Reverse-engineered from ARM64 Linux binary (`libminecraftpe.so`).

### Address translation

Ghidra adds a `0x100000` image base to all addresses. When cross-referencing file offsets to Ghidra locations, add `0x100000`.

### Main verification function

Located at file offset `0x0a244a88` (Ghidra: `0x0a344a88`). Called from the handshake handler twice — once for each of two JWT chains stored at offsets `0x50` and `0x90` on the session object. The function uses whatever key type the parsed JWT's `x5u` header contains (RSA or EC).

Related functions observed during RE:

| Ghidra name | Purpose |
|---|---|
| `FUN_10233ea0` / `FUN_10113be0` | Inner token verifier (called from main handshake) |
| `FUN_10c59fd0` | ISO 8601 date string parser |
| `FUN_0a377338` at `0a3452d8` | Combines results from the two verification calls |
| `FUN_0fb41b9c` | JWT parser; extracts key from `x5u` header |
| String compare at `0a345594` / `0a34559c` | Tenant ID match check |
| Disconnect at `0a345684` | Error code `0x2f` on verification failure |

### Expiry check logic

Decompiled snippet from the verification function:

```c
tVar11 = time((time_t *)0x0);                          // current Unix timestamp (seconds)
uVar12 = FUN_10c59fd0(&local_120, &local_78, 0, 0);    // parse 3rd segment (ISO 8601 expiry)
lVar2 = local_78;
if ((uVar12 & 1) == 0) {
    lVar2 = 0;                                         // parse failed → treat as epoch (0)
}
if (lVar2 < tVar11) {
    iVar14 = 3;                                        // EXPIRED: try next chain entry
} else {
    iVar14 = 1;                                        // VALID: write 4 segments, status = 1
}
```

Properties:

- **Simple less-than**. `parsed_expiry < current_time` → expired. No grace period, no clock skew tolerance.
- **Failed parse = epoch**. Malformed dates always compare as expired. Date string compatibility is forgiving in practice — the client's parser is liberal.
- **No "not yet valid" check**. Only an upper bound. Future-issued tokens are always accepted regardless of issuance time.
- **Signature verified first, then expiry**. Expired-but-signed tokens return `iVar14 = 3`; bad-signature tokens return a different value.
- **`iVar14 = 3` triggers chain fallback**. The client tries the next JWT in the chain. This is why the client's JWT chain may contain multiple tokens — fallback on per-token expiry.
- **Client clock is what matters**. A student device with wrong time can fail verification with no server-side indicator.

### Verification flow

1. Parse outer JWT from chain (signed with whatever key is in its `x5u` header)
2. Extract `signedToken` claim from payload
3. Split on `|` to get `[tenantId, oid, expiry, signatureHex]`
4. Hex-decode signature
5. Verify RSA signature against MESS public key over `tenantId|oid|expiry` UTF-8 bytes
6. Parse `expiry` into `time_t`; compare to `time(NULL)`
7. Check tenantId in signed token matches the client's own tenant context (prevents a server from echoing a token from a different tenant)
8. If all checks pass, accept the handshake

The tenant ID match check at step 7 is why join codes are tenant-scoped — if a student from tenant A connects to a server echoing a token from tenant B, the client disconnects.

---

## 4. Token Echo: How Servers Authenticate to the Client

The non-obvious core insight of building an education server: **servers do not need to mint their own MESS tokens**.

### The problem

The Education client verifies the server with the same algorithm above. The server-to-client handshake JWT must carry a `signedToken` claim containing a MESS-signed token matching the client's tenant. Without MESS's private key, a server cannot produce such a token.

### The solution

The client **already sent a valid token** in its `EduTokenChain` during login. That token is tenant-scoped (to the client's own tenant), signed by MESS, and fresh. The server:

1. Extracts the `chain` field from the client's `EduTokenChain` JWT payload
2. Verifies the MESS signature on it (rejecting forgeries from malicious clients)
3. Stores it
4. Places it back into the server-to-client handshake JWT as `signedToken`

The client verifies its own token against its own tenant, expiry is fine (the client just minted/received it), signature is valid (MESS signed it). Handshake accepted.

### Handshake JWT structure

Server-to-client handshake packet contains a JWT signed with `ES384` using a server-generated EC P-384 key pair. Required claims:

- `salt` — base64-encoded random bytes (for the normal encryption handshake)
- `signedToken` — the MESS-signed token echoed back

Required header:
- `x5u` — base64-encoded DER of the server's EC P-384 public key
- `alg` — `ES384`

### Security implications

- The echo scheme means server authenticity is **transitively** derived from the client's own MESS-issued token. A server does not prove "I am authorized"; it proves "I received and echoed back your own valid token."
- Microsoft's design assumes no legitimate case where a third-party server relays the client's token back to it. Our Java-bridging use case is an unintended path that works because of this assumption.
- A server MUST verify the MESS signature on the received token before echoing. Otherwise a malicious client could supply a forged token, receive it echoed back, and use that as evidence the server accepts forged tokens for anyone downstream.

---

## 5. Education Client Login Structure

Education clients follow the standard Bedrock login flow but with additional fields. The full nested structure from outermost to innermost:

### Layer 1: Login chain

JSON array of JWT strings. For Education:
- **Self-signed** with an ephemeral EC P-384 key generated per session
- No Mojang root of trust, no Xbox Live involvement
- Standard chain validators report `signed=false`

This means: everything inside the chain is under client control at this layer. Any server-side ID claim (XUID, username, display name) from the chain alone is untrusted.

### Layer 2: BedrockClientData JWT

Signed by the last identity key in the chain. Payload contains standard Bedrock fields plus education-specific ones:

```json
{
  "GameVersion": "1.21.132",
  "DeviceOS": 1,
  "LanguageCode": "en_US",
  "ServerAddress": "play.example.com:19132",
  "ThirdPartyName": "Niels",

  "isEduMode": true,
  "tenantId": null,
  "eduJoinerToHostNonce": "...",
  "eduSessionToken": "...",
  "EduTokenChain": "eyJhbGci...",
  "adRole": 0
}
```

Field specifics:

| Field | Notes |
|---|---|
| `isEduMode` | **Self-reported, not cryptographically verified**. Cannot be used alone for auth decisions. |
| `tenantId` | Always `null` in observed edu clients. Do not use this field for tenant detection. |
| `eduJoinerToHostNonce` | Nonce from MESS (purpose: some tenant-owner verification we don't use). Present but unused in our flow. |
| `eduSessionToken` | Session token from MESS. Present but unused. |
| `EduTokenChain` | Another nested JWT. **This is the important field** — contains the MESS-signed servertoken. |
| `adRole` | 0 = student, 1 = teacher. Values outside this range observed in some unsigned/modified clients. |

### Layer 3: EduTokenChain JWT

A separate JWT with its own header, payload, and signature. Real captured payload:

```json
{
  "chain": "4cf5151d-0705-4be5-839d-fa2abe1b4206|3606e21e-7fc0-4352-bb64-78647e9954c8|2026-04-20T17:26:48.327Z|12b65e9af67c815c3f21f998b6d1ed95de167dc29bf29e5872baec6680fe23f7b9bcb4510994123eba302a7a853281d41cc22dc82b69f6fcbd02c7da56eedb61951e95147401f06f0b3738153805fc8877ff24520781f322bfe44cae9d549a9315ef55ea0f176e8619f52d3250a770c6eacf7a2abca5c9d6c44c73c33484b84b",
  "exp": 1775842088
}
```

- `chain` — the MESS-signed servertoken in the format from Section 1. **Only field we consume.**
- `exp` — JWT-level Unix seconds expiry for the outer wrapper. Separate from the MESS-signed expiry embedded inside `chain`. We don't need to verify this; the inner expiry is what matters.

### Extraction path

```
LoginPacket
  → AuthPayload chain
    → parse last JWT in chain → payload JSON
      → BedrockClientData
        → EduTokenChain field (string, is itself a JWT)
          → parse EduTokenChain JWT → payload JSON
            → chain field (string)
              → split on "|"
                → [tenantId, oid, expiry, signatureHex]
```

### Non-education detection signal

In observed traffic, `isEduMode: true` + presence of `EduTokenChain` is the only reliable signal. `ThirdPartyName` is often populated for education but not exclusively. `DeviceOS` varies (Windows, iPad, Chromebook, Android all observed).

---

## 6. Entra OID Properties

The Entra Object ID (field `oid` in both MESS tokens and access token JWTs) is a critical identity primitive.

### Format

- Standard **UUID v4** per RFC 4122
- 128 bits, 122 random, 6 fixed:
  - Bits 48-51: version = `0100` (version 4)
  - Bits 64-65: variant = `10`
- String form: `xxxxxxxx-xxxx-4xxx-[89ab]xxx-xxxxxxxxxxxx` (case-insensitive hex)

### Uniqueness and stability

- **Generated once when the Entra account is created**, never changes during the account's lifetime
- **Unique within a tenant** by Microsoft's design
- **Different OIDs across tenants for the same physical user**: if a person has accounts at two schools, they have two distinct OIDs
- Microsoft does not reuse OIDs when accounts are deleted

### Properties useful for server implementations

- Can be used as a stable per-user identifier without coordinating with Microsoft (it's cryptographically delivered in the MESS token)
- Can be used as input to local hash functions or UUID constructions for backend player identity
- Cannot be used cross-tenant: teacher A@schoolX and teacher A@schoolY with the same display name have different OIDs and are correctly treated as different users

### Generating a 64-bit identifier from an OID

If you need a 64-bit integer or the LSB of a 128-bit UUID derived from an OID, take 64 purely random bits by skipping the 6 fixed positions. Reading left-to-right:

```
Bit 0-47    → 48 random bits   (before version nibble)
Bit 48-51   → SKIP (version = 0100)
Bit 52-63   → 12 random bits   (after version, end of MSB half)
Bit 64-65   → SKIP (variant = 10)
Bit 66-69   → 4 random bits    (first 4 bits after variant)
Total: 48 + 12 + 4 = 64 random bits.
```

Example implementation:
```java
UUID parsed = UUID.fromString(oid);
long msb = parsed.getMostSignificantBits();
long lsb = parsed.getLeastSignificantBits();

long upper = ((msb >>> 16) << 12) | (msb & 0xFFF);  // 48 + 12 = 60 random bits
long lower = (lsb << 2) >>> 60;                       // 4 random bits
long result = (upper << 4) | lower;                   // 64 random bits
```

### Collision probability

Birthday paradox on 64 random bits yields 50% collision at ~2^32 entries (~4.3 billion). Microsoft reports 200 million+ M365 A3/A5 Education licenses (2026). For any realistic server deployment, collision probability is effectively zero.

---

## 7. Discovery API

Discovery is Microsoft's registration service for Nethernet-hosted games. It maps join codes to nethernet IDs.

### Base URL

```
https://discovery.minecrafteduservices.com
```

### Required headers on ALL requests

```
api-version: 2.0
User-Agent: libhttpclient/1.0.0.0
Content-Type: application/json
```

Missing `api-version: 2.0` returns 400 Bad Request. The client itself sends this header.

### Endpoints

| Endpoint | Method | Auth | Returns |
|---|---|---|---|
| `/host` | POST | `Authorization: Bearer <MS access token>` | `{serverToken, passcode}` |
| `/heartbeat` | POST | `Authorization: Bearer <serverToken>` | empty 200 |
| `/update` | POST | `Authorization: Bearer <serverToken>` | empty 200 |
| `/dehost` | POST | `Authorization: Bearer <serverToken>` | empty 200 |
| `/joininfo` | POST | `Authorization: Bearer <MS access token>` | `{connectionInfo.info.id, serverName, serverDetails}` |

### `/host` request

```json
{
  "build": 12110000,
  "locale": "en_US",
  "maxPlayers": 40,
  "networkId": "1234567890",
  "playerCount": 1,
  "protocolVersion": 1,
  "serverDetails": "host username",
  "serverName": "world name",
  "transportType": 2
}
```

Returns:
```json
{
  "serverToken": "tenantId|oid|expiry|sig",
  "passcode": "10,13,5,1,17"
}
```

The `serverToken` has the same format as Section 1 (MESS-signed) but is issued by Discovery specifically for this registration. It's used as the Bearer token for subsequent heartbeat/update/dehost calls.

### Passcode format

Comma-separated numeric indices into a symbol array:

```
0=Book      1=Balloon   2=Rail       3=Alex      4=Cookie    5=Fish
6=Agent     7=Cake      8=Pickaxe    9=WaterBucket  10=Steve  11=Apple
12=Carrot   13=Panda    14=Sign      15=Potion   16=Map      17=Llama
```

Observed lengths: 4, 5, 6 symbols per passcode. Client displays the corresponding icons in the join UI.

### Share link format

```
https://education.minecraft.net/joinworld/<base64_of_passcode_string>
```

Example: `passcode = "10,13,5,1,17"` → base64 `MTAsMTMsNSwxLDE3` → link `https://education.minecraft.net/joinworld/MTAsMTMsNSwxLDE3`.

The link uses standard base64 (not URL-safe base64). Clicking opens Education Edition via `minecraftedu://` protocol handler with the passcode pre-filled.

### Scoping behavior

- **Join codes are tenant-scoped.** `/joininfo` only returns the nethernet ID if the querying user is in the same tenant as the hosting account.
- **Nethernet IDs are not tenant-scoped.** A client connecting to the signaling server with a raw nethernet ID succeeds regardless of tenant.
- **Multiple accounts can `/host` with the same `networkId`.** Each receives its own `passcode` and `serverToken`. Discovery stores them as independent registrations. This is how one physical server can serve join codes for many tenants with one shared Nethernet endpoint.

### Heartbeat semantics

Call `/heartbeat` every 100 seconds (confirmed via `discovery.heartbeatFrequencyS` in the edutoken spec) to keep a registration alive. Missing heartbeats cause the join code to expire after some grace period (not precisely measured; appears to be a few minutes).

### Joincode length control

The edutoken response from MESS contains `joinCodeLength` which influences how long passcodes are. Not directly controllable by the server.

---

## 8. Nethernet Signaling Protocol

Nethernet is Microsoft's WebRTC-over-signaling layer for peer-to-peer game connections (also used for cross-network Realms/friends). Education clients use it for join codes.

### Signaling WebSocket URL

```
wss://signal.franchise.minecraft-services.net/ws/v1.0/signaling/<networkId>
```

Where `networkId` is a uint64 string. The server (our side) opens this connection to register as the host of that networkId. Clients later connect to the same URL with the same networkId to receive signal routing from the server.

### Required WebSocket handshake headers

```
Authorization: <mcToken>
User-Agent: libhttpclient/1.0.0.0
session-id: <UUID v4>
request-id: <UUID v4>
```

Where `mcToken` is the full string `MCToken <jwt>` (including the `MCToken ` prefix). Obtained via PlayFab + `session/start` (see Section 9).

### NetworkId format

- Specified as `uint64`
- Client generates 20-digit decimal IDs in the wild
- Server implementations may use any uint64 value; Microsoft's signaling server treats it as a routing key
- Collision risk: if two independent servers choose the same `networkId`, incoming client connections to that ID race between them unpredictably
- Minimum viable length depends on total server population — shorter IDs have higher collision risk

### Signal message format

Messages are JSON over TextWebSocketFrames. Examples:

**Outbound (server to signaling server)**:
```json
{"Type": 0}                               // ping/keepalive
{"Type": 1, "To": "<peer id>", "Message": "<signal data>"}  // relay signal
```

**Inbound (signaling server to us)**:
```json
{"Type": 0, ...}    // NOT_FOUND — peer lookup failed
{"Type": 1, "From": "<sender id>", "Message": "<signal data>"}  // routed signal
{"Type": 2, "Message": "<ICE creds JSON>"}   // credentials (TURN servers)
{"Type": 3, ...}    // ACCEPTED
{"Type": 4, ...}    // ACK
```

### Signal payload format (inside `Message`)

```
<TYPE> <connectionId> <data>
```

Types: `CONNECTREQUEST`, `CONNECTRESPONSE`, `CANDIDATEADD`, `CONNECTERROR`. `connectionId` is an unsigned 64-bit ID as string. `data` is an SDP offer/answer or ICE candidate string depending on type.

### Keepalive

Clients send `{"Type": 0}` every 5 seconds to keep the WebSocket alive through middlebox timeouts. Microsoft's server does not initiate pings; if the client stops sending, the connection eventually closes.

### ICE servers

The signaling server sends a `credentials` message (Type 2) with TURN server information during initial connection. Parse this JSON for `TurnAuthServers` (or lowercase `turnAuthServers`) array. Each entry has `Urls` (or `urls`), `Username`, `Password`/`Credential`. Use these in the WebRTC `RTCConfiguration.iceServers`.

---

## 9. PlayFab / MCToken Acquisition

The MCToken is required for Nethernet signaling authentication. Obtained anonymously — no user account needed.

### Step 1: PlayFab LoginWithCustomID

```
POST https://6955f.playfabapi.com/Client/LoginWithCustomID
```

PlayFab title ID `6955F` is the Minecraft Education title.

Request:
```json
{
  "CreateAccount": null,
  "CustomId": "MCPF<random 32 hex chars>",
  "EncryptedRequest": null,
  "InfoRequestParameters": {
    "GetPlayerProfile": true,
    "GetUserAccountInfo": true,
    ...
  },
  "PlayerSecret": null,
  "TitleId": "6955F"
}
```

Generate a fresh `CustomId` per session (format convention: `MCPF` + 32 random alphanumerics). On first use of a CustomId, set `CreateAccount: true`. PlayFab returns:

- `SessionTicket` — used in the next step
- `EntityToken`, `PlayFabId`, `TitlePlayerAccount.Id` — not needed for our flow

### Step 2: session/start

```
POST https://authorization.franchise.minecraft-services.net/api/v1.0/session/start
Headers:
  User-Agent: libhttpclient/1.0.0.0   (REQUIRED — request fails without this)
```

Request:
```json
{
  "device": {
    "applicationType": "MinecraftPE",
    "capabilities": null,
    "gameVersion": "1.21.10",
    "id": "<random 32-char lowercase hex>",
    "memory": "2147483647",
    "platform": "Win32",
    "playFabTitleId": "6955F",
    "storePlatform": "uwp.store",
    "treatmentOverrides": null,
    "type": "Win32"
  },
  "user": {
    "language": "en",
    "languageCode": "en-US",
    "regionCode": "US",
    "token": "<SessionTicket from step 1>",
    "tokenType": "PlayFab"
  }
}
```

Response:
```json
{
  "result": {
    "authorizationHeader": "MCToken <jwt>",
    "validUntil": "UTCTimestamp",
    "issuedAt": "UTCTimestamp",
    "treatments": [...],
    ...
  }
}
```

The `authorizationHeader` is the MCToken (including the `MCToken ` prefix). Use this as the `Authorization` header for the Nethernet signaling WebSocket handshake.

### Notes

- The flow is **anonymous** — no Microsoft account, no user identity. Any server can obtain a fresh MCToken this way.
- **Device ID stability**: the mcedu-docs suggest using the same 32-char device ID across sessions. In practice, rotation has not caused failures.
- **`validUntil`**: the response includes an expiry. Observed tokens remain valid long enough that proactive refresh every 30 minutes is more than sufficient. Precise lifetime not empirically measured.

---

## 10. Observed Token Lifetimes

Summary of tokens involved in a full education flow, with empirically observed or documented lifetimes:

| Token | Issuer | Purpose | Lifetime |
|---|---|---|---|
| MS Entra access token | Microsoft Entra | Bearer for Discovery `/host`, `/joininfo` | ~76 minutes (observed: `exp - iat = 4591s`) |
| MS Entra refresh token | Microsoft Entra | Refresh access tokens | Days to weeks |
| MESS servertoken (from Discovery `/host`) | MESS | Bearer for heartbeat/update/dehost | ~10 days (observed `validUntil` 10 days after issuance) |
| MESS servertoken (inside client's EduTokenChain) | MESS | Server-to-client handshake echo | Observed with ~10 day `expiry` segment |
| PlayFab SessionTicket | PlayFab | Input to `session/start` | Unknown; anonymous, cheap to refresh |
| MCToken | `session/start` | Nethernet signaling WebSocket auth | Unknown; `validUntil` field present in response but never precisely measured |

### Structure of MS access token

MS access tokens are standard JWTs. Decoded payload (real example, anonymized):

```json
{
  "aud": "16556bfc-5102-43c9-a82a-3ea5e4810689",
  "iss": "https://sts.windows.net/<tenantId>/",
  "iat": 1776181348,
  "exp": 1776185939,
  "appid": "b36b1432-1a1c-4c82-9b76-24de1cab42f2",
  "name": "Niels Imfeld",
  "oid": "<user OID>",
  "tid": "<tenant ID>",
  "upn": "nimfeld@mc7x.onmicrosoft.com",
  "unique_name": "nimfeld@mc7x.onmicrosoft.com",
  "ver": "1.0"
}
```

Useful claims:
- `tid` — tenant ID (matches the `tenantId` in MESS servertoken from same user)
- `oid` — user Object ID (matches MESS token `oid`)
- `upn` / `preferred_username` — user email — useful for UI/logs
- `appid` — the OAuth client ID used (values below)

### Education OAuth client

For device-code / interactive OAuth flows against Microsoft Entra to obtain a token usable with MESS/Discovery:

- **Client ID**: `b36b1432-1a1c-4c82-9b76-24de1cab42f2` (Education Client)
- **Scope (v2 OAuth)**: `16556bfc-5102-43c9-a82a-3ea5e4810689/.default offline_access`
- **Resource (v1 OAuth)**: `https://meeservices.minecraft.net`

For server-management operations (Dedicated Server registration via MESS tooling API):

- **Client ID**: `1c91b067-6806-44a5-8d2d-3137e625f5b8` (Tooling Client)
- Same scope

Both clients are pre-registered by Microsoft. Any Microsoft user can consent to these apps without Azure AD admin approval — the device code flow "just works" against any M365 Education tenant.

---

## 11. Connection Methods and What They Actually Do

Education Edition has multiple ways for students to initiate joining a server. They follow different paths but can all converge on the same backend.

### Method A: Direct IP connect

Via `minecraftedu://connect/?serverUrl=IP:19132` share link, or via adding a server entry through a server list resource pack UI.

- Client opens RakNet connection directly to `IP:19132`
- Sends login chain including `isEduMode=true` + `EduTokenChain`
- Server verifies MESS token, authenticates
- No Nethernet involvement

### Method B: Join code

Student enters symbols or clicks `https://education.minecraft.net/joinworld/...`.

1. Client posts `/joininfo` to Discovery with the passcode
2. Discovery returns the nethernet ID for that passcode (tenant-scoped lookup)
3. Client opens WebSocket to `wss://signal.franchise.minecraft-services.net/ws/v1.0/signaling/<id>`
4. WebRTC handshake through Microsoft's signaling brokered to the host server
5. Host server serves a minimal Bedrock handshake and sends `TransferPacket` to IP:port
6. Client reconnects to IP:port via RakNet (this reconnect is identical to Method A from here on)

### Method C: Connection ID (direct nethernet ID)

Student enters the raw 10+ digit nethernet ID in Education Edition's connection dialog.

- Skips Discovery entirely
- Client connects directly to `wss://signal.franchise.minecraft-services.net/ws/v1.0/signaling/<id>`
- Rest of the flow is identical to Method B from step 3 onward

### Why "URI method" and "resource pack method" are Method A, not join codes

The `minecraftedu://connect/?serverUrl=IP:port` URI and server list resource pack both configure the client to connect to a specific IP:port. These are **direct connects** (Method A), not Nethernet-mediated.

A common confusion: reports that "join codes work but URI/resource pack fails" usually indicate a direct-connect problem on the server, NOT a Nethernet problem. Debug at the RakNet/Geyser layer, not the signaling layer.

### Cross-tenant implications

- Method A works cross-tenant (the server verifies whatever MESS token the client provides)
- Method B is tenant-scoped (Discovery's `/joininfo` filters by tenant)
- Method C works cross-tenant (signaling server routes by nethernet ID only, with no tenant check)

This is why a single server can offer "cross-tenant access" by exposing its raw connection ID — bypassing the Discovery tenant filter.

---

## 12. Kastle Library Quirks (NetherNet / WebRTC)

The only publicly-available Java library for Nethernet is `dev.kastle.netty:netty-transport-nethernet` and its sister `dev.kastle.webrtc:webrtc-java`. Sources at `Kas-tle/NetworkCompatible`. Observed issues during long-running server use:

### Ping task exception fragility

In `NetherNetXboxSignaling.onConnected()`, version 1.6.1 and 1.7.0:

```java
ctx.executor().scheduleAtFixedRate(() -> {
    JsonObject ping = new JsonObject();
    ping.addProperty("Type", 0);
    ctx.writeAndFlush(new TextWebSocketFrame(gson.toJson(ping)));
}, 5, 5, TimeUnit.SECONDS);
```

**`ScheduledExecutorService.scheduleAtFixedRate` silently cancels the task on the first exception thrown by the runnable.** A single transient write failure (network blip, broken pipe, SSL exception during reconfiguration) permanently kills the 5-second keepalive. The WebSocket then dies to idle timeouts at middleboxes with no indication in logs.

Mitigation when using this library: wrap the runnable body in try/catch, or build your own keepalive on top of the library.

### PeerConnectionFactory ownership

`NetherNetServerChannel.doClose()` always calls `factory.dispose()`:

```java
protected void doClose() throws Exception {
    this.open = false;
    try {
        signaling.close();
    } finally {
        factory.dispose();
    }
}
```

Consequence: a `PeerConnectionFactory` passed into `NetherNetChannelFactory.server(factory, signaling)` cannot outlive the channel. Reusing the same factory across multiple channel lifecycles causes JNI crashes (`VM attach current thread failed`, `Object handle is null` NPEs in `createPeerConnection`).

If you need to restart the Nethernet channel (e.g. periodic signaling refresh), create a fresh `PeerConnectionFactory` each time.

### Private `channel` field (1.6.1)

Version 1.6.1's `NetherNetXboxSignaling` has a `private Channel channel` field that becomes null after `channelInactive()` fires. Without a public accessor or subclass-visible access, there's no clean way to check if the upstream WebSocket is still alive.

Version 1.7.0 refactored this into `AbstractNetherNetXboxSignaling` with `protected Channel channel`, making subclass access possible.

### Library lacks reconnection

Neither version attempts to reconnect the signaling WebSocket if it drops. `channelInactive()` sets `this.channel = null` and calls it a day. External code must detect deadness and rebuild.

### Netty native thread cleanup is async

Calling `shutdownGracefully()` on the Netty event loop groups returns immediately. Native WebRTC threads inside `PeerConnectionFactory.dispose()` also clean up asynchronously. Rapidly tearing down and rebuilding can race, producing the JNI NPE crashes above.

Mitigation: after closing the channel, wait for `shutdownGracefully(0, N, SECONDS).syncUninterruptibly()` to drain. Zero quiet period (nothing to drain), bounded timeout, block until complete.

### Signaling WebSocket liveness is unreliable to detect

Even with keepalive pings, the signaling connection can become half-closed (TCP FIN from some middlebox, our side doesn't notice, writes go into the void). `channel.isActive()` can still return true. Detecting this reliably requires either:

- Tracking time since last inbound frame (but the signaling server is quiet when idle, so silence doesn't guarantee death)
- Sending periodic probes that require a response (not supported by the signaling protocol)
- Periodic forced rebuilds regardless of apparent state (brute force but reliable)

In practice, a periodic forced rebuild (every 30+ minutes) combined with a basic `isActive()` check on a shorter interval is the most reliable approach.

---

## 13. Netty Classpath in Geyser Extensions

Geyser extensions interact with Bedrock protocol classes (from `org.cloudburstmc.protocol.*`) inside Netty channel pipelines. The Bedrock protocol classes are loaded from Geyser's classloader, unshaded, and they reference `io.netty.*` types. This constrains how extensions can bundle Netty.

### What doesn't work: shading `io.netty`

If you shade `io.netty.*` to a new package (e.g. `com.example.shaded.netty.*`), your pipeline classes (`ChannelPipeline`, `ChannelHandlerContext`, etc.) use the shaded types. But Bedrock protocol classes from Geyser's classpath reference the original `io.netty.*` types. Adding a Bedrock codec to your shaded pipeline crashes at runtime:

```
IncompatibleClassChangeError: Class BedrockPacketCodec_v3 does not implement
the requested interface com.example.shaded.netty.channel.ChannelHandler
```

Same class name (`ChannelHandler`), different classloaders, different types to the JVM.

### What doesn't work: selective exclusion with version pinning

Excluding core Netty modules while bundling `netty-codec-http` can hit version mismatches. If your bundled `netty-codec-http 4.2.x` references classes that only exist in `netty-transport 4.2.x`, but the host server provides `netty-transport 4.1.x`, you get:

```
NoClassDefFoundError: io/netty/util/LeakPresenceDetector
  at io.netty.handler.codec.http.HttpObjectEncoder.<clinit>(...)
```

`LeakPresenceDetector` is a 4.2-only class referenced by `netty-codec-http` 4.2, absent from 4.1 transport.

### What works: bundle the full stack, don't exclude, don't shade

Pull in the whole Netty dependency tree at a consistent version matching what the library you're using requires. If `kastle 1.7.0` pins `io.netty:* = 4.1.130`, let your fat jar contain all those at `4.1.130`. The host's classloader will resolve Netty classes to whichever copy loads first — usually the host's, but the kastle-pulled copy is present as a fallback and is API-compatible with the host's in the 4.1 series (Netty maintains backward compat within a major).

This is how MCXboxBroadcast handles the same problem. It's not elegant but it's the path of least resistance.

### Takeaway

- Don't shade Netty in a Geyser extension
- Don't cherry-pick Netty modules with mismatched versions
- Bundle the full transitive Netty tree at one consistent version
- Let the host's classloader resolve the conflict

---

## 14. Nethernet Long-Running Failure Modes

Long-running (>8 hour) Nethernet hosting exhibits failure modes not apparent in short testing:

### "Zombie" registration

Symptom: Discovery `/joininfo` still returns the nethernet ID. Heartbeats continue to succeed. But incoming client connections time out during the signaling handshake.

Cause: the host's outbound WebSocket to `signal.franchise.minecraft-services.net` has silently died (middlebox timeout, TCP half-close, library ping task dead). Our Netty server channel still reports active (it's a separate LOCAL bind). Discovery has no visibility into signaling state; it only knows heartbeats arrive. From the client's perspective, the signaling server tries to relay `CONNECTREQUEST` to the host but gets no response.

Resolution: external liveness check on the signaling WebSocket + periodic forced rebuild.

### JNI crashes on rapid rebuild

Symptom: `NullPointerException: Object handle is null` in `PeerConnectionFactory.createPeerConnection` (native method).

Cause: `NetherNetServerChannel.doClose()` calls `factory.dispose()`. The native WebRTC layer has async cleanup. If an incoming `CONNECTREQUEST` arrives between dispose and the factory's native side finishing cleanup, the Java wrapper tries to call into a null native handle.

Resolution: create a fresh `PeerConnectionFactory` per rebuild. Do not attempt to reuse.

### Benign shutdown messages

On JVM shutdown, WebRTC's native threads may print:

```
VM attach current thread failed
Failed to attach thread 140481825597120
```

This is JNI error output from native threads trying to `AttachCurrentThread` after the JVM has already started shutting down. Harmless — cleanup happens regardless of whether attach succeeds. Expected behavior of any JNI library with an async native thread pool.

### Middlebox drops

Corporate networks / ISP NATs may drop idle long-lived TCP connections after various timeouts (commonly 60-300 seconds, but up to hours). The library's 5-second ping usually covers this, but:

- If the ping task dies (see kastle quirks), no more keepalive → drop
- Some middleboxes drop on exact uptime intervals regardless of traffic

No reliable workaround beyond periodic forced reconnection.

---

## 15. Education-Specific Protocol Fields

### StartGamePacket extra strings

The Education client expects **three additional string fields** at the end of `LevelSettings` in `StartGamePacket`, after `ownerId`. Without them, the client's deserializer reads into subsequent packet data and desyncs.

Names (observed, not Microsoft-documented):
- `educationReferrerId`
- `educationCreatorWorldId`
- `educationCreatorId`

All three can be empty strings (`""`). Serializer pseudocode:

```
super.writeLevelSettings(buffer, helper, packet);
helper.writeString(buffer, "");  // educationReferrerId
helper.writeString(buffer, "");  // educationCreatorWorldId
helper.writeString(buffer, "");  // educationCreatorId
```

### Block break action names

Education uses different action names in block break events than Bedrock:

| Bedrock | Education equivalent |
|---|---|
| `BLOCK_CONTINUE_DESTROY` | `CONTINUE_BREAK` |
| `ABORT_BREAK` | `STOP_BREAK` |

Server-side handlers need to accept both names as synonymous.

### Code Builder gamerule

Education clients check a `codebuilder` gamerule. When true, the "Code" button appears in the pause menu. Clicking it triggers a protocol feature Java servers can't support and causes illegal-packet disconnects. Send `codebuilder=false` as a game rule in `StartGamePacket` to suppress the button.

### Version observations

- Current stable: **1.21.132** (code-named "Copper, Collaborate & Compete", released 2025-10-01, support through 2026-02-17)
- Current preview: **1.21.131.1 Preview**
- Legacy branch: 1.21.93 (2025-10-30), 1.21.92, 1.21.91 "Chase the Clouds", 1.21.10 (2025-03-22)

Protocol version for 1.21.132 matches Bedrock protocol v898 family, but with the StartGamePacket difference above.

---

## 16. Open Questions

### Does Discovery `/update` accept a new `networkId`?

If yes: a server could change its nethernet ID without invalidating existing join codes (passcodes would survive the transition). If no: any nethernet ID change requires dehost + new host, producing a new passcode.

The observed `/update` body does not include `networkId`. Unclear if the API accepts it if sent.

### Exact MCToken lifetime

`validUntil` is returned in `session/start` responses but has not been stress-tested. The practical question: what's the minimum refresh frequency to avoid signaling failures? Our 30-minute rebuild cycle is safely below any reasonable lifetime, but precise bounds are unknown.

### Microsoft signaling server connection limits

Unknown if Microsoft enforces:
- Per-IP concurrent connection limits
- Per-networkId connection limits (multiple hosts claiming same ID)
- Per-MCToken connection rate limits
- Total connection lifetime caps

No limits observed in normal operation, but scaling behavior under heavy load hasn't been tested.

### EduTokenChain outer `exp` field

The outer JWT wrapping the MESS-signed `chain` has its own `exp` claim. Does the client verify this? Does it need to match or relate to the inner MESS expiry? Our server-side flow ignores it and only verifies the inner MESS signature's expiry — this works, but the outer `exp` semantics are unverified.

### `adRole` values beyond 0 and 1

Observed values: 0 (student), 1 (teacher). Possible but unconfirmed: admin, guest, parent. Values outside this range have been seen in unsigned/test clients.

### Cross-tenant behavior of direct nethernet ID connections

Empirically, Method C (direct nethernet ID entry) works cross-tenant. It's unclear whether Microsoft considers this intentional. If they add tenant matching at the signaling layer in a future update, the cross-tenant property would break.

### `eduSessionToken` and `eduJoinerToHostNonce` semantics

Both fields are present in BedrockClientData for education clients. Neither is needed for our auth flow. The nonce fields likely relate to "official" server-registration-based auth paths (where the server proves ownership of a tenant-registered dedicated server). Precise semantics unverified since we use the token-echo path instead.

### ENCRYPTION public key purpose

MESS exposes `/public_keys/encryption` alongside `/public_keys/signing`. The client fetches both at startup via `MessPublicKeyManager`. What uses the encryption key is unclear — possibly in a MESS-native encrypted channel we never interact with.

---

## External References

- [josef240/mcedu](https://github.com/josef240/mcedu) — Python implementation of the full auth chain and Discovery API
- [josef240/mcedu-docs](https://github.com/josef240/mcedu-docs) — Unofficial protocol documentation (startup, discovery, edutoken, nethernet)
- [Kas-tle/NetworkCompatible](https://github.com/Kas-tle/NetworkCompatible) — Kastle's Nethernet and WebRTC Java libraries
- [rtm516/MCXboxBroadcast](https://github.com/rtm516/MCXboxBroadcast) — Closest architectural reference for long-running Kastle-based signaling hosting
- [df-mc/nethernet-specs](https://github.com/df-mc/nethernet-specs) — Community protocol specs for Nethernet
- [Microsoft Entra ID token claims reference](https://learn.microsoft.com/en-us/entra/identity-platform/id-token-claims-reference)

---

*This document collects findings from reverse engineering and empirical observation of the Education Edition client, MESS services, Discovery API, and Nethernet signaling. It is intended as a reference for anyone building server-side infrastructure for Minecraft Education Edition.*
