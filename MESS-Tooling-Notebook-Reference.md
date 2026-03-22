# MESS Tooling API: Complete Reference from Official Notebook

**Source:** Microsoft's official Jupyter Notebook tooling, downloaded from `https://aka.ms/MCEDU-DS-Tooling`
**Notebook URL resolves to:** `https://notebooks.minecrafteduservices.com/docs/DedicatedServerSampleTooling.ipynb`
**API Documentation:** `https://notebooks.minecrafteduservices.com/docs/DedicatedServerApiDocs.html`
**Admin Portal:** `https://aka.ms/MCEDU-DS-Portal` → `https://education.minecraft.net/teachertools/en_US/dedicatedservers/`
**Date of analysis:** March 16, 2026

---

## 1. Authentication (CRITICAL DISCOVERY)

### App IDs — Three Distinct Ones Exist

| App ID | Name / Purpose | Supports Device Code Flow | Used By |
|--------|---------------|--------------------------|---------|
| `1c91b067-6806-44a5-8d2d-3137e625f5b8` | **Admin Tooling App ID** (from notebook) | **YES** | Official notebook for all `tooling/*` endpoints |
| `b36b1432-1a1c-4c82-9b76-24de1cab42f2` | Education Client App ID | YES | Minecraft Education client, `server/register`, `server/fetch_token` |
| `f8ba6a93-3dc8-4753-9f89-886138158d8b` | Admin Portal Web App ID ("Minecraft Education AI Web") | NO (browser redirect only) | Admin Portal web UI at `education.minecraft.net` |

**Key finding:** The notebook uses app ID `1c91b067-6806-44a5-8d2d-3137e625f5b8`, which is different from both the Education client app ID and the browser admin portal app ID. This app ID supports the device code flow and is accepted by all `tooling/*` endpoints.

### Scopes

```
["16556bfc-5102-43c9-a82a-3ea5e4810689/.default"]
```

The scope is the MESS resource ID (`16556bfc-5102-43c9-a82a-3ea5e4810689`) with `/.default` appended. This is the same resource ID used by the Education client app ID.

### Authority URL

```
https://login.microsoftonline.com/organizations
```

The notebook uses `/organizations` as the MSAL authority, NOT `/common/oauth2`. The `/organizations` authority restricts authentication to work/school (Entra ID) accounts only — personal Microsoft accounts are excluded. This is more correct for Education scenarios.

### Authentication Flow (from notebook code)

```python
import msal

adminToolingAppId = "1c91b067-6806-44a5-8d2d-3137e625f5b8"
minecraftEduServicesScopes = ["16556bfc-5102-43c9-a82a-3ea5e4810689/.default"]

app = msal.PublicClientApplication(
    adminToolingAppId,
    authority="https://login.microsoftonline.com/organizations",
    token_cache=msal.TokenCache()
)

# Token acquisition priority:
# 1. Check cached token validity (time.time() < token.expiry)
# 2. Try silent refresh: app.acquire_token_silent(scopes, account)
# 3. Fall back to device code flow: app.initiate_device_flow(scopes) → app.acquire_token_by_device_flow(flow)
```

### Token Decoding

The notebook decodes the access token JWT (without signature verification) to extract:
- `unique_name` — username (used for silent refresh account lookup)
- `name` — display name
- `tid` — tenant ID
- `exp` — expiration timestamp

### Sample Output

```
Acquiring tooling token:
To sign in, use a web browser to open the page https://microsoft.com/devicelogin and enter the code AMBFBVS4X to authenticate.
Access token for user 'Admin' in tenant '01234567-abcd-4567-0123-YourTenantID' expires on 2025-09-24, at 09:46:23 PM
```

---

## 2. API Base URL

```
https://dedicatedserver.minecrafteduservices.com/
```

The notebook prepends this to all endpoint paths.

---

## 3. Request Pattern

The notebook uses a `RequestBuilder` class. Key observations:

- All requests include `Authorization: Bearer {access_token}` header
- `tooling/edit_server_info` requires an additional `api-version: 2.0` header
- Other tooling endpoints do NOT use the `api-version` header
- The `onBehalfOfTenantId` query parameter is available on most tooling endpoints for partner access (via `.obo(tenantId)`)
- Requests use JSON content type

