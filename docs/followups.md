# Follow-ups — things left to do

Living list of work items that have a clear design, a clear next owner, and haven't shipped yet. Update as work lands (strike items, add notes); close items out by removing them once the code is on `main`.

## Wear / avatar system

### `wear-avatar-state-signal` — sprite/atlas state swaps ✅ shipped

Phone now publishes `{state, ts}` DataItems at `/openclaw/avatars/<id>/state` on every marker dispatch; watch subscribes via `WearAssetStore.agentStates`; `SpriteAvatar`/`AtlasAvatar` call `runtime.requestState(newState)` on change. Leave this entry here as breadcrumbs until the next session confirms swaps are visible on-device.

### `wear-tiles` — per-agent Wear OS Tiles

Multi-day effort: Tile service that shows the agent's current avatar frame + last reply preview, tap launches the dial on that agent's page. Needs a phone-side picker ("pin these 4 as Tiles"), a `TileService` subclass, layout resources, and hookup through `WearAssetStore` for frame bitmaps. Design doc TBD before committing.

### `wear-aod` — always-on display (AmbientMode)

Low-power ambient rendering for the dial. Implement `AmbientModeSupport.AmbientCallback` on `WearMainActivity`, provide a dimmed + non-animating composable variant for ambient, cap state swaps to ~60s cadence while ambient. ~1 day of focused work. Current implementation keeps the screen on while the app is foregrounded via `FLAG_KEEP_SCREEN_ON`; AOD proper is the bigger lift.

### Avatar transitions — crossfade blend

`AvatarRuntime` recognizes the `{ blend: "crossfade", ms: N }` transition shape in the manifest (per `docs/avatars/formats.md`) but currently plays blend entries as an instant swap (`parseTransitions` skips `JSONObject` values). To implement: capture the outgoing final frame on state change, start the incoming state alongside it, run a Compose `animateFloatAsState` alpha blend for `ms` milliseconds before releasing the outgoing frame. Worth doing when a state explicitly benefits from a soft entry (e.g. `*->happy`).

## TTS

### `tts-streaming` — phase 1 + 2

Full design is pinned at `docs/tts/streaming.md`. Current `NodeRuntime.wearRelayChatStream` ships the blob path via `wearRelayTalkSpeak`; the TODO at that call site points at the design. Phase 1 (wire format parity) and phase 2 (tempfile-fed MediaPlayer for true time-to-first-audio under 400 ms) both remain to land.

## Avatars — artist workflow

### Atlas transitions authoring

`pnpm avatar:pack` honors `transitions` from a sibling `sprites-config.jsonc` but there's no authoring-side tool for a designer to preview blend transitions without running the full phone→watch round-trip. Mildly useful; not a blocker.

### Bundled plugin avatar spec

`docs/avatars/formats.md` is agent-focused. Bundled plugins that ship their own avatars (if that becomes a thing) will want an equivalent shape. Revisit when a plugin actually needs it.

## Docs — living-doc discipline

Every avatar/TTS surface change should update the corresponding living doc in the same commit. The sentinel list is in the root `AGENTS.md` → _Scoped Workflow Guides_. If a PR touches:

- `src/config/types.agents.ts` avatar types → update `docs/avatars/formats.md` field reference
- `src/config/zod-schema.core.ts` avatar shapes → update `docs/avatars/formats.md` format-selection table
- Phone `rewriteAvatars` prefetch branches → update _How the three formats are served_ section
- Watch `AvatarRuntime` → update the watch runtime section
- `scripts/avatars/*.mjs` → update _Tooling reference_
- `NodeRuntime.wearRelayChatStream` TTS call → update `docs/tts/streaming.md`

Reject PRs that miss the doc update — code wins in a disagreement, but the disagreement is a bug to fix, not a state to leave.

## Telemetry / observability

Nothing tracked today. Worth adding once a user-visible latency regression bites:

- Watch-side first-frame-render time per agent (wall-clock from `AvatarRuntime` construction → first `currentBitmap` emission)
- State-signal latency (phone publish → watch observe → runtime swap)
- TTS time-to-first-audio once streaming lands
