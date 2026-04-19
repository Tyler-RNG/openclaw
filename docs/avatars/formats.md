# Avatar Formats — Artist & Integrator Guide

This is the living spec for every avatar format OpenClaw supports on the watch and in the control UI. Update this file whenever a new format lands, when a config key changes, or when a field is retired. Runtime config types in `src/config/types.agents.ts` and the zod schema in `src/config/zod-schema.ts` must stay in sync with this document.

## Why three formats

Each format is a different point on the control-vs-simplicity curve. Agents pick the format that matches their needs; the three paths coexist and an agent can migrate at any time.

| Format                            | Artist complexity                      | Playback control                                   | Bundle size                                              | When to use                                          |
| --------------------------------- | -------------------------------------- | -------------------------------------------------- | -------------------------------------------------------- | ---------------------------------------------------- |
| Legacy GIF (`kind: "states"`)     | Drop in one GIF per state              | Whole-clip loop only                               | Smallest (interframe delta compression)                  | Quick-and-done, single animation loop per state      |
| Sprite frames (`kind: "sprites"`) | Export numbered images per state       | Per-state fps, loop modes, intro/loop/outro phases | Medium (per-frame compression, one HTTP fetch per frame) | Want play-once + hold, ping-pong, or phased thinking |
| Sprite atlas (`kind: "atlas"`)    | Export one atlas image + JSON manifest | Same control as sprites, single fetch              | Smallest of the controllable paths                       | Shipping many states; reduces DataClient chatter     |

All three store assets under `~/.openclaw/assets/avatars/<agentId>/`. Path is configurable via `gateway.http.endpoints.assets.assetsDir` but `avatars/<agentId>/` inside that root is the convention.

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
  "intro": { "sequence": [0,1,2,3], "fps": 24, "loop": "once" },
  "loop":  { "sequence": [4,5,6,7], "fps": 12, "loop": "infinite" },
  "outro": { "sequence": [8,9,10],  "fps": 24, "loop": "once" }
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

## Format 1 — Legacy GIF (`kind: "states"`)

### Directory layout

```
~/.openclaw/assets/avatars/<agentId>/
├── neutral.gif
├── thinking.gif
├── happy.gif
├── sad.gif
├── angry.gif
└── curious.gif
```

### Config

```jsonc
"identity": {
  "avatar": {
    "kind": "states",
    "default": "neutral",
    "states": {
      "neutral":  { "file": "avatars/ginger/neutral.gif",  "description": "resting / listening" },
      "thinking": { "file": "avatars/ginger/thinking.gif", "description": "processing" },
      "happy":    { "file": "avatars/ginger/happy.gif",    "description": "warm" },
      "sad":      { "file": "avatars/ginger/sad.gif",      "description": "sympathy" },
      "angry":    { "file": "avatars/ginger/angry.gif",    "description": "frustration" },
      "curious":  { "file": "avatars/ginger/curious.gif",  "description": "uncertain" }
    }
  }
}
```

### Artist rules

- Square aspect (watch renders 143dp × 143dp cropped, so anything not square gets center-cropped).
- ≤ 200 KB per GIF recommended; hard cap 10 MB per the asset endpoint config. Dial animation quality is worth more than tiny byte savings — use quality GIF encoding (8–12 fps, 60–80 colors is usually enough for expressive loops).
- Transparent background OK but GIF only supports 1-bit transparency (no soft edges).
- Animation loops forever — no play-once in this format. Use Format 2 or 3 if you need playback control.

---

## Format 2 — Sprite frames (`kind: "sprites"`)

Individual images per frame. Full control over fps, loop, and phases. No atlas — watch prefetches each frame file independently.

### Directory layout

```
~/.openclaw/assets/avatars/<agentId>/
├── frames/
│   ├── neutral/
│   │   ├── 00.webp
│   │   ├── 01.webp
│   │   ├── 02.webp
│   │   ├── 03.webp
│   │   ├── 04.webp
│   │   └── 05.webp
│   ├── thinking/
│   │   ├── intro/
│   │   │   ├── 00.webp
│   │   │   ├── 01.webp
│   │   │   ├── 02.webp
│   │   │   └── 03.webp
│   │   ├── loop/
│   │   │   ├── 00.webp
│   │   │   ├── 01.webp
│   │   │   ├── 02.webp
│   │   │   └── 03.webp
│   │   └── outro/
│   │       ├── 00.webp
│   │       ├── 01.webp
│   │       └── 02.webp
│   ├── happy/
│   │   ├── 00.webp
│   │   ├── 01.webp
│   │   └── ... (up to 07.webp)
│   └── sad/
│       └── ...
└── (legacy .gif files can still live alongside; format is chosen per-agent-config)
```

### Config