---

## 4. Field Naming Convention (CRITICAL DISCREPANCY)

**The notebook uses PascalCase for ALL request and response fields.**

This is different from the API documentation HTML page, which uses camelCase in its examples.

### Cross-reference: Notebook vs API Docs vs EduGeyser

| Notebook (PascalCase) | API Docs (camelCase) | EduGeyser (current) | Endpoint |
|----------------------|---------------------|--------------------| ---------|
| `ServerId` | `serverId` | `serverId` | `tooling/edit_server_info` |
| `ServerName` | `serverName` | `serverName` | `tooling/edit_server_info` |
| `Enabled` | `enabled` | `enabled` | `tooling/edit_server_info` |
| `IsBroadcasted` | `isBroadcasted` | `isBroadcasted` | `tooling/edit_server_info` |
| `SharingEnabled` | `sharingEnabled` | `sharingEnabled` | `tooling/edit_server_info` |
| `CrossTenantAllowed` | `crossTenantAllowed` | `crossTenantAllowed` | `tooling/edit_server_info` |
| `Passcode` | `password` | `password` | `tooling/edit_server_info` |
| `DisablePasscodeProtection` | `disablePasswordProtection` | `disablePasswordProtection` | `tooling/edit_server_info` |
| `ServerIds` | `serverIds` | `serverIds` | `tooling/fetch_server_info` |
| `DedicatedServerEnabled` | `dedicatedServerEnabled` | N/A | `tooling/edit_tenant_settings` |
| `TeachersAllowed` | `teachersAllowed` | N/A | `tooling/edit_tenant_settings` |
| `CrossTenantAllowed` | `crossTenantAllowed` | N/A | `tooling/edit_tenant_settings` |
| `UpsertPartnerPermissions` | `upsertPartnerPermissions` | N/A | `tooling/edit_tenant_settings` |
| `RemovePartnerPermissions` | `removePartnerPermissions` | N/A | `tooling/edit_tenant_settings` |
| `GuestTenantIds` | `guestTenantIds` | N/A | `tooling/create_server_invite` |
| `ServerInvitesSent` | `serverInvitesSent` | N/A | `tooling/fetch_tenant_settings` response |
| `ServerInvitesReceived` | `serverInvitesReceived` | N/A | `tooling/fetch_tenant_settings` response |
| `AddBroadcastedServers` | `addBroadcastedServers` | N/A | `tooling/edit_tenant_settings` |
| `RemoveBroadcastedServers` | `removeBroadcastedServers` | N/A | `tooling/edit_tenant_settings` |

### Password vs Passcode

The notebook uses `Passcode` and `DisablePasscodeProtection`. The API docs use `password` and `disablePasswordProtection`. These are different field names for the same functionality. The notebook also sends passcodes as **plaintext strings** (not encrypted), possibly enabled by the `api-version: 2.0` header. The API docs state passwords must be encrypted with the RSA key from `public_keys/encryption`.

**Hypothesis:** The `api-version: 2.0` header may enable a newer API version that accepts plaintext passcodes and uses the PascalCase field names. Without this header, the API may expect the older camelCase format with encrypted passwords.

---

## 5. Tooling Endpoints — Complete Reference from Notebook

### 5.1 `tooling/fetch_all_server_ids` — GET

Returns all server IDs the tenant owns or has guest access to.

```python
RequestBuilder("tooling/fetch_all_server_ids").get()
```

No request body. No special headers.

**Response format (from API docs):**
```json
{
    "serverIdList": ["US8IO2XK5AHF", "0LPN6Z208PHE"]
}
```

Note: Response field is `serverIdList` (camelCase). Unknown if PascalCase variant exists in response.

---

### 5.2 `tooling/fetch_server_info` — POST

Retrieves registration info for specified servers.

```python
RequestBuilder("tooling/fetch_server_info") \
    .members({ "ServerIds": ["YourServerID"] }) \
    .post()
```

**Request body:**
```json
{
    "ServerIds": ["YourServerID"]
}
```

