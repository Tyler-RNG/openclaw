# OpenClaw Data Plane — Sidecar (reference implementation)

Canonical Node/Express implementation of the OpenClaw Gateway data plane
spec ([`docs/gateway/data-plane.md`](../../../docs/gateway/data-plane.md)).
Runs alongside a Gateway whose binary can't be patched in place, providing:

- `GET /assets/<path>` — static binary asset serving (avatars, images).
- `GET /stream/tts` — ElevenLabs TTS streaming proxy.
- `GET /agents` — proxies the Gateway's `agents.list` RPC and rewrites
  relative avatar paths to absolute URLs. (The Android phone companion
  currently resolves these itself over WebSocket, so this endpoint is
  optional for that client.)
- `GET /health` — unauthenticated liveness probe.

## Architecture

```
┌─────────────┐   WSS      ┌──────────────────────┐
│ phone/watch │────────────→│ gateway :18789 (lo)  │
└─────────────┘            └──────────────────────┘
       │                             ▲
       │ HTTPS                       │ HTTP (loopback)
       ▼                             │
┌─────────────────────────┐          │
│ tailscale serve :443    │──────────┤ /agents proxies gateway RPC
│   → sidecar :8443       │          │
│   ├── /assets/<path>    │          │
│   ├── /stream/tts ──────┼──→ ElevenLabs
│   ├── /agents ──────────┘
│   └── /health
└─────────────────────────┘
```

Why a sidecar: the main Gateway multiplexes WebSocket + HTTP on a single
port, but its HTTP surface is currently limited to RPC-style endpoints
(OpenAI Chat Completions, etc.). Binary / streaming content lives better on
a dedicated process, and keeping it out-of-tree means a compiled Gateway
binary can still get the full data plane today.

## Files

| Path | Purpose |
|---|---|
| `server.js` | Express server, ~300 lines. Implements all four endpoints. |
| `package.json` | Single runtime dep: `express`. Node 18+. |
| `config.example.json` | Template config. Copy to `config.json` and fill in live values. `config.json` is git-ignored. |
| `test.js` | 20+ integration tests hitting a running sidecar. Reads `SIDECAR_TOKEN` + `ELEVENLABS_API_KEY` from env. |
| `systemd/openclaw-sidecar.service.example` | Systemd unit template. |
| `systemd/sidecar.env.example` | EnvironmentFile template for the ElevenLabs API key. |
| `assets/avatars/` | Your avatar files. The example copy ships empty — drop GIFs here. |

## Setup

### 1. Install

```bash
cd scripts/gateway-data-plane/sidecar
npm install
```

### 2. Configure

```bash
cp config.example.json config.json
```

Edit `config.json`:

- `port` — the port the sidecar binds internally. Typically `8443`,
  fronted by `tailscale serve` on `443`.
- `http.publicBaseUrl` — the URL **clients** hit. For tailnet-only
  deployments, `https://<machine>.<tailnet>.ts.net` (no port, because
  Tailscale serves on 443).
- `http.assetsDir` — filesystem root for `/assets/<path>`. Resolved
  relative to `server.js` if relative.
- `auth.token` — must match the Gateway's auth token. Shared bearer
  secret for both WS and data plane.
- `gateway.host` / `gateway.port` / `gateway.token` — where to reach the
  Gateway for the `/agents` RPC proxy. Typically loopback.

### 3. Tell the Gateway about the data plane

In the Gateway's `openclaw.json`, add a `dataPlane` block that matches:

```json
{
  "dataPlane": {
    "baseUrl": "https://<machine>.<tailnet>.ts.net",
    "publicAssets": false,
    "streamTts": true
  }
}
```

`baseUrl` must equal the sidecar's `http.publicBaseUrl`. `publicAssets`
must match. `streamTts: true` signals to clients that `/stream/tts` is
wired up to a real provider.

### 4. Expose via Tailscale

Map tailnet HTTPS 443 to the sidecar's 8443:

```bash
sudo tailscale serve --https=443 --set-path / http://127.0.0.1:8443
```

Verify from any tailnet device:

```bash
curl -I https://<machine>.<tailnet>.ts.net/health
```

### 5. Supervise via systemd

```bash
sudo cp systemd/openclaw-sidecar.service.example /etc/systemd/system/openclaw-sidecar.service
sudo vi /etc/systemd/system/openclaw-sidecar.service
#   fill in your unix user, working directory, and ELEVENLABS_API_KEY
sudo systemctl daemon-reload
sudo systemctl enable --now openclaw-sidecar
journalctl -u openclaw-sidecar -f
```

Apply config changes:

```bash
sudo systemctl daemon-reload   # only if the unit file itself changed
sudo systemctl restart openclaw-sidecar
```

