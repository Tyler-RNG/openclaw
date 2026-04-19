---
summary: "End-to-end deploy guide for running multi-state agent avatars natively: self-built openclaw, identity.avatar in object form, gateway-hosted /assets, no sidecar. Phone+watch companion apps talk directly to the gateway over Tailscale HTTPS."
read_when:
  - Cutting over from the sidecar-synthesis interim pattern to the native feature
  - Spinning up a self-built openclaw from this repo with avatar-states enabled
  - Configuring Tailscale HTTPS as the single data plane for phone + watch
title: "Avatar states — native deploy"
---

# Avatar states — native deploy

When a published openclaw release includes the avatar-states schema widening, operators can stop running the sidecar-synthesis interim pattern and let the gateway do all the work. This page covers that cutover end-to-end.

<Note>
The code this guide depends on is merged to this repo's `main`. Published openclaw binaries don't yet ship it — you need to build from source until that changes. See [Building openclaw from source](#1-build-openclaw-from-source).
</Note>

## What moves vs. what stays

| Concern | Sidecar interim | Native (this guide) |
|---|---|---|
| `identity.avatar` in `openclaw.json` | plain string | `{ kind: "states", ... }` object |
| Per-agent states descriptor | sidecar's `config.json` | gateway's `openclaw.json` |
| `GET /assets/<path>` | sidecar on `:8443` fronted by Tailscale serve `:443` | gateway in-process, Tailscale serve points at gateway |
| `GET /stream/tts` | sidecar | gateway in-process (or omit) |
| `agents.list` → `identity.avatarStates` | sidecar synthesizes | gateway emits natively |
| `[avatar:<state>]` marker parsing | client-side in phone | gateway strips; phone still parses defensively |
| `avatar.state.change` WS events | not fired | gateway fires on model markers + lifecycle |
| Instruction injection | phone companion | phone companion (unchanged) |
| Bytes on watch | Wear `DataClient` | Wear `DataClient` (unchanged) |

## 1. Build openclaw from source

From a fresh clone of this repo on the machine you want to run the gateway on:

```bash
pnpm install
pnpm build
```

This produces `dist/` with the full server, including the avatar-states widening, native `/assets` handler, marker parser, and live stream splice.

To make the `openclaw` command on your PATH point at this build:

```bash
pnpm link --global
openclaw --version   # confirm it's the build from this repo
```

Or run in-place without linking:

```bash
node ./openclaw.mjs gateway
```

## 2. Flip `openclaw.json` to the object-form avatar

For every agent you want multi-state behavior on, replace the string `identity.avatar` with the object form:

```jsonc
{
  "agents": {
    "list": [
      {
        "id": "ginger",
        "identity": {
          "emoji": "🦞",
          "theme": "#ffaa00",
          "avatar": {
            "kind": "states",
            "default": "neutral",
            "states": {
              "neutral":  { "file": "avatars/ginger/neutral.gif",  "description": "resting / listening" },
              "thinking": { "file": "avatars/ginger/think.gif",    "description": "processing, working through a problem" },
              "happy":    { "file": "avatars/ginger/smile.gif",    "description": "warm, supportive, agreeing" },
              "sad":      { "file": "avatars/ginger/frown.gif",    "description": "sympathy, disappointment" },
              "angry":    { "file": "avatars/ginger/angry.gif",    "description": "frustration, firm boundary" },
              "curious":  { "file": "avatars/ginger/cock-head.gif","description": "uncertain, asking a clarifying question" }
            }
          }
        }
      }
    ]
  }
}
```

Constraints (see [`avatar-states.md`](./avatar-states.md)):
- `kind` must be `"states"`.
- `default` must exist in `states`.
- State names match `[a-zA-Z0-9_-]+`.
- `file` is a free-form string; the gateway serves it from `assetsDir` when prefixed with `/assets/...`.
- Adding a state named `thinking` opts into the gateway's auto-thinking-on-lifecycle-start behavior.

Mix-and-match is fine: some agents object-form, others plain string.

## 3. Enable the native data plane

Add to `openclaw.json`:

```json
{
  "gateway": {
    "http": {
      "endpoints": {
        "assets": {
          "enabled": true,
          "assetsDir": "./assets",
          "publicAssets": false,
          "maxAssetSizeBytes": 10485760,
          "publicBaseUrl": "https://<machine>.<tailnet>.ts.net"
        }
      }
    }
  },
  "dataPlane": {
    "baseUrl": "https://<machine>.<tailnet>.ts.net",
    "publicAssets": false,
    "streamTts": false
  }
}
```

- `gateway.http.endpoints.assets.enabled: true` turns on the in-gateway `/assets/<path>` handler.
- `assetsDir` — filesystem root (relative to the state dir if relative). Put your avatar GIFs here, e.g. `assets/avatars/ginger/neutral.gif`.
- `publicAssets: false` requires clients to pass the gateway auth token via Bearer header or `?token=` query.
- `dataPlane.baseUrl` — this is what clients read via `config.get` to know where to fetch from. For native deploy, it's the **gateway's own Tailscale URL** — no sidecar in the loop.

Optional TTS:

```json
{
  "gateway": {
    "http": {
      "endpoints": {
        "streamTts": {
          "enabled": true,
          "provider": "elevenlabs",
          "apiKey": { "source": "env", "provider": "default", "id": "ELEVENLABS_API_KEY" },
          "defaultModel": "eleven_turbo_v2"
        }
      }
    }
  }
}
```

Then set `dataPlane.streamTts: true` so clients know the capability exists.

## 4. Tailscale serve

Point Tailscale at the gateway's port. If your gateway listens on `:18789`:

```bash
sudo tailscale serve --https=443 --set-path / http://127.0.0.1:18789
sudo tailscale serve status
```

Verify:

```bash
curl -i "https://<machine>.<tailnet>.ts.net/health"
# 200 OK
```

## 5. Tear down the sidecar

Once the gateway is serving `/assets/*` and `/stream/tts` itself, the sidecar has nothing left to do.

```bash
sudo systemctl stop openclaw-sidecar
sudo systemctl disable openclaw-sidecar
# Optional, keep the unit file around in case you want to roll back:
# sudo rm /etc/systemd/system/openclaw-sidecar.service
# sudo systemctl daemon-reload
```

Tailscale serve should now route `/` → gateway (step 4), not sidecar. If you had `tailscale serve` pointing at the sidecar's `:8443`, re-run step 4 to redirect at the gateway.

Delete the `agents.*.avatarStates` block from the sidecar's `config.json` if you want (it's unused now), or leave it as a rollback breadcrumb.