```jsonc
"identity": {
  "avatar": {
    "kind": "sprites",
    "default": "neutral",
    "basePath": "avatars/ginger/frames",   // relative to assetsDir
    "format": "webp",                      // "webp" | "png" | "jpg"
    "transitions": {
      "*->thinking": "thinking.intro",
      "thinking->*": "thinking.outro"
    },
    "states": {
      "neutral": {
        "count": 6,                         // 00.webp … 05.webp under <basePath>/neutral/
        "fps": 12,
        "loop": "infinite",
        "description": "resting / listening"
      },
      "thinking": {
        "intro":  { "count": 4, "fps": 24, "loop": "once" },
        "loop":   { "count": 4, "fps": 12, "loop": "infinite" },
        "outro":  { "count": 3, "fps": 24, "loop": "once" },
        "description": "processing"
      },
      "happy": {
        "count": 8,
        "fps": 24,
        "loop": "ping-pong",
        "holdLastFrame": true,
        "description": "warm"
      },
      "sad":     { "count": 6, "fps": 10, "loop": "infinite", "description": "sympathy" },
      "angry":   { "count": 6, "fps": 24, "loop": "infinite", "description": "frustration" },
      "curious": { "count": 6, "fps": 14, "loop": "infinite", "description": "uncertain" }
    }
  }
}
```

### Frame filename convention

```
<basePath>/<state>/<NN>.<format>                        // single-sequence state
<basePath>/<state>/<phase>/<NN>.<format>                // phased state (intro|loop|outro)
```

