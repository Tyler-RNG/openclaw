# SpriteCore

OpenClaw plugin that owns the data plane for multi-state sprite avatars and
voice/TTS. Once enabled, SpriteCore is the single source of truth for:

- per-agent avatar config (atlas image + manifest)
- per-agent voice descriptor (provider + voiceId for the watch / phone)
- the prompt block that teaches the model which avatar states exist (so it
  knows when to emit `[avatar:happy]`, `[avatar:thinking]`, etc.)
- HTTP asset serving (`/openclaw-assets/*`)
- streaming TTS proxy (`/stream/tts`)
- the gateway RPC `node.getCharacterManifest` that ships the watch a
  ready-to-render manifest

The agent's `identity.avatar` field in `openclaw.json` stays narrow: a
workspace-relative image path, an http(s) URL, a data URI, or a short string /
emoji. Anything richer (atlas, multiple states, prompting vocabulary, voice
selection) lives in this plugin's config block.

## Enable

```jsonc
{
  "plugins": {
    "entries": {
      "sprite-core": {
        "enabled": true,
        "config": {
          "assets": {
            "enabled": true,
            "assetsDir": "./assets",
            "publicAssets": false,
            "maxAssetSizeBytes": 10485760,
            "publicBaseUrl": "https://<your-machine>.<your-tailnet>.ts.net",
          },
          "streamTts": {
            "enabled": true,
            "provider": "elevenlabs",
            "apiKey": { "source": "env", "id": "ELEVENLABS_API_KEY" },
            "defaultModel": "eleven_turbo_v2",
          },
          "agents": {
            "agent": {
              "avatar": {
                "kind": "atlas",
                "default": "idle",
                "manifest": "avatars/agent/agent.atlas.json",
              },
              "voice": {
                "provider": "elevenlabs",
                "voiceId": "<your-voice-id>",
                "label": "default",
              },
              "prompting": {
                "descriptions": {
                  "idle": "calm / listening",
                  "thinking": "processing the user's request",
                  "happy": "warm / pleased",
                  "sad": "sympathy / disappointment",
                },
              },
            },
          },
        },
      },
    },
  },
}
```

## Default `agent` template

Ships under `extensions/sprite-core/template/agent/`. It declares four states
(`idle`, `thinking`, `happy`, `sad`) and includes a placeholder atlas image
(four solid-colored squares) so the runtime works the moment you enable the
plugin — no art required.

To use the template:

1. Copy `extensions/sprite-core/template/agent/` into
   `~/.openclaw/assets/avatars/agent/` (or wherever your `assetsDir` resolves
   to under the `avatars/<id>/` convention).
2. Paste the config block from `template/agent/README.md` into your
   `openclaw.json` under `plugins.entries["sprite-core"].config.agents.agent`.
3. Restart the gateway. The watch will fetch the manifest, render the four
   placeholder colors, and auto-swap to `thinking` on every send.

Replace the placeholder image with real art whenever you have it; the manifest
schema does not need to change. See
`extensions/sprite-core/template/agent/README.md` for the swap procedure.

## Config reference

### `assets`

Static asset serving for atlas images, frame trees, audio clips.

| Field               | Type      | Notes                                                                                             |
| ------------------- | --------- | ------------------------------------------------------------------------------------------------- |
| `enabled`           | `boolean` | Required to be `true` for the route to register.                                                  |
| `assetsDir`         | `string`  | Path the route serves from. Relative paths resolve under `~/.openclaw/state`. Default `./assets`. |
| `publicAssets`      | `boolean` | When `true`, `/openclaw-assets/*` skips gateway auth. Use only when intentional.                  |
| `maxAssetSizeBytes` | `number`  | Hard cap on per-file size. Default 10 MiB.                                                        |
| `publicBaseUrl`     | `string`  | URL the plugin advertises to clients in `/sprite-core/agents`. Useful for Tailscale endpoints.    |

Path traversal (`..`), symlinks pointing outside `assetsDir`, and dotfiles are
rejected. ETag + 24 h `Cache-Control` are set automatically.

### `streamTts`

Streaming TTS proxy. Today only ElevenLabs is wired.

| Field          | Type           | Notes                                                                                              |
| -------------- | -------------- | -------------------------------------------------------------------------------------------------- |
| `enabled`      | `boolean`      | Required to be `true` for the route to register.                                                   |
| `provider`     | `"elevenlabs"` | Only value supported today.                                                                        |
| `apiKey`       | `SecretInput`  | Use `{ "source": "env", "id": "ELEVENLABS_API_KEY" }`. Plain strings are accepted but discouraged. |
| `defaultModel` | `string`       | ElevenLabs model id. Default `eleven_turbo_v2`. Override per request via `?model=` query param.    |

