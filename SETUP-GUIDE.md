# EduGeyser Setup Guide

## Connect Minecraft Education Edition to Java Servers

EduGeyser is a modified version of [GeyserMC](https://geysermc.org/) that allows **Minecraft Education Edition** students to join **Java Edition** servers. It works alongside regular Bedrock Edition - both Education and Bedrock players can connect to the same server simultaneously.

---

## Table of Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [EduFloodgate Configuration](#edufloodgate-configuration)
- [Troubleshooting](#troubleshooting)
- [FAQ](#faq)

---

## Requirements

**Server side:**
- A Java Edition Minecraft server (Paper, Spigot, etc.)
- Java 17 or newer
- EduGeyser jar (replaces standard Geyser)
- EduFloodgate jar (replaces standard Floodgate) - required for proper username/UUID handling
- [Geyser Education Extension](https://github.com/SendableMetatype/Geyser-Education-Extension) - handles how students connect

> **Simplest setup:** A single Paper server with EduGeyser, EduFloodgate, and the Education Extension. No proxy needed. If you already run a Velocity/BungeeCord network, EduGeyser works there too - install EduGeyser and the extension on the proxy and EduFloodgate on both the proxy and backend servers.

**Client side:**
- Minecraft Education Edition v1.21.133 or newer
- A Microsoft 365 Education account (school-provided)
- Windows, macOS, iPad, Chromebook, or Android

---

## Installation

1. Download the latest [EduGeyser](https://github.com/SendableMetatype/EduGeyser/releases), [EduFloodgate](https://github.com/SendableMetatype/EduFloodgate/releases), and [Geyser Education Extension](https://github.com/SendableMetatype/Geyser-Education-Extension/releases) jars
2. Place them in your server's plugins folder (replacing standard Geyser and Floodgate if present). The extension jar goes in the Geyser `extensions` folder.
3. Make sure `auth-type` is set to `floodgate` in the Geyser config:

```yaml
auth-type: floodgate
```

4. Start the server

On first start, the extension generates a **Connection ID** (a 10-digit number) and prints it to the console. Students connect by opening Education Edition, pressing **Play**, then **Join World**, then the small **...** button to the right of the confirm button. In this dialog they can enter the Connection ID to join. No accounts or configuration needed.

For **join codes** and **server list broadcasting**, see the [extension's documentation](https://github.com/SendableMetatype/Geyser-Education-Extension).

For general Geyser configuration (ports, proxy setup, etc.), see the [Geyser wiki](https://geysermc.org/wiki/geyser/setup/).

---

## EduFloodgate Configuration

EduFloodgate uses a separate prefix for education players to distinguish them from regular Bedrock players:

```yaml
# In EduFloodgate config.yml:
education-prefix: "+"
```

- `education-prefix` - prefix for education player usernames (default: `+`)

Education Edition usernames often collide (the Entra default format is "FirstnameLastInitial," which is not unique per-user). When two education players with the same display name are online simultaneously, Floodgate appends a `_N` suffix to resolve the collision:

- First "Mark" to join: `+Mark`
- Second "Mark" to join: `+Mark_2`

---

## Troubleshooting

### "An error occurred" immediately after connecting

This usually means the server is not running EduGeyser. Verify you're using the EduGeyser jar, not standard Geyser.

### Education players have usernames like `+Mark` or `+Mark_2`

Education players get a `+` prefix (configurable in EduFloodgate's config). If two education players with the same display name are online at the same time, a `_N` suffix is appended to distinguish them.

### Regular Bedrock players can't connect anymore

EduGeyser supports both Education and Bedrock clients simultaneously. If Bedrock players can't connect:
- Make sure `auth-type` is set to `floodgate`
- Make sure EduFloodgate is installed alongside EduGeyser

### Students get "Invalid Tenant ID" or get disconnected immediately

The student's education token may have expired. Have them restart Education Edition to get a fresh login session.

---

## FAQ

### Does this work with any Java server?

Yes - Paper, Spigot, Fabric, Forge, or any other Java server that Geyser normally supports.

### Can Education and Bedrock players be on the same server?

Yes. EduGeyser detects each client type automatically. Both can play together.

### Do students need to install anything?

No. Students connect through Education Edition's built-in connection dialog using the Connection ID, or through join codes if configured.

### Is this safe for schools?

Students authenticate through their school's Microsoft 365 accounts. Player identity is cryptographically verified via Microsoft Education Services. The server can identify each student's school (tenant ID) and role (student/teacher).

### How many students can connect?

The same as any Geyser server - typically 50-100+ depending on your Java server's hardware.

### Does this cost anything?

The software is free. No Microsoft licenses, admin accounts, or subscriptions are required for basic education support.

### Can students from different schools join the same server?

Yes. The Connection ID works across all tenants. Students from any school can join using the same ID.

---

## Supported Platforms

Education Edition runs on these platforms, all of which can connect to EduGeyser:

| Platform | Supported |
|----------|-----------|
| Windows 10/11 | Yes |
| macOS | Yes |
| iPad / iOS | Yes |
| Chromebook | Yes |
| Android | Yes |
