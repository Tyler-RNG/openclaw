---
summary: "Integration spec for the sidecar, Android companion, and watch to deliver multi-state agent avatars end-to-end."
read_when:
  - Rebuilding the gateway data-plane sidecar to serve a states-configured agent
  - Adding multi-state avatar support to the Android companion app
  - Adding multi-state avatar support to the Wear OS / watch app
  - Preparing an integration retest pass after a gateway upgrade
title: "Avatar states — integration spec"
---

# Avatar states — integration spec

This page is the authoritative hand-off for the pieces **outside** the gateway that need to know about multi-state avatars. For the user-facing config reference, see [avatar-states.md](./avatar-states.md).

## 1. Responsibility split

| Responsibility | Owner |
|---|---|
| Parse `[avatar:<state>]` markers from assistant text | Gateway |
| Strip markers from the text clients see | Gateway |
| Emit `avatar.state.change` WS events | Gateway |
| Serve avatar bytes (`/assets/<path>`) | Gateway or sidecar |
| Inject the `instruction` system message on new sessions | Client (phone) |
| Resolve `state → URL` and fetch bytes | Client (phone) |
| Push asset bytes to the watch | Android companion (via Wear DataClient) |
| Render the image | Watch |

**None of the state logic lives on the watch or sidecar.** Both are dumb.

## 2. `agents.list` response shape

New optional field on each agent's `identity`:

```jsonc
{
  "identity": {
    "name": "Ginger",
    "emoji": "🦞",
    "theme": "#ffaa00",
    "avatar": null,                 // string form, may be absent
    "avatarUrl": null,               // resolved string URL, may be absent
    "avatarStates": {                // NEW — present only for Form C
      "default": "neutral",
      "states": {
        "neutral": { "file": "avatars/ginger/neutral.gif", "description": "resting" },
        "happy":   { "file": "avatars/ginger/smile.gif",   "description": "warm, supportive" }
      },
      "instruction": "You can change your avatar expression…"
    }
  }
}
```

Clients that don't know about `avatarStates` ignore the field and fall back to the legacy `avatar` / `avatarUrl` — both can be absent simultaneously without causing errors.

## 3. WS event — `avatar.state.change`

Fires zero or more times per assistant run, only for agents with `avatar.kind: "states"`.

```jsonc
// WS frame: { "event": "avatar.state.change", "data": {...} }
{
  "runId": "<client-run-id>",
  "sessionKey": "agent:ginger:main",
  "agentId": "ginger",
  "state": "happy",
  "file": "avatars/ginger/smile.gif",
  "ts": 1741024123456
}
```

- `file` is the exact string from the agent config. It may be a relative path, an absolute URL, or a data URI — the client resolves it.
- Unknown-state markers are silently dropped; no event fires for them.
- An idempotent repeat of the same state (e.g. `[avatar:happy]` twice in a row) still fires two events. Clients that want to debounce should compare `state` against last-received.

## 4. Sidecar responsibilities

The sidecar contract is **unchanged** from pre-states. Summary of what it must do, for completeness:

| Endpoint | Required | Notes |
|---|---|---|
| `GET /assets/<path>` | yes | Serve from `assetsDir`. Path-traversal, symlink, hidden-file, size-cap guards required. |
| `GET /stream/tts`, `GET /tts` | if TTS configured | ElevenLabs proxy. Always requires auth even if `publicAssets: true`. |
| `GET /agents` | recommended | Proxies the gateway's `agents.list` RPC. **Must pass `identity.avatarStates` through unmodified** if present. |
| `GET /health` | yes | Unauthenticated liveness. |

### `GET /agents` avatar URL rewriting

The sidecar already rewrites `identity.avatar` relative paths into fully-qualified URLs (`<publicBaseUrl>/assets/<file>?token=<token>`). For `avatarStates.states.<name>.file` the sidecar **should** do the same rewrite when the value is not already an absolute URL or data URI. Pseudocode:

```js
for (const agent of response.agents ?? []) {
  const states = agent.identity?.avatarStates?.states;
  if (!states) continue;
  for (const name of Object.keys(states)) {
    const f = states[name].file;
    if (!f || /^(https?:|data:)/.test(f)) continue;   // already absolute
    states[name].file = buildAssetUrl(f, token);       // same helper as legacy avatar
  }
}
```

