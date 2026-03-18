# Manual Token Capture (Fiddler)

This is an alternative method for obtaining a server token if the [EduGeyser Token Tool](education-tools/EduGeyser%20Token%20Tool.exe) doesn't work for your situation. It requires intercepting Education Edition's network traffic using Fiddler Classic.

> **Note:** Most users should use the Token Tool instead — it's simpler and doesn't require Minecraft Education Edition to be installed on the same machine.

## Prerequisites

- [Fiddler Classic](https://www.telerik.com/fiddler/fiddler-classic) installed (free)
- Minecraft Education Edition installed on the same Windows machine
- Run this command as Administrator to allow capturing Education Edition traffic:
  ```
  CheckNetIsolation LoopbackExempt -a -n="Microsoft.MinecraftEducationEdition_8wekyb3d8bbwe"
  ```

## Fiddler Setup

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

## Capture Process

1. Clear Fiddler's session list (Ctrl+X)
2. Open a world in Education Edition (singleplayer)
3. Start hosting the world (click "Host")
4. In Fiddler, look for a POST request to `discovery.minecrafteduservices.com/host`
5. Click the request, go to the **Inspectors** tab → **Headers**
6. Copy the full value of the `Authorization: Bearer` header (everything after "Bearer ")

## Exchange for Server Token (PowerShell)

```powershell
$token = "PASTE_YOUR_BEARER_TOKEN_HERE"
$response = Invoke-RestMethod -Method Post `
  -Uri "https://discovery.minecrafteduservices.com/host" `
  -Headers @{"Authorization"="Bearer $token"; "api-version"="2.0"} `
  -ContentType "application/json" `
  -Body '{"build":12232001,"locale":"en_US","maxPlayers":40,"networkId":"1234567890","playerCount":0,"protocolVersion":1,"serverDetails":"GeyserProxy","serverName":"My Server","transportType":2}'
$response.serverToken
```

Copy the output — this is your server token for the `server-tokens` list in the EduGeyser config. Bearer tokens expire after ~75-90 minutes, so don't wait too long between capturing and exchanging.

## Using the Token

Paste the token into your EduGeyser config:

```yaml
education:
  tenancy-mode: standalone
  server-tokens:
    - "paste-your-token-here"
```

Save the config and restart your server.
