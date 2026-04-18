import { resolveAgentAvatar } from "../agents/identity-avatar.js";
import type { OpenClawConfig } from "../config/types.openclaw.js";
import type { AgentEventPayload } from "../infra/agent-events.js";
import { resolveAgentIdFromSessionKey } from "../routing/session-key.js";
import { parseAvatarMarkers, type AvatarMarker } from "./avatar-marker-parser.js";

/**
 * Live-stream splice for avatar-state signalling.
 *
 * Two signal sources converge on one `avatar.state.change` event shape:
 *
 * 1. **Model-driven** — `[avatar:<state>]` markers emitted in assistant text.
 *    Only the model knows tone (happy/sad/angry/curious/…); client must have
 *    been briefed via the `instruction` injection so the model speaks the
 *    protocol. We strip matching markers from visible text and surface them.
 *
 * 2. **Gateway-driven auto-emit** for two reserved state names:
 *    - `thinking` — fired on lifecycle.start when the agent's state list
 *      contains a "thinking" state. Gives the watch a visible "working on it"
 *      cue even if the model never emits a marker.
 *    - `<cfg.default>` — fired on lifecycle.end if we're not already there,
 *      so the watch resets to a resting expression after each run.
 *
 * Model-driven markers win when both fire: if the model emits a marker inside
 * an otherwise auto-managed run, we track `lastEmittedState` and avoid
 * emitting the default-on-end (since the model explicitly set the tone).
 *
 * The gateway stores assistant deltas as cumulative text each push. To avoid
 * re-emitting the same marker every delta, we track per-run how many markers
 * we've already surfaced and emit only the tail.
 */

export type AvatarMarkerBroadcastEvent = {
  runId: string;
  sessionKey?: string;
  agentId: string;
  state: string;
  /** File reference from the config (free-form string: path / URL / etc). */
  file: string;
};

export type AvatarMarkerBroadcastResult = {
  /**
   * Rewritten event payload where `data.delta` and `data.text` have markers
   * stripped. `null` means "no rewriting happened, pass the original through".
   */
  event: AgentEventPayload | null;
  /** Newly observed markers for this delta, in order. */
  events: AvatarMarkerBroadcastEvent[];
};

export type AvatarMarkerBroadcast = {
  process(
    evt: AgentEventPayload,
    ctx: { sessionKey?: string },
  ): AvatarMarkerBroadcastResult;
  clearRun(runId: string): void;
};

function isAssistantEvent(evt: AgentEventPayload): boolean {
  return evt.stream === "assistant";
}

function isLifecycleEvent(evt: AgentEventPayload): boolean {
  return evt.stream === "lifecycle";
}

function lifecyclePhase(evt: AgentEventPayload): string | null {
  const phase = evt.data?.phase;
  return typeof phase === "string" ? phase : null;
}

function stringOrEmpty(value: unknown): string {
  return typeof value === "string" ? value : "";
}

type RunState = {
  /** How many markers we've surfaced via the assistant-text path so far. */
  emittedMarkers: number;
  /** Last state we broadcast for this run (model marker or auto-emit). */
  lastEmittedState: string | null;
  /** Did we auto-emit "thinking" for this run at lifecycle.start? */
  autoThinkingEmitted: boolean;
};

function getOrInitRunState(map: Map<string, RunState>, runId: string): RunState {
  let s = map.get(runId);
  if (!s) {
    s = { emittedMarkers: 0, lastEmittedState: null, autoThinkingEmitted: false };
    map.set(runId, s);
  }
  return s;
}