- `<NN>` is zero-padded to 2 digits. If `count` ≥ 100, use 3 digits. Runtime reads `count` and formats the index with the needed width automatically.
- `<format>` is fixed per agent (can't mix webp and png in one avatar — keep it uniform).

### Artist rules

- Per-frame square images, same dimensions across all frames of a state (ideally across all states so RAM usage is predictable).
- WebP preferred — ~40% smaller than PNG at equivalent quality, supports alpha.
- Transparency is per-pixel (real alpha, unlike GIF).
- Recommended per-frame size: ≤ 40 KB. Whole-agent bundle across all states should stay ≤ 2 MB so watch prefetch is snappy.
- FPS guidance: 12 fps for idle/loop states, 24 fps for reactions, 30 fps max (watch display max refresh is 60 fps but GPU budget for composable rendering is tight — 30 is the safe ceiling).

---

## Format 3 — Sprite atlas (`kind: "atlas"`)

All frames packed into a single image + a JSON manifest describing frame positions and animation definitions. One atlas image + one manifest per agent = the leanest on-the-wire shape.

This is the target format for production. Artists author in frames (Format 2), then run `pnpm avatar:pack <agentId>` to produce the atlas. The frames tree can remain as a source-of-truth; the atlas is the published artifact.

### Directory layout

```
~/.openclaw/assets/avatars/<agentId>/
├── <agentId>.atlas.webp              ← single packed image, all frames
├── <agentId>.atlas.json              ← manifest: frame rects + animation defs
├── frames/                           ← OPTIONAL source-of-truth frames (input to the packer)
│   └── ...                           ← same layout as Format 2
└── (any legacy .gif files — ignored when kind:"atlas" is selected)
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

```jsonc
"identity": {
  "avatar": {
    "kind": "atlas",
    "manifest": "avatars/ginger/ginger.atlas.json",
    "default": "neutral",
    "descriptions": {
      "neutral":  "resting / listening",
      "thinking": "processing",
      "happy":    "warm",
      "sad":      "sympathy",
      "angry":    "frustration",
      "curious":  "uncertain"
    }
  }
}
```

The gateway dereferences the manifest when building the client descriptor, so per-state descriptions live in config (agent-side authorship) while playback timing / frame layout lives in the atlas manifest (artist-side authorship). This split keeps the artist's atlas independent of the agent's personality language.

### Frame name convention

Frame keys follow `"<state>/<NN>"` for single-sequence states or `"<state>.<phase>/<NN>"` for phased states. The `animations` table references these keys explicitly so the packer has freedom over the physical frame order inside the image.

### Artist rules

- Power-of-two atlas dimensions preferred (1024×1024, 2048×2048) for GPU upload efficiency.
- Keep the frame grid uniform (all frames the same `frameSize`) unless you really need variable frame sizes — uniform frames make slicing faster on the watch.
- WebP with alpha; quality 85 is usually indistinguishable from quality 100 at half the bytes.
- Atlas image should stay ≤ 2 MB. Manifest ≤ 50 KB.
- The packer (`pnpm avatar:pack`) handles deduplication — identical frames across states (e.g., the last `thinking.outro` frame equals the first `neutral` frame) will reuse a single rect in the atlas. Author can ship duplicates; atlas consolidates.

---

## How the three formats are served

### Filesystem

- All three formats live under `~/.openclaw/assets/avatars/<agentId>/`.
- The gateway's asset endpoint (`gateway.http.endpoints.assets`) serves anything under `assetsDir` at `GET /openclaw-assets/<path>?token=<token>` (or public if `publicAssets: true`).
- No special routing per format — everything is static file serving.

### Clients

- **Android phone (watch relay)** — reads `identity.avatar.kind` and chooses a prefetch strategy:
  - `states` → fetches each state's `.gif` once and publishes on DataClient at `/openclaw/avatars/<agentId>`
  - `sprites` → fetches every frame, publishes each at `/openclaw/avatars/<agentId>/frames/<state>[/phase]/<NN>`
  - `atlas` → fetches `<agentId>.atlas.webp` + `<agentId>.atlas.json`, publishes both at `/openclaw/avatars/<agentId>/atlas/image` and `/openclaw/avatars/<agentId>/atlas/manifest`
- **Wear OS watch** — subscribes to the DataClient prefix, builds a frame source, and drives playback via `AvatarRuntime` (shared across all three kinds — only the frame source differs).
- **Control UI dashboard** — loads the raw files directly from the gateway over HTTP; no DataClient involvement.

## Artist workflow

1. **Author in frames** (Format 2) using your tool of choice (Aseprite, Photoshop timeline + export layers, Rive export). Drop into `~/.openclaw/assets/avatars/<agentId>/frames/<state>[/phase]/<NN>.webp`.
2. **Test live** — flip that agent's config in `openclaw.json` to `kind: "sprites"` with the appropriate `count`/`fps`/`loop` per state. Reload. Frames play immediately.
3. **Pack when shipping** — run `pnpm avatar:pack <agentId>` to generate the atlas + manifest. Script writes `<agentId>.atlas.webp` and `<agentId>.atlas.json` sibling to the frames dir.
4. **Switch config** to `kind: "atlas"` pointing at the generated manifest. Delete or archive the `frames/` dir to shrink the runtime install (or keep it as the editable source-of-truth; the atlas is what ships).

## Migration from existing GIFs

If an agent already has `neutral.gif / thinking.gif / …` under `kind: "states"`:

```bash
# One-time extraction; emits frames/<state>/NN.webp + suggested config block
pnpm avatar:extract <agentId>
```

The script:

1. `ffmpeg -i <state>.gif -vsync 0 frames/<state>/%02d.webp` per state
2. Auto-detects fps from the GIF's frame delays (rounds to nearest integer)
3. Writes a starter config block in `frames/<agentId>.sprites-config.jsonc` with sensible defaults (`loop: "infinite"`, `fps` from the detected rate) — paste into `openclaw.json` to finish migration

After `extract` you're in Format 2. After `pack` you're in Format 3.

## Field reference (all formats)

### Common to sprite + atlas formats

| Field          | Type                               | Default | Meaning                                                                       |
| -------------- | ---------------------------------- | ------- | ----------------------------------------------------------------------------- |
| `kind`         | `"states" \| "sprites" \| "atlas"` | —       | Selects format                                                                |
| `default`      | string                             | —       | State name the agent holds when idle                                          |
| `descriptions` | `Record<state, string>`            | `{}`    | Per-state description for the avatar-states instruction injected to the model |

### `kind: "states"` (GIF) fields

| Field                       | Type   | Meaning                          |
| --------------------------- | ------ | -------------------------------- |
| `states.<name>.file`        | string | Gateway-relative path to the GIF |
| `states.<name>.description` | string | Prompt description               |

### `kind: "sprites"` fields

| Field                         | Type                                  | Default      | Meaning                                                                            |
| ----------------------------- | ------------------------------------- | ------------ | ---------------------------------------------------------------------------------- |
| `basePath`                    | string                                | —            | Gateway-relative dir prefix; frames under `<basePath>/<state>[/phase]/NN.<format>` |
| `format`                      | `"webp" \| "png" \| "jpg"`            | `"webp"`     | Frame file extension                                                               |
| `states.<name>.count`         | number                                | —            | Number of frames (single-sequence state)                                           |
| `states.<name>.fps`           | number                                | `12`         | Playback rate                                                                      |
| `states.<name>.loop`          | `"infinite" \| "once" \| "ping-pong"` | `"infinite"` | Loop mode                                                                          |
| `states.<name>.holdLastFrame` | boolean                               | `false`      | When `loop: "once"`, freeze on last frame instead of clearing                      |
| `states.<name>.iterations`    | number                                | infinite     | For `ping-pong`, cap the round trips                                               |
| `states.<name>.<phase>`       | same as state                         | —            | Phased state: `intro`/`loop`/`outro` each carry their own sequence fields          |
| `transitions`                 | `Record<pattern, ref \| blend>`       | `{}`         | Declarative state-swap transitions                                                 |

### `kind: "atlas"` fields

| Field          | Type                    | Meaning                                          |
| -------------- | ----------------------- | ------------------------------------------------ |
| `manifest`     | string                  | Gateway-relative path to the atlas JSON manifest |
| `default`      | string                  | State name                                       |
| `descriptions` | `Record<state, string>` | Per-state description                            |

The atlas _manifest_ owns `frames` / `animations` / `transitions` — the agent config only points at it and layers on descriptions.

## Update protocol for this doc

Whenever you touch:

- `src/config/types.agents.ts` — update the **Field reference** and **Config** examples
- `src/config/zod-schema.ts` — update the format-selection table and validation notes
- The phone `rewriteAvatars` prefetch logic — update the **How the three formats are served / Android phone** section
- The watch `AvatarRuntime` — update the **How the three formats are served / Wear OS watch** section
- Any packer / extractor script — update the **Artist workflow** and **Migration** sections

This file is the source of truth for artists. If it disagrees with code, code wins — but the disagreement is a bug, fix both.
