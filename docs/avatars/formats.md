# Avatar Formats — Artist & Integrator Guide

This is the living spec for the atlas avatar format OpenClaw supports on the watch and in the control UI. Update this file whenever the atlas shape changes, when a config key changes, or when a field is retired. Runtime config types in `src/config/types.base.ts` and the zod schema in `src/config/zod-schema.core.ts` must stay in sync with this document.

The gateway previously accepted two other avatar shapes (`kind: "states"` GIFs and `kind: "sprites"` per-frame images). Both have been retired. Authors can still use the scripts under `scripts/avatars/*` to extract frames from GIFs and pack them into an atlas — the intermediate sprite tree is a useful authoring input but is no longer served as a runtime format.

## Shared concepts

### Loop modes

| Mode        | Behavior                                                                                                                               |
| ----------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `infinite`  | Loops frames in order forever (GIF-style).                                                                                             |
| `once`      | Plays through the frame list one time. With `holdLastFrame: true` the player freezes on the final frame; without it the player clears. |
| `ping-pong` | Plays 0…N then N-1…0 back down; repeats. `iterations` caps the number of round trips (default: infinite).                              |

### Phased states (intro / loop / outro)

A state can be a single sequence or three named sub-sequences. Use phases when a state needs a smooth entry + a looping body + a smooth exit (classic example: `thinking.intro` plays once on entry, `thinking.loop` cycles while waiting, `thinking.outro` plays once when the reply lands).

```jsonc
"thinking": {
  "intro": { "frames": ["thinking.intro/00", "thinking.intro/01"], "fps": 24, "loop": "once" },
  "loop":  { "frames": ["thinking.loop/00",  "thinking.loop/01"],  "fps": 12, "loop": "infinite" },
  "outro": { "frames": ["thinking.outro/00"],                      "fps": 24, "loop": "once" }
}
```

When a state has no phases, the whole sequence is treated as the `loop` phase.

### Declarative transitions

Optional `transitions` table maps state-pair patterns to an animation the runtime plays _during_ the swap. Patterns support wildcards.

```jsonc
"transitions": {
  "*->thinking":   "thinking.intro",      // any state → thinking plays its intro phase
  "thinking->*":   "thinking.outro",      // leaving thinking plays its outro
  "*->happy":      { "blend": "crossfade", "ms": 150 },
  "neutral->sad":  { "blend": "crossfade", "ms": 300 }
}
```

A transition can be:

- **A phase reference** (`"thinking.intro"`) — runtime plays that phase once before entering the target state's own loop.
- **A blend object** (`{ "blend": "crossfade", "ms": N }`) — runtime cross-fades the outgoing final frame and the incoming first frame over N ms.

If no transition matches, the runtime swaps states instantly.

---

## Sprite atlas (`kind: "atlas"`)

All frames packed into a single image + a JSON manifest describing frame positions and animation definitions. One atlas image + one manifest per agent = the leanest on-the-wire shape.

Artists typically author in frames locally (using `pnpm avatar:extract` to slice legacy GIFs into WebPs, then hand-editing), then run `pnpm avatar:pack <agentId>` to produce the atlas. The frames tree can remain as a source-of-truth; the atlas is the published artifact.

### Directory layout

```
~/.openclaw/assets/avatars/<agentId>/
├── <agentId>.atlas.webp              ← single packed image, all frames
├── <agentId>.atlas.json              ← manifest: frame rects + animation defs
└── frames/                           ← OPTIONAL source-of-truth frames (input to the packer)
    └── ...
```

### Atlas manifest schema