**Key notes from notebook:**
- "If the server is shared with other tenants, some of these properties are unique per tenant"
- "The owning tenant's `Enabled` field disables the server for all tenants if set to `False`"

**Response format (from API docs):**
```json
{
    "serverInfoDictionary": {
        "OHMNTT69T7XH": {
            "serverName": "Lorenzo's Cool Arctic Dreamhouse",
            "playerCount": 3,
            "maxPlayers": 25,
            "health": 1,
            "isBroadcasted": true,
            "isPasswordProtected": false,
            "isOwningTenant": true,
            "isEnabled": true,
            "isSharingEnabled": true
        }
    }
}
```

May return 206 partial success if some server IDs are inaccessible.

---

### 5.3 `tooling/edit_server_info` — POST

Configures a server registration. **Requires `api-version: 2.0` header.**

#### Enable, name, and broadcast a server (full setup):
```python
RequestBuilder("tooling/edit_server_info") \
    .header("api-version", "2.0") \
    .members({
        "ServerId": "YourServerID",
        "ServerName": "Your cool server",
        "Enabled": True,
        "IsBroadcasted": True,
        "SharingEnabled": True,
        "CrossTenantAllowed": True,
    }) \
    .post()
```

**Note from notebook:** `CrossTenantAllowed` has no effect on servers your tenant does not own. If your tenant accepted an invite to the server from another tenant, they own the server.

All fields except `ServerId` are optional — you can update individual properties.

#### Set a passcode:
```python
RequestBuilder("tooling/edit_server_info") \
    .header("api-version", "2.0") \
    .members({
        "ServerId": "YourServerID",
        "Passcode": "MyPasscode"
    }) \
    .post()
```

Note: The notebook sends the passcode as **plaintext**. No encryption step.

#### Disable a passcode:
```python
RequestBuilder("tooling/edit_server_info") \
    .header("api-version", "2.0") \
    .members({
        "ServerId": "YourServerID",
        "DisablePasscodeProtection": True
    }) \
    .post()
```

#### As a guest tenant (limited fields):
Guest tenants can only modify: `ServerName`, `Enabled`, `SharingEnabled`, `IsBroadcasted`. They cannot modify `CrossTenantAllowed` or passcodes.

---

### 5.4 `tooling/delete_server_registration` — POST

Permanently deletes a server registration.

```python
RequestBuilder("tooling/delete_server_registration") \
    .members({ "ServerId": "YourServerID" }) \
    .post()
```

**WARNING from notebook:** "If you own the server, this will also delete the registration for guest tenants."

May return 200, 204, or 206 — all are successes with different internal meanings.

---

### 5.5 `tooling/fetch_tenant_settings` — GET

Reads current tenant configuration.

```python
RequestBuilder("tooling/fetch_tenant_settings").get()
```

No request body.

**Response format (from API docs):**
```json
{
    "broadcastedServers": ["US8IO2XK5AHF", "0LPN6Z208PHE"],
    "dedicatedServerEnabled": true,
    "crossTenantAllowed": true,
    "serverInvitesSent": {
        "8f7aa7ad-...": "US8IO2XK5AHF"
    },
    "serverInvitesReceived": ["US8IO2XK5AHF"],
    "teachersAllowed": false,
    "partnerPermissions": {
        "8f7aa7ad-...": 0
    }
}
```

Note: The notebook's `fetchAndPrintServerInvites` helper accesses `response["ServerInvitesSent"]` and `response["ServerInvitesReceived"]` — PascalCase in the response. Need to test which casing the API actually returns.

Partners see a subset of the response based on their permission level.

---

### 5.6 `tooling/edit_tenant_settings` — POST

Enables/disables dedicated servers, teacher access, cross-tenant play, and partner permissions.

#### Enable all features (one-time admin setup):
```python
RequestBuilder("tooling/edit_tenant_settings") \
    .members({
        "CrossTenantAllowed": True,
        "DedicatedServerEnabled": True,
        "TeachersAllowed": True
    }) \
    .post()
```

**Note from notebook:** "These settings are also editable at https://aka.ms/MCEDU-DS-Portal"

