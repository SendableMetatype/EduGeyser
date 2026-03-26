# Stripped Education Features

Features that were implemented and tested but found to be unnecessary for Education Edition clients to connect and play. Documented here for reference in case they're ever needed.

## Removed: EducationSettingsPacket

A dedicated packet sent after StartGamePacket to configure education-specific UI. Contained fields for Code Builder URI, override URI, and quiz URI. Removed because the Education Edition client works correctly without it — education features are enabled by the 3 eduSharedUri strings in StartGamePacket alone.

## Removed: Education-Specific Gamerules

The following gamerules were sent to education clients during session setup:

```java
new GameRuleData<>("allowdestructiveobjects", true);   // Allow TNT, fire spread
new GameRuleData<>("allowmobs", true);                  // Allow mob spawning
new GameRuleData<>("globalmute", false);                // Allow chat
```

Removed because these are Java server defaults anyway. The edu client respects the Java server's behavior without explicit gamerule overrides.

**Kept:** `codebuilder: false` — this one IS required. Without it, the Code Builder UI is active and the client sends `CodeBuilderSourcePacket`, which triggers the ILLEGAL_SERIALIZER and disconnects the player.

## Removed: Education-Specific Experiments

The following experiment toggles were added to StartGamePacket for education clients:

```java
new ExperimentData("chemistry", true);   // Chemistry tables, compounds, elements
new ExperimentData("gametest", true);    // GameTest framework UI
```

Removed because the items/blocks these enable don't exist on the Java server, so the experiments have no effect. The client connects and plays normally without them.

## Removed: IGNORED Packet Serializers

Seven education packet types were overridden from ILLEGAL (disconnects client) to IGNORED (silently dropped) in EducationCodecProcessor:

- `PhotoTransferPacket` — in-game photography
- `LabTablePacket` — chemistry lab tables
- `CodeBuilderSourcePacket` — Code Builder save-to-server
- `CreatePhotoPacket` — photo creation
- `NpcRequestPacket` — NPC interaction
- `PhotoInfoRequestPacket` — photo metadata
- `GameTestRequestPacket` — GameTest framework

Removed because the items/entities that trigger these packets don't exist on the Java server. The client never sends them during normal gameplay. The `CodeBuilderSourcePacket` case is handled by disabling Code Builder via the `codebuilder: false` gamerule instead.

## Removed: EDU_STOP_BREAK_THRESHOLD

A separate block break detection threshold (0.65f) for education clients in BlockBreakHandler. The comment noted it was unused since server-authoritative block breaking handles edu clients correctly. Removed with no gameplay impact.

## What Remains (Minimum Required)

1. **EducationStartGameSerializer** — appends 3 empty strings (eduSharedUri resource/buttonName/linkUri) to StartGamePacket. Without these, the edu client refuses to load the world.
2. **`codebuilder: false` gamerule** — disables Code Builder UI to prevent the client from sending unsupported packets.
3. **Authentication system** — tenant-based auth, nonce verification, token management (completely separate from session setup).