```jsonc
// <agentId>.atlas.json
{
  "version": 1,
  "agent": "ginger",
  "image": "ginger.atlas.webp",       // filename, sibling to this manifest
  "size": { "w": 1024, "h": 1024 },   // atlas image pixel dimensions
  "frameSize": { "w": 256, "h": 256 },// per-frame render size (used for dst rect on watch)
  "frames": {
    // Keyed by stable name; value is the src rect inside the atlas image.
    "neutral/00":        { "x": 0,   "y": 0,   "w": 256, "h": 256 },
    "neutral/01":        { "x": 256, "y": 0,   "w": 256, "h": 256 },
    "neutral/02":        { "x": 512, "y": 0,   "w": 256, "h": 256 },
    "thinking.intro/00": { "x": 0,   "y": 256, "w": 256, "h": 256 },
    "thinking.loop/00":  { "x": 0,   "y": 512, "w": 256, "h": 256 },
    "thinking.outro/00": { "x": 0,   "y": 768, "w": 256, "h": 256 },
    "happy/00":          { "x": 0,   "y": 0,   "w": 256, "h": 256 }
    // … one entry per frame
  },
  "animations": {
    "neutral":  { "frames": ["neutral/00","neutral/01","neutral/02"], "fps": 12, "loop": "infinite" },
    "thinking": {
      "intro": { "frames": ["thinking.intro/00","thinking.intro/01"], "fps": 24, "loop": "once" },
      "loop":  { "frames": ["thinking.loop/00","thinking.loop/01"],   "fps": 12, "loop": "infinite" },
      "outro": { "frames": ["thinking.outro/00"],                     "fps": 24, "loop": "once" }
    },
    "happy":    { "frames": ["happy/00","happy/01","happy/02","happy/03"], "fps": 24, "loop": "ping-pong", "holdLastFrame": true },
    "sad":      { "frames": [...], "fps": 10, "loop": "infinite" },
    "angry":    { "frames": [...], "fps": 24, "loop": "infinite" },
    "curious":  { "frames": [...], "fps": 14, "loop": "infinite" }
  },
  "transitions": {
    "*->thinking": "thinking.intro",
    "thinking->*": "thinking.outro",
    "*->happy":    { "blend": "crossfade", "ms": 150 }
  }
}
```

### Config (in `openclaw.json`)

The atlas avatar lives under the SpriteCore plugin block, not on the agent's
`identity.avatar`. Agent identity stays narrow (a string path / URL / emoji);
anything richer — atlas selection, per-state descriptions, voice — is owned
by the plugin.

```jsonc
"plugins": {
  "entries": {
    "sprite-core": {
      "enabled": true,
      "config": {
        "assets": { "enabled": true, "assetsDir": "./assets" },
        "agents": {
          "ginger": {
            "avatar": {
              "kind": "atlas",
              "default": "neutral",
              "manifest": "avatars/ginger/ginger.atlas.json"
            },
            "emotions": {
              "neutral":  { "description": "resting / listening" },
              "thinking": { "description": "processing" },
              "happy":    {
                "description": "warm",
                "directive": { "style": 0.6, "speakerBoost": true }
              },
              "sad":      {
                "description": "sympathy",
                "directive": { "stability": 0.85, "speed": 0.95 }
              },
              "angry":    {
                "description": "frustration",
                "directive": { "style": 0.85, "stability": 0.2 }
              },
              "curious":  { "description": "uncertain" }
            }
          }
        }
      }
    }
  }
}
```

Each emotion entry carries a `description` (feeds the injected system prompt so the model knows what the state is for) and an optional `directive` (per-state TTS voice overrides — `voiceId`, `stability`, `similarity`, `style`, `speakerBoost`, `speed`, `audioTag`). Directive fields are merged field-by-field with the base TalkDirective when the client synthesizes the text segment that follows a `<<<state>>>` marker.

The optional `audioTag` is an inline emotion cue for TTS models that support them — ElevenLabs `eleven_v3` recognizes tags like `[happy]`, `[sad]`, `[excited]`, `[whispers]`, `[laughs]`, `[sighs]`. When set, the client prepends the tag to the segment's text before synthesis (`audioTag: "[happy]"` turns `"great today"` into `"[happy] great today"`). **Only set this when your configured `defaultModel` is `eleven_v3`** — older models (`eleven_turbo_v2`, `eleven_multilingual_v2`) don't understand the tags and will speak the bracketed text aloud.

The legacy `prompting.descriptions` shape is still honored as a fallback for states whose emotion entry has no description, giving operators a soft migration window. New agents should use `emotions` directly.

