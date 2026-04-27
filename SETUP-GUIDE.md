# EduGeyser Setup Guide

## Connect Minecraft Education Edition to Java Servers

EduGeyser is a modified version of [GeyserMC](https://geysermc.org/) that allows **Minecraft Education Edition** students to join **Java Edition** servers. It works alongside regular Bedrock Edition - both Education and Bedrock players can connect to the same server simultaneously.

---

## Table of Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [How Students Connect](#how-students-connect)
- [Server List Broadcasting (Optional)](#server-list-broadcasting-optional)
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

> **Simplest setup:** A single Paper server with EduGeyser and EduFloodgate as plugins. No proxy needed. If you already run a Velocity/BungeeCord network, EduGeyser works there too - install EduGeyser on the proxy and EduFloodgate on both the proxy and backend servers.

**Client side:**
- Minecraft Education Edition v1.21.132 or newer
- A Microsoft 365 Education account (school-provided)
- Windows, macOS, iPad, Chromebook, or Android

---

## Installation

1. Download the latest [EduGeyser](https://github.com/SendableMetatype/EduGeyser/releases) and [EduFloodgate](https://github.com/SendableMetatype/EduFloodgate/releases) jars
2. Place them in your server's plugins folder (replacing standard Geyser and Floodgate if present)
3. Make sure `auth-type` is set to `floodgate` in the Geyser config:

```yaml
auth-type: floodgate
```

4. Start the server

That's it. Education clients can now connect. No tokens, no configuration, no admin accounts needed.

---

## How Students Connect

Education Edition does not natively have a server list for direct IP connections. There are several ways for students to connect:

### Connection Link (Quickest)

Share this link with students. They click it and Education Edition opens and connects directly:

```
minecraftedu://connect/?serverUrl=YOUR_SERVER_IP&serverPort=19132
```

The EduGeyser connection page at [edugeyser.org](https://edugeyser.org) makes this easy - share the link with students, they enter the server IP and port, and click Join. Education Edition launches and connects directly. Students can also save servers for quick access later.

The downside: this is a one-time connection. Students need the link again each time they want to rejoin. For repeated use, the resource pack is better.

### Server List Resource Pack (Recommended for Repeated Use)

The included **EduGeyser Server List resource pack** adds a permanent **Servers** button to Education Edition's home screen - the same server list interface that regular Bedrock players have.

**Setup:**
1. Share the [`ServerButton.mcpack`](education-tools/ServerButton.mcpack) file with students (via Teams, Classroom, email, or a shared drive)
2. Students open the file - Education Edition installs the resource pack automatically
3. Students go to **Settings > Global Resources** and activate the pack
4. Back on the Play screen, a **Servers** button now appears
5. Students click **Add Server**, enter your server's name, IP, and port, then click **Save**
6. The server is permanently saved - students just click it to connect

---

## Geyser Education Extension (Optional)

The [Geyser Education Extension](https://github.com/SendableMetatype/Geyser-Education-Extension) adds three additional ways for students to find and join your server:

- **Join Codes** — Students enter a code in Education Edition's built-in "Join Code" screen, or click a share link. No IP address needed. Any M365 Education account can create codes.
- **Connection ID** — A single 10-digit number that students enter directly in Education Edition's connection dialog. Works across all tenants, so students from different schools can join with the same ID.
- **Server List Broadcasting** — Your server appears automatically in Education Edition's built-in server browser. Requires Global Admin access to each M365 Education tenant you want to broadcast to.

This extension is completely optional. Education support in EduGeyser works out of the box, and students can always connect via direct IP or a connection link. See the extension's README for setup instructions.

---

## EduFloodgate Configuration

EduFloodgate uses a separate prefix for education players to distinguish them from regular Bedrock players:

```yaml
# In EduFloodgate config.yml:
education-prefix: "+"
```

- `education-prefix` - prefix for education player usernames (default: `+`)

Education Edition usernames often collide (the Entra default format is "FirstnameLastInitial," which is not unique per-user). When two education players with the same display name are online simultaneously, Floodgate appends a `_N` suffix to resolve the collision:

- First "Mark" to join → `+Mark`
- Second "Mark" to join → `+Mark_2`

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

No additional software. Students can connect via a direct IP link. For easier access, the optional [Geyser Education Extension](https://github.com/SendableMetatype/Geyser-Education-Extension) provides join codes, a cross-tenant connection ID, and server list broadcasting. A resource pack is also available that adds a permanent Servers button to Education Edition.

### Is this safe for schools?

Students authenticate through their school's Microsoft 365 accounts. Player identity is cryptographically verified via Microsoft Education Services. The server can identify each student's school (tenant ID) and role (student/teacher).

### How many students can connect?

The same as any Geyser server - typically 50-100+ depending on your Java server's hardware.

### Does this cost anything?

The software is free. No Microsoft licenses, admin accounts, or subscriptions are required for basic education support.

### Can students from different schools join the same server?

Yes. Any student from any M365 Education tenant can connect without any server-side configuration.

---

## Appendix

### Supported Platforms

Education Edition runs on these platforms, all of which can connect to EduGeyser:

| Platform | Connection Methods |
|----------|-------------------|
| Windows 10/11 | URI link, resource pack, join code, connection ID, server list (last three with extension) |
| macOS | URI link, resource pack, join code, connection ID, server list (last three with extension) |
| iPad / iOS | URI link, resource pack, join code, connection ID, server list (last three with extension) |
| Chromebook | URI link, resource pack, join code, connection ID, server list (last three with extension) |
| Android | URI link, resource pack, join code, connection ID, server list (last three with extension) |

The `minecraftedu://connect` URI scheme works on all platforms where Education Edition is installed.
