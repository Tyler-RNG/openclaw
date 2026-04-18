---
summary: "Interim pattern for running multi-state agent avatars on a published openclaw binary that doesn't yet validate the object form of identity.avatar. Sidecar synthesizes avatarStates; phone parses markers client-side."
read_when:
  - Running a published openclaw binary that rejects `identity.avatar` as an object
  - Wanting the multi-state avatar feature today without waiting for a native release
  - Setting up the data-plane sidecar for states-aware clients
title: "Avatar states — interim sidecar synthesis"
---

# Avatar states — interim sidecar synthesis

<Note>
**This is a transitional pattern.** When a released openclaw binary includes the native `identity.avatar` object-form widening, migrate to the native flow and delete the sidecar synthesis. See [`avatar-states.md`](./avatar-states.md) for the native config shape and [`avatar-states-integration.md`](./avatar-states-integration.md) for the native integration spec.
</Note>

## Why this exists

The native spec puts the states descriptor directly in `openclaw.json` under `identity.avatar` as an object. That form is validated by code not yet shipped in a public openclaw release — a published binary will reject your config with:

```
agents.list.0.identity.avatar: Invalid input: expected string, received object
```

This page describes an operator-friendly workaround: keep `identity.avatar` as a plain string in `openclaw.json` (so the published binary is happy), move the states descriptor into the **sidecar's** config, and let the sidecar synthesize the `identity.avatarStates` field on its `/agents` proxy response.

End-to-end behavior for clients is identical to the native flow. Migration is transparent when native ships.

## What each piece does

| Piece | Interim | Native (future) |
|---|---|---|
| `openclaw.json` `identity.avatar` | plain string (path/URL) | object `{ kind: "states", ... }` |
| Sidecar | synthesizes `identity.avatarStates` into `/agents` response | passes gateway's response through unchanged |
| Phone companion | parses `[avatar:<state>]` markers client-side; strips from displayed text; pushes asset bytes to watch | subscribes to `avatar.state.change` WS event; pushes asset bytes to watch |
| Watch | listens for `DataClient` updates at `/openclaw/avatars/<agentId>` | same |

## 1. `openclaw.json` — keep it boring

`identity.avatar` stays a string pointing to whatever the default-state file is. The published binary validates, the gateway starts, nothing special:

```jsonc
{
  "agents": {
    "list": [
      {
        "id": "ginger",
        "identity": {
          "emoji": "🦞",
          "avatar": "avatars/ginger/neutral.gif"
        }
      }
    ]
  }
}
```

## 2. Sidecar `config.json` — new `agents` section

Add a top-level `agents` block to the sidecar's own `config.json` (alongside `http`, `auth`, `gateway`, etc.). Each agent's key is the agent id; the value mirrors the native `avatarStates` shape:

```jsonc
{
  "port": 8443,
  "http": { "assetsDir": "./assets", "publicBaseUrl": "https://...", "publicAssets": false },
  "auth": { "token": "..." },
  "gateway": { "host": "127.0.0.1", "port": 18789, "token": "..." },

  "agents": {
    "ginger": {
      "avatarStates": {
        "default": "neutral",
        "states": {
          "neutral":  { "file": "avatars/ginger/neutral.gif",  "description": "resting / listening" },
          "thinking": { "file": "avatars/ginger/think.gif",    "description": "processing, working through a problem" },
          "happy":    { "file": "avatars/ginger/smile.gif",    "description": "warm, supportive, agreeing" },
          "sad":      { "file": "avatars/ginger/frown.gif",    "description": "sympathy, disappointment" },
          "angry":    { "file": "avatars/ginger/angry.gif",    "description": "frustration, firm boundary" },
          "curious":  { "file": "avatars/ginger/cock-head.gif","description": "uncertain, asking a clarifying question" }
        },
        "instruction": "(optional override; see §4 — sidecar auto-generates if absent)"
      }
    }
  }
}
```

