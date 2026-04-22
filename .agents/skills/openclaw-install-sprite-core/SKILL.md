---
name: openclaw-install-sprite-core
description: Install and configure the SpriteCore plugin for OpenClaw — enable the plugin, create the assets directory, copy the default agent template (or another authored avatar), paste the `plugins.entries["sprite-core"].config` block into `openclaw.json`, optionally wire ElevenLabs TTS, and restart the gateway. Use this when the user asks you to "install sprite-core", "enable the sprite avatar plugin", "set up multi-state avatars", or any equivalent phrasing.
---

# Install SpriteCore Plugin

Use this skill when the user wants the SpriteCore plugin installed on a fresh
(or partially-configured) gateway. The plugin owns avatar assets, per-agent
voice, prompting vocabulary, and the `node.getCharacterManifest` RPC.

## Read first

- `extensions/sprite-core/README.md` — canonical plugin config reference.
- `extensions/sprite-core/template/agent/README.md` — default template + config
  snippet you'll paste into `openclaw.json`.
- `docs/avatars/formats.md` — atlas wire format.

## Preconditions

Before running this flow, confirm with the user:

- **Agent id** the plugin should configure. Default is literally `agent`
  (matches the template). If the user already has named agents, ask which one
  should get the sprite avatar.
- **Assets location.** The plugin's `assetsDir` defaults to
  `~/.openclaw/state/assets` (via `resolveStateDir()`). If the user has a
  custom layout, read their existing `openclaw.json` first.
- **Voice.** ElevenLabs is the only provider wired today. The plugin ships
  without a key. If the user wants TTS:
  - They must provide `ELEVENLABS_API_KEY` in the gateway's environment.
  - They need an ElevenLabs `voiceId` from their account.
- **Real art or template.** The template is four solid-colored placeholder
  squares (idle/thinking/happy/sad). Works end-to-end without real art. Ask
  if they want to swap in custom art after install.

## Default workflow

Run these in order. Each step has a verify gate — stop and report if it
doesn't pass.

### 1. Verify the plugin is available

```bash
openclaw plugins list | grep sprite-core
```

Expected: `sprite-core` appears in the list with `enabled: false` (or already
enabled). If missing, the gateway build predates the plugin — tell the user
to update OpenClaw first.

### 2. Enable the plugin

```bash
openclaw config set plugins.entries.sprite-core.enabled true
```

### 3. Create the assets directory

```bash
mkdir -p ~/.openclaw/state/assets/avatars/<agent-id>
```

Substitute the agent id (default: `agent`). If the user specified a custom
`assetsDir`, use that root instead.

### 4. Copy the default template

From the repo checkout:

```bash
cp extensions/sprite-core/template/agent/agent.atlas.json \
   extensions/sprite-core/template/agent/agent.atlas.webp \
   ~/.openclaw/state/assets/avatars/<agent-id>/
```

If the user chose a non-default agent id, rename the files:

```bash
cd ~/.openclaw/state/assets/avatars/<agent-id>/
mv agent.atlas.json <agent-id>.atlas.json
mv agent.atlas.webp <agent-id>.atlas.webp
```

Also update the `"image"` and `"agent"` fields inside the `.atlas.json` file
to match the new agent id:

```bash
sed -i 's/"agent": "agent"/"agent": "<agent-id>"/; s/"image": "agent.atlas.webp"/"image": "<agent-id>.atlas.webp"/' <agent-id>.atlas.json
```

### 5. Paste the config block into `openclaw.json`

