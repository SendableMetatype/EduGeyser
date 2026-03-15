# EduGeyser Setup Guide

## Connect Minecraft Education Edition to Java Servers

EduGeyser is a modified version of [GeyserMC](https://geysermc.org/) that allows **Minecraft Education Edition** students to join **Java Edition** servers. It works alongside Bedrock Edition — both Education and regular Bedrock players can connect to the same server simultaneously.

---

## Table of Contents

- [Requirements](#requirements)
- [Quick Start (Which method should I use?)](#quick-start)
- [Method A: Standalone Mode (No Admin Access Needed)](#method-a-standalone-mode)
- [Method B: Dedicated Server Mode (Recommended for Schools)](#method-b-dedicated-server-mode)
- [How Students Connect](#how-students-connect)
- [Multi-Tenancy (Multiple Schools)](#multi-tenancy-multiple-schools)
- [Configuration Reference](#configuration-reference)
- [In-Game Management Commands](#in-game-management-commands)
- [Troubleshooting](#troubleshooting)
- [FAQ](#faq)

---

## Requirements

**Server side:**
- A Java Edition Minecraft server (Paper, Spigot, etc.)
- Java 17 or newer
- EduGeyser jar (replaces standard Geyser) — works as a plugin on Paper/Spigot or as a standalone proxy
- EduFloodgate jar (replaces standard Floodgate) — required for proper username/UUID handling

> **Simplest setup:** A single Paper server with EduGeyser and EduFloodgate as plugins. No proxy needed. If you already run a Velocity/BungeeCord network, EduGeyser works there too — install EduGeyser on the proxy and EduFloodgate on both the proxy and backend servers.

**Client side:**
- Minecraft Education Edition v1.21.132 or newer
- A Microsoft 365 Education account (school-provided)
- Windows, macOS, iPad, Chromebook, or Android

---

## Quick Start

Choose your setup method based on your situation:

| Situation | Method | What You Need |
|-----------|--------|---------------|
| **I'm a teacher or student** with no IT admin access | [Method A: Standalone](#method-a-standalone-mode) | Any M365 Education account + 5 minutes |
| **I'm an IT admin** or have Global Admin access to my school's M365 tenant | [Method B: Dedicated Server](#method-b-dedicated-server-mode) | Global Admin account + 10 minutes |
| **I want to test** with a personal tenant (not a school) | [Method B](#method-b-dedicated-server-mode) | A commercial M365 Education license ($3/month) |

**Method A** is simpler — students connect via the included **server list resource pack** (adds a permanent Servers button to their home screen) or a one-time link. **Method B** additionally makes your server appear in Education Edition's built-in server list — students just click "Play" without installing anything.

---

## Method A: Standalone Mode

*No admin access needed. Any student or teacher can set this up.*

### Overview

In Standalone Mode, you manually obtain a token from Microsoft's Education services by briefly hosting a world in Education Edition. The token authorizes your Geyser server to accept Education connections from your school's tenant. Students connect using the included server list resource pack (recommended) or a one-time link — the server does not appear in Education Edition's built-in server list.

### Step 1: Install EduGeyser

1. Download the latest [EduGeyser](https://github.com/SendableMetatype/EduGeyser/releases) and [EduFloodgate](https://github.com/SendableMetatype/EduFloodgate/releases) jars
2. Place them in your server's plugins folder (replacing standard Geyser and Floodgate if present)
3. Start the server once to generate config files, then stop it

### Step 2: Obtain a Server Token

You need a `serverToken` from Microsoft that proves your server is authorized for your school's tenant. Any student or teacher with an M365 Education account at the school can do this.

#### Option 1: Token Extractor Tool (Easiest)

1. Download the [Token Extractor Tool](education-tools/EduGeyser%20Token%20Extractor.exe) (Windows only, requires Administrator) — [source code](education-tools/Token_Extractor.py)
2. Run the tool as Administrator
3. Open Minecraft Education Edition
4. Open any world and click "Host" to start hosting
5. The tool will automatically capture the token and display it
6. Copy the token — it will also be saved to `server_token.txt`
7. Close the tool and stop hosting

> **Note:** The Token Extractor Tool uses a local proxy (mitmproxy) to intercept the Education client's request to Microsoft. It requires Administrator privileges to set up the proxy and install a temporary certificate. All proxy settings are restored automatically when the tool exits.

#### Option 2: Manual Fiddler Capture

If the Token Extractor Tool doesn't work for you, see the [Manual Token Capture Guide](#manual-token-capture-fiddler) in the appendix below.

### Step 3: Configure Geyser

Open your Geyser config file (`plugins/Geyser-Spigot/config.yml` on Paper/Spigot, or `plugins/Geyser-Velocity/config.yml` on Velocity) and set:

```yaml
# Set tenancy mode to standalone (no MESS registration needed)
edu-tenancy-mode: standalone

# Paste your server token(s) here
edu-server-tokens:
  - "paste-your-full-server-token-here"
```

The token is a long pipe-separated string that looks like:
```
03b5e7a1-cb09-4417-9e1a-c686b440b2c5|e2a49ff3-29ba-4cc2-...|2026-03-19T14:18:13.486Z|41863f21cdbeacbd1...
```

The first part is your school's tenant ID. EduGeyser extracts it automatically on startup and uses it to route connecting students to the correct token.

If you have students from **multiple schools**, add a token from each school:
```yaml
edu-server-tokens:
  - "token-from-school-A"
  - "token-from-school-B"
```

Also make sure `auth-type` is set to `floodgate`:
```yaml
auth-type: floodgate
```

### Step 4: Start the Server

Start your server. You should see a log message confirming Education Edition support is active.

### Step 5: Get Students Connected

You have two options — see [How Students Connect](#how-students-connect) for full details:

1. **Resource pack (recommended):** Share the included `education-tools/ServerButton.mcpack` with students. They open it, activate it, and get a permanent Servers button on their home screen where they can save your server.

2. **Connection link (quick):** Share this link and students click it to connect instantly:
   ```
   minecraftedu://connect/?serverUrl=YOUR_SERVER_IP:19132
   ```
   The included [`join-server.html`](education-tools/join-server.html) page makes this easy — edit the IP and share the file.

### Token Renewal

The server token expires after approximately 2 weeks. When it expires, students will get a connection error. Repeat Step 2 to get a fresh token and update `edu-server-tokens` in config.

Students using the resource pack won't need to re-enter the server address. Only the server-side token needs updating.

---

## Method B: Dedicated Server Mode

*Requires Global Admin access to an M365 Education tenant. Server appears in Education Edition's built-in server list.*

### Overview

In Dedicated Server Mode, EduGeyser registers itself with Microsoft's Education server services (MESS) using the official dedicated server API. Your server appears in Education Edition's server list — students can browse to it and click "Play" without needing a link or IP address. Token refresh is fully automatic.

### Step 1: Prepare Your M365 Tenant

You need **Global Administrator** access to a Microsoft 365 Education tenant.

**If you're a school IT admin:** You already have this.

**If you want a test tenant:** Purchase a commercial Minecraft Education license ($36/year or ~$3/month) at the Minecraft Education commercial purchase page. This creates a new M365 tenant where you are Global Admin. You can cancel within 7 days for a prorated refund.

#### Enable Dedicated Servers in Your Tenant

1. Go to the [Dedicated Server Admin Portal](https://education.minecraft.net/teachertools/en_US/dedicatedservers/)
2. Sign in with your Global Admin account
3. Click the **Settings** button in the top right
4. You'll find three toggles:
   - **Enable Dedicated Servers** — required, turn this on
   - **Allow teachers to manage servers** — recommended, lets teachers manage servers without needing Global Admin access
   - **Enable Cross-Tenant** — optional, turn this on if you want students from other schools to be able to connect
5. Save your settings

> **Note:** These are one-time tenant-level settings. Once enabled, you can register multiple servers under the same tenant. For additional questions about the dedicated server system, see the [Dedicated Server FAQ](https://edusupport.minecraft.net/hc/en-us/articles/41758309283348-Dedicated-Server-FAQ).

### Step 2: Install EduGeyser

1. Download the latest [EduGeyser](https://github.com/SendableMetatype/EduGeyser/releases) and [EduFloodgate](https://github.com/SendableMetatype/EduFloodgate/releases) jars
2. Place them in your server's plugins folder (replacing standard Geyser and Floodgate if present)
3. Start the server once to generate config files, then stop it

### Step 3: Configure Geyser

Open your Geyser config file and set:

```yaml
# This enables the automatic MESS integration.
# The name will appear in Education Edition's server list.
edu-server-name: "My School's Java Server"

# Maximum player count shown in the server list.
edu-max-players: 40

# Your server's public IP and Geyser port.
# If left empty, EduGeyser will try to auto-detect your public IP.
# Recommended to set this manually, especially if behind NAT.
edu-server-ip: "play.myserver.com:19132"
```

Leave `edu-server-id` empty. It will be filled automatically after first registration.

### Step 4: First Startup — Device Code Authentication

Start the server. EduGeyser will display a message like:

```
[EduGeyser] ============================================
[EduGeyser] Education Edition Authentication Required
[EduGeyser] Go to: https://microsoft.com/devicelogin
[EduGeyser] Enter code: ABCD1234
[EduGeyser] ============================================
```

1. Open the URL in a web browser (any device)
2. Enter the code shown in the console
3. Sign in with your **Global Admin** M365 Education account
4. Accept the permissions prompt

Once authenticated, EduGeyser will:
- Register the server with Microsoft's Education services
- Obtain a server token automatically
- Register your server's IP so it appears in the Education server list
- Begin sending heartbeats to keep the server listed as "online"
- Save all tokens to `edu_session.json` for automatic renewal on future restarts

You should see:
```
[EduGeyser] Server registered with MESS. Server ID: UXHG99K8JX2P
[EduGeyser] Server hosted at play.myserver.com:19132
[EduGeyser] Education Edition support is now active.
```

### Step 5: Enable the Server in the Admin Portal

The server is now registered but **not yet visible to students**. You need to enable it:

1. Go to the [Dedicated Server Admin Portal](https://education.minecraft.net/teachertools/en_US/dedicatedservers/)
2. Sign in with the same Global Admin account (or a teacher account, if "Allow teachers to manage servers" was enabled in Settings)
3. Find your new server in the list (it will have the server ID shown in the console, e.g., `UXHG99K8JX2P`)
4. Optionally change the display name
5. Click the button to **enable** the server
6. Click the button to **broadcast** it (makes it visible to all users in your tenant)
7. Optionally set a password for the server

Once enabled and broadcasted, the server appears in students' Education Edition server list.

### Step 6: Students Connect

Students open Minecraft Education Edition, go to the **Servers** tab, and your server appears in the list. They click "Play" to connect.

> **Tip:** Students can also connect immediately via the URI link (`minecraftedu://connect/?serverUrl=ip:port`) even before you complete the admin portal step — the portal step is only needed for server list visibility.

### After First Setup

On subsequent server restarts, EduGeyser silently refreshes all tokens in the background. The device code flow and admin portal steps are only needed once (or if you run `/geyser edu reset`).

### Enabling Cross-Tenant Access

There are two ways to allow students from **other schools** (other M365 tenants) to connect:

#### Option 1: MESS Cross-Tenant (Portal-Based)

1. Go to the [Dedicated Server Admin Portal](https://education.minecraft.net/teachertools/en_US/dedicatedservers/)
2. Click **Settings** (top right) and enable **Cross-Tenant** if you haven't already
3. Find your server in the list and enable **Cross-Tenant** on that specific server as well

Students from other tenants can then see and join your server. Their school's admin does NOT need to enable anything on their end. Cross-tenant is one-sided.

For more details, see the [Dedicated Server FAQ](https://edusupport.minecraft.net/hc/en-us/articles/41758309283348-Dedicated-Server-FAQ).

#### Option 2: Hybrid Mode (Token-Based)

If cross-tenant via the portal doesn't work for your situation, you can use **hybrid mode** to manually add tokens for additional schools:

```yaml
edu-tenancy-mode: hybrid

# Tokens for additional schools (your primary school is handled by MESS automatically)
edu-server-tokens:
  - "token-from-school-B"
  - "token-from-school-C"
```

In hybrid mode, your primary school connects via the MESS-registered token (automatic). Additional schools connect using tokens you obtain via the [Token Extractor Tool](education-tools/EduGeyser%20Token%20Extractor.exe) or [Fiddler](#manual-token-capture-fiddler), using an account from each additional school.

Each token contains the school's tenant ID. EduGeyser parses them on startup and routes connecting students to the correct token automatically.

---

## How Students Connect

### If Using Dedicated Server Mode (Method B)

Students open Education Edition → **Servers** tab → find your server → click **Play**. That's it.

You can also distribute the resource pack (see below) so students can add additional servers themselves.

### If Using Standalone Mode (Method A)

#### Recommended: EduGeyser Server List Resource Pack

The best way for students to connect is the included **EduGeyser Server List resource pack**. It adds a permanent **Servers** button to Education Edition's home screen — the same server list interface that regular Bedrock players have, with the ability to save servers by name, IP, and port.

**Why this is the best option:**
- One-click install (open the `.mcpack` file) — just as easy as clicking a URI link
- But unlike a link, it **permanently integrates** into the home screen
- Students can save your server and reconnect any time without needing a new link
- Multiple servers can be saved
- Familiar Bedrock-style UI — students who've used regular Minecraft will recognize it

**Setup:**
1. Share the [`ServerButton.mcpack`](education-tools/ServerButton.mcpack) file with students (via Teams, Classroom, email, or a shared drive)
2. Students open the file — Education Edition launches and installs the resource pack automatically
3. Students go to **Settings → Global Resources** and activate the pack
4. Back on the Play screen, a **Servers** button now appears
5. Students click **Add Server**, enter your server's name, IP, and port, then click **Save**
6. The server is now permanently saved — students just click it to connect in the future

This works because the underlying connection code for direct IP connections already exists in the Education client — Microsoft only removed the UI. The resource pack re-adds it.

#### Alternative: Connection Link (Quick, One-Time)

If you need students connected immediately without installing anything, share a link:

```
minecraftedu://connect/?serverUrl=YOUR_SERVER_IP:19132
```

Or share the included `join-server.html` page (edit the server IP first). Students click it and Education Edition opens and connects directly. Works on all platforms.

The downside: this is a one-time connection. Students would need the link again each time they want to rejoin. For repeated use, the resource pack is much better.

---

## Multi-Tenancy (Multiple Schools)

EduGeyser supports three tenancy modes that control how server tokens are managed:

| Mode | Config Value | How Tokens Work |
|------|-------------|-----------------|
| **Official** | `official` | MESS handles everything. One token from your registration serves all tenants. `edu-server-tokens` is ignored. This is the default. |
| **Hybrid** | `hybrid` | MESS handles your primary school. Additional schools are supported via tokens in `edu-server-tokens`. |
| **Standalone** | `standalone` | No MESS registration at all. All tokens come from `edu-server-tokens`. |

### How Token Routing Works

Each server token contains the school's tenant ID as its first field (before the first `|`). On startup, EduGeyser parses every token and builds a routing table mapping tenant IDs to tokens.

When an Education Edition student connects, EduGeyser extracts their school's tenant ID from their login chain and looks it up in the routing table. If a match is found, that token is used for the handshake. If no match is found, EduGeyser falls back to the MESS-registered token (official/hybrid modes), and if that also fails, the student is disconnected with a message telling them (and the server admin) which tenant ID was not configured.

### Example: Standalone with Two Schools

```yaml
edu-tenancy-mode: standalone
edu-server-tokens:
  - "03b5e7a1-...|e2a49ff3-...|2026-03-25T...|41863f21..."
  - "a1b2c3d4-...|f5e6d7c8-...|2026-03-25T...|8bc9af01..."
```

On startup you'll see:
```
[EduTenancy] Registered token for tenant 03b5e7a1-... (source: config edu-server-tokens)
[EduTenancy] Registered token for tenant a1b2c3d4-... (source: config edu-server-tokens)
[EduTenancy] Tenancy mode: standalone, registered tenants: 2
```

### Example: Hybrid with MESS + One Extra School

```yaml
edu-server-name: "My School's Server"
edu-tenancy-mode: hybrid
edu-server-tokens:
  - "token-from-partner-school"
```

Your primary school connects via MESS (automatic token). The partner school connects via the config token.

---

## Configuration Reference

### EduGeyser Config (`config.yml`)

| Option | Default | Description |
|--------|---------|-------------|
| `edu-tenancy-mode` | `"official"` | How tokens are managed. `"official"` = MESS only, `"hybrid"` = MESS + config tokens, `"standalone"` = config tokens only. |
| `edu-server-tokens` | `[]` | List of server tokens for hybrid/standalone mode. Each token's tenant ID is extracted automatically on startup. |
| `edu-server-name` | `""` | Server name for the Education server list. Setting this enables Dedicated Server Mode (MESS registration). |
| `edu-server-id` | `""` | Auto-filled after first MESS registration. Don't set manually unless restoring a backup. |
| `edu-server-ip` | `""` | Public IP:port for MESS registration. Auto-detected if empty. Set manually if behind NAT. |
| `edu-max-players` | `40` | Player limit shown in the Education server list (MESS only). |
| `edu-auth-mode` | `"verified"` | `"verified"` or `"permissive"`. Verified mode checks the Education client's authorization token. |

### EduFloodgate Config (`config.yml`)

| Option | Default | Description |
|--------|---------|-------------|
| `education-prefix` | `"#"` | Prefix for Education player usernames (e.g., `#StudentName7b91`). Must be different from the Bedrock prefix (`.`). |

### Required Geyser Settings

These must be set for Education Edition support to work:

```yaml
# In config.yml:
auth-type: floodgate
```

> **Note:** `validate-bedrock-login` (under advanced settings) should remain `true` — this authenticates regular Xbox/Bedrock players normally. EduGeyser handles Education players separately in code, bypassing the Xbox check only for clients that identify as Education Edition. You do not need to weaken security for Bedrock players.

---

## In-Game Management Commands

| Command | Description |
|---------|-------------|
| `/geyser edu` or `/geyser edu status` | Shows Education system status: active/inactive, server ID, IP, token expiry, player count, auth mode |
| `/geyser edu players` | Lists all connected Education Edition players with their school tenant and role (student/teacher) |
| `/geyser edu reset` | Deletes saved tokens and restarts device code authentication. Use if authentication breaks. |
| `/geyser edu register` | Forces a full re-registration with Microsoft's Education services. |

Permission: `geyser.command.edu`

---

## Troubleshooting

### Students get disconnected with "Your school is not configured on this server"

The server has no token matching the connecting student's school tenant.

- **Standalone Mode:** Make sure you have a token from the student's school in `edu-server-tokens`. Each token only works for the school it was obtained from.
- **Dedicated Server Mode:** Run `/geyser edu reset` to re-authenticate. If the student is from a different school, enable cross-tenant or use hybrid mode to add their school's token.
- **Token expired:** Tokens last about 2 weeks. Get a fresh one and update the config.

### "An error occurred" immediately after connecting

This usually means the server is not running EduGeyser (the modified Geyser with Education support). Verify you're using the EduGeyser jar, not standard Geyser.

### Students can't find the server in their server list

- The server list only works with **Dedicated Server Mode (Method B)**. Standalone Mode requires a connection link.
- Verify the server is registered: run `/geyser edu status` and check that it shows as active with a server ID.
- Make sure you **enabled** and **broadcasted** the server in the [Admin Portal](https://education.minecraft.net/teachertools/en_US/dedicatedservers/) (Step 5 of Method B). Registration alone is not enough — the server must be explicitly enabled.
- The Dedicated Server feature must be enabled in the school's M365 tenant by a Global Admin.
- If using cross-tenant, cross-tenant must be enabled in **Settings** (top right) AND on the specific server (in the admin portal).

See the [Dedicated Server FAQ](https://edusupport.minecraft.net/hc/en-us/articles/41758309283348-Dedicated-Server-FAQ) for additional help.

### "Your sign-in was successful but does not meet the criteria to access this resource"

The school's M365 tenant has **Conditional Access** policies that block the device code authentication flow. This is common in large school districts with strict security policies.

**Workaround:** Use Standalone Mode (Method A) instead. The Token Extractor Tool works around conditional access by capturing the token through Education Edition's own authentication flow.

### Device code flow times out / no prompt appears

- Make sure you're signing in with a **Global Admin** account, not a regular teacher or student account.
- The device code expires after about 15 minutes. If it times out, restart the server to get a new code.
- Check that your tenant has an active Minecraft Education license.

### Education players have weird usernames like `#NielsI7b91`

This is the Education username format: `#` prefix + player name + 4-character tenant hash. The hash distinguishes players from different schools who might share the same name. It is derived from the school's tenant ID.

### Server shows "offline" in the Education server list but students can still connect

The heartbeat update may have stopped. Run `/geyser edu status` to check. If the token has expired, run `/geyser edu reset`.

Even when showing "offline," students can still connect by clicking the server entry — the status is cosmetic.

### Regular Bedrock players can't connect anymore

EduGeyser supports both Education and Bedrock clients simultaneously. If Bedrock players can't connect:
- Make sure `auth-type` is set to `floodgate`
- Make sure EduFloodgate is installed alongside EduGeyser
- Make sure you haven't accidentally set the RakNet ping edition to `MCEE` (this would hide the server from Bedrock clients)

---

## FAQ

### Does this work with any Java server?

Yes — Paper, Spigot, Fabric, Forge, or any other Java server that Geyser normally supports. The simplest setup is a single Paper server with EduGeyser and EduFloodgate as plugins.

### Can Education and Bedrock players be on the same server?

Yes. EduGeyser detects each client type automatically and applies the correct protocol handling. Both can play together.

### Do students need to install anything?

No additional software is needed. For the best experience, distribute the included server list resource pack (`.mcpack` file) — students open it once and get a permanent Servers button on their home screen. Without the resource pack, students can still connect via a one-time link.

### Is this safe for schools?

EduGeyser preserves Microsoft's tenant-based authentication. Students authenticate through their school's Microsoft 365 accounts. The server token system ensures only authorized servers can accept Education connections.

### How many students can connect?

The same as any Geyser server — typically 50-100+ depending on your Java server's hardware. The `edu-max-players` config only affects the number displayed in the Education server list, not an actual limit.

### Does this cost anything?

The software is free. If you need your own M365 Education tenant for testing (Method B), a commercial license costs $36/year or ~$3/month.

### How long do tokens last?

- **Standalone/Hybrid config tokens:** ~2 weeks. Must be manually refreshed.
- **Dedicated Server Mode (MESS):** Tokens refresh automatically every 30 minutes. No manual intervention needed after initial setup.

### Can students from different schools join the same server?

Yes, there are two ways:

1. **Cross-tenant via MESS** (Dedicated Server Mode): Enable cross-tenant in the admin portal settings and on the server. Students from other schools can join without their admin needing to do anything.
2. **Hybrid or standalone mode**: Add a server token from each school to `edu-server-tokens` in the config. EduGeyser routes each student to the correct token based on their school's tenant ID. This works with any setup method.

---

## Appendix

### Manual Token Capture (Fiddler)

If the Token Extractor Tool doesn't work, you can capture the token manually using Fiddler Classic:

**Prerequisites:**
- [Fiddler Classic](https://www.telerik.com/fiddler/fiddler-classic) installed (free)
- Run this command as Administrator to allow capturing Education Edition traffic:
  ```
  CheckNetIsolation LoopbackExempt -a -n="Microsoft.MinecraftEducationEdition_8wekyb3d8bbwe"
  ```

**Fiddler Setup:**
1. Open Fiddler Classic
2. Go to **Tools → Options → HTTPS**
3. Check **"Capture HTTPS CONNECTs"**
4. Check **"Decrypt HTTPS traffic"** (accept certificate prompts)
5. Check **"Ignore server certificate errors"**
6. Check **WinConfig** and verify Education Edition is listed for exemption
7. Make sure "Capturing" shows in the bottom-left (press F12 to toggle)

**Important:** Education Edition uses WinHTTP, not WinINET. If Fiddler isn't capturing traffic, you may also need to set the WinHTTP proxy manually:
```
netsh winhttp set proxy 127.0.0.1:8888
```
Reset after capturing:
```
netsh winhttp reset proxy
```

**Capture Process:**
1. Clear Fiddler's session list (Ctrl+X)
2. Open a world in Education Edition (singleplayer)
3. Start hosting the world (click "Host")
4. In Fiddler, look for a POST request to `discovery.minecrafteduservices.com/host`
5. Click the request, go to the **Inspectors** tab → **Headers**
6. Copy the full value of the `Authorization: Bearer` header (everything after "Bearer ")

**Exchange for server token (PowerShell):**

```powershell
$token = "PASTE_YOUR_BEARER_TOKEN_HERE"
$response = Invoke-RestMethod -Method Post `
  -Uri "https://discovery.minecrafteduservices.com/host" `
  -Headers @{"Authorization"="Bearer $token"; "api-version"="2.0"} `
  -ContentType "application/json" `
  -Body '{"build":12232001,"locale":"en_US","maxPlayers":40,"networkId":"1234567890","playerCount":0,"protocolVersion":1,"serverDetails":"GeyserProxy","serverName":"My Server","transportType":2}'
$response.serverToken
```

Copy the output — this is your `education-token` for the Geyser config. Bearer tokens expire after ~75-90 minutes, so don't wait too long between capturing and exchanging.

### Supported Platforms

Education Edition runs on these platforms, all of which can connect to EduGeyser:

| Platform | Connection Methods |
|----------|-------------------|
| Windows 10/11 | Resource pack, URI link / connection page, server list (Method B) |
| macOS | Resource pack, URI link, server list (Method B) |
| iPad / iOS | Resource pack, URI link, server list (Method B) |
| Chromebook | Resource pack, URI link, server list (Method B) |
| Android | Resource pack, URI link, server list (Method B) |

The `minecraftedu://connect` URI scheme works on all platforms where Education Edition is installed.