> **The plugin ships without an ElevenLabs key.** You provide your own.
> Without `streamTts.enabled = true` and a valid `apiKey`, `/stream/tts`
> returns 503 and the watch falls back silently — agents still work, the
> avatar still animates, just no spoken audio. See [ElevenLabs setup](#elevenlabs-setup).

### `agents.<id>`

Per-agent rich descriptor that supersedes the legacy
`agents.list[].identity.avatar` object form and `agents.list[].voice` block.

| Field       | Type              | Notes                                                                                             |
| ----------- | ----------------- | ------------------------------------------------------------------------------------------------- |
| `avatar`    | `AvatarConfig`    | Atlas descriptor — see below.                                                                     |
| `voice`     | `VoiceConfig`     | `{ provider, voiceId, label, … }` — extra keys passed through to the watch.                       |
| `prompting` | `PromptingConfig` | Per-state descriptions used to build the model-side instruction. Optional `instruction` override. |

#### `AvatarConfig` — `kind: "atlas"` (only kind currently supported)

| Field      | Type      | Notes                                                        |
| ---------- | --------- | ------------------------------------------------------------ |
| `kind`     | `"atlas"` | Discriminator.                                               |
| `default`  | `string`  | State the agent holds when idle. Conventionally `idle`.      |
| `manifest` | `string`  | Path to the atlas JSON manifest, resolved under `assetsDir`. |

The manifest itself owns frame rects, animations, and transitions — see
`docs/avatars/formats.md` for the full atlas schema.

#### `VoiceConfig`

Pass-through descriptor surfaced to the watch / phone via
`/sprite-core/agents`. Extra keys are allowed.

```jsonc
"voice": {
  "provider": "elevenlabs",
  "voiceId":  "21m00Tcm4TlvDq8ikWAM",
  "label":    "default"
}
```

#### `PromptingConfig`

Drives the system-prompt block that teaches the model the avatar's emotion
vocabulary.

| Field          | Type                    | Notes                                                                                             |
| -------------- | ----------------------- | ------------------------------------------------------------------------------------------------- |
| `descriptions` | `Record<state, string>` | One entry per state. Used to render `- <state>: <description>` lines in the injected instruction. |
| `instruction`  | `string` (optional)     | Explicit override. When set, replaces the auto-generated text entirely.                           |

The state names you list here must match keys in the atlas manifest's
`animations` table — that's how the watch maps a model-emitted
`[avatar:happy]` marker to the right animation.

The keyword vocabulary (state names) lives in the gateway plugin; the parsing
of `[avatar:<state>]` markers from the model output stays on the gateway side
(`src/gateway/avatar-marker-parser.ts`) and the playback code stays on the
edge devices (Wear OS DisplayKit). So edge devices stay generic — any state
name in the manifest just works.

## Routes

| Path                          | Auth      | Purpose                                                                |
| ----------------------------- | --------- | ---------------------------------------------------------------------- |
| `GET /openclaw-assets/<path>` | gateway\* | Static asset serving. \*`auth: "plugin"` when `publicAssets: true`.    |
| `GET /stream/tts`             | gateway   | Streaming TTS proxy (ElevenLabs).                                      |
| `GET /sprite-core/agents`     | gateway   | `{ agents: { <id>: { avatar, voice } }, publicBaseUrl? }` for clients. |

## Gateway RPC

| Method                      | Purpose                                                                                                                                                                              |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `node.getCharacterManifest` | Returns `{ manifest, revision }` — a ready-to-render manifest assembled from the plugin's per-agent atlas config + on-disk atlas JSON. The watch calls this through the phone relay. |

`node.getCharacterManifest` is registered by this plugin via
`api.registerGatewayMethod` from `extensions/sprite-core/index.ts`. When the
plugin is disabled, the method is unregistered and returns "method not found"
naturally — operators get a graceful degradation rather than a stale handler.

## How `thinking` auto-plays

The Wear OS phone-relay (`apps/android/app/src/main/java/ai/openclaw/app/wear/WearRelayService.kt`)
publishes a `state: "thinking"` cue on the `/openclaw/avatars/<agentId>/state`
DataClient path the moment the user sends a message. If your manifest declares
a `thinking` animation, DisplayKit swaps to it. If it doesn't, the watch
no-ops and stays on the previous state.

Model-emitted `[avatar:<state>]` markers (parsed by
`src/gateway/avatar-marker-parser.ts`) override this state mid-reply — last
write wins.

## ElevenLabs setup

The plugin **does not** ship with a key. Steps for an operator:

1. Create an ElevenLabs account at <https://elevenlabs.io>.
2. Get your API key from the profile menu.
3. Export it in your shell environment (the gateway must inherit it):
   ```bash
   export ELEVENLABS_API_KEY="sk_..."
   ```
4. Pick a voice id from your ElevenLabs voice library.
5. Wire both into `openclaw.json` under `plugins.entries["sprite-core"].config`:
   - `streamTts.apiKey = { "source": "env", "id": "ELEVENLABS_API_KEY" }`
   - `agents.<id>.voice = { "provider": "elevenlabs", "voiceId": "<your-id>" }`
6. Restart the gateway.

If you don't enable `streamTts`, agents still work normally — the watch's
TTS playback path falls back silently.

## Security

- Asset serving rejects path traversal (`..`), symlinks pointing outside
  `assetsDir`, and dotfile access.
- File size capped by `maxAssetSizeBytes`.
- `publicAssets: true` skips gateway auth — only set this when you intentionally
  serve operator-chosen files to anonymous clients (e.g. avatars on a public web page).
- The ElevenLabs API key should be a `SecretRef` (env, file, keychain), never
  inlined as a plain string in committed config.

## Plugin self-containment

Everything avatar / character-manifest now lives in this plugin:

- `src/prompting.ts` owns `buildPromptingInstruction` + `isAtlasAvatarConfig`.
- `src/character-manifest.ts` owns `buildCharacterManifest` and the wire-shape
  inlined `CharacterManifest` type.
- `index.ts` registers `node.getCharacterManifest` via
  `api.registerGatewayMethod` and reads fresh plugin config per call.

Core has no atlas-shaped types: `IdentityConfig.avatar` is narrowed back to
`string` (path / URL / data URI / emoji), `AgentAvatarAtlasConfig` and
friends are deleted, the gateway agent row no longer carries an `avatarAtlas`
block. Disable the plugin and the only thing that stops working is the
multi-state sprite avatar (the simple string avatar still resolves through
core's `resolveAgentAvatar`).

### Open follow-ups

- The plugin builds a prompt instruction (`buildPromptingInstruction`) but
  nothing in core injects it into the model's system prompt today. Wire the
  plugin to register a `before-agent-start` hook that prepends the
  instruction so the model actually learns the avatar marker vocabulary.
- Per-agent `voice` still lives on `agents.list[].voice` in core (read by
  `src/gateway/session-utils.ts:listAgentsForGateway`). Plugin already
  accepts it under `agents.<id>.voice`; consolidate so core no longer has
  the field.

## Pixellab.ai pipeline

The plugin ships two Node scripts. Together they cover the create → animate
→ package flow end to end (once the animate step has its own script).

### Create a character

```bash
node extensions/sprite-core/scripts/pixellab-create.mjs \
  --name "elf" \
  --description "a magical elf with pointed ears"
```

Queues a 4-direction character on pixellab, polls the background job, and
prints the new `character_id` plus the four rotation URLs so you can eyeball
the look before adding animations. `--json` emits just the id + rotations
for scripting.

### Add animations

Not yet ported. Use the pixellab.ai web UI or the animate-character script
(operator-supplied).

### Export into SpriteCore

The plugin ships a Node exporter that downloads a finished pixellab.ai
character bundle by UUID and writes a SpriteCore-compatible atlas + manifest
directly into `<assetsDir>/avatars/<agentId>/`:

```bash
# Quick path — assumes pixellab key is in `pass` or exported as PIXELLAB_API_KEY
node extensions/sprite-core/scripts/pixellab-export.mjs \
  --uid <pixellab-character-uuid>

# Explicit key command + custom output root
PIXELLAB_API_KEY="$(op read op://vault/pixellab/api-key)" \
  node extensions/sprite-core/scripts/pixellab-export.mjs \
  --uid <uuid> \
  --assets-root ~/.openclaw/state/assets/avatars \
  --overwrite

# Dry-run the plan without touching pixellab or disk
node extensions/sprite-core/scripts/pixellab-export.mjs --uid <uuid> --dry-run
```

Auth resolution order: `PIXELLAB_API_KEY` env → `--api-key-command "<cmd>"`
→ `pass show pixellab/api-key`. Pick whichever matches your secret store.

Output:

- `<assetsDir>/avatars/<agentId>/<agentId>.atlas.webp` — packed atlas image.
- `<assetsDir>/avatars/<agentId>/<agentId>.atlas.json` — manifest.
- `<assetsDir>/avatars/<agentId>/frames/<state>/NN.webp` — per-state frame
  tree (useful for re-packing via `pnpm avatar:pack`).

The exporter pairs zip-folder hashes with the pixellab API's
`animation_type` field (via `GET /characters/<id>/animations`) to emit clean
canonical SpriteCore state names — `happy`, `sad`, `thinking`, `idle` — and
generates descriptions from the animation's `display_name` (or the original
emotion prompt when no display name is set). Duplicate canonical names (e.g.
two `idle` animations of different lengths) get `_2`/`_3` suffixes. If the
metadata fetch fails, it falls back to verbose slug names.

For the end-to-end create → approve → animate → export flow, see the
`openclaw-pixellab-avatar` skill at
`.agents/skills/openclaw-pixellab-avatar/SKILL.md`.

The `pixellab.ai` online pixel-sprite generator is a candidate art pipeline
for the template. The intent is:

1. Operator runs a Claude Code skill (`.agents/skills/openclaw-pixellab-avatar/SKILL.md`).
2. Skill walks them through pixellab signup + API key extraction.
3. Skill prompts pixellab to generate a character + the emotions/states the
   operator wants.
4. A packaging script (`scripts/avatars/pixellab-import.mjs`) downloads the
   results and wires them into the SpriteCore template layout
   (`avatars/<agentId>/<agentId>.atlas.{webp,json}`).

The skill exists as a stub. The packaging script is not yet implemented (the
upstream pixellab.ai API contract needs to be confirmed first); see
`scripts/avatars/pixellab-import.mjs` for the placeholder.