Use the `openclaw config set` CLI so the operator's edits route through the
normal config path. The block to apply is:

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
          },
          "agents": {
            "<agent-id>": {
              "avatar": {
                "kind": "atlas",
                "default": "idle",
                "manifest": "avatars/<agent-id>/<agent-id>.atlas.json",
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

Apply each leaf with `openclaw config set`:

```bash
openclaw config set plugins.entries.sprite-core.config.assets.enabled true
openclaw config set plugins.entries.sprite-core.config.assets.assetsDir ./assets
openclaw config set plugins.entries.sprite-core.config.assets.publicAssets false
openclaw config set plugins.entries.sprite-core.config.assets.maxAssetSizeBytes 10485760
openclaw config set plugins.entries.sprite-core.config.agents.<agent-id>.avatar.kind atlas
openclaw config set plugins.entries.sprite-core.config.agents.<agent-id>.avatar.default idle
openclaw config set plugins.entries.sprite-core.config.agents.<agent-id>.avatar.manifest avatars/<agent-id>/<agent-id>.atlas.json
openclaw config set plugins.entries.sprite-core.config.agents.<agent-id>.prompting.descriptions.idle "calm / listening"
openclaw config set plugins.entries.sprite-core.config.agents.<agent-id>.prompting.descriptions.thinking "processing the user's request"
openclaw config set plugins.entries.sprite-core.config.agents.<agent-id>.prompting.descriptions.happy "warm / pleased"
openclaw config set plugins.entries.sprite-core.config.agents.<agent-id>.prompting.descriptions.sad "sympathy / disappointment"
```

If the user prefers direct JSON editing, hand them the block verbatim and let
them paste it under `plugins.entries["sprite-core"]`. Either way, run
`openclaw config get plugins.entries.sprite-core` afterwards to confirm the
block landed.

### 6. ElevenLabs TTS (optional)

Only run these if the user confirmed they want voice.

```bash
# In the gateway's environment (add to shell profile or systemd unit):
export ELEVENLABS_API_KEY="sk_..."
```

Then wire it:

```bash
openclaw config set plugins.entries.sprite-core.config.streamTts.enabled true
openclaw config set plugins.entries.sprite-core.config.streamTts.provider elevenlabs
openclaw config set plugins.entries.sprite-core.config.streamTts.apiKey '{"source":"env","id":"ELEVENLABS_API_KEY"}'
openclaw config set plugins.entries.sprite-core.config.streamTts.defaultModel eleven_turbo_v2
openclaw config set plugins.entries.sprite-core.config.agents.<agent-id>.voice.provider elevenlabs
openclaw config set plugins.entries.sprite-core.config.agents.<agent-id>.voice.voiceId "<voice-id>"
openclaw config set plugins.entries.sprite-core.config.agents.<agent-id>.voice.label default
```

Ask the user for their preferred ElevenLabs voice id if they don't have one
handy (browse <https://elevenlabs.io/app/voice-library>).

### 7. Restart the gateway

Platform-dependent. On macOS: use the OpenClaw Mac app, not an ad-hoc kill.
On Linux/Docker: `systemctl restart openclaw-gateway` or equivalent.

### 8. Verify end-to-end

Run these as smoke checks:

```bash
# Plugin registered and serving /sprite-core/agents
TOKEN=$(openclaw config get gateway.auth.token)
curl -sH "Authorization: Bearer $TOKEN" http://localhost:18789/sprite-core/agents | jq .

# Atlas image served
curl -sI -H "Authorization: Bearer $TOKEN" \
  "http://localhost:18789/openclaw-assets/avatars/<agent-id>/<agent-id>.atlas.webp" \
  | head -n 3   # expect HTTP 200

# Character manifest RPC
openclaw gateway rpc node.getCharacterManifest '{"agentId":"<agent-id>"}' | jq .
```

Each should return success. If `/openclaw-assets/*` returns 404, the assets
weren't copied to the right place — re-check `assetsDir` and `manifest` path
resolution.

## Using custom art

If the user has real pixel-art frames instead of the placeholder, refer them
to `.agents/skills/openclaw-pixellab-avatar/SKILL.md` (for pixellab.ai-based
generation) or `docs/avatars/formats.md` (for hand-authored frames packed via
`pnpm avatar:pack`).

## Failure modes

- **`openclaw plugins list` doesn't include `sprite-core`** — their OpenClaw
  install predates the plugin. Update OpenClaw first.
- **`/sprite-core/agents` returns 404** — plugin is disabled or the route
  isn't registered. Re-check step 2.
- **`/openclaw-assets/*` returns 403** — `assetsDir` resolves outside the
  configured path or symlinks escape it. Check the `assetsDir` value.
- **`node.getCharacterManifest` returns "atlas-unreadable"** — the manifest
  path in `openclaw.json` doesn't match the file on disk. Check the file name
  matches the `manifest` config value.
- **Watch doesn't animate, no RPC errors** — the watch caches manifests. Have
  the user restart the watch app or toggle connectivity.

## Don't

- Don't edit `node_modules/@openclaw/sprite-core/**`. The plugin is bundled
  with the gateway package; upgrades will overwrite manual edits.
- Don't hard-code an ElevenLabs key in `openclaw.json`. Always use
  `{ "source": "env", "id": "ELEVENLABS_API_KEY" }`.
- Don't skip the restart step. Plugin registration happens at gateway startup.