For multi-admin hosts, move the API key out of the unit file into
`/etc/openclaw/sidecar.env` (see `systemd/sidecar.env.example`) so it
stops appearing in `systemctl cat`.

## Security

Handled by the sidecar:

- **Path traversal** — resolved paths must stay under `assetsDir`; `..` rejected with `403`.
- **Symlinks** — followed, then re-validated against `assetsDir`. Out-of-tree targets → `403`.
- **Hidden files** — any path segment starting with `.` is rejected.
- **Size cap** — files over `http.maxAssetSizeBytes` return `413`.
- **Auth** — `Authorization: Bearer <token>` header *or* `?token=<token>` query. Must match `auth.token`. `/stream/tts` always requires auth even if `publicAssets: true`.
- **Rate limiting** — 20 401s / IP / minute, then `429`. Defense against token guessing.
- **Log scrubbing** — `?token=…` is always written as `?token=***`.
- **CORS** — opt-in via `http.corsOrigins`. Never `*`.

Left to the operator:

- Never commit `config.json` (live tokens) — enforced by `.gitignore`.
- Keep `ELEVENLABS_API_KEY` out of `systemd` unit files; use
  `EnvironmentFile=/etc/openclaw/sidecar.env` with `0640` perms.
- Bind to `0.0.0.0` only if you trust Tailscale ACLs to enforce access.
  Otherwise bind to the Tailscale IP explicitly or layer iptables.
- Rotate `auth.token` + redeploy both Gateway and sidecar together.

## Verifying

```bash
HOST="<your-machine>.<your-tailnet>.ts.net"
TOKEN="$(pass show openclaw/gateway-token)"   # or wherever your secret lives

# Health (unauth)
curl -i "https://$HOST/health"

# Asset with Bearer header
curl -i -H "Authorization: Bearer $TOKEN" "https://$HOST/assets/avatars/ginger.gif" -o /tmp/ginger.gif
file /tmp/ginger.gif       # → "GIF image data"

# Asset with query token (what `<img src>` / MediaPlayer will use)
curl -i "https://$HOST/assets/avatars/ginger.gif?token=$TOKEN" -o /tmp/ginger2.gif

# Path traversal → 403
curl -i "https://$HOST/assets/../package.json?token=$TOKEN"

# 304 caching
etag=$(curl -sI "https://$HOST/assets/avatars/ginger.gif?token=$TOKEN" | awk -F': ' 'tolower($1)=="etag"{print $2}' | tr -d '\r')
curl -i -H "If-None-Match: $etag" "https://$HOST/assets/avatars/ginger.gif?token=$TOKEN"

# Streaming TTS (slow first byte is fine; throughput should be steady)
curl -N "https://$HOST/stream/tts?voice=<voice-id>&text=hello%20world&token=$TOKEN" -o /tmp/hello.mp3
```

Run the test suite against a locally running sidecar:

```bash
SIDECAR_TOKEN="$TOKEN" node test.js
```

# Upstreaming

This lives as a sidecar today because patching a compiled OpenClaw dist
in place isn't straightforward. The long-term home for these endpoints is
**inside the Gateway itself**, multiplexed on the existing WS+HTTP port
next to `/v1/chat/completions`. When that lands:

- `server.js` here gets ported to a Gateway HTTP route module (TypeScript).
- `config.json` collapses into the Gateway's main config under `http.*`.
- `systemd/` goes away — the Gateway's own supervisor handles it.
- Clients don't change at all; the `baseUrl` + endpoint contract is the
  same whether it's a sidecar or in-process.

Use this sidecar in the meantime to get the full data plane working
against the binary Gateway you have today. Swap to in-process once the
Gateway upstream ships native support.

## Known tradeoffs / follow-ups

- `/stream/tts` and `/tts` have duplicated handler bodies (`/tts` exists
  for Tailscale path-stripping compatibility). Worth DRYing into a shared
  function in a follow-up PR.
- No request IDs in logs yet. Handy if this ever fronts enough traffic
  that correlating an error back to a specific caller matters.
- TLS is disabled in-process. Tailscale handles transport encryption
  between peers; operators exposing the sidecar to the public internet
  should front it with a real cert (Let's Encrypt / Caddy / nginx).
- `assets/` currently only supports **GET**. No upload/delete by design —
  the Gateway host manages its own files.
- The included `test.js` hits a live server; there's no unit-test layer
  that can run in CI without a running instance. Worth adding.

## See also

- [`docs/gateway/data-plane.md`](../../../docs/gateway/data-plane.md) — the
  URL/auth/endpoint contract clients rely on.
- [`docs/gateway/tailscale.md`](../../../docs/gateway/tailscale.md) —
  how `tailscale serve` fronts a loopback service on 443.
- [`docs/gateway/authentication.md`](../../../docs/gateway/authentication.md)
  — the token model the sidecar shares with the Gateway.
