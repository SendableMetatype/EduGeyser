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

Education Edition does not natively have a server list for direct IP connections. There are two ways for students to connect:

### Connection Link (Quickest)

Share this link with students. They click it and Education Edition opens and connects directly:

```
minecraftedu://connect/?serverUrl=YOUR_SERVER_IP:19132
```

The included [`join-server.html`](education-tools/join-server.html) page makes this easy - edit the IP and share the file. Students click the button and connect.

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

## Server List Broadcasting (Optional)

If you want your server to appear automatically in Education Edition's built-in server browser (so students don't need to enter an IP at all), install the [Geyser Education Extension](https://github.com/SendableMetatype/Geyser-Education-Extension).

This is completely optional and requires a Global Admin account for each M365 Education tenant you want to broadcast to. See the extension's README for setup instructions.

---

## EduFloodgate Configuration

EduFloodgate uses a separate prefix for education players to distinguish them from regular Bedrock players:

```yaml
# In EduFloodgate config.yml:
education-prefix: "+"
education-hash: true
```

- `education-prefix` - prefix for education player usernames (default: `+`)
- `education-hash` - appends a 4-character tenant hash to prevent name collisions between students from different schools (default: `true`)

With default settings, an education player named "Mark" from tenant `03b5e7a1-...` would appear as `+Mark7b91`.

---

## Troubleshooting

### "An error occurred" immediately after connecting

This usually means the server is not running EduGeyser. Verify you're using the EduGeyser jar, not standard Geyser.

### Education players have weird usernames like `+Mark7b91`

This is the education username format: `+` prefix + player name + 4-character tenant hash. The hash distinguishes players from different schools who might share the same name. It is derived from the school's tenant ID. You can disable the hash in EduFloodgate's config by setting `education-hash: false`.

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

No additional software. For the best experience, distribute the server list resource pack so students get a permanent Servers button. Without it, students connect via a one-time link.

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
| Windows 10/11 | Resource pack, URI link, server list (with extension) |
| macOS | Resource pack, URI link, server list (with extension) |
| iPad / iOS | Resource pack, URI link, server list (with extension) |
| Chromebook | Resource pack, URI link, server list (with extension) |
| Android | Resource pack, URI link, server list (with extension) |

The `minecraftedu://connect` URI scheme works on all platforms where Education Edition is installed.