## 6. Restart the gateway

```bash
sudo systemctl restart openclaw          # or however your unit is named
journalctl -u openclaw -f
```

Confirm in the log you see something like:

```
gateway ... listening on 127.0.0.1:18789
assets http ... enabled (assetsDir=<abs>, publicBaseUrl=https://...)
```

## 7. Test the data plane

```bash
TOKEN="<gateway auth token>"
HOST="<machine>.<tailnet>.ts.net"

# Liveness
curl -i "https://$HOST/health"

# Asset with query token (what clients use)
curl -i "https://$HOST/assets/avatars/ginger/neutral.gif?token=$TOKEN" -o /tmp/g.gif
file /tmp/g.gif     # GIF image data

# Unknown/bad path → 403 or 404, never 200
curl -i "https://$HOST/assets/../openclaw.json?token=$TOKEN"
```

And verify the native `agents.list` emits `avatarStates`:

```bash
curl -s "https://$HOST/agents?token=$TOKEN" \
  | jq '.agents[] | select(.id=="ginger") | .identity.avatarStates'
```

Expected: an object with `default`, `states`, `instruction`.

## 8. Phone companion: point at the gateway directly

In the phone app's Connect / Gateway settings:

- **Gateway URL:** `wss://<machine>.<tailnet>.ts.net/` (or however your app constructs the WS URL; the host is the same).
- **Data plane base URL:** comes down via `config.get` from the gateway's own `dataPlane.baseUrl` — no user configuration needed as long as `openclaw.json` has that field set (step 3).

After the phone reconnects with the new config:

- `agents.list` arrives with `identity.avatarStates` populated for your states-configured agents.
- Phone caches the descriptor, injects the instruction on first chat per agent, fires the thinking GIF on dispatch, parses any markers from the stream, publishes state bytes to Wear DataClient. All the pipes already built on `apps/android/app/src/main/java/ai/openclaw/app/wear/` now feed from the gateway instead of the sidecar — zero app-side changes needed to flip sources.

## 9. Watch companion: unchanged

Wear reads bytes from `/openclaw/avatars/<agentId>` via Wear `DataClient`. No changes required when switching from sidecar to native. Same APK works.

## What the logs should tell you after cutover

**Phone relay panel** (`apps/android/app/src/main/java/ai/openclaw/app/wear/WearRelayLog.kt`):

```
agents list sent
agents states: 1/12 agents
ginger states: neutral,thinking,happy,sad,angry,curious default=neutral
```

On watch chat:

```
chat ginger ← "hi there"
chat ginger: injecting avatar-states instruction
chat ginger avatar: thinking (dispatch)
...
chat ginger → interim 0ch
chat ginger → final 128ch
chat ginger avatar(happy): swap
chat ginger avatar(neutral): swap
```

The `avatar(happy): swap` appears whenever the model emits `[avatar:happy]` — parsed on the phone from cumulative block text (gateway also strips for WS subscribers, idempotent).

**Gateway log** (see also `avatar-states-integration.md`):

```
[gateway][chat] sending assistant-text delta (...)
[gateway][broadcast] avatar.state.change {agentId=ginger, state=thinking, ...}
```

`avatar.state.change` WS events fire for every model-emitted marker AND for auto-thinking/default transitions. Today no client subscribes to them — the phone still path-parses — but the event stream is there for future push-path work.

## Rollback

If something goes sideways and you want the sidecar back:

1. Stop the gateway or flip `gateway.http.endpoints.assets.enabled: false`.
2. Revert `openclaw.json` `identity.avatar` back to a plain string.
3. `systemctl start openclaw-sidecar` (service is still enabled unless you removed it).
4. Repoint Tailscale serve at the sidecar's port.
5. Restart the gateway.

Nothing in the client (phone/watch) needs rolling back — it treats both paths identically.

## Related

- [`avatar-states.md`](./avatar-states.md) — native config reference.
- [`avatar-states-integration.md`](./avatar-states-integration.md) — client-side integration spec for what phone + watch do.
- [`avatar-states-sidecar-interim.md`](./avatar-states-sidecar-interim.md) — the pattern this page replaces.
- [`tailscale.md`](./tailscale.md) — broader Tailscale setup for gateway serve.
- [`data-plane.md`](./data-plane.md) — the HTTP data plane contract.
