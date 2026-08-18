---
summary: "HTTP data plane for serving binary assets (avatars, images) and streaming media (TTS audio) from the Gateway"
read_when:
  - Adding avatars, images, or other binary assets to agent/session identity
  - Integrating ElevenLabs or other streaming TTS providers
  - Building a client (watch, mobile, embedded) that needs to fetch binary content the Gateway holds
title: "Data plane (HTTP)"
---

# Data plane (HTTP)

The Gateway's WebSocket is the **control plane** — RPCs like `chat.send`, `agents.list`, event subscriptions. It's great for JSON messages, terrible for binary blobs and streams.

The **data plane** is a small HTTP surface on the same host/port as the WebSocket. It serves:

- Static binary assets (agent avatars, images, audio clips) from a configured directory
- Streaming responses (ElevenLabs TTS audio, transcoded media) proxied from upstream providers

Same auth as the WebSocket. Same port (multiplexed, like `/v1/chat/completions`). Clients over Tailscale, LAN, or public networks all reach it the same way.

This endpoint is **disabled by default** for security. Enable and configure it explicitly before clients try to use it.

## Why it exists

Several clients need binary content the Gateway holds:

- **Watch / mobile clients** display agent avatars. Stashing avatar GIFs on the user's phone doesn't scale across devices; keeping them on the Gateway is the single source of truth.
- **Voice features** (ElevenLabs, other TTS) need to stream audio to the device that's about to play it. Buffering the whole synthesis server-side before forwarding adds 2–5 s of latency; proxying chunks as they arrive lets the client start playback within ~500 ms.
- **Future integrations** (agent-generated images, document previews, file attachments) have the same shape: Gateway knows where the bytes are, client needs to fetch them.

Rather than encoding each blob as base64 inside a JSON RPC (size bloat, poor caching, no streaming), expose a small HTTP surface.

## Endpoints

### `GET /assets/<path>` — static file serving

Serves a file from the configured `http.assetsDir`.

**Request:**

```
GET /assets/<relative-path>
Authorization: Bearer <token>          # or ?token=<token>
```

**Behavior:**

1. Resolve `<relative-path>` against `assetsDir`. Reject if the resolved absolute path escapes `assetsDir` (path traversal defense).
2. Reject hidden files (leading `.`).
3. Reject symlinks pointing outside `assetsDir`.
4. Reject files larger than `http.maxAssetSizeBytes` (default 10 MB) with `413 Payload Too Large`.
5. Infer `Content-Type` from extension (table below).
6. Serve with:
   - `Cache-Control: public, max-age=86400`
   - `ETag: "<sha1-of-contents-or-mtime>"`
   - Honor `If-None-Match` → `304 Not Modified`.

**MIME inference:**

| Ext | Content-Type |
|---|---|
| `.gif` | `image/gif` |
| `.png` | `image/png` |
| `.jpg` / `.jpeg` | `image/jpeg` |
| `.webp` | `image/webp` |
| `.svg` | `image/svg+xml` |
| `.mp3` | `audio/mpeg` |
| `.wav` | `audio/wav` |
| `.ogg` | `audio/ogg` |

Unknown extensions → `application/octet-stream`.

**Responses:**

- `200 OK` with body on success
- `304 Not Modified` on matching `If-None-Match`
- `401 Unauthorized` if auth required and missing/invalid
- `403 Forbidden` on path traversal / symlink escape / hidden file
- `404 Not Found` if file missing
- `413 Payload Too Large` if over size cap

### `GET /stream/tts` — streaming TTS audio

Proxies a text-to-speech synthesis from an upstream provider (ElevenLabs, etc.) as chunked audio. No buffering — forwards chunks the moment they arrive upstream.

**Request:**

```
GET /stream/tts?voice=<voiceId>&text=<urlencoded>&token=<token>
```

Optional parameters:

