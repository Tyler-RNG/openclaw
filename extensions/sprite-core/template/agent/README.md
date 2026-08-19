# Default `agent` Template

This is the template avatar that ships with the SpriteCore plugin. It declares
four states — `idle`, `thinking`, `happy`, `sad` — and renders as four
solid-colored placeholder squares so the runtime works end-to-end without
shipping any real art.

**State vocabulary is open.** You can declare more states (e.g. `angry`,
`curious`, `surprised`) or fewer. Two names are special:

- `idle` — the default state declared on the agent entry; what the avatar holds
  when nothing is happening.
- `thinking` — auto-played by the watch / phone relay while waiting for a
  reply (no model marker required). If you omit this state, the watch falls
  back to whatever the current state is. See
  `apps/android/app/src/main/java/ai/openclaw/app/wear/WearRelayService.kt`
  for the dispatch.

All other state names are model-driven via `[avatar:<state>]` markers in the
reply text. The plugin generates the prompt that teaches the model which states
exist from the `prompting.descriptions` block in the plugin config.

## Files

- `agent.atlas.json` — atlas manifest (frame rects + animation defs + transitions).
- `agent.atlas.webp` — the packed atlas image (placeholder; replace with real art).
- `regenerate-placeholder-atlas.mjs` — regenerates the placeholder WebP from
  the four hard-coded colors. Only useful if you change the placeholder
  palette; not part of normal operator workflow.

## Use it

After enabling the SpriteCore plugin (see `extensions/sprite-core/README.md`),
copy this directory into `~/.openclaw/assets/avatars/agent/` and add this
entry to your `openclaw.json`:

```jsonc
"plugins": {
  "entries": {
    "sprite-core": {
      "enabled": true,
      "config": {
        "assets": { "enabled": true, "assetsDir": "./assets" },
        "agents": {
          "agent": {
            "avatar": {
              "kind": "atlas",
              "default": "idle",
              "manifest": "avatars/agent/agent.atlas.json"
            },
            "prompting": {
              "descriptions": {
                "idle":     "calm / listening",
                "thinking": "processing the user's request",
                "happy":    "warm / pleased",
                "sad":      "sympathy / disappointment"
              }
            }
          }
        }
      }
    }
  }
}
```

## Replace the placeholder with real art

1. Author 256×256 (or any uniform size) WebP/PNG frames per state under
   `~/.openclaw/assets/avatars/agent/frames/<state>/NN.webp`.
2. Run `pnpm avatar:pack agent` to repack into `agent.atlas.webp` +
   `agent.atlas.json`.
3. Restart the gateway (or `openclaw reload`).

See `docs/avatars/formats.md` for the full atlas spec, phased states
(intro/loop/outro), transitions (crossfade etc.), and the packer reference.

## Generate art with pixellab.ai

See `.agents/skills/openclaw-pixellab-avatar/SKILL.md` for a guided flow that
walks you through pixellab signup, character + emotion prompting, and (once
the import script lands) packaging the result into this template's shape.