export function createAvatarMarkerBroadcast(params: {
  getConfig: () => OpenClawConfig;
}): AvatarMarkerBroadcast {
  const runStateById = new Map<string, RunState>();

  function stripAndDiff(args: {
    evt: AgentEventPayload;
    agentId: string;
    sessionKey?: string;
    stateMap: Record<string, { file: string; description?: string }>;
    runState: RunState;
  }): AvatarMarkerBroadcastResult {
    const { evt, agentId, sessionKey, stateMap, runState } = args;
    const rawText = stringOrEmpty(evt.data?.text);
    const rawDelta = stringOrEmpty(evt.data?.delta);
    const parsedText = parseAvatarMarkers(rawText);
    const parsedDelta = parseAvatarMarkers(rawDelta);

    const previousCount = runState.emittedMarkers;
    const newMarkers: AvatarMarker[] = parsedText.markers.slice(previousCount);
    runState.emittedMarkers = parsedText.markers.length;

    const events: AvatarMarkerBroadcastEvent[] = [];
    for (const marker of newMarkers) {
      const entry = stateMap[marker.state];
      if (!entry) {
        // Unknown state — silently skip per spec.
        continue;
      }
      events.push({
        runId: evt.runId,
        sessionKey,
        agentId,
        state: marker.state,
        file: entry.file,
      });
      runState.lastEmittedState = marker.state;
    }

    if (parsedText.markers.length === 0 && parsedDelta.markers.length === 0) {
      return { event: null, events };
    }

    const rewrittenData: Record<string, unknown> = { ...evt.data };
    if (rawText) {
      rewrittenData.text = parsedText.cleanedText;
    }
    if (rawDelta) {
      rewrittenData.delta = parsedDelta.cleanedText;
    }
    return {
      event: { ...evt, data: rewrittenData },
      events,
    };
  }

  function resolveStatesForSession(sessionKey: string | undefined):
    | {
        agentId: string;
        stateMap: Record<string, { file: string; description?: string }>;
        defaultState: string;
      }
    | null {
    const agentId = resolveAgentIdFromSessionKey(sessionKey);
    if (!agentId) {
      return null;
    }
    const resolved = resolveAgentAvatar(params.getConfig(), agentId);
    if (resolved.kind !== "states") {
      return null;
    }
    return { agentId, stateMap: resolved.states, defaultState: resolved.default };
  }

  function handleLifecycleStart(
    evt: AgentEventPayload,
    sessionKey: string | undefined,
  ): AvatarMarkerBroadcastResult {
    const bundle = resolveStatesForSession(sessionKey);
    if (!bundle) {
      return { event: null, events: [] };
    }
    const thinkingEntry = bundle.stateMap["thinking"];
    if (!thinkingEntry) {
      // Operator hasn't opted in to auto-thinking by naming a state "thinking".
      return { event: null, events: [] };
    }
    const runState = getOrInitRunState(runStateById, evt.runId);
    if (runState.autoThinkingEmitted) {
      return { event: null, events: [] };
    }
    runState.autoThinkingEmitted = true;
    runState.lastEmittedState = "thinking";
    return {
      event: null,
      events: [
        {
          runId: evt.runId,
          sessionKey,
          agentId: bundle.agentId,
          state: "thinking",
          file: thinkingEntry.file,
        },
      ],
    };
  }

  function handleLifecycleEnd(
    evt: AgentEventPayload,
    sessionKey: string | undefined,
  ): AvatarMarkerBroadcastResult {
    const runState = runStateById.get(evt.runId);
    if (!runState || !runState.autoThinkingEmitted) {
      // Either an agent without a thinking state or a run we never touched.
      return { event: null, events: [] };
    }
    const bundle = resolveStatesForSession(sessionKey);
    if (!bundle) {
      return { event: null, events: [] };
    }
    if (runState.lastEmittedState === bundle.defaultState) {
      // Already at default — nothing to do.
      return { event: null, events: [] };
    }
    const defaultEntry = bundle.stateMap[bundle.defaultState];
    if (!defaultEntry) {
      return { event: null, events: [] };
    }
    runState.lastEmittedState = bundle.defaultState;
    return {
      event: null,
      events: [
        {
          runId: evt.runId,
          sessionKey,
          agentId: bundle.agentId,
          state: bundle.defaultState,
          file: defaultEntry.file,
        },
      ],
    };
  }

  return {
    process(evt, ctx) {
      const sessionKey = ctx.sessionKey ?? evt.sessionKey;
      if (isLifecycleEvent(evt)) {
        const phase = lifecyclePhase(evt);
        if (phase === "start") {
          return handleLifecycleStart(evt, sessionKey);
        }
        if (phase === "end") {
          return handleLifecycleEnd(evt, sessionKey);
        }
        return { event: null, events: [] };
      }
      if (!isAssistantEvent(evt)) {
        return { event: null, events: [] };
      }
      const bundle = resolveStatesForSession(sessionKey);
      if (!bundle) {
        return { event: null, events: [] };
      }
      const runState = getOrInitRunState(runStateById, evt.runId);
      return stripAndDiff({
        evt,
        agentId: bundle.agentId,
        sessionKey,
        stateMap: bundle.stateMap,
        runState,
      });
    },
    clearRun(runId) {
      runStateById.delete(runId);
    },
  };
}