#### Add a partner:
```python
RequestBuilder("tooling/edit_tenant_settings") \
    .members({
        "UpsertPartnerPermissions": {
            "01234567-abcd-4567-0123-ThePartnerID": 0
        }
    }) \
    .post()
```

Partner permission levels:
- `0` = **FullManagement** — can manage all tenant settings except partner permission edits
- `1` = **ESports** — can set up connections and edit the broadcasted servers list only

**Note from notebook:** "partners can never configure users or servers belonging to your tenant"

**Note from API docs:** "The `upsertPartnerPermissions` and `removePartnerPermissions` properties must use the tenant IDs of server configuration partners who have been **officially approved by Minecraft Education**."

#### Remove a partner:
```python
RequestBuilder("tooling/edit_tenant_settings") \
    .members({
        "RemovePartnerPermissions": ["01234567-abcd-4567-0123-ThePartnerID"]
    }) \
    .post()
```

#### Broadcasting via tenant settings:
The API docs show `addBroadcastedServers` / `removeBroadcastedServers` in `edit_tenant_settings`. The notebook does not use these — it broadcasts via `edit_server_info` with `IsBroadcasted: True` instead. Both mechanisms may coexist.

```json
{
    "AddBroadcastedServers": ["QH2JNJUZY7YX"],
    "RemoveBroadcastedServers": ["OHMNTT69T7XH"]
}
```

---

### 5.7 `tooling/create_server_invite` — POST

Sends a cross-tenant server invite.

```python
# Print invite state before
fetchAndPrintServerInvites()

RequestBuilder("tooling/create_server_invite") \
    .members({
        "ServerId": "YourServerID",
        "GuestTenantIds": ["01234567-abcd-4567-0123-SomeTenantID"]
    }) \
    .post()

# Print invite state after
fetchAndPrintServerInvites()
```

**Prerequisites (from API docs):**
- Owning tenant must have `CrossTenantAllowed` enabled in tenant settings
- Server must have `CrossTenantAllowed` enabled via `edit_server_info`

Returns 409 if invite already sent.

---

### 5.8 `tooling/accept_server_invite` — POST

Accepts an invite from another tenant.

```python
fetchAndPrintServerInvites()

RequestBuilder("tooling/accept_server_invite") \
    .members({ "ServerId": "SomeServerID" }) \
    .post()

fetchAndPrintServerInvites()
```

**Prerequisites (from API docs):**
- Both the owning and guest tenants must have `CrossTenantAllowed` enabled
- The server must have `CrossTenantAllowed` enabled

Supports `onBehalfOfTenantId` parameter — partners can accept on behalf of other tenants if the partner is the owner of the server registration.

---

### 5.9 `tooling/remove_server_connection` — POST

Revokes cross-tenant access (both pending and accepted invitations).

#### As the owning tenant (remove another tenant's access):
```python
fetchAndPrintServerInvites()

RequestBuilder("tooling/remove_server_connection") \
    .members({
        "ServerId": "YourServerID",
        "GuestTenantIds": ["01234567-abcd-4567-0123-SomeTenantID"]
    }) \
    .post()

fetchAndPrintServerInvites()
```

#### As the guest tenant (remove your own access):
```python
fetchAndPrintServerInvites()

RequestBuilder("tooling/remove_server_connection") \
    .members({ "ServerId": "SomeServerID" }) \
    .post()

fetchAndPrintServerInvites()
```

Note: When the calling tenant is a guest, do NOT include `GuestTenantIds` — the API infers the caller's tenant.

---

## 6. Server Endpoints — Reference from API Docs

These are NOT in the notebook (the notebook only covers `tooling/*` endpoints). Included here for completeness against the API docs.

| Endpoint | Method | Auth | Purpose |
|----------|--------|------|---------|
| `server/register` | POST | Entra token (edu client app ID) | Create new server registration, get server ID + token |
| `server/fetch_token` | GET | Entra token (edu client app ID) | Get new server token using `?serverId=` parameter |
| `server/host` | POST | MESS server token | Register IP with MESS, mark as online |
| `server/update` | POST | MESS server token | Report status (playerCount, maxPlayers, health) |
| `server/dehost` | POST | MESS server token | Mark as offline on graceful shutdown |
| `server/fetch_joiner_info` | GET | MESS server token | Poll for pending client connections (nonce verification) |
| `server/refresh_token` | POST | MESS server token | Documented but returns 404 in practice — use `fetch_token` instead |