- `model=<provider-model-id>` (default: provider-specific, typically `eleven_turbo_v2`)
- `stability=<0..1>`
- `similarity=<0..1>`
- `provider=<elevenlabs|…>` (default from Gateway config)

**Response:**

```
200 OK
Content-Type: audio/mpeg
Transfer-Encoding: chunked
Cache-Control: no-store
<audio bytes streaming as upstream synthesizes>
```

**Semantics:**

- No server-side buffering. Every chunk out of the upstream provider flushes to the client immediately.
- Connection close on upstream error mid-stream; client treats interruption as end-of-playback.
- `429 Too Many Requests` if rate-limited *before* the stream starts. Once streaming, errors close the connection.

### `agents.list` URL construction

When the data plane is enabled, `agents.list` should return **full HTTP URLs** in identity fields that reference binary assets:

```json
{
  "agents": [
    {
      "id": "spark",
      "name": "Spark",
      "identity": {
        "emoji": "⚡",
        "theme": "#FFAA00",
        "title": "Quick helper",
        "avatar": "https://gateway.tailnet.ts.net:18789/assets/avatars/spark.gif?token=<client-token>"
      },
      "model": { "id": "claude-haiku-4-5", "label": "Haiku 4.5" },
      "voice": { "provider": "elevenlabs", "voiceId": "…", "label": "Rachel" }
    }
  ]
}
```

URL construction rules:

