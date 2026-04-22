---
name: openclaw-pixellab-avatar
description: Walk an operator through generating a pixel-art avatar on pixellab.ai for the OpenClaw SpriteCore plugin and packaging it into the per-agent atlas + manifest layout. Use this when the user wants to create a new sprite avatar from scratch, add new emotion states to an existing one, or asks anything about pixellab.ai integration with OpenClaw. Pairs with `scripts/avatars/pixellab-import.mjs` for the actual asset packaging step (currently a stub).
---

# OpenClaw + pixellab.ai Avatar Skill

Use this skill when the user wants to author a SpriteCore avatar with
pixellab.ai art instead of hand-drawn frames. The skill orchestrates the
human + pixellab interaction; the packaging script does the file plumbing.

## Read first

- `extensions/sprite-core/README.md` — plugin config the assets must satisfy.
- `extensions/sprite-core/template/agent/README.md` — template layout the
  output must match.
- `docs/avatars/formats.md` — atlas spec the manifest must conform to.

## Default workflow

The pipeline has four steps. Each produces an artifact you verify before
moving on. Don't skip steps — each gate catches a different class of mistake.

### 1. Confirm the ask

Ask the user:

- **Character name** (short, e.g. `moon`, `elf`, `ginger`).
- **Character description** — a single sentence describing the look
  (e.g. `a magical elf with pointed ears`). PixelLab folds this into both
  the character's name and prompt fields (the API doesn't support them
  separately), so keep it concise + self-describing.
- **Target emotions / animations** — which states the final avatar will
  carry. Common set: `idle`, `thinking`, `happy`, `sad`, `angry`, `surprised`.
  At minimum include `idle` and `thinking` (the watch auto-plays `thinking`
  while waiting for replies).
- **Agent id** this will be wired into (default: `agent`).

### 2. Create the character (step 1 of 4)

Pixellab's create endpoint takes a description and returns a character_id +
4 directional rotations. It does **not** add animations yet.

```bash
node extensions/sprite-core/scripts/pixellab-create.mjs \
  --name "<short-name>" \
  --description "<one-sentence description>"
```

Options:

- `--width / --height <n>` — pixel frame size (default 96×96)
- `--api-key-command "<cmd>"` — custom secret source (falls back to
  `PIXELLAB_API_KEY` env or `pass show pixellab/api-key`)
- `--json` — emit `{ character_id, name, rotations }` for downstream chaining
- `--dry-run` — print the request payload without calling pixellab

The script waits up to 5 minutes for the background job to finish, then
prints the character id and the four rotation URLs (`south`, `west`, `east`,
`north`).

### 3. Operator approves the look

Open the four rotation URLs in a browser and eyeball them. If the character
doesn't match what the user wanted:

- Adjust the description and re-run step 2 (generates a fresh character_id).
- Or delete via `DELETE /v2/characters/{id}` and re-run.

If approved, hold the `character_id` — every downstream step needs it.

### 4. Add animations (step 3 of 4)

**This step uses a script the operator will supply separately.** The flow:

- Call pixellab's animate-character endpoint (`POST /v2/animate-character`)
  once per emotion.
- Pass the character_id + the emotion prompt.
- Wait for each background job to complete.

Until that script exists, the operator has two alternatives:

- Create the animations manually in the pixellab.ai web UI against the same
  character_id.
- Ask for the animate-character script from the human who owns this flow.

### 5. Export into SpriteCore (step 4 of 4)

Once animations exist on the character, the exporter pulls the ZIP bundle,
calls `/characters/<id>/animations` for canonical emotion names (`happy`,
`sad`, etc. — not the verbose pixellab slugs), packs frames into a WebP
atlas, writes the manifest, and prints the config snippet ready to paste
into `openclaw.json`.

```bash
# Writes atlas + manifest to ~/.openclaw/state/assets/avatars/<agent-id>/
node extensions/sprite-core/scripts/pixellab-export.mjs \
  --uid <character_id> \
  --overwrite

# Custom output root
node extensions/sprite-core/scripts/pixellab-export.mjs \
  --uid <character_id> \
  --assets-root ~/my-custom/assets/avatars \
  --overwrite

# Override the agent id (otherwise derived from the character's pixellab name)
node extensions/sprite-core/scripts/pixellab-export.mjs \
  --uid <character_id> \
  --agent-id <agent-id> \
  --overwrite
```

The exporter pairs zip-folder hashes with the API's `animation_type` field
to produce clean SpriteCore state names and derives descriptions from
pixellab's `display_name` (or the original emotion prompt when unset).
Duplicate canonical names (e.g. two `idle` animations of different lengths)
get `_2`/`_3` suffixes so the manifest stays valid.

The compat shim `scripts/avatars/pixellab-import.mjs` forwards to the same
exporter, so any existing call site keeps working.

### 6. Wire into `openclaw.json`

Copy the config snippet the exporter prints into `openclaw.json` under
`plugins.entries["sprite-core"].config.agents.<agent-id>`, then restart the
gateway. Default state from the snippet is `idle` when present; otherwise
the first animation. Review before saving — sometimes you want a more
specific default.

### 5. Verify

- `openclaw config get plugins.entries.sprite-core.config.agents.<agentId>`
  shows the block.
- `curl -H "Authorization: Bearer <gateway-token>" http://localhost:18789/sprite-core/agents`
  returns the agent's avatar + voice.
- Phone/watch refresh — the new manifest should arrive on the
  `/openclaw/avatars/<agentId>/character-manifest` DataClient path.

## Constraints

- Do not commit the pixellab API key. It belongs in the user's environment
  only.
- Do not invent pixellab.ai API endpoints. If the upstream contract isn't
  documented in this skill, ask the user to share their pixellab account
  docs before generating any code that hits the API.
- Frame dimensions must be uniform. If the user supplies mismatched sizes,
  ask which one is canonical and resize the others (sharp is in repo deps)
  before packing.

## Open TODOs

- The exporter currently targets existing pixellab characters (`--uid`). The
  **create character** pipeline (choose prompts, generate new emotions, save
  character id back to pixellab) is still user-owned — a future skill + script
  will cover that flow so this skill can run end-to-end from "no character" to
  "packaged atlas in plugin config."
- Animation name mapping: pixellab's generation names (e.g.
  `warm_smile_bright_eyes_joyful_expression`) are verbose. Add `--rename`
  support to the exporter so operators can collapse them to `happy`, `sad`,
  etc. in one pass.