The gateway dereferences the manifest when building the client descriptor, so per-state descriptions live in plugin config (agent-side authorship) while playback timing / frame layout lives in the atlas manifest (artist-side authorship). This split keeps the artist's atlas independent of the agent's personality language.

See `extensions/sprite-core/README.md` for the full plugin config reference and the default `agent` template that ships with the plugin.

### Marker syntax

The model signals emotion changes by wrapping the state name in triple angle brackets: `<<<happy>>>`, `<<<thinking>>>`, `<<<sad>>>`. Markers may appear inline mid-sentence or on their own line; the gateway strips them from the visible text and emits them as state-change events so clients can drive avatar animation and per-segment TTS voice selection.

The `<<<…>>>` escape is deliberately unusual so the model is unlikely to emit it by accident. The vocabulary is only injected into the system prompt for sessions whose connected client advertises a sprite display capability (`display:sprite-headshot` or `display:sprite-fullbody`); dashboards, Telegram, and headless sessions do not see the marker instructions even when the plugin is installed.

### Frame name convention

Frame keys follow `"<state>/<NN>"` for single-sequence states or `"<state>.<phase>/<NN>"` for phased states. The `animations` table references these keys explicitly so the packer has freedom over the physical frame order inside the image.

### Artist rules

- Power-of-two atlas dimensions preferred (1024×1024, 2048×2048) for GPU upload efficiency.
- Keep the frame grid uniform (all frames the same `frameSize`) unless you really need variable frame sizes — uniform frames make slicing faster on the watch.
- WebP with alpha; quality 85 is usually indistinguishable from quality 100 at half the bytes.
- Atlas image should stay ≤ 2 MB. Manifest ≤ 50 KB.
- The packer (`pnpm avatar:pack`) handles deduplication — identical frames across states (e.g., the last `thinking.outro` frame equals the first `neutral` frame) will reuse a single rect in the atlas. Author can ship duplicates; atlas consolidates.

---

## How the atlas format is served

### Filesystem

- Atlas assets live under `~/.openclaw/assets/avatars/<agentId>/`.
- The gateway's asset endpoint (`gateway.http.endpoints.assets`) serves anything under `assetsDir` at `GET /openclaw-assets/<path>?token=<token>` (or public if `publicAssets: true`).
- No special routing — everything is static file serving.

### Clients

- **Android phone (watch relay)** — reads `identity.avatar.kind` and, for `atlas`, fetches `<agentId>.atlas.webp` + `<agentId>.atlas.json`, publishing both at `/openclaw/avatars/<agentId>/atlas/image` and `/openclaw/avatars/<agentId>/atlas/manifest`.
- **Wear OS watch** — subscribes to the DataClient prefix, builds a frame source from the atlas image + manifest, and drives playback via `AvatarRuntime`.
- **Control UI dashboard** — loads the raw files directly from the gateway over HTTP; no DataClient involvement.

## Artist workflow

1. **Author in frames** using your tool of choice (Aseprite, Photoshop timeline + export layers, Rive export). Drop into `~/.openclaw/assets/avatars/<agentId>/frames/<state>[/phase]/<NN>.webp`. The `scripts/avatars/*` tools exist to help here — `pnpm avatar:extract` slices an existing per-state GIF into numbered WebP frames, which is a convenient starting point.
2. **Pack when shipping** — run `pnpm avatar:pack <agentId>` to generate the atlas + manifest. Script writes `<agentId>.atlas.webp` and `<agentId>.atlas.json` sibling to the frames dir.
3. **Point config at the atlas** — set `identity.avatar` in `openclaw.json` to `{ "kind": "atlas", "default": "neutral", "manifest": "avatars/ginger/ginger.atlas.json" }`. Delete or archive the `frames/` dir to shrink the runtime install (or keep it as the editable source-of-truth; the atlas is what ships).

## Tooling reference

Both scripts read from / write to `~/.openclaw/assets/avatars/<agentId>/` by default. Override with `--assets-root <dir>` when working with a non-standard layout.

### `pnpm avatar:extract <agentId>` — GIF → frames