- Prefix = `http.publicBaseUrl` if configured, else derive from the request's `Host` header and TLS state.
- Path = `/assets/` + the stored relative path (e.g., `avatars/spark.gif`).
- Token = the same bearer token the client used to authorize the WS connection, embedded as `?token=` (necessary because `<img>` / `MediaPlayer` / Coil can't always set custom headers).

If `http.publicAssets: true`, drop the token — the bytes are unauth'd.

## Authentication

The data plane accepts the **same** token used by WebSocket. No new auth surface.

**Two ways to present it:**

1. `Authorization: Bearer <token>` — preferred for programmatic clients (`curl`, Node `fetch`, custom HTTP clients that can set headers).
2. `?token=<token>` — required for `<img src>`, `MediaPlayer`, Coil, and anything else where you don't control request headers.

**Reject with `401 Unauthorized`** if the token is missing or invalid. Rate-limit 401s per-IP to prevent token-guessing (reuse the existing Gateway rate-limit policy).

**Scrub tokens from access logs** — never log a full URL with `?token=` in plaintext. Replace with `?token=<redacted>`.

### Public mode (dev)

Setting `http.publicAssets: true` disables auth on `/assets/*` only. Useful for local development over Tailscale or a trusted LAN. `/stream/tts` always requires auth regardless — it costs money.

## Configuration

The data plane has **two configuration points** that must agree:

1. **Sidecar / data-plane server** — where assets live, where to proxy TTS, and what token it accepts.
2. **Gateway `openclaw.json`** — the `dataPlane` block returned via `config.get`, so phone/watch clients know where to fetch from and what capabilities are available.

If you're running a single process that serves both the WebSocket and HTTP data plane, both configs live in the gateway's own config file. If you're running the data plane as a separate sidecar (see `scripts/gateway-data-plane/`), the sidecar reads its own `config.json` and the gateway only carries the `dataPlane` block that tells clients where the sidecar is.

### Data-plane server config

```json
{
  "port": 8443,
  "http": {
    "publicBaseUrl": "https://gateway.tailnet.ts.net",
    "assetsDir": "./assets",
    "publicAssets": false,
    "maxAssetSizeBytes": 10485760,
    "streamTts": {
      "provider": "elevenlabs",
      "providerApiKeyRef": { "kind": "env", "name": "ELEVENLABS_API_KEY" }
    }
  },
  "auth": {
    "token": "<gateway-shared-token>"
  },
  "gateway": {
    "host": "127.0.0.1",
    "port": 18789
  }
}
```

| Field | Default | Description |
|---|---|---|
| `port` | — | Internal port the server binds. Typically fronted by `tailscale serve` or a reverse proxy to reach 443. |
| `http.publicBaseUrl` | derived from request | URL prefix used when constructing asset / stream URLs. **Must be reachable from clients** — e.g. `https://<machine>.<tailnet>.ts.net` for tailnet-only deployments on port 443. |
| `http.assetsDir` | `"./assets"` | Filesystem root for `GET /assets/*`. |
| `http.publicAssets` | `false` | If `true`, skip auth on `/assets/*`. `/stream/tts` always requires auth. |
| `http.maxAssetSizeBytes` | `10485760` (10 MB) | Reject files over this size with `413`. |
| `http.streamTts` | — | Required to enable `GET /stream/tts`. Provider + credentials lookup. |
| `auth.token` | — | Shared bearer token; must match the gateway's token so a single token authorizes both WS and data plane. |
| `gateway` | — | Where the sidecar reaches the gateway for proxied RPCs (e.g., `GET /agents` rewriting). Usually loopback. |

**Never log the token or `?token=…` query values.** Scrub them before anything hits disk or stdout.

### Gateway `openclaw.json` — `dataPlane` block

Clients read this via `config.get`:

```json
{
  "dataPlane": {
    "baseUrl": "https://gateway.tailnet.ts.net",
    "publicAssets": false,
    "streamTts": true
  }
}
```

| Field | Description |
|---|---|
| `baseUrl` | Matches the sidecar's `http.publicBaseUrl`. Clients append `/assets/<path>` or `/stream/tts?…`. **No port if fronted on 443**. |
| `publicAssets` | Mirrors the sidecar flag. Clients use it to decide whether to attach `?token=`. |
| `streamTts` | `true` if the sidecar has `/stream/tts` wired to a provider. Clients that fall back to the gateway's `talk.speak` RPC check this to decide whether to bypass it and hit the sidecar directly. |

## Directory layout

```
<gateway-root>/
  assets/                    ← http.assetsDir
    avatars/
      ginger.gif
      spark.gif
    images/
      <future>
```

Arbitrary subdirectories allowed. Anything under `assetsDir` is reachable as `/assets/<subpath>`. Nothing outside is.

## Security

- **Path traversal:** resolve `<path>` with the platform's canonical path function (`path.resolve`, `filepath.Clean`, etc.) and assert the result is a descendant of `assetsDir`. Reject otherwise with `403`.
- **Symlink escape:** if supporting symlinks, re-canonicalize after following and re-check containment. Simplest: don't follow symlinks.
- **Hidden files:** reject any component starting with `.`. Prevents `.env`, `.git`, etc. from leaking.
- **Size cap:** enforce `maxAssetSizeBytes` before reading the file, and cap response streams at the same limit.
- **TLS:** use HTTPS even over Tailscale — Tailscale encrypts the transport but clients still want hostname verification and the same URL shape as over the public internet.
- **CORS:** if a browser-based dashboard needs `/assets`, add `Access-Control-Allow-Origin` with the specific origin. Never `*` — the token is in the URL.
- **Rate-limiting:** reuse the Gateway's existing per-IP / per-token rate policy; 401 spikes get throttled aggressively.
- **Log scrubbing:** query-string token must never appear in access logs.

## Tailscale

All endpoints work transparently over Tailscale. Clients address the Gateway by MagicDNS hostname (`<machine>.<tailnet>.ts.net`) or Tailscale IP (`100.x.y.z`), same as for WebSocket. No special configuration — Tailscale is transparent to HTTP.

Binding: use `0.0.0.0`; Tailscale ACLs control reachability.

## Verification

```bash
TS_HOST="gateway.tailnet.ts.net"
PORT=18789
TOKEN="<gateway-token>"

# Drop a smoke-test file
mkdir -p ./assets/avatars
echo "smoke-test" > ./assets/avatars/smoke.txt

# Auth failure (401 unless http.publicAssets=true)
curl -i "https://$TS_HOST:$PORT/assets/avatars/smoke.txt"

# Header auth
curl -i -H "Authorization: Bearer $TOKEN" \
  "https://$TS_HOST:$PORT/assets/avatars/smoke.txt"

# Query auth
curl -i "https://$TS_HOST:$PORT/assets/avatars/smoke.txt?token=$TOKEN"

# Path traversal (403)
curl -i "https://$TS_HOST:$PORT/assets/../package.json?token=$TOKEN"

# Missing file (404)
curl -i "https://$TS_HOST:$PORT/assets/nope.gif?token=$TOKEN"

# Real avatar
curl -i -o /tmp/out.gif \
  "https://$TS_HOST:$PORT/assets/avatars/ginger.gif?token=$TOKEN"
file /tmp/out.gif   # should report "GIF image data"

# Caching (304)
ETAG=$(curl -sI "https://$TS_HOST:$PORT/assets/avatars/ginger.gif?token=$TOKEN" | grep -i etag | awk '{print $2}' | tr -d '\r\n')
curl -i -H "If-None-Match: $ETAG" \
  "https://$TS_HOST:$PORT/assets/avatars/ginger.gif?token=$TOKEN"

# Streaming TTS (if enabled)
curl -i -N -o /tmp/out.mp3 \
  "https://$TS_HOST:$PORT/stream/tts?voice=21m00Tcm4TlvDq8ikWAM&text=hello%20world&token=$TOKEN"
```

## Implementation order (MVP → full)

1. **MVP:** `http.enabled`, `http.assetsDir`, `GET /assets/<path>` with path-traversal guard and MIME detection. `http.publicAssets: true` for first test.
2. `agents.list` returns full URLs using `http.publicBaseUrl`.
3. Auth wiring (`Authorization` header + `?token=`). Flip `publicAssets` to `false`.
4. ETag / `If-None-Match` caching.
5. `/stream/tts` proxy, starting with ElevenLabs.

Stop after step 2 if you just need avatars on the watch. Steps 3–4 are hardening. Step 5 unlocks the full voice pipeline.

## Client expectations

Clients (phone, watch, embedded) that use the data plane should:

- Never assume `/assets/*` is the same origin as anything else — always use the URL returned in `agents.list`.
- Treat absent `avatar` field as "no avatar available"; fall back to emoji / initials / default asset.
- Handle `401` by re-authenticating (same flow as WS token refresh).
- Treat `/stream/tts` as best-effort — if it errors mid-stream, fall back to on-device TTS gracefully.
- Respect `Cache-Control` / `ETag`. Assets are expected to be cached for ~1 day.

### Wear OS companion path

The Android phone companion fetches the data plane on behalf of a paired Galaxy Watch (or similar wearable) that isn't on the same tailnet. The fetched bytes are then forwarded to the watch via the **Wearable DataClient / Asset** API (not MessageClient, which caps at ~100 KB per message). Specifically:

- Avatars → `DataItem` at `/openclaw/avatars/<agentId>` with the image bytes as an `Asset`. Animated GIFs preserved in full fidelity.
- TTS audio → `DataItem` at `/openclaw/tts/<assetId>` when the clip is larger than ~60 KB. Smaller clips ride inline via MessageClient for lower latency.

`agents.list` fields surfaced to the watch reference these with `wear-asset:avatar:<id>` / `wear-asset:tts:<id>` strings; the watch-side `WearAssetStore` resolves them to bytes on demand. Gateways don't need to know about this — it's purely a phone-companion implementation detail and transparent to the data plane contract described above.

## Related

- [Authentication](./authentication.md) — token model reused by the data plane.
- [OpenAI Chat Completions](./openai-http-api.md) — other HTTP surface multiplexed on the same port; same auth pattern.
- [Tailscale](./tailscale.md) — how Gateway clients reach the data plane over a tailnet.