This is a quality-of-life convenience so mobile/watch clients don't have to reconstruct URLs. Not required — if the sidecar skips it, the client does the same prefix-join.

### What the sidecar does NOT do

- It does not parse `[avatar:<state>]` markers. That runs in the gateway.
- It does not emit `avatar.state.change`. That's a gateway WS event.
- It does not need to know an asset is an avatar vs anything else.

## 5. Android companion responsibilities

### On session bootstrap / `agents.list` handling

1. For each agent, parse `identity.avatarStates`.
2. Build `state → URL` for each agent's states. URL construction:
   - If `file` starts with `http://`, `https://`, or `data:` — use verbatim.
   - Otherwise: `<sidecarBaseUrl>/assets/<file>` (or `<gatewayBaseUrl>/assets/<file>` if the data plane is in-gateway). Append `?token=<token>` when `publicAssets: false`.
3. Fetch the default state's bytes (at least) and cache. Push to the watch at `/openclaw/avatars/<agentId>` via `DataClient` (using `Asset` for binaries).
4. **Inject the `instruction` as a system message** on new sessions targeting a states-aware agent. This is the only way the model learns the marker protocol exists. Do not inject for agents without `avatarStates`.

### On WS event `avatar.state.change`

1. Look up the new `state` in the cached state map. If missing, ignore.
2. Fetch the bytes (with HTTP cache / ETag support) — the sidecar/gateway emits `ETag` and honors `If-None-Match`.
3. Push bytes to watch via `DataClient` at `/openclaw/avatars/<agentId>` (overwrite previous asset).

### Guardrails

- Debounce state changes to at most ~2 Hz per agent (state flicker protection).
- Coalesce: if several changes arrive before the previous fetch finishes, apply only the last.
- If a fetch fails (network, 403, 404), don't swap — keep the last-known good frame on the watch.
- Don't spawn instructions on refresh; only on `session.start` for a new sessionKey. Duplicate injections per session are wasted tokens.

## 6. Watch responsibilities

- Listen for `DataItem` changes at `/openclaw/avatars/<agentId>`.
- On update: replace the currently rendered image with the new asset's bytes.
- No knowledge of states, markers, or the gateway protocol. All translation is the phone's job.
- Cache the last-received asset locally so the rendering survives phone disconnects.

## 7. Retest checklist

### Gateway
- [ ] Agent with `avatar.kind: "states"` shows `identity.avatarStates` in `agents.list`.
- [ ] Agent with string `avatar` unchanged; no `avatarStates`.
- [ ] Assistant reply with `[avatar:happy]\n` — user-visible text strips the marker, exactly one `avatar.state.change` event fires.
- [ ] Multiple markers → events in order.
- [ ] Cumulative-text delta (same text pushed twice) → marker emits exactly once.
- [ ] Unknown state → marker stripped, no event.
- [ ] `[avatar:X]` inline in a sentence → left as literal text.

### Sidecar
- [ ] `GET /assets/avatars/…/*.gif` returns bytes with auth enforced.
- [ ] `GET /agents` forwards `identity.avatarStates` untouched (or with URL-rewriting if that path is enabled).
- [ ] Legacy agents behave identically to the pre-states build.
- [ ] Path traversal, hidden files, size cap all still rejected.

### Android companion
- [ ] On pair / reconnect, `agents.list` is read and `avatarStates` detected.
- [ ] `instruction` is injected as a system message on new session start (states-aware agents only).
- [ ] Default-state GIF fetched and pushed to watch before first model reply.
- [ ] `avatar.state.change` event flips the watch asset within ~500 ms of the marker reaching the phone.
- [ ] Forwarding debounce prevents flicker on rapid state changes.

### Watch
- [ ] Default avatar renders after pairing.
- [ ] GIF swaps when phone pushes new bytes.
- [ ] Last frame survives a phone disconnect.

## 8. Things that don't change

- TTS audio path (`/stream/tts`) — separate feature, unaffected.
- Legacy static avatars — zero-change behavior.
- The WS control-plane protocol outside the new `avatar.state.change` event name.
- Token / auth contract — same bearer + `?token=` scheme everywhere.
- Sidecar's `/assets` path-hygiene guards (traversal, symlinks, size cap, hidden files).

## 9. Related

- [avatar-states.md](./avatar-states.md) — operator-facing config reference.
- [data-plane.md](./data-plane.md) — where the assets themselves live.
- [configuration.md](./configuration.md) — broader gateway config.