**Server endpoints use the Education client app ID (`b36b1432-...`), NOT the tooling app ID.**

**Note:** The tooling app ID (`1c91b067-...`) cannot be used for `server/register` or `server/fetch_token` — these require the Education client app ID (`b36b1432-...`). Two separate device code flows are needed for full setup.

---

## 7. Client Endpoints — Reference from API Docs

| Endpoint | Method | Auth | Purpose |
|----------|--------|------|---------|
| `client/check_server_access` | POST | Entra token | Verify client can access a server by ID |
| `client/fetch_broadcasted_servers` | GET | Entra token | Get server IDs broadcasted in tenant |
| `client/fetch_server_info` | POST | Entra token | Get display info for multiple servers |
| `client/join_server` | POST | Entra token | Initiate join, get connection info + nonces |

---

## 8. Miscellaneous Endpoints

| Endpoint | Method | Auth | Purpose |
|----------|--------|------|---------|
| `public_keys/encryption` | GET | None | RSA 2048 OAEP-SHA256 public key for encrypting passcodes |
| `public_keys/signing` | GET | None | Public keys for verifying server token signatures |

---

## 9. Discrepancies Between Sources

### API Docs vs Notebook

| Topic | API Docs | Notebook | Likely Correct |
|-------|----------|----------|---------------|
| Field casing | camelCase | PascalCase | **Notebook** — actively maintained |
| Tooling app ID | `f8ba6a93-...` (browser) | `1c91b067-...` (device code) | **Notebook** — only one that works with device code |
| Password field | `password` (encrypted) | `Passcode` (plaintext) | **Notebook** — likely newer API version |
| Disable password | `disablePasswordProtection` | `DisablePasscodeProtection` | **Notebook** |
| `api-version` header | Not mentioned | `2.0` on `edit_server_info` | **Notebook** — may control API behavior |
| Health range | 0-3 | Not specified | API rejects 3, accepts -1 to 2 |
| `server/refresh_token` | Documented as working | Not present in notebook | **Endpoint returns 404** — use `server/fetch_token` instead |
| Broadcasting | Via `edit_tenant_settings` with `addBroadcastedServers` | Via `edit_server_info` with `IsBroadcasted` | Both may work |

---

## 10. Potential Features (Enabled by Tooling API Access)

### In-game commands (using tooling token):

| Command | API Call | Purpose |
|---------|----------|---------|
| `/geyser edu invite <tenantId>` | `tooling/create_server_invite` | Send cross-tenant invite |
| `/geyser edu revoke <tenantId>` | `tooling/remove_server_connection` | Revoke tenant access |
| `/geyser edu servers` | `tooling/fetch_all_server_ids` + `tooling/fetch_server_info` | List all servers in tenant |
| `/geyser edu passcode <code>` | `tooling/edit_server_info` with `Passcode` | Set server passcode |
| `/geyser edu passcode off` | `tooling/edit_server_info` with `DisablePasscodeProtection` | Remove passcode |
| `/geyser edu settings` | `tooling/fetch_tenant_settings` | Show tenant configuration |
| `/geyser edu broadcast on/off` | `tooling/edit_server_info` with `IsBroadcasted` | Toggle server list visibility |
| `/geyser edu sharing on/off` | `tooling/edit_server_info` with `SharingEnabled` | Toggle share button |
| `/geyser edu crosstenant on/off` | `tooling/edit_server_info` with `CrossTenantAllowed` | Toggle cross-tenant |
| `/geyser edu delete` | `tooling/delete_server_registration` | Delete server registration |
| `/geyser edu invites` | `tooling/fetch_tenant_settings` → `ServerInvitesSent/Received` | Show invite status |

### Automated admin onboarding:

On first startup with a Global Admin login, EduGeyser could automatically enable dedicated servers, teacher access, register the server, enable and broadcast it — collapsing the entire Method B setup into a single device code authentication.