Field semantics match the native spec exactly. See [`avatar-states.md#field-reference`](./avatar-states.md#field-reference).

## 3. Sidecar `/agents` proxy — transform step

The sidecar already proxies the gateway's `agents.list` at `GET /agents` and rewrites relative avatar URLs to absolute ones. Extend that transform to synthesize `avatarStates`:

```js
// In the /agents handler, after receiving the gateway response:
if (data.agents?.length) {
  for (const agent of data.agents) {
    const agentCfg = sidecarConfig.agents?.[agent.id];
    if (!agentCfg?.avatarStates) continue;

    const states = {};
    for (const [name, entry] of Object.entries(agentCfg.avatarStates.states ?? {})) {
      if (!entry?.file || typeof entry.file !== "string") continue;
      const file = /^(https?:|data:)/.test(entry.file)
        ? entry.file
        : buildAssetUrl(entry.file, tok);   // <publicBaseUrl>/assets/<file>?token=<tok>
      states[name] = { file, description: entry.description };
    }

    agent.identity = agent.identity ?? {};
    agent.identity.avatarStates = {
      default: agentCfg.avatarStates.default,
      states,
      instruction: agentCfg.avatarStates.instruction?.trim()
        || buildAvatarStateInstruction(agentCfg.avatarStates),
    };
  }
}
```

### `buildAvatarStateInstruction` — port

Port of the gateway-side helper. ~20 lines:

```js
function buildAvatarStateInstruction(statesCfg) {
  if (typeof statesCfg.instruction === "string" && statesCfg.instruction.trim()) {
    return statesCfg.instruction.trim();
  }
  const lines = [
    "You can change your avatar expression during a reply by writing a marker on its own line.",
    "Marker format: `[avatar:<state>]` — the line must contain nothing else.",
    "The marker is stripped from the visible reply; use it to convey tone as you speak.",
    "",
    "Available states:",
  ];
  for (const [name, entry] of Object.entries(statesCfg.states ?? {})) {
    const desc = entry?.description?.trim() ?? "";
    lines.push(desc ? `- ${name}: ${desc}` : `- ${name}`);
  }
  lines.push("", `Default state: ${statesCfg.default}.`);
  lines.push(
    "Switch states multiple times per reply when it helps the tone land. Do not mention this marker system in your reply.",
  );
  return lines.join("\n");
}
```

### Guard against config shape drift

If `agent.identity.avatar` in the proxied response is not a string (defensive — no published build should emit an object today, but be nice to future builds), skip the existing avatar-URL rewrite for that agent so it doesn't crash on `.startsWith`:

```js
const avatar = agent.identity?.avatar;
if (typeof avatar === "string" && !avatar.startsWith("http")) {
  agent.identity.avatar = buildAssetUrl(avatar, tok);
}
```

## 4. Phone companion — interim responsibilities

Same contract as the native integration spec, minus the `avatar.state.change` WS event (published gateway doesn't emit it) and minus gateway-side marker stripping.

**Additions vs. native:**

- On each assistant-text delta, run the text through a client-side marker parser. Strip `[avatar:<state>]` lines that are otherwise alone on their line. Forward clean text to the UI.
- Synthesize `avatar.state.change` locally when markers are parsed — treat each parsed marker as if the gateway fired one, then look up the state file and push bytes to the watch.

**Marker parser (Kotlin port of `src/gateway/avatar-marker-parser.ts`):**

- Streaming-safe: buffers the trailing partial line across chunk boundaries.
- A marker line matches `^[ \t]*\[avatar:([a-zA-Z0-9_-]+)\][ \t]*$` — line must contain nothing else.
- Inline `[avatar:X]` amid other text is *not* a marker; pass through as literal.
- Roughly 80 lines of pure code, no Android dependencies.

Same session-bootstrap logic as the native spec:

1. Read `agents.list.identity.avatarStates` (now synthesized by the sidecar).
2. Inject `avatarStates.instruction` as a system message on new session start, states-aware agents only.
3. Build `state → absolute URL` map from `avatarStates.states`.
4. Fetch default-state bytes; push to watch.

## 5. Watch — zero change

Exactly as the native spec: `DataClient` at `/openclaw/avatars/<agentId>` drives the rendered image. The watch doesn't care whether state changes came from a gateway WS event or from a phone-parsed marker.

## 6. Migration to native

When a release with the native object-form widening ships:

1. **Sidecar:** delete the `/agents` transform block and the `agents.*.avatarStates` section from `config.json`. Drop `buildAvatarStateInstruction` from the sidecar code.
2. **`openclaw.json`:** rewrite `identity.avatar` from a string to the object form (copy the sidecar's old `avatarStates` value into it).
3. **Phone companion:** delete the client-side marker parser and local `avatar.state.change` synthesis. Subscribe to the gateway's `avatar.state.change` WS event and push bytes on each one.
4. **Watch:** no change.
5. **TODO in this repo:** delete this doc page, remove its nav entry, update `avatar-states.md` and `avatar-states-integration.md` to remove references.

Everything else is transparent. The same watch bits render the same animations before and after.

## TODO tracker (repo-side)

When you cut a release including the `feat/avatar-states-schema`, `feat/avatar-states-resolver`, and `feat/avatar-states-streaming` branches:

- [ ] Delete `docs/gateway/avatar-states-sidecar-interim.md`
- [ ] Remove its entry from `docs/docs.json`
- [ ] Remove "Until native widening ships…" banners from `avatar-states.md` and `avatar-states-integration.md`
- [ ] Update the sidecar reference (`scripts/gateway-data-plane/sidecar/README.md`) to drop the `avatarStates` synthesis section

## Related

- [`avatar-states.md`](./avatar-states.md) — native, operator-facing config reference.
- [`avatar-states-integration.md`](./avatar-states-integration.md) — native, client-facing integration spec.
- [`data-plane.md`](./data-plane.md) — sidecar endpoints that serve the asset bytes.