Splits an agent's existing per-state GIFs into numbered WebP frame sequences, as a handy authoring starting point. Also emits a starter config stub.

```bash
# Default — extract all <agentId>/<state>.gif files found on disk
pnpm avatar:extract ginger

# Force an fps override instead of detecting from GIF frame delays
pnpm avatar:extract ginger --fps 24

# Dry-run: print the plan without writing anything
pnpm avatar:extract ginger --dry-run

# Emit PNG instead of WebP (rare; WebP recommended)
pnpm avatar:extract ginger --format png
```

Outputs per-state `<agentId>/frames/<state>/NN.webp` (zero-padded to 2 digits; 3 digits when frame count ≥ 100). The starter config stub is purely informational — the runtime only accepts `kind: "atlas"`, so follow up with `pnpm avatar:pack` before wiring into `openclaw.json`.

Detects fps per GIF from its encoded frame delays (`ffprobe avg_frame_rate`), rounded to the nearest integer. Override with `--fps` if you want uniform timing across states.

### `pnpm avatar:pack <agentId>` — frames → atlas

Composites every frame under `<agentId>/frames/` into a single WebP atlas image and writes a sibling JSON manifest.

```bash
# Default — pack every state + phase found, WebP quality 85
pnpm avatar:pack ginger

# Force a specific grid width (otherwise the script picks ⌈√N⌉ cols)
pnpm avatar:pack ginger --cols 6

# Tighter quality / smaller file
pnpm avatar:pack ginger --quality 75

# Source frames are PNG instead of WebP
pnpm avatar:pack ginger --input-format png

# Dry-run — validate dimensions + print the grid without writing files
pnpm avatar:pack ginger --dry-run
```

Outputs:

- `<agentId>/<agentId>.atlas.webp` — every unique frame composited in a grid with transparent background
- `<agentId>/<agentId>.atlas.json` — manifest: `frames` rect-per-key map, `animations` per-state timing + frame refs, and `transitions` copied from a sibling starter config if present

**Deduplication:** identical frame bytes (detected by SHA-256) share a single atlas slot. A state whose last outro frame equals the first frame of the default state compacts automatically.

**Uniform dimensions:** all frames for a given agent must share width × height. The packer reads the first frame's dimensions and rejects any frame that differs. This is enforced so the runtime's atlas slicer can assume a uniform grid.

## Migration from legacy GIFs

```bash
# 1. Extract GIFs into frames
pnpm avatar:extract ginger

# 2. Pack into an atlas
pnpm avatar:pack ginger
# → point openclaw.json at the generated manifest:
#   { "kind": "atlas", "default": "neutral",
#     "manifest": "avatars/ginger/ginger.atlas.json" }
```

The source `frames/` dir can stay on disk as the editable source-of-truth — the runtime only reads the atlas.

## Field reference

### Top-level

| Field          | Type                    | Meaning                                                          |
| -------------- | ----------------------- | ---------------------------------------------------------------- |
| `kind`         | `"atlas"`               | Discriminator; must be the literal string `atlas`.               |
| `default`      | string                  | State name the agent holds when idle.                            |
| `manifest`     | string                  | Gateway-relative path to the atlas JSON manifest.                |
| `descriptions` | `Record<state, string>` | Per-state description for the instruction injected to the model. |
| `instruction`  | string                  | Optional explicit instruction override.                          |

The atlas _manifest_ owns `frames` / `animations` / `transitions` — the agent config only points at it and layers on descriptions.

## Update protocol for this doc

Whenever you touch:

- `src/config/types.base.ts` — update the **Field reference** and **Config** examples
- `src/config/zod-schema.core.ts` — update the format-selection table and validation notes
- The phone `rewriteAvatars` prefetch logic — update the **How the atlas format is served / Android phone** section
- The watch `AvatarRuntime` — update the **How the atlas format is served / Wear OS watch** section
- Any packer / extractor script — update the **Artist workflow** and **Migration** sections

This file is the source of truth for artists. If it disagrees with code, code wins — but the disagreement is a bug, fix both.