---

## Appendix A: Complete Notebook Source Code

### Authentication Cell (Cell 1)

```python
import jwt
import json
import requests
import time
import datetime
import importlib
import subprocess

def require(package_name):
    """Check if a package is installed, and if not, attempts to install it."""
    try:
        return importlib.import_module(package_name)
    except ImportError:
        subprocess.run(["pip", "install", package_name])
        try:
            return importlib.import_module(package_name)
        except ImportError:
            raise ImportError(f"The '{package_name}' module is required.")

if require("msal"):
    print("MSAL is installed.")

import msal

baseUrl = "https://dedicatedserver.minecrafteduservices.com/"
adminToolingAppId = "1c91b067-6806-44a5-8d2d-3137e625f5b8"
minecraftEduServicesScopes = ["16556bfc-5102-43c9-a82a-3ea5e4810689/.default"]

class Token:
    def __init__(self, authResult: dict):
        self.access: str = authResult["access_token"]
        decoded = jwt.decode(self.access, options={"verify_signature": False})
        self.username: str = decoded['unique_name']
        self.name: str = decoded['name']
        self.tenant: str = decoded['tid']
        self.expiry: int = decoded['exp']

    def _getExpirationAsString(self) -> str:
        expiration = datetime.datetime.fromtimestamp(self.expiry)
        return expiration.strftime("%Y-%m-%d, at %I:%M:%S %p")

    def __str__(self) -> str:
        return f"Access token for user '{self.name}' in tenant '{self.tenant}' expires on {self._getExpirationAsString()}"

def isTokenValid(token: Token) -> bool:
    return token and time.time() < token.expiry

def getOrMakeApp(appId: str) -> msal.PublicClientApplication:
    app = globals().get(appId, msal.PublicClientApplication(
        appId,
        authority="https://login.microsoftonline.com/organizations",
        token_cache=msal.TokenCache()
    ))
    globals()[appId] = app
    return app

def getToken(app: msal.PublicClientApplication, name: str = "tooling token") -> Token | None:
    token: Token | None = globals().get(name, None)
    if isTokenValid(token):
        print(f"Using cached {name}:")
    else:
        if token != None:
            account = app.get_accounts(token.username)[0]
            result = app.acquire_token_silent(minecraftEduServicesScopes, account)
            if "access_token" in result:
                token = Token(result)
                print(f"Silently acquired {name}:")
        if not isTokenValid(token):
            flow = app.initiate_device_flow(minecraftEduServicesScopes)
            print(f"Acquiring {name}:\n" + flow["message"])
            result = app.acquire_token_by_device_flow(flow)
            if "access_token" in result:
                token = Token(result)
            else:
                print(f"Error acquiring {name}: {result.get('error')}")
                return token
    print(str(token))
    globals()[name] = token
    return token

toolingToken = getToken(getOrMakeApp(adminToolingAppId))

class RequestBuilder:
    def __init__(self, endpoint: str, token: Token | str = toolingToken, serviceUrl: str = baseUrl):
        self.endpoint = endpoint
        self.url = serviceUrl + endpoint
        accessToken = token if isinstance(token, str) else token.access
        self.headers = { "Authorization": f"Bearer {accessToken}" }
        self.params = {}
        self.body = {}
        self.print = True

    def obo(self, tenantId) -> 'RequestBuilder':
        self.params["onBehalfOfTenantId"] = tenantId
        return self

    def header(self, key, value) -> 'RequestBuilder':
        self.headers[key] = value
        return self

    def members(self, dictionary) -> 'RequestBuilder':
        self.body |= dictionary
        return self

    def quiet(self) -> 'RequestBuilder':
        self.print = False
        return self

    def get(self):
        return self._handle(requests.get(self.url, headers=self.headers, json=self.body, params=self.params))

    def post(self):
        return self._handle(requests.post(self.url, headers=self.headers, json=self.body, params=self.params))

    def _handle(self, response):
        resultText = result = response.text
        try:
            result = response.json()
            resultText = json.dumps(result, indent=1)
        except requests.exceptions.JSONDecodeError:
            try:
                decoded = jwt.decode(result, options={"verify_signature": False})
                result = decoded["payload"]
                resultText = json.dumps(result, indent=1)
            except jwt.DecodeError:
                pass
        if self.print:
            print(f"{self.endpoint}: {response.status_code} {resultText}\n")
        return result

def fetchAndPrintServerInvites(token: Token = toolingToken):
    response = RequestBuilder("tooling/fetch_tenant_settings", token).quiet().get()
    print("Invites sent:", response["ServerInvitesSent"])
    print("Invites received:", response["ServerInvitesReceived"])
    print()
```

