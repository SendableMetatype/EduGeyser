# EduGeyser: Complete Technical Reference

## ⚠ Implementation details in this document are outdated

This document was written during initial development and has not been updated to reflect the current EduGeyser implementation. Details about specific code structure, class names, config fields, and authentication flows may be outdated. Use this as a general information source about the Education Edition protocol and MESS API, not as a guide to the current codebase. For setup instructions, see the [Setup Guide](SETUP-GUIDE.md).

---

## Connecting Minecraft Education Edition to Java Servers via Geyser

**Document Version:** 3.0 (Final Consolidated)
**Date:** March 15, 2026
**Status:** Working — Education Edition clients can connect to Java Edition servers through a modified Geyser proxy (fork of Geyser 2.9.4-SNAPSHOT, commit `2f1cb9b`).

> **This document captures every finding from the first known successful implementation of
> Minecraft Education Edition client support through GeyserMC (a Bedrock-to-Java translation
> proxy). None of this information has been publicly documented before. This is a consolidated
> master reference combining all development notes, implementation details, and debugging history.**

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Platform & Version Analysis](#2-platform--version-analysis)
3. [Protocol Analysis](#3-protocol-analysis)
4. [Connection Methods](#4-connection-methods)
5. [Client Detection (Per-Session)](#5-client-detection-per-session)
6. [Authentication & Token System](#6-authentication--token-system)
7. [Encryption Handshake](#7-encryption-handshake)
8. [The Core Fix: Education-Specific StartGamePacket Fields](#8-the-core-fix-education-specific-startgamepacket-fields)
9. [Codec Swap: Netty Pipeline Timing](#9-codec-swap-netty-pipeline-timing)
10. [Registry Transfer After Codec Swap](#10-registry-transfer-after-codec-swap)
11. [StartGamePacket Field Configuration](#11-startgamepacket-field-configuration)
12. [EducationSettingsPacket](#12-educationsettingspacket)
13. [Education Gamerules](#13-education-gamerules)
14. [Education-Specific Packet Types](#14-education-specific-packet-types)
15. [Block Breaking Differences](#15-block-breaking-differences)
16. [Skin Handling](#16-skin-handling)
17. [Movement Authority](#17-movement-authority)
18. [P2P Join Code System (WebRTC)](#18-p2p-join-code-system-webrtc)
19. [Dedicated Server System & Binary Analysis](#19-dedicated-server-system--binary-analysis)
20. [Microsoft 365 Tenant System](#20-microsoft-365-tenant-system)
21. [Packet Flow Analysis](#21-packet-flow-analysis)
22. [Client-Side Debugging (DLL Analysis)](#22-client-side-debugging-dll-analysis)
23. [Files Modified (Geyser Fork)](#23-files-modified-geyser-fork)
24. [Configuration](#24-configuration)
25. [Development Environment & Build](#25-development-environment--build)
26. [Debug Logging](#26-debug-logging)
27. [Packet Processing Order](#27-packet-processing-order)
28. [Companion Geyser Extension [OUTDATED]](#28-companion-geyser-extension-outdated)
29. [Debugging History: Eliminated Theories](#29-debugging-history-eliminated-theories)
30. [Full StartGamePacket Dump (Before Fix)](#30-full-startgamepacket-dump-before-fix)
31. [Common Mistakes & Pitfalls](#31-common-mistakes--pitfalls)
32. [Remaining Known Issues](#32-remaining-known-issues)
33. [Prior Art](#33-prior-art)
34. [API Endpoints & Services](#34-api-endpoints--services)
35. [Protocol Documentation References](#35-protocol-documentation-references)
36. [Gameplay Differences (Education vs Bedrock, March 2026)](#36-gameplay-differences-education-vs-bedrock-march-2026)
37. [Summary of Required Changes for Education Edition Support](#37-summary-of-required-changes-for-education-edition-support)

38. [MESS API Complete Reference](#38-mess-api-complete-reference)
39. [Nonce Verification System](#39-nonce-verification-system)
40. [Token Extractor Tool](#40-token-extractor-tool)
41. [Client Binary Reverse Engineering (Windows PE)](#41-client-binary-reverse-engineering-windows-pe)
42. [Education UI Architecture](#42-education-ui-architecture)
43. [Community Context, User Base & Demand](#43-community-context-user-base--demand)
44. [Geyser Maintainer History on Education Edition](#44-geyser-maintainer-history-on-education-edition)
45. [Education Azure AD Application Details](#45-education-azure-ad-application-details)
46. [OAuth2 Token Acquisition Challenges](#46-oauth2-token-acquisition-challenges)
47. [Legal & Policy Considerations](#47-legal--policy-considerations)
48. [Commercial License for Testing](#48-commercial-license-for-testing)
49. [Education Login Chain & Security Model](#49-education-login-chain--security-model)
50. [EduTokenChain Verification System](#50-edutokenchain-verification-system)
51. [EducationAuthManager (Dedicated Server Lifecycle)](#51-educationauthmanager-dedicated-server-lifecycle)
52. [EduCommand (In-Game Management)](#52-educommand-in-game-management)
53. [Floodgate Integration](#53-floodgate-integration)
54. [UUID Generation Scheme](#54-uuid-generation-scheme)
55. [Username Format & Collision Prevention](#55-username-format--collision-prevention)
56. [DLL Catalog (Client-Side Investigation)](#56-dll-catalog-client-side-investigation)
57. [Changes Tested and Reverted](#57-changes-tested-and-reverted)

**Appendices:**
- [Appendix A: Microsoft 365 Education Tenant System (Detailed)](#appendix-a-microsoft-365-education-tenant-system-detailed)
- [Appendix B: Education Edition File Locations](#appendix-b-education-edition-file-locations)
- [Appendix C: Binary Analysis Reference (Windows Client — Disconnect Investigation)](#appendix-c-binary-analysis-reference-windows-client--disconnect-investigation)
- [Appendix D: Geyser Build System](#appendix-d-geyser-build-system)
- [Appendix E: Session Lifecycle for Education Clients](#appendix-e-session-lifecycle-for-education-clients)
- [Appendix F: Geyser Internals (Netty/RakNet Architecture)](#appendix-f-geyser-internals-nettyraknet-architecture)
- [Appendix G: Windows PE Client Binary Reference](#appendix-g-windows-pe-client-binary-reference)
- [Appendix H: Complete minecraft:// URI Scheme List](#appendix-h-complete-minecraft-uri-scheme-list)
- [Appendix I: Education Edition Game Data Files](#appendix-i-education-edition-game-data-files)

---

## 1. Architecture Overview

```
Education Edition Client (protocol 898, edition MCEE)
    ↓ minecraftedu://connect/?serverUrl=ip:port
Geyser-Velocity (forked, with Education support)
    ↓ translates Bedrock ↔ Java
Velocity proxy (online-mode=false)
    ↓
Paper/Spigot backend servers
```

### Approach: Geyser Fork (not an extension, not a standalone proxy)

A Geyser extension was initially considered, but the required modifications are too deep:

- The encryption handshake must be modified to include a `signedToken` claim in the JWT.
- The StartGamePacket serializer must be replaced to append 3 extra fields.
- The codec must be swapped per-session, and codec helper registries must be re-applied after the swap.
- Block break handling must be patched for Education-specific action types.
- Client detection must happen during login JWT parsing.

None of these are possible through the Geyser Extension API (version 2.8.3-SNAPSHOT / 2.9.0). The extension API provides events like `SessionLoginEvent` and `GeyserBedrockPingEvent`, but does not expose codec manipulation, serializer replacement, or handshake JWT customization.

### Failed Extension Approach: Handshake Interception

An attempt was made to intercept the handshake via the extension rather than forking Geyser:

- `EduLoginEncryptionUtils.java` — modified copy of Geyser's encryption flow
- `EduPacketHandlerWrapper.java` — wrapped the original `UpstreamPacketHandler`, intercepted `LoginPacket`
- `HandshakeInterceptor.java` — installed wrapper via Netty pipeline manipulation
- `EduHandshakeUtils.java` — created JWT with `salt` + `signedToken`

**This approach failed.** The Netty interceptor never fired — the handler installation was not hooking into the pipeline correctly. The `LoginEncryptionUtils.startEncryptionHandshake` is a `private static` method and Java doesn't support runtime method replacement without bytecode manipulation.

**Lesson:** The fork approach was necessary because the Geyser extension API does not expose handshake-level hooks. Only the fork can modify the `ServerToClientHandshakePacket` JWT construction.

### Why the Fork Is Technically Necessary (Deep Dive)

The method `LoginEncryptionUtils.startEncryptionHandshake` is `private static`. Inside it, a `KeyPair` is created as a **local variable** that is used for two inseparable purposes:

```java
KeyPair serverKeyPair = EncryptionUtils.createKeyPair();
byte[] token = EncryptionUtils.generateRandomToken();

// 1. JWT is signed with serverKeyPair.getPrivate()
packet.setJwt(EncryptionUtils.createHandshakeJwt(serverKeyPair, token));
session.sendUpstreamPacketImmediately(packet);

// 2. Encryption key is derived from the SAME private key
SecretKey encryptionKey = EncryptionUtils.getSecretKey(
    serverKeyPair.getPrivate(), clientPublicKey, token);
session.getUpstream().getSession().enableEncryption(encryptionKey);
```

The `serverKeyPair` is used for both **signing the JWT** and **deriving the ECDH encryption key**. These two uses are inseparable. You cannot intercept the JWT after it's created and re-sign it with a different key, because the encryption key derivation must use the same key pair. The only way to add `signedToken` to the JWT is to modify the code that creates it.

### Components

- **Geyser Fork** — modifications to `LoginEncryptionUtils.java` for signedToken injection and per-client Education detection, `CodecProcessor.java` for Education serializer registration and codec factory, `GeyserSession.java` for Education StartGamePacket flags / codec swap / registry transfer / EducationSettingsPacket / gamerules, `BedrockClientData.java` for IsEduMode detection, `BlockBreakHandler.java` for Education block action types, `SkinManager.java` / `SkullSkinManager.java` for simplified skins, `GeyserConfig.java` for education-token config option.
- **EducationStartGameSerializer** — custom serializer extending `StartGameSerializer_v898` that writes three Education-specific fields missing from standard Bedrock.
- **Geyser Config** — `validate-bedrock-login: false`, `auth-type: offline`, `education-token: "<token>"`
- **Velocity Config** — `online-mode = false`

---

## 2. Platform & Version Analysis

### Education Edition Build System

Education Edition is a direct fork of Bedrock, built from the same source tree. This was confirmed through the debug information accessible on the home screen of the Education client.

**Education Edition Stable (target client):**
```
Version:          v1.21.132
Build:            41141490
Branch:           edu_r21_u13
SHA:              88d5dd39883ee2657aff5aeb50f65b0c
Protocol Version: 898
```

**Education Edition Preview (stale, not useful):**
```
Version:          v1.21.131.2
Build:            39941265 (OLDER than stable)
Branch:           edu_r21_u13
SHA:              af9dcd15acadd29a1d4e2d86ddd0219f
Protocol Version: 898
Renderer:         RENDERDRAGON D3D12+, D3D_SM6S
Platform:         Windows Desktop Build (Centennial)
GPU:              AMD Radeon(TM) 8060S Graphics
OS:               Windows 10.0.26200
Environment:      Production
```

**Bedrock 26.3 (latest consumer Bedrock):**
```
Version:          v26.3
Build:            41676402
Branch:           r/26_u0
SHA:              04071b68ba0b36d495650bcab8ec3c5d
Protocol Version: 924
```

**Branch naming convention:** `edu_r21_u13` means Education branch off release 21 (1.21), update 13. `r/26_u0` means release 26, update 0. Same build system, same naming convention, confirming shared codebase.

**Build number analysis:** Education stable (41.1M) and Bedrock 26.3 (41.7M) were compiled within a short window of each other. The Education Preview (39.9M) is OLDER than stable — it has not been updated since stable shipped and is effectively obsolete.

**Critical finding:** Both Education stable and preview are on protocol 898. The preview offers no advantage for development.

### Education Edition Content

Education 1.21.132 ("Copper, Collaboration & Compete", released February 17, 2026) includes content through Bedrock 1.21.130 (Mounts of Mayhem). This includes spears, nautiluses, nautilus armor, zombie horsemen, camel husks, parched, netherite horse armor.

### Education Update Cycle

Recent Education update history:

| Version | Release Date | Named Update |
|---------|-------------|--------------|
| v1.21.03 | July 23, 2024 | Cloud Update |
| v1.21.91 | July 22, 2025 | Chase the Clouds |
| v1.21.92 | August 20, 2025 | Bug fix |
| v1.21.132 | February 17, 2026 | Copper, Collaboration & Compete |

Plus numerous preview builds and point releases. The cadence has accelerated significantly compared to the ~18 month gap between the Cloud Update and prior releases. Whether this pace continues is unknown but promising.

This means once a working proxy is established, it will likely remain functional for a long time before Education updates break it — and if the faster cadence holds, Education may stay closer to current Bedrock.

### Education Edition User Base

- **35 million** students and teachers use Minecraft Education globally
- Licensed in **115 countries**
- Added over **1.2 million** new students in early 2025 alone
- **80%** of students report using coding to enhance their learning
- **85%** of teachers report improved student engagement

### Education-Exclusive Features

These exist only in Education and are irrelevant for Java server survival gameplay:

- **Chemistry:** Element constructor, lab table, compound creator, material reducer
- **Exclusive items:** Ice bombs, underwater TNT, underwater torches, balloons, glow sticks, sparklers, bleach, medicine, compounds
- **Exclusive blocks:** Allow, deny, border, chalkboard, camera
- **Tools:** NPCs for lesson design, camera + portfolio, Code Builder (MakeCode)
- **Settings:** Classroom mode, immutable world gamerule, `/ability` command

### Education Skin System

Education Edition does NOT have the full Bedrock Character Creator / Persona system. Education uses classic skins only — simple 64x64 or 128x128 PNG textures. The Character Creator has been listed as "Planned" for Education but has not shipped as of March 2026.

Standard Bedrock has two skin systems:
- **Classic skins:** Simple PNG texture on player model
- **Persona/Character Creator:** Modular system with interchangeable body parts (`persona_right_leg`, `persona_left_arm`, etc.), each with UUIDs, piece types, and pack IDs

This difference is accounted for in the skin simplification (see Section 16), though it was confirmed NOT to be a cause of the original disconnect issue.

### Block/Item Palettes

**Critical finding:** Education's block and item palettes are IDENTICAL to standard Bedrock's at the same protocol version. Verified by examining Geyser's resource files:

- `runtime_item_states.1_21_130.json` — already contains ALL Education entries: `element_0` through `element_118`, `compound`, `medicine`, `bleach`, `balloon`, `sparkler`, `glow_stick`, `ice_bomb`, `chemistry_table`, `compound_creator`, `lab_table`, `material_reducer`, `element_constructor`, `allow`, `deny`, `border_block`, `camera`, `chalkboard`, `colored_torch_*`, `underwater_torch`
- `block_palette.1_21_110.nbt` — shared across all protocol versions since 1.21.110, includes chemistry/education block entries

Education-specific content is in the standard Bedrock palette, just gated behind the `educationFeaturesEnabled` runtime flag. No palette translation or modification needed.

---

## 3. Protocol Analysis

### Protocol Compatibility

| Edition | Version | Protocol | Branch |
|---------|---------|----------|--------|
| Education stable | 1.21.132 | 898 | edu_r21_u13 |
| Education preview | 1.21.131.2 (stale) | 898 | edu_r21_u13 |
| Bedrock | 26.3 | 924 | r/26_u0 |
| Geyser target | 26.x | 924 | — |

**No protocol translation is needed.** Geyser natively supports Bedrock 1.21.111 through 26.3, which explicitly includes 1.21.130-1.21.132. Protocol 898 is already handled in Geyser's `GameProtocol.java` with a supported codec listed as `v898`.

Education Edition 1.21.132 is built from the same source tree as standard Bedrock (`edu_r21_u13` — branch off r21, update 13). Block/item palettes are identical — Education entries (elements, compounds, chemistry blocks, allow/deny/border) are already present in standard Bedrock's palette files, just gated behind the `educationFeaturesEnabled` flag at runtime.

### CloudburstMC Protocol Version Mapping

Protocol 898 maps to Minecraft version string `"1.21.130"` in CloudburstMC's `Bedrock_v898.java`. This is the canonical version string, not `"1.21.132"` (Education's build version) or any other variant.

### Protocol Structure

Education and Bedrock share:
- Same RakNet transport layer (UDP, port 19132)
- Same packet IDs and serialization format
- Same compression (Zlib/Snappy) and encryption support
- Same JWT chain structure in login packets

Differences:
- **Edition string:** Education identifies as `MCEE` in RakNet unconnected pong, vs `MCPE` for Bedrock
- **Authentication:** Education uses Microsoft 365 Education tenant JWTs instead of Xbox Live
- **Education packets:** Several packet types exist for Education features (chemistry reactions, etc.) — present in both binaries but gated
- **StartGamePacket wire format:** Education v898 has 3 extra string fields in LevelSettings (see Section 8)
- **Block breaking action types:** Education uses different `PlayerActionType` values (see Section 15)

### Historical Note: Protocol Divergence

bundabrg's original Reversion library (2020-2021, protocol v363-v392) had separate Education Edition codecs with custom serializers for `StartGame`, `InventoryTransaction`, `CraftingData`, `Event`, and `PlayerList` packets. This indicated that at that time, Education's packet serialization differed from Bedrock at the same protocol version.

**Current status (protocol 898):** Since Education is now built from the same branch with education features gated behind flags rather than being a deeply separate fork, the serialization has largely converged. The palettes are confirmed identical. However, the `StartGamePacket` still requires Education-specific structural fields (see Section 8), and block breaking action types differ (see Section 15).

### Protocol Gap Analysis: Education 898 vs Current Bedrock 924

While protocol 898 is natively supported by Geyser (meaning no translation is needed now), the following details document the gap for future reference if either side updates:

- **26 intermediate protocol bumps** exist between 898 and 924 — these are not separate releases but incremental changes in preview/beta builds
- Protocol 898 was the **final version using the old version numbering format** (1.x.x). Bedrock 26.x uses year-based versioning.
- **10 new packets** were added between 898 and 924: `ClientboundDataStorePacket`, `GraphicsParameterOverridePacket`, `ServerboundDataStorePacket`, DataDrivenUI variant packets, `TextureShiftPacket`, `VoxelShapesPacket`, `CameraSplinePacket`, `CameraAimAssistActorPriorityPacket`
- **Protocol 922** added `Server Join Information Flag` and `Server Telemetry Data` fields to `StartGamePacket`
- Mojang's documentation was migrating packet serialization to the **"Cereal" format**, introducing breaking changes in how packets are described
- **`EditionMismatchEduToVanilla`** and **`EditionMismatchVanillaToEdu`** disconnect reasons were added in this version range, confirming Mojang's awareness of cross-edition connections

### Minecraft's New Drop System

Minecraft shifted to year-based versioning (26.x for 2026) and a "game drops" model with smaller, more frequent updates. This could mean Bedrock pulls ahead of Education faster, or it could mean Education adopts the same cadence. Too early to tell.

### Version Support Risk

If Bedrock moves to protocol 950+ and Geyser drops 898, Education support breaks — not because of the Education code but because Geyser pruned the codec. The real problem is not keeping the codec alive (trivial), but translating new content that doesn't exist in Education's older version (ongoing work). oryxel1's active GeyserReversion extension handles multi-version Bedrock support and could serve as a mitigation path.

---

## 4. Connection Methods

### URI Scheme (Primary Method)

Education Edition registers a URI scheme that allows direct IP connection:

```
start "" "minecraftedu://connect/?serverUrl=IP_ADDRESS:PORT"
```

This bypasses the Education client's join code system entirely. No admin portal registration, no DLL injection, no UI modification needed. The client connects directly via RakNet to the specified IP and port.

This works on all platforms where Education Edition is installed — Windows, macOS, iPad, Chromebook, Android. The URI scheme is registered by the app itself. A simple HTML page with a link works cross-platform:

```html
<a href="minecraftedu://connect/?serverUrl=your.server.ip:19132">Click to Join</a>
```

An alternate URI format also works:
```
minecraftedu://connect?serverUrl=<host>&serverPort=<port>
```

Other URIs tested:
- `minecraftedu:?addExternalServer=Name|ip:port` — runs successfully but has no visible effect (Education has no server list UI to display it)

Education Edition registers `minecraftedu://` as a protocol handler in its `AppxManifest.xml`:
```xml
<uap3:Protocol Name="minecraftedu" Parameters="&quot;%1&quot;" />
```

The binary parses these URI parameters: `serverUrl`, `serverPort`, `localWorld`, `localLevelId`, `useRemoteConnect`, `deeplinkToken`.

See Appendix H for a complete list of all known `minecraft://` and `minecraftedu://` URI schemes.

### VDX Desktop UI Resource Pack

A third-party resource pack called **VDX Desktop UI** (by CrisXolt) can be installed on Education Edition to restore the standard Bedrock server list UI, including the "Add External Server" screen with IP address, port, and server name fields.

This gives Education Edition users a persistent server list — the same experience Bedrock players have — without needing the URI scheme or admin portal registration.

The resource pack works because the underlying connection code for direct IP connections already exists in the Education client — Microsoft only removed the UI. The resource pack re-adds it using the same internal data bindings (`#ip_text_box`, `#port_text_box`, `#name_text_box`).

**Resource pack source:** VDX-DesktopUI by @CrisXolt (available on MCPEDL and similar sites)

**Connection methods summary:**

| Method | Requires Admin? | Persistent? | User Experience |
|--------|----------------|-------------|-----------------|
| `minecraftedu://connect/?serverUrl=ip:port` | No | No (one-time link) | Click link to join |
| VDX Desktop UI resource pack | No | Yes (saved server list) | Standard Bedrock server list |
| Official Dedicated Server Admin Portal | Yes (Global Admin) | Yes (registered) | Official, managed by IT |

### Join Code System (Education's Normal Multiplayer)

**Picture-based join codes:**
- 4 or 5 Minecraft item icons (smaller tenants get 4, larger get 5). Internally represented as comma-separated indices like `"0,13,16,12"`.
- Restricted to players within the same Microsoft 365 Education tenant
- Host device acts as server
- Maximum 40 players

**Connection IDs:**
- Numeric session identifier (e.g., `9106347824772137138`)
- Alternative to picture codes
- Entered via "Advanced" option on the join screen

**Join URL format:**
```
https://education.minecraft.net/joinworld/MSw5LDAsNA%3D%3D
```
The parameter is URL-encoded base64. `MSw5LDAsNA==` decodes to `1,9,0,4` — the join code icon indices.

**Underlying P2P infrastructure:** Uses `signal.franchise.minecraft-services.net` for WebSocket signaling, STUN/TURN via `relay.communication.microsoft.com` for WebRTC data channel NAT traversal. NOT direct IP connections. See Section 18 for full details.

### Dedicated Server System (February 2026)

New as of Education 1.21.132. Self-hosted on Windows/Linux. Requires Global Admin role.

- **Admin Portal:** Web interface for Global Admins to enable servers, add entries. URL: `education.minecraft.net/teachertools/en_US/dedicatedservers/`
- **Server software:** Separate builds for Windows and Linux
- **Minimum specs:** 64-bit Intel/AMD, 2 cores, 1 GB RAM, Ubuntu 18+ or Windows 10 1703+/Server 2016+
- **Features:** Broadcast to in-game server list, cross-tenant play (scripting only), passcode protection
- **Limitation:** Only Global Admins can manage; teacher management "coming soon"
- **Recommended port:** NOT 19132 (conflicts with other Minecraft apps). Default is 20202.

### Safety Service

During connection, the Education client calls `safety-secondary.franchise.minecraft-services.net:443`. This is a safety/moderation service check. **Blocking it via hosts file does NOT prevent the disconnect** — it's not the cause of the connection failure.

---

## 5. Client Detection (Per-Session)

### How Education clients are detected

Education Edition clients are identified by the `IsEduMode` field in their **client data JWT** (the second JWT in the login packet, containing device/skin/platform information).

**File:** `BedrockClientData.java`

```java
@SerializedName(value = "IsEduMode")
private boolean isEduMode;

public boolean isEducationEdition() {
    return isEduMode;
}
```

Detection happens in `LoginEncryptionUtils.encryptConnectionWithCert()`, immediately after parsing client data:

```java
BedrockClientData data = JsonUtils.fromJson(clientDataPayload, BedrockClientData.class);
session.setClientData(data);

if (data.isEducationEdition()) {
    session.setEducationClient(true);
}
```

The `educationClient` boolean is stored on `GeyserSession` and used throughout the session lifetime. This enables per-client behavior — a single server can support both Education and standard Bedrock clients simultaneously.

### What does NOT work for detection

- **`TitleId` in client JWT**: Initially attempted, but the Education Edition TitleId is not reliably known and may change between versions. Hardcoding it is fragile.
- **`TokenPayload` vs `CertificateChainPayload`**: Education clients use `CertificateChainPayload`, the same as standard Bedrock. They are NOT distinguishable by auth payload type.
- **`rawIdentityClaims` from the identity chain**: The `IsEduMode`, `TenantId`, and `ADRole` fields are NOT present in the identity chain claims (`result.rawIdentityClaims()` from `EncryptionUtils.validatePayload()`). They exist only in the client data JWT parsed by `BedrockClientData`.
- **Global config check**: Using the `education-token` config to toggle education mode globally means ALL clients are treated as Education or NONE are. This breaks mixed-client servers where both Education and standard Bedrock clients connect simultaneously.

### Auth payload type

Despite being Education Edition, the client sends a `CertificateChainPayload` (not `TokenPayload`). The auth payload type alone cannot distinguish Education from standard Bedrock.

### Login chain validation

Education client login chains are **not signed by Mojang's root key**. The `result.signed()` check returns `false`. The Geyser config option `validate-bedrock-login` must be set to `false` to allow Education clients to connect. When disabled, both signed (standard Bedrock) and unsigned (Education) chains are accepted.

### Known auth information from Education JWT

The Education client's JWT chain contains (in the client data JWT):
- `IsEduMode`: boolean (always true for Education clients)
- `TenantId`: string (the Azure AD tenant ID of the school/organization)
- `ADRole`: int — `0` = student, `1` = teacher

Additional JWT fields found in client login data:
- `EduTokenChain` — JWT signed by MESS proving client authorization (the trust anchor — see Section 50)
- `EduJoinerToHostNonce` — nonce for join validation (changes per connection)
- `EduSessionToken` — `"uuid|timestamp"` format, present when client joined via server list, absent for direct URI connections

**Note:** The `TenantId` field from `BedrockClientData` (deserialized via `@SerializedName("TenantId")`) is **always null** for Education clients — not empty string, but null. The real, trustworthy tenant ID is only available inside the `EduTokenChain` JWT payload (see Section 50). This has implications for UUID generation and username formatting (see Sections 54-55).

---

## 6. Authentication & Token System

### Overview

Education Edition uses a dual authentication model:
1. **Client → Server:** The client authenticates via Microsoft 365 Education tenant JWTs (not Xbox Live)
2. **Server → Client:** The server must prove tenant authorization via a `signedToken` in the handshake JWT

Geyser's `validate-bedrock-login: false` config disables #1. The `signedToken` injection handles #2.

### The signedToken

The `signedToken` is a tenant-scoped authorization token. Format:
```
tenantId|serverId|timestamp|signature
```

Example:
```
03b5e7a1-cb09-4417-9e1a-c686b440b2c5|e2a49ff3-29ba-4cc2-99de-4c355fe81bfa|2026-03-19T14:18:13.486Z|41863f21cdbeacbd1...
```

Components:
- First UUID: Tenant ID (matches `tid` in the Bearer JWT)
- Second field: User/Object ID or Server ID depending on acquisition method (matches `oid` in the Bearer JWT when obtained via `/host`; is a server-specific identifier like `I1AWQKBZS3I6` when obtained via admin portal)
- Timestamp: ISO 8601 format
- Signature: Hex-encoded cryptographic signature (~350+ characters total)

**Validation:** The client validates this token. Changing even one character causes immediate rejection with "Invalid Tenant ID." A separate "School not allowed" error may also appear in certain failure modes.

**Scope:** The serverToken is scoped to a tenant. A token from tenant A will be rejected by clients from tenant B. There is no way to create a cross-tenant token — the P2P `/host` endpoint always generates a token scoped to the calling user's tenant.

This means: for school students to connect, the serverToken **must** come from the school's tenant. Any student or teacher at the school can provide the Bearer token (Method 1), or a Global Admin can use the automated MESS integration (Method 2).

**Token refresh:** Token refresh is done by refreshing the Entra access token via standard OAuth refresh_token grant, then calling `/server/fetch_token` with the new access token. Note: the MESS `/server/token_refresh` endpoint is documented but returns 404 in practice — see Section 32, issue #7.

### Two Separate Token Services

Two separate Microsoft services handle Education server tokens with different capabilities:

| Feature | discovery.minecrafteduservices.com | dedicatedserver.minecrafteduservices.com |
|---------|-----------------------------------|------------------------------------------|
| Token type | P2P serverToken | Dedicated serverToken |
| Registration | Implicit (via /host) | Explicit (via /register) |
| Server list | No | Yes (server appears in Education client) |
| Keepalive | /update with passcode | /server/update with health |
| Token refresh | None (re-call /host) | Via Entra refresh + /server/fetch_token |
| Join code | Yes (passcode in /host response) | No (uses server list) |
| Admin required | No (any M365 Education user) | Yes (Global Admin for initial tenant setup) |
| Cross-tenant | Limited (tenant-scoped token) | Full (with invite system) |

See Section 38 for the complete MESS API reference.

### Four Methods to Obtain a Token

All methods produce a `tenantId|...|timestamp|signature` format token. The client validates the format, not the source.

#### Method 1: P2P Hosting Capture (Any M365 Education User, No Admin Required)

Any user with a Microsoft 365 Education license (student or teacher) can generate a P2P serverToken by hosting a world. The token is scoped to their tenant. This is the simplest method and requires no admin access. Students connect via the `minecraftedu://connect` URI scheme — the server will NOT appear in the Education server list.

**Automated approach: Token Extractor Tool** — see Section 40. The user runs the tool, hosts a world for one second, and the tool captures and exchanges the token automatically.

**Manual approach: Fiddler Capture**

The Education client must be exempted for Fiddler interception:
```
CheckNetIsolation LoopbackExempt -a -n="Microsoft.MinecraftEducationEdition_8wekyb3d8bbwe"
```
(Run as admin. Find exact package name with: `Get-AppxPackage *education* | Select PackageFamilyName`)

Fiddler setup:
1. Tools → Options → HTTPS
2. Check "Capture HTTPS CONNECTs"
3. Check "Decrypt HTTPS traffic" (accept certificate prompts)
4. Check "Ignore server certificate errors"
5. Check WinConfig for Education Edition exemption
6. Ensure "Capturing" shows in bottom left (F12 toggles)

Capture process:
1. Clear Fiddler session list (Ctrl+X)
2. Open a world in Education singleplayer
3. Start hosting from within the world
4. The POST to `discovery.minecrafteduservices.com/host` appears with `Authorization: Bearer` header

The Bearer token JWT contains:
- `tid`: tenant ID (e.g., `03b5e7a1-cb09-4417-9e1a-c686b440b2c5`)
- `oid`: user object ID
- `appid`: `b36b1432-1a1c-4c82-9b76-24de1cab42f2` (Minecraft Education client ID)
- `aud`: `16556bfc-5102-43c9-a82a-3ea5e4810689` (Education services resource)

Full decoded Bearer JWT example:
```json
{
  "aud": "16556bfc-5102-43c9-a82a-3ea5e4810689",
  "iss": "https://sts.windows.net/03b5e7a1-cb09-4417-9e1a-c686b440b2c5/",
  "appid": "b36b1432-1a1c-4c82-9b76-24de1cab42f2",
  "tid": "03b5e7a1-cb09-4417-9e1a-c686b440b2c5",
  "oid": "e2a49ff3-29ba-4cc2-99de-4c355fe81bfa",
  "family_name": "...",
  "given_name": "...",
  "name": "...",
  "unique_name": "...",
  "upn": "...",
  "ver": "1.0",
  "amr": ["pwd", "mfa"],
  "acr": "1",
  "scp": "user_impersonation"
}
```

Bearer tokens expire after approximately 75-90 minutes.

**Exchange Bearer for serverToken:**

```powershell
$token = "YOUR_BEARER_TOKEN"
$response = Invoke-RestMethod -Method Post -Uri "https://discovery.minecrafteduservices.com/host" -Headers @{"Authorization"="Bearer $token"; "api-version"="2.0"} -ContentType "application/json" -Body '{"build":12232001,"locale":"en_US","maxPlayers":40,"networkId":"1234567890","playerCount":0,"protocolVersion":1,"serverDetails":"GeyserProxy","serverName":"My Server","transportType":2}'
$response.serverToken
```

**Important:** Use `$response.serverToken` to get the full untruncated value. PowerShell default display truncates long strings.

The full response includes:
- `serverToken`: the token for the handshake JWT
- `passcode`: join code as comma-separated icon indices (e.g., `"0,13,16,12"`)

The serverToken is valid for approximately 2 weeks based on the embedded timestamp. This method produces a **P2P token** — the server will NOT appear in the Education client's server list.

**Scope:** The serverToken is scoped to the calling user's tenant. For school students to connect, the token **must** come from someone in the school's M365 tenant (any student or teacher at the school).

> **OUTDATED ENDPOINT:** The original endpoint bundabrg used (`meeservices.azurewebsites.net/v2/signin`) no longer exists — DNS resolution fails. `discovery.minecrafteduservices.com/host` is the current equivalent. The OAuth2 OOB redirect URI (`urn:ietf:wg:oauth:2.0:oob`) also no longer works — Microsoft returns `AADSTS50011`. Obtain Bearer tokens via Fiddler capture instead.

#### Method 2: EducationAuthManager — Automatic MESS Integration (Recommended)

This is the built-in system in the Geyser fork that fully automates token acquisition, server registration, and lifecycle management using Microsoft's official MESS dedicated server API. The server appears in Education Edition's in-app server list — students can join without knowing the IP address.

**Requirements:**
- A Microsoft 365 Education tenant where you are **Global Admin** (see Section 48 for how to obtain a commercial test tenant for $36/year)
- The Dedicated Server feature must be enabled in the tenant (via the admin portal at `education.minecraft.net/teachertools/en_US/dedicatedservers/`)

**How it works:**

1. Set `edu-server-name` in Geyser's `config.yml` (e.g., `edu-server-name: "My Java Server"`)
2. On first startup, Geyser initiates an **OAuth device code flow** — it displays a URL and code in the console
3. The admin opens the URL in a browser, enters the code, and signs in with their Global Admin M365 account
4. Geyser receives an **Entra access token** and **refresh token**
5. Geyser calls `POST /server/register` on `dedicatedserver.minecrafteduservices.com` → receives a **serverId** and **serverToken**
6. Geyser calls `POST /server/host` to register the server's IP:port with MESS → the server appears in Education Edition's server list
7. Geyser attempts `POST /tooling/edit_server_info` to enable the server, set its name, and enable broadcast (best-effort — may return 401, in which case manual portal setup is needed)
8. **Every 10 seconds:** Geyser sends `POST /server/update` with player count and health status (keeps the server showing as "online")
9. **Every 30 minutes:** Geyser refreshes the Entra access token and fetches a new server token (automatic, no user interaction)
10. **On shutdown:** Geyser calls `POST /server/dehost` to immediately remove the server from the list

**After first setup:** All tokens are persisted to `edu_session.json`. On subsequent restarts, Geyser silently refreshes tokens without requiring a new device code flow. The admin never needs to interact again unless the session expires or is reset.

**Configuration:**
```yaml
edu-server-name: "My Java Server"     # Setting this enables the system
edu-server-id: ""                      # Auto-filled on first registration
edu-server-ip: ""                      # Auto-detected or set manually
edu-max-players: 40                    # Shown in server list
```

**What students see:** The server appears in their Education Edition server list with the configured name, player count, and health status. They click "Play" and connect — no IP address or URI needed.

**Cross-tenant:** With `crossTenantAllowed` enabled on the server registration (via admin portal), students from OTHER M365 tenants can also join. The owning tenant's admin enabling cross-tenant is sufficient — the guest tenant's admin does NOT need to enable anything (see Section 20, Cross-Tenant One-Sided Bypass).

**Management:** `/geyser edu status`, `/geyser edu players`, `/geyser edu reset`, `/geyser edu register` (see Section 52).

**Note on conditional access:** Some school tenants have conditional access policies that block the device code flow. In those cases, use Method 1 (P2P capture) with the Token Extractor Tool instead, and set the token manually via `education-token` in config.

See Section 51 for complete EducationAuthManager implementation details, and Section 38 for the full MESS API reference.

#### Method 3: Admin Portal (Manual, Global Admin Required)

The Dedicated Server Admin Portal at `education.minecraft.net/teachertools/en_US/dedicatedservers/` allows Global Admins to register servers.

When creating a server, the portal calls:
```
POST https://teachertools.minecrafteduservices.com/website/dedicatedserver/register_server
Authorization: Bearer <Entra JWT with appid f8ba6a93-3dc8-4753-9f89-886138158d8b>
api-version: 1.0
```

The response is a signed JWT whose payload contains:
```json
{
  "ServerId": "I1AWQKBZS3I6",
  "ServerToken": "75535150-2dbb-4af5-...|I1AWQKBZS3I6|2026-03-23T21:44:31.890Z|7a8a1b..."
}
```

The token from the admin portal can be pasted into `education-token` in Geyser's config as a manual alternative to Method 2's automatic system.

#### Method 4: Dedicated Server Binary (Reference Only)

The official Education dedicated server binary (`bedrock_server_edu` / `bedrock_server.exe`) authenticates via the same Entra device code flow that Method 2 automates. This method is documented for reference — understanding how the official binary works is what enabled Method 2's implementation.

The server binary authenticates via Entra device code flow:
1. POST to `https://login.microsoftonline.com/{tenantId}/organizations/oauth2/v2.0/devicecode`
2. Client ID: `b36b1432-1a1c-4c82-9b76-24de1cab42f2`
3. Scope: `api://mc{env}{env_id}/.default offline_access`
4. Grant type: `urn:ietf:params:oauth:grant-type:device_code`
5. After Entra auth, exchanges with Education services at endpoints like `server/fetch_token` on `dedicatedserverprod.minecrafteduservices.com`
6. Response includes `serverToken` and `serverId`
7. Stores session in `edu_server_session.json` with fields: `server_id` (string), `refresh_token` (string), `expires_on` (int, unix timestamp)

**Note:** Some school tenants have conditional access policies that block the device code flow from non-client contexts. In those cases, the Fiddler capture method (Method 1) is the only option.

### Session Registration API Detail

When a player hosts a world, the client calls:

**POST** `https://discovery.minecrafteduservices.com/host`

Headers:
```
Authorization: Bearer <JWT token>
Content-Type: application/json
api-version: 2.0
User-Agent: libhttpclient/1.0.0.0
```

Request body:
```json
{
  "build": 12232001,
  "locale": "en_US",
  "maxPlayers": 40,
  "networkId": "6843815231478666781",
  "playerCount": 0,
  "protocolVersion": 1,
  "serverDetails": "PlayerName",
  "serverName": "My World",
  "transportType": 2
}
```

- `networkId` — generated by the client, serves as the Connection ID
- `serverDetails` — the host's username
- `transportType: 2` — connection type identifier

Response:
```json
{
  "serverToken": "03b5e7a1-...|e2a49ff3-...|2026-03-18T13:54:14.684Z|68d15236...",
  "passcode": "9,4,13,0"
}
```

Session updates are sent periodically:
```json
{
  "build": 12232001,
  "locale": "en_US",
  "maxPlayers": 40,
  "passcode": "9,4,13,0",
  "playerCount": 1,
  "protocolVersion": 1,
  "serverDetails": "PlayerName",
  "serverName": "My World"
}
```

### OAuth2 Application Details

| Field | Value |
|-------|-------|
| Client ID | `b36b1432-1a1c-4c82-9b76-24de1cab42f2` |
| Audience | `16556bfc-5102-43c9-a82a-3ea5e4810689` |
| Issuer | `https://sts.windows.net/{tenantId}/` |
| Scope | `user_impersonation` |
| Token endpoint | `login.microsoftonline.com/common/oauth2/token` |
| OOB redirect | `urn:ietf:wg:oauth:2.0:oob` (**NO LONGER WORKS**) |

### Historical Token Endpoint

bundabrg's original implementation used `meeservices.azurewebsites.net/v2/signin` to obtain signed tokens. **This endpoint no longer exists** (DNS resolution fails). The current equivalent is `discovery.minecrafteduservices.com/host`.

bundabrg's OAuth2 flow used:
- Client ID: `b36b1432-1a1c-4c82-9b76-24de1cab42f2`
- Resource: `https://meeservices.minecraft.net`
- Redirect URI: `urn:ietf:wg:oauth:2.0:oob`

---

## 7. Encryption Handshake

### How the Token is Injected (Geyser Fork)

**Standard Bedrock handshake:**

Geyser normally creates the handshake JWT using:
```java
jwt = EncryptionUtils.createHandshakeJwt(serverKeyPair, token);
```
This produces a JWT containing only a `salt` claim.

**Education handshake:**

Education clients require an additional `signedToken` claim in the handshake JWT. Without it, the client rejects the server with an "Invalid Tenant ID" error.

**File:** `LoginEncryptionUtils.startEncryptionHandshake()`

```java
if (session.isEducationClient() && educationToken != null && !educationToken.isEmpty()) {
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
}
```

Key details:
- Algorithm: **ES384** (same as standard Bedrock)
- The `x5u` header contains the server's public key (Base64-encoded)
- The `signedToken` claim contains the raw token string from the Education services endpoint
- The `salt` claim is still required (same as standard handshake)
- The `signedToken` is added as a JWT claim **before** signing

Alternative implementation using Nimbus JOSE (also works):
```java
JWTClaimsSet.Builder claimsBuilder = new JWTClaimsSet.Builder();
claimsBuilder.claim("salt", Base64.getEncoder().encodeToString(token));
claimsBuilder.claim("signedToken", signedTokenValue);  // BEFORE signing

SignedJWT jwt = new SignedJWT(
    new JWSHeader.Builder(JWSAlgorithm.ES384).x509CertURL(x5u).build(),
    claimsBuilder.build()
);
EncryptionUtils.signJwt(jwt, (ECPrivateKey) serverKeyPair.getPrivate());
```

### Critical: Encryption Order

The handshake packet MUST be sent **before** enabling encryption:

```java
// 1. Build and send handshake UNENCRYPTED
ServerToClientHandshakePacket packet = new ServerToClientHandshakePacket();
packet.setJwt(jwt.serialize());
session.sendUpstreamPacketImmediately(packet);

// 2. THEN enable encryption
SecretKey encryptionKey = EncryptionUtils.getSecretKey(
    serverKeyPair.getPrivate(), key, token);
session.getUpstream().getSession().enableEncryption(encryptionKey);
```

**Why:** The CloudburstMC Protocol library applies encryption immediately upon `enableEncryption()`. If called before sending the handshake, the packet arrives encrypted but the client hasn't read the salt from the JWT yet to derive the shared secret. The client receives gibberish and cannot respond.

**Historical note:** bundabrg's original code enabled encryption BEFORE sending, which worked with the old Nukkitx protocol library (which deferred encryption activation). The CloudburstMC library requires the reversed order. This was a bug that had to be fixed during development — it is listed in the eliminated theories table (Section 29) as "Encryption order ✅ Fixed."

### Chain Validation Clarification

Education client login chains are **not signed by Mojang's standard Bedrock root key** — this is why `result.signed()` returns `false` in Geyser's validation. However, the Education chain IS cryptographically signed by a separate Microsoft Education identity authority. The chain is valid and authenticates the player's M365 identity — it just uses a different root of trust than Xbox Live.

This means:
- Geyser's built-in `validate-bedrock-login` check must be `false` (it only knows the Mojang Bedrock root key)
- Custom chain validation against the Education identity key would enable verified identity (real M365 name, tenant ID, role)
- With custom validation, identity claims (`displayName`, `identity`, `XUID`, `TenantId`, `ADRole`) from the chain are authentic and cannot be spoofed

---

## 8. The Core Fix: Education-Specific StartGamePacket Fields

### The Problem

After authentication, encryption, and resource pack negotiation all succeed, the Education client would receive the StartGamePacket, process it, and disconnect 1-1.5 seconds later with a generic "An error occurred" message. Server-side: clean disconnect, no errors, no exceptions.

### Root Cause

The Education Edition client's v898 protocol deserializer expects **three additional string fields** at the end of the LevelSettings section of the StartGamePacket that standard Bedrock v898 does not include:

1. `educationReferrerId` (string)
2. `educationCreatorWorldId` (string)
3. `educationCreatorId` (string)

These fields exist in the Education build but **not** in CloudburstMC's open-source protocol library, which only implements standard Bedrock. When the client reads past where these fields should be, it consumes bytes belonging to subsequent data (levelId, levelName, etc.), causing a deserialization failure. The client sets a disconnect flag during initialization and disconnects on the next tick.

### How This Was Discovered

The Education dedicated server binary (`bedrock_server_edu`, `bedrock_server.exe`) is publicly downloadable from the admin portal. Running `strings` on the binary revealed a cereal serialization schema containing field descriptions for the StartGamePacket's LevelSettings. The field ordering showed three Education-specific entries that don't exist in any version of CloudburstMC's `StartGamePacket.java`:

```
...
For servers this should always be false.
Education Referrer Id
Education Creator World Id
Scenario Identifier from the server.
World Identifier from the server.
Education Creator Id
Owner Identifier from the server.
ChatRestriction Level
...
```

Cross-referencing against CloudburstMC's `StartGamePacket.java` confirmed `serverId`, `worldId`, `scenarioId`, and `ownerId` existed but `Education Referrer Id`, `Education Creator World Id`, and `Education Creator Id` did not.

**Note:** The `strings` output shows field descriptions, not wire serialization order. The cereal schema descriptions are not necessarily listed in the same order as the binary serialization. The working implementation appends all three fields after `super.writeLevelSettings()` (i.e., after `ownerId`), which is confirmed correct by testing.

### The Standard v898 Level Settings Tail (what Geyser normally writes)

```
... → eduSharedUriResource (2 strings) → forceExperimentalGameplay (optional bool)
→ chatRestrictionLevel (byte) → disablingPlayerInteractions (bool)
→ serverId (string) → worldId (string) → scenarioId (string) → ownerId (string)
```

### The 3 Education Fields Appended After ownerId

```
→ educationReferrerId (string)
→ educationCreatorWorldId (string)
→ educationCreatorId (string)
```

These fields are written as empty strings. They exist at the very end of `writeLevelSettings()`, after `ownerId`. This matches the Bedrock convention of appending new fields at the tail of level settings.

### The Fix

Create a custom serializer `EducationStartGameSerializer.java` that extends `StartGameSerializer_v898`:

**File:** `EducationStartGameSerializer.java`

```java
class EducationStartGameSerializer extends StartGameSerializer_v898 {

    static final EducationStartGameSerializer INSTANCE = new EducationStartGameSerializer();

    @Override
    protected void writeLevelSettings(ByteBuf buffer, BedrockCodecHelper helper, StartGamePacket packet) {
        super.writeLevelSettings(buffer, helper, packet);
        // Education-specific fields not present in standard Bedrock v898
        helper.writeString(buffer, ""); // educationReferrerId
        helper.writeString(buffer, ""); // educationCreatorWorldId
        helper.writeString(buffer, ""); // educationCreatorId
    }

    @Override
    protected void readLevelSettings(ByteBuf buffer, BedrockCodecHelper helper, StartGamePacket packet) {
        super.readLevelSettings(buffer, helper, packet);
        // Consume Education-specific fields on read path
        helper.readString(buffer); // educationReferrerId
        helper.readString(buffer); // educationCreatorWorldId
        helper.readString(buffer); // educationCreatorId
    }
}
```

The serializer extends `StartGameSerializer_v898` (the latest version in the codec chain for protocol 898). It overrides `writeLevelSettings` to append the 3 fields after the parent writes all standard fields, and `readLevelSettings` to consume them when reading.

The education codec is created in `CodecProcessor.java`:

```java
public static BedrockCodec educationCodec(BedrockCodec codec) {
    return codec.toBuilder()
        .updateSerializer(StartGamePacket.class, EducationStartGameSerializer.INSTANCE)
        .build();
}
```

This creates a new codec identical to the standard one, with only the `StartGamePacket` serializer replaced.

These fields are written as empty strings. The client only requires them to be present in the byte stream to correctly parse the remainder of the packet. The values themselves are not validated for gameplay purposes.

### Why Other Fixes Failed

All field-value changes (vanillaVersion, gamerules, education flags, EducationSettingsPacket) had zero effect because the problem was not about field values — it was about the packet's binary structure. The client's deserializer expected N+3 strings in the LevelSettings section. Without those 3 strings, every byte after that point was misread. No amount of tweaking the values of correctly-positioned fields could fix a structural misalignment.

The nuclear suppression test (suppressing all packets after StartGame) was the key diagnostic: it caused the same disconnect at the same timing (1-1.5 seconds). This definitively proved:
1. The problem is in or before StartGamePacket (not any subsequent packet)
2. No post-StartGame packet suppression or modification could fix it
3. The problem is structural, not value-based

---

## 9. Codec Swap: Netty Pipeline Timing

### The Problem with Temporary Codec Swaps

Initial attempts tried to temporarily swap the codec before sending the StartGamePacket and swap back immediately after:

```java
// THIS DOES NOT WORK
upstream.getSession().setCodec(educationCodec);
sendUpstreamPacket(startGamePacket);
upstream.getSession().setCodec(standardCodec);  // Swapped back too early!
```

This fails because `sendUpstreamPacket()` (and even `sendUpstreamPacketImmediately()`) do not encode the packet synchronously. Both methods queue the packet to Netty's event loop. The actual serialization happens later, on the event loop thread, by which time the codec has already been swapped back to standard.

Even `channel.writeAndFlush()` does not encode synchronously if called from a non-event-loop thread — the write is scheduled on the event loop.

### The Solution: Permanent Codec Swap

The education codec is set **permanently** for education client sessions. Since it only differs in the `StartGamePacket` serializer, all other packets use identical serializers. There is no behavioral difference for non-StartGamePacket traffic.

```java
if (educationClient) {
    upstream.getSession().setCodec(CodecProcessor.educationCodec(upstream.getSession().getCodec()));
}
sendUpstreamPacket(startGamePacket);
```

This is set in `GeyserSession.startGame()`, just before the StartGamePacket is sent.

---

## 10. Registry Transfer After Codec Swap

### The Problem

Calling `setCodec()` on a Bedrock session creates a **new `BedrockCodecHelper`** internally. The old helper had item definitions, block definitions, and camera preset definitions configured. The new helper starts empty.

Without these registries, any subsequent packet that references items (e.g., `PlayerAuthInputPacket` with `PERFORM_ITEM_INTERACTION`) will crash with:

```
NullPointerException: Cannot invoke "DefinitionRegistry.getDefinition(int)"
because "this.itemDefinitions" is null
    at BedrockCodecHelper_v431.readItem()
    at PlayerAuthInputSerializer_v712.readItemUseTransaction()
```

### The Fix

After setting the education codec, immediately re-apply all registries to the new codec helper:

```java
if (educationClient) {
    upstream.getSession().setCodec(CodecProcessor.educationCodec(upstream.getSession().getCodec()));
    // setCodec() creates a new codec helper, wiping registries. Re-set them.
    upstream.getCodecHelper().setItemDefinitions(this.itemMappings);
    upstream.getCodecHelper().setBlockDefinitions(this.blockMappings);
    upstream.getCodecHelper().setCameraPresetDefinitions(CameraDefinitions.CAMERA_DEFINITIONS);
}
```

The three registries that must be transferred:
1. **`itemDefinitions`** — maps runtime item IDs to item definitions (required for item serialization)
2. **`blockDefinitions`** — maps runtime block IDs to block definitions
3. **`cameraPresetDefinitions`** — camera preset registry

Without ALL three, various packets will fail to serialize/deserialize at unpredictable times.

---

## 11. StartGamePacket Field Configuration

In addition to the three structural fields (Section 8), the following StartGamePacket fields are set differently for Education clients in the Geyser fork's `GeyserSession.java`:

| Field | Education Value | Standard Bedrock Value |
|-------|----------------|----------------------|
| `eduEditionOffers` | `1` | `0` |
| `eduFeaturesEnabled` | `true` | `false` |
| `educationProductionId` | `"education"` | `""` |
| `vanillaVersion` | `"*"` | `"*"` |
| `authoritativeMovementMode` | `SERVER` | `SERVER` |
| `serverAuthoritativeBlockBreaking` | `true` | `true` |

**Note on reverted changes:** During development, Education clients were initially set to `AuthoritativeMovementMode.CLIENT` with `serverAuthoritativeBlockBreaking=false`. These were defensive hypotheses that were later reverted after the core fix (3 extra StartGamePacket fields) resolved the connection issue. Both Education and Bedrock clients now use identical movement and block breaking authority settings. The `CONTINUE_BREAK`/`STOP_BREAK` aliases in `BlockBreakHandler` remain as a safety net in case the Education client still sends these action types under server-authoritative mode. See Section 57 for all reverted changes.

**Note on `vanillaVersion`:** This field remains at `"*"` (Geyser's default wildcard to bypass strict version checking). During debugging, it was identified as a leading suspect — the theory was that Education's C++ engine might fail to parse `"*"` as a Major.Minor.Patch version string. Testing confirmed this was NOT the cause of the disconnect. Setting it to `"1.21.132"` or `"1.21.130"` made no difference. The root cause was always the three missing structural fields (Section 8).

**Note on education flags:** Toggling `eduEditionOffers`, `eduFeaturesEnabled`, and `educationProductionId` on or off does NOT change the disconnect behavior. They are supplementary to the core serializer fix and are set for proper Education feature support.

### Experiments

Education clients receive additional experiments in the StartGamePacket:

```java
startGamePacket.getExperiments().add(new ExperimentData("chemistry", true));
startGamePacket.getExperiments().add(new ExperimentData("gametest", true));
```

These are added alongside the standard experiments (`data_driven_items`, `upcoming_creator_features`, `experimental_molang_features`).

### Gamerules in StartGamePacket

Education-specific gamerules are embedded in the StartGamePacket's gamerule list:

```java
startGamePacket.getGamerules().add(new GameRuleData<>("codebuilder", false));
startGamePacket.getGamerules().add(new GameRuleData<>("allowdestructiveobjects", true));
startGamePacket.getGamerules().add(new GameRuleData<>("allowmobs", true));
startGamePacket.getGamerules().add(new GameRuleData<>("globalmute", false));
```

---

## 12. EducationSettingsPacket

Packet ID 137, sent server→client. Registered in the v898 codec (inherited from v388 registration, serializer updated at v465). Mojang's official documentation describes it as being transmitted "when the game is starting."

An `EducationSettingsPacket` is sent immediately after the `StartGamePacket` for education clients. This packet configures Education-specific features like Code Builder.

```java
if (educationClient) {
    EducationSettingsPacket eduSettings = new EducationSettingsPacket();
    eduSettings.setCodeBuilderUri("");
    eduSettings.setCodeBuilderTitle("");
    eduSettings.setCanResizeCodeBuilder(false);
    eduSettings.setDisableLegacyTitle(false);
    eduSettings.setPostProcessFilter("");
    eduSettings.setScreenshotBorderPath("");
    eduSettings.setEntityCapabilities(OptionalBoolean.of(false));
    eduSettings.setOverrideUri(Optional.empty());
    eduSettings.setQuizAttached(false);
    eduSettings.setExternalLinkSettings(OptionalBoolean.empty());
    sendUpstreamPacket(eduSettings);
}
```

All fields are set to empty/disabled defaults. The packet is not sent for standard Bedrock clients.

**Testing showed:** Sending this packet (with all fields zeroed/empty) did NOT fix the disconnect. The core fix is the three StartGamePacket fields (Section 8). However, sending EducationSettingsPacket may be beneficial for full Education feature support (Code Builder, screenshots, etc.).

Fields (from CloudburstMC's `EducationSettingsSerializer_v465`):
- `codeBuilderUri` (string)
- `codeBuilderTitle` (string)
- `canResizeCodeBuilder` (boolean)
- `disableLegacyTitle` (boolean)
- `postProcessFilter` (string)
- `screenshotBorderPath` (string)
- `entityCapabilities` (OptionalBoolean — maps to Agent "Can Modify Blocks")
- `overrideUri` (Optional<String>)
- `quizAttached` (boolean)
- `externalLinkSettings` (OptionalBoolean)

---

## 13. Education Gamerules

Education gamerules are sent in **two places**:

### 1. Inside the StartGamePacket (level settings)

See Section 11 above. These gamerules are part of the StartGamePacket's gamerule list.

### 2. As a separate GameRulesChangedPacket

Sent during `sendDefaultGamerules()`, which runs after the StartGamePacket:

```java
if (educationClient) {
    gamerulePacket.getGameRules().add(new GameRuleData<>("allowdestructiveobjects", true));
    gamerulePacket.getGameRules().add(new GameRuleData<>("allowmobs", true));
    gamerulePacket.getGameRules().add(new GameRuleData<>("globalmute", false));
}
```

Note: `codebuilder` is only in the StartGamePacket, not in the separate gamerule packet.

---

## 14. Education-Specific Packet Types

The following packets are Education-specific and are blocked by Geyser's `CodecProcessor.java`. In the original Geyser codebase they use `ILLEGAL_SERIALIZER` (which throws `IllegalArgumentException`). During debugging, these were first changed to `IGNORED_SERIALIZER` (silently drops), then ultimately changed to keep their **original serializers** from CloudburstMC Protocol so the codec handles them normally:

**Serializer progression:** `ILLEGAL_SERIALIZER` (throws) → `IGNORED_SERIALIZER` (silently drops) → **original serializer** (handles normally).

| # | Packet Class | Purpose |
|---|-------------|---------|
| 1 | `PhotoTransferPacket` | Photo sharing between clients |
| 2 | `LabTablePacket` | Chemistry lab table interactions |
| 3 | `CodeBuilderSourcePacket` | Code Builder IDE integration |
| 4 | `CreatePhotoPacket` | In-game photography |
| 5 | `NpcRequestPacket` | NPC interaction (Education NPCs) |
| 6 | `PhotoInfoRequestPacket` | Photo metadata requests |
| 7 | `GameTestRequestPacket` | GameTest framework (if gametest experiment is enabled) |

**Fix:** Change from `ILLEGAL_SERIALIZER` to `IGNORED_SERIALIZER` in `CodecProcessor.java` (lines 256-261 for packets 1-6, and line 269 for GameTestRequestPacket). This prevents the server from crashing when Education clients send education-specific packets.

In `CodecProcessor.java`:
```java
.updateSerializer(PhotoTransferPacket.class, IGNORED_SERIALIZER)
.updateSerializer(LabTablePacket.class, IGNORED_SERIALIZER)
.updateSerializer(CodeBuilderSourcePacket.class, IGNORED_SERIALIZER)
.updateSerializer(CreatePhotoPacket.class, IGNORED_SERIALIZER)
.updateSerializer(NpcRequestPacket.class, IGNORED_SERIALIZER)
.updateSerializer(PhotoInfoRequestPacket.class, IGNORED_SERIALIZER)
// Line 269 (separate from the block above):
.updateSerializer(GameTestRequestPacket.class, IGNORED_SERIALIZER)
```

### Additional Education Packets (Not Blocked by Geyser)

These packets exist in the protocol and are NOT blocked by Geyser's `CodecProcessor`. They were identified from Mojang's protocol documentation:

| Packet | ID | Purpose |
|--------|-----|---------|
| `EducationSettingsPacket` | 137 | Sends `EducationLevelSettings` structure at game start (see Section 12) |
| `EduUriResourcePacket` | — | Education URI resource distribution |
| `CodeBuilderPacket` | — | Code Builder session control (distinct from `CodeBuilderSourcePacket`) |
| `LessonProgressPacket` | — | Tracks student lesson completion and progress |
| `AgentActionEventPacket` | — | Agent (Education robot) action events |
| `AgentAnimationPacket` | — | Agent animation events |

### Generic Type Helper for updateSerializer

`updateSerializer` has the signature `<T extends BedrockPacket> void updateSerializer(Class<T>, BedrockPacketSerializer<T>)`. When iterating over `Class<? extends BedrockPacket>[]`, the wildcard type `?` can't satisfy both `T` positions. A helper method captures `T` via an unchecked cast:

```java
private <T extends BedrockPacket> void restoreSerializer(
        BedrockCodec.Builder builder, BedrockCodec originalCodec,
        Class<? extends BedrockPacket> rawClass) {
    Class<T> packetClass = (Class<T>) rawClass; // unchecked but safe
    var definition = originalCodec.getPacketDefinition(packetClass);
    if (definition != null) {
        builder.updateSerializer(packetClass, definition.getSerializer());
    }
}
```

---

## 15. Block Breaking Differences

### Education Sends Different Action Types

The Education client uses different `PlayerActionType` values for block breaking compared to standard Bedrock:

| Action | Standard Bedrock | Education Edition |
|--------|-----------------|-------------------|
| Continue breaking | `BLOCK_CONTINUE_DESTROY` | `CONTINUE_BREAK` |
| Finish breaking | `BLOCK_PREDICT_DESTROY` | `STOP_BREAK` |
| Cancel breaking | `ABORT_BREAK` | `STOP_BREAK` |

The Education client reports `inputInteractionModel=TOUCH` even on Windows, likely because Education Edition defaults to touch input mode for classroom tablet compatibility.

Without handling, `CONTINUE_BREAK` causes the Java server to kick the player with "Invalid block breaking action received!"

### The Double-Duty STOP_BREAK Problem

**Critical difference:** Education uses `STOP_BREAK` for BOTH cancelling AND completing a block break. Standard Bedrock uses `BLOCK_PREDICT_DESTROY` for completion and `ABORT_BREAK` for cancellation.

Since `STOP_BREAK` can mean either "I finished breaking this block" or "I stopped breaking this block", we must distinguish them. The heuristic: if there is an active block being broken (`currentBlockPos != null`) and sufficient mining progress has accumulated (`currentProgress >= 0.65`), treat `STOP_BREAK` as a completed break. Otherwise, treat it as an abort.

### Education Block Action Packet Structure

When an Education client finishes breaking a block, it sends a `PlayerAuthInputPacket` containing:

```
inputData: [PERFORM_ITEM_INTERACTION, PERFORM_BLOCK_ACTIONS, BLOCK_BREAKING_DELAY_ENABLED]
itemUseTransaction: ItemUseTransaction(actionType=2, blockPosition=(...))
playerActions: [
    PlayerBlockActionData(action=STOP_BREAK, blockPosition=null, face=0),
    PlayerBlockActionData(action=CONTINUE_BREAK, blockPosition=(x, y, z), face=1)
]
```

Key observations:
- `STOP_BREAK` has `blockPosition=null` and `face=0`
- A `CONTINUE_BREAK` follows in the same packet with the actual block position
- `itemUseTransaction` with `actionType=2` (DESTROY) is also present but is NOT used for server-side block breaking in Geyser

### The Ghost Block Problem

Without proper handling, after `STOP_BREAK` destroys the block, the subsequent `CONTINUE_BREAK` in the same packet would:
1. Enter `handleContinueDestroy()`
2. Find `currentBlockState == null` (because `destroyBlock` called `clearCurrentVariables()`)
3. Fall into the `else` branch
4. Call `handleStartBreak()` on the same position — but the block there is now the one BEHIND the just-destroyed block
5. Start a break animation on the wrong block

**Fix:** After the education `STOP_BREAK` destroys a block, set `lastMinedPosition` to the destroyed position. The subsequent `CONTINUE_BREAK` then hits `testForLastBreakPosOrReset()` and is skipped.

```java
case ABORT_BREAK, STOP_BREAK -> {
    if (testForItemFrameEntity(position)) {
        continue;
    }

    if (actionData.getAction() == PlayerActionType.STOP_BREAK
            && session.isEducationClient()
            && currentBlockPos != null
            && currentBlockState != null
            && currentBlockFace != null
            && currentProgress >= 0.65F) {
        Vector3i minedPos = currentBlockPos;
        destroyBlock(currentBlockState, currentBlockPos, currentBlockFace, false);
        this.lastMinedPosition = minedPos;
        continue;
    }

    handleAbortBreaking(position);
}
```

### CONTINUE_BREAK Handling

`CONTINUE_BREAK` is already aliased with `BLOCK_CONTINUE_DESTROY` in the switch statement:

```java
case BLOCK_CONTINUE_DESTROY, CONTINUE_BREAK -> {
    // Education Edition sends CONTINUE_BREAK instead of BLOCK_CONTINUE_DESTROY
    ...
}
```

No special handling is needed beyond the alias.

---

## 16. Skin Handling [REVERTED]

> **NOTE: Simplified skin handling was removed after the core fix was found. Education 1.21.132 is built from the same source as Bedrock 1.21.130-1.21.132 and supports all the same skin features. Geyser already sends classic skins (not persona) — the simplified skins were an unnecessary defensive measure. Testing confirmed removal didn't break anything. See Section 57 for all reverted changes.**

For historical reference, the original implementation:

Education Edition uses the same skin format as standard Bedrock at the protocol level. However, Geyser sends skins with:
- Large custom `geo.json` geometry data
- `premium(true)` flag
- `overridingPlayerAppearance(true)` flag

Education clients receive simplified skins without custom geometry, capes, or premium flags. This prevents potential deserialization issues with Education's simpler skin system (which lacks the full Persona/Character Creator).

**File:** `SkinManager.java`

```java
if (session.isEducationClient()) {
    return SerializedSkin.builder()
        .skinId(skinId)
        .skinResourcePatch(geometry.geometryName())
        .skinData(ImageData.of(skin.skinData()))
        .capeData(ImageData.EMPTY)
        .geometryData("")
        .fullSkinId(skinId)
        .build();
}
```

The same simplified skin is used for skull skins in `SkullSkinManager.java`.

Just flat skin texture with standard geometry name (`geometry.humanoid.custom`).

**Important:** This simplification was done as a precautionary measure but is **NOT** the cause of the disconnect. The nuclear suppression test proved that no post-StartGame packet (including PlayerList where skin data lives) causes the disconnect. Skin data arrives via `PlayerListPacket` which is sent after `StartGamePacket`.

---

## 17. Movement Authority [REVERTED]

> **NOTE: Client-authoritative movement for Education clients was reverted. After the core fix, both Education and Bedrock clients use `AuthoritativeMovementMode.SERVER` with `serverAuthoritativeBlockBreaking=true`. The original CLIENT mode was a defensive hypothesis. See Section 57 for all reverted changes.**

For historical reference, the original implementation:

Education clients use **client-authoritative movement** instead of server-authoritative:

```java
if (educationClient) {
    startGamePacket.setAuthoritativeMovementMode(AuthoritativeMovementMode.CLIENT);
    startGamePacket.setRewindHistorySize(0);
    startGamePacket.setServerAuthoritativeBlockBreaking(false);
}
```

Server-authoritative movement causes issues with Education clients. `serverAuthoritativeBlockBreaking` must also be `false` for Education, as the client's block breaking action format differs from what the server-authoritative system expects.

---

## 18. P2P Join Code System (WebRTC)

### How Join Codes Work

When a player hosts a world in Education Edition:

1. **`POST /host`** to `discovery.minecrafteduservices.com` — returns `serverToken` and `passcode` (the join code as icon indices)
2. **WebSocket** opened to `signal-{region}.franchise.minecraft-services.net/ws/v1.0/signaling/{networkId}` with `MCToken` authorization
3. **`POST /update`** to `discovery.minecrafteduservices.com` — periodic keepalive with player count

When a student enters the join code, the signaling service brokers a **WebRTC data channel** connection using ICE/STUN/TURN — not a direct RakNet connection.

### Signaling Infrastructure

The actual P2P connection is brokered through:
- **Signaling:** WebSocket at `signal.franchise.minecraft-services.net`
- **STUN/TURN:** `turn.azure.com`, `world.relay.skype.com` on IP range `20.202.0.0/16`, TCP port 443, UDP ports 3478-3481
- **P2P:** Local ephemeral UDP ports specified by the host client

The join code system does NOT contain IP addresses. The signaling service uses the `networkId` to match host and joiner, then brokers a P2P connection via STUN/TURN.

### Captured Signaling Flow

The WebSocket receives:
- Server TURN credentials (`relay.communication.microsoft.com:3478`)
- `CONNECTREQUEST` with SDP offer (DTLS/SCTP webrtc-datachannel)
- `CANDIDATEADD` with ICE candidates (host, srflx, relay)

Both `transportType: 1` and `transportType: 2` in the `/host` request produce the same WebRTC negotiation. There is no transport type that produces direct IP connections via the join code system.

### Implication

Making a Geyser server joinable via join codes would require implementing a WebRTC-to-RakNet bridge — accepting WebRTC data channel connections brokered by the signaling service and bridging them to Geyser's RakNet port. This is a substantial engineering effort.

The `minecraftedu://` URI scheme remains the practical connection method for direct IP servers.

---

## 19. Dedicated Server System & Binary Analysis

### Download

The Education dedicated server is publicly downloadable. The admin portal at `education.minecraft.net/teachertools/en_US/dedicatedservers/` generates download links to:
```
https://downloads.minecrafteduservices.com/retailbuilds/PropsDS/MinecraftEducation_PropsDS_1.21.132.1.zip
```

**Official API documentation:** Microsoft published complete MESS (Minecraft Education Edition Server Services) API docs at `https://notebooks.minecrafteduservices.com/docs/DedicatedServerApiDocs.html`. A Jupyter Notebook tooling download is also available at `https://aka.ms/MCEDU-DS-Tooling`. See Section 38 for the complete API reference.

This contains only a configured `server.properties`. The actual server binaries (Windows and Linux) are available separately from the same portal:
- `MinecraftEducation_WinDS_1_21_132_1.zip` — Windows (`bedrock_server.exe`, 103MB)
- `MinecraftEducation_LinuxDS_1_21_132_1.zip` — Linux (`bedrock_server_edu`, 185MB, ELF x86-64)

### Binary Characteristics

- Windows: PE executable, stripped
- Linux: ELF 64-bit PIE, dynamically linked, stripped, uses libcurl for HTTPS
- Both contain cereal serialization schemas with human-readable field name strings
- Build path: `D:\a\_work\1\s\handheld\src-server\` (Windows build system)

### Key Strings Found in Binary

**Authentication flow:**
```
/organizations/oauth2/v2.0/devicecode
urn:ietf:params:oauth:grant-type:device_code
https://login.microsoftonline.com
oauth2/v2.0/token
TenantInfo::updateEntraToken
edu_server_session.json
b36b1432-1a1c-4c82-9b76-24de1cab42f2    (Minecraft Education client ID)
api://auth-minecraft-services/multiplayer
api://mc{}{}/.default                      (scope template)
grant_type={}&client_id={}&refresh_token={}
grant_type={}&client_id={}&device_code={}
client_id={}&scope={}&grant_type=client_credentials
```

**Session file fields:**
```
Json is missing required string field: server_id
Json is missing required string field: refresh_token
Json is missing required int field: expires_on
```

**Server hosting flow (`EduMultiplayerHeadlessHost`):**
```
HostServer
connectionInfo
transportType
transportInfo
Bearer
Authorization
Could not contact Services to host server
Server successfully hosted in tenant %s at IP:
Fetch Joiners
Fetch Server
UpdateServer
playerCount, maxPlayers, health
status, guestTenantStatuses, retryAfterSeconds
api-version
```

**Login/disconnect reasons:**
```
LoginFailed_InvalidTenant
LoginFailed_EditionMismatchEduToVanilla
LoginFailed_EditionMismatchVanillaToEdu
LoginFailed_ClientOld
LoginFailed_ServerOld
Dedicated Server is not enabled for owner tenant.
Cross-tenant is not enabled for owner tenant.
Cross-tenant is not enabled for this tenant.
```

**API endpoints (reconstructed from garbled binary strings):**
- `dedicatedserverprod.minecrafteduservices.com` — production Education services
- `dedicatedserverstaging.minecrafteduservices.com` — staging
- `dedserver.dev.mine...minecrafteduservices.com` — dev
- Paths: `server/fetch_token`, `server/fetch_server`, `server/fetch_joiners`, `server/refresh_token`
- Discovery: `https://client.discovery.minecraft-services.net/api/v1.0/discovery/`

**Default port:** 20202 (not standard Bedrock 19132)

### Linux Binary Supplementary Analysis

Key code locations found via cross-reference scanning of the Linux ELF binary:
- `signedToken` string referenced at vaddr `0x48dbf43` and `0x55a6214` (handshake JWT construction)
- `Successfully read server session info from disk` at vaddr `0x981a833` (session loading)
- `readServerCredentialsOnDisk` at file offset `0xfb50e1`

ELF section layout:
- `.text` at vaddr `0x36c2300` (file offset `0x36c1300`)
- `.rodata` at vaddr `0xd24280` (file offset `0xd24280`)
- `.data` at vaddr `0xb0f1590`

The binary was considered for LD_PRELOAD hooking (intercepting libcurl calls to inject auth tokens) but this approach was abandoned in favor of the proper MESS API integration.

### Education-Specific UUIDs Found

- `b36b1432-1a1c-4c82-9b76-24de1cab42f2` — Minecraft Education client app ID (used for OAuth2)
- `16556bfc-5102-43c9-a82a-3ea5e4810689` — Education services resource/audience
- `f8ba6a93-3dc8-4753-9f89-886138158d8b` — Teacher tools web app ID (admin portal)
- `975f013f-7f24-47e8-a7d3-abc4752bf346` — Unknown (possibly Azure safety/moderation service)
- `e29833d6-7ad0-4c5c-bf03-2b359707190a` — Unknown (dev environment)

---

## 20. Microsoft 365 Tenant System

- A tenant = one organization's Microsoft universe (school district, university)
- **Global Administrator** role required to register dedicated servers or enable the feature in the admin portal
- Regular users (teachers, students) cannot access admin settings
- All users with M365 Education licenses automatically have access to Minecraft Education Edition
- Education multiplayer is restricted to same tenant by default
- Cross-tenant requires dedicated server setup AND both tenants enabling cross-tenant
- Tenant ID found in JWT: `tid` field

### Token Scoping

The serverToken is scoped to a tenant. A token from tenant A will be rejected by clients from tenant B. There is no way to create a cross-tenant token — the P2P `/host` endpoint always generates a token scoped to the calling user's tenant.

This means: for school students to connect via P2P tokens, the token **must** come from someone in the school's M365 tenant (any student or teacher). Alternatively, the EducationAuthManager (Method 2 in Section 6) uses the dedicated server API which supports cross-tenant access.

### Cross-Tenant One-Sided Bypass

When a server's owning tenant enables `crossTenantAllowed` in tenant settings and on the specific server, clients from OTHER tenants can connect even if their tenant has NOT enabled cross-tenant.

Direct IP connections via the `minecraftedu://` URI scheme bypass the `client/join_server` endpoint entirely. And even through the server list, the cross-tenant check appears to be one-sided in practice — if the owning tenant enables cross-tenant and the server registration exists, connections from other tenants succeed.

**Implication:** A single commercial M365 Education license with Global Admin on your own tenant is sufficient to serve students from ANY school tenant. The school's admin doesn't need to enable or configure anything.

### Formal Cross-Tenant Invite Flow

For tenants that DO have admin cooperation:

**Owner tenant:**
1. `tooling/edit_tenant_settings` → enable `CrossTenantAllowed`
2. `tooling/edit_server_info` → enable `crossTenantAllowed` on server
3. `tooling/create_server_invite` → send invite with `guestTenantIds`

**Guest tenant:**
1. `tooling/edit_tenant_settings` → enable `CrossTenantAllowed` (requires their admin)
2. `tooling/accept_server_invite` → accept the invite (requires their admin)
3. Server appears in guest tenant's server list

### Partner Access System

Tenant admins can delegate limited management to other tenants via partner relationships configured in `tooling/edit_tenant_settings`. Two partner levels:
- `FullManagement` (0): Can view/edit all settings except partner permissions.
- `ESports` (1): Can view/edit all settings except partner permissions and teacher permissions.

Partners can manage guest server registrations that link to servers they own, but cannot manage the other tenant's own server registrations.

---

## 21. Packet Flow Analysis

### Complete Connection Sequence

Logged via instrumentation of `sendUpstreamPacket` and `sendUpstreamPacketImmediately` in Geyser:

```
Bedrock -> Server: RequestNetworkSettingsPacket
Server -> Bedrock (immediate): NetworkSettingsPacket
Bedrock -> Server: LoginPacket
  [Is player data signed? false]
Server -> Bedrock (immediate): ServerToClientHandshakePacket
  [edu-geyser: Education Edition client connected: NielsI (Tenant: , Role: 0)]
Server -> Bedrock: PlayStatusPacket
Server -> Bedrock: ResourcePacksInfoPacket
Bedrock -> Server: ClientToServerHandshakePacket
  [Could not find packet — harmless, no translator registered]
Bedrock -> Server: ClientCacheStatusPacket
  [Could not find packet — harmless]
Bedrock -> Server: ResourcePackClientResponsePacket
Server -> Bedrock: ResourcePackStackPacket
Bedrock -> Server: ResourcePackClientResponsePacket
  [Player connected with username NielsI (898)]
  [NielsI (logged in as: NielsI) has connected to the Java server]
  [Registering bedrock skin for NielsI]
  [server connection: NielsI -> lobby has connected]
Server -> Bedrock: StartGamePacket
  [Education flags: eduEditionOffers=1, eduFeaturesEnabled=true, educationProductionId=education]
Server -> Bedrock: LevelChunkPacket (multiple)
Server -> Bedrock: SetEntityDataPacket
Server -> Bedrock: GameRulesChangedPacket
Server -> Bedrock: SetTitlePacket (x2)
Server -> Bedrock: SetDifficultyPacket
Server -> Bedrock: UpdateAdventureSettingsPacket
Server -> Bedrock: UpdateAbilitiesPacket
Server -> Bedrock: PlayerHotbarPacket
Server -> Bedrock: TrimDataPacket
Server -> Bedrock: CraftingDataPacket (x2)
Server -> Bedrock: UpdateAdventureSettingsPacket
Server -> Bedrock: UpdateAbilitiesPacket
Server -> Bedrock: UnlockedRecipesPacket
Server -> Bedrock: RespawnPacket
Server -> Bedrock: MovePlayerPacket (x2)
Server -> Bedrock: SetEntityMotionPacket
Server -> Bedrock: NetworkChunkPublisherUpdatePacket
  [Spawned player at (-6.5, 93.0, -2.5)]
Server -> Bedrock: SetTimePacket
Server -> Bedrock: SetSpawnPositionPacket
Server -> Bedrock: InventorySlotPacket (x4)
Server -> Bedrock: InventoryContentPacket (x4)
Server -> Bedrock: TextPacket
Server -> Bedrock: AvailableCommandsPacket
Server -> Bedrock: UpdateAttributesPacket (x4)
Server -> Bedrock: SetEntityDataPacket
Server -> Bedrock: PlayerListPacket (x2)
Server -> Bedrock: LevelChunkPacket (additional)
Server -> Bedrock: AddEntityPacket
Server -> Bedrock: MobArmorEquipmentPacket
Server -> Bedrock: MobEquipmentPacket
Server -> Bedrock: MoveEntityDeltaPacket (multiple)
```

**Before the fix:** the sequence would end with:
```
  [NielsI has disconnected from the Java server because of Bedrock client disconnected]
```
at approximately 1-1.5 seconds after StartGamePacket.

**After the fix (Section 8):** the client remains connected and gameplay proceeds normally.

### Key Observations

1. **`ClientToServerHandshakePacket`** — "Could not find packet" is a debug message (line 76 in `PacketTranslatorRegistry.java`), not an error. No `PacketTranslator` is registered for this packet because it's a no-op acknowledgement. Harmless.

2. **`StartGamePacket`** — NOT logged by the standard `sendUpstreamPacket` instrumentation. It's sent directly via `upstream.sendPacket(startGamePacket)` at line 1883 of `GeyserSession.java`. Requires separate logging at that specific call site.

3. **No skin data before StartGamePacket** — The login flow (`LoginPacket → ServerToClientHandshakePacket → PlayStatusPacket → ResourcePacks`) contains zero skin information. First skin data arrives via `PlayerListPacket` after `StartGamePacket`.

4. **`GeyserSession.tick()`** bypasses packet suppression — Line 1380 calls `this.upstream.getSession().getPeer().sendPacketsImmediately()` directly on the `BedrockPeer`, bypassing any suppression layers added to `sendUpstreamPacket`.

5. **Standard Bedrock vs Education** — Standard Bedrock clients complete the exact same packet sequence and remain connected. The disconnect was Education-specific (resolved by the core fix in Section 8).

---

## 22. Client-Side Debugging (DLL Analysis)

### DLL Injection Setup

Education Edition is a Centennial/UWP app. DLL injection is possible using **Fate Injector**.

The Education binary is at the install location found via:
```powershell
Get-AppxPackage *education* | Select InstallLocation
```

### closesocket Hook

A DLL was created to hook `closesocket` from `ws2_32.dll` to capture disconnect call stacks.

**Findings:**
- Two `closesocket` calls, 10ms apart, same thread (47204), identical call stacks
- This is a single disconnect path closing two sockets (likely IPv4 + IPv6)

Key stack frames (rebased to `0x00007FF70B430000`):

| Frame | RVA | Role |
|-------|-----|------|
| 1 | `0x5CBA49E` | Direct `closesocket` caller |
| 2 | `0x5CDF232` | Socket cleanup orchestrator |
| 3 | `0x199554` | Dispatch/wrapper |
| 5 | `0x5CBE957` | RakNet/network layer |
| 6 | `0x28AF06E` | Disconnect decision — error evaluated here |
| 7 | `0x28BA6E6` | Connection state handler |
| 8 | `0x29E3C27` | Packet processor or tick handler |
| 9-15 | `0xAC-0xAF` range | Game loop / update chain |
| 16 | `0x1FA6563` | Higher-level game tick |
| 17-19 | `0x2D5-2D6` range | Main loop / thread entry |

### Disconnect Reason Analysis

**DisconnectFailReason `0x29` (41) = "Disconnected"**

This is the generic disconnect reason — not a specific error like "BadPacket" or "Timeout". The enum was mapped by cross-referencing the binary's enum registration function (at RVA `0x02935E40`) with the gophertunnel protocol library. There are 129 total enum values.

**Frame 6 (`RVA 0x28AEFA0`):** Hardcodes `mov edx, 0x29` then calls virtual disconnect function.

**Frame 8 (`RVA 0x29E3BA0`):** Checks a flag at `[this->field_0xD0->field_0x40]`. If non-zero, triggers disconnect.

### Disconnect Flag Analysis

- Flag was ALREADY `0x01` on first tick check — set during connection handshake/initialization, not during gameplay
- Disconnect fires 17ms after the tick function first sees the flag
- The flag object memory contained the string `persona_right_leg` nearby (coincidental memory layout, not causal)
- Frame at `0xADF9C0` (outer tick function) has related check: `cmp byte ptr [rbx + 0x414], 0`

This was consistent with the actual root cause: a deserialization failure in StartGamePacket processing that sets a disconnect flag immediately, before any gameplay packets are processed.

### DLL Patch Attempt

Attempted to patch out the disconnect check at `0x29E3C11` (make the `cmp byte ptr [rax], bpl; je skip` always skip). **This did NOT fix the disconnect** — there is at least one additional disconnect path that was not patched.

### Server Button DLL

A separate DLL (`ServerTabEnabler.dll`) successfully made the server list button visible in Education's UI, but the button's click handler is non-functional — the underlying server list code is not properly initialized.

**Version history:**

- **v1 (Trampoline):** Crashed — replaced a `PropertyVariant` object pointer with a raw bool pointer. The binding system tried to call virtual functions on it and crashed.
- **v2 (String corruption):** Changed `#edu_server_screen_enabled` to `!edu_server_screen_enabled` in `.rdata`. The UI queries the original name, finds no matching binding, defaults to `visible=true`. Button appeared but clicking did nothing — the C++ button handler still checked three guard conditions, all of which failed because server infrastructure was never initialized.
- **v3 (Corruption + handler patches):** Added NOP of the flag check JZ and replaced the server object null check with `MOV rcx, rdi` (controller pointer). Still non-functional — the screen name string was never written, and the CALL target expected a server object, not a controller pointer.

The button handler at `EDUPlayScreenController` (`RVA 0x140F060`) has three sequential guards that all must pass: a feature flag check at `[controller+0x111]`, a string-empty check at `[controller+0xC80]`, and a server object null check at `[controller+0xDF8]`. Patching individual guards doesn't work because the underlying data structures (screen name string, server object) are populated by the tick() initialization gate at `RVA 0x140E8A9`, which is never executed when the server feature is disabled at the tenant/config level.

See Section 41 and Appendix G for the full Windows PE binary analysis.

---

## 23. Files Modified (Geyser Fork)

### New Files

| File | Purpose |
|------|---------|
| `core/.../network/EducationStartGameSerializer.java` | Custom StartGamePacket serializer that appends 3 education strings |
| `core/.../network/EducationAuthManager.java` | Dedicated server auth lifecycle: device code flow, token management, host/update/dehost, session persistence (857 lines) |
| `core/.../command/defaults/EduCommand.java` | `/geyser edu` command with status, players, reset, register subcommands (181 lines) |

### Modified Files

| File | Changes |
|------|---------|
| `core/.../network/CodecProcessor.java` | Added `educationCodec()` method; removed `ILLEGAL_SERIALIZER` for 6 education packet types (now use original serializers) |
| `core/.../session/GeyserSession.java` | `educationClient` field; education flags in `startGame()`; codec swap + registry transfer; EducationSettingsPacket; education gamerules |
| `core/.../session/GeyserSessionAdapter.java` | Education UUID generation, XUID fallback to `"0"`, Floodgate data integration (tenantId, adRole) |
| `core/.../session/auth/BedrockClientData.java` | 6 education fields (`IsEduMode`, `TenantId`, `ADRole`, `EduJoinerToHostNonce`, `EduSessionToken`, `EduTokenChain`); `isEducationEdition()` method |
| `core/.../session/cache/BlockBreakHandler.java` | `CONTINUE_BREAK`/`STOP_BREAK` handling with progress-based disambiguation; `lastMinedPosition` ghost block prevention |
| `core/.../util/LoginEncryptionUtils.java` | Education detection before Xbox validation; EduTokenChain signature verification (MESS keys, ES384, rawToDer); education handshake with `signedToken` |
| `core/.../skin/SkinManager.java` | Simplified skins for education clients **(REVERTED — removed)** |
| `core/.../skin/SkullSkinManager.java` | Simplified skull skins **(REVERTED — removed)** |
| `core/.../network/netty/GeyserServer.java` | MCEE ping edition code (commented out, defaults to MCPE) |
| `core/.../network/UpstreamPacketHandler.java` | Debug logging for education handshake flow |
| `core/.../configuration/GeyserConfig.java` | 8 education config options |
| `core/.../command/CommandRegistry.java` | EduCommand registration |
| `core/.../GeyserImpl.java` | EducationAuthManager field, initialization in `startInstance()`, shutdown in `shutdown()` |

---

## 24. Configuration

### Geyser Configuration

`plugins/Geyser-Velocity/config.yml`:
```yaml
bedrock:
  port: 19132
auth-type: offline
validate-bedrock-login: false
debug-mode: true

# === Education Edition Configuration ===

# Legacy: static signed token from discovery service.
# Leave empty to use the new EducationAuthManager system instead.
education-token: ""

# Education Edition dedicated server ID from the admin portal.
# If empty and edu-server-name is set, Geyser will auto-register a new server.
edu-server-id: ""

# Public IP:port for Education Edition server registration.
# Auto-detected if empty. Recommended to set manually (auto-detection may fail behind NAT).
edu-server-ip: ""

# Display name for the Education Edition server list.
# Setting this enables the EducationAuthManager system (device code flow, MESS integration).
edu-server-name: ""

# Maximum number of Education Edition players shown in server list.
edu-max-players: 40

# Authentication mode: "verified" or "permissive"
# verified = require valid EduTokenChain (currently bypassed due to MESS key rotation)
# permissive = allow without verification
edu-auth-mode: "verified"

# Whether to use the player's real Microsoft 365 display name.
edu-use-real-names: true

# Whether to log tenant ID and AD role on connect.
edu-log-tenant: true
```

### Two Authentication Modes

- **`education-token`** (static) — Manual token from MESS/discovery service. Simpler but requires manual refresh when token expires.
- **`edu-server-name`/`edu-server-id`** (dynamic) — Full EducationAuthManager flow with device code auth, automatic registration, token refresh, server list integration, and heartbeat. See Section 51.

### Required Config Changes for Education Support

1. **`education-token`** or **`edu-server-name`**: At least one must be set to enable Education support.
2. **`validate-bedrock-login`**: The fork code explicitly bypasses Xbox validation for Education clients regardless of this setting — the conditional checks `!result.signed() && !isEducationClient && validateBedrockLogin`. So technically this setting only affects non-edu clients. However, it should generally be `false` when supporting Education clients for compatibility.

### Velocity Configuration

`velocity.toml`:
```toml
online-mode = false
```

**Note:** This disables Java authentication for all players. Use a plugin like SemiAuth for production.

---

## 25. Development Environment & Build

### Building the Geyser Fork

- Clone Geyser, make changes to `LoginEncryptionUtils.java`, `GeyserSession.java`, `CodecProcessor.java`, `BedrockClientData.java`, `BlockBreakHandler.java`, `SkinManager.java`, `SkullSkinManager.java`, `GeyserConfig.java`
- Add `EducationStartGameSerializer.java`
- Build with `./gradlew build` (or `./gradlew build -x test`)
- Output: `bootstrap/velocity/build/libs/Geyser-Velocity.jar`
- Deploy velocity bootstrap jar to server

### Build System Details

- Build tool: Gradle (Kotlin DSL)
- Java version: 17
- Geyser API dependency: `compileOnly`, version 2.8.3-SNAPSHOT / 2.9.0
- Protocol library repository: `https://repo.opencollab.dev/main/`
- Output: Platform-specific JARs in `bootstrap/*/build/libs/`

### Exact Dependency Versions

| Dependency | Version | Purpose |
|-----------|---------|---------|
| `org.geysermc.geyser:api` | 2.9.0-SNAPSHOT | Geyser Extension API |
| `org.geysermc.geyser:core` | 2.9.0-SNAPSHOT | Internal class access (reflection targets) |
| `org.cloudburstmc.protocol:bedrock-codec` | 3.0.0.Beta12-20260129.225905-2 | Packet types, codec manipulation |
| `org.cloudburstmc.protocol:bedrock-connection` | 3.0.0.Beta12-20260129.225905-2 | BedrockPeer, BedrockServerSession |
| `jose4j` | 0.9.6 | JWT creation (on classpath via Protocol) |

### Debug Tools Used

- **Fiddler Classic** — HTTPS traffic capture (requires CheckNetIsolation for UWP)
- **Wireshark** — packet capture (limited usefulness due to encryption)
- **Fate Injector** — DLL injection into Education Edition (UWP app)
- **Custom DLL** — closesocket hook for disconnect analysis
- **Geyser packet logging** — added to `sendUpstreamPacket` and `sendUpstreamPacketImmediately`
- **Education dedicated server binary** — `strings` analysis for field discovery
- **CloudburstMC Protocol source** — `StartGameSerializer_v898.java` and parent classes for field ordering

### CheckNetIsolation Command
```
CheckNetIsolation LoopbackExempt -a -n="Microsoft.MinecraftEducationEdition_8wekyb3d8bbwe"
```

---

## 26. Debug Logging

The following debug log tags are present in the codebase. These should be removed for production use:

| Tag | Location | Purpose |
|-----|----------|---------|
| `[EduTrace]` | `UpstreamPacketHandler.java` | Traces every Bedrock packet received |
| `[EduDetect]` | `LoginEncryptionUtils.java` | Logs auth payload type and education detection result |
| `[EduHandshake]` | `LoginEncryptionUtils.java`, `UpstreamPacketHandler.java` | Traces handshake JWT construction, sending, and encryption |
| `[EduSerializer]` | `EducationStartGameSerializer.java` | Logs when serialize/writeLevelSettings are called |
| `[edu-geyser]` | `AuthHandler.java` (extension) | Logs Education client connections with tenant info |
| Various `geyser.getLogger().info(...)` | `GeyserSession.java` | Logs education flags, gamerules, experiments, movement mode |

---

## 27. Packet Processing Order

The order in which Education-related packets and configurations are sent is critical. Here is the exact sequence:

```
Login phase:
  1. LoginPacket received
  2. Client data JWT parsed → educationClient = true
  3. ServerToClientHandshakePacket sent (with signedToken)
  4. Encryption enabled
  5. ClientToServerHandshakePacket received
  6. PlayStatusPacket (LOGIN_SUCCESS) sent
  7. ResourcePacksInfoPacket sent (packs=0, forced=false)
  8. ResourcePackClientResponsePacket received

Game start phase:
  9. startGame() called
  10. StartGamePacket constructed with education fields
  11. Education codec set permanently (with registry transfer)
  12. StartGamePacket sent (3 extra strings appended)
  13. EducationSettingsPacket sent
  14. sendDefaultGamerules() called (with education gamerules)
  15. Player spawns
```

The EducationSettingsPacket MUST come after StartGamePacket. The education gamerules in the separate `GameRulesChangedPacket` come after both. The codec swap MUST happen before the StartGamePacket is sent (but after registries are configured).

---

## 28. Companion Geyser Extension [OUTDATED]

> **NOTE: The companion extension has been merged into the Geyser fork entirely. This section is retained for historical reference only. The extension is no longer needed as a separate component.**

A companion Geyser extension (`EduGeyserExtension`, `EduGeyser-1.0.0-SNAPSHOT.jar`, 8.7 KB) was originally placed in `plugins/Geyser-Velocity/extensions/`.

Extension descriptor (`extension.yml`):
```yaml
id: edu-geyser
name: EduGeyser
main: <main class>
api: 2.9.0
version: 1.0.0
```

### Extension Components

| Class | Purpose |
|-------|---------|
| `EduGeyserExtension` | Main extension class; registered handlers on `GeyserPostInitializeEvent` |
| `AuthHandler` | Subscribed to `SessionInitializeEvent`; parsed the client data JWT to extract and log `IsEduMode`, `TenantId`, `ADRole` for Education clients |
| `CodecHandler` | Re-enabled the 6 Education packet serializers by restoring them from `Bedrock_v898.CODEC` via reflection |
| `PingHandler` | Changed RakNet ping edition from `MCPE` to `MCEE` (existed but was **not registered**) |

### AuthHandler: JWT Parsing Approach

The extension's `AuthHandler` independently parsed the client data JWT by Base64-decoding the payload section and extracting JSON fields:

```java
String[] parts = jwt.split("\\.");
String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
JsonObject payload = JsonParser.parseString(payloadJson).getAsJsonObject();

boolean isEduMode = payload.has("IsEduMode") && payload.get("IsEduMode").getAsBoolean();
String tenantId = payload.has("TenantId") ? payload.get("TenantId").getAsString() : "";
String adRole = payload.has("ADRole") ? payload.get("ADRole").getAsString() : "";
```

This confirmed that `IsEduMode`, `TenantId`, and `ADRole` are present in the client data JWT payload. However, `TenantId` consistently logged as empty string in testing, suggesting it may be in a different JWT in the chain or may require a different parsing approach.

### CodecHandler: Re-enabling Education Packets via Reflection

The extension accessed Geyser internals via reflection:

```java
Field codecsField = GameProtocol.class.getDeclaredField("SUPPORTED_BEDROCK_CODECS");
codecsField.setAccessible(true);
List<BedrockCodec> codecs = (List<BedrockCodec>) codecsField.get(null);
```

For each codec, it retrieved the original serializer from `Bedrock_v898.CODEC` and replaced the `IGNORED_SERIALIZER`:

```java
var definition = originalCodec.getPacketDefinition(packetClass);
builder.updateSerializer(packetClass, definition.getSerializer());
```

### PingHandler: MCEE Edition String

The `PingHandler` class existed but was **not registered** in `EduGeyserExtension.onPostInitialize()`. It was designed to change the RakNet ping response edition from `MCPE` to `MCEE` using reflection on `GeyserBedrockPingEventImpl`:

```java
BedrockPong pong = (BedrockPong) pongField.get(event);
pong.edition("MCEE");
```

This is **not needed** when clients connect via the `minecraftedu://connect` URI scheme, which bypasses server discovery entirely. The MCEE edition string is only relevant for LAN discovery / server list display where Education clients filter by edition.

**Warning:** Setting the edition string to `MCEE` in the ping response breaks standard Bedrock clients — they cannot connect when the edition is `MCEE`. Options:
- Run separate Geyser instances for Bedrock and Education on different ports
- Make the handler conditional (difficult since ping is broadcast, not per-client)
- Remove the handler entirely — the URI scheme forces direct connection regardless of ping edition

> **OUTDATED: Extension config location.** The extension previously stored its token in `plugins/Geyser-Velocity/extensions/edu-geyser/config.properties` as `signed-token=<token>`. This is no longer applicable — the token is now in Geyser's main `config.yml` as `education-token` (see Section 24).

### Alternative Approaches Considered (All Rejected)

Before settling on the fork approach, these alternatives were evaluated:

| Approach | Why It Failed |
|----------|--------------|
| **Extension-only (Netty interception)** | Required duplicating ~140 lines of Geyser internal code, intercepting `ServerBootstrapAcceptor` via reflection, replacing packet handlers via reflection on `BedrockPeer.sessions`, accessing 5+ private fields via cached `Field` objects. Fragile and breaks with any Geyser refactor. |
| **ByteBuddy / ASM bytecode manipulation** | Could intercept `startEncryptionHandshake` at the bytecode level, but requires Java agent (`-javaagent`) or `ByteBuddyAgent.install()` which is JVM-specific. |
| **Netty outbound handler to modify JWT in flight** | Cannot re-sign the JWT without the server's private key, which is a local variable inside `startEncryptionHandshake`. |
| **`java.lang.reflect.Proxy` on `BedrockPacketHandler`** | Would work (it's an interface), but has the same code duplication issue — `handlePacket` dispatch must go through the proxy while individual `handle(XxxPacket)` calls must delegate to the original. |
| **Subclassing `UpstreamPacketHandler`** | Constructor creates a new `CompressionStrategy` and event loop state — can't create a "fresh" handler and swap it in because the private fields would be at their initial values. |
| **Pre-emptive encryption before `LoginPacket` handler** | Not possible because the client's public key (needed for ECDH) is only available from the `LoginPacket` payload. |

The extension-only prototype compiled successfully but was abandoned because the ~20-line fork patch is orders of magnitude more maintainable than ~500 lines of reflection-heavy extension code.

---

## 29. Debugging History: Eliminated Theories

This section documents the investigation process to prevent others from repeating dead ends.

### Eliminated Causes

| # | Theory | Test | Result |
|---|--------|------|--------|
| 1 | Protocol translation needed | Checked Geyser source | ❌ Geyser supports protocol 898 natively |
| 2 | Block/item palette differences | Examined palette files | ❌ Palettes identical, Education items in Bedrock palette |
| 3 | Missing Education experiments | Added `chemistry` experiment | ❌ No change |
| 4 | Education flags in StartGamePacket | Toggled on/off | ❌ No change either way |
| 5 | Specific post-StartGame packet | Nuclear suppression (ALL packets after StartGame) | ❌ Still disconnects at same timing |
| 6 | Entity packets crashing client | Empty void world, zero entities | ❌ Still disconnects |
| 7 | CraftingDataPacket | Suppressed | ❌ No change |
| 8 | AvailableCommandsPacket | Suppressed | ❌ No change |
| 9 | UnlockedRecipesPacket | Suppressed | ❌ No change |
| 10 | BiomeDefinitionListPacket | Suppressed | ❌ No change |
| 11 | Movement mode | Tried client-auth and server-auth | ❌ No change |
| 12 | Persona/skin data | Stripped all persona data | ❌ No skin data sent before disconnect flag is set |
| 13 | Safety service phone-home | Blocked `safety-secondary.franchise.minecraft-services.net` via hosts | ❌ No change |
| 14 | Education packet codec crashes | Changed ILLEGAL to IGNORED serializer | ❌ No change to disconnect (but still correct fix) |
| 15 | `vanillaVersion: "*"` | Changed to `"1.21.130"` or `"1.21.132"` | ❌ No change |
| 16 | Empty gamerules array | Added `codebuilder`, `allowdestructiveobjects`, `allowmobs`, `globalmute` | ❌ No change |
| 17 | Missing EducationSettingsPacket | Sent with zeroed fields | ❌ No change |
| 18 | `currentTick: -1` | Changed to positive value | ❌ No change |
| 19 | Token format (P2P vs dedicated) | P2P token works identically | ❌ Not the cause |
| 20 | Self-token (same user hosting and connecting) | Tested | ❌ Not the cause |
| 21 | DLL-patched disconnect flag | Patched out the check | ❌ Another disconnect path exists |
| 22 | Encryption order | Fixed to send-then-encrypt | ✅ Fixed handshake, but disconnect persisted |
| 23 | Packet leaks via tick() | Found and fixed leaking packets | ✅ Reduced inconsistency |
| 24 | **Education StartGamePacket wire format** | **3 extra string fields in LevelSettings** | ✅ **ROOT CAUSE — FIXED** |

### Key Diagnostic: Nuclear Suppression Test

Suppressing ALL packets after StartGame caused the same disconnect at the same timing (1-1.5 seconds). This definitively proved:
1. The problem is in or before StartGamePacket (not any subsequent packet)
2. No post-StartGame packet suppression or modification could fix it
3. The problem is structural, not value-based

The nuclear suppression test was initially misinterpreted as proving the StartGamePacket was accepted (since it didn't cause an immediate error). In reality, the StartGamePacket was being processed, but its binary deserialization was failing silently — the client set a disconnect flag during the parsing, then disconnected on the next tick cycle 1-1.5 seconds later.

### Inconsistent Behavior Phase

During one testing phase, the client exhibited inconsistent disconnects:
- Different error messages each attempt
- Eventually a full client crash
- This was traced to **packet leaks** through `GeyserSession.tick()` bypassing suppression
- After fixing leaks, behavior became more consistent

### Confirmed via Delay Test

A 10-second delay was inserted between `ResourcePackClientResponsePacket` and `StartGamePacket`. **The client does NOT disconnect during the delay.** This confirmed the problem is in or after the `StartGamePacket`, not in the pre-StartGame handshake phase.

### DLL Analysis Results

The `closesocket` hook on the Education client revealed:
- Disconnect reason `0x29` (41) = generic "Disconnected"
- A flag at `[this->field_0xD0->field_0x40]` was ALREADY set on the first tick check — set during connection initialization, not during gameplay
- `persona_right_leg` string appeared nearby in memory (coincidental memory layout, not causal)
- Patching out the disconnect check at the flag did NOT fix it — another disconnect path existed

This was consistent with the actual root cause: a deserialization failure in StartGamePacket processing that sets a disconnect flag immediately, before any gameplay packets are processed.

### Original "Blocking Issue" Hypotheses [OUTDATED]

> **NOTE: These hypotheses were documented before the root cause was discovered. All were incorrect. They are retained here to show the investigation path and prevent others from pursuing them.**

Before the three extra Education fields were discovered, the leading suspects were:

1. **`vanillaVersion` field set to `"*"`**: Theory was that Education's C++ parser would fail on a non-numeric version string. Testing showed no difference when set to `"1.21.132"` — confirmed not the cause.
2. **Empty gamerules array**: Theory was Education required `allowdestructiveobjects`, `allowmobs`, `globalmute` directly in the StartGamePacket. Adding them made no difference — confirmed not the cause.
3. **`eduSharedUriResource`**: Unknown what Education expected for this field. Turned out to be irrelevant — the disconnect was structural, not value-based.
4. **Persona/skin system incompatibility**: Education lacks the full Character Creator. However, no skin data is sent before the disconnect flag is set — confirmed not the cause.

---

## 30. Full StartGamePacket Dump (Before Fix)

This was the StartGamePacket Geyser sent before the serializer fix. All field values are reasonable; the disconnect was caused by missing structural fields, not incorrect values.

```
uniqueEntityId: 3
runtimeEntityId: 3
playerGameType: SURVIVAL
playerPosition: (0.0, 69.0, 0.0)
rotation: (1.0, 1.0)
seed: -1
dimensionId: 0
generatorId: 1
levelGameType: SURVIVAL
difficulty: 1
defaultSpawn: (0, 0, 0)
achievementsDisabled: true
currentTick: -1
eduEditionOffers: 1
eduFeaturesEnabled: true
educationProductionId: education
rainLevel: 0.0
lightningLevel: 0.0
multiplayerGame: true
broadcastingToLan: true
platformBroadcastMode: PUBLIC
xblBroadcastMode: PUBLIC
commandsEnabled: true
texturePacksRequired: false
bonusChestEnabled: false
startingWithMap: false
trustingPlayers: true
defaultPlayerPermission: MEMBER
serverChunkTickRange: 4
behaviorPackLocked: false
resourcePackLocked: false
fromLockedWorldTemplate: false
usingMsaGamertagsOnly: false
fromWorldTemplate: false
worldTemplateOptionLocked: false
spawnBiomeType: DEFAULT
customBiomeName:
forceExperimentalGameplay: OptionalBoolean.empty
levelId: Geyser
levelName: Geyser
premiumWorldTemplateId: 00000000-0000-0000-0000-000000000000
enchantmentSeed: 0
multiplayerCorrelationId:
vanillaVersion: *
inventoriesServerAuthoritative: true
serverEngine:
playerPropertyData: {}
worldTemplateId: 28eff3c1-789e-490a-a074-08152b91547c
chatRestrictionLevel: NONE
authoritativeMovementMode: CLIENT
rewindHistorySize: 0
serverAuthoritativeBlockBreaking: false
serverId:
worldId:
scenarioId:
ownerId:
gamerules: []

Experiments:
  data_driven_items = true
  upcoming_creator_features = true
  experimental_molang_features = true
  chemistry = true
  gametest = true
```

---

## 31. Common Mistakes & Pitfalls

### Encryption Order

**Wrong (breaks CloudburstMC library):**
```java
session.enableEncryption(key);           // Encryption ON
session.sendPacketImmediately(handshake); // Sent encrypted — client can't read it
```

**Correct:**
```java
session.sendPacketImmediately(handshake); // Sent unencrypted — client reads salt
session.enableEncryption(key);           // THEN encryption ON
```

**Historical note:** bundabrg's original code used the "wrong" order, which worked with the older Nukkitx library that deferred encryption. The CloudburstMC library applies it immediately.

### Token Source

The old endpoint `meeservices.azurewebsites.net/v2/signin` no longer exists (DNS fails). Use `discovery.minecrafteduservices.com/host`.

The OAuth2 OOB redirect URI (`urn:ietf:wg:oauth:2.0:oob`) also no longer works for this client ID. Obtain Bearer tokens via Fiddler capture instead.

### MCEE Ping Breaking Bedrock

Setting the edition string to `MCEE` in the ping response breaks standard Bedrock clients. Either:
- Run separate instances
- Remove the PingHandler (URI scheme bypasses ping anyway)
- Make it conditional (difficult at the ping level)

### Packet Suppression Leaks

`GeyserSession.tick()` sends packets directly through `BedrockPeer.sendPacketsImmediately()`, bypassing any suppression added to `sendUpstreamPacket`. Any debugging via packet suppression must also account for this path.

### StartGamePacket Logging

The `StartGamePacket` is sent via `upstream.sendPacket()` (line 1883 in `GeyserSession.java`), NOT through `sendUpstreamPacket()`. Adding logging to `sendUpstreamPacket` will NOT capture it. Log directly at the call site.

### PowerShell curl Alias

PowerShell aliases `curl` to `Invoke-WebRequest`, which has different syntax from real curl. Use `Invoke-RestMethod` for API calls:
```powershell
$response = Invoke-RestMethod -Method Post -Uri "URL" -Headers @{...} -ContentType "application/json" -Body '...'
```

### UWP Traffic Capture

Education Edition is a Centennial/UWP app. Traffic is NOT visible to Wireshark or Fiddler by default. Requires:
```
CheckNetIsolation LoopbackExempt -a -n="Microsoft.MinecraftEducationEdition_8wekyb3d8bbwe"
```
This exemption may be lost after reboots or updates — re-run if Fiddler stops capturing.

**Critical: WinHTTP vs WinINET.** Education Edition uses `libhttpclient` which relies on **WinHTTP**, NOT WinINET. Setting only the WinINET registry proxy (what most proxy tools do by default) does NOT capture Education Edition traffic. Must also set WinHTTP proxy:
```
netsh winhttp set proxy 127.0.0.1:8877
```
Reset with:
```
netsh winhttp reset proxy
```

### Token Expiration

- Bearer tokens expire after ~75-90 minutes
- Server tokens (signedToken) expire after ~2 weeks based on embedded timestamp
- Token must be refreshed before expiration or the connection will be rejected

### Visual Studio Debugger Limitations

Attaching VS debugger to the Education process with all exception types enabled produced no results. The client handles errors gracefully — it catches its own exceptions and disconnects cleanly rather than crashing. The `closesocket` hook approach was more effective.

### Temporary Codec Swaps Do Not Work

Due to Netty's asynchronous encoding pipeline, you cannot temporarily swap the codec around a `sendUpstreamPacket()` call. The encoding happens later on the event loop thread, by which time the codec is already restored. Even `sendUpstreamPacketImmediately()` (`channel.writeAndFlush()`) is not synchronous when called from a non-event-loop thread. The codec must be set permanently (see Section 9).

### setCodec() Wipes Codec Helper Registries

Calling `setCodec()` on a Bedrock session creates a fresh `BedrockCodecHelper` with no registries. All item/block/camera registries must be re-applied manually. Without this, `NullPointerException` crashes occur at unpredictable times when packets reference items (see Section 10).

### Global Config-Based Education Toggle Breaks Mixed Servers

Using the config to determine education mode means ALL clients get education treatment or NONE do. Per-client detection via `IsEduMode` in the client data JWT is required for servers that accept both Education and standard Bedrock clients (see Section 5).

### TitleId Detection Is Unreliable

The `TitleId` field in client data was considered for detection but rejected because:
- The actual Education TitleId is not well-documented
- It may change between versions
- Hardcoding it is fragile

### Education Identity Claims Are NOT in rawIdentityClaims

`IsEduMode`, `TenantId`, and `ADRole` are NOT found in `result.rawIdentityClaims()` (the identity chain claims from `EncryptionUtils.validatePayload()`). They are only in the **client data JWT** parsed by `BedrockClientData`.

### Block Breaking: STOP_BREAK Has Dual Meaning

`STOP_BREAK` means both "cancel" and "complete" for Education clients. Without progress-based disambiguation, blocks either never break server-side (ghost blocks) or break incorrectly (see Section 15).

### Block Breaking: Ghost Animation After Destruction

After destroying a block via `STOP_BREAK`, the `CONTINUE_BREAK` that follows in the same packet must be suppressed by setting `lastMinedPosition`. Without this, a break animation starts on the block behind the just-destroyed one (see Section 15).

### Packet Suppression Is Not Needed in Production

Early debugging used aggressive packet suppression (blocking all packets after StartGamePacket). This was a debugging technique, not a solution. All suppression should be completely disabled (`EDU_TEST_GROUP = 7` or removed entirely) for normal operation.

---

## 32. Remaining Known Issues

1. **MESS key rotation (CRITICAL)** — The MESS signing key for EduTokenChain JWTs rotates frequently — observed changing between connection attempts within minutes. Hardcoding keys is not viable long-term. EduTokenChain verification is currently bypassed (logs warnings but allows connections). Potential fixes: discover if Microsoft publishes a JWKS endpoint, verify the x5u key chain of trust, or accept the x5u key and verify the JWT signature with it.

2. **TenantId is NULL (CRITICAL)** — The `TenantId` field from `BedrockClientData` is always null for Education clients. The real tenant ID is only in the `EduTokenChain` payload. This means all edu players currently get the same 4-char tenant hash suffix, and UUID generation only differentiates by username, not by tenant. Collision prevention between tenants is effectively disabled.

3. **Known bug: display name logic** — Line 208 of `LoginEncryptionUtils.java` checks `result.signed()` which is always false for edu clients, so `edu-use-real-names` never activates. Should check EduTokenChain verification status instead.

4. **Ghost block animation (survival, cosmetic)** — When breaking blocks in survival, there's a brief visual flash of the block behind before the `lastMinedPosition` check catches it. This is cosmetic only — no actual block is broken.

5. **Double break in creative** — In creative mode with instamine, the `CONTINUE_BREAK` after `STOP_BREAK` could potentially trigger a second break. The `lastMinedPosition` fix addresses this, but edge cases may remain.

6. **Tooling API returns 401** — The `/tooling/edit_server_info` and `/tooling/edit_tenant_settings` endpoints reject the Education client app ID token. They require the teacher tools app ID (`f8ba6a93-...`) which cannot be used in the device code flow. The `tryEditServerInfo()` method is best-effort — warns on failure and prints instructions for manual setup via the admin portal.

7. **`server/token_refresh` returns 404** — The MESS `/server/token_refresh` endpoint consistently returns 404 and does not work. Token refresh is done by refreshing the Entra access token via standard OAuth refresh_token grant, then calling `/server/fetch_token` with the new access token.

8. **Health value 3 rejected** — The MESS API documentation claims health values 0-3, but testing revealed the API rejects `health=3`. Accepted values are **-1 to 2**. The implementation uses `health=2`.

9. **`Is player data signed? false`** — Education client chains are not signed by Mojang's root key. This is expected. Security relies on EduTokenChain verification (currently bypassed — see issue #1).

10. **Floodgate locale files** — Building Floodgate from source may not include locale `.properties` files in the jar, causing `LanguageManager` NPE on startup.

---

## 33. Prior Art

### bundabrg's Projects (2020-2022, Abandoned)

| Project | URL | Purpose |
|---------|-----|---------|
| GeyserReversion | [GitHub](https://github.com/bundabrg/GeyserReversion) | Geyser extension for multi-version + Education support |
| Reversion | [GitHub](https://github.com/bundabrg/Reversion) | Protocol translation library |
| Geyser fork | [GitHub](https://github.com/bundabrg/Geyser) branch `feature/mcee` | Education Edition Geyser implementation |
| GeyserToken | [GitHub](https://github.com/bundabrg/GeyserToken) | Python tool for OAuth2 token retrieval |
| Geyser PR #536 | [GitHub](https://github.com/GeyserMC/Geyser/pull/536) | Original implementation details and discussion |
| Education docs | [Site](https://bundabrg.github.io/GeyserReversion/education/) | Token generation instructions |

**Key details from bundabrg:**
- Extension ran EducationServer on port 19133 (separate from Bedrock on 19132)
- Registered version translators like `Translator_v390ee_to_v408be`
- Required `tokens.yml` with refresh tokens per tenant
- Tokens expired after ~2 weeks, auto-renewed via refresh token
- Had separate education mappings submodule
- Abandoned in July 2022 because Geyser's extension interface was unstable

**What bundabrg Did:**

His approach was fundamentally different — at protocol v363-v392 (Education 1.12-1.14 era), Education had a genuinely different StartGamePacket wire format with different field layouts. He wrote complete custom serializers for Education:

**Education-specific serializers:**
- `StartGameSerializer_v363` / `v390` / `v392` — different byte layout for LevelSettings, including extra "Unknown" bytes (`buffer.writeByte(2)` and `buffer.writeByte(0)`) not present in standard Bedrock
- `PlayerListSerializer_v363` — dramatically simpler skin format (no persona data)
- `InventoryTransactionSerializer_v390` — different inventory source reading
- `CraftingDataSerializer_v390` — Education chemistry recipe types
- `EventSerializer_v390` — different event type handling

**Education-specific packet helper:**
- `EducationPacketHelper_v363` / `v390` / `v391` — different entity data IDs, command parameters, level events, sound events

**StartGamePacket handler:**
```java
// The only application-level change for Education StartGame:
packet.getGamerules().add(new GameRuleData<>("codebuilder", false));
```

**Authentication:**
- Used OAuth2 flow with client ID `b36b1432-1a1c-4c82-9b76-24de1cab42f2` and resource `https://meeservices.minecraft.net`
- Called `https://meeservices.azurewebsites.net/v2/signin` (now defunct)
- `signedToken` placed in handshake JWT claims before signing

**Key differences from current EduGeyser approach:**
- bundabrg needed full protocol translation because Education and Bedrock had different wire formats
- Current v898: protocols have converged, but Education has 3 extra fields in LevelSettings
- bundabrg ran a separate EducationServer on port 19133
- Current: single Geyser instance with Education serializer registered per-client

### Why bundabrg Abandoned

The Geyser extension API he relied on was not accepted upstream. Maintaining protocol updates across two divergent codebases became unsustainable. He planned to build a standalone proxy instead but never completed it.

### oryxel1's Projects

| Project | URL | Purpose |
|---------|-----|---------|
| GeyserReversion | [Modrinth](https://modrinth.com/mod/geyserreversion) | Active multi-version Geyser extension (no Education support) |
| Ouranos fork | [GitHub](https://github.com/oryxel1/ouranos) | Bedrock protocol translation library |

### Other Related Tools

| Project | URL | Purpose |
|---------|-----|---------|
| Blackjack200/Ouranos | [GitHub](https://github.com/Blackjack200/Ouranos) | Standalone Bedrock protocol proxy |
| MCXboxBroadcast | [GitHub](https://github.com/MCXboxBroadcast/Broadcaster) | Xbox Live session broadcaster for Geyser |
| BedrockConnect | [GitHub](https://github.com/Pugmatt/BedrockConnect) | DNS-based server list for consoles |
| Kas-tle/ProxyPass | [GitHub](https://github.com/Kas-tle/ProxyPass) | Bedrock MITM proxy with releases |
| CloudburstMC/ProxyPass | [GitHub](https://github.com/CloudburstMC/ProxyPass) | Original Bedrock MITM proxy (no releases) |

---

## 34. API Endpoints & Services

### Active Endpoints

**MESS (Minecraft Education Edition Server Services) base URL:** `https://dedicatedserver.minecrafteduservices.com`

**Note:** `teachertools.minecrafteduservices.com` is a frontend wrapper. The underlying API at `dedicatedserver.minecrafteduservices.com` is what the server binary and programmatic access calls directly. See Section 38 for the complete MESS API reference.

| Endpoint | Purpose | Auth |
|----------|---------|------|
| `discovery.minecrafteduservices.com/host` | P2P session registration, token acquisition | Bearer JWT |
| `dedicatedserver.minecrafteduservices.com/server/*` | Dedicated server lifecycle (register, host, update, dehost, token refresh) | Bearer JWT or MESS Server Token |
| `dedicatedserver.minecrafteduservices.com/client/*` | Client operations (check access, fetch servers, join) | Bearer JWT |
| `dedicatedserver.minecrafteduservices.com/tooling/*` | Admin operations (tenant settings, server config, invites) | Bearer JWT (admin app ID) |
| `teachertools.minecrafteduservices.com/website/dedicatedserver/register_server` | Dedicated server registration (admin portal frontend) | Bearer JWT (appid `f8ba6a93-...`) |
| `dedicatedserverprod.minecrafteduservices.com` | Dedicated server services (legacy path — `fetch_token`, `fetch_server`, `fetch_joiners`, `refresh_token`) | Bearer JWT |
| `signal.franchise.minecraft-services.net` | WebSocket signaling for P2P | Bearer JWT |
| `turn.azure.com` | STUN/TURN NAT traversal | Session-based |
| `world.relay.skype.com` | STUN/TURN relay | Session-based |
| `safety-secondary.franchise.minecraft-services.net` | Safety/moderation check | Bearer JWT |
| `downloads.minecrafteduservices.com` | Client/server updates | N/A |
| `client.discovery.minecraft-services.net/api/v1.0/discovery/` | Service discovery | N/A |
| `notebooks.minecrafteduservices.com/docs/DedicatedServerApiDocs.html` | Official MESS API documentation | N/A |

### transportType Values

- `0` — Dedicated server (direct IP). Used in `server/host` on the dedicated server API.
- `2` — P2P connection. Used in the discovery service `/host` endpoint.
- The API docs note: "Internal meaning and will change in the future."

### Defunct Endpoints

| Endpoint | Was Used For | Status |
|----------|-------------|--------|
| `meeservices.azurewebsites.net/v2/signin` | Token acquisition (bundabrg era) | DNS resolution fails |

### Network Requirements

URLs that must be allowlisted:
- `//*.minecraft-services.net/`
- `signal.franchise.minecraft-services.net` (WebSocket)
- `turn.azure.com` / `world.relay.skype.com` (STUN/TURN, IP range `20.202.0.0/16`, TCP 443, UDP 3478-3481)

---

## 35. Protocol Documentation References

- [Mojang bedrock-protocol-docs](https://github.com/Mojang/bedrock-protocol-docs) — official packet schemas (JSON/HTML)
- [CloudburstMC Protocol](https://github.com/CloudburstMC/Protocol) — Java implementation of Bedrock protocol
- [PrismarineJS bedrock-protocol](https://github.com/PrismarineJS/bedrock-protocol) — JavaScript implementation
- [Bedrock Wiki protocol](https://wiki.bedrock.dev/servers/bedrock)
- [Bedrock Wiki RakNet](https://wiki.bedrock.dev/servers/raknet) — documents MCEE/MCPE edition field
- [Geyser Extension docs](https://geysermc.org/wiki/geyser/extensions/)
- [Geyser API docs](https://geysermc.org/wiki/geyser/api/)
- [Geyser Extension template](https://github.com/GeyserMC/GeyserExtensionTemplate)
- [oryxel1/GeyserReversion on Modrinth](https://modrinth.com/mod/geyserreversion) — active, proves extension approach works
- [MisteFr protocol diffs](https://github.com/MisteFr/minecraft-bedrock-documentation) — Bedrock protocol version diffs

### Dead-End Resources

These resources were investigated during research but proved not useful:

- **`pmmp/BedrockBlockPaletteArchive`** — Does NOT have palettes for protocol 898 or 924. Only covers older protocol versions.
- **`MisteFr/minecraft-bedrock-documentation`** — Stops at version 1.16.201.03. Not useful for modern protocol versions (898+), though still listed above for historical reference.

---

## 36. Gameplay Differences (Education vs Bedrock, March 2026)

Effectively zero for survival gameplay. Bedrock 26.0-26.3 added only command macros, bugfixes, and creator tools over Education's 1.21.132 content. Baby mob redesigns and craftable name tags are behind an experimental toggle ("Tiny Takeover" / "Drop 1 of 2026") and haven't shipped as stable content yet.

Education-exclusive features (chemistry, NPCs, Code Builder, camera/portfolio, allow/deny blocks, classroom mode) don't exist on the Java server side and are irrelevant for survival gameplay.

---

## 37. Summary of Required Changes for Education Edition Support

### Minimum Required (for connection to work):

1. **Per-client Education detection** — detect `IsEduMode` in client data JWT, store `educationClient` boolean on session
2. **EducationStartGameSerializer** — extends `StartGameSerializer_v898`, writes 3 extra empty strings after `super.writeLevelSettings()`: `educationReferrerId`, `educationCreatorWorldId`, `educationCreatorId`
3. **Education codec factory** — `CodecProcessor.educationCodec()` creates a codec variant with `EducationStartGameSerializer`
4. **Permanent codec swap with registry transfer** — set education codec on session before StartGamePacket, re-apply item/block/camera registries
5. **signedToken in handshake JWT** — inject tenant-scoped token as claim before signing (per-client, only for Education)
6. **Encryption order** — send handshake packet before enabling encryption
7. **Education packet codecs** — change `ILLEGAL_SERIALIZER` to `IGNORED_SERIALIZER` for 7 Education packet types
8. **Block breaking** — handle `CONTINUE_BREAK` and `STOP_BREAK` action types with progress-based disambiguation

### Recommended (for better compatibility):

9. **Education flags** — set `eduEditionOffers=1`, `eduFeaturesEnabled=true`, `educationProductionId="education"`
10. **Chemistry experiment** — add to experiments list
11. **EducationSettingsPacket** — send after StartGame with empty/default fields (expected by Education clients)
12. **Education gamerules** — `codebuilder=false`, `allowdestructiveobjects=true`, `allowmobs=true`, `globalmute=false` in both StartGamePacket and GameRulesChangedPacket. `allowdestructiveobjects` and `allowmobs` are essential because Education defaults them to `false`.
13. **MCEE ping response** — set edition to `MCEE` in RakNet pong (only needed for LAN discovery, not URI scheme or MESS server list connections)
14. **EducationAuthManager** — full MESS lifecycle: device code flow, server registration, token refresh, heartbeat, server list integration (see Section 51)
15. **EduTokenChain verification** — verify client authorization JWT against MESS public keys (see Section 50)
16. **Floodgate integration** — BedrockData extended with edu fields, education UUID generation, username formatting with tenant hash (see Sections 53-55)

### Previously Applied, Now Reverted (see Section 57):

- ~~Simplified skins~~ — removed, Education supports standard skin features
- ~~Client-authoritative movement~~ — reverted to SERVER for all clients
- ~~`vanillaVersion` set to `"1.21.132"`~~ — reverted to `"*"`, confirmed unnecessary
- ~~`serverAuthoritativeBlockBreaking=false`~~ — reverted to `true` for all clients

---

## Appendix A: Microsoft 365 Education Tenant System (Detailed)

- **Tenant:** One organization's entire Microsoft universe (school district, university)
- **Global Administrator:** Unlimited control, required for dedicated server registration. Typically 2-3 per organization (IT staff)
- **IT/Service Admins:** Manage specific services
- **Teachers:** Regular users with some elevated classroom permissions
- **Students:** Regular users
- All users with M365 Education licenses automatically have Minecraft Education access
- Multiplayer restricted to same tenant by default
- Cross-tenant requires dedicated server setup AND both tenants enabling cross-tenant
- Tenant ID found in JWT `tid` field
- Admin center: `admin.microsoft.com` (requires admin role)
- User profile: `myaccount.microsoft.com` (any user)

---

## Appendix B: Education Edition File Locations

- **Install directory:** `Get-AppxPackage *education* | Select InstallLocation`
- **User data:** `%LocalAppData%\Packages\Microsoft.MinecraftEducationEdition_8wekyb3d8bbwe\LocalState\`
- **No client-side logs** are generated by default in the user data folder
- Content Log can be enabled in-game settings on standard Bedrock but the option may not exist in Education

---

## Appendix C: Binary Analysis Reference (Windows Client — Disconnect Investigation)

Windows Education client base address (for RVA calculations): `0x00007FF70B430000` (varies per launch due to ASLR)

### Key Loaded Modules

| Module | Base Address | Size | Notes |
|---|---|---|---|
| minecraft.windows.exe | 0x00007FF70B430000 | ~159 MB | Main executable |
| ntdll.dll | 0x00007FFA4DAA0000 | 2.4 MB | |
| WS2_32.dll | 0x00007FFA4C2A0000 | 464 KB | Winsock2, closesocket at +0x10B10 |
| libcef.dll | 0x00007FF970090000 | ~222 MB | Chromium Embedded Framework (HBUI) |
| cohtml.WindowsDesktop.dll | 0x00007FF9F4EA0000 | 7.5 MB | Coherent HTML UI |
| v8.dll | 0x00007FF9F32F0000 | 23.4 MB | Google V8 JavaScript engine |
| python38.dll | 0x00007FF9F49D0000 | 4.9 MB | Embedded Python 3.8 (Code Builder) |
| fmod64.dll | 0x00007FFA12960000 | 1.9 MB | Audio |
| dbghelp.dll | 0x00007FFA3FBE0000 | 2.3 MB | Debug helper (stack capture) |
| SecureEngineSDK64.dll | 0x0000000010000000 | 36 KB | Anti-tamper/DRM (fixed base, may interfere with hooking) |
| libhpdf.dll | 0x00007FFA12890000 | 808 KB | PDF generation library |

### Full Disconnect Call Chain (22 frames)

Captured by SocketHook.dll IAT hook on `closesocket`:

```
closesocket(socket)
 ├─ minecraft.windows.exe+0x5CBA49E    Frame 1     RakNetSocket2 - closesocket caller
 ├─ minecraft.windows.exe+0x5CDF232    Frame 2     Socket cleanup orchestrator
 ├─ minecraft.windows.exe+0x199554     Frame 3     Dispatch wrapper
 ├─ minecraft.windows.exe+0x6D5DDCA    Frame 4     (unknown helper)
 ├─ minecraft.windows.exe+0x5CBE957    Frame 5     Network layer (RakNet)
 ├─ minecraft.windows.exe+0x28AF06E    Frame 6     Disconnect function body
 │       mov edx, 0x29  (reason = "Disconnected")
 ├─ minecraft.windows.exe+0x28BA6E6    Frame 7     Connection state handler
 │       processes shared_ptr, hash 0xa56a090e
 ├─ minecraft.windows.exe+0x29E3C27    Frame 8     Tick / flag checker <<<
 │       reads: this→0xD0→subObj→0x40→flagObj[0]
 │       if non-zero: calls subObj vtable[0x58] → disconnect
 ├─ minecraft.windows.exe+0xADFBB0     Frame 9     Outer tick ([rbx+0x414] flag)
 ├─ minecraft.windows.exe+0xD05BE7     Frame 10    Game loop dispatch
 ├─ minecraft.windows.exe+0xAC51C8     Frame 11    Scheduler
 ├─ minecraft.windows.exe+0xAF36FF     Frame 12    Task runner
 ├─ minecraft.windows.exe+0xAC4F89     Frame 13    Event loop
 ├─ minecraft.windows.exe+0xAC650F     Frame 14    Event loop
 ├─ minecraft.windows.exe+0xAC4658     Frame 15    Event loop
 ├─ minecraft.windows.exe+0x1FA6563    Frame 16    App tick
 ├─ minecraft.windows.exe+0x2D592E     Frame 17    Main loop
 ├─ minecraft.windows.exe+0x2D6020     Frame 18    Main loop
 ├─ minecraft.windows.exe+0x2D61C4     Frame 19    Main loop
 ├─ minecraft.windows.exe+0x6D5E842    Frame 20    Thread start
 ├─ KERNEL32.DLL+0x2E8D7               Frame 21    BaseThreadInitThunk
 └─ ntdll.dll+0x8C48C                  Frame 22    RtlUserThreadStart
```

### Key Disconnect Functions

| RVA | Purpose |
|-----|---------|
| `0x28AEFA0` | Disconnect function — `mov edx, 0x29`, calls virtual disconnect through inner object vtable |
| `0x29E3BA0` | Flag checker — triple pointer indirection: `this→0xD0→subObj→0x40→flagObj[0]`, if non-zero calls `subObj_vtable[0x58]` |
| `0x29E3C11` | Disconnect branch — `cmp byte ptr [rax], bpl; je skip` |
| `0x29E3C14` | JE instruction — the conditional branch patched by DisconnectBypass DLLs |
| `0x02935E40` | DisconnectFailReason enum registration function (entt meta reflection) |
| `0xADF9C0` | Outer tick function with related status check at `[rbx + 0x414]` |
| `0xB288E0` | Shared_ptr handler — processes handler with hash `0xa56a090e` |

### Flag Checker Assembly (RVA 0x29E3BA0)

```asm
mov  [rsp+0x10], rbx
mov  [rsp+0x18], rbp
mov  [rsp+0x20], rsi
push rdi
...
mov  rax, [rcx + 0xD0]        ; subObj = this->0xD0
mov  rax, [rax + 0x40]        ; flagObj = subObj->0x40 (shared_ptr raw pointer)
                               ; subObj+0x48 = shared_ptr control block ref count
cmp  byte ptr [rax], bpl      ; check flagObj[0] (the disconnect flag)
je   +0xDF                     ; skip disconnect if flag == 0
...
call [subObj_vtable + 0x58]    ; leads to 0x28AEFA0 → disconnect
```

### Flag Object Memory Dump

```
0x212FF7FC830: 01 00 00 00 00 00 00 00  (byte 0 = disconnect flag = TRUE)
0x212FF7FC838: 00 00 00 00 00 00 08 00
0x212FF7FC840: 70 65 72 73 6F 6E 61 5F  "persona_right_le"
0x212FF7FC850: 67 00 ...                  "g\0"
```

### DisconnectFailReason Enum (Notable Values)

136 total values (0-135), extracted from entt meta reflection at RVA `0x02935E40` and cross-referenced with gophertunnel Go library.

| Value | Hex | Name | Notes |
|---|---|---|---|
| 0 | 0x00 | Unknown | |
| 8 | 0x08 | VersionMismatch | |
| 9 | 0x09 | SkinIssue | Potentially relevant |
| 11 | 0x0B | EduLevelSettingsMissing | Education-specific |
| 26 | 0x1A | InvalidPlatformSkin | Potentially relevant |
| 31 | 0x1F | BannedSkin | |
| 32 | 0x20 | Timeout | |
| **41** | **0x29** | **Disconnected** | **Generic — this is what the client uses** |
| 55 | 0x37 | Kicked | |
| 90 | 0x5A | BadPacket | |
| 131 | 0x83 | EditionMismatchVanillaToEdu | Education-specific |
| 132 | 0x84 | EditionMismatchEduToVanilla | Education-specific |

The fact that the code uses "Disconnected" (generic) rather than a specific reason like "SkinIssue" or "EduLevelSettingsMissing" confirms the disconnect is a catch-all from a general validation failure (the StartGamePacket deserialization error).

### DebugView Observations

During testing, DebugView captured:
- `"SafetyServiceHelper didn't get a valid response from the safety service"` — observed during connection. Confirmed NOT the cause of the disconnect.
- `"Opening level 'C:/Users/niels/AppData/Local/Temp/minecraftpe/blob_cache'"` — blob cache path

### Education-Specific Disconnect Messaging

The binary references `data/definitions/disconnection_errors/disconnection_error_messaging_edu.json` at RVA `0x07FEFC60` — a separate Education-specific disconnect messaging file alongside the standard `disconnection_error_messaging.json`.

### UWP Sandbox Constraints for DLL Development

- `CreateFileA` to arbitrary paths fails (AppContainer restrictions)
- `GetTempPathA` returns a writable app-specific temp folder
- `OutputDebugStringA` works (captured by Sysinternals DebugView run as admin)
- LNK1104 occurs if DLL is still loaded from a previous injection — must kill the game first

---

## Appendix D: Geyser Build System

- Build tool: Gradle (Kotlin DSL)
- Java version: 17
- Geyser API dependency: `compileOnly`, version 2.8.3-SNAPSHOT / 2.9.0
- Protocol library repository: `https://repo.opencollab.dev/main/`
- Build command: `./gradlew build` or `./gradlew build -x test`
- Output: Platform-specific JARs in `bootstrap/*/build/libs/`

---

## Appendix E: Session Lifecycle for Education Clients

```
1. Client connects (RakNet)
2. RequestNetworkSettingsPacket → standard codec set (v898)
3. LoginPacket received
   a. Parse auth chain (CertificateChainPayload) → result.signed() = false
   b. Parse client data JWT → detect IsEduMode = true → set educationClient = true
   c. startEncryptionHandshake() → build JWT with signedToken claim
   d. Send ServerToClientHandshakePacket
   e. Enable encryption
4. ClientToServerHandshakePacket received
5. PlayStatusPacket (LOGIN_SUCCESS) sent
6. ResourcePacksInfoPacket sent
7. ResourcePackClientResponsePacket received (COMPLETED)
8. startGame() called:
   a. Build StartGamePacket with education fields
   b. Set education codec permanently (with registry transfer)
   c. Send StartGamePacket (3 extra strings appended by EducationStartGameSerializer)
   d. Send EducationSettingsPacket
9. Send default gamerules (with education gamerules)
10. Player spawns, gameplay begins
    - Block breaking uses CONTINUE_BREAK / STOP_BREAK instead of
      BLOCK_CONTINUE_DESTROY / BLOCK_PREDICT_DESTROY
    - Movement is client-authoritative
    - Skins are simplified
```

---

## 38. MESS API Complete Reference

Microsoft's Minecraft Education Edition Server Services (MESS) API is fully documented at `https://notebooks.minecrafteduservices.com/docs/DedicatedServerApiDocs.html`.

### Base URL
```
https://dedicatedserver.minecrafteduservices.com
```

### Authentication Types
- **Entra Access Token** — obtained via OAuth2 device code flow or browser auth. Used for `server/register`, `server/fetch_token`, all `client/` endpoints, and all `tooling/` endpoints.
- **MESS Server Token** — obtained from `server/register`, `server/fetch_token`, or `server/refresh_token`. Format: `tenantId|serverId|timestamp|signature`. Used for `server/host`, `server/update`, `server/dehost`, `server/fetch_joiner_info`, `server/refresh_token`.

### App IDs for Entra Tokens
- `b36b1432-1a1c-4c82-9b76-24de1cab42f2` — Minecraft Education client app ID. Used for `server/register` and `server/fetch_token`.
- `f8ba6a93-3dc8-4753-9f89-886138158d8b` — Admin tooling web app ID ("Minecraft Education AI Web"). Used for `tooling/` endpoints. Has redirect URIs including `https://education.minecraft.net/teachertools/dedicatedservers`.

### Server Endpoints

#### `POST /server/register`
Creates a new server registration. Returns a JWT containing both `serverId` and `serverToken`.

```
Authorization: Bearer {Entra Access Token}
X-Request-ID: {random UUID}
```

Response: JWT string. Decoded payload:
```json
{
  "payload": {
    "ServerId": "UXHG99K8JX2P",
    "ServerToken": "75535150-...|UXHG99K8JX2P|2026-03-23T...|signature..."
  }
}
```

Note: New registrations are **disabled by default**. Must call `tooling/edit_server_info` to enable before clients can connect.

#### `GET /server/fetch_token?serverId={serverId}`
Fetches a new server token using an Entra token. Used when server token has expired.

```
Authorization: Bearer {Entra Access Token}
X-Request-ID: {random UUID}
```

Response: JWT string containing `serverToken` only (no `serverId`).

#### `POST /server/refresh_token` (endpoint path: `/server/token_refresh`)
Documented as refreshing a still-valid server token without requiring an Entra token.

```
Authorization: Bearer {MESS Server Token}
X-Request-ID: {random UUID}
```

> **WARNING: This endpoint consistently returns 404 in testing and does not work.** Instead, refresh tokens by: (1) refreshing the Entra access token via standard OAuth refresh_token grant, then (2) calling `/server/fetch_token` with the new access token to get a new server token. This approach is reliable and tested.

#### `POST /server/host`
Registers the server's IP address with MESS. Makes the server reachable by clients.

```
Authorization: Bearer {MESS Server Token}
x-request-id: {random UUID}
Content-Type: application/json

{
  "connectionInfo": {
    "transportType": 0,
    "transportInfo": {
      "ip": "your.server.ip:19132"
    }
  }
}
```

Response: `200 OK` with empty body.

Notes: Automatically sets server health to 1.0 (100% healthy, online). Can be called multiple times — MESS always uses the most recent IP.

**Confirmed working:** Called `/server/host` with a Geyser server's IP. Server appeared in the Education client's server list. Clients on the owning tenant could connect. With cross-tenant enabled, clients from other tenants could also connect.

#### `POST /server/update`
Reports server status to MESS. Displayed to clients in the server list.

```
Authorization: Bearer {MESS Server Token}
Content-Type: application/json

{
  "playerCount": 12,
  "maxPlayers": 25,
  "health": 3
}
```

Health values: The API documentation claims 0-3, but testing revealed the API **rejects `health=3`**. Accepted values are **-1 to 2**. Use `health=2`.

Should be called when status changes, throttled to minimum 10 seconds between calls. This is what makes the server show as "online" in the client. Without periodic updates, the server shows as "offline" but still allows connections when clicked.

#### `POST /server/dehost`
Marks the server as offline. Should be called on graceful shutdown.

```
Authorization: Bearer {MESS Server Token}
```

Without calling dehost, MESS keeps routing clients to the server for up to 1 hour before marking it offline.

#### `GET /server/fetch_joiner_info`
Returns pending client connections. Part of the nonce verification system (see Section 39).

```
Authorization: Bearer {MESS Server Token}
```

Response:
```json
[
  {
    "sessionToken": "...",
    "joinerToHostNonce": "8a2cbf5c-311c-48a5-b990-38cc6bd88afc",
    "hostToJoinerNonce": "d54225f7-9e9a-4a4c-9c71-358a14ecef9e"
  }
]
```

May be empty. Should be polled periodically.

### Client Endpoints

#### `POST /client/check_server_access`
Verifies whether a client can access a specific server. Used when manually adding a server by ID.

```
Authorization: Bearer {Entra Access Token}
Body: { "serverId": "UXHG99K8JX2P" }
```

#### `GET /client/fetch_broadcasted_servers`
Returns server IDs that are broadcasted (visible by default) in the tenant.

```
Authorization: Bearer {Entra Access Token}
```

Response:
```json
{
  "serverIdList": ["US8IO2XK5AHF", "0LPN6Z208PHE"]
}
```

#### `POST /client/fetch_server_info`
Gets display info for multiple servers at once.

```
Authorization: Bearer {Entra Access Token}
Body: { "serverIds": ["OHMNTT69T7XH", "QH2JNJUZY7YX"] }
```

Response includes per-server: `serverName`, `playerCount`, `maxPlayers`, `health`, `isBroadcasted`, `isPasswordProtected`, `isOwningTenant`, `isEnabled`, `isSharingEnabled`.

#### `POST /client/join_server`
Initiates a join. Returns connection info and nonce pair for verification.

```
Authorization: Bearer {Entra Access Token}
Body: {
  "serverId": "UXHG99K8JX2P",
  "password": "<encrypted, optional>"
}
```

Response:
```json
{
  "connectionInfo": {
    "transportType": 0,
    "transportInfo": {
      "ip": "192.168.1.1:25565"
    }
  },
  "sessionToken": "...",
  "joinerToHostNonce": "8a2cbf5c-...",
  "hostToJoinerNonce": "d54225f7-..."
}
```

Passwords must be encrypted using the public key from `public_keys/encryption` endpoint and base64 encoded.

### Tooling Endpoints

All tooling endpoints use:
```
Authorization: Bearer {Entra Access Token acquired with admin tooling app ID f8ba6a93-...}
```

> **WARNING: Tooling endpoints return 401 Unauthorized when called with a token acquired using the Education client app ID (`b36b1432-...`).** The admin portal uses a different app ID (`f8ba6a93-...`) that cannot be used in the device code flow. The `tryEditServerInfo()` method in EducationAuthManager is best-effort — it warns on failure and prints instructions for manual setup via the admin portal.

#### `POST /tooling/edit_tenant_settings`
Enables/disables dedicated servers and cross-tenant for a tenant.

#### `GET /tooling/fetch_tenant_settings`
Reads current tenant configuration.

#### `POST /tooling/edit_server_info`
Configures a server registration. Required after `server/register` to enable the server.

```json
{
  "serverId": "UXHG99K8JX2P",
  "serverName": "My Server",
  "enabled": true,
  "crossTenantAllowed": true,
  "isBroadcasted": true,
  "sharingEnabled": true,
  "disablePasswordProtection": true
}
```

All fields except `serverId` are optional.

#### `GET /tooling/fetch_server_info` — Gets full server registration details.
#### `GET /tooling/fetch_all_server_ids` — Lists all registered servers in the tenant.
#### `POST /tooling/delete_server_registration` — Permanently deletes a server registration.
#### `POST /tooling/create_server_invite` — Invites another tenant to access a server as a guest.
#### `POST /tooling/accept_server_invite` — Accepts a server invitation from another tenant.

### Miscellaneous Endpoints

#### `GET /public_keys/encryption` — Returns the public key used to encrypt server passwords.
#### `GET /public_keys/signing` — Returns the public keys used to verify server token signatures.

---

## 39. Nonce Verification System

### How It Works

1. Student clicks "Play" in server list
2. Client calls `POST /client/join_server` with Entra token
3. MESS validates identity, returns: `connectionInfo` (server IP), `sessionToken`, `joinerToHostNonce`, `hostToJoinerNonce`
4. Client connects to server IP, sends `joinerToHostNonce` via `EduJoinerToHostNonce` JWT claim in login packet
5. Server polls `GET /server/fetch_joiner_info`, gets matching nonce pair
6. Server verifies `joinerToHostNonce` from client matches what MESS returned
7. Server sends `hostToJoinerNonce` back to client as proof

### Identity Verification

This provides MESS-brokered identity verification — the student's connection is authorized by Microsoft's service. Cannot be spoofed because the nonce is unique per join attempt and tied to the student's Entra authentication.

### Relationship to Chain Validation

The nonce system is separate from and complementary to the JWT chain validation in the login packet:

- **Chain validation:** Proves the player IS who they claim to be (cryptographically signed identity). Works for both URI and server list connections.
- **Nonce verification:** Proves MESS AUTHORIZED this specific connection. Only works for server list connections. Adds access control on top of identity.

The `EduJoinerToHostNonce` and `EduSessionToken` fields in the client's login data JWT are the mechanism. Whether URI connections also trigger `client/join_server` is unknown — needs testing.

---

## 40. Token Extractor Tool

### Purpose

Automated replacement for manual Fiddler capture. A teacher runs the tool, hosts a world in Education Edition for one second, the tool captures the Bearer token and exchanges it for a serverToken.

### How It Works

1. Enables UWP loopback exemption for Education Edition
2. Generates and installs mitmproxy CA certificate into Windows Trusted Root store
3. Sets both WinINET (registry) and WinHTTP (`netsh winhttp set proxy`) system proxies
4. Runs mitmproxy listening on `127.0.0.1:8877`
5. Waits for `discovery.minecrafteduservices.com/host` request
6. Captures Bearer token from Authorization header
7. Restores all proxy settings
8. Calls `/host` with captured Bearer token to get serverToken
9. Outputs token to console, clipboard, and `server_token.txt`

### Critical: WinHTTP vs WinINET

Education Edition uses `libhttpclient` which relies on WinHTTP, NOT WinINET. Setting only the WinINET registry proxy (what most proxy tools do by default) does NOT capture Education Edition traffic. Must also set WinHTTP proxy:

```python
subprocess.run(["netsh", "winhttp", "set", "proxy", "127.0.0.1:8877"])
subprocess.run(["netsh", "winhttp", "reset", "proxy"])  # Reset
```

### Requirements

- Windows (Education Edition is a UWP app)
- Run as Administrator (for CheckNetIsolation, certutil, netsh, registry)
- Python 3.12+ with `mitmproxy` and `requests`
- Can be packaged as `.exe` with PyInstaller

### Cleanup

The tool restores proxy settings on exit via `atexit`, signal handlers, and finally blocks. The mitmproxy CA cert persists for future runs. Remove with:
```
certutil -delstore Root mitmproxy
```

---

## 41. Client Binary Reverse Engineering (Windows PE)

### Target

| Field | Value |
|-------|-------|
| Application | Minecraft Education Edition (stable) |
| Version | 1.21.13201.0 |
| Main binary | `Minecraft.Windows.exe` (153 MB, PE32+ x64) |
| Image base | `0x140000000` |

Education Edition is built on the same C++ codebase as Bedrock Edition. The server connectivity code exists in the binary — it's just disabled by feature flags. Config files are NOT the gate — the edition/server check is in compiled C++ code.

### How the Servers Tab Is Gated

The Play screen is defined in `data/resource_packs/education/ui/edu_play_screen.json`. The Servers button's visibility is bound to `#edu_server_screen_enabled`, set by `EDUPlayScreenController::tick()` at `RVA 0x140E4C0`.

A **master JZ at RVA `0x140E8A9`** gates the entire server infrastructure. When this jump is taken (servers disabled), the server object at `[controller + 0xDF8]`, the feature flag at `[controller + 0x111]`, and the screen name string at `[controller + 0xC70]` are never initialized.

The button handler at `RVA 0x140F060` has three sequential guards: flag check (`[controller+0x111]`), string-empty check (`[controller+0xC80]`), and server object null check (`[controller+0xDF8]`). All three must pass.

See Appendix G for full RVA tables, controller member offsets, and byte patterns.

---

## 42. Education UI Architecture

### Dual UI Systems

1. **JSON UI** (legacy) — XML/JSON-based declarative UI in `resource_packs/*/ui/`. Used for: Play screen, server screens, most gameplay UIs.
2. **HBUI** (React-based) — JavaScript bundles with React components, Webpack-built. Used for: Create/edit world screens, newer UIs.

Education's `routes.json` has **NO server routes**. Bedrock's `routes.json` HAS server routes (`/play/servers/add`, `/play/servers/:id/:type/edit`, `/play/:tab/:id?/:type?`, `/sign-in-play-on-server`).

### Education Server UI JSON Files

All present in `data/resource_packs/education/ui/`:

| File | Purpose |
|------|---------|
| `edu_play_screen.json` | Play screen with Servers button |
| `edu_servers_screen.json` | Full server list/grid with add/remove/share |
| `edu_add_server_screen.json` | Add server dialog (max 12 char server ID) |
| `edu_servers_passcode_screen.json` | Server passcode entry |
| `ip_join_screen.json` | Direct IP:port join screen (max 256 chars) |

The vanilla `add_external_server_screen.json` (also present in Education's package at `data/resource_packs/vanilla/ui/`) has data bindings for `#name_text_box` (max 16), `#ip_text_box` (max 256), `#port_text_box` (max 6 digits).

---

## 43. Community Context, User Base & Demand

There is no currently working tool to connect Education Edition clients to Java servers:

- **bundabrg's GeyserReversion** — abandoned July 2022
- **bundabrg's Geyser PR #536** — closed by bundabrg himself in August 2020, moved to plugin approach
- **GitHub issue #2646** — closed by Camotoy: "not something the core team is interested in at this time"
- **GeyserReversionReloaded** — attempted by Ifixthingz383, no significant progress
- **Minecraft Forum** — educators actively asking for someone to update GeyserReversion

Most educators don't know Geyser exists. Most Geyser users don't have Education Edition. The demand exists in the Education community, not the Geyser community.

---

## 44. Geyser Maintainer History on Education Edition

### PR #536 (May–August 2020)

Maintainer feedback was constructive: rtm516 gave code review, Tim203 actively supported the idea, Camotoy asked technical questions. Nobody told bundabrg the feature was unwanted. Bundabrg closed the PR himself to pursue a plugin approach.

### Issue #2646 (November 2021)

Camotoy: "Unfortunately, this is not something the core team is interested in at this time. None of us have Education Edition on hand."

### What Has Changed Since 2021

- Education shares Bedrock's protocol — no translation layer needed
- Extension API was built but is insufficient for Education support
- User base grew from ~10M to 35M across 115 countries
- Official dedicated servers launched
- The required changes are now minimal (surgical modifications vs bundabrg's massive refactor)

Current Project Leads: Camotoy, onebeastchris, Redned, rtm516, Tim203.

---

## 45. Education Azure AD Application Details

### Education Services Service Principal

**App ID:** `16556bfc-5102-43c9-a82a-3ea5e4810689`
**Display Name:** "Minecraft Education Edition Services"
**SPNs:** `16556bfc-...`, `https://meeservices-staging.minecraft.net`, `https://meeservices.minecraft.net`
**oauth2PermissionScopes:** Empty array — no third-party app can request access.

### Teacher Tools Web App

**App ID:** `f8ba6a93-3dc8-4753-9f89-886138158d8b`
**Display Name:** "Minecraft Education AI Web"
**Redirect URIs:** Production and staging URIs for `education.minecraft.net/teachertools/*`, `edu-stage.minecraft.net/teachertools/*`, `teachertools.web.minecrafteduservices.com/teachertools/*`, and `signin-oidc` endpoints.

---

## 46. OAuth2 Token Acquisition Challenges

### Device Code Flow

Works for tenants without conditional access. Uses **v1.0 endpoint** (NOT v2.0):
```
POST https://login.microsoftonline.com/common/oauth2/devicecode
client_id=b36b1432-1a1c-4c82-9b76-24de1cab42f2
resource=16556bfc-5102-43c9-a82a-3ea5e4810689
```

### Custom Azure AD App (FAILED)

The Education services resource (`16556bfc-...`) exposes NO `oauth2PermissionScopes`. Only Microsoft's first-party app IDs can access the Education services API. Custom app registrations cannot. MSAL v2.0 endpoints fail with "resource principal not found" — must use v1.0 with `resource` parameter.

### Conditional Access Blocking

Some school tenants block device code flow. The Token Extractor Tool (Section 40) works around this by intercepting the Education client's own auth flow via mitmproxy.

### Redirect URI Discovery

The Education client app uses Windows WAM for auth, not browser redirects. No redirect URIs are registered. Standard native redirects (`oob`, `nativeclient`) all return mismatch errors.

---

## 47. Legal & Policy Considerations

No explicit prohibition found against connecting Education Edition clients to third-party servers. The tenant security system exists to protect students. Geyser has existed for years as a Bedrock-to-Java bridge without Microsoft/Mojang action. Frame the project as extending cross-platform compatibility with proper authentication, not bypassing security.

---

## 48. Commercial License for Testing

To test the dedicated server auth flow: create a new M365 Admin Center account, purchase one commercial license ($36/year or $3/month annual commitment). This makes you Global Admin of your own tenant. Microsoft subscriptions can be cancelled with prorated refund within 7 days. Academic pricing: $5.04/user/year. Free trial: 10 logins (does not create a new tenant).

---

## 49. Education Login Chain & Security Model

### Education Login Chain Structure

Education clients send a **self-signed** login chain with these characteristics:
- Chain length: **1** (single JWT, not a chain)
- The `x5u` header equals the `identityPublicKey` in the payload — there is no root certificate authority
- `result.signed()` (CloudburstMC's `EncryptionUtils.validatePayload()`) is **always false**
- `XUID` is always **empty** (no Xbox Live)
- The `identity` UUID is self-generated by the client (not trustworthy)

This means the login chain itself provides **no trust** — it cannot be validated against any known root key.

### Security Model

| Data Source | Trustworthy? | Reason |
|---|---|---|
| Login chain (`extraData`) | No | Self-signed by client |
| Client data (`TenantId`, `ADRole`, `IsEduMode`) | No | Signed by client's own key |
| `EduTokenChain` JWT | **Yes** | Signed by MESS private key (ES384) |
| Tenant ID from EduTokenChain `chain` field | **Yes** | Inside MESS-signed payload |
| `displayName` from login chain | Conditionally | Trustworthy only if EduTokenChain verified |
| `identity` UUID | No | Self-generated, changes per session |

A spoofing client would need to forge the EduTokenChain, which requires MESS's private key — they cannot do this.

### No Per-User Unique ID in Trusted Data

The EduTokenChain payload contains a **tenant ID** but no per-user ID. The `identity` UUID from the self-signed login chain is generated by the client and is not trustworthy. The only way to uniquely identify an education player is **tenant ID + display name** (M365 enforces unique usernames within a tenant).

### Client Data Trustworthiness

| Field | Example | Trustworthy? | Notes |
|---|---|---|---|
| `IsEduMode` | `true` | No (self-signed) | Used for detection, not security |
| `TenantId` | `null` | N/A | Always null — real tenant ID is in EduTokenChain |
| `ADRole` | `0` | No (self-signed) | 0=student, 1=teacher |
| `EduTokenChain` | JWT string | **Yes** (MESS-signed) | The trust anchor |
| `EduSessionToken` | `"uuid\|timestamp"` | Unknown | Present via server list only |
| `EduJoinerToHostNonce` | UUID | Unknown | Changes per connection |
| `displayName` | `NielsI` | Conditionally | Only if EduTokenChain verified |

---

## 50. EduTokenChain Verification System

### What Is EduTokenChain

The `EduTokenChain` is a JWT in the client data, signed by Microsoft's MESS service using **ES384** (ECDSA P-384). The client receives this from MESS when authorized to join a server. The client is a courier — they cannot forge it.

### JWT Structure

- Header: `{"alg":"ES384","x5u":"<MESS public key>"}`
- Payload: `{"chain":"<tenantId>|<unknownId>|<expiryISO>|<hexSignature>","exp":<unix_timestamp>}`

**Chain field format (pipe-separated):**

| Position | Value | Example |
|----------|-------|---------|
| 1 | Tenant ID (trustworthy) | `75535150-2dbb-4af5-9070-3fb6f6c8585c` |
| 2 | Unknown UUID (server/session ID?) | `bb1430cb-bdcf-48b0-bd66-4b58bbb0a9dd` |
| 3 | Expiry ISO 8601 | `2026-03-24T14:37:55.297Z` |
| 4 | Hex signature (purpose unclear) | `4daf8636cfb7d6b5...` |

### Known MESS Public Keys (EC P-384)

```
// Current (as of March 2026)
MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE0mYk5OWVJ/Fi3KVH35wJBQKxWVzhR9fHBD4+STlMPS3OcaqavMsVxuO8cPRPzpGuXdGD6AlD8YVQBOvuw+yHm+0vMSiJo8hCDAkOA767dsdmXNWYdpXHvCW1kBR2sKgQ

// Previous
MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEWQV0SMQIW5FvnAKe2ibSoqPBDI9iaxqbiBKCIKGu2YKAhksJp+nZEQ1bUlTzUsR9yjauLswIo5Q8NdwRgybb1VbVrX1xVIZGXZP4b8IpNS908UA646GIFatYZFWKVY61
```

These are X.509 SubjectPublicKeyInfo (SPKI) format, Base64-encoded.

### Verification Process

1. Split JWT into 3 parts (header.payload.signature)
2. For each known MESS public key, attempt SHA384withECDSA signature verification
3. JWT ES384 signatures are 96 bytes raw (two 48-byte R||S integers) but Java expects DER format — `rawToDer()` handles the conversion (split into R/S, trim leading zeros, add leading zero if high bit set, wrap in DER SEQUENCE)
4. If any key verifies, the chain is valid

**Security: Must use hardcoded key, NOT JWT x5u.** Using the JWT's own `x5u` header key would be circular — anyone could create a self-signed JWT. The `x5u` is only logged for diagnostics (detecting key rotation).

### Server Token JWT vs EduTokenChain

These are signed by completely different systems:
- **Server token JWT** (from `/server/fetch_token`): RS256 with `x5t` thumbprint — standard Azure token
- **EduTokenChain**: ES384 with `x5u` public key — MESS-specific signing

The keys cannot be cross-referenced.

### Current State (BYPASSED)

Verification is currently **bypassed** with TODO markers. During development, a MESS key rotation broke verification for legitimate clients. The code logs warnings but allows connections regardless. The `x5u` from unrecognized keys is logged so new keys can be added manually. Once the key rotation pattern is understood, rejection should be re-enabled.

### Token Naming Clarification

The MESS JWT response contains two useful representations:
- **`serverToken`** — the inner payload string, used as the `signedToken` claim in the handshake JWT
- **`serverTokenJwt`** — the full JWT string, used as `Authorization: Bearer` header for MESS API calls

The MESS API uses **camelCase** (`serverToken`, `serverId`). Some documentation shows PascalCase (`ServerToken`, `ServerId`). The actual API returns camelCase. The code handles both.

---

## 51. EducationAuthManager (Dedicated Server Lifecycle)

**File:** `EducationAuthManager.java` (857 lines)

Manages the full lifecycle of Education Edition dedicated server authentication. Initialized in `GeyserImpl.startInstance()`, shutdown in `GeyserImpl.shutdown()`.

### Constants

- Education client ID: `b36b1432-1a1c-4c82-9b76-24de1cab42f2`
- Resource ID: `16556bfc-5102-43c9-a82a-3ea5e4810689`
- Entra base: `https://login.microsoftonline.com/common/oauth2`
- MESS base: `https://dedicatedserver.minecrafteduservices.com`
- Tooling base: `https://teachertools.minecrafteduservices.com/website/dedicatedserver`

### Startup Flow

1. Load `edu_session.json` from Geyser config directory
2. If no session: initiate device code flow → get access token + refresh token → call `/server/register` or `/server/fetch_token` → get server token
3. If session exists with valid token: refresh Entra access token via refresh_token → call `/server/fetch_token` for fresh server token
4. If session corrupted (empty server ID): clear and restart
5. `POST /server/host` → register IP with MESS (auto-detected or from `edu-server-ip` config)
6. `tryEditServerInfo()` → attempt to enable server, set name, broadcast (best-effort, may 401)
7. Start scheduled tasks

### Scheduled Tasks

- **Every 10 seconds:** `POST /server/update` with playerCount, maxPlayers, health=2
- **Every 30 minutes:** Refresh Entra access token, call `/server/fetch_token` for new server token

### Shutdown

- `POST /server/dehost` to immediately remove from server list
- Cancel scheduled tasks
- Save session to `edu_session.json`

### Session Persistence (`edu_session.json`)

```json
{
  "server_id": "UXHG99K8JX2P",
  "server_token": "75535150-...|UXHG99K8JX2P|...",
  "server_token_jwt": "eyJ0eXAi...",
  "server_token_expires": 1774042800,
  "refresh_token": "0.AUEBUFFTdbst...",
  "access_token": "eyJ0eXAi...",
  "access_token_expires": 1773438871
}
```

### Public IP Auto-Detection

If `edu-server-ip` is not configured, Geyser tries: `checkip.amazonaws.com` → `api.ipify.org` → `icanhazip.com` → bind address fallback.

---

## 52. EduCommand (In-Game Management)

**File:** `EduCommand.java` (181 lines)

Registered as `/geyser edu` with permission `geyser.command.edu`:

| Command | Description |
|---|---|
| `/geyser edu` or `/geyser edu status` | Shows education system status (active/inactive, server ID, IP, token expiry, player count, auth mode) |
| `/geyser edu players` | Lists all connected Education Edition players with tenant ID and role |
| `/geyser edu reset` | Deletes `edu_session.json` and restarts device code authentication |
| `/geyser edu register` | Forces full re-registration (same as reset) |

---

## 53. Floodgate Integration

### Architecture

```
Edu Client → Geyser → [BedrockData encrypted] → Velocity (Floodgate) → [re-encrypted] → Backend (Floodgate)
```

1. **Geyser** detects edu client, extracts tenantId/adRole, packs into `BedrockData` (null-byte separated)
2. **Geyser** encrypts BedrockData and appends to server handshake hostname
3. **Velocity Floodgate** decrypts, creates `FloodgatePlayerImpl` with edu UUID + username
4. **Velocity Floodgate** re-encrypts via `toBedrockData()` and forwards to backend
5. **Backend Floodgate** decrypts and creates its own FloodgatePlayer

### BedrockData Serialization Format

Expanded from 12 to **15 fields** (null-byte separated):

| Index | Field | Type | Description |
|---|---|---|---|
| 0-11 | (standard fields) | various | version, username, xuid, deviceOs, languageCode, uiProfile, inputMode, ip, linkedPlayer, fromProxy, subscribeId, verifyCode |
| **12** | **education** | **int** | **`1` if edu player, `0` otherwise** |
| **13** | **tenantId** | **String** | **M365 tenant ID or empty** |
| **14** | **adRole** | **int** | **0=student, 1=teacher, -1=N/A** |

### FloodgatePlayer API

Three new methods on the public `FloodgatePlayer` interface:
- `boolean isEducationPlayer()`
- `String getTenantId()`
- `int getAdRole()`

### Player Linking

Education players skip player linking (`fetchLinkedPlayer` returns null) — they have no Xbox account to link.

### `isFloodgateId()` Updated

```java
public boolean isFloodgateId(UUID uuid) {
    return uuid.getMostSignificantBits() == 0 || Utils.isEducationId(uuid);
}
```

### Build Dependency Chain

1. Build GeyserFork: `./gradlew build -x javadoc -x test`
2. Publish common module: `./gradlew :common:publishToMavenLocal`
3. Build FloodgateFork: `./gradlew build -x javadoc -x delombok -x javadocJar` (Lombok + JDK 21 fix)
4. FloodgateFork's `Versions.kt`: `geyserVersion = "2.9.4-SNAPSHOT"` (must match GeyserFork)
5. Deploy: GeyserFork + FloodgateFork-Velocity in Velocity plugins, FloodgateFork-Spigot in backend plugins, shared `key.pem`

---

## 54. UUID Generation Scheme

Three distinct UUID formats prevent collisions:

| Player Type | MSB | LSB | Example |
|---|---|---|---|
| Java | Random (standard v4) | Random | `550e8400-e29b-41d4-a716-446655440000` |
| Bedrock | `0x0000000000000000` | XUID as long | `00000000-0000-0000-0000-000123456789` |
| Education | `0x0000000100000001` | SHA-256(tenantId:username) first 8 bytes | `00000001-0000-0001-a1b2-c3d4e5f6a7b8` |

No format can collide with another. Education UUID input: `tenantId + ":" + username`, SHA-256 of UTF-8 bytes, first 8 bytes as big-endian long for LSB.

**Note:** `createEducationUuid()` is duplicated in both `GeyserSessionAdapter.java` and Floodgate's `Utils.java` because Geyser can't import Floodgate utils.

**Known issue:** Since `TenantId` is always null (see Section 32, issue #2), the current input is effectively `":username"`, meaning UUIDs only differentiate by username, not by tenant.

---

## 55. Username Format & Collision Prevention

### Format

| Player Type | Format | Example |
|---|---|---|
| Java | `<username>` | `MarkR` |
| Bedrock | `.<username>` (Floodgate prefix) | `.MarkR` |
| Education | `#<truncated_name><4-char-tenant-hash>` | `#StudentNamea1b2` |

- Education prefix: `#` (configurable via `education-prefix` in Floodgate config)
- Tenant hash: 4-character hex from SHA-256 of tenantId (first 2 bytes = `%02x%02x`)
- Name truncated to fit within 16-char Java limit: `16 - prefix.length() - 4`
- With default `#`: max 11 characters of player name
- Spaces replaced with `_` if `replace-spaces: true`

### Why the Tenant Hash

Players from different M365 tenants (different schools) could have the same display name. The 4-char hash provides 65,536 possible values per name — collision risk is negligible.

**Known issue:** Since `TenantId` is always null, `getTenantHash("")` hashes an empty string — all edu players currently get the same 4-char suffix.

---

## 56. DLL Catalog (Client-Side Investigation)

Five DLLs were created during the client-side binary investigation. All were injected via Fate Injector into the Education Edition UWP process.

### 1. SocketHook.dll (`closesocket_hook.cpp`)

IAT-patched `closesocket` across all loaded modules. Logged 64-frame call stacks with module+RVA. Successfully captured the complete 22-frame disconnect call chain on thread 47204 (two `closesocket` calls, 10ms apart, socket handles `0x12C8` and `0x1268`).

### 2. DisconnectHook.dll (`disconnect_hook.cpp`)

INT3 breakpoints + PAGE_GUARD + Vectored Exception Handler. Two-phase: Phase 1 read the disconnect flag via triple pointer indirection (`this→0xD0→subObj→0x40→flagObj[0]`), Phase 2 installed PAGE_GUARD to trap the writer. Result: flag was already `0x01` on first check (PAGE_GUARD too late). Discovered `persona_right_leg` string at flag object +0x10. Caused post-dismissal crashes (INT3/VEH interference).

### 3. DisconnectBypass.dll v1 (`disconnect_bypass.cpp`)

Binary patch: JE→JMP at RVA `0x29E3C14` (original `0F 84 DF 00 00 00` → `E9 E0 00 00 00 90`). Converts conditional skip-disconnect to unconditional. Plus INT3 at disconnect function for monitoring. **Result: disconnect still occurred** — another code path exists. Caused post-dismissal crashes.

### 4. DisconnectBypassV2.dll (`disconnect_bypass_v2.cpp`)

Same JE→JMP patch, no INT3/VEH/exception handlers. Pure binary patching only. Built to isolate whether the v1 crash was caused by the bypass or the hooks.

### 5. ServerTabEnabler.dll (`dllmain.cpp`)

Server tab UI enabler. Three patches: (1) string corruption `#`→`!` in `.rdata` to make button visible, (2) NOP the flag check JZ, (3) replace server object null check with controller pointer. Button appeared but was non-functional — server infrastructure never initialized by tick().

---

## 57. Changes Tested and Reverted

Several defensive changes were applied early and later determined unnecessary once the core fix (3 extra StartGamePacket strings) was identified.

### Simplified Skins (REMOVED)

In `SkinManager.getSkin()` and `SkullSkinManager.buildSkullEntryManually()`, an early-return for Education clients stripped custom geometry, capes, premium/override flags. **Removed** because Education 1.21.132 supports all standard skin features and Geyser already sends classic skins.

### Client-Authoritative Movement (REVERTED to SERVER)

Education clients were set to `AuthoritativeMovementMode.CLIENT` with `setRewindHistorySize(0)` and `setServerAuthoritativeBlockBreaking(false)`. **Reverted** to `SERVER` for all clients. The `CONTINUE_BREAK`/`STOP_BREAK` aliases remain as safety net.

**Open question:** Whether Education clients under `SERVER` mode send `BLOCK_CONTINUE_DESTROY`/`BLOCK_PREDICT_DESTROY` (like Bedrock) or still send `CONTINUE_BREAK`/`STOP_BREAK`. The alias approach handles both.

### vanillaVersion "1.21.132" (REVERTED to "*")

Set `vanillaVersion` to `"1.21.132"` for Education clients. **Reverted** — `"*"` works fine after the core fix.

### serverAuthoritativeBlockBreaking = false (REVERTED to true)

Paired with CLIENT movement mode. **Reverted** to `true` for all clients when movement was unified to SERVER.

---

## Appendix F: Geyser Internals (Netty/RakNet Architecture)

### Protocol Codec System

1. **`GameProtocol.SUPPORTED_BEDROCK_CODECS`** — `List<BedrockCodec>` with all supported versions. Protocol 898: `register(Bedrock_v898.CODEC, "1.21.130", "1.21.131", "1.21.132")`
2. **`CodecProcessor`** — Replaces serializers for unhandled packets with `ILLEGAL_SERIALIZER`
3. **`BedrockCodec`** — Immutable. Modify via: `codec.toBuilder().updateSerializer(...).build()`

### Netty / RakNet Architecture

- **`RakServerChannel`** — Netty `ServerChannel` for RakNet over UDP
- **`ServerBootstrapAcceptor`** — Processes new child channels
- **`BedrockPeer`** — Pipeline handler `"bedrock-peer"`, contains `sessions` (`Int2ObjectMap<BedrockSession>`, protected)
- **`BedrockServerSession`** — Wraps channel, has `setPacketHandler()`/`getPacketHandler()`
- **`BedrockPacketHandler`** — Interface with visitor pattern dispatch

### Class Hierarchy

```
BedrockPacketHandler (interface, CloudburstMC Protocol)
    → LoggingPacketHandler (abstract, Geyser: protected GeyserImpl geyser, protected GeyserSession session)
        → UpstreamPacketHandler (private: networkSettingsRequested, receivedLoginPacket, resourcePackLoadEvent)
```

### Geyser Internal Classes Used (via reflection)

| Class | Field/Method | Purpose |
|-------|-------------|---------|
| `GameProtocol` | `SUPPORTED_BEDROCK_CODECS` (private static) | Codec list for patching |
| `GeyserBedrockPingEventImpl` | `pong` (private) | BedrockPong for edition string |
| `GeyserSession` | `getClientData()` | Client JWT data |
| `BedrockClientData` | `getOriginalString()` | Raw JWT string |

---

## Appendix G: Windows PE Client Binary Reference (v1.21.13201.0)

Image base: `0x140000000`. Binary: `Minecraft.Windows.exe` (153 MB, PE32+ x64).

### PE Sections

| Section | Virtual Address | Raw Pointer | Size |
|---------|----------------|-------------|------|
| .text   | 0x1000         | 0x400       | 0x77A6000 |
| .rdata  | 0x77A7000      | 0x77A6400   | 0x1331C00 |
| .data   | 0x8AD9000      | 0x8AD8000   | 0x371C00 |
| .pdata  | 0x909C000      | 0x8E49C00   | 0x40FC00 |
| .rsrc   | 0x94B7000      | 0x9263800   | 0x5F3600 |
| .reloc  | 0x9AAB000      | 0x9856E00   | 0xBEA00 |

### tick() Function RVAs

| RVA | Description |
|-----|-------------|
| `0x140E4C0` | `EDUPlayScreenController::tick()` entry |
| `0x140E8A9` | Master JZ gating server infrastructure init |
| `0x140E956` | CALL `0x1440A70` (create server object) |
| `0x140E969` | `MOV [rdi+0xDF8], rcx` (stores server object) |
| `0x140EA95` | LEA loading `#edu_server_screen_enabled` string |

### Button Handler RVAs

| RVA | Description |
|-----|-------------|
| `0x140F060` | Handler entry |
| `0x140F0A3` | Flag check `[controller+0x111]` |
| `0x140F0B3` | String-empty check `[controller+0xC80]` |
| `0x140F0BF` | Server object null check `[controller+0xDF8]` |
| `0x140F0D3` | CALL `0x1315CD0` (navigate) |

### Controller Member Offsets

| Offset | Type | Purpose |
|--------|------|---------|
| `+0x111` | byte | Server feature enabled flag |
| `+0xAF0` | pointer | Provider object |
| `+0xC70`/`+0xC80` | string | Screen name |
| `+0xDF8`/`+0xE00` | pointer | Server object (PropertyVariant pair) |

### Byte Patterns

Flag check: `40 38 B7 11 01 00 00 74 43 48 8D 9F 70 0C 00 00 4C 8D 73 10 49 39 36 74 33`
Server object load: `48 8B 8F F8 0D 00 00 48 85 C9 74 27`

---

## Appendix H: Complete minecraft:// URI Scheme List

| URI | Purpose |
|-----|---------|
| `minecraftedu://connect/?serverUrl=IP&serverPort=PORT` | Direct connect (Education) |
| `minecraft://connect/?serverUrl=IP&serverPort=PORT` | Direct connect (Bedrock) |
| `minecraft:?addExternalServer=Name\|IP:Port` | Add to server list (may be broken) |
| `minecraft://openServersTab` | Open Servers tab |
| `minecraft://connect/?localLevelId=ID` | Connect to local world by ID |
| `minecraft://connect/?localWorld=NAME` | Connect to local world by name |
| `minecraft://?load=ID` | Load local world |
| `minecraft://connectToRealm?realmId=ID` | Connect to Realm |
| `minecraft://acceptRealmInvite?inviteID=CODE` | Accept Realm invite |
| `minecraft://?slashcommand=CMD` | Execute slash command |
| `minecraft:?edu=1` | Launch in Education mode |
| `minecraft://?import=PATH` | Import content |
| `minecraft://?importpack=PATH` | Import resource/behavior pack |
| `minecraft://?importaddon=PATH` | Import addon |

Sources: lukeeey/mcpe-docs, phasephasephase/MCBEProtocolURIs, HBIDamian/minecraftUrlSchemes, lukeeey/minecraft-launch-intents.

---

## Appendix I: Education Edition Game Data Files

### Game Data Directory

`%LOCALAPPDATA%\Packages\Microsoft.MinecraftEducationEdition_8wekyb3d8bbwe\LocalState\games\com.mojang\`

Files confirmed absent by default: `external_servers.txt`, `servers.dat`, `server_list_cache.json`. The `minecraftpe/storage_object` file may contain encrypted server config.

### external_servers.txt Format (if created manually)

```ini
[1]
name=MyServer
host=192.168.1.100
port=19132
```

Requires Servers tab UI visibility (VDX Desktop UI resource pack or DLL patch) to be usable.

### Supported File Types (AppxManifest.xml)

`.mcworld`, `.mctemplate`, `.mcaddon`, `.mcpack`, `.mcshortcut` (zip-based, `launcher_quick_play.json` with `id`/`name`/`source` fields).
