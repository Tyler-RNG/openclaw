---
summary: "Multi-state agent avatars: configure a set of named expressions (happy, sad, angry…) and let the model switch between them mid-reply via inline markers."
read_when:
  - Setting up an agent whose avatar should change expression during a conversation
  - Wiring up a watch or Android companion that can render multi-state avatars
  - Authoring a setup assistant / onboarding wizard that needs to explain this feature
title: "Multi-state avatars"
---

# Multi-state avatars

By default, an agent's `identity.avatar` is a single value — a path, URL, data URI, emoji, or short text. That renders the same image or emoji for every reply.

**Multi-state** avatars let an agent expose a set of named expressions (e.g. `happy`, `sad`, `angry`, `curious`, `neutral`) and let the model pick one per line while it writes. The model emits an inline marker, the gateway strips it from the visible text, and a capable client swaps the rendered image to match. Clients that don't understand the new shape ignore it and the agent behaves exactly like it did before.

This is opt-in per agent. If you don't configure it, nothing changes.

## Config shape

```json
{
  "agents": {
    "list": [
      {
        "id": "ginger",
        "identity": {
          "avatar": {
            "kind": "states",
            "default": "neutral",
            "states": {
              "neutral": { "file": "avatars/ginger/neutral.gif", "description": "resting" },
              "happy":   { "file": "avatars/ginger/smile.gif",   "description": "warm, supportive" },
              "sad":     { "file": "avatars/ginger/frown.gif",   "description": "sympathy, disappointment" },
              "angry":   { "file": "avatars/ginger/angry.gif",   "description": "frustration, setting a boundary" },
              "curious": { "file": "avatars/ginger/think.gif",   "description": "thinking, uncertain" }
            },
            "instruction": "(optional — replaces the auto-generated instruction text)"
          }
        }
      }
    ]
  }
}
```

### Field reference

| Field | Required | Description |
|---|---|---|
| `kind` | yes | Literal `"states"`. Discriminator. |
| `default` | yes | Name of the default state. Must exist in `states`. |
| `states` | yes | Map from state name → `{ file, description? }`. At least one entry. |
| `states.<name>.file` | yes | Free-form string the client resolves. Typical forms: workspace-relative path (`avatars/ginger/happy.gif`), `/assets/...` URL served by the data plane, or an absolute `http(s)://…` URL. |
| `states.<name>.description` | no | Short description surfaced to the model so it can pick the right state. Omit for states that don't need per-state hinting. |
| `instruction` | no | Full override for the system instruction clients inject on new sessions. When unset, the instruction is built automatically from the state descriptions. |

### Constraints

- State names match `[a-zA-Z0-9_-]+`. Names like `happy-v2` or `state_1` are fine. Spaces, dots, and symbols are rejected.
- `default` must be a key in `states`.
- `file` must be a non-empty string. The gateway does not validate that the file exists — that's the client's / data-plane's job.
- Alongside legacy string avatars and multi-state objects, the config accepts nothing else. Any other shape causes a schema validation error.

## How it works end-to-end

1. **Config** — operator adds the multi-state block above.
2. **agents.list** — the gateway's `agents.list` RPC returns `identity.avatarStates = { default, states, instruction }` on that agent. Clients that don't understand the field simply ignore it.
3. **Client bootstrap** — a multi-state-aware client (watch, Android companion) reads `avatarStates.instruction` and injects it as a system message when starting a new session with this agent. Clients that ignore it just don't send the instruction and the agent won't try to emit markers.
4. **Model reply** — the model, knowing the marker protocol, writes lines like:
   ```
   [avatar:happy]
   That's a great question! Let me think about that.
   [avatar:curious]
   There are a few angles here...
   ```
5. **Gateway stream parser** — each chunk of assistant text passes through a streaming parser that
   - recognizes lines matching `[avatar:<state>]` (whitespace tolerated, line must contain nothing else),
   - strips the marker line from the visible text,
   - emits a state-change event alongside the text stream.

   *Note: the parser ships as a pure function today; the live-stream splice is Phase 2 of the rollout.*
6. **Client render** — the client swaps the rendered image to `states[<state>].file`. Unknown or stale state names fall back to `default`.

## Marker format

- `[avatar:<state>]` on its own line.
- Leading/trailing spaces and tabs on the marker line are tolerated.
- The line must contain nothing else. If there's other text on the same line, the `[avatar:X]` is treated as literal text, not a marker.
- State names match `[a-zA-Z0-9_-]+`.
- Markers are emitted as often as the model wants — typical usage is one at the start of a reply plus occasional mid-reply switches when the tone changes.

## Reserved state names (gateway auto-emits)

Two state names have special semantics — if they appear in your `states` map, the gateway emits `avatar.state.change` events for them automatically, without requiring the model to emit a marker.

| Name | When gateway emits | Purpose |
|---|---|---|
| `thinking` | On `lifecycle.start` of any run | Gives the watch a visible "working on it" signal even if the model never emits a marker (and useful while the client-side instruction injection is stubbed out). |
| `<cfg.default>` | On `lifecycle.end` if the run drifted off `default` | Resets the rendered expression to resting after each reply. |

**Semantics:**

- Operator opts in by naming a state `thinking` in `states`. Omit it and the gateway stays silent at run start.
- Model-emitted markers take precedence over auto-emits during a run. If the model emits `[avatar:happy]` and the run then ends, the gateway emits `<default>` to reset; if the model's last marker was already `<default>`, no duplicate event fires.
- All other state names (`happy`, `sad`, `angry`, `curious`, custom names, …) are purely model-controlled via marker.

## What happens if…

- **The agent has no `avatar.kind: "states"` configured?** Nothing changes. The agent uses its single avatar (or none) exactly like before.
- **The client doesn't support multi-state avatars?** The client sees the legacy `avatar` / `avatarUrl` fields on `agents.list` (if any) and renders that. It never sends the instruction, so the model won't emit markers.
- **The model emits a marker for a state that doesn't exist?** The client silently ignores the change and stays on the previous state (or `default` if it hasn't rendered yet).
- **The model emits `[avatar:happy]` inline in a sentence?** It's treated as literal text and flows through to the user visibly. This is by design — it prevents accidentally stripping text that wasn't meant as a marker.
- **The marker is split across two stream chunks?** The parser buffers the partial marker and resolves it when the closing bracket and newline arrive. Non-marker text keeps streaming at normal speed.
- **Two clients are connected, one supports states and one doesn't?** Each gets the view it knows how to render. State-aware clients render `[avatar:X]` transitions; legacy clients render the static `avatar`. Both see the same visible text (markers stripped).

## Authoring tip for onboarding / setup flows

If you're building a setup wizard or onboarding assistant that helps users configure this feature, the minimum viable question set is:

1. **Which states do you want?** (default suggestion: `neutral`, `happy`, `sad`, `angry`, `curious`)
2. **Where are the GIFs / PNGs?** (path, URL, or data plane reference per state)
3. **What's the default state?** (must be one of the above; default suggestion: `neutral`)
4. **Describe when each state applies.** (one short line per state — these feed directly into the model's instruction)

That's enough to write a valid config block. Skip question 4 and the feature still works, it's just harder for the model to pick a good state.

## Related

- [Data plane (HTTP)](./data-plane.md) — how `/assets/...` references resolve to real bytes.
- [Configuration](./configuration.md) — agent identity configuration at the top level.