### All Tooling Cells (Cells 3-24)

```python
# Cell 3: tooling/fetch_all_server_ids
_ = RequestBuilder("tooling/fetch_all_server_ids").get()

# Cell 5: tooling/fetch_server_info
_ = RequestBuilder("tooling/fetch_server_info") \
    .members({ "ServerIds": ["YourServerID"] }) \
    .post()

# Cell 7: tooling/edit_server_info — full setup
_ = RequestBuilder("tooling/edit_server_info") \
    .header("api-version", "2.0") \
    .members({
        "ServerId": "YourServerID",
        "ServerName": "Your cool server",
        "Enabled": True,
        "IsBroadcasted": True,
        "SharingEnabled": True,
        "CrossTenantAllowed": True,
    }) \
    .post()

# Cell 8: Set passcode
_ = RequestBuilder("tooling/edit_server_info") \
    .header("api-version", "2.0") \
    .members({
        "ServerId": "YourServerID",
        "Passcode": "MyPasscode"
    }) \
    .post()

# Cell 9: Disable passcode
_ = RequestBuilder("tooling/edit_server_info") \
    .header("api-version", "2.0") \
    .members({
        "ServerId": "YourServerID",
        "DisablePasscodeProtection": True
    }) \
    .post()

# Cell 11: tooling/delete_server_registration
_ = RequestBuilder("tooling/delete_server_registration") \
    .members({ "ServerId": "YourServerID" }) \
    .post()

# Cell 13: tooling/fetch_tenant_settings
_ = RequestBuilder("tooling/fetch_tenant_settings").get()

# Cell 15: tooling/edit_tenant_settings — enable all features
_ = RequestBuilder("tooling/edit_tenant_settings") \
    .members({
        "CrossTenantAllowed": True,
        "DedicatedServerEnabled": True,
        "TeachersAllowed": True
    }) \
    .post()

# Cell 16: Add partner
_ = RequestBuilder("tooling/edit_tenant_settings") \
    .members({
        "UpsertPartnerPermissions": {
            "01234567-abcd-4567-0123-ThePartnerID": 0
        }
    }) \
    .post()

# Cell 17: Remove partner
_ = RequestBuilder("tooling/edit_tenant_settings") \
    .members({ "RemovePartnerPermissions": ["01234567-abcd-4567-0123-ThePartnerID"] }) \
    .post()

# Cell 19: tooling/create_server_invite
fetchAndPrintServerInvites()
RequestBuilder("tooling/create_server_invite") \
    .members({
        "ServerId": "YourServerID",
        "GuestTenantIds": ["01234567-abcd-4567-0123-SomeTenantID"]
    }) \
    .post()
fetchAndPrintServerInvites()

# Cell 21: tooling/accept_server_invite
fetchAndPrintServerInvites()
RequestBuilder("tooling/accept_server_invite") \
    .members({ "ServerId": "SomeServerID" }) \
    .post()
fetchAndPrintServerInvites()

# Cell 23: tooling/remove_server_connection (as owner)
fetchAndPrintServerInvites()
RequestBuilder("tooling/remove_server_connection") \
    .members({
        "ServerId": "YourServerID",
        "GuestTenantIds": ["01234567-abcd-4567-0123-SomeTenantID"]
    }) \
    .post()
fetchAndPrintServerInvites()

# Cell 24: tooling/remove_server_connection (as guest)
fetchAndPrintServerInvites()
RequestBuilder("tooling/remove_server_connection") \
    .members({ "ServerId": "SomeServerID" }) \
    .post()
fetchAndPrintServerInvites()
```
